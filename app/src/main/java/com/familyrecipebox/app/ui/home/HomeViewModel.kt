package com.familyrecipebox.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.familyrecipebox.app.data.RecipeCard
import com.familyrecipebox.app.data.RecipeRepository
import com.familyrecipebox.app.ui.common.INTERNAL_CATEGORIES
import com.familyrecipebox.app.ui.common.categoryDisplayName
import com.familyrecipebox.app.util.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：观察菜谱列表并提供排序、删除、点赞、分类重命名操作
 */
class HomeViewModel(
    private val repository: RecipeRepository,
    context: Context
) : ViewModel() {

    private val appContext: Context = context.applicationContext

    private val prefs = appContext.getSharedPreferences("category_prefs", Context.MODE_PRIVATE)

    private val _categoryDisplayNames = MutableStateFlow(loadCategoryNames(context))
    val categoryDisplayNames: StateFlow<Map<String, String>> = _categoryDisplayNames

    val recipes: StateFlow<List<RecipeCard>> = repository.getAllRecipes()
        // 数据库读取失败（例如迁移异常）时降级为空列表并记录原因，
        // 而不是把异常抛到协程之外导致整个界面崩溃
        .catch { error ->
            CrashReporter.record(appContext, "读取菜谱列表失败", error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleLike(recipe: RecipeCard) {
        viewModelScope.launch {
            repository.updateRecipe(recipe.copy(isLiked = !recipe.isLiked))
        }
    }

    fun deleteRecipe(recipe: RecipeCard) {
        viewModelScope.launch {
            repository.deleteRecipe(recipe)
        }
    }

    /**
     * 在当前分类内移动菜谱到指定位置
     * @param category 当前分类
     * @param fromIndex 分类内原位置
     * @param toIndex 分类内目标位置（0..size）
     */
    fun moveRecipeInCategory(category: String, fromIndex: Int, toIndex: Int) {
        val list = recipes.value.filter { it.category == category }.toMutableList()
        if (fromIndex !in list.indices) return
        val moved = list.removeAt(fromIndex)
        val actualTo = toIndex.coerceIn(0, list.size)
        list.add(actualTo, moved)
        viewModelScope.launch {
            list.forEachIndexed { index, recipe ->
                repository.updateRecipe(recipe.copy(orderIndex = index))
            }
        }
    }

    /**
     * 重命名分类显示名（仅影响 UI，不影响数据库中的分类 key）
     */
    fun renameCategory(category: String, newName: String) {
        if (category !in INTERNAL_CATEGORIES) return
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        prefs.edit().putString(categoryNameKey(category), trimmed).apply()
        _categoryDisplayNames.value = _categoryDisplayNames.value.toMutableMap().apply {
            put(category, trimmed)
        }
    }

    /**
     * 重置所有分类名为默认本地化名称
     */
    fun resetCategoryNames(context: Context) {
        prefs.edit().clear().apply()
        _categoryDisplayNames.value = INTERNAL_CATEGORIES.associateWith {
            categoryDisplayName(context, it)
        }
    }

    private fun loadCategoryNames(context: Context): Map<String, String> {
        val defaults = INTERNAL_CATEGORIES.associateWith { categoryDisplayName(context, it) }
        val overrides = INTERNAL_CATEGORIES.mapNotNull { key ->
            prefs.getString(categoryNameKey(key), null)?.let { key to it }
        }.toMap()
        return defaults + overrides
    }

    private fun categoryNameKey(category: String) = "category_name_$category"

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val repository: RecipeRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                return HomeViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
