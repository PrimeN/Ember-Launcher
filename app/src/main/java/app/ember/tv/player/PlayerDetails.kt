package app.ember.tv.player

import app.ember.tv.data.Person

/**
 * What the advanced player shows about the title (Settings > Player > Player look): too much for
 * intent extras (the cast with photos), so it is handed over in memory, keyed by the resume key.
 */
data class PlayerDetails(
    val name: String,
    val logo: String? = null,
    val year: String? = null,
    /** e.g. "IMDb 8.3". */
    val rating: String? = null,
    val episodeTitle: String? = null,
    val synopsis: String? = null,
    val cast: List<Person> = emptyList(),
    val backdrop: String? = null,
) {
    companion object {
        private val byKey = java.util.Collections.synchronizedMap(LinkedHashMap<String, PlayerDetails>())

        fun put(key: String, d: PlayerDetails) {
            byKey[key] = d
            // Only the last few are needed.
            while (byKey.size > 8) byKey.remove(byKey.keys.first())
        }

        fun get(key: String?): PlayerDetails? = key?.let { byKey[it] }
    }
}
