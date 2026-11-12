package app.ember.tv.state

import app.ember.tv.data.Updater

import android.app.Application
import android.content.ActivityNotFoundException
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > System > Updates: new versions from GitHub. Split out of HomeViewModel. */
class UpdatesState(
    private val app: Application,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    /** The stable channel ("releases" or "builds"), used while [updateBeta] is off. */
    var updateChannel by mutableStateOf(prefs.updateChannel); private set
    /** Beta updates: check the beta branch's builds instead of the stable channel. */
    var updateBeta by mutableStateOf(prefs.updateBeta); private set
    var updateBetaSource by mutableStateOf(prefs.updateBetaSource); private set
    /** What a check reads: "beta" while beta updates are on, else the stable channel. */
    val activeChannel get() = if (updateBeta) if (updateBetaSource == "actions") "beta-actions" else "beta" else updateChannel
    var updateAutoCheck by mutableStateOf(prefs.updateAutoCheck); private set
    var update by mutableStateOf<Updater.Release?>(null); private set
    var updateMessage by mutableStateOf<String?>(null); private set
    /** 0..1 while downloading, null otherwise. */
    var updateProgress by mutableStateOf<Float?>(null); private set
    /** 0..1 while Android installs the update (#212), null otherwise. Ember usually restarts before it clears. */
    var installProgress by mutableStateOf<Float?>(null); private set
    private var checkJob: Job? = null
    private var checkRevision = 0L
    internal var checkRelease: suspend (String) -> Result<Updater.Release?> = Updater::check
    internal var nowMillis: () -> Long = System::currentTimeMillis
    internal var installerAllowed: () -> Boolean = { Updater.canInstall(app) }
    internal var requestInstallPermission: () -> Boolean = { Updater.openInstallPermission(app) }
    internal var downloadRelease: suspend (Updater.Release, (Float) -> Unit) -> Result<File> = { release, progress -> Updater.download(app, release, progress) }
    /** Copies the APK into Android's installer, so it runs off the main thread (#99). */
    internal var installRelease: suspend (File, (Float) -> Unit) -> Boolean = { file, progress -> withContext(Dispatchers.IO) { Updater.install(app, file, progress) } }
    private var downloadRevision = 0L

    init {
        // What Android's installer reports (confirmation needed, failed, cancelled) shows here (#99).
        // Any word from the installer ends the installing bar; success restarts Ember anyway.
        Updater.statusListener = { message -> scope.launch { updateMessage = message; installProgress = null } }
    }

    fun updateUpdateChannel(v: String) {
        stopDownload()
        updateChannel = v
        prefs.updateChannel = v
        update = null
        checkForUpdate(manual = true)
    }

    /** Switches between beta and stable updates; the other branch's offer is dropped and a check runs. */
    fun toggleUpdateBeta() {
        stopDownload()
        updateBeta = !updateBeta
        prefs.updateBeta = updateBeta
        update = null
        checkForUpdate(manual = true)
    }

    fun toggleUpdateAutoCheck() {
        updateAutoCheck = !updateAutoCheck
        prefs.updateAutoCheck = updateAutoCheck
    }

    fun updateBetaSource(value: String) {
        val next = if (value == "actions") "actions" else "releases"
        if (updateBetaSource == next) return
        updateBetaSource = next
        prefs.updateBetaSource = next
        if (updateBeta) {
            stopDownload()
            update = null
            checkForUpdate(manual = true)
        }
    }

    /** Looks for a newer Ember on GitHub. At start it runs once a day, quietly. */
    fun checkForUpdate(manual: Boolean) {
        val last = prefs.updateCheckedAt
        if (!manual && (!updateAutoCheck || last != 0L && nowMillis() - last in 0L until 20 * 3600_000L)) return
        checkJob?.cancel()
        val revision = ++checkRevision
        val channel = activeChannel
        if (manual) updateMessage = "Checking..."
        checkJob = scope.launch {
            val result = try { checkRelease(channel) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            ensureActive()
            if (revision != checkRevision || channel != activeChannel) return@launch
            prefs.updateCheckedAt = nowMillis()
            result.onSuccess { r ->
                update = r?.takeIf { it.isNewer }
                updateMessage = when {
                    update != null -> "${update!!.name} is available."
                    r == null -> if (!manual) null else if (channel == "beta-actions")
                        "No successful signed beta Actions APK is available. Actions APKs expire after three days."
                        else "No release with an APK found on this channel."
                    else -> if (manual) "Ember is up to date." else null
                }
                if (!manual && update != null) {
                    android.widget.Toast.makeText(app, "An Ember update is ready: Settings > System > Updates.", android.widget.Toast.LENGTH_LONG).show()
                }
            }.onFailure { e -> if (manual) updateMessage = e.message ?: "Could not check for updates." }
        }
    }

    internal suspend fun awaitCheck() { checkJob?.join() }

    fun installUpdate() {
        val r = update ?: return
        val allowed = try { installerAllowed() } catch (_: SecurityException) {
            updateMessage = "Installing apps is restricted on this device. Check the device administrator settings."
            return
        }
        if (!allowed) {
            updateMessage = if (requestInstallPermission())
                "Allow Ember to install apps (the switch that just opened), then press Install again."
            else "Open Android Settings and allow Ember to install apps, then press Install again. This device has no permission shortcut."
            return
        }
        if (updateProgress != null || installProgress != null) return
        updateProgress = 0f
        updateMessage = "Downloading..."
        val revision = ++downloadRevision
        val channel = activeChannel
        downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val file = downloadRelease(r) { p -> scope.launch {
                    if (revision == downloadRevision && channel == activeChannel && updateProgress != null) {
                        updateProgress = p
                        // The bar is full but checking and handing over the file takes a moment (#99).
                        if (p >= 1f) updateMessage = "Preparing the install…"
                    }
                } }
                ensureActive()
                if (revision != downloadRevision || channel != activeChannel) return@launch
                file.onSuccess {
                    // #212: on most TVs Ember updates itself and restarts without Android asking.
                    updateProgress = null
                    installProgress = 0f
                    updateMessage = INSTALLING
                    val session = try {
                        installRelease(it) { p -> scope.launch { if (revision == downloadRevision && installProgress != null) installProgress = maxOf(installProgress ?: 0f, p) } }
                    } catch (e: Throwable) { installProgress = null; throw e }
                    if (!session) { installProgress = null; updateMessage = ASKS }
                }.onFailure { e -> updateMessage = e.message ?: "The download failed." }
            } catch (_: ActivityNotFoundException) {
                updateMessage = "No Android package installer is available on this device. The downloaded update was not installed."
            } catch (_: SecurityException) {
                updateMessage = "Android blocked the update installer. Check permission to install apps or device administrator restrictions."
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (revision == downloadRevision) updateMessage = "Download cancelled."
                throw e
            } finally {
                if (revision == downloadRevision) updateProgress = null
            }
        }.also { it.start() }
    }

    private var downloadJob: Job? = null
    val downloading get() = downloadJob?.isActive == true

    /** Stops a running download (the partial file is removed). */
    private fun stopDownload() { downloadRevision++; downloadJob?.cancel(); updateProgress = null }
    fun cancelUpdate() { stopDownload(); updateMessage = "Download cancelled." }

    companion object {
        const val INSTALLING = "Installing the update. Ember restarts by itself when it's done."
        const val ASKS = "Android will ask to install the update."
    }
}
