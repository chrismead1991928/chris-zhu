package com.familyrecipebox.app.share

/**
 * 分享长图的暂存文件策略。
 *
 * 单独抽出来是因为这里全部是**纯计算**：文件名怎么生成、哪些旧文件该清理。
 * 这两件事看起来琐碎，但错了都不轻：
 * 文件名带非法字符会让写入直接失败；清理逻辑写错则可能删掉刚生成的那张，
 * 或者让缓存目录无限膨胀。所以它们不掺任何 IO，可以被单元测试压住。
 */
object ShareImagePolicy {

    /** 分享图片的暂存目录名（位于 cacheDir 下，与 file_paths.xml 中的路径一一对应） */
    const val DIRECTORY_NAME = "shared_cards"

    /** 缓存保留时长：超过这个时长的旧分享图会被清掉 */
    const val MAX_AGE_MILLIS = 60 * 60 * 1000L

    /** 缓存最多保留多少个分享图。给「保留时长」再加一道数量上限，防止短时间内连发几十次 */
    const val MAX_FILES = 12

    /** 文件名里标题部分的最大字符数，避免超长标题把路径顶爆 */
    private const val MAX_SLUG_LENGTH = 48

    /** 标题完全无法使用时（例如只有 emoji）的兜底主干 */
    private const val FALLBACK_SLUG = "recipe"

    /**
     * 生成分享图的文件名。
     *
     * 规则：`<时间戳>-<标题片段>.<扩展名>`。时间戳放前面有两个作用——
     * 同名标题不会互相覆盖，且按文件名排序即等于按时间排序。
     *
     * 标题会被压成一个安全片段：保留字母、数字与汉字/假名等文字字符，
     * 其余（含 `/`、`\`、`:`、空格、emoji）一律换成 `-` 并折叠去重。
     * 这一步是必须的：路径分隔符会让写入逃出目标目录，某些字符则直接导致创建文件失败。
     */
    fun fileNameFor(
        title: String,
        timestampMillis: Long,
        extension: String = "jpg"
    ): String {
        val safeExtension = extension
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(5)
            .ifBlank { "jpg" }
        return "$timestampMillis-${slugify(title)}.$safeExtension"
    }

    /**
     * 把标题压成安全片段。
     * 连续的不安全字符会被折叠成一个 `-`，首尾的 `-` 去掉，超长则截断。
     */
    fun slugify(title: String): String {
        val builder = StringBuilder()
        var pendingDash = false

        for (char in title.trim()) {
            if (char.isLetterOrDigit() || char == '_') {
                if (pendingDash && builder.isNotEmpty()) builder.append('-')
                pendingDash = false
                builder.append(char)
            } else if (builder.isNotEmpty()) {
                pendingDash = true
            }
        }

        val slug = builder.toString().take(MAX_SLUG_LENGTH).trim('-')
        return slug.ifBlank { FALLBACK_SLUG }
    }

    /**
     * 判断哪些暂存文件应当被清理，返回文件名列表。
     *
     * 两条独立规则叠加：
     * 1. 超过 [maxAgeMillis] 的旧文件一律清掉；
     * 2. 仍在有效期内的文件如果超过 [maxFiles] 个，从最旧的开始清到只剩上限。
     *
     * 年龄按「最后一次修改时间」而不是文件名里的时间戳判断：
     * 文件系统的时间戳才是事实，文件名只是给人看的标签。
     */
    fun filesToPrune(
        candidates: List<ShareImageFile>,
        nowMillis: Long,
        maxAgeMillis: Long = MAX_AGE_MILLIS,
        maxFiles: Int = MAX_FILES
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()

        val expired = candidates.filter { nowMillis - it.lastModifiedMillis > maxAgeMillis }
        val fresh = candidates
            .filterNot { candidate -> expired.any { it.name == candidate.name } }
            .sortedByDescending { it.lastModifiedMillis }

        val overflow = fresh.drop(maxFiles)

        return (expired.map { it.name } + overflow.map { it.name }).distinct()
    }

    data class ShareImageFile(val name: String, val lastModifiedMillis: Long)
}
