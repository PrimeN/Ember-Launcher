package app.ember.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Display-only aliases keyed by stable catalog identity; provider IDs and requests stay intact. */
object CatalogNames {
    var names by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun load(value: Map<String, String>) { names = value }
    fun label(key: String?, original: String): String = names[key] ?: original

    fun updated(current: Map<String, String>, key: String, value: String): Map<String, String> {
        val name = value.trim().take(120)
        return if (name.isEmpty()) current - key else current + (key to name)
    }
}
