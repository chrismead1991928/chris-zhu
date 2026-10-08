package com.familyrecipebox.app.ui.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.familyrecipebox.app.backup.ImageStore
import com.familyrecipebox.app.data.DEFAULT_RECIPE_CATEGORY
import com.familyrecipebox.app.data.RecipeCard
import com.familyrecipebox.app.data.RecipeRepository
import com.familyrecipebox.app.data.normalizeRecipeCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 编辑界面 UI 状态
 */
data class EditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = false,
    val id: Int = 0,
    val serverId: String = "",
    val title: String = "",
    val imageName: String = "tomato_egg",
    val imageUri: String? = null,
    val steps: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val category: String = DEFAULT_RECIPE_CATEGORY,
    val rating: Float = 0f,
    val isLiked: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 编辑菜谱 ViewModel
 *
 * @param defaultCategory 新建菜谱时的默认分类。由主界面传入「用户当前正在浏览的页签」，
 *   这样保存后返回主界面，新菜谱就出现在他刚才看的那个页签里。
 *   若传入的值非法会回退到 [DEFAULT_RECIPE_CATEGORY]，绝不写入非法分类——
 *   主界面只渲染规范分类对应的页签，写入非法分类会让菜谱在界面上彻底消失。
 */
class EditViewModel(
    private val recipeId: Int,
    private val repository: RecipeRepository,
    private val imageStore: ImageStore,
    private val defaultCategory: String = DEFAULT_RECIPE_CATEGORY
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    /** 照片复制失败时置位，界面消费后置回 false，避免旋转屏幕后重复弹提示 */
    private val _imageSaveFailed = MutableStateFlow(false)
    val imageSaveFailed: StateFlow<Boolean> = _imageSaveFailed.asStateFlow()

    init {
        if (recipeId == 0) {
            _uiState.value = EditUiState(
                isLoading = false,
                isNew = true,
                title = "New Recipe",
                imageName = "tomato_egg",
                steps = listOf(""),
                tips = listOf(""),
                category = normalizeRecipeCategory(defaultCategory),
                createdAt = System.currentTimeMillis()
            )
        } else {
            viewModelScope.launch {
                repository.getRecipeById(recipeId).collect { recipe ->
                    recipe?.let { mapToUiState(it) }
                }
            }
        }
    }

    private fun mapToUiState(recipe: RecipeCard) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isNew = false,
            id = recipe.id,
            serverId = recipe.serverId,
            title = recipe.title,
            imageName = recipe.imageName,
            imageUri = recipe.imageUri,
            steps = recipe.steps,
            tips = recipe.tips,
            category = recipe.category,
            rating = recipe.rating,
            isLiked = recipe.isLiked,
            orderIndex = recipe.orderIndex,
            createdAt = if (recipe.createdAt == 0L) System.currentTimeMillis() else recipe.createdAt
        )
    }

    fun updateTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    /**
     * 用户从相册选定了照片。
     *
     * 关键动作是**立刻把图片复制进应用私有目录**，而不是只记住相册给的地址：
     * 那个地址的读取授权是临时的，重启设备或用户在相册里删掉原图之后就失效了。
     * 复制成功后界面只引用私有文件，从此不再依赖任何外部授权。
     */
    fun selectImage(uri: Uri) {
        viewModelScope.launch {
            val fileName = imageStore.importFromUri(uri)
            if (fileName == null) {
                _imageSaveFailed.value = true
                return@launch
            }
            _uiState.value = _uiState.value.copy(imageUri = imageStore.uriStringFor(fileName))
        }
    }

    fun consumeImageSaveFailure() {
        _imageSaveFailed.value = false
    }

    fun updateCategory(category: String) {
        _uiState.value = _uiState.value.copy(category = category)
    }

    fun updateRating(rating: Float) {
        _uiState.value = _uiState.value.copy(rating = rating.coerceIn(0f, 5f))
    }

    fun updateCreatedAt(time: Long) {
        _uiState.value = _uiState.value.copy(createdAt = time)
    }

    fun updateStep(index: Int, text: String) {
        val updated = _uiState.value.steps.toMutableList().apply { this[index] = text }
        _uiState.value = _uiState.value.copy(steps = updated)
    }

    fun addStep() {
        val updated = _uiState.value.steps + ""
        _uiState.value = _uiState.value.copy(steps = updated)
    }

    fun removeStep(index: Int) {
        val updated = _uiState.value.steps.toMutableList().apply { removeAt(index) }
        _uiState.value = _uiState.value.copy(steps = updated)
    }

    fun updateTip(index: Int, text: String) {
        val updated = _uiState.value.tips.toMutableList().apply { this[index] = text }
        _uiState.value = _uiState.value.copy(tips = updated)
    }

    fun addTip() {
        val updated = _uiState.value.tips + ""
        _uiState.value = _uiState.value.copy(tips = updated)
    }

    fun removeTip(index: Int) {
        val updated = _uiState.value.tips.toMutableList().apply { removeAt(index) }
        _uiState.value = _uiState.value.copy(tips = updated)
    }

    fun save() {
        val current = _uiState.value
        if (current.isLoading) return
        viewModelScope.launch {
            val recipe = RecipeCard(
                id = current.id,
                serverId = current.serverId,
                title = current.title,
                imageName = current.imageName,
                imageUri = current.imageUri,
                steps = current.steps,
                tips = current.tips,
                category = current.category,
                rating = current.rating,
                isLiked = current.isLiked,
                orderIndex = current.orderIndex,
                createdAt = current.createdAt
            )
            if (current.isNew || current.id == 0) {
                val maxOrder = repository.getMaxOrderIndex()
                repository.insertRecipe(recipe.copy(id = 0, serverId = "", orderIndex = maxOrder + 1))
            } else {
                repository.updateRecipe(recipe)
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val current = _uiState.value
        if (current.isLoading || current.isNew || current.id == 0) return
        viewModelScope.launch {
            val recipe = repository.getRecipeById(current.id).first() ?: return@launch
            repository.deleteRecipe(recipe)
            onDeleted()
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val recipeId: Int,
        private val repository: RecipeRepository,
        private val imageStore: ImageStore,
        private val defaultCategory: String = DEFAULT_RECIPE_CATEGORY
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EditViewModel::class.java)) {
                return EditViewModel(recipeId, repository, imageStore, defaultCategory) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
