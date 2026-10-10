package app.orbit.launcher.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why Orbit last stopped unexpectedly, so Settings can show it and
 * you can copy it into the chat. Nothing leaves the phone on its own.
 *
 * Kotlin crashes are written by an uncaught-exception handler; native crashes
 * and freezes (ANRs) come from Android's own exit records.
 */
class CrashLog(private val context: Context) {
    private val file = File(context.filesDir, "last_crash.txt")
    private val seen = context.getSharedPreferences("orbit_crash", Context.MODE_PRIVATE)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("${stamp(System.currentTimeMillis())} crash on ${thread.name}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
        runCatching { recordExitReasons() }
    }

    /** Android's record of native crashes and freezes since we last looked. */
    private fun recordExitReasons() {
        val am = context.getSystemService(ActivityManager::class.java)
        val last = seen.getLong(KEY_SEEN, 0L)
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .filter { it.timestamp > last }
        reasons.firstOrNull()?.let { seen.edit().putLong(KEY_SEEN, it.timestamp).apply() }
        val bad = reasons.firstOrNull { it.reason in REPORTED } ?: return
        // A Kotlin crash we already wrote ourselves is more useful than Android's summary.
        if (bad.reason == ApplicationExitInfo.REASON_CRASH && file.exists()) return
        val kind = when (bad.reason) {
            ApplicationExitInfo.REASON_ANR -> "freeze (ANR)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            else -> "crash"
        }
        val trace = runCatching {
            bad.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(60).joinToString("\n") }
        }.getOrNull().orEmpty()
        file.writeText("${stamp(bad.timestamp)} $kind: ${bad.description.orEmpty()}\n$trace")
    }

    fun read(): String? = file.takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun clear() {
        file.delete()
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private companion object {
        const val KEY_SEEN = "exit_seen"
        val REPORTED = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
        )
    }
}
