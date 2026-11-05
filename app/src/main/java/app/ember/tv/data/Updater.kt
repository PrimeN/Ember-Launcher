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
 * Standard updates use public tagged releases. Beta uses the private repository's explicitly
 * published beta-build, authenticated with a device-entered read token. Android asks before installing;
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
        val privateBeta: Boolean = false,
    ) {
        val isNewer: Boolean
            get() = versionCode?.let { it > BuildConfig.VERSION_CODE } ?: newerName(tag.removePrefix("v"), BuildConfig.VERSION_NAME)
    }

    private val http = Net.client(20, 60).newBuilder().cache(null).addNetworkInterceptor { chain ->
        val original = chain.request()
        val guarded = if (!privateApiUrl(original.url.toString())) original.newBuilder().removeHeader("Authorization").build() else original
        chain.proceed(guarded)
    }.build()
    private val json = Json { ignoreUnknownKeys = true }
    // A cancelled transfer must finish deleting its partial file before a replacement opens it.
    private val downloadLock = Mutex()

    internal fun request(url: String, accept: String, betaToken: String? = null) = Request.Builder().url(url).apply {
        header("Accept", accept)
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "EmberTV/${BuildConfig.VERSION_NAME}")
        // GitHub drops Authorization on cross-host redirects. Never authorize an arbitrary asset URL.
        if (!betaToken.isNullOrBlank() && privateApiUrl(url)) {
            require(betaToken.length <= 4096 && betaToken.all { it in '!'..'~' }) { "The beta token contains invalid characters. Enter it again in Settings > API keys." }
            header("Authorization", "Bearer $betaToken")
            header("Cache-Control", "no-store")
        }
    }.build()

    /** The newest release on the chosen channel ("releases", "builds" or "beta"), or an error message. */
    suspend fun check(channel: String): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            val beta = channel == "beta"
            val token = if (beta) ApiKeys.get("GITHUB_BETA_TOKEN").also {
                check(it.isNotBlank()) { "Private beta builds require a repository-read token in Settings > API keys > Private beta updates." }
            } else null
            val url = updateUrl(channel)
            withResponse(http.newCall(request(url, "application/vnd.github+json", token))) { r ->
                when (r.code) {
                    200 -> Unit
                    404 -> if (beta) error("No private beta build is available, or the token cannot access the beta repository.") else return@runCatching null
                    401 -> error("The beta access token was rejected. Update it in Settings > API keys.")
                    403 -> if (beta && r.header("X-RateLimit-Remaining") != "0") error("The beta token cannot read this repository. Check its repository access and Contents permission.")
                        else error("GitHub is temporarily limiting update checks. Try again later.")
                    429 -> error("GitHub is temporarily limiting update checks. Try again later.")
                    else -> error("GitHub answered ${r.code}.")
                }
                releaseOf(json.parseToJsonElement(r.body.string()) as JsonObject, beta)
            }
        }.also { ensureActive() }
    }

    /**
     * Downloads the APK to the app's cache; [onProgress] gets 0..1. Cancelling the calling coroutine
     * stops the transfer. The file must match the size and SHA-256 GitHub lists for the asset.
     */
    suspend fun download(context: Context, r: Release, onProgress: (Float) -> Unit): Result<File> = downloadLock.withLock { withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "ember-update.apk")
        try {
            dir.listFiles()?.forEach { it.delete() }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            val token = if (r.privateBeta) ApiKeys.get("GITHUB_BETA_TOKEN").also {
                check(it.isNotBlank() && privateApiUrl(r.assetApiUrl)) { "Private beta access is unavailable. Check the beta token and try again." }
            } else null
            withResponse(http.newCall(request(r.assetApiUrl, "application/octet-stream", token))) { resp ->
                if (!resp.isSuccessful) error("Download failed (${resp.code}).")
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: r.sizeBytes
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var shown = -1
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
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
            verify(r, done, digest.digest().joinToString("") { "%02x".format(it) })?.let { error(it) }
            Result.success(out)
        } catch (e: CancellationException) {
            out.delete(); throw e
        } catch (e: Exception) {
            out.delete()
            ensureActive() // A cancelled call fails with an IOException; report the cancellation instead.
            Result.failure(e)
        }
    } }

    internal fun releaseOf(o: JsonObject, privateBeta: Boolean = false): Release? {
        val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true } ?: return null
        val notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assetName = apk["name"]!!.jsonPrimitive.content
        val download = if (privateBeta) apk["url"]?.jsonPrimitive?.contentOrNull?.takeIf {
            val path = it.toHttpUrlOrNull()?.encodedPath.orEmpty()
            privateApiUrl(it) && path.substringBeforeLast('/').equals("/repos/${BuildConfig.BETA_UPDATE_REPO}/releases/assets", true)
                && path.substringAfterLast('/').matches(Regex("[0-9]+"))
        } else apk["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: apk["url"]?.jsonPrimitive?.contentOrNull
        if (download == null) return null
        return Release(
            tag = o["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: assetName,
            notes = notes, assetApiUrl = download, assetName = assetName,
            sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            versionCode = versionCodeOf(notes, assetName),
            sha256 = sha256Of(apk["digest"]?.jsonPrimitive?.contentOrNull),
            privateBeta = privateBeta,
        )
    }

    internal fun repository(channel: String) = if (channel == "beta") BuildConfig.BETA_UPDATE_REPO else BuildConfig.UPDATE_REPO
    /** Standard checks always use public releases, even with a saved legacy rolling-build preference. */
    internal fun updateUrl(channel: String) = releaseUrl(repository(channel), if (channel == "beta") "beta" else "releases")
    internal fun privateApiUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.host == "api.github.com" && it.port == 443 &&
            it.username.isEmpty() && it.password.isEmpty() &&
            it.encodedPath.startsWith("/repos/${BuildConfig.BETA_UPDATE_REPO}/releases/", ignoreCase = true)
    } == true

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

    /** Hands the APK to Android's installer, which asks before installing. */
    fun install(context: Context, apk: File) {
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
