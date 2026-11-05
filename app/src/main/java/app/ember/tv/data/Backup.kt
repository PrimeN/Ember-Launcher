package app.ember.tv.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.ember.tv.tracker.TokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One file with everything: settings, addons, watchlist, Home layout, screensaver choices,
 * playback positions, tracker and media server sign-ins, and API keys entered on the TV.
 *
 * With a passphrase the contents are encrypted (AES-256-GCM, key from PBKDF2-SHA256), so the
 * file is safe to keep in a cloud folder. Without one it is plain JSON and anyone holding the
 * file can use your sign-ins.
 */
object Backup {
    const val FORMAT = "ember-backup"
    const val VERSION = 1

    /** Files in the app's folder that are settings too: Collections and a custom badge pack. */
    private val DATA_FILES = BackupFormat.dataFiles

    private const val ITERATIONS = 120_000
    private val json = Json { ignoreUnknownKeys = true }

    class BackupException(message: String) : Exception(message)

    data class Summary(val created: String?, val addons: Int, val signIns: Int, val keys: Int)

    fun fileName(): String = "ember-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"

    // ---- building and reading the file ----

    /** The backup as file bytes. Call off the main thread: encryption and the keystore are slow. */
    @Synchronized fun export(context: Context, passphrase: String): ByteArray =
        encodePayload(LocalLibrary.snapshotFiles {persistence(context).snapshot()},passphrase)

