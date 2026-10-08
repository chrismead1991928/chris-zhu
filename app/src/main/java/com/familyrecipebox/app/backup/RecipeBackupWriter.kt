package com.familyrecipebox.app.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.familyrecipebox.app.data.RecipeCard
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出失败时用于给用户/日志提供线索的异常
 */
class BackupWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 备份写入结果
 *
 * @param healable 备份时发现「还是临时相册地址」的照片。这些地址此刻可读，
 *   顺手复制进私有目录可以把一次性的临时授权转成永久可用的私有文件，
 *   由调用方决定何时落库（本类只负责报告，不改数据库）。
 */
data class BackupWriteOutcome(
    val recipeCount: Int,
    val imageCount: Int,
    val unreadableImages: Int,
    val healable: List<HealCandidate>
)

/**
 * 待疗愈的照片：数据库主键 + 当前那个临时地址
 */
data class HealCandidate(val recipeId: Int, val sourceUri: String)

/**
 * 把菜谱写成一份 ZIP 备份。
 *
 * 分两趟做：
 * 1. **探测趟**：确定每条菜谱有没有可读的照片，并分配 ZIP 内的条目名。
 *    这一步只做存在性/可读性探测，不读图片内容，所以很快。
 * 2. **写入趟**：先写 manifest 和 recipes（此时条目名已经确定），再逐张把照片流进 ZIP。
 *
 * 必须先探测的原因：manifest 里要写明照片总数，而 ZIP 是顺序写入的，
 * 不能等照片都写完了再回头改开头的内容。
 */
class RecipeBackupWriter(
    context: Context,
    private val imageStore: ImageStore
) {

    private val appContext = context.applicationContext
    private val gson: Gson = Gson()

    /** 同步 IO。调用方需保证在工作线程执行 */
    fun write(target: Uri, recipes: List<RecipeCard>, appVersionName: String): BackupWriteOutcome {
        var imageSequence = 0
        var unreadable = 0
        val healable = ArrayList<HealCandidate>()

        // ---- 探测趟 ----
        val entries = recipes.map { recipe ->
            val uriString = recipe.imageUri
            if (uriString.isNullOrBlank()) return@map PlannedRecipe(recipe, null)

            val ownedName = imageStore.fileNameOf(uriString)
            if (ownedName != null) {
                // 已经是私有目录里的文件：存在且非空即可用，无需任何授权
                val file = imageStore.fileFor(ownedName)
                if (file.isFile && file.length() > 0L) {
                    imageSequence++
                    PlannedRecipe(recipe, ImageOrigin.Private(file), imageSequence)
                } else {
                    unreadable++
                    PlannedRecipe(recipe, null)
                }
            } else if (canRead(uriString)) {
                // 遗留的临时相册地址，此刻仍可读
                imageSequence++
                if (recipe.id != 0) healable += HealCandidate(recipe.id, uriString)
                PlannedRecipe(recipe, ImageOrigin.Temporary(uriString), imageSequence)
            } else {
                unreadable++
                PlannedRecipe(recipe, null)
            }
        }

        val imageCount = entries.count { it.origin != null }
        val manifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            appId = BackupFormat.APP_ID,
            appVersionName = appVersionName,
            exportedAt = System.currentTimeMillis(),
            recipeCount = entries.size,
            imageCount = imageCount
        )

        val recipesJson = gson.toJson(entries.map { it.toBackupRecipe() })
        val manifestJson = GsonBuilder().setPrettyPrinting().create().toJson(manifest)

        val raw = appContext.contentResolver.openOutputStream(target)
            ?: throw BackupWriteException("无法创建备份文件，请换一个保存位置")

        var writtenImages = 0
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            writeEntry(zip, BackupFormat.ENTRY_MANIFEST, manifestJson.toByteArray(Charsets.UTF_8))
            writeEntry(zip, BackupFormat.ENTRY_RECIPES, recipesJson.toByteArray(Charsets.UTF_8))

            for (item in entries) {
                val origin = item.origin ?: continue
                val entryName = item.entryName ?: continue
                // 单张照片读不出来不应该毁掉整份备份，跳过并让用户知道少了几张
                if (writeImage(zip, entryName, origin)) {
                    writtenImages++
                } else {
                    unreadable++
                }
            }
        }

        return BackupWriteOutcome(
            recipeCount = entries.size,
            imageCount = writtenImages,
            unreadableImages = unreadable,
            healable = healable
        )
    }

    private fun writeImage(zip: ZipOutputStream, entryName: String, origin: ImageOrigin): Boolean {
        var stream: InputStream? = null
        return try {
            stream = when (origin) {
                is ImageOrigin.Private -> origin.file.inputStream()
                is ImageOrigin.Temporary ->
                    appContext.contentResolver.openInputStream(Uri.parse(origin.uriString))
            }
            val opened = stream ?: return false
            opened.use { input ->
                zip.putNextEntry(ZipEntry(entryName))
                input.copyTo(zip)
                zip.closeEntry()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "写入照片失败：$entryName", e)
            // 条目可能已经开了一半，关掉它，避免污染 ZIP 结构
            runCatching { zip.closeEntry() }
            false
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 只探测能否打开，不读取内容 */
    private fun canRead(uriString: String): Boolean = try {
        appContext.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** 备份里的一个条目：菜谱 + 它的照片从哪来 */
    private data class PlannedRecipe(
        val recipe: RecipeCard,
        val origin: ImageOrigin?,
        private val sequence: Int = 0
    ) {
        val entryName: String? = origin?.let { origin ->
            val extension = when (origin) {
                is ImageOrigin.Private ->
                    origin.file.extension.takeIf { it.isNotBlank() }?.lowercase() ?: "jpg"
                is ImageOrigin.Temporary -> {
                    val fromName = Uri.parse(origin.uriString).lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.lowercase()
                        ?.takeIf { it.length in 1..5 }
                    fromName ?: "jpg"
                }
            }
            "${BackupFormat.ENTRY_IMAGE_PREFIX}img-%04d.%s".format(sequence, extension)
        }

        fun toBackupRecipe() = BackupRecipe(
            serverId = recipe.serverId,
            title = recipe.title,
            imageName = recipe.imageName,
            imageEntry = entryName,
            steps = recipe.steps,
            tips = recipe.tips,
            category = recipe.category,
            rating = recipe.rating,
            isLiked = recipe.isLiked,
            orderIndex = recipe.orderIndex,
            createdAt = recipe.createdAt,
            lastModifiedAt = recipe.lastModifiedAt
        )
    }

    private sealed interface ImageOrigin {
        /** 已经躺在应用私有目录里的文件，最可靠 */
        data class Private(val file: File) : ImageOrigin

        /** 遗留的相册临时地址，能读一次算一次 */
        data class Temporary(val uriString: String) : ImageOrigin
    }

    private companion object {
        private const val TAG = "RecipeBackupWriter"
    }
}
