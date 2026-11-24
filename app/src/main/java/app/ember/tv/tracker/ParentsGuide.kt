package app.ember.tv.tracker


/**
 * #424: IMDb's parents guide (community-voted severity for five kinds of content), read from the
 * free imdbapi.dev service by IMDb id. A category shows only when its most-voted severity beats
 * "None"; the strongest come first.
 */
object ParentsGuide {
    data class Entry(val category: String, val severity: String) {
        val rank: Int get() = SEVERITIES.indexOf(severity).let { if (it < 0) 9 else it }
    }

    private val SEVERITIES = listOf("Severe", "Moderate", "Mild")
    private val CATEGORIES = linkedMapOf(
        "SEXUAL_CONTENT" to "Sex & nudity",
        "VIOLENCE" to "Violence",
        "PROFANITY" to "Profanity",
        "ALCOHOL_DRUGS" to "Alcohol & drugs",
        "FRIGHTENING_INTENSE_SCENES" to "Frightening scenes",
    )

    suspend fun load(imdb: String): List<Entry>? {
        if (!Regex("^tt[0-9]{5,10}$").matches(imdb)) return null
        val json = runCatching { Http.metadata("https://api.imdbapi.dev/titles/$imdb/parentsGuide") }.getOrNull() ?: return null
        return parse(json.arr("parentsGuide").list())
    }

    internal fun parse(categories: List<kotlinx.serialization.json.JsonElement>): List<Entry> = categories.mapNotNull { c ->
        val label = CATEGORIES[c.str("category")?.uppercase()] ?: return@mapNotNull null
        val votes = c.arr("severityBreakdowns").list().mapNotNull { b ->
            val level = b.str("severityLevel")?.lowercase() ?: return@mapNotNull null
            level to (b.int("voteCount") ?: 0)
        }
        val none = votes.firstOrNull { it.first == "none" }?.second ?: 0
        val top = votes.filter { it.first != "none" }.maxByOrNull { it.second } ?: return@mapNotNull null
        if (top.second <= none) return@mapNotNull null
        Entry(label, top.first.replaceFirstChar { it.uppercase() })
    }.sortedBy { it.rank }
}
