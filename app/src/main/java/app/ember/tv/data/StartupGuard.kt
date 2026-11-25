package app.ember.tv.data

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * #442: counts Ember's starts that never got as far as a working Home (a crash, or being closed
 * before it settled). After [LIMIT] in a row within [WINDOW_MS], Home opens the recovery page
 * instead, so a broken update can't leave the TV on a black screen. Kept in a small file, since
 * the recovery page runs in its own process.
 */
object StartupGuard {
    const val LIMIT = 3
    const val WINDOW_MS = 15 * 60_000L
    /** How long Home must stay up before its start counts as good. */
    const val SETTLE_MS = 5_000L

    data class State(val failures: Int = 0, val lastAt: Long = 0)

    /** The next state when a start begins at [now]: a start long after the last one begins a new run. */
    fun starting(state: State, now: Long): State =
        if (now - state.lastAt > WINDOW_MS) State(1, now) else State(state.failures + 1, now)

    /** True when the recovery page should open instead of Home. */
    fun shouldRecover(state: State, now: Long): Boolean = state.failures >= LIMIT && now - state.lastAt <= WINDOW_MS

    @Volatile private var countedThisProcess = false

    private fun file(c: Context) = File(c.filesDir, "startup_guard")
    private fun crashFile(c: Context) = File(c.filesDir, "startup_last_crash")

    fun read(c: Context): State = runCatching {
        val (n, at) = file(c).readText().trim().split(',')
        State(n.toInt(), at.toLong())
    }.getOrDefault(State())

    private fun write(c: Context, s: State) { runCatching { file(c).writeText("${s.failures},${s.lastAt}") } }

    fun needsRecovery(c: Context, now: Long = System.currentTimeMillis()) = shouldRecover(read(c), now)

    /** Home is starting: counted as failed until [healthy] says otherwise. */
    fun begin(c: Context, now: Long = System.currentTimeMillis()) {
        countedThisProcess = true
        write(c, starting(read(c), now))
    }

    /** Home stayed up: the count starts again. */
    fun healthy(c: Context) { runCatching { file(c).delete() } }

    /** A line about the last crash, for the recovery page. */
    fun lastCrash(c: Context): String? = runCatching { crashFile(c).readText().takeIf { it.isNotBlank() } }.getOrNull()

    fun recoveryIntent(c: Context): Intent = Intent().setClassName(c.packageName, "app.ember.tv.system.RecoveryActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /**
     * Notes each crash of Ember's main process (and counts it when Home hadn't started yet), and opens
     * the recovery page as Ember goes down once the limit is reached. Then Android handles it as before.
     */
    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val where = error.stackTrace.firstOrNull { it.className.startsWith("app.ember") } ?: error.stackTrace.firstOrNull()
                crashFile(app).writeText("${error.javaClass.simpleName}: ${CrashLog.redact(error.message.orEmpty()).take(160)}" +
                    (where?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""))
                if (!countedThisProcess) { countedThisProcess = true; write(app, starting(read(app), System.currentTimeMillis())) }
                if (needsRecovery(app)) app.startActivity(recoveryIntent(app))
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
