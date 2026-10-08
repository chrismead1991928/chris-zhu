package com.familyrecipebox.app.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件的物理格式定义。
 *
 * 备份是一个普通 ZIP，内部结构固定为：
 * ```
 * manifest.json      格式版本、导出时间、数量统计（先被读取，用于快速判定文件是否合法）
 * recipes.json       菜谱数组，字段刻意与 Room 实体解耦，避免数据库改列时读不了旧备份
 * images/img-0001.jpg 用户照片原图，文件名由导出顺序决定，与菜谱通过 recipes.json 关联
 * ```
 *
 * 选 ZIP 而不是「JSON 里塞 base64」的原因：照片是二进制大文件，
 * base64 会让体积膨胀三分之一，而且必须整份读进内存；
 * ZIP 支持流式读写，几百张照片也不会撑爆内存。
 *
 * 所有上限的作用是防御损坏或伪造的文件（例如 ZIP 炸弹），
 * 数值都远高于正常使用量，不会误伤真实用户的备份。
 */
object BackupFormat {

    /** 当前写出的格式版本。读取时高于这个版本的备份会被拒绝，而不是猜着读 */
    const val FORMAT_VERSION = 1

    const val APP_ID = "com.familyrecipebox.app"

    const val MIME_TYPE = "application/zip"

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_RECIPES = "recipes.json"
    const val ENTRY_IMAGE_PREFIX = "images/"

    /** 单个 ZIP 内允许的最大条目数（正常备份是「菜谱数 + 照片数 + 2」） */
    const val MAX_ENTRY_COUNT = 8000

    /** 单个备份允许的最大菜谱数 */
    const val MAX_RECIPE_COUNT = 5000

    /** 单个备份允许的最大照片数 */
    const val MAX_IMAGE_COUNT = 2000

    /** 单个 JSON 条目的最大字节数 */
    const val MAX_JSON_BYTES = 64L * 1024 * 1024

    /** 单张照片的最大字节数 */
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    /** 所有照片解压后的总字节数上限 */
    const val MAX_TOTAL_IMAGE_BYTES = 800L * 1024 * 1024

    /**
     * 默认的备份文件名。用时间戳而非固定名，避免用户连续导出时互相覆盖，
     * 也方便在文件管理器里按时间找到「换机前那一份」。
     */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "FamilyRecipeBox-Backup-$stamp.zip"
    }
}
