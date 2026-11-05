package app.ember.tv.tracker

/** A Home row built from trackers or TMDB rather than an addon catalog. */
data class SmartRow(val id: String, val title: String, val source: String, val items: List<TrackedItem>)

/**
 * Additional personalized Home rows: Recommended for you, Because you watched,
 * Hidden gems and your personal lists. Each can be switched off in Settings.
 */
class SmartRows(private val hub: TrackerHub) {
    suspend fun load(off: Set<String>): List<SmartRow> {
        val rows = ArrayList<SmartRow>()
        val trakt = hub.trakt()

        if (trakt != null && UP_NEXT !in off) {
            val items = runCatching { trakt.upNext() }.getOrDefault(emptyList())
            if (items.isNotEmpty()) rows += SmartRow(UP_NEXT, "Up next", "Trakt", hub.enrichAll(items))
        }

        if (trakt != null && AIRING !in off) {
            val items = runCatching { trakt.airingThisWeek() }.getOrDefault(emptyList())
            if (items.isNotEmpty()) rows += SmartRow(AIRING, "Airing this week", "Your shows on Trakt", hub.enrichAll(items))
        }

        if (trakt != null && RECS !in off) {
            val items = runCatching { trakt.recommendations() }.getOrDefault(emptyList())
            if (items.isNotEmpty()) rows += SmartRow(RECS, "Recommended for you", "Trakt", hub.enrichAll(items))
        }

        if (trakt != null && BECAUSE !in off) {
            val last = runCatching { trakt.lastWatched() }.getOrNull()
            if (last != null) {
                val items = runCatching { trakt.related(last) }.getOrDefault(emptyList())
                if (items.isNotEmpty()) rows += SmartRow(BECAUSE, "Because you watched ${last.title}", "Trakt", hub.enrichAll(items))
            }
        }

        if (GEMS !in off) {
            val items = runCatching { Tmdb.hiddenGems() }.getOrDefault(emptyList())
            if (items.isNotEmpty()) rows += SmartRow(GEMS, "Hidden gems", "TMDB", items)
        }

        if (trakt != null && LISTS !in off) {
            val lists = runCatching { trakt.personalLists() }.getOrDefault(emptyList())
            for ((name, items) in lists) {
                rows += SmartRow("$LISTS:$name", name, "Your Trakt list", hub.enrichAll(items))
            }
        }
        return rows
    }

    companion object {
        const val UP_NEXT = "upnext"
        const val AIRING = "airing"
        const val RECS = "recs"
        const val BECAUSE = "because"
        const val GEMS = "gems"
        const val LISTS = "lists"

        /** Row switches shown in Settings, in display order. */
        val ALL = listOf(
            UP_NEXT to "Up next (the next episode of shows you watch)",
            AIRING to "Airing this week",
            RECS to "Recommended for you",
            BECAUSE to "Because you watched",
            GEMS to "Hidden gems",
            LISTS to "Your Trakt lists",
        )
    }
}
