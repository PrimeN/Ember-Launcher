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
    /** #448: how long Home may stay black before the recovery page opens by itself. */
    const val BLACK_MS = 30_000L
    /** #448: how often a black Home is looked at again. */
    const val RECHECK_MS = 3_000L

    data class State(val failures: Int = 0, val lastAt: Long = 0)

    /** The next state when a start begins at [now]: a start long after the last one begins a new run. */
    fun starting(state: State, now: Long): State =
        if (now - state.lastAt > WINDOW_MS) State(1, now) else State(state.failures + 1, now)

    /** True when the recovery page should open instead of Home. */
    fun shouldRecover(state: State, now: Long): Boolean = state.failures >= LIMIT && now - state.lastAt <= WINDOW_MS

    @Volatile private var countedThisProcess = false
    /** #448: Home began and hasn't shown itself working yet; only then does a crash count or open recovery. */
    @Volatile var homeStarting = false; private set
    /** #448: Ember's Application is still being created; a crash here breaks every start, Home's included. */
    @Volatile var appCreating = false

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
        homeStarting = true
        write(c, starting(read(c), now))
    }

    /** Home showed itself working (it drew something, or opened an app or a video): the count starts again. */
    fun healthy(c: Context) { homeStarting = false; runCatching { file(c).delete() } }

    /**
     * #448: true when a small copy of the screen is (nearly) all black: every pixel darker than
     * [threshold] of 255. Ember's Home always draws text, the clock or artwork, even in its darkest theme.
     */
    fun looksBlack(pixels: IntArray, threshold: Int = 18): Boolean = pixels.isNotEmpty() && pixels.all { p ->
        maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) < threshold
    }

    /** #448: the remote sequence that opens the recovery page from anywhere in Ember: Up Up Down Down Left Right Left Right. */
    val SECRET = listOf(19, 19, 20, 20, 21, 22, 21, 22)
    /** True when the last keys pressed end with [SECRET]. */
    fun secretEntered(recent: List<Int>): Boolean = recent.size >= SECRET.size && recent.takeLast(SECRET.size) == SECRET

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
                // #448: only a crash while Home is starting (or while Ember itself is being created)
                // counts; a crash behind another app, from a link or background work, doesn't, and
                // never opens the recovery page over what you're watching.
                if (appCreating && !countedThisProcess) { countedThisProcess = true; homeStarting = true; write(app, starting(read(app), System.currentTimeMillis())) }
                if (homeStarting && needsRecovery(app)) app.startActivity(recoveryIntent(app))
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
