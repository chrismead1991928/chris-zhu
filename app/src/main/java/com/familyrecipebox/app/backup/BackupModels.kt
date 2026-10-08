package com.familyrecipebox.app.backup

/**
 * 备份文件里的数据结构。
 *
 * 这里刻意**不使用 Room 的 [com.familyrecipebox.app.data.RecipeCard]** 直接序列化，
 * 而是单独定义一套磁盘模型，原因是：
 * 1. 数据库实体带着同步元数据（syncedAt / isDeleted / imageLocalOnly）与设备相关字段
 *    （imageUri 是别的机器上的绝对路径，导入后完全无意义），这些都不该写进备份；
 * 2. 备份是长期存在的文件，可能被一年后的新版本读取。
 *    把磁盘格式与数据库表结构解耦，将来加列、改列都不会让旧备份变成废纸。
 *
 * 字段命名保持直观，万一用户自己解开 ZIP 也能看懂。
 */
data class BackupManifest(
    val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    val appId: String = BackupFormat.APP_ID,
    val appVersionName: String = "",
    /** 导出时刻，用于在恢复确认框里告诉用户「这份备份是什么时候的」 */
    val exportedAt: Long = 0L,
    val recipeCount: Int = 0,
    val imageCount: Int = 0
)

/**
 * 单条菜谱在备份中的形态。
 *
 * 注意两个图片字段的分工：
 * - [imageName] 指向应用内置的默认配图（如 tomato_egg），装在 APK 里，换机后依然有效；
 * - [imageEntry] 是用户在备份 ZIP 内的照片条目名（如 images/img-0001.jpg），
 *   为空表示这条菜谱在备份时没有可用照片。
 */
data class BackupRecipe(
    /** 稳定 UUID。导入时用它识别「这条菜谱本机已经有了」，所以必须跨设备保持不变 */
    val serverId: String = "",
    val title: String = "",
    val imageName: String? = null,
    val imageEntry: String? = null,
    val steps: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val category: String = "Other",
    val rating: Float = 0f,
    val isLiked: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = 0L,
    /** 合并导入时用它判断「本机那份新，还是备份里这份新」 */
    val lastModifiedAt: Long = 0L
)

/**
 * 已经解析完成、等待用户确认的导入任务。
 *
 * 之所以把「解析」和「写库」拆成两步：解析要先知道文件里到底有什么（多少条菜谱、多少张照片、
 * 什么时间导出的），才能让用户在选择「合并」还是「整库替换」之前看到真实信息。
 * 解析出来的照片先落在缓存目录里，用户取消时直接整份丢弃。
 *
 * @param stagedImageNames 备份内的照片条目名（形如 `images/img-0001.jpg`），
 *   与 [BackupRecipe.imageEntry] 是同一套命名，导入时用它把菜谱与照片对上。
 */
class PendingBackup(
    val manifest: BackupManifest,
    val recipes: List<BackupRecipe>,
    val stagedImageNames: Set<String>,
    private val stagingRoot: java.io.File
) {
    /**
     * 取得暂存的照片文件。
     * 传进来的可能是备份里的条目名（`images/img-0001.jpg`），
     * 这里统一只取文件名，避免路径穿越到暂存目录之外。
     */
    fun stagedImage(name: String): java.io.File = java.io.File(stagingRoot, java.io.File(name).name)

    /**
     * 丢弃暂存内容。无论用户取消还是导入完成都要调用，
     * 否则缓存目录会一直留着上一份备份的照片。
     */
    fun discard() {
        runCatching { stagingRoot.deleteRecursively() }
    }
}
