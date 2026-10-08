package com.familyrecipebox.app.backup

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 读取备份的结果
 */
sealed interface BackupInspection {
    data class Ready(val pending: PendingBackup) : BackupInspection
    data class Rejected(val reason: BackupRejectionReason) : BackupInspection
}

/**
 * 拒绝接手一份文件的原因。每一种都对应一句明确的用户提示，
 * 不让用户面对「导入失败」这种无从下手的提示。
 */
enum class BackupRejectionReason {
    /** 不是备份文件（缺少 manifest / 结构完全对不上） */
    NOT_A_BACKUP,

    /** 由更新版本的应用导出，当前版本读不了 */
    NEWER_FORMAT,

    /** 文件本身完好，但里面一条菜谱都没有 */
    NO_RECIPES,

    /** 超出防御性上限，或文件已损坏 */
    TOO_LARGE
}

/**
 * 解析备份文件。
 *
 * 安全考虑：
 * - 用户可能选中任何文件（导入时放开筛选是为了避免「找不到我保存的备份」这种更常见的问题），
 *   所以这里必须假设输入是任意字节流，先校验结构、再校验版本号，都不通过就直接拒绝；
 * - 每个条目都有字节上限，并且累计上限也受控，防止精心构造的 ZIP 在解压时把存储撑爆；
 * - 图片条目名必须是纯文件名，挡掉 `../` 之类的路径穿越。
 *
 * 解析出的照片先落到缓存目录，不直接进私有图片目录：
 * 用户还没确认「合并 / 整库替换」之前，不应该在本机留下任何痕迹。
 *
 * 这里只依赖一个「打开输入流」的函数而不是 Uri，一方面职责更干净，
 * 另一方面让这套针对不可信输入的解析逻辑可以在纯 JVM 单元测试里跑，
 * 不必依赖真机或模拟器。
 */
class RecipeBackupReader(private val stagingRoot: File) {

    private val gson = Gson()

