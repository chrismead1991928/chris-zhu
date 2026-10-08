package com.familyrecipebox.app.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(entities = [RecipeCard::class], version = 8, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RecipeDatabase : RoomDatabase() {

    abstract fun recipeCardDao(): RecipeCardDao

    companion object {
        @Volatile
        private var INSTANCE: RecipeDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN category TEXT NOT NULL DEFAULT 'Other'")
                db.execSQL("ALTER TABLE recipes ADD COLUMN rating REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recipes ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recipes ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Convert legacy Chinese category values to English for the international version
                db.execSQL("UPDATE recipes SET category = 'Meat' WHERE category = '荤菜'")
                db.execSQL("UPDATE recipes SET category = 'Vegetable' WHERE category = '素菜'")
                db.execSQL("UPDATE recipes SET category = 'Cold Dish' WHERE category = '凉菜'")
                db.execSQL("UPDATE recipes SET category = 'Other' WHERE category = '其他' OR category = '' OR category IS NULL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Map old international categories to the new western-style categories
                db.execSQL("UPDATE recipes SET category = 'Main' WHERE category = 'Meat'")
                db.execSQL("UPDATE recipes SET category = 'Side' WHERE category = 'Vegetable'")
                db.execSQL("UPDATE recipes SET category = 'Salad' WHERE category = 'Cold Dish'")
                db.execSQL("UPDATE recipes SET category = 'Dessert' WHERE category = 'Other' OR category = '' OR category IS NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 增加云同步所需字段
                db.execSQL("ALTER TABLE recipes ADD COLUMN serverId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE recipes ADD COLUMN lastModifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recipes ADD COLUMN syncedAt INTEGER")
                db.execSQL("ALTER TABLE recipes ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")

                // 为已有菜谱生成稳定的 serverId（v4 风格 UUID）
                db.execSQL("UPDATE recipes SET serverId = $SQL_RANDOM_UUID")
                db.execSQL("UPDATE recipes SET lastModifiedAt = createdAt")

                // 创建唯一索引
                db.execSQL("CREATE UNIQUE INDEX index_recipes_serverId ON recipes(serverId)")
            }
        }

        /**
         * v7：补齐同步元数据。
         * 6 -> 7 只做数据整理，不改变表结构之外的使用方式：
         * 1. 新增 imageLocalOnly，标记「只在本机有效」的图片地址
         * 2. 清理空字符串 / 空白的图片地址与 serverId
         * 3. 修正缺失或非法的时间戳，保证增量同步的游标可用
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN imageLocalOnly INTEGER NOT NULL DEFAULT 0")

                // 相册 / 相机选择的图片只在本机有效，标记出来避免同步到其他设备
                db.execSQL(
                    """
                    UPDATE recipes SET imageLocalOnly = 1
                    WHERE imageUri LIKE 'content://%' OR imageUri LIKE 'file://%'
                    """.trimIndent()
                )

                // 空字符串等价于「没有图片」，统一为 NULL
                db.execSQL(
                    "UPDATE recipes SET imageUri = NULL WHERE imageUri IS NOT NULL AND TRIM(imageUri) = ''"
                )

                // 历史数据可能残留空 serverId，缺失时补一个新的稳定 UUID
                db.execSQL(
                    """
                    UPDATE recipes SET serverId = $SQL_RANDOM_UUID
                    WHERE serverId IS NULL OR TRIM(serverId) = ''
                    """.trimIndent()
                )

                // 时间戳兜底：createdAt / lastModifiedAt 为 0 会让增量同步丢失变更
                db.execSQL(
                    """
                    UPDATE recipes SET createdAt = CAST(strftime('%s','now') AS INTEGER) * 1000
                    WHERE createdAt IS NULL OR createdAt <= 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE recipes SET lastModifiedAt = createdAt
                    WHERE lastModifiedAt IS NULL OR lastModifiedAt <= 0
                    """.trimIndent()
                )

                // 非法的同步书签会让菜谱永远处于「已同步」，重置为未同步
                db.execSQL(
                    "UPDATE recipes SET syncedAt = NULL WHERE syncedAt IS NOT NULL AND syncedAt <= 0"
                )
            }
        }

        /**
         * v8：修复「新建的菜谱在主页面上看不到」。
         *
         * 原因：新建菜谱的默认分类曾被写成 'Other'，而 'Other' 不属于任何一个页签
         * （Main / Side / Salad / Dessert），于是这些菜谱在所有页签下都不可见；
         * 导出走的是全表查询、不看分类，所以又能把它们带走——
         * 表现为「数据库里有、备份里有，就是界面上没有」这种最难排查的状况。
         *
         * 这里把所有非法分类归到 DEFAULT_RECIPE_CATEGORY（Main）。
         * 归到 Main 而不是历史上「未分类」用的 Dessert，是为了让这批菜谱落在
         * 主页面的默认页签上，打开就能看见。
         *
         * 写入侧的根治在 normalizeRecipeCategory()，由 RecipeRepository 统一调用。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE recipes SET category = 'Main'
                    WHERE category IS NULL OR TRIM(category) = ''
                       OR category NOT IN ('Main', 'Side', 'Salad', 'Dessert')
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): RecipeDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RecipeDatabase::class.java,
                    "recipe_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    // 只允许降级时重建，升级路径必须走上面的迁移，避免静默丢数据
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { database ->
                        INSTANCE = database
                        // 首次启动时写入示例菜谱。这一步失败（例如迁移异常）不应让应用崩溃，
                        // 否则用户只会看到闪退而得不到任何可定位的信息
                        scope.launch {
                            try {
                                val dao = database.recipeCardDao()
                                if (dao.count() == 0) {
                                    dao.insertAll(defaultRecipes())
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "写入默认菜谱失败", e)
                            }
                        }
                    }
            }
        }

        private const val TAG = "RecipeDatabase"

        /**
         * 用于初始化默认数据的独立作用域：单个任务失败不影响其他任务，
         * 也不会把异常抛到未捕获处理器导致进程退出
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * SQLite 侧生成 v4 风格 UUID 的表达式
         */
        private const val SQL_RANDOM_UUID =
            "lower(hex(randomblob(4))) || '-' || " +
                "lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-a' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "lower(hex(randomblob(6)))"
    }
}

/**
 * Preloaded sample recipes in English for the international version.
 * Western-style common dishes.
 */
private fun defaultRecipes(): List<RecipeCard> {
    val now = System.currentTimeMillis()
    return listOf(
        RecipeCard(
            title = "Spaghetti Carbonara",
            imageName = "spaghetti_carbonara",
            category = "Main",
            orderIndex = 0,
            createdAt = now,
            steps = listOf(
                "Cook spaghetti in salted boiling water until al dente.",
                "Fry diced pancetta until crispy and golden.",
                "Whisk eggs, grated Pecorino Romano, and black pepper in a bowl.",
                "Toss hot pasta with pancetta fat, remove from heat, then mix in the egg sauce."
            ),
            tips = listOf("Use the pasta cooking water to loosen the sauce; never scramble the eggs over direct heat.")
        ),
        RecipeCard(
            title = "Caesar Salad",
            imageName = "caesar_salad",
            category = "Salad",
            orderIndex = 1,
            createdAt = now,
            steps = listOf(
                "Chop romaine lettuce into bite-sized pieces.",
                "Make dressing with anchovy, garlic, lemon juice, egg yolk, olive oil, and Parmesan.",
                "Toss lettuce with dressing and top with croutons and shaved Parmesan.",
                "Season with freshly ground black pepper and serve chilled."
            ),
            tips = listOf("Dry the lettuce thoroughly so the dressing clings to every leaf.")
        ),
        RecipeCard(
            title = "Creamy Mashed Potatoes",
            imageName = "mashed_potatoes",
            category = "Side",
            orderIndex = 2,
            createdAt = now,
            steps = listOf(
                "Peel and cut potatoes into even chunks.",
                "Boil in salted water until fork-tender.",
                "Drain and mash while hot, then fold in warm milk and butter.",
                "Season with salt and white pepper to taste."
            ),
            tips = listOf("Use starchy potatoes like Russets for the fluffiest mash.")
        ),
        RecipeCard(
            title = "Fluffy Pancakes",
            imageName = "pancakes",
            category = "Dessert",
            orderIndex = 3,
            createdAt = now,
            steps = listOf(
                "Whisk flour, sugar, baking powder, and a pinch of salt.",
                "Mix in milk, egg, and melted butter until just combined.",
                "Cook spoonfuls on a lightly greased pan over medium heat.",
                "Flip when bubbles form on the surface and cook until golden."
            ),
            tips = listOf("Do not overmix the batter; a few lumps keep pancakes light and tender.")
        )
    )
}
