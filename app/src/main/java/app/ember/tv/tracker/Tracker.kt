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
}
