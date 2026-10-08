package com.familyrecipebox.app.data

/**
 * 菜谱分类的**规范集合**，是分类定义的唯一来源。
 *
 * 放在 data 层而不是 UI 层，因为它同时被数据库、仓库、备份导入等多处使用；
 * 之前它定义在 UI 层，导致数据层反向依赖 UI 层，也埋下了「写入非法分类」的隐患。
 *
 * 这里的取值与数据库里实际存储的 key 一一对应，主界面底部页签就是按这个列表渲染的。
 */
val RECIPE_CATEGORIES: List<String> = listOf("Main", "Side", "Salad", "Dessert")

/**
 * 分类缺失或非法时的兜底值。
 *
 * 取 Main 而不是最后一个页签，是为了让「分类异常」的菜谱落到主界面的默认页签上，
 * 立刻可见——分类出错最糟糕的后果就是菜谱凭空消失。
 */
const val DEFAULT_RECIPE_CATEGORY: String = "Main"

/**
 * 把任意来源的分类值规范化成 [RECIPE_CATEGORIES] 中的一项。
 *
 * **这是防止「菜谱消失」的关键一道闸**：主界面只渲染 [RECIPE_CATEGORIES] 里的页签，
 * 任何不在这个集合里的分类值都会让菜谱在主页面上看不到（但仍会被导出到备份里，
 * 于是表现为「数据库里有、界面上没有」这种最难排查的状况）。
 *
 * 所有写入数据库的路径都必须先过这一层。
 */
fun normalizeRecipeCategory(category: String?): String =
    category?.takeIf { it in RECIPE_CATEGORIES } ?: DEFAULT_RECIPE_CATEGORY
