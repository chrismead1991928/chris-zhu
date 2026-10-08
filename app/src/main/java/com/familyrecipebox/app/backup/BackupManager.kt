package com.familyrecipebox.app.backup

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.familyrecipebox.app.data.RecipeCard
import com.familyrecipebox.app.data.RecipeDatabase
import com.familyrecipebox.app.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 导出结果 */
sealed interface BackupExportResult {
    data class Success(
        val recipeCount: Int,
        val imageCount: Int,
        val unreadableImages: Int,
        val healedImages: Int
    ) : BackupExportResult

    /** 一条菜谱都没有，没什么可导出的 */
    data object NothingToExport : BackupExportResult

    data class Failure(val cause: Throwable) : BackupExportResult
}

/** 解析结果：准备好待用户确认，或给出拒绝原因 */
sealed interface BackupPrepareResult {
    data class Ready(val pending: PendingBackup) : BackupPrepareResult
    data class Rejected(val reason: BackupRejectionReason) : BackupPrepareResult
    data class Failure(val cause: Throwable) : BackupPrepareResult
}

/** 导入方式 */
enum class ImportMode {
    /** 合并：本机已有的保留，备份里缺的补上，同一条以较新的为准 */
    MERGE,

    /** 整库替换：先清空本机菜谱，再完整恢复备份内容 */
    REPLACE
}

/** 导入结果 */
data class BackupImportResult(
    val mode: ImportMode,
    val added: Int,
    val updated: Int,
    val skipped: Int,
    val removed: Int,
    val imagesRestored: Int,
    val totalRecipes: Int
)

/**
 * 备份与恢复的总入口。
 *
 * 设计要点：
 * - **导入永远不会被免费版上限拦下**。恢复数据是「拿回自己的东西」，
 *   任何「因为你不是会员所以不让你恢复」的逻辑都会直接毁掉用户对应用的信任。
 *   免费版上限只约束日后新增菜谱。
 * - **解析与落库分离**。解析完先让用户看清备份里有什么、再决定怎么恢复。
 * - **先收编照片、再写数据库**；数据库事务失败时把刚写的照片删掉，不留孤儿。
 * - 只收编「本次真的会写入」的菜谱所用的照片，被跳过的菜谱不会留下垃圾文件。
 */