    internal fun encodePayload(payload:JsonObject,passphrase:String):ByteArray {
        BackupFormat.plan(payload)
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        val file = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("created", now)
            if (passphrase.isEmpty()) {
                put("encrypted", false)
                put("data", payload)
            } else {
                put("encrypted", true)
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
                put("kdf", "PBKDF2WithHmacSHA256")
                put("iterations", ITERATIONS)
                put("salt", b64(salt))
                put("iv", b64(iv))
                put("data", b64(cipher.doFinal(payload.toString().toByteArray())))
            }
        }
        return file.toString().toByteArray(Charsets.UTF_8).also {
            if(it.size>MAX_BYTES)throw BackupException("This backup exceeds the supported file size. No data was omitted.")
        }
    }

    /** Checks the file and decrypts it, without changing anything yet. */
    fun read(bytes: ByteArray, passphrase: String): Pair<JsonObject, Summary> {
        if(bytes.isEmpty() || bytes.size>MAX_BYTES)throw BackupException("This backup is empty or exceeds the supported file size.")
        return try {readChecked(bytes,passphrase)}
        catch(error:BackupException) {throw error}
        catch(_:Exception) {throw BackupException("The backup file is damaged or has an invalid format.")}
    }

    private fun readChecked(bytes:ByteArray,passphrase:String):Pair<JsonObject,Summary> {
        val file = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
            .getOrElse { throw BackupException("This is not an Ember backup file.") }
        if (file["format"]?.jsonPrimitive?.contentOrNull != FORMAT) throw BackupException("This is not an Ember backup file.")
        val version = file["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if(version<1)throw BackupException("This backup has no supported format version.")
        if (version > VERSION) throw BackupException("This backup comes from a newer version of Ember. Update the app first.")

        val payload = if (file["encrypted"]?.jsonPrimitive?.booleanOrNull == true) {
            if (passphrase.isEmpty()) throw BackupException("This backup is protected. Enter its passphrase.")
            val salt = unb64(file.str("salt"))
            val iv = unb64(file.str("iv"))
            val iterations = file.str("iterations").toIntOrNull() ?: ITERATIONS
            if(iterations !in 10_000..1_000_000 || salt.size !in 16..64 || iv.size!=12 || file.str("kdf")!="PBKDF2WithHmacSHA256")
                throw BackupException("The encrypted backup parameters are invalid.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
            val plain = try {
                cipher.doFinal(unb64(file.str("data")))
            } catch (e: AEADBadTagException) {
                throw BackupException("Wrong passphrase.")
            }
            json.parseToJsonElement(plain.decodeToString()).jsonObject
        } else {
            file["data"]?.jsonObject ?: throw BackupException("The backup file is empty.")
        }

        BackupFormat.plan(payload)
        val tokens = payload["tokens"]?.jsonObject ?: JsonObject(emptyMap())
        val privateAddons = tokens["addons.config"]?.jsonPrimitive?.contentOrNull?.let { runCatching { json.parseToJsonElement(it).jsonArray.size }.getOrNull() }
        val addons = privateAddons ?: payload["prefs.ember"]?.jsonObject?.get("addons")?.jsonObject?.get("v")
            ?.jsonPrimitive?.contentOrNull?.split("\n")?.count { it.isNotBlank() } ?: 0
        val servers = tokens["ms.servers"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { json.parseToJsonElement(it).jsonArray.size }.getOrNull() } ?: 0
        val signIns = servers + tokens.keys.count { k ->
            k.endsWith(".access") || k == "plex.token" || k == "anilist.user" || k == "mal.user"
        }
        val keys = tokens.keys.count { it.startsWith("apikey.") }
        return payload to Summary(file["created"]?.jsonPrimitive?.contentOrNull, addons, signIns, keys)
    }

    /**
     * Replaces this TV's settings with the backup. Everything is written synchronously, and the
     * app should restart afterwards so every screen reloads.
     */
    @Synchronized fun restore(context: Context, payload: JsonObject) {
        val storage=persistence(context)
        LocalLibrary.restoreFiles {storage.restore(payload)}
        ApiKeys.reload()
    }

    private fun persistence(context:Context):BackupPersistence {
        val store=TokenStore(context)
        return BackupPersistence(context,object:BackupSecrets {
            override val encrypted get()=store.encryptionAvailable
            override fun read()=store.all()
            override fun replace(values:Map<String,String>)=store.replaceAll(values)
        })
    }

    /** Reconcile the complete allowlisted file set; absent files must not survive restore. */
    internal fun restoreDataFiles(dir: File, files: JsonObject) {
        DATA_FILES.forEach { name ->
            val text = (files[name] as? JsonPrimitive)?.contentOrNull
            val file = File(dir, name)
            if (text == null) {
                if (file.exists() && !file.delete()) throw BackupException("Could not remove $name.")
            } else AtomicFile.write(file, text).getOrElse {
                // A failed or interrupted restore keeps the previous complete file, never half of one.
                throw BackupException("Could not write $name.")
            }
        }
    }

    // ---- crypto helpers ----

    private fun key(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String): ByteArray = runCatching { java.util.Base64.getDecoder().decode(s.filterNot(Char::isWhitespace)) }
        .getOrElse { throw BackupException("The backup file is damaged.") }

    private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    // ---- where backup files go ----

    /** Whether this TV has a system file picker. Many Google TV devices do not. */
    fun pickerAvailable(context: Context): Boolean =
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .resolveActivity(context.packageManager) != null

    /** Saves to Download/Ember on this TV. Returns where it went, in words. */
    fun saveToDownloads(context: Context, name: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ember")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw BackupException("Could not create the file in Downloads.")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw BackupException("Could not write the file in Downloads.")
        return "Download/Ember/$name"
    }

    data class Found(val name: String, val uri: Uri, val modified: Long)

    /**
     * Backups in Download/Ember. On Android 10 and later this lists only files this install
     * saved; a backup from another TV or an earlier install is opened with the picker or a link.
     */
    fun listDownloads(context: Context): List<Found> = runCatching {
        val out = mutableListOf<Found>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("ember-backup%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Found(c.getString(1), Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()), c.getLong(2) * 1000)
            }
        }
        out
    }.getOrDefault(emptyList())

    private val http by lazy { Net.base }

    /** Fetches a backup from a direct link (for example a Dropbox link ending in ?dl=1). */
    fun download(url: String): ByteArray {
        val req = okhttp3.Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw BackupException("The link returned an error (${r.code}).")
            val body = r.body ?: throw BackupException("The link returned nothing.")
            if (body.contentLength() > MAX_BYTES) throw BackupException("That file is too big to be an Ember backup.")
            val bytes = body.byteStream().use { it.readNBytesCompat(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) throw BackupException("That file is too big to be an Ember backup.")
            return bytes
        }
    }

    private const val MAX_BYTES = 20 * 1024 * 1024

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun readUri(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use {it.readNBytesCompat(MAX_BYTES+1)}
            ?.also {if(it.size>MAX_BYTES)throw BackupException("That file is too big to be an Ember backup.")}
            ?: throw BackupException("Could not open the file.")
}
