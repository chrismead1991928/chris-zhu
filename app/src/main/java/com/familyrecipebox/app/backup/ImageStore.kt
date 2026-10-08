package com.familyrecipebox.app.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 菜谱照片的私有仓库。
 *
 * 解决的问题：用户从相册选图时，如果只把 `content://` 地址存进数据库，
 * 那个读取授权是**临时**的——重启手机、用户在相册里删掉原图、
 * 或者把照片挪到别的相册，菜谱配图就会变成一片空白；
 * 换到新手机更是必然失效。
 *
 * 因此选图后立刻把图片**复制进应用私有目录**，数据库里只保存这个私有文件的地址。
 * 这样照片不再依赖任何外部授权，也能被完整地打进备份。
 */
class ImageStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)

    /**
     * 判断某个图片地址是否指向本仓库内的文件，是则返回文件名。
     *
     * 只认 `file://` 且父目录必须正好是私有图片目录，避免把相册的
     * `content://` 地址或外部 `file://` 路径误判成本仓库的文件。
     */
    fun fileNameOf(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = File(path)
        if (file.parentFile?.absolutePath != dir.absolutePath) return null
        return file.name.takeIf { isSafeName(it) }
    }

    /** 文件名 => 可交给图片加载库使用的地址 */
    fun uriStringFor(fileName: String): String = Uri.fromFile(File(dir, fileName)).toString()

    fun fileFor(fileName: String): File = File(dir, fileName)

    /**
     * 把外部图片（相册 / 相机 / 其他应用）复制进私有目录，返回新文件名。
     * 失败返回 null，调用方负责提示用户，绝不留下半截文件。
     */
    suspend fun importFromUri(source: Uri): String? = withContext(Dispatchers.IO) {
        try {
            if (!ensureDir()) return@withContext null
            val fileName = "${UUID.randomUUID()}.${extensionFor(source)}"
            val target = File(dir, fileName)
            val copied = appContext.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || target.length() <= 0L) {
                target.delete()
                return@withContext null
            }
            fileName
        } catch (e: Exception) {
            Log.w(TAG, "把照片复制到应用私有目录失败", e)
            null
        }
    }

    /**
     * 把备份里解压出来的照片收编进私有目录，返回新文件名。
     * 与 [importFromUri] 的区别是来源已经是本地文件，不需要任何读取授权。
     */
    suspend fun adopt(staged: File): String? = withContext(Dispatchers.IO) {
        try {
            if (!staged.isFile || staged.length() <= 0L) return@withContext null
            if (!ensureDir()) return@withContext null
            val extension = staged.extension.takeIf { it.isNotBlank() }?.lowercase() ?: "jpg"
            val fileName = "${UUID.randomUUID()}.$extension"
            staged.copyTo(File(dir, fileName), overwrite = true)
            fileName
        } catch (e: Exception) {
            Log.w(TAG, "收编备份中的照片失败", e)
            null
        }
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        runCatching { fileFor(fileName).delete() }
        Unit
    }

    /**
     * 清理不再被任何菜谱引用的孤儿照片。
     *
     * [minAgeMillis] 是一道安全阀：刚选好、还没来得及保存的照片此刻也是「未被引用」的，
     * 给一个最小年龄门槛，避免把用户正在编辑中的照片删掉。
     */
    suspend fun deleteUnreferenced(referenced: Set<String>, minAgeMillis: Long): Int =
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory) return@withContext 0
            val now = System.currentTimeMillis()
            var removed = 0
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name in referenced) return@forEach
                if (now - file.lastModified() < minAgeMillis) return@forEach
                if (file.delete()) removed++
            }
            removed
        }

    private fun ensureDir(): Boolean = dir.isDirectory || dir.mkdirs()

    /**
     * 推断扩展名：优先问内容提供者的 MIME 类型，其次看原始文件名，最后退回 jpg。
     * 扩展名只影响可读性，不影响能否显示（图片加载库按内容识别格式）。
     */
    private fun extensionFor(source: Uri): String {
        val fromMime = appContext.contentResolver.getType(source)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!fromMime.isNullOrBlank()) return fromMime.lowercase()

        val fromName = source.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
        if (fromName != null) return fromName.lowercase()

        return "jpg"
    }

    /** 只接受纯文件名，挡掉 `../` 这类越权路径 */
    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\')

    private companion object {
        private const val TAG = "ImageStore"
        private const val DIR_NAME = "recipe_images"
    }
}
