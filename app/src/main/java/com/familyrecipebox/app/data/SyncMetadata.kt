package com.familyrecipebox.app.data

/**
 * 菜谱的同步状态（由同步元数据推导，不落库）
 */
enum class RecipeSyncStatus {
    /** 本地有未上传的新增或修改 */
    PENDING_UPLOAD,

    /** 本地已删除，等待把删除同步到云端 */
    PENDING_DELETE,

    /** 本地与服务端一致 */
    SYNCED
}

/**
 * 是否存在待上传的本地变更。
 *
 * 判断依据：syncedAt 为空（从未同步）或早于 lastModifiedAt（同步后又被修改）。
 */
val RecipeCard.isPendingSync: Boolean
    get() = isDeleted || syncedAt == null || lastModifiedAt > syncedAt

/**
 * 当前菜谱的同步状态
 */
val RecipeCard.syncStatus: RecipeSyncStatus
    get() = when {
        isDeleted -> RecipeSyncStatus.PENDING_DELETE
        isPendingSync -> RecipeSyncStatus.PENDING_UPLOAD
        else -> RecipeSyncStatus.SYNCED
    }

/**
 * 是否为「只在本机有效」的图片地址。
 *
 * 相册选择的图片由 MediaStore 授权，URI 形如 content://media/...；
 * 相机拍摄或内部存储则可能是 file:// 或绝对路径。这些地址换设备后无法访问，
 * 不能直接同步给其他设备。
 */
fun isDeviceLocalImageUri(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    val normalized = uri.trim()
    return normalized.startsWith("content://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("/")
}

/**
 * 是否为可跨设备访问的图片地址（http/https）
 */
fun isPortableImageUri(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    val normalized = uri.trim()
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

/**
 * 清理图片地址：空白字符串统一视为没有图片
 */
fun normalizeImageUri(uri: String?): String? = uri?.trim()?.takeIf { it.isNotEmpty() }
