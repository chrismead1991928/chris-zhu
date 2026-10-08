package com.familyrecipebox.app.backup

import com.familyrecipebox.app.data.RecipeCard
import com.familyrecipebox.app.data.normalizeRecipeCategory
import java.util.UUID

/**
 * 一条备份菜谱在本次导入中被判定的处理方式
 */
internal data class PlannedRecipe(
    /** 最终落库使用的 serverId。备份里缺失时会现补一个，保证跨设备唯一 */
    val serverId: String,
    val backup: BackupRecipe,
    /** null 表示新增；非 null 表示覆盖这条本机记录 */
    val localId: Int?
)

internal data class ImportPlan(
    val insert: List<PlannedRecipe>,
    val update: List<PlannedRecipe>,
    val skipped: Int,
    val duplicates: Int
) {
    /** 本次导入真正会写入的菜谱，用于决定需要收编哪些照片 */
    val all: List<PlannedRecipe> get() = insert + update
}

/**
 * 判定每条备份菜谱该怎么处理。
 *
 * 刻意抽成不含 IO、不含 Android 依赖的纯函数：
 * 这里只要判错一次就是**直接丢用户数据**，必须能被单元测试牢牢压住。
 * 数据库写入和文件操作都留在 [BackupManager] 里。
 */
internal fun planImport(
    mode: ImportMode,
    backupRecipes: List<BackupRecipe>,
    localRecipes: List<RecipeCard>
): ImportPlan {
    val localByServerId = localRecipes.associateBy { it.serverId }
    val seen = HashSet<String>()
    val insert = ArrayList<PlannedRecipe>()
    val update = ArrayList<PlannedRecipe>()
    var skipped = 0
    var duplicates = 0

    for (backup in backupRecipes) {
        val serverId = backup.serverId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        // 备份内部出现重复 serverId（例如手工改过文件）时只认第一条；
        // 否则两条互相覆盖，最终留下哪条取决于遍历顺序，结果不可预测
        if (!seen.add(serverId)) {
            duplicates++
            skipped++
            continue
        }

        // 整库替换模式下本机记录马上就要被清空，
        // 因此不能再用「本机那份更新」当理由跳过，否则刚删掉的数据就再也回不来了
        val local = if (mode == ImportMode.MERGE) localByServerId[serverId] else null

        when {
            local == null -> insert += PlannedRecipe(serverId, backup, null)
            backup.lastModifiedAt > local.lastModifiedAt ->
                update += PlannedRecipe(serverId, backup, local.id)
            else -> skipped++
        }
    }

    return ImportPlan(insert = insert, update = update, skipped = skipped, duplicates = duplicates)
}

/**
 * 备份条目 => 数据库实体。
 *
 * 几条刻意的取舍：
 * - [BackupRecipe.title] 原样保留。它是用户自己的数据，即使为空也不替用户编造标题。
 * - 未知分类统一交给 [normalizeRecipeCategory] 收敛：它回退到 Main（主界面默认页签），
 *   保证任何异常分类都不会让菜谱在界面上凭空消失。
 * - 同步元数据一律清空：本应用不做云同步，新写入的记录不存在「已同步」状态。
 */
internal fun PlannedRecipe.toEntity(imageUri: String?): RecipeCard {
    val created = backup.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    val modified = backup.lastModifiedAt.takeIf { it > 0 } ?: created

    return RecipeCard(
        id = localId ?: 0,
        serverId = serverId,
        title = backup.title,
        imageName = backup.imageName?.takeIf { it.isNotBlank() } ?: "tomato_egg",
        imageUri = imageUri,
        imageLocalOnly = imageUri != null,
        steps = backup.steps.filter { it.isNotBlank() },
        tips = backup.tips.filter { it.isNotBlank() },
        category = normalizeRecipeCategory(backup.category),
        rating = backup.rating.coerceIn(0f, 5f),
        isLiked = backup.isLiked,
        orderIndex = backup.orderIndex.coerceAtLeast(0),
        createdAt = created,
        lastModifiedAt = modified,
        syncedAt = null,
        isDeleted = false
    )
}