class BackupManager(
    context: Context,
    private val database: RecipeDatabase,
    private val imageStore: ImageStore
) {

    private val appContext = context.applicationContext
    private val dao = database.recipeCardDao()
    private val writer = RecipeBackupWriter(appContext, imageStore)
    private val reader = RecipeBackupReader(
        stagingRoot = File(appContext.cacheDir, STAGING_DIR_NAME)
    )

    /**
     * 导出全部菜谱到用户选定的位置。
     * 同时顺手把「仍是临时相册地址」的照片复制进私有目录并更新数据库，
     * 让这次导出成为一次真实的修复动作——否则那些照片下次可能就读不出来了。
     */
    suspend fun export(target: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        try {
            val recipes = dao.getAllIncludingDeleted().filterNot { it.isDeleted }
            if (recipes.isEmpty()) return@withContext BackupExportResult.NothingToExport

            val outcome = writer.write(target, recipes, appVersionName())
            val healed = healLegacyImages(outcome.healable)

            BackupExportResult.Success(
                recipeCount = outcome.recipeCount,
                imageCount = outcome.imageCount,
                unreadableImages = outcome.unreadableImages,
                healedImages = healed
            )
        } catch (e: Exception) {
            CrashReporter.record(appContext, "导出备份失败", e)
            BackupExportResult.Failure(e)
        }
    }

    /**
     * 解析备份文件，把内容暂存起来等用户确认。
     */
    suspend fun prepare(source: Uri): BackupPrepareResult = withContext(Dispatchers.IO) {
        try {
            val inspection = reader.inspect { appContext.contentResolver.openInputStream(source) }
            when (inspection) {
                is BackupInspection.Rejected -> BackupPrepareResult.Rejected(inspection.reason)
                is BackupInspection.Ready -> BackupPrepareResult.Ready(inspection.pending)
            }
        } catch (e: Exception) {
            CrashReporter.record(appContext, "解析备份文件失败", e)
            BackupPrepareResult.Failure(e)
        }
    }

    /**
     * 执行导入。
     *
     * @param pending 由 [prepare] 得到、尚未丢弃的解析结果
     * @param mode 合并或整库替换
     */
    suspend fun restore(pending: PendingBackup, mode: ImportMode): BackupImportResult =
        withContext(Dispatchers.IO) {
            val adoptedFiles = mutableListOf<String>()

            try {
                // ---- 第一步：判定要写什么（纯逻辑，见 planImport）----
                val localRecipes = dao.getAllOnce().filterNot { it.isDeleted }
                val plan = planImport(mode, pending.recipes, localRecipes)

                // ---- 第二步：只收编本次会用到的那几张照片 ----
                val adopted = HashMap<String, String>()
                val neededEntries = plan.all.mapNotNull { it.backup.imageEntry }.distinct()
                for (entry in neededEntries) {
                    val fileName = imageStore.adopt(pending.stagedImage(entry)) ?: continue
                    adopted[entry] = fileName
                    adoptedFiles += fileName
                }

                fun imageUriFor(item: PlannedRecipe): String? =
                    item.backup.imageEntry?.let { adopted[it] }?.let { imageStore.uriStringFor(it) }

                // ---- 第三步：写数据库（单事务，要么全成要么全不动）----
                val obsoleteImages = mutableListOf<String>()
                val result = database.withTransaction {
                    if (mode == ImportMode.REPLACE) {
                        localRecipes.forEach { recipe ->
                            imageStore.fileNameOf(recipe.imageUri)?.let { obsoleteImages += it }
                        }
                        dao.deleteAllHard()
                    }

                    val toInsert = plan.insert.map { it.toEntity(imageUriFor(it)) }
                    val toUpdate = plan.update.map { it.toEntity(imageUriFor(it)) }
                    if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
                    toUpdate.forEach { dao.update(it) }

                    BackupImportResult(
                        mode = mode,
                        added = toInsert.size,
                        updated = toUpdate.size,
                        skipped = plan.skipped,
                        removed = if (mode == ImportMode.REPLACE) localRecipes.size else 0,
                        imagesRestored = adopted.size,
                        totalRecipes = dao.count()
                    )
                }

                // ---- 第四步：清理被替换掉的旧照片 ----
                obsoleteImages.forEach { imageStore.delete(it) }

                result
            } catch (e: Exception) {
                // 数据库没写成功，那么刚收编进来的照片就是孤儿，收回去
                adoptedFiles.forEach { imageStore.delete(it) }
                CrashReporter.record(appContext, "导入备份失败", e)
                throw e
            } finally {
                pending.discard()
            }
        }

    /**
     * 只删掉确定不再被引用的私有照片。
     * 应用启动时调用一次即可覆盖绝大多数场景：编辑中途被系统杀掉、
     * 换图后没有保存等等遗留的垃圾文件。
     */
    suspend fun collectGarbage(minAgeMillis: Long = ORPHAN_MIN_AGE_MILLIS): Int =
        withContext(Dispatchers.IO) {
            try {
                val referenced = dao.getAllOnce()
                    .mapNotNull { imageStore.fileNameOf(it.imageUri) }
                    .toSet()
                imageStore.deleteUnreferenced(referenced, minAgeMillis)
            } catch (e: Exception) {
                Log.w(TAG, "清理孤儿照片失败", e)
                0
            }
        }

    /**
     * 把遗留的临时相册地址换成私有目录中的永久副本。
     * 任何一张失败都不影响其他张，也不影响导出本身已经成功这个事实。
     */
    private suspend fun healLegacyImages(candidates: List<HealCandidate>): Int {
        var healed = 0
        for (candidate in candidates) {
            try {
                val fileName = imageStore.importFromUri(Uri.parse(candidate.sourceUri)) ?: continue
                dao.updateImageUri(candidate.recipeId, imageStore.uriStringFor(fileName))
                healed++
            } catch (e: Exception) {
                Log.w(TAG, "疗愈临时图片地址失败：${candidate.sourceUri}", e)
            }
        }
        return healed
    }

    private fun appVersionName(): String = try {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    private companion object {
        private const val TAG = "BackupManager"
        private const val STAGING_DIR_NAME = "backup_staging"

        /** 孤儿照片的最小年龄：留出余量，避免删掉用户正在编辑、尚未保存的照片 */
        private const val ORPHAN_MIN_AGE_MILLIS = 10 * 60 * 1000L
    }
}
