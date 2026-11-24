package app.ember.tv.data

/**
 * #428: a source that failed before it started playing. The player reports it as it closes, and
 * the title page plays the next ready source instead of leaving you to pick one.
 */
object DeadLinks {
    data class Report(val resumeKey: String, val url: String, val startOver: Boolean, val at: Long)

    const val MAX_SKIPS = 4
    private const val FRESH_MS = 60_000L
    private const val FORGET_MS = 10 * 60_000L

    @Volatile private var pending: Report? = null
    private val dead = HashMap<String, MutableSet<String>>()
    private val firstAt = HashMap<String, Long>()

    @Synchronized fun report(resumeKey: String, url: String, startOver: Boolean, now: Long = System.currentTimeMillis()) {
        if (now - (firstAt[resumeKey] ?: 0L) > FORGET_MS) { dead.remove(resumeKey); firstAt[resumeKey] = now }
        dead.getOrPut(resumeKey) { HashSet() } += url
        pending = Report(resumeKey, url, startOver, now)
    }

    /** The report waiting for the title page, once. */
    @Synchronized fun take(now: Long = System.currentTimeMillis()): Report? =
        pending.also { pending = null }?.takeIf { now - it.at <= FRESH_MS }

    @Synchronized fun deadFor(resumeKey: String): Set<String> = dead[resumeKey].orEmpty().toSet()

    /** A source that plays now: a direct link that is cached on a debrid service or a plain web link. */
    fun ready(s: StreamOption): Boolean = s.isDirect && s.kind != SourceKind.UNCACHED && s.serverPlayback == null

    /**
     * The next ready source after [failed] in the list's order (then from the top), skipping dead
     * ones. With Play button rules on, the best one that meets them comes first.
     */
    fun next(streams: List<StreamOption>, failed: String, dead: Set<String>, rules: PlayRules? = null): StreamOption? {
        if (dead.size > MAX_SKIPS) return null
        val at = streams.indexOfFirst { it.url == failed }
        val ordered = if (at < 0) streams else streams.drop(at + 1) + streams.take(at)
        val left = ordered.filter { ready(it) && it.url != failed && it.url !in dead }
        if (left.isEmpty()) return null
        return rules?.takeIf { it.enabled }?.let { PlayPicker.pick(left, emptyList(), it) } ?: left.first()
    }
}
