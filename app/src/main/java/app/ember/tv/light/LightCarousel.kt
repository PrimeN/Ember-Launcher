package app.ember.tv.light

import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey

internal data class LightSlide(
    val id: String, val title: String, val subtitle: String, val artwork: String?,
    val meta: Meta? = null, val external: LightMedia? = null,
    val continuing: Boolean = false,
)

internal object LightCarousel {
    /** Continue Watching stays first, then app previews and recommendations. */
    fun compose(own: List<Meta>, external: List<LightMedia>, recommendations: List<Meta>): List<LightSlide> {
        fun Meta.slide(continuing: Boolean) = LightSlide("media:${mediaKey()}", name,
            if (continuing) listOfNotNull("Continue watching", season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" · ") else "Recommended",
            background ?: poster, meta = this, continuing = continuing)
        val continueItems = (external.filter { it.continuing }.take(6).map { LightSlide(it.id, it.title,
            listOf("Continue watching", it.description).filter(String::isNotBlank).joinToString(" · "), it.artwork, external = it, continuing = true) } + own.take(8).map { it.slide(true) }
            ).take(8)
        val next = external.filterNot { it.continuing }.map { LightSlide(it.id, it.title, it.description.ifBlank { "From your apps" }, it.artwork, external = it) } + recommendations.map { it.slide(false) }
        return (continueItems + next).distinctBy { slide ->
            slide.meta?.let { "media:${it.mediaKey()}" } ?: slide.external?.let { "app:${it.pkg}:${LightMediaProvider.normalize(it.title)}:${it.description}" } ?: slide.id
        }.take(24)
    }
}
