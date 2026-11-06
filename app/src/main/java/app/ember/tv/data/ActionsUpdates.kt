package app.ember.tv.data

import app.ember.tv.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream

/** Public beta artifacts only; no GitHub credentials are stored or sent to the download mirror. */
internal object ActionsUpdates {
    const val CHANNEL = "beta-actions"
    const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    private const val MAX_APK_BYTES = 384L * 1024 * 1024
    val runsUrl get() = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/actions/workflows/build.yml/runs?branch=beta&status=success&per_page=5"
    private val json = Json { ignoreUnknownKeys = true }
    private val name = Regex("^ember-tv-(\\d+(?:\\.\\d+){2,3}(?:[-+][A-Za-z0-9.-]+)?)-(\\d+)-release$")

    private suspend fun read(url: String, client: OkHttpClient): JsonObject? = coroutineScope {
        withResponse(client.newCall(Updater.request(url, "application/vnd.github+json"))) { response ->
            when (response.code) {
                404 -> null
                200 -> json.parseToJsonElement(response.body.string()).jsonObject
                403, 429 -> error("GitHub is temporarily limiting update checks. Try again later.")
                else -> error("Could not check public Actions builds (${response.code}). Try again later.")
            }
        }
    }

    suspend fun check(client: OkHttpClient): Updater.Release? {
        val runs = (read(runsUrl, client)?.get("workflow_runs") as? JsonArray).orEmpty()
        for (value in runs.take(5)) {
            val run = value as? JsonObject ?: continue
            if (!trusted(run)) continue
            val id = run["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: continue
            val artifacts = read("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/actions/runs/$id/artifacts?per_page=100", client)
                ?.get("artifacts") as? JsonArray ?: continue
            val found = artifacts.mapNotNull { (it as? JsonObject)?.let { item -> releaseOf(item, run) } }
                .maxByOrNull { it.versionCode ?: 0 }
            if (found != null) return found
        }
        return null
    }

    internal fun trusted(run: JsonObject): Boolean =
        run["head_branch"]?.jsonPrimitive?.contentOrNull == "beta" &&
            run["status"]?.jsonPrimitive?.contentOrNull == "completed" &&
            run["conclusion"]?.jsonPrimitive?.contentOrNull == "success" &&
            run["event"]?.jsonPrimitive?.contentOrNull in setOf("push", "workflow_dispatch") &&
            (run["head_repository"] as? JsonObject)?.get("full_name")?.jsonPrimitive?.contentOrNull
                .equals(BuildConfig.UPDATE_REPO, ignoreCase = true)

    internal fun releaseOf(artifact: JsonObject, run: JsonObject, now: Instant = Instant.now()): Updater.Release? {
        if (!trusted(run) || artifact["expired"]?.jsonPrimitive?.booleanOrNull != false) return null
        val expiry = artifact["expires_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        if (!expiry.isAfter(now)) return null
        val label = artifact["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val match = name.matchEntire(label) ?: return null
        val code = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..2100000000 } ?: return null
        val id = artifact["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val size = artifact["size_in_bytes"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..MAX_ARCHIVE_BYTES } ?: return null
        val digest = Updater.sha256Of(artifact["digest"]?.jsonPrimitive?.contentOrNull) ?: return null
        val sha = run["head_sha"]?.jsonPrimitive?.contentOrNull.orEmpty().take(12)
        return Updater.Release(
            tag = CHANNEL, name = "Ember ${match.groupValues[1]} · beta Actions", notes =
                "Successful public beta build.\nversionCode: $code\nCommit: $sha\nActions APKs expire after three days. Downloads use nightly.link.",
            assetApiUrl = "https://nightly.link/${BuildConfig.UPDATE_REPO}/actions/artifacts/$id.zip",
            assetName = label.removeSuffix("-release") + ".apk", sizeBytes = size, versionCode = code,
            sha256 = digest, zippedApk = true,
        )
    }

    /** Fixed output path, exactly one matching release APK, bounded expansion and no path traversal. */
    internal suspend fun extract(archive: File, output: File, expectedName: String, maxBytes: Long = MAX_APK_BYTES) {
        var found = false
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                check(++count <= 16 && !entry.name.startsWith('/') && '\\' !in entry.name &&
                    entry.name.split('/').none { it == ".." }) { "The Actions archive contains an invalid path." }
                if (entry.isDirectory) { check(entry.size <= 0 && zip.read() == -1) { "The Actions archive contains an invalid directory." }; continue }
                check(!found && entry.name.substringAfterLast('/') == expectedName) {
                    "The Actions archive does not contain one matching signed release APK. Choose Releases instead."
                }
                check(entry.size <= maxBytes) { "The Actions APK is too large." }
                var expanded = 0L
                output.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = zip.read(buffer)
                        if (n < 0) break
                        expanded += n
                        check(expanded <= maxBytes) { "The Actions APK is too large." }
                        out.write(buffer, 0, n)
                    }
                }
                check(expanded > 0) { "The Actions APK is empty." }
                found = true
            }
        }
        check(found) { "No APK was found in the Actions archive." }
    }
}
