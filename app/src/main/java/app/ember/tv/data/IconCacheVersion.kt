package app.ember.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The icon cache's epoch, kept across restarts so downloaded icons are reused. Only Clear cache
 * (or a damaged index) starts a new one (#55). URLs and private source arguments never enter it.
 */
internal class IconCacheVersion(private val file: File) {
    // Older indexes also listed configuration digests; they are ignored, so the epoch carries over.
    @Serializable private data class Saved(val epoch: String)
    private val json = Json { ignoreUnknownKeys = true }
    private var saved = runCatching {
        require(file.length() in 1..8192)
        json.decodeFromString<Saved>(file.readText()).also { require(UUID.fromString(it.epoch).toString() == it.epoch) }
    }.getOrNull()
        ?: Saved(UUID.randomUUID().toString()).also { runCatching { AtomicFile.write(file, json.encodeToString(it)).getOrThrow() } }
    val epoch get() = saved.epoch

    /** Starts a new epoch: no earlier icon key matches it again. */
    fun rotate() {
        val next = Saved(UUID.randomUUID().toString())
        AtomicFile.write(file, json.encodeToString(next)).getOrThrow()
        saved = next
    }

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).toHex()
        fun key(epoch: String, url: String) = "ember-icon:$epoch:${digest(url)}"
    }
}
