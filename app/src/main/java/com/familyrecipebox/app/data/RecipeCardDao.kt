package com.familyrecipebox.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeCardDao {

    @Query("SELECT * FROM recipes WHERE isDeleted = 0 ORDER BY orderIndex ASC, id ASC")
    fun getAll(): Flow<List<RecipeCard>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun getById(id: Int): Flow<RecipeCard?>

    @Query("SELECT * FROM recipes WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): RecipeCard?

    @Query("SELECT MAX(orderIndex) FROM recipes WHERE isDeleted = 0")
    suspend fun getMaxOrderIndex(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: RecipeCard): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipes: List<RecipeCard>)

    @Query("SELECT COUNT(*) FROM recipes WHERE isDeleted = 0")
    suspend fun count(): Int

    @Update
    suspend fun update(recipe: RecipeCard)

    @Delete
    suspend fun delete(recipe: RecipeCard)

    /**
     * 获取自上次同步以来有变更的本地菜谱（用于上传到服务器）
     */
    @Query("SELECT * FROM recipes WHERE (syncedAt IS NULL OR lastModifiedAt > syncedAt) AND isDeleted = 0")
    suspend fun getPendingUploads(): List<RecipeCard>

    /**
     * 获取所有未同步的删除记录（用于上传到服务器后清理本地）
     */
    @Query("SELECT * FROM recipes WHERE isDeleted = 1 AND (syncedAt IS NULL OR lastModifiedAt > syncedAt)")
    suspend fun getPendingDeletions(): List<RecipeCard>

    /**
     * 标记菜谱为已同步
     */
    @Query("UPDATE recipes SET syncedAt = :syncedAt WHERE serverId = :serverId")
    suspend fun markSynced(serverId: String, syncedAt: Long)

    /**
     * 硬删除已同步的软删除记录
     */
    @Query("DELETE FROM recipes WHERE isDeleted = 1 AND syncedAt IS NOT NULL AND syncedAt >= lastModifiedAt")
    suspend fun purgeSyncedDeletions()

    /**
     * 统计待上传的本地变更数量（含软删除记录），用于判断是否需要真正发起同步
     */
    @Query("SELECT COUNT(*) FROM recipes WHERE syncedAt IS NULL OR lastModifiedAt > syncedAt")
    suspend fun getPendingCount(): Int

    /**
     * 清空同步书签：把所有菜谱标记为未同步。
     * 用于切换账号或强制全量重传，避免沿用上一个账号的同步进度。
     */
    @Query("UPDATE recipes SET syncedAt = NULL")
    suspend fun resetSyncBookkeeping()

    /**
     * 获取所有菜谱（包含已删除），用于导出/全量备份
     */
    @Query("SELECT * FROM recipes ORDER BY orderIndex ASC, id ASC")
    suspend fun getAllIncludingDeleted(): List<RecipeCard>

    /**
     * 一次性取出全部菜谱（含软删除记录），供备份导入时建立 serverId 索引。
     * 与 Flow 版本的区别是不会持续观察，只在调用的一刻读一次。
     */
    @Query("SELECT * FROM recipes")
    suspend fun getAllOnce(): List<RecipeCard>

    /**
     * 物理清空菜谱表，仅用于「整库替换」式导入。
     * 这类导入会让本机数据被备份内容完全覆盖，因此必须由用户在界面上显式确认过。
     */
    @Query("DELETE FROM recipes")
    suspend fun deleteAllHard()

    /**
     * 把某个菜谱的图片地址指向应用私有目录中的文件。
     * 用于把遗留的临时相册地址「疗愈」成永久可用的私有文件引用。
     */
    @Query("UPDATE recipes SET imageUri = :imageUri, imageLocalOnly = 1 WHERE id = :id")
    suspend fun updateImageUri(id: Int, imageUri: String)
}
