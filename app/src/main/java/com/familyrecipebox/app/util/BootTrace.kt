package com.familyrecipebox.app.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 启动阶段追踪器。
 *
 * 用途：某些崩溃发生在 [android.content.ContentProvider] 初始化阶段，
 * 早于 `Application.onCreate`，此时全局异常处理器还来不及安装，堆栈拿不到。
 * 这里把启动过程按阶段写进一个私有文件，若「上一次启动没有走到最后」，
 * 这次启动就能看到那次卡在了哪一步，从而在无法连接 adb 的情况下定位问题。
 *
 * 文件流转：
 * - boot_trace.txt：本次启动的记录，[begin] 时清空重写
 * - boot_trace_previous.txt：上一次「未走完」的记录，[readIncompleteBoot] 读取后即删除
 */
object BootTrace {

    private const val TAG = "BootTrace"

    private const val CURRENT_FILE = "boot_trace.txt"
    private const val PREVIOUS_FILE = "boot_trace_previous.txt"

    /** 启动成功的标记，出现在记录末尾即代表流程走完 */
    private const val COMPLETE_MARK = "BOOT_COMPLETED"

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var active = false

    /**
     * 开始记录本次启动，应尽可能早地调用（Application.onCreate 第一步）。
     */
    fun begin(context: Context) {
        try {
            val dir = context.applicationContext.filesDir
            val current = File(dir, CURRENT_FILE)
            val previous = File(dir, PREVIOUS_FILE)

            // 上一次的记录若没走到最后，留一份快照供本次启动展示
            if (current.exists() && current.length() > 0) {
                val text = current.readText()
                if (text.contains(COMPLETE_MARK)) {
                    // 上次是正常启动，无需保留
                    previous.delete()
                } else {
                    current.copyTo(previous, overwrite = true)
                }
            }

            current.writeText("")
            currentFile = current
            active = true
            stage("Application.onCreate")
        } catch (e: Exception) {
            active = false
            Log.w(TAG, "启动追踪初始化失败", e)
        }
    }

    /**
     * 记录一个已走完的启动阶段
     */
    fun stage(name: String) {
        append("· $name")
    }

    /**
     * 记录某个阶段抛出的异常（主动捕获的失败，不依赖全局异常处理器）
     */
    fun failure(stage: String, error: Throwable) {
        append("! $stage 失败：${error.javaClass.name}: ${error.message}")
    }

    /**
     * 标记本次启动成功走完，应在首个界面显示后调用
     */
    fun complete() {
        append(COMPLETE_MARK)
    }

    /**
     * 读取上一次「未走完」的启动记录；上次启动正常时返回 null。
     * 读取后即删除，避免同一个问题反复提示。
     */
    fun readIncompleteBoot(context: Context): String? {
        return try {
            val previous = File(context.applicationContext.filesDir, PREVIOUS_FILE)
            if (!previous.exists()) return null

            val content = previous.readText()
            previous.delete()

            if (content.isBlank() || content.contains(COMPLETE_MARK)) null else content
        } catch (e: Exception) {
            Log.w(TAG, "读取启动追踪记录失败", e)
            null
        }
    }

    /**
     * 清理上一次启动的追踪记录
     */
    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, PREVIOUS_FILE).delete() }
    }

    private fun append(line: String) {
        if (!active) return
        try {
            currentFile?.appendText("$line\n")
        } catch (e: Exception) {
            Log.w(TAG, "写入启动追踪失败：$line", e)
        }
    }
}
