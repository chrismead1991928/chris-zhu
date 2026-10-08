package com.familyrecipebox.app.backup

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份解析器的单元测试。
 *
 * 之所以重点测「拒绝」而不是「接受」：导入时为了让用户能找到自己转存过的备份文件，
 * 文件选择器不按类型过滤，因此解析器面对的是**任意字节流**。
 * 它必须在任何输入下都给出明确结论，而不是崩溃、卡死或写出越权文件。
 */
class RecipeBackupReaderTest {

    private val gson = Gson()

    private fun newStagingRoot(): File =
        File(
            System.getProperty("java.io.tmpdir"),
            "backup-test-${System.nanoTime()}"
        )

    /** 按写入器的约定拼一份完整备份 */
    private fun buildBackup(
        manifestJson: String? = null,
        recipesJson: String? = null,
        images: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val manifest = manifestJson ?: gson.toJson(
            BackupManifest(
                formatVersion = BackupFormat.FORMAT_VERSION,
                appId = BackupFormat.APP_ID,
                appVersionName = "1.0.1",
                exportedAt = 1_700_000_000_000L,
                recipeCount = 1,
                imageCount = images.size
            )
        )
        val recipes = recipesJson ?: gson.toJson(
            listOf(
                BackupRecipe(
                    serverId = "11111111-2222-4333-8444-555555555555",
                    title = "Spaghetti Carbonara",
                    imageName = "spaghetti_carbonara",
                    imageEntry = images.keys.firstOrNull(),
                    steps = listOf("Boil pasta", "Fry pancetta"),
                    tips = listOf("Keep pasta water"),
                    category = "Main",
                    rating = 4.5f,
                    isLiked = true,
                    orderIndex = 3,
                    createdAt = 1_600_000_000_000L,
                    lastModifiedAt = 1_600_000_000_000L
                )
            )
        )

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_RECIPES))
            zip.write(recipes.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            images.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun inspect(bytes: ByteArray, stagingRoot: File = newStagingRoot()): BackupInspection =
        RecipeBackupReader(stagingRoot).inspect { ByteArrayInputStream(bytes) }

    @Test
    fun `正常备份被接受并落地照片`() {
        val stagingRoot = newStagingRoot()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = buildBackup(images = mapOf("images/img-0001.jpg" to payload))

        val result = inspect(bytes, stagingRoot)

        assertTrue("应当解析成功，实际为 $result", result is BackupInspection.Ready)
        val pending = (result as BackupInspection.Ready).pending
        assertEquals(1, pending.recipes.size)
        assertEquals(1, pending.manifest.imageCount)
        assertEquals(
            "暂存目录里应出现这张照片",
            payload.size.toLong(),
            pending.stagedImage("images/img-0001.jpg").length()
        )
        // 条目名必须保持完整形态，导入侧是按这个键去查表的
        assertEquals(setOf("images/img-0001.jpg"), pending.stagedImageNames)
    }

    @Test
    fun `完全不是 ZIP 的文件被拒绝`() {
        val result = inspect("这不是备份，只是一段普通文本".toByteArray(Charsets.UTF_8))
        assertEquals(
            BackupRejectionReason.NOT_A_BACKUP,
            (result as BackupInspection.Rejected).reason
        )
    }

    @Test
    fun `缺少 manifest 的 ZIP 被拒绝`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_RECIPES))
            zip.write("[]".toByteArray())
            zip.closeEntry()
        }
        val result = inspect(out.toByteArray())
        assertEquals(
            BackupRejectionReason.NOT_A_BACKUP,
            (result as BackupInspection.Rejected).reason
        )
    }

    @Test
    fun `更新格式版本的备份被拒绝而不是猜着读`() {
        val manifest = gson.toJson(
            BackupManifest(
                formatVersion = BackupFormat.FORMAT_VERSION + 1,
                appId = BackupFormat.APP_ID
            )
        )
        val result = inspect(buildBackup(manifestJson = manifest))
        assertEquals(
            BackupRejectionReason.NEWER_FORMAT,
            (result as BackupInspection.Rejected).reason
        )
    }

    @Test
    fun `空菜谱列表被识别为无内容而不是损坏`() {
        val result = inspect(buildBackup(recipesJson = "[]"))
        assertEquals(
            BackupRejectionReason.NO_RECIPES,
            (result as BackupInspection.Rejected).reason
        )
    }

    @Test
    fun `照片条目名试图穿越目录时整份备份被拒绝`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
            zip.write(gson.toJson(BackupManifest()).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_RECIPES))
            zip.write(gson.toJson(listOf(BackupRecipe(serverId = "x"))).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("images/../../evil.jpg"))
            zip.write(byteArrayOf(9))
            zip.closeEntry()
        }

        val result = inspect(out.toByteArray())
        assertEquals(
            BackupRejectionReason.NOT_A_BACKUP,
            (result as BackupInspection.Rejected).reason
        )
    }

    @Test
    fun `被拒绝时暂存目录不留残余`() {
        val stagingRoot = newStagingRoot()
        File(stagingRoot, "img-0001.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        inspect("bad".toByteArray(), stagingRoot)

        assertFalse(
            "解析失败后应当把上一轮的暂存内容清掉",
            stagingRoot.exists() && stagingRoot.listFiles()?.isNotEmpty() == true
        )
    }

    @Test
    fun `没有菜谱引用的照片不会留在暂存目录`() {
        val stagingRoot = newStagingRoot()
        val bytes = buildBackup(
            images = mapOf(
                "images/img-0001.jpg" to byteArrayOf(1, 2, 3),
                "images/orphan.jpg" to byteArrayOf(4, 5, 6)
            )
        )
        // 只有 img-0001 被菜谱引用（buildBackup 取第一个 key 作为 imageEntry）
        val result = inspect(bytes, stagingRoot)

        val pending = (result as BackupInspection.Ready).pending
        assertEquals(setOf("images/img-0001.jpg"), pending.stagedImageNames)
        assertFalse(
            "未被引用的照片应被清掉，避免白占私有目录",
            File(stagingRoot, "orphan.jpg").exists()
        )
    }

    @Test
    fun `暂存文件读取不会逃出暂存目录`() {
        val stagingRoot = newStagingRoot()
        val pending = PendingBackup(
            manifest = BackupManifest(),
            recipes = emptyList(),
            stagedImageNames = emptySet(),
            stagingRoot = stagingRoot
        )

        val escaped = pending.stagedImage("images/../../../../etc/passwd")

        assertEquals(
            "路径穿越必须被压回暂存目录内部",
            stagingRoot.absolutePath,
            escaped.parentFile?.absolutePath
        )
        assertEquals("passwd", escaped.name)
    }

    @Test
    fun `清单与菜谱经 JSON 往返后字段不丢失`() {
        val original = listOf(
            BackupRecipe(
                serverId = "abc",
                title = "标题里有引号 \" 和逗号 , ",
                imageName = "tomato_egg",
                imageEntry = "images/img-0001.png",
                steps = listOf("第一步", "Step 2"),
                tips = listOf("小贴士"),
                category = "Dessert",
                rating = 3.5f,
                isLiked = true,
                orderIndex = 7,
                createdAt = 111L,
                lastModifiedAt = 222L
            )
        )

        val type = object : TypeToken<List<BackupRecipe>>() {}.type
        val restored = gson.fromJson<List<BackupRecipe>>(gson.toJson(original), type)

        assertEquals(original, restored)
    }

    @Test
    fun `默认文件名带日期且以 zip 结尾`() {
        val name = BackupFormat.suggestedFileName()
        assertTrue("实际为 $name", name.endsWith(".zip"))
        assertTrue("实际为 $name", Regex("""FamilyRecipeBox-Backup-\d{8}-\d{4}\.zip""").matches(name))
    }
}
