package app.ember.tv.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A failed encryption/write/readback keeps the original configuration; there is no new plaintext fallback. */
internal class AddonConfiguration(
    private val read: () -> String?, private val writeEncrypted: (String) -> Boolean,
    private val legacy: () -> List<String>, private val hasLegacy: () -> Boolean, private val removeLegacy: () -> Unit,
) {
    fun load(): List<String> {
        val saved = runCatching { read()?.let { Json.decodeFromString<List<String>>(it) } }.getOrNull()
        if (saved != null) { if (hasLegacy()) removeLegacy(); return saved }
        val previous = legacy()
        if (hasLegacy()) save(previous)
        return previous
    }
    fun save(value: List<String>): Boolean = runCatching {
        val text = Json.encodeToString(value)
        if (!writeEncrypted(text) || read() != text) return false
        removeLegacy()
        true
    }.getOrDefault(false)
}
