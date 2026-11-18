package app.ember.tv.data

import android.content.Context
import androidx.core.content.edit
import app.ember.tv.tracker.KeystoreSecretCipher
import app.ember.tv.tracker.SecretCipher
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automatic backups: the same contents as Settings > Back up, made on a schedule and kept privately
 * on this TV (the newest [KEEP]). They are encrypted with a key held in the Android Keystore, so they
 * restore on this TV only, and they go when Ember is uninstalled. A backup to keep elsewhere is still
 * made by hand.
 */
internal object AutoBackup {
    const val DIR = "auto-backups"
    const val KEEP = 3
    private const val NAME = "ember-auto-backup"
    private const val DAY = 86_400_000L
    val choices = listOf("off" to "Off", "daily" to "Every day", "weekly" to "Every week")
    /** Tests replace the Keystore, which Robolectric does not have. */
    internal var cipher: () -> SecretCipher = { KeystoreSecretCipher("ember_auto_backup_v1") }

    data class Saved(val file: File, val time: Long)

    fun interval(mode: String?): Long? = when (mode) { "daily" -> DAY; "weekly" -> 7 * DAY; else -> null }
    fun due(mode: String?, last: Long, now: Long): Boolean = interval(mode)?.let { last <= 0 || now - last !in 0 until it } ?: false

    private fun dir(context: Context) = File(context.filesDir, DIR)
    private fun prefs(context: Context) = context.getSharedPreferences("ember", Context.MODE_PRIVATE)

    fun mode(context: Context): String = prefs(context).read(PreferenceKeys.AUTO_BACKUP) ?: "weekly"
    fun lastAt(context: Context): Long = prefs(context).read(PreferenceKeys.AUTO_BACKUP_AT)
    fun lastError(context: Context): String? = prefs(context).read(PreferenceKeys.AUTO_BACKUP_ERROR)

    fun list(context: Context): List<Saved> = dir(context).listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("auto-") && it.name.endsWith(".bin") }
        .map { Saved(it, it.name.removePrefix("auto-").removeSuffix(".bin").toLongOrNull() ?: it.lastModified()) }
        .sortedByDescending { it.time }

    /** Runs when the schedule says so; true when a backup was made. */
    fun runIfDue(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        due(mode(context), lastAt(context), now) && runCatching { backUp(context, now) }.isSuccess

    /** Makes one now. A failure is remembered for Settings and thrown. */
    @Synchronized fun backUp(context: Context, now: Long = System.currentTimeMillis()): Saved {
        try {
            val plain = Backup.export(context, "")
            val sealed = cipher().encrypt(NAME, plain)
            val folder = dir(context).apply { mkdirs() }
            val target = File(folder, "auto-$now.bin")
            AtomicFile.write(target, sealed).getOrThrow()
            list(context).drop(KEEP).forEach { it.file.delete() }
            prefs(context).edit { putLong(PreferenceKeys.AUTO_BACKUP_AT.name, now); remove(PreferenceKeys.AUTO_BACKUP_ERROR.name) }
            return Saved(target, now)
        } catch (e: Exception) {
            prefs(context).edit { putString(PreferenceKeys.AUTO_BACKUP_ERROR.name, (e.message ?: e.javaClass.simpleName).take(300)) }
            throw e
        }
    }

    /** Checks one, like opening a backup file, without changing anything yet. */
    fun read(saved: Saved): Pair<JsonObject, Backup.Summary> =
        Backup.read(runCatching { cipher().decrypt(NAME, saved.file.readBytes()) }
            .getOrElse { throw Backup.BackupException("This automatic backup can't be opened on this TV.") }, "")

    fun label(saved: Saved): String = DisplayDates.dateTime(saved.time)
}
