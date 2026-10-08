package com.familyrecipebox.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 菜谱卡片数据实体
 * @param serverId  用于云端同步的稳定 UUID（本地生成，终身不变）
 * @param imageName 默认本地图片的 drawable 名称（如 tomato_egg）
 * @param imageUri  用户从相册选择的图片 URI，非空时优先使用
 * @param imageLocalOnly 图片是否为本机私有资源（content:// 或 file://）。
 *        这类 URI 换一台设备就失效，所以同步时只上传 imageName，不上传 imageUri
 * @param steps     步骤列表
 * @param tips      要点列表
 * @param category  Category: Main, Side, Salad, Dessert
 * @param rating    星级评分 0..5，支持 0.5
 * @param isLiked   是否点赞
 * @param orderIndex 排序索引
 * @param createdAt 建档时间戳
 * @param lastModifiedAt 最后修改时间戳（用于增量同步冲突解决）
 * @param syncedAt  最后一次成功同步到服务器时的本地 lastModifiedAt；
 *        为空或小于 lastModifiedAt 表示还有本地变更待上传
 * @param isDeleted 软删除标记，删除后同步到云端再清理本地
 */
@Entity(
    tableName = "recipes",
    indices = [Index(value = ["serverId"], unique = true)]
)
data class RecipeCard(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val serverId: String = UUID.randomUUID().toString(),
    val title: String,
    val imageName: String,
    val imageUri: String? = null,
    val imageLocalOnly: Boolean = false,
    val steps: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val category: String = "Other",
    val rating: Float = 0f,
    val isLiked: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val isDeleted: Boolean = false
)
