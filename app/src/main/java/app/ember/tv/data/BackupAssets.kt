package app.ember.tv.data

import android.content.Context
import app.ember.tv.home.LauncherExtras
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.Base64

/** Portable copies of managed customization files; document-provider grants stay device-local. */
internal object BackupAssets {
    const val FONT = "fonts/subtitle-custom.ttf"
    private const val TOTAL_LIMIT = 16 * 1024 * 1024
    private val presentation = Regex("presentation/[a-zA-Z0-9-]{1,100}\\.(?:jpg|jpeg|png|webp|gif|mp4|m4v|mkv|webm|mov)")
    private val json = Json { ignoreUnknownKeys = true }
    private fun limit(name: String) = if (name == FONT) SubtitleFonts.MAX_BYTES.toInt() else 8_000_000
    private fun allowed(name: String) = name == FONT || presentation.matches(name)
    private fun invalid(): Nothing = throw Backup.BackupException("The backup contains invalid customization files. Nothing was restored.")
    private fun extras(prefs: Map<String, Any>) = (prefs["launcherExtras"] as? String)?.let { json.decodeFromString<LauncherExtras>(it) }
    private fun local(uri: String): File? = try { URI(uri).takeIf { it.scheme == "file" }?.let(::File) }
        catch (_: Exception) { invalid() }
    private fun readBounded(file: File, maximum: Int): ByteArray = file.inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= maximum) {
            val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

    fun snapshot(context: Context, prefs: Map<String, Any>): JsonObject {
        val names = linkedSetOf<String>()
        if (SubtitleFonts.file(context).isFile) names.add(FONT)
        val config = (prefs["subtitleConfig"] as? String)?.let { json.decodeFromString<SubtitleConfig>(it) }
        if (config?.font == "custom" && FONT !in names) throw Backup.BackupException("The selected subtitle font is missing. Load it again before backing up.")
        extras(prefs)?.slides.orEmpty().forEach { item -> local(item.uri)?.let { file ->
            val name = "presentation/${file.name}"
            if (!allowed(name) || file.canonicalFile != destination(context, name).canonicalFile || !file.isFile)
                throw Backup.BackupException("A presentation file is missing or unavailable. Select it again before backing up.")
            names.add(name)
        } }
        var total = 0L
        return JsonObject(names.associateWith { name ->
            val file = destination(context, name)
            val size = file.length()
            total += size
            if (size !in 1..limit(name).toLong() || total > TOTAL_LIMIT)
                throw Backup.BackupException("Customization files exceed the backup size limit. No files were omitted.")
            val bytes = readBounded(file, limit(name))
            if (bytes.isEmpty() || bytes.size > limit(name)) throw Backup.BackupException("A customization file exceeds the backup size limit.")
            JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
        })
    }

    fun decode(value: JsonElement?): Map<String, ByteArray>? {
        if (value == null) return null // Backups made before portable assets were supported.
        val assets = value as? JsonObject ?: invalid()
        if (assets.size > 41) invalid()
        var total = 0L
        return assets.mapValues { (name, encoded) ->
            if (!allowed(name)) invalid()
            val text = (encoded as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
            if (text.length > (limit(name).toLong() + 2) / 3 * 4) invalid()
            val bytes = try { Base64.getDecoder().decode(text) } catch (_: Exception) { invalid() }
            total += bytes.size
            if (bytes.isEmpty() || bytes.size > limit(name) || total > TOTAL_LIMIT) invalid()
            if (name == FONT && !SubtitleFonts.looksLikeFont(bytes)) invalid()
            bytes
        }
    }

    fun remap(context: Context, plan: BackupPlan): BackupPlan {
        val assets = plan.assets ?: return plan
        val core = plan.preferences.getValue("ember").toMutableMap()
        val font = (core["subtitleConfig"] as? String)?.let { json.decodeFromString<SubtitleConfig>(it) }
        if (font?.font == "custom" && FONT !in assets) invalid()
        extras(core)?.let { saved ->
            val slides = saved.slides.map { item -> local(item.uri)?.let { old ->
                val name = "presentation/${old.name}"
                if (name !in assets) invalid()
                item.copy(uri = destination(context, name).toURI().toString())
            } ?: item }
            core["launcherExtras"] = json.encodeToString(saved.copy(slides = slides))
        }
        return plan.copy(preferences = plan.preferences + ("ember" to core))
    }

    fun destination(context: Context, name: String): File {
        if (!allowed(name)) invalid()
        val root = context.filesDir.canonicalFile
        val target = File(root, name)
        if (Files.isSymbolicLink(target.parentFile!!.toPath()) || Files.isSymbolicLink(target.toPath()) || target.canonicalFile != target.absoluteFile) invalid()
        return target
    }
}

/** Keep rollback copies on disk instead of holding every previous uploaded video in memory. */
internal class BackupAssetTransaction(private val context: Context, private val assets: Map<String, ByteArray>?,
    private val write: (File, ByteArray?) -> Unit) {
    private var staging: File? = null
    private val previous = linkedMapOf<File, File?>()
    fun apply() {
        val incoming = assets ?: return
        staging = Files.createTempDirectory(context.cacheDir.toPath(), "restore-assets-").toFile()
        for (name in incoming.keys + BackupAssets.FONT) {
            val target = BackupAssets.destination(context, name)
            if (target.exists() && !target.isFile) error("Customization target is unavailable.")
            val backup = target.takeIf { it.isFile }?.let { source -> File(staging, "${previous.size}.bin").also { source.copyTo(it) } }
            previous[target] = backup
            write(target, incoming[name])
            val expected = incoming[name]
            check(if (expected == null) !target.exists() else target.isFile && target.readBytes().contentEquals(expected))
        }
    }
    fun rollback(): Boolean {
        var restored = true
        previous.forEach { (target, copy) ->
            if (runCatching {
                if (copy == null) { check(!target.exists() || target.delete()) }
                else AtomicFile.replace(target) { copy.copyTo(it, overwrite = true) }.getOrThrow()
            }.isFailure) restored = false
        }
        if (restored) runCatching { staging?.deleteRecursively() }
        return restored
    }
    fun finish() {
      runCatching {
        staging?.deleteRecursively()
        val incoming = assets ?: return@runCatching
        val directory = File(context.filesDir, "presentation")
        if (!Files.isSymbolicLink(directory.toPath())) directory.listFiles().orEmpty()
            .filter { it.isFile && "presentation/${it.name}" !in incoming }
            .forEach { it.delete() }
      }
    }
}
