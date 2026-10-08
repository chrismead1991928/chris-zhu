package com.familyrecipebox.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生成分享长图并交给系统分享面板。
 *
 * 流程：渲染长图 → 写入 cacheDir 下的暂存目录 → 通过 FileProvider 换成一个
 * 可外借的内容地址 → 构建 ACTION_SEND 意图。
 *
 * 这里刻意**不**直接 startActivity：启动界面属于调用方的职责（也必须在主线程），
 * 本类只负责「把图准备好并给出意图」。
 *
 * 为什么不用 MediaStore 或让用户先选保存位置：
 * 分享是「发出去」而不是「存下来」，中间插一个保存对话框会打断用户；
 * 走 FileProvider 既不需要任何存储权限，也不会在用户的相册里留下垃圾。
 */
class RecipeShareImageSharer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 生成分享图。
     *
     * @return 分享意图；任一步骤失败（含内存不足）返回 null，由调用方提示用户
     */
    suspend fun createShareIntent(
        data: ShareCardData,
        labels: ShareCardLabels,
        chooserTitle: String
    ): Intent? {
        val bitmap = withContext(Dispatchers.Default) {
            RecipeShareCardRenderer.render(appContext, data, labels)
        } ?: return null

        val uri = try {
            withContext(Dispatchers.IO) { writeToCache(bitmap, data.title) }
        } finally {
            // 位图已经落到文件，内存里这份可以立即释放
            bitmap.recycle()
        } ?: return null

        return buildShareIntent(uri, data.title, chooserTitle)
    }

    private fun writeToCache(bitmap: Bitmap, title: String): Uri? {
        return try {
            val dir = File(appContext.cacheDir, ShareImagePolicy.DIRECTORY_NAME)
            if (!dir.isDirectory && !dir.mkdirs()) return null

            pruneOldFiles(dir)

            val file = File(dir, ShareImagePolicy.fileNameFor(title, System.currentTimeMillis()))
            file.outputStream().use { output ->
                // JPEG 而非 PNG：长图高度可达数千像素，PNG 会有好几 MB，
                // 画质 95 对大面积色块与文字都足够，体积只有 PNG 的几分之一
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            if (file.length() <= 0L) {
                file.delete()
                return null
            }

            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.w(TAG, "写入分享图片失败", e)
            null
        }
    }

    /**
     * 清理过期的分享图。
     * 失败不影响本次分享：缓存目录由系统兜底回收，清理只是锦上添花。
     */
    private fun pruneOldFiles(dir: File) {
        try {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            val candidates = files.map {
                ShareImagePolicy.ShareImageFile(it.name, it.lastModified())
            }
            ShareImagePolicy.filesToPrune(candidates, System.currentTimeMillis())
                .forEach { name -> runCatching { File(dir, name).delete() } }
        } catch (e: Exception) {
            Log.w(TAG, "清理过期分享图失败", e)
        }
    }

    private fun buildShareIntent(uri: Uri, title: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            // 接收方必须被显式授予读取权限，否则会读到一张空白图。
            // 同时写进 clipData：部分应用只认剪贴板里的地址，两条路都给上才稳。
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        private const val TAG = "RecipeShareImageSharer"
        private const val MIME_TYPE = "image/jpeg"
        private const val JPEG_QUALITY = 95
    }
}
