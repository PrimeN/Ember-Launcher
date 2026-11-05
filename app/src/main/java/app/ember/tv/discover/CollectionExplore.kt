package app.ember.tv.discover

import app.ember.tv.data.CollectionPage
import app.ember.tv.data.CollectionSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded, profile/region/query-specific sessions avoid replaying earlier provider pages. */
class CollectionExplore(private val source: ExploreSource, private val owner: () -> String, private val permitted: () -> Boolean,
    private val region: () -> String, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val owner: String, val region: String, val query: ExploreQuery)
    private class Entry(val session: ExploreSession, val created: Long) { val lock = Mutex() }
    private val entries = LinkedHashMap<Key, Entry>(8, .75f, true)
    suspend fun page(input: CollectionSource, skip: Int): CollectionPage {
        val query = input.exploreQuery ?: error("Missing Discover criteria")
        check(permitted() && owner() == input.ownerProfile)
        val key = Key(owner(), region(), query)
        val entry = synchronized(entries) {
            val old = entries[key]?.takeIf { clock() - it.created < 15 * 60_000 }
            (old ?: Entry(ExploreSession(query, key.region, source), clock()).also {
                entries[key] = it
                while (entries.size > 8) entries.remove(entries.keys.first())
            })
        }
        return entry.lock.withLock {
            val session = entry.session
            require(skip in 0..100)
            if (!session.complete) session.advance(budget = 8)
            currentCoroutineContext().ensureActive()
            check(permitted() && owner() == key.owner && region() == key.region)
            if (!session.complete) return@withLock CollectionPage(emptyList(), skip, false)
            val items = session.results.drop(skip).take(20).map { it.meta }
            CollectionPage(items, skip + items.size, session.complete && skip + items.size >= session.results.size || skip + items.size >= 100,
                CollectionPage.fingerprint(items.map { it.id }))
        }
    }
    fun clear() = synchronized(entries) { entries.clear() }
}
