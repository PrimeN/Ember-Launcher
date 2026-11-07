package app.ember.tv.tracker

/** One watch-tracking service. */
interface Tracker {
    val id: String
    val name: String
    val blurb: String
    val mode: LinkMode

    /** False when the app was built without this service's API key. */
    val configured: Boolean

    /** True when the service can receive progress while you watch. */
    val scrobbles: Boolean

    fun isConnected(): Boolean
    fun accountLabel(): String?
    fun disconnect()

    suspend fun continueWatching(): List<TrackedItem>
    suspend fun watchlist(): List<TrackedItem>

    // Device-code services
    suspend fun beginLink(): LinkSession = error("$name does not use a sign-in code")
    suspend fun pollLink(session: LinkSession): Boolean = false

    // Username services
    suspend fun linkUsername(name: String): Boolean = false

    suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) {}

    /** Whether this operation is supported for this title (independent of connectivity). */
    fun supportsWatchlist(ref: TitleRef, add: Boolean): Boolean = false

    // Writes. Services that cannot do one of these leave the default, which does nothing.
    /** Returns true when the title was sent to the service. */
    suspend fun addToWatchlist(ref: TitleRef): Boolean = false
    suspend fun removeFromWatchlist(ref: TitleRef): Boolean = false

    /** Removes a Continue watching entry. [itemKey] is the key from [TrackedItem]. */
    suspend fun removePlayback(itemKey: String): Boolean = false

    /** Whether explicit watched / unwatched marks can be sent for this title (#79). */
    fun supportsHistory(ref: TitleRef): Boolean = false
    /** Marks a movie, one episode ([season] and [episode]) or a whole show watched or unwatched (#79). */
    suspend fun markWatched(ref: TitleRef, season: Int?, episode: Int?, watched: Boolean): Boolean = false

    /** True when titles can be rated, 1 to 10 (#84). */
    val rates: Boolean get() = false
    /** Sends your rating, or clears it when [rating] is null (#84). */
    suspend fun rate(ref: TitleRef, rating: Int?): Boolean = false
    /** Your current rating for the title, when the service has one (#84). */
    suspend fun ratingOf(ref: TitleRef): Int? = null

    /** Reads the account name shown in Settings (#85). */
    suspend fun loadAccountName() {}
    fun needsAccountName(): Boolean = false

    /** Every watched movie and episode, as watched keys (see watchedKeys), or null when not offered (#83). */
    suspend fun watched(): Set<String>? = null

    // ---- two-way sync between trackers (#90) ----
    /** True when this service can take part in keeping trackers in sync. */
    val syncs: Boolean get() = false
    /** Watched movies and episodes (and finished shows, which sync leaves out). */
    suspend fun syncWatched(): List<SyncItem> = error("$name does not sync")
    /** Watchlist movies and shows. */
    suspend fun syncWatchlist(): List<SyncItem> = watchlist().mapNotNull(SyncItem::of)
    /** Your ratings of movies and shows. */
    suspend fun syncRatings(): Map<SyncItem, Int> = error("$name does not sync")
    /** False when removing from the watchlist would also delete history (Simkl). */
    val removesFromWatchlist: Boolean get() = true
    /** Applies a batch; throws when the service could not take it. */
    suspend fun applySync(category: SyncCategory, add: List<SyncItem>, remove: List<SyncItem>) {}
    suspend fun applyRatings(set: Map<SyncItem, Int>, clear: List<SyncItem>) {}
}

/** A service refused a change for a reason a retry won't fix, e.g. a free-account limit (#82). */
class TrackerRefusal(val tracker: String, message: String) : Exception(message)
