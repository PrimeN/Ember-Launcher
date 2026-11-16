package app.ember.tv.state

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import app.ember.tv.data.Backup
import app.ember.tv.data.AutoBackup
import app.ember.tv.data.PreferenceKeys
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Settings > Back up and Restore. Split out of HomeViewModel. */
class BackupState(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    var backupBusy by mutableStateOf(false); private set
    var backupMessage by mutableStateOf<String?>(null); private set
    var backupFiles by mutableStateOf<List<Backup.Found>>(emptyList()); private set
    /** A checked backup waiting for the user to confirm the restore. */
    var pendingRestore by mutableStateOf<Pair<JsonObject, Backup.Summary>?>(null); private set
    /** Set after a restore; Settings then restarts the app so everything reloads. */
    var restartNeeded by mutableStateOf(false); private set

    val backupPickerAvailable: Boolean by lazy { Backup.pickerAvailable(app) }

    // ---- automatic backups kept on this TV ----
    internal var autoBackups by mutableStateOf<List<AutoBackup.Saved>>(emptyList()); private set
    var autoBackupMode by mutableStateOf(AutoBackup.mode(app)); private set
    var autoBackupError by mutableStateOf<String?>(null); private set

    fun refreshAutoBackups() {
        scope.launch {
            autoBackups = withContext(Dispatchers.IO) { AutoBackup.list(app) }
            autoBackupError = withContext(Dispatchers.IO) { AutoBackup.lastError(app) }
        }
    }

    fun setAutoBackupMode(mode: String) {
        if (AutoBackup.choices.none { it.first == mode }) return
        app.getSharedPreferences("ember", android.content.Context.MODE_PRIVATE).edit()
            .putString(PreferenceKeys.AUTO_BACKUP.name, mode).apply()
        autoBackupMode = mode
    }

    fun autoBackupNow() = backupJob {
        withContext(Dispatchers.IO) { AutoBackup.backUp(app) }
        refreshAutoBackups()
        "Automatic backup made."
    }

    internal fun openAutoBackup(saved: AutoBackup.Saved) = backupJob {
        pendingRestore = withContext(Dispatchers.IO) { AutoBackup.read(saved) }
        null
    }

    fun refreshBackupFiles() {
        scope.launch {
            backupFiles = withContext(Dispatchers.IO) { Backup.listDownloads(app) }
        }
    }

    private fun backupJob(work: suspend () -> String?) {
        if (backupBusy) return
        backupBusy = true
        backupMessage = null
        scope.launch {
            backupMessage = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Backup.BackupException) {
                e.message
            } catch (e: Exception) {
                "Something went wrong: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                backupBusy = false
            }
        }
    }

    fun saveBackupToDownloads(passphrase: String) = backupJob {
        val where = withContext(Dispatchers.IO) {
            Backup.saveToDownloads(app, Backup.fileName(), Backup.export(app, passphrase))
        }
        refreshBackupFiles()
        "Saved to $where."
    }

    fun saveBackupTo(uri: Uri, passphrase: String) = backupJob {
        withContext(Dispatchers.IO) {
            val bytes = Backup.export(app, passphrase)
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw Backup.BackupException("Could not write the file.")
        }
        "Backup saved."
    }

    fun openBackup(uri: Uri, passphrase: String) = backupJob {
        pendingRestore = withContext(Dispatchers.IO) { Backup.read(Backup.readUri(app, uri), passphrase) }
        null
    }

    fun openBackupLink(url: String, passphrase: String) = backupJob {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        pendingRestore = withContext(Dispatchers.IO) { Backup.read(Backup.download(u), passphrase) }
        null
    }

    fun cancelRestore() {
        pendingRestore = null
    }

    /** Restores a backup that was already checked (from the phone page); Ember restarts afterwards. */
    fun restoreNow(payload: JsonObject) {
        pendingRestore = null
        backupJob {
            withContext(Dispatchers.IO) { Backup.restore(app, payload) }
            restartNeeded = true
            "Restored from your phone. Ember is restarting..."
        }
    }

    fun confirmRestore() {
        val payload = pendingRestore?.first ?: return
        backupJob {
            withContext(Dispatchers.IO) { Backup.restore(app, payload) }
            pendingRestore = null
            restartNeeded = true
            "Restored. Ember is restarting..."
        }
    }
}
