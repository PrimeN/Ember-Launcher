package app.ember.tv.mediaserver

import app.ember.tv.data.HeroArtwork
import app.ember.tv.data.Meta
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native artwork always gets first refusal; external backdrops precede a portrait fallback. */
internal object ServerImagePolicy {
    fun backgrounds(meta: Meta, fallback: HeroArtwork, failed: Set<String> = emptySet()): List<Pair<String, Boolean>> =
        (listOfNotNull(meta.background?.takeIf(String::isNotBlank)?.let { it to true }) +
            fallback.backgrounds.map { it to true } +
            listOfNotNull(meta.imdb?.takeIf { Regex("tt[0-9]+").matches(it) }?.let { "https://images.metahub.space/background/large/$it/img" to true }) +
            listOfNotNull(meta.poster?.takeIf(String::isNotBlank)?.let { it to false }) +
            fallback.posters.map { it to false }).distinctBy { it.first }.filterNot { it.first in failed }

    fun posters(meta: Meta, fallback: HeroArtwork, enhanced: String?, failed: Set<String> = emptySet()): List<String> =
        (listOfNotNull(enhanced, meta.poster?.takeIf(String::isNotBlank)) + fallback.posters +
            listOfNotNull(meta.imdb?.takeIf { Regex("tt[0-9]+").matches(it) }?.let { "https://images.metahub.space/poster/medium/$it/img" }))
            .distinct().filterNot { it in failed }
}

/** A failed request cannot cancel its own fallback loop by changing its effect key. */
internal suspend fun <T : Any> loadFirstServerImage(candidates: List<Pair<String, Boolean>>, load: suspend (String, Boolean) -> T?): Pair<Pair<String, Boolean>, T>? {
    for (candidate in candidates) {
        val image = load(candidate.first, candidate.second)
        currentCoroutineContext().ensureActive()
        if (image != null) return candidate to image
    }
    return null
}
