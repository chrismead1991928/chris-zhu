package com.familyrecipebox.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享图片文件策略的单元测试。
 *
 * 关注点只有一个：用户输入（菜谱标题）经过这套规则之后，
 * 一定会变成一个**能安全写进文件系统**的名字。
 * 一旦这里漏掉某种字符，分享功能就会在遇到特定标题时直接失败，
 * 而这种失败只在特定用户那里复现，最难排查。
 */
class ShareImagePolicyTest {

    private val now = 1_700_000_000_000L

    // ---- slugify ----

    @Test
    fun `普通标题保留可读性`() {
        assertEquals("Spaghetti-Carbonara", ShareImagePolicy.slugify("Spaghetti Carbonara"))
    }

    @Test
    fun `中文标题被完整保留`() {
        // 汉字属于文字字符，不该被当成非法字符清掉，否则中文用户拿到的全是兜底名
        assertEquals("番茄炒蛋", ShareImagePolicy.slugify("番茄炒蛋"))
    }

    @Test
    fun `路径分隔符被清除 无法逃出目标目录`() {
        val slug = ShareImagePolicy.slugify("../../etc/passwd")
        assertFalse(slug.contains('/'))
        assertFalse(slug.contains('\\'))
        assertFalse(slug.contains(".."))
    }

    @Test
    fun `文件系统保留字符被替换`() {
        val slug = ShareImagePolicy.slugify("a:b*c?d\"e<f>g|h")
        listOf(':', '*', '?', '"', '<', '>', '|').forEach { char ->
            assertFalse("不应包含 $char", slug.contains(char))
        }
        assertEquals("a-b-c-d-e-f-g-h", slug)
    }

    @Test
    fun `连续非法字符折叠成一个短横线`() {
        assertEquals("a-b", ShareImagePolicy.slugify("a   ///   b"))
    }

    @Test
    fun `首尾的短横线被去掉`() {
        assertEquals("abc", ShareImagePolicy.slugify("***abc***"))
        assertEquals("abc", ShareImagePolicy.slugify("   abc   "))
    }

    @Test
    fun `emoji 标题退化为兜底名而不是空名`() {
        // 空文件名会让 File 创建失败，必须兜底
        assertEquals("recipe", ShareImagePolicy.slugify("🍅🍳"))
        assertEquals("recipe", ShareImagePolicy.slugify(""))
        assertEquals("recipe", ShareImagePolicy.slugify("   "))
        assertEquals("recipe", ShareImagePolicy.slugify("!!!"))
    }

    @Test
    fun `超长标题被截断`() {
        val slug = ShareImagePolicy.slugify("a".repeat(500))
        assertEquals(48, slug.length)
    }

    @Test
    fun `混合中英与标点的标题保持可读`() {
        assertEquals(
            "妈妈的-Tomato-Egg-2024",
            ShareImagePolicy.slugify("妈妈的 Tomato Egg (2024)")
        )
    }

    // ---- fileNameFor ----

    @Test
    fun `文件名带时间戳前缀且扩展名固定`() {
        val name = ShareImagePolicy.fileNameFor("Spaghetti Carbonara", now)
        assertEquals("1700000000000-Spaghetti-Carbonara.jpg", name)
    }

    @Test
    fun `同名菜谱不会互相覆盖`() {
        // 时间戳前缀就是为了这个：同一道菜连发两次必须是两个文件
        val first = ShareImagePolicy.fileNameFor("番茄炒蛋", now)
        val second = ShareImagePolicy.fileNameFor("番茄炒蛋", now + 1)
        assertFalse(first == second)
    }

    @Test
    fun `扩展名被净化 无法注入路径片段`() {
        val name = ShareImagePolicy.fileNameFor("abc", now, extension = "../jpg")
        assertEquals("1700000000000-abc.jpg", name)
    }

    @Test
    fun `空扩展名回退到 jpg`() {
        assertTrue(ShareImagePolicy.fileNameFor("abc", now, extension = "").endsWith(".jpg"))
        assertTrue(ShareImagePolicy.fileNameFor("abc", now, extension = "  ").endsWith(".jpg"))
    }

    @Test
    fun `文件名里不含任何路径分隔符`() {
        listOf("a/b", "a\\b", "..", "con:", "   ").forEach { title ->
            val name = ShareImagePolicy.fileNameFor(title, now)
            assertFalse("标题 $title 生成了 $name", name.contains('/'))
            assertFalse("标题 $title 生成了 $name", name.contains('\\'))
        }
    }

    // ---- filesToPrune ----

    @Test
    fun `空列表无需清理`() {
        assertTrue(ShareImagePolicy.filesToPrune(emptyList(), now).isEmpty())
    }

    @Test
    fun `超过保留时长的文件被清理`() {
        val files = listOf(
            ShareImagePolicy.ShareImageFile("fresh.jpg", now - 1000L),
            ShareImagePolicy.ShareImageFile("stale.jpg", now - 2 * ShareImagePolicy.MAX_AGE_MILLIS)
        )

        val pruned = ShareImagePolicy.filesToPrune(files, now)

        assertEquals(listOf("stale.jpg"), pruned)
    }

    @Test
    fun `刚生成的分享图不会被清掉`() {
        // 这条最关键：清理逻辑写错就会把自己刚写好的图删掉，分享出去一片空白
        val files = listOf(ShareImagePolicy.ShareImageFile("just-now.jpg", now))

        assertTrue(ShareImagePolicy.filesToPrune(files, now).isEmpty())
    }

    @Test
    fun `数量超限时从最旧的开始清`() {
        val files = (1..5).map {
            ShareImagePolicy.ShareImageFile("img-$it.jpg", now - it * 1000L)
        }

        val pruned = ShareImagePolicy.filesToPrune(
            candidates = files,
            nowMillis = now,
            maxAgeMillis = ShareImagePolicy.MAX_AGE_MILLIS,
            maxFiles = 2
        )

        // 保留最新两个（img-1、img-2），清掉 img-3 / img-4 / img-5
        assertEquals(3, pruned.size)
        assertTrue(pruned.containsAll(listOf("img-3.jpg", "img-4.jpg", "img-5.jpg")))
        assertFalse(pruned.contains("img-1.jpg"))
        assertFalse(pruned.contains("img-2.jpg"))
    }

    @Test
    fun `过期与超限两条规则不会重复报告同一个文件`() {
        val files = listOf(
            ShareImagePolicy.ShareImageFile("stale.jpg", now - 5 * ShareImagePolicy.MAX_AGE_MILLIS),
            ShareImagePolicy.ShareImageFile("fresh-1.jpg", now - 10L),
            ShareImagePolicy.ShareImageFile("fresh-2.jpg", now - 20L)
        )

        val pruned = ShareImagePolicy.filesToPrune(
            candidates = files,
            nowMillis = now,
            maxAgeMillis = ShareImagePolicy.MAX_AGE_MILLIS,
            maxFiles = 1
        )

        assertEquals("清理结果不应有重复项", pruned.size, pruned.distinct().size)
        assertEquals(listOf("stale.jpg", "fresh-2.jpg"), pruned)
    }
}