    /** 同步 IO。调用方需保证在工作线程执行 */
    fun inspect(openInput: () -> InputStream?): BackupInspection {
        // 上一份备份的暂存内容一律先清掉，避免新旧照片混在一起
        runCatching { stagingRoot.deleteRecursively() }

        var manifest: BackupManifest? = null
        var recipes: List<BackupRecipe>? = null
        var entryCount = 0
        var imageCount = 0
        var totalImageBytes = 0L

        try {
            val raw = openInput() ?: return reject(BackupRejectionReason.NOT_A_BACKUP)

            raw.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entryCount > BackupFormat.MAX_ENTRY_COUNT) {
                            return reject(BackupRejectionReason.TOO_LARGE)
                        }
                        if (entry.isDirectory) continue

                        when {
                            entry.name == BackupFormat.ENTRY_MANIFEST -> {
                                val text = readBounded(zip, BackupFormat.MAX_JSON_BYTES)
                                    ?: return reject(BackupRejectionReason.TOO_LARGE)
                                val parsed = runCatching {
                                    gson.fromJson(text, BackupManifest::class.java)
                                }.getOrNull()
                                if (parsed == null || !parsed.looksLikeOurs()) {
                                    return reject(BackupRejectionReason.NOT_A_BACKUP)
                                }
                                manifest = parsed
                            }

                            entry.name == BackupFormat.ENTRY_RECIPES -> {
                                val text = readBounded(zip, BackupFormat.MAX_JSON_BYTES)
                                    ?: return reject(BackupRejectionReason.TOO_LARGE)
                                val type = object : TypeToken<List<BackupRecipe>>() {}.type
                                val parsed = runCatching {
                                    gson.fromJson<List<BackupRecipe>>(text, type)
                                }.getOrNull() ?: return reject(BackupRejectionReason.NOT_A_BACKUP)
                                if (parsed.size > BackupFormat.MAX_RECIPE_COUNT) {
                                    return reject(BackupRejectionReason.TOO_LARGE)
                                }
                                recipes = parsed
                            }

                            entry.name.startsWith(BackupFormat.ENTRY_IMAGE_PREFIX) -> {
                                val fileName = entry.name
                                    .removePrefix(BackupFormat.ENTRY_IMAGE_PREFIX)
                                if (!isSafeStagedName(fileName)) {
                                    return reject(BackupRejectionReason.NOT_A_BACKUP)
                                }
                                imageCount++
                                if (imageCount > BackupFormat.MAX_IMAGE_COUNT) {
                                    return reject(BackupRejectionReason.TOO_LARGE)
                                }
                                val written = streamToFile(
                                    zip,
                                    File(stagingRoot, fileName),
                                    BackupFormat.MAX_IMAGE_BYTES
                                ) ?: return reject(BackupRejectionReason.TOO_LARGE)
                                totalImageBytes += written
                                if (totalImageBytes > BackupFormat.MAX_TOTAL_IMAGE_BYTES) {
                                    return reject(BackupRejectionReason.TOO_LARGE)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            // 文件损坏、被截断、根本不是 ZIP……都归到这里
            Log.w(TAG, "解析备份文件失败", e)
            return reject(BackupRejectionReason.NOT_A_BACKUP)
        }

        val validManifest = manifest ?: return reject(BackupRejectionReason.NOT_A_BACKUP)
        if (validManifest.formatVersion > BackupFormat.FORMAT_VERSION) {
            return reject(BackupRejectionReason.NEWER_FORMAT)
        }
        val validRecipes = recipes ?: return reject(BackupRejectionReason.NOT_A_BACKUP)
        if (validRecipes.isEmpty()) {
            return reject(BackupRejectionReason.NO_RECIPES)
        }

        // 只保留「确实落盘、且被某条菜谱引用」的暂存照片，其余一律清掉。
        // 这里刻意用备份里的原始条目名（images/img-0001.jpg）作为标识，
        // 与 BackupRecipe.imageEntry 保持同一套命名，导入时才能一一对上。
        // 反例：如果这里改成去掉目录后的文件名，导入侧按 imageEntry 查表就会全部落空，
        // 表现为「菜谱恢复了、照片全没了」。
        val stagedOnDisk = stagingRoot.listFiles()
            ?.filter { it.isFile }
            ?.associateBy { it.name }
            .orEmpty()

        val referenced = validRecipes.mapNotNull { it.imageEntry }.distinct()
        val usable = referenced.filter { stagedOnDisk.containsKey(File(it).name) }.toSet()

        val usedFileNames = usable.map { File(it).name }.toSet()
        (stagedOnDisk.keys - usedFileNames).forEach { orphan ->
            runCatching { File(stagingRoot, orphan).delete() }
        }

        return BackupInspection.Ready(
            PendingBackup(
                manifest = validManifest,
                recipes = validRecipes,
                stagedImageNames = usable,
                stagingRoot = stagingRoot
            )
        )
    }

    private fun reject(reason: BackupRejectionReason): BackupInspection.Rejected {
        runCatching { stagingRoot.deleteRecursively() }
        return BackupInspection.Rejected(reason)
    }

    private fun BackupManifest.looksLikeOurs(): Boolean =
        formatVersion > 0 &&
            (appId.isBlank() || appId == BackupFormat.APP_ID) &&
            recipeCount >= 0

    /** 读取整个条目，超过上限则返回 null（防止单个 JSON 条目撑爆内存） */
    private fun readBounded(input: InputStream, maxBytes: Long): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 把条目流式落到文件，返回字节数；超上限返回 null 并删除半截文件 */
    private fun streamToFile(input: InputStream, target: File, maxBytes: Long): Long? {
        target.parentFile?.mkdirs()
        var total = 0L
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    out.write(buffer, 0, read)
                }
            }
        } finally {
            if (total > maxBytes) runCatching { target.delete() }
        }
        // 空文件视为无效条目，但不算致命错误——只是这张照片没了
        if (total <= 0L) return 0L
        return total
    }

    private fun isSafeStagedName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\')

    private companion object {
        private const val TAG = "RecipeBackupReader"
    }
}
