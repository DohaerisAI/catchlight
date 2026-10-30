package com.phoneaf.ui

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Field crash reporting without any server: Kotlin crashes are written to a file as they
 * happen; native crashes, ANRs and out-of-memory kills come from Android's own record of why
 * the last run ended (API 30+). Both are shown once on the next launch, ready to copy.
 */
object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("${stamp(System.currentTimeMillis())} · thread ${thread.name}\n$sw")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The report from the previous run, if it ended badly; consumed on read. */
    fun takePrevious(context: Context): String? {
        val parts = mutableListOf<String>()
        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            parts += file.readText().take(6000)
            file.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) exitInfo(context)?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun exitInfo(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (info.timestamp <= prefs.getLong("seen", 0L)) return null
        prefs.edit().putLong("seen", info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "app not responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            else -> return null // normal exits, user swipes, updates: nothing to report
        }
        val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText().take(4000) } }.getOrNull()
        } else null
        return buildString {
            append("${stamp(info.timestamp)} · Android: $reason")
            info.description?.let { append(" · ").append(it) }
            append(" · memory ${info.pss / 1024} MB")
            trace?.let { append("\n").append(it) }
        }
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
