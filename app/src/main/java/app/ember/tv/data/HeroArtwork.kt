package app.ember.tv.data

import app.ember.tv.tracker.arr
import app.ember.tv.tracker.dbl
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.long
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import kotlin.math.abs

/** Artwork only: catalog identity, descriptions, progress and playback references stay intact. */
@Serializable
data class HeroArtwork(val backgrounds: List<String> = emptyList(), val logo: String? = null, val posters: List<String> = emptyList()) {
    fun applyTo(meta: Meta): Meta {
        val server = app.ember.tv.mediaserver.ServerRef.of(meta) != null
        val background=if(server)meta.background ?: backgrounds.firstOrNull() else backgrounds.firstOrNull() ?: meta.background
        return meta.copy(background = background,
            backgroundFocusX=meta.backgroundFocusX.takeIf {background==meta.background},backgroundFocusY=meta.backgroundFocusY.takeIf {background==meta.background},
            logo = if(server)meta.logo ?: logo else logo ?: meta.logo, poster = meta.poster ?: posters.firstOrNull())
    }
    val empty get() = backgrounds.isEmpty() && logo == null && posters.isEmpty()
}

internal data class HeroIdentity(val movie: Boolean, val tmdb: Long?, val imdb: String?) {
    companion object {
        // Compiled once: this runs for every visible card (watched badges, artwork keys).
        private val TMDB_ID = Regex("^tmdb:(?:(movie|tv|series):)?([0-9]+)$")
        private val IMDB_ID = Regex("^tt[0-9]+$")
        private val TYPES = setOf("movie", "series", "tv", "show", "anime")
        fun of(meta: Meta): HeroIdentity? {
            val type = meta.type.lowercase(Locale.ROOT)
            if (type !in TYPES) return null
            val movie = type == "movie"
            val encoded = TMDB_ID.matchEntire(meta.id)
                ?.takeIf { it.groupValues[1].isEmpty() || (it.groupValues[1] == "movie") == movie }
                ?.groupValues?.get(2)?.toLongOrNull()
            val tmdb = meta.tmdbId?.takeIf { it > 0 } ?: encoded?.takeIf { it > 0 }
            val imdb = meta.imdb?.takeIf { IMDB_ID.matches(it) }
            return if (tmdb == null && imdb == null) null else HeroIdentity(movie, tmdb, imdb)
        }
    }
}

internal object HeroArtworkSelection {
    fun language(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        .takeIf { it.length in 2..3 && it.all(Char::isLetter) } ?: "en"

    private data class Image(val url: String, val language: String?, val width: Int, val height: Int,
        val votes: Int, val score: Double)

    private fun tmdbImage(j: JsonElement): Image? {
        val path = j.str("file_path")?.takeIf { Regex("^/[A-Za-z0-9_.-]+\\.(png|jpg|webp)$", RegexOption.IGNORE_CASE).matches(it) } ?: return null
        val width = j.int("width") ?: return null
        val height = j.int("height") ?: return null
        if (width <= 0 || height <= 0) return null
        return Image("https://image.tmdb.org/t/p/original$path", j.str("iso_639_1"), width, height,
            (j.int("vote_count") ?: 0).coerceAtLeast(0), (j.dbl("vote_average") ?: 0.0).takeIf { it.isFinite() }?.coerceIn(0.0,10.0) ?: 0.0)
    }

    private fun languageRank(value: String?, language: String, background: Boolean): Int = when {
        background && (value.isNullOrBlank() || value == "00") -> 0
        value == language -> if (background) 1 else 0
        value == "en" -> if (background) 2 else 1
        value.isNullOrBlank() || value == "00" -> 2
        else -> 3
    }

    /** Favor clean wide images, adequate resolution, then well-supported votes and aspect fit. */
    fun tmdb(json: JsonElement, locale: String): HeroArtwork {
        val language = language(locale)
        val images = json.obj("images") ?: json
        val backgrounds = images.arr("backdrops").list().mapNotNull(::tmdbImage)
            .filter { it.width >= 780 && it.height >= 360 && it.width.toDouble()/it.height in 1.5..2.4 }
            .sortedWith(compareBy<Image> { if (it.width >= 1280) 0 else 1 }
                .thenBy { languageRank(it.language, language, true) }
                .thenBy { if (it.width >= 1920) 0 else 1 }
                .thenByDescending { (it.score * it.votes + 70.0) / (it.votes + 10.0) }
                .thenBy { abs(it.width.toDouble()/it.height - 16.0/9) }
                .thenByDescending { it.width.coerceAtMost(3840) }
                .thenBy { it.url })
            .map { it.url }.distinct().take(2)
        val logo = images.arr("logos").list().mapNotNull(::tmdbImage)
            .filter { it.width >= 80 && it.height >= 20 && it.width.toDouble()/it.height in .8..14.0 }
            .sortedWith(compareBy<Image> { languageRank(it.language, language, false) }
                .thenBy { if (it.url.endsWith(".png", true)) 0 else 1 }
                .thenByDescending { it.width.coerceAtMost(1200) }
                .thenByDescending { (it.score * it.votes + 70.0) / (it.votes + 10.0) }
                .thenBy { it.url })
            .firstOrNull()?.url?.let { sizedLogo(it) }
        val posters = images.arr("posters").list().mapNotNull(::tmdbImage)
            .filter { it.width >= 200 && it.height >= 300 && it.width.toDouble()/it.height in .5.. .85 }
            .sortedWith(compareBy<Image> { languageRank(it.language, language, false) }
                .thenByDescending { (it.score * it.votes + 70.0) / (it.votes + 10.0) }
                .thenByDescending { it.width.coerceAtMost(1500) }.thenBy { it.url })
            .map { it.url.replace("/t/p/original/", "/t/p/w500/") }.distinct().take(2)
        return HeroArtwork(backgrounds, logo, posters)
    }

    private fun sizedLogo(url: String) = url.replace("/t/p/original/", "/t/p/w500/")

    fun fanart(json: JsonElement, movie: Boolean, locale: String): HeroArtwork {
        val language = language(locale)
        fun best(field: String, background: Boolean): List<String> = json.arr(field).list()
            .mapNotNull { j ->
                val url = j.str("url")?.toHttpUrlOrNull()?.takeIf {
                    it.host in setOf("assets.fanart.tv", "fanart.tv", "www.fanart.tv") && it.username.isBlank() && it.password.isBlank()
                }?.newBuilder()?.scheme("https")?.build()?.toString() ?: return@mapNotNull null
                Triple(url, languageRank(j.str("lang"), language, background), j.str("likes")?.toIntOrNull() ?: 0)
            }.sortedWith(compareBy<Triple<String,Int,Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first })
            .map { it.first }.distinct()
        val backgrounds = best(if (movie) "moviebackground" else "showbackground", true).take(2)
        val logo = (best(if (movie) "hdmovielogo" else "hdtvlogo", false) + best(if (movie) "movielogo" else "clearlogo", false)).firstOrNull()
        return HeroArtwork(backgrounds, logo, best(if(movie)"movieposter" else "tvposter", false).take(2))
    }
}
