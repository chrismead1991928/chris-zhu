package com.familyrecipebox.app.billing

import android.content.Context
import android.content.SharedPreferences

/**
 * 免费版/高级版限制管理器。
 *
 * - 免费版菜谱总数上限：20
 * - 高级版：一次买断后不再受限
 *
 * 这里保留 [PREMIUM_RECIPE_LIMIT] 作为内部安全上限，而不是用 Int.MAX_VALUE：
 * 目的是拦住在极端情况下（例如异常的批量导入）无限增长的写入，
 * 对个人菜谱应用而言 1000 条已远超正常使用量，因此在文案上按「无限」表述，
 * 不对用户暴露这个数字。
 */
class FreeTierLimiter(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun canAddRecipe(currentCount: Int, isPremium: Boolean): Boolean {
        val limit = if (isPremium) PREMIUM_RECIPE_LIMIT else FREE_RECIPE_LIMIT
        return currentCount < limit
    }

    fun getRecipeLimit(isPremium: Boolean): Int {
        return if (isPremium) PREMIUM_RECIPE_LIMIT else FREE_RECIPE_LIMIT
    }

    companion object {
        const val FREE_RECIPE_LIMIT = 20
        const val PREMIUM_RECIPE_LIMIT = 1000

        private const val PREFS_NAME = "free_tier_limiter"
    }
}
