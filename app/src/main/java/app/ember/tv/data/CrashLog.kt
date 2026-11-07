package app.ember.tv.data

import android.content.Context
import android.os.Build
import app.ember.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few crashes on this TV (files/crashes.txt) so they can be read in
 * Settings > About > Crash log and reported. Nothing is sent anywhere.
 */
object CrashLog {
    private const val MAX_BYTES = 48_000
    private const val SEPARATOR = "\n\n========================================\n\n"
    private var file: File? = null

    /** Called once from EmberApp: records uncaught crashes, then lets Android handle them as before. */
    fun install(context: Context) {
        file = File(context.filesDir, "crashes.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records a problem that did not crash the app (e.g. a playback error), with [what] happened
     * first, so it can be read in Settings > About > Crash log.
     */
    fun note(what: String, error: Throwable) {
        // Callers include the player's main thread: the file work happens on one background thread,
        // in order, so a note never blocks playback and concurrent notes cannot overwrite each other.
        val thread = Thread.currentThread()
        runCatching { writer.execute { runCatching { record(thread, error, what) } } }
    }

    private val lock = Any()
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ember-crash-log").apply { isDaemon = true } }
    }

    /** Waits for queued notes (tests and the crash log screen read after this). */
    internal fun flush() { runCatching { writer.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) } }

    private fun record(thread: Thread, error: Throwable, what: String? = null): Unit = synchronized(lock) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        write(header(System.currentTimeMillis()) + "  ·  thread " + thread.name + "\n" +
            (if (what != null) redact(what) + "\n" else "") + redact(trace).take(16_000))
    }

    private fun header(at: Long, recorded: Boolean = false) = buildString {
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at)))
        append(if (recorded) "  ·  recorded by Ember " else "  ·  Ember ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.BUILD_TYPE).append(", build ").append(BuildConfig.VERSION_CODE).append(")")
        append("  ·  Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
    }

    /** Newest first; old entries drop off the end. */
    private fun write(entry: String): Unit = synchronized(lock) {
        val f = file ?: return
        val old = if (f.exists()) f.readText() else ""
        // Replaced atomically (#76): Android may kill Ember mid-write, the moment the log matters most.
        AtomicFile.write(f, (entry + if (old.isNotEmpty()) SEPARATOR + old else "").take(MAX_BYTES)).getOrThrow()
    }

    /** Runs [block] on the crash log's own thread, in order with notes (exit history uses it). */
    internal fun later(block: () -> Unit) { runCatching { writer.execute { runCatching(block) } } }

    /** On the writer thread only: a note without queueing again. */
    internal fun noteNow(what: String, error: Throwable) = runCatching { record(Thread.currentThread(), error, what) }

    /**
     * On the writer thread only: an entry that is not an exception, dated when it happened. Android
     * reports it on the next start, so the version shown is the one that recorded it.
     */
    internal fun entryNow(at: Long, title: String, details: String) =
        runCatching { write(header(at, recorded = true) + "\n" + redact(title) + "\n" + redact(details).take(16_000)) }

    /** Links in errors can hold addon settings, debrid keys or tokens: keep only the site name. */
    fun redact(text: String): String = text
        // Any scheme (ftp, smb, sftp, rtsp, rtmp, webdav...): keep the host, never user:password@ or the path.
        .replace(linkPattern) { m -> "${m.groupValues[1]}://${m.groupValues[2].substringAfterLast('@')}/…" }
        .replace(Regex("""(?i)magnet:\?[^\s'"]*"""), "magnet:?…")
        .replace(Regex("""(?i)(api[_-]?key|token|password|passwd|secret|apikey|key)=([^&\s]+)"""), "$1=…")
    private val linkPattern = Regex("""([A-Za-z][A-Za-z0-9+.-]{1,15})://([^/\s?#'"]+)[^\s'"]*""")

    /** Recorded crashes, newest first. */
    fun entries(): List<String> {
        flush()
        return synchronized(lock) {
            val f = file ?: return emptyList()
            if (!f.exists()) return emptyList()
            runCatching { f.readText() }.getOrDefault("").split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun clear() { flush(); synchronized(lock) { file?.delete() } }
}
