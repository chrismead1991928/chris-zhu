package com.familyrecipebox.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.familyrecipebox.app.R

/**
 * Stable internal category keys used for database storage and business logic.
 *
 * 规范列表本身定义在数据层（`data/RecipeCategories.kt`），这里只做转发，
 * 让 UI 层不必直接引用数据层的常量，同时保证全应用只有一份定义。
 */
val INTERNAL_CATEGORIES: List<String> = com.familyrecipebox.app.data.RECIPE_CATEGORIES

/**
 * Returns the localized display name for a category key.
 */
@Composable
fun categoryDisplayName(category: String): String {
    return when (category) {
        "Main" -> stringResource(R.string.category_main)
        "Side" -> stringResource(R.string.category_side)
        "Salad" -> stringResource(R.string.category_salad)
        "Dessert" -> stringResource(R.string.category_dessert)
        // Legacy values (safe fallback for old data)
        "Meat" -> stringResource(R.string.category_main)
        "Vegetable" -> stringResource(R.string.category_side)
        "Cold Dish" -> stringResource(R.string.category_salad)
        "Other" -> stringResource(R.string.category_dessert)
        "荤菜" -> stringResource(R.string.category_main)
        "素菜" -> stringResource(R.string.category_side)
        "凉菜" -> stringResource(R.string.category_salad)
        "其他" -> stringResource(R.string.category_dessert)
        else -> category
    }
}

/**
 * Non-Composable variant for ViewModels or other non-UI code.
 */
fun categoryDisplayName(context: Context, category: String): String {
    return when (category) {
        "Main" -> context.getString(R.string.category_main)
        "Side" -> context.getString(R.string.category_side)
        "Salad" -> context.getString(R.string.category_salad)
        "Dessert" -> context.getString(R.string.category_dessert)
        "Meat" -> context.getString(R.string.category_main)
        "Vegetable" -> context.getString(R.string.category_side)
        "Cold Dish" -> context.getString(R.string.category_salad)
        "Other" -> context.getString(R.string.category_dessert)
        "荤菜" -> context.getString(R.string.category_main)
        "素菜" -> context.getString(R.string.category_side)
        "凉菜" -> context.getString(R.string.category_salad)
        "其他" -> context.getString(R.string.category_dessert)
        else -> category
    }
}
