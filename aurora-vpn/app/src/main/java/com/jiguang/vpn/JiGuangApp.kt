package com.jiguang.vpn

import android.app.Application
import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

/**
 * 应用入口:在进程启动时对 sing-box 内核做一次性初始化。
 * 官方约定 Libbox.setup() 必须在任何内核调用之前、且只调用一次;
 * 反复调用会破坏内核全局状态并可能导致原生崩溃。
 *
 * 同时安装未捕获异常记录:崩溃堆栈写入 filesDir/crash_last.txt,
 * 下次启动在首页展示,便于远程定位(真机无法取 logcat 时)。
 */
class JiGuangApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        ensureLibbox(this)
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    buildString {
                        append("thread=").append(thread.name).append('\n')
                        append(Log.getStackTraceString(throwable))
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "JiGuangApp"
        const val CRASH_FILE = "crash_last.txt"

        @Volatile
        private var libboxReady = false

        /** 读取上次崩溃记录(无则 null)。 */
        fun lastCrash(context: Context): String? {
            val f = File(context.filesDir, CRASH_FILE)
            if (!f.isFile) return null
            return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        fun clearCrash(context: Context) {
            runCatching { File(context.filesDir, CRASH_FILE).delete() }
        }

        /** 幂等初始化,可在任意入口安全调用。 */
        fun ensureLibbox(context: Context) {
            if (libboxReady) return
            synchronized(JiGuangApp::class.java) {
                if (libboxReady) return
                try {
                    runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag()) }
                    val opts = SetupOptions().apply {
                        basePath = context.filesDir.absolutePath
                        workingPath = context.filesDir.absolutePath
                        tempPath = context.cacheDir.absolutePath
                        fixAndroidStack = true
                        logMaxLines = 3000
                        debug = false
                        crashReportSource = "Application"
                        appVersion = runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                        }.getOrDefault("")
                    }
                    Libbox.setup(opts)
                    libboxReady = true
                } catch (t: Throwable) {
                    Log.e(TAG, "libbox setup failed", t)
                }
            }
        }
    }
}