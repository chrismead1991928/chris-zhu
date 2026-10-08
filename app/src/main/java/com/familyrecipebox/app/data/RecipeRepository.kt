package com.familyrecipebox.app.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 菜谱数据仓库：封装 Room 访问，便于 ViewModel 调用。
 *
 * 所有本地写入都会经过 [normalizeForLocalWrite]，统一维护同步元数据
 * （serverId / createdAt / lastModifiedAt / syncedAt / imageLocalOnly），
 * 保证云端同步能正确识别「哪些数据变更过、哪些图片只能留在本机」。
 */
class RecipeRepository(private val dao: RecipeCardDao) {

    fun getAllRecipes(): Flow<List<RecipeCard>> = dao.getAll()

    fun getRecipeById(id: Int): Flow<RecipeCard?> = dao.getById(id)

    /**
     * 新增菜谱：补齐 serverId、建档时间，并标记为「未同步」。
     */
    suspend fun insertRecipe(recipe: RecipeCard): Long {
        return dao.insert(normalizeForLocalWrite(recipe, isNew = true))
    }

    /**
     * 更新菜谱：保留 serverId 与已有的同步书签，只推进 lastModifiedAt。
     */
    suspend fun updateRecipe(recipe: RecipeCard) {
        dao.update(normalizeForLocalWrite(recipe, isNew = false))
    }

    /**
     * 软删除菜谱：标记删除并把同步书签清空，
     * 待删除记录同步到云端后再由 purgeSyncedDeletions() 物理清理。
     */
    suspend fun deleteRecipe(recipe: RecipeCard) {
        val deleted = recipe.copy(
            isDeleted = true,
            lastModifiedAt = System.currentTimeMillis(),
            syncedAt = null
        )
        dao.update(deleted)
    }

    suspend fun getMaxOrderIndex(): Int = dao.getMaxOrderIndex() ?: -1

    suspend fun getPendingUploads(): List<RecipeCard> = dao.getPendingUploads()

    suspend fun getPendingDeletions(): List<RecipeCard> = dao.getPendingDeletions()

    /**
     * 待上传的本地变更数量（含待同步的删除）
     */
    suspend fun pendingCount(): Int = dao.getPendingCount()

    suspend fun markSynced(serverId: String, syncedAt: Long) =
        dao.markSynced(serverId, syncedAt)

    /**
     * 重置同步书签，用于切换账号或强制全量重传
     */
    suspend fun resetSyncBookkeeping() = dao.resetSyncBookkeeping()

    suspend fun purgeSyncedDeletions() = dao.purgeSyncedDeletions()

    /**
     * 写入数据库前统一整理同步元数据
     */
    private fun normalizeForLocalWrite(recipe: RecipeCard, isNew: Boolean): RecipeCard {
        val now = System.currentTimeMillis()
        val imageUri = normalizeImageUri(recipe.imageUri)

        return recipe.copy(
            // serverId 是云端主键，缺失时必须补一个稳定 UUID，避免多条空值互相覆盖
            serverId = recipe.serverId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            imageUri = imageUri,
            imageLocalOnly = isDeviceLocalImageUri(imageUri),
            createdAt = recipe.createdAt.takeIf { it > 0 } ?: now,
            // 本地每次写入都推进修改时间，增量同步据此判断需要上传
            lastModifiedAt = now,
            // 分类必须先规范化：主界面只渲染 RECIPE_CATEGORIES 里的页签，
            // 写入一个不在集合里的分类会让这条菜谱在所有页签下都看不到，
            // 而导出（走全表查询、不看分类）又会把它带走——表现为「存了但看不见」
            category = normalizeRecipeCategory(recipe.category),
            // 新增记录尚未上传；更新记录保留原书签，由 lastModifiedAt 判断是否待同步
            syncedAt = if (isNew) null else recipe.syncedAt
        )
    }
}
