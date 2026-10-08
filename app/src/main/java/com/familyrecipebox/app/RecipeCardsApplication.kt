package com.familyrecipebox.app

import android.app.Application
import android.util.Log
import com.familyrecipebox.app.backup.BackupManager
import com.familyrecipebox.app.backup.ImageStore
import com.familyrecipebox.app.billing.BillingManager
import com.familyrecipebox.app.data.RecipeDatabase
import com.familyrecipebox.app.data.RecipeRepository
import com.familyrecipebox.app.util.BootTrace
import com.familyrecipebox.app.util.CrashReporter
import com.familyrecipebox.app.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 本应用是纯本地应用：菜谱只存在本机数据库中，不上传、不做账号体系。
 * 唯一的联网行为来自 Google Play Billing（仅在购买或查询已购时由 Play 商店进程完成），
 * 应用自身不发起任何网络请求。
 *
 * 数据安全靠两条腿：一是照片在选图时就复制进应用私有目录，
 * 不依赖任何会过期的外部授权；二是「备份与恢复」提供完整的导出与导入通道，
 * 让用户换机前能自己把数据带走。
 *
 * 启动期的原则：**唯一**的对外依赖是购买能力，它属于可选增值能力，
 * 初始化失败只能降级为「购买不可用」，绝不能连累应用启动——
 * 否则未安装 Google Play 服务的设备会直接打不开菜谱。
 */
class RecipeCardsApplication : Application() {

    lateinit var billingManager: BillingManager
        private set

    lateinit var database: RecipeDatabase
        private set

    lateinit var repository: RecipeRepository
        private set

    lateinit var imageStore: ImageStore
        private set

    lateinit var backupManager: BackupManager
        private set

    /** 后台杂务作用域：图片回收这类任务失败不应影响任何界面 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 必须先于其他一切：启动阶段追踪 + 全局异常捕获
        BootTrace.begin(this)
        CrashReporter.install(this)

        applyPersistedLocale()

        // 构造器内部已做异常保护，不会因为缺少 Google Play 服务而抛出
        billingManager = BillingManager.getInstance(this)
        BootTrace.stage("BillingManager")

        database = RecipeDatabase.getDatabase(this)
        BootTrace.stage("RecipeDatabase")

        imageStore = ImageStore(this)
        repository = RecipeRepository(database.recipeCardDao())
        backupManager = BackupManager(this, database, imageStore)
        BootTrace.stage("BackupManager")

        // 清理上次运行遗留的孤儿照片（换图后未保存、编辑中途被系统杀掉等）。
        // 放在启动后异步做，且带最小年龄门槛，不会碰到用户此刻正在编辑的图片。
        appScope.launch {
            backupManager.collectGarbage()
        }
    }

    private fun applyPersistedLocale() {
        try {
            LocaleHelper.applyPersistedLocale(this)
            BootTrace.stage("LocaleHelper")
        } catch (e: Exception) {
            // 部分定制系统在设置应用语言时可能抛异常，不应影响启动
            Log.w(TAG, "应用持久化的语言设置失败", e)
            BootTrace.failure("LocaleHelper", e)
        }
    }

    private companion object {
        private const val TAG = "RecipeCardsApp"
    }
}
