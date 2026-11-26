package app.ember.tv.data

/**
 * #461: Ember's own lists as collection sources: your Watchlist, Continue watching, Recently
 * watched and the smart rows. They are read from what Home already has, so they are always current.
 */
object LibraryLists {
    const val PROVIDER = "library"
    const val WATCHLIST = "watchlist"
    const val CONTINUE = "continue"
    const val RECENT = "recent"
    private const val SMART = "smart:"

    data class Entry(val id: String, val label: String)

    val all: List<Entry> = listOf(
        Entry(WATCHLIST, "Watchlist"),
        Entry(CONTINUE, "Continue watching"),
        Entry(RECENT, "Recently watched"),
        Entry(SMART + "upnext", "Up next"),
        Entry(SMART + "airing", "Airing this week"),
        Entry(SMART + "recs", "Recommended for you"),
        Entry(SMART + "because", "Because you watched"),
        Entry(SMART + "gems", "Hidden gems"),
        Entry(SMART + "lists", "Your Trakt lists"),
    )

    fun known(id: String?): Boolean = all.any { it.id == id }
    fun label(id: String?): String = all.firstOrNull { it.id == id }?.label ?: "Ember list"
    fun source(entry: Entry) = CollectionSource(provider = PROVIDER, catalogId = entry.id, title = entry.label)
    fun isLibrary(s: CollectionSource) = s.provider.equals(PROVIDER, ignoreCase = true)

    /** The smart row id a list reads, or null for the fixed lists. */
    fun smartId(id: String): String? = id.takeIf { it.startsWith(SMART) }?.removePrefix(SMART)

    /** One page holding the whole list. */
    fun page(items: List<Meta>): CollectionPage =
        CollectionPage(items, items.size, true, CollectionPage.fingerprint(items.map { it.mediaKey() }))
}
