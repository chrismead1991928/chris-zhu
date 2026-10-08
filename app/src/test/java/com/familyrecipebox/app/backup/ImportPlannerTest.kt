package com.familyrecipebox.app.backup

import com.familyrecipebox.app.data.RecipeCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入判定的单元测试。
 *
 * 这里的每一个分支都直接决定「用户的哪些数据会被写、哪些会被丢」，
 * 因此除了正向路径，重点是那些**曾经踩过坑**的边界：
 * 整库替换时不能因为「本机那份更新」就跳过，
 * 否则刚清空的库再被跳过一次，数据就永久消失了。
 */
class ImportPlannerTest {

    private fun backup(
        serverId: String = "s1",
        title: String = "Carbonara",
        lastModifiedAt: Long = 100L,
        imageEntry: String? = null
    ) = BackupRecipe(
        serverId = serverId,
        title = title,
        imageName = "spaghetti_carbonara",
        imageEntry = imageEntry,
        lastModifiedAt = lastModifiedAt
    )

    private fun local(
        id: Int = 1,
        serverId: String = "s1",
        lastModifiedAt: Long = 100L
    ) = RecipeCard(
        id = id,
        serverId = serverId,
        title = "本机版本",
        imageName = "tomato_egg",
        lastModifiedAt = lastModifiedAt
    )

    @Test
    fun `合并时本机没有的菜谱走新增`() {
        val plan = planImport(ImportMode.MERGE, listOf(backup()), emptyList())

        assertEquals(1, plan.insert.size)
        assertEquals(0, plan.update.size)
        assertEquals(0, plan.skipped)
        assertNull("新增记录不应带上本机 id", plan.insert.first().localId)
    }

    @Test
    fun `合并时备份更旧就跳过 不动本机数据`() {
        val plan = planImport(
            ImportMode.MERGE,
            listOf(backup(lastModifiedAt = 100L)),
            listOf(local(lastModifiedAt = 200L))
        )

        assertEquals(0, plan.insert.size)
        assertEquals(0, plan.update.size)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `合并时备份更新则覆盖并沿用本机主键`() {
        val plan = planImport(
            ImportMode.MERGE,
            listOf(backup(title = "备份版本", lastModifiedAt = 300L)),
            listOf(local(id = 42, lastModifiedAt = 200L))
        )

        assertEquals(0, plan.insert.size)
        assertEquals(1, plan.update.size)
        val updated = plan.update.first()
        assertEquals("必须覆盖本机那条，而不是新增一条", 42, updated.localId)
        assertEquals("覆盖不是跳过", 0, plan.skipped)
    }

    @Test
    fun `整库替换时本机同一条更新也必须写入 否则数据永久丢失`() {
        val plan = planImport(
            ImportMode.REPLACE,
            listOf(backup(lastModifiedAt = 1L)),
            listOf(local(lastModifiedAt = 9_999L))
        )

        assertEquals("替换模式下必须全部重新写入", 1, plan.insert.size)
        assertEquals(0, plan.update.size)
        assertEquals("替换模式下没有「跳过」这个概念", 0, plan.skipped)
        assertNull(plan.insert.first().localId)
    }

    @Test
    fun `整库替换时本机所有记录都不参与比较`() {
        val plan = planImport(
            ImportMode.REPLACE,
            listOf(backup(serverId = "a"), backup(serverId = "b")),
            listOf(local(serverId = "a"), local(id = 2, serverId = "b"))
        )

        assertEquals(2, plan.insert.size)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `serverId 缺失时补发互不相同的 UUID`() {
        val plan = planImport(
            ImportMode.MERGE,
            listOf(backup(serverId = ""), backup(serverId = "  ")),
            emptyList()
        )

        // 两条空 serverId 会被补成不同 UUID，因此都算新增
        assertEquals(2, plan.insert.size)
        val ids = plan.insert.map { it.serverId }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals("补发的 UUID 不能互相重复", 2, ids.toSet().size)
    }

    @Test
    fun `备份内部重复 serverId 时只认第一条`() {
        val plan = planImport(
            ImportMode.MERGE,
            listOf(
                backup(serverId = "dup", title = "第一条"),
                backup(serverId = "dup", title = "第二条")
            ),
            emptyList()
        )

        assertEquals(1, plan.insert.size)
        assertEquals("第一条", plan.insert.first().backup.title)
        assertEquals(1, plan.duplicates)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `all 汇总了本次真正会写入的条目 用于决定收编哪些照片`() {
        val plan = planImport(
            ImportMode.MERGE,
            listOf(
                backup(serverId = "new", imageEntry = "images/a.jpg"),
                backup(serverId = "old", lastModifiedAt = 1L, imageEntry = "images/b.jpg")
            ),
            listOf(local(serverId = "old", lastModifiedAt = 500L))
        )

        assertEquals(1, plan.all.size)
        assertEquals(listOf("images/a.jpg"), plan.all.mapNotNull { it.backup.imageEntry })
    }

    @Test
    fun `未知分类收敛到合法页签 评分收敛到合法区间`() {
        val entity = PlannedRecipe(
            serverId = "s1",
            backup = BackupRecipe(category = "Unknown Category", rating = 9.9f),
            localId = null
        ).toEntity(imageUri = null)

        assertEquals("Main", entity.category)
        assertEquals(5.0f, entity.rating, 0.0001f)
        assertEquals("tomato_egg", entity.imageName)
        assertNull(entity.imageUri)
        assertFalse(entity.imageLocalOnly)
        assertNull("本地应用不存在已同步状态", entity.syncedAt)
        assertFalse(entity.isDeleted)
    }

    @Test
    fun `合法的分类原样保留 有照片时标记为本机私有`() {
        val entity = PlannedRecipe(
            serverId = "s1",
            backup = BackupRecipe(category = "Salad", rating = 4.5f, imageName = "caesar_salad"),
            localId = null
        ).toEntity(imageUri = "file:///data/recipe_images/x.jpg")

        assertEquals("Salad", entity.category)
        assertEquals(4.5f, entity.rating, 0.0001f)
        assertEquals("caesar_salad", entity.imageName)
        assertTrue(entity.imageLocalOnly)
        assertEquals("file:///data/recipe_images/x.jpg", entity.imageUri)
    }

    @Test
    fun `空白步骤与要点会被剔除`() {
        val entity = PlannedRecipe(
            serverId = "s1",
            backup = BackupRecipe(steps = listOf("第一步", "", "   "), tips = listOf("")),
            localId = null
        ).toEntity(imageUri = null)

        assertEquals(listOf("第一步"), entity.steps)
        assertTrue(entity.tips.isEmpty())
    }

    @Test
    fun `时间戳缺失时回退到当前时间且修改时间不早于建档时间`() {
        val entity = PlannedRecipe(
            serverId = "s1",
            backup = BackupRecipe(createdAt = 0L, lastModifiedAt = 0L),
            localId = null
        ).toEntity(imageUri = null)

        assertTrue(entity.createdAt > 0L)
        assertEquals(entity.createdAt, entity.lastModifiedAt)
    }

    @Test
    fun `标题保留原样 即使是空的也不替用户编造`() {
        val entity = PlannedRecipe(
            serverId = "s1",
            backup = BackupRecipe(title = ""),
            localId = null
        ).toEntity(imageUri = null)

        assertEquals("", entity.title)
        assertNotNull(entity.serverId)
    }
}
