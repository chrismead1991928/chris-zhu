package com.familyrecipebox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类规范化的单元测试。
 *
 * 这组测试锁的是线上出现过的一个具体事故：
 * 新建的菜谱因为带上了 `"Other"` 这个不在页签集合里的分类，
 * 结果「数据库里有、备份能导出、但主界面所有页签下都看不到」。
 *
 * 由于这类问题不会报错、不会崩溃，只会让用户的菜谱凭空消失，
 * 因此这里把「规范化后的值必须落在页签集合内」当作硬不变量来测。
 */
class RecipeCategoriesTest {

    @Test
    fun `兜底分类必须是页签集合中的一项 否则兜底本身就会让菜谱消失`() {
        assertTrue(
            "DEFAULT_RECIPE_CATEGORY 必须落在 RECIPE_CATEGORIES 内，否则异常分类会命中一个不存在的页签",
            DEFAULT_RECIPE_CATEGORY in RECIPE_CATEGORIES
        )
    }

    @Test
    fun `合法分类原样保留`() {
        RECIPE_CATEGORIES.forEach { category ->
            assertEquals(category, normalizeRecipeCategory(category))
        }
    }

    @Test
    fun `历史遗留的 Other 分类会被收敛 不再让菜谱消失`() {
        // 这正是出问题的那个值：旧代码把它当作新建菜谱的默认分类
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("Other"))
    }

    @Test
    fun `空值与纯空白都会收敛到兜底分类`() {
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory(null))
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory(""))
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("   "))
    }

    @Test
    fun `大小写与未知取值一律收敛 不做模糊匹配`() {
        // 分类是内部存储 key，必须精确匹配；大小写不同就不是同一个页签
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("main"))
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("MAIN"))
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("Desserts"))
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("汤"))
        assertEquals(DEFAULT_RECIPE_CATEGORY, normalizeRecipeCategory("  Main  "))
    }

    @Test
    fun `任意输入规范化后必定落在页签集合内`() {
        val messy = listOf(
            null, "", " ", "Other", "other", "Unknown", "Main ", " Main",
            "Side", "Salad", "Dessert", "Soup", "0", "null", "undefined", "\n"
        )

        messy.forEach { input ->
            val normalized = normalizeRecipeCategory(input)
            assertTrue(
                "normalizeRecipeCategory(${input?.let { "\"$it\"" }}) 返回了 $normalized，" +
                    "它不在 RECIPE_CATEGORIES 内，会导致这条菜谱在界面上不可见",
                normalized in RECIPE_CATEGORIES
            )
        }
    }

    @Test
    fun `页签集合不包含重复项且非空`() {
        assertTrue(RECIPE_CATEGORIES.isNotEmpty())
        assertEquals(RECIPE_CATEGORIES.size, RECIPE_CATEGORIES.toSet().size)
    }
}
