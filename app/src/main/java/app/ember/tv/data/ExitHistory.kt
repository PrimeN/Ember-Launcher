package app.ember.tv.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * Why Ember last stopped, from Android's own exit history (#27). The crash log alone only sees
 * Kotlin/Java exceptions: not responding (ANR), native crashes in drivers or decoders, and the
 * low-memory killer leave it empty. Android 11+ keeps a short history per app, across updates.
 */
internal object ExitHistory {
    private const val PREFS = "ember_exit_history"
    private const val RECORDED_UNTIL = "recorded_until"
    /** On the first run, older exits are not worth reporting. */
    private const val FIRST_RUN_WINDOW_MS = 3 * 86_400_000L
    private const val MAX_PER_START = 10

    /** One exit as recorded; plain values so the wording can be tested without Android. */
    data class Exit(
        val reason: Int, val timestamp: Long, val description: String?, val pssKb: Long, val rssKb: Long,
        val importance: Int, val process: String, val status: Int, val trace: String?,
    )

    /** Queued on the crash log's writer so the Crash log screen always sees the result. */
    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val app = context.applicationContext
        CrashLog.later { runCatching { recordNow(app) }.onFailure { CrashLog.noteNow("Could not read Android's exit history", it) } }
    }

    @RequiresApi(30)
    private fun recordNow(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val since = prefs.getLong(RECORDED_UNTIL, now - FIRST_RUN_WINDOW_MS)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.timestamp > since && worthRecording(it.reason) }
            .sortedBy { it.timestamp }
            .takeLast(MAX_PER_START)
            .map { it.toExit() }
        // Oldest first, so the newest exit ends up at the top of the newest-first log.
        exits.forEach { CrashLog.entryNow(it.timestamp, title(it), details(it)) }
        val newest = exits.maxOfOrNull { it.timestamp } ?: since
        prefs.edit { putLong(RECORDED_UNTIL, maxOf(newest, since)) }
    }

    @RequiresApi(30)
    private fun ApplicationExitInfo.toExit() = Exit(reason, timestamp, description, pss, rss, importance, processName, status,
        runCatching { traceInputStream?.use { it.readBytes(limit = 512 * 1024) } }.getOrNull()?.let { bytes ->
            when (reason) {
                ApplicationExitInfo.REASON_ANR -> mainThread(String(bytes, Charsets.UTF_8))
                // A protobuf tombstone: its readable strings are the signal, abort message and frames.
                ApplicationExitInfo.REASON_CRASH_NATIVE -> readableStrings(bytes)
                else -> null
            }
        })

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    /** User stops, updates and Ember closing itself are normal; everything else is worth a line. */
    fun worthRecording(reason: Int) = reason !in setOf(
        1 /* EXIT_SELF */, 10 /* USER_REQUESTED */, 11 /* USER_STOPPED */, 8 /* PERMISSION_CHANGE */,
        12 /* DEPENDENCY_DIED */, 15 /* PACKAGE_STATE_CHANGE */, 16 /* PACKAGE_UPDATED */, 0 /* UNKNOWN */,
    )

    fun reasonName(reason: Int) = when (reason) {
        2 -> "Stopped by a signal"
        3 -> "Closed by Android to free memory"
        4 -> "Crashed (Kotlin/Java)"
        5 -> "Crashed (native code: graphics driver, decoder or player library)"
        6 -> "Not responding (ANR): Android closed Ember"
        7 -> "Could not start"
        9 -> "Closed by Android for using too many resources"
        13 -> "Closed by Android (other)"
        14 -> "Frozen and closed by Android"
        else -> "Stopped (reason $reason)"
    }

    /** Foreground or visible at exit: the person saw it happen. */
    fun onScreen(importance: Int) = importance <= 200

    fun title(e: Exit) = "Ember stopped: ${reasonName(e.reason)}"

    fun details(e: Exit) = buildString {
        append(if (onScreen(e.importance)) "Ember was on screen." else "Ember was in the background.")
        if (e.reason == 3 && !onScreen(e.importance)) append(" Closing a background app to free memory is normal.")
        append('\n')
        e.description?.takeIf { it.isNotBlank() }?.let { append("Android: ").append(it.trim()).append('\n') }
        if (e.reason == 2) append("Signal: ").append(e.status).append(if (e.status == 9) " (SIGKILL: usually the low-memory killer)" else "").append('\n')
        if (e.pssKb > 0 || e.rssKb > 0) append("Memory at exit: ").append(e.pssKb / 1024).append(" MB PSS, ").append(e.rssKb / 1024).append(" MB RSS\n")
        if (e.process.isNotBlank()) append("Process: ").append(e.process).append('\n')
        e.trace?.takeIf { it.isNotBlank() }?.let { append(if (e.reason == 6) "Main thread at the time:\n" else "Crash details:\n").append(it) }
    }.trimEnd()

    /** The "main" thread block of an ANR trace: what was blocking the screen. */
    fun mainThread(trace: String, maxLines: Int = 60): String {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return lines.take(maxLines).joinToString("\n")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].isBlank() } ?: lines.size
        return lines.subList(start, minOf(end, start + maxLines)).joinToString("\n")
    }

    /** Printable runs from a tombstone, without repeats: signal, abort message, libraries and functions. */
    fun readableStrings(bytes: ByteArray, minLength: Int = 6, maxLines: Int = 50): String {
        val found = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() { if (run.length >= minLength) found += run.toString().trim(); run.setLength(0) }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
            if (found.size >= maxLines) break
        }
        flush()
        return found.filter { it.isNotBlank() }.take(maxLines).joinToString("\n")
    }
}
