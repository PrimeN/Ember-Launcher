package app.ember.tv.data

import androidx.core.net.toUri

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.ember.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates (Settings > System > Updates) from the GitHub repository's releases:
 * Standard updates use public tagged releases. Beta uses the same public repository's explicitly
 * published beta-build. Neither channel requires credentials. Android asks before installing;
 * an update installs over the
 * current app only when both are signed with the same key.
 */
object Updater {
    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        /** Public APK download link, with the API asset URL as a compatibility fallback. */
        val assetApiUrl: String,
        val assetName: String,
        val sizeBytes: Long,
        /** Build number, read from the notes ("versionCode: 123") or the file name. */
        val versionCode: Int?,
        /** SHA-256 GitHub publishes for the asset ("digest": "sha256:..."), lowercase hex; null if absent. */
        val sha256: String? = null,
        /** Actions downloads are verified ZIP archives containing one signed release APK. */
        val zippedApk: Boolean = false,
    ) {
        val isNewer: Boolean
            get() = versionCode?.let { it > BuildConfig.VERSION_CODE } ?: newerName(tag.removePrefix("v"), BuildConfig.VERSION_NAME)
    }

    private val http = Net.client(20, 60).newBuilder().cache(null).addNetworkInterceptor { chain ->
        val original = chain.request()
        val guarded = original.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build()
        chain.proceed(guarded)
    }.build()
    private val json = Json { ignoreUnknownKeys = true }
    // A cancelled transfer must finish deleting its partial file before a replacement opens it.
    private val downloadLock = Mutex()

    internal fun request(url: String, accept: String) = Request.Builder().url(url).apply {
        header("Accept", accept)
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "EmberTV/${BuildConfig.VERSION_NAME}")
        header("Cache-Control", "no-store")
    }.build()

    /** The newest release on the chosen channel ("releases", "builds" or "beta"), or an error message. */
    suspend fun check(channel: String): Result<Release?> = check(channel, http)

    internal suspend fun check(channel: String, client: OkHttpClient): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            if (channel == ActionsUpdates.CHANNEL) return@runCatching ActionsUpdates.check(client)
            val url = updateUrl(channel)
            withResponse(client.newCall(request(url, "application/vnd.github+json"))) { r ->
                when (r.code) {
                    200 -> Unit
                    404 -> return@runCatching null
                    401 -> error("The public update service is unavailable. Try again later.")
                    403 -> error("GitHub is temporarily limiting update checks. Try again later.")
                    429 -> error("GitHub is temporarily limiting update checks. Try again later.")
                    else -> error("GitHub answered ${r.code}.")
                }
                releaseOf(json.parseToJsonElement(r.body.string()) as JsonObject)
            }
        }.also { ensureActive() }
    }

    /**
     * Downloads the APK to the app's cache; [onProgress] gets 0..1. Cancelling the calling coroutine
     * stops the transfer. The file must match the size and SHA-256 GitHub lists for the asset.
     */
    suspend fun download(context: Context, r: Release, onProgress: (Float) -> Unit): Result<File> = download(context, r, onProgress, http)

    internal suspend fun download(context: Context, r: Release, onProgress: (Float) -> Unit, client: OkHttpClient): Result<File> = downloadLock.withLock { withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "ember-update.apk")
        val archive = File(dir, "ember-update.zip")
        // The last verified download, by the release's checksum: pressing Install again installs it
        // instead of downloading everything again (#99).
        val marker = File(context.cacheDir, "updates-verified")
        if (r.sha256 != null) {
            val saved = runCatching { marker.readText().split('|') }.getOrNull()
            if (saved?.size == 2 && saved[0] == r.sha256 && out.isFile && out.length() == saved[1].toLongOrNull()) {
                onProgress(1f)
                return@withContext Result.success(out)
            }
        }
        marker.delete()
        try {
            dir.listFiles()?.forEach { it.delete() }
            check(!r.zippedApk || (r.sha256 != null && r.sizeBytes in 1..ActionsUpdates.MAX_ARCHIVE_BYTES)) {
                "The Actions archive has no valid integrity information. Check for updates again."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            withResponse(client.newCall(request(r.assetApiUrl, "application/octet-stream"))) { resp ->
                if (!resp.isSuccessful) error(if (r.zippedApk)
                    "The Actions APK is unavailable (${resp.code}). Check again or choose Releases."
                    else "Download failed (${resp.code}).")
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: r.sizeBytes
                body.byteStream().use { input ->
                    (if (r.zippedApk) archive else out).outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var shown = -1
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            check(!r.zippedApk || done <= ActionsUpdates.MAX_ARCHIVE_BYTES) { "The Actions archive is too large." }
                            // Report whole percents only.
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != shown && pct >= 0) {
                                shown = pct
                                onProgress((pct / 100f).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            verify(r, done, digest.digest().toHex())?.let { error(it) }
            if (r.zippedApk) ActionsUpdates.extract(archive, out, r.assetName)
            r.sha256?.let { runCatching { marker.writeText("$it|${out.length()}") } }
            Result.success(out)
        } catch (e: CancellationException) {
            out.delete(); throw e
        } catch (e: Exception) {
            out.delete()
            ensureActive() // A cancelled call fails with an IOException; report the cancellation instead.
            Result.failure(e)
        } finally {
            archive.delete()
        }
    } }

    internal fun releaseOf(o: JsonObject): Release? {
        val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true } ?: return null
        val notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assetName = apk["name"]!!.jsonPrimitive.content
        val download = publicAssetUrl(apk["browser_download_url"]?.jsonPrimitive?.contentOrNull)
            ?: publicAssetUrl(apk["url"]?.jsonPrimitive?.contentOrNull)
        if (download == null) return null
        return Release(
            tag = o["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: assetName,
            notes = notes, assetApiUrl = download, assetName = assetName,
            sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            versionCode = versionCodeOf(notes, assetName),
            sha256 = sha256Of(apk["digest"]?.jsonPrimitive?.contentOrNull),
        )
    }

    internal fun repository(@Suppress("UNUSED_PARAMETER") channel: String) = BuildConfig.UPDATE_REPO
    /** Standard checks always use public releases, even with a saved legacy rolling-build preference. */
    internal fun updateUrl(channel: String) = if (channel == ActionsUpdates.CHANNEL) ActionsUpdates.runsUrl
        else releaseUrl(repository(channel), if (channel == "beta") "beta" else "releases")
    private fun publicAssetUrl(value: String?): String? = value?.takeIf { raw -> raw.toHttpUrlOrNull()?.let {
        if (it.scheme != "https" || it.port != 443 || it.username.isNotEmpty() || it.password.isNotEmpty()) false
        else when (it.host) {
            "github.com" -> it.encodedPath.startsWith("/${BuildConfig.UPDATE_REPO}/releases/download/", ignoreCase = true)
            "api.github.com" -> it.encodedPath.substringBeforeLast('/').equals("/repos/${BuildConfig.UPDATE_REPO}/releases/assets", true) &&
                it.encodedPath.substringAfterLast('/').matches(Regex("[0-9]+"))
            else -> false
        }
    } == true }

    /** Legacy URL helper retained for compatibility; updateUrl normalizes standard checks to releases. */
    internal fun releaseUrl(repo: String, channel: String): String = when (channel) {
        "builds" -> "https://api.github.com/repos/$repo/releases/tags/latest-build"
        "beta" -> "https://api.github.com/repos/$repo/releases/tags/beta-build"
        else -> "https://api.github.com/repos/$repo/releases/latest"
    }

    /** Null when the download matches the release, otherwise the message to show. */
    internal fun verify(r: Release, size: Long, sha256: String): String? = when {
        r.sizeBytes > 0 && size != r.sizeBytes -> "The download was incomplete. Try again."
        r.sha256 != null && !r.sha256.equals(sha256, ignoreCase = true) -> "The download is damaged (its checksum does not match the release). Try again."
        else -> null
    }

    /** "sha256:<64 hex>" from the GitHub API, as lowercase hex; anything else is ignored. */
    internal fun sha256Of(digest: String?): String? =
        digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase(java.util.Locale.ROOT)
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    /** True when Android lets Ember install apps; otherwise [openInstallPermission] first. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for Ember (some TVs only have it under Security). */
    fun openInstallPermission(context: Context): Boolean {
        val specific = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchInstalledApp({ context.startActivity(specific) }, {}) || launchInstalledApp({
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, {})
    }

    /** Install progress and failures from Android's installer, for Settings > Updates (#99). */
    @Volatile var statusListener: ((String) -> Unit)? = null
    fun report(message: String) { statusListener?.invoke(message) }

    /**
     * Hands the APK to Android's installer (#99). A PackageInstaller session reports back: Android
     * asks for the confirmation it needs (opened by [UpdateInstallReceiver]) and says why when it
     * fails, where a plain view intent can be dropped without a word. Once Ember has installed
     * itself this way, Android 12+ can apply later updates without the extra prompt. If a session
     * can't be created, the plain intent is used. Writes the file: call off the main thread.
     */
    fun install(context: Context, apk: File) {
        val sessionStarted = runCatching { installWithSession(context.applicationContext, apk) }.isSuccess
        if (!sessionStarted) installWithIntent(context, apk)
    }

    private fun installWithSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds its status to this intent.
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                session.commit(android.app.PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun installWithIntent(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    /** "0.10.0" is newer than "0.9.2". */
    /**
     * The build number of a release: "versionCode: 123" in its notes, or the number at the end of
     * the APK's name (ember-tv-0.9.0-123.apk; a "-debug" build has none).
     */
    fun versionCodeOf(notes: String, assetName: String): Int? =
        Regex("""versionCode:\s*(\d+)""").find(notes)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""-(\d+)\.apk$""").find(assetName)?.groupValues?.get(1)?.toIntOrNull()

    fun newerName(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

/** What Android's installer says about an update session (#99). */
class UpdateInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            val opened = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            Updater.report(if (opened) "Android will ask to install the update." else "Android didn't open its installer. Press Install again; it won't download again.")
            return
        }
        Updater.report(updateInstallMessage(status))
    }
}

/** The message for an installer status (#99). */
internal fun updateInstallMessage(status: Int): String = when (status) {
    android.content.pm.PackageInstaller.STATUS_SUCCESS -> "Update installed."
    android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "The update wasn't installed. Press Install to try again; it won't download again."
    android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> "This TV blocked the update (a device policy or another installer)."
    android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed Ember (a different signature). It can only be installed after uninstalling this copy."
    android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn't compatible with this TV."
    android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "Android says the update file is invalid. Check for updates and try again."
    android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage on this TV to install the update."
    else -> "The update couldn't be installed. Press Install to try again."
}
