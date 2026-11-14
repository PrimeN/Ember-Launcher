package app.ember.tv.remote

import app.ember.tv.DetailsUi
import app.ember.tv.data.StreamOption
import java.util.UUID

/** Phone choices identify a source, never its changing position or its private playback URL. */
internal class RemoteSources {
    private data class Selection(val profile: String, val type: String, val title: String,
        val season: Int?, val episode: Int?, val episodeId: String?)
    data class Choice(val ref: String, val source: StreamOption)
    private var selection: Selection? = null
    private val references = linkedMapOf<String, StreamOption>()

    private fun current(profile: String, details: DetailsUi?): List<StreamOption> {
        val next = details?.let { d -> Selection(profile, d.meta.type, d.meta.id,
            d.episode?.season ?: d.season ?: d.meta.season, d.episode?.episode ?: d.meta.episode, d.episode?.id) }
        if (selection != next) { references.clear(); selection = next }
        val available = details?.let { RemoteFormat.sources(it.serverStreams, it.streams).take(120) }.orEmpty()
        val sources = available.toSet()
        references.entries.removeAll { it.value !in sources }
        val known = references.values.toSet()
        available.distinct().filterNot { it in known }.forEach { references[UUID.randomUUID().toString()] = it }
        return available
    }

    @Synchronized fun choices(profile: String, details: DetailsUi?): List<Choice> {
        val available = current(profile, details)
        val bySource = references.entries.associate { it.value to it.key }
        return available.map { Choice(bySource.getValue(it), it) }
    }

    @Synchronized fun resolve(profile: String, details: DetailsUi?, ref: String): StreamOption? {
        current(profile, details)
        return references[ref]
    }
}
