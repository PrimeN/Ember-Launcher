package app.ember.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * #263: whether an addon's own metadata comes first. When on, a title keeps the logo, backgrounds,
 * details and cast its addon gave, its details are asked of the addon whose catalog it came from,
 * and TMDB and Fanart only fill in what the addon left out (skipping their requests when nothing is
 * missing).
 */
object AddonMetadata {
    /** Mirrors [MetaConfig.addonFirst]; read by artwork and the title page. */
    var first by mutableStateOf(true)

    private val origins = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3000
    }

    private fun key(type: String, id: String) = "$type:$id"

    /** Remembers which addon's catalog listed these titles. */
    fun listed(addonUrl: String, metas: List<Meta>) = synchronized(origins) {
        metas.forEach { if (it.id.isNotBlank()) origins[key(it.type, it.id)] = addonUrl }
    }

    /** The addon whose catalog last listed this title, if any. */
    fun origin(type: String, id: String): String? = synchronized(origins) { origins[key(type, id)] }

    /** True when the addon already gave everything artwork lookups would add. */
    fun complete(meta: Meta): Boolean = first && !meta.background.isNullOrBlank() && !meta.logo.isNullOrBlank()
}
