package app.ember.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only configuration digests are persisted; URLs and private source arguments never enter this index. */
internal class IconCacheVersion(private val file: File) {
    @Serializable private data class Saved(val epoch: String, val groups: Map<String, String>)
    private val json = Json { ignoreUnknownKeys = true }
    private var persisted = false
    private var saved = runCatching {
        require(file.length() in 1..8192)
        json.decodeFromString<Saved>(file.readText()).also {
            require(UUID.fromString(it.epoch).toString() == it.epoch)
            require(it.groups.keys.all { key -> key in setOf("catalogs", "collections") })
            require(it.groups.values.all { value -> value.matches(Regex("[a-f0-9]{64}")) })
            persisted = true
        }
    }.getOrElse { Saved(UUID.randomUUID().toString(), emptyMap()) }
    val epoch get() = saved.epoch

    fun update(signatures: Map<String, String>, force: Boolean = false): Boolean {
        val groups = saved.groups + signatures
        if (!force && groups == saved.groups && persisted && file.isFile) return false
        val next = Saved(UUID.randomUUID().toString(), groups)
        AtomicFile.write(file, json.encodeToString(next)).getOrThrow()
        saved = next
        persisted = true
        return true
    }

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun key(epoch: String, url: String) = "ember-icon:$epoch:${digest(url)}"
    }
}
