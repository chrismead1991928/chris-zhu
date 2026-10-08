package com.familyrecipebox.app.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 崩溃记录器。
 *
 * 把未捕获异常的堆栈写入应用私有目录，便于在无法连接 adb / Android Studio 时
 * 定位启动崩溃：下次启动时界面会读出这份记录并展示出来。
 *
 * 记录在被读取或忽略后会被清理，避免同一个问题反复提示。
 */
object CrashReporter {

    private const val TAG = "AppCrash"
    private const val FILE_NAME = "last_crash.txt"

    /**
     * 已被捕获、但会导致功能不可用的错误。
     *
     * 与崩溃分开存放：这类错误不会让进程退出，但同样需要暴露出来，
     * 否则只会表现为「界面空白」而查不到原因。
     */
    private const val ERROR_FILE_NAME = "last_error.txt"

    /** 单次记录的长度上限，避免超大堆栈拖慢崩溃时的写盘 */
    private const val MAX_CHARS = 8000

    /**
     * 安装全局未捕获异常处理器。应作为 Application.onCreate 的第一步调用，
     * 否则启动阶段更早出现的异常不会被记录下来。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "未捕获异常（线程 ${thread.name}）", throwable)
                File(appContext.filesDir, FILE_NAME)
                    .writeText(Log.getStackTraceString(throwable).take(MAX_CHARS))
            } catch (e: Exception) {
                // 崩溃处理阶段不能再抛异常，否则会掩盖原始问题
                Log.w(TAG, "写入崩溃记录失败", e)
            }
            // 交回系统默认处理，保持原有的崩溃行为
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 读取上一次崩溃记录；没有记录时返回 null
     */
    fun readLastCrash(context: Context): String? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            Log.w(TAG, "读取崩溃记录失败", e)
            null
        }
    }

    /**
     * 清理崩溃记录
     */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /**
     * 记录一个被捕获的错误。
     *
     * 用于「已经做了降级处理、但用户会感知到功能不可用」的场景，
     * 例如数据库读取失败导致列表为空。下次启动时会和崩溃记录一起展示。
     */
    fun record(context: Context, label: String, throwable: Throwable) {
        try {
            Log.e(TAG, label, throwable)
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            File(context.applicationContext.filesDir, ERROR_FILE_NAME)
                .writeText(
                    "[$stamp] $label\n${Log.getStackTraceString(throwable)}".take(MAX_CHARS)
                )
        } catch (e: Exception) {
            Log.w(TAG, "写入错误记录失败", e)
        }
    }

    /**
     * 读取最近一次被捕获的错误；没有记录时返回 null
     */
    fun readLastError(context: Context): String? {
        return try {
            val file = File(context.filesDir, ERROR_FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            Log.w(TAG, "读取错误记录失败", e)
            null
        }
    }

    /**
     * 清理错误记录
     */
    fun clearError(context: Context) {
        runCatching { File(context.filesDir, ERROR_FILE_NAME).delete() }
    }
}
