package app.ember.tv.data

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** Poster enhancement choices (Settings > Posters). Saved as JSON in preferences, so it is in backups. */
@Serializable
data class PosterConfig(
    /** "off", "rpdb" or "better". */
    val provider: String = OFF,
    // ---- RPDB (ratingposterdb.com) ----
    /** "default", "certs" (age ratings), "mc" (Metacritic), "rt" (Rotten Tomatoes). Paid keys only. */
    val rpdbStyle: String = "default",
    /** Prefer artwork without the title printed on it. Paid keys only. */
    val rpdbTextless: Boolean = false,
    /** Poster language (ISO code); null for the default. Paid keys only. */
    val rpdbLang: String? = null,
    // ---- Better Posters (btttr.cc) ----
    val bpGenre: Boolean = true,
    val bpRating: Boolean = true,
    /** Rating source code (IM, TM, RT, MC, TR, LB, RE); null for the average of all. */
    val bpRatingSource: String? = null,
    val bpQuality: Boolean = false,
    val bpAgeRating: Boolean = false,
    val bpTrending: Boolean = true,
    val bpLang: String? = null,
    // ---- Landscape cards (wide rows, Watch next) ----
    /** "title": backdrop with the title's logo; "photo": backdrop only; "rpdb": RPDB backdrop with ratings, plus the logo. */
    val landscape: String = LANDSCAPE_TITLE,
) {
    companion object {
        const val LANDSCAPE_TITLE = "title"
        const val LANDSCAPE_PHOTO = "photo"
        const val LANDSCAPE_RPDB = "rpdb"
        const val OFF = "off"
        const val RPDB = "rpdb"
        const val BETTER = "better"
    }
}

/**
 * Builds enhanced poster links. Titles need an IMDb id (RPDB also takes TMDB ids); anything else,
 * or a failed image, falls back to the addon's own poster.
 */
object Posters {
    /** Read by every poster card, so changing it in Settings redraws them. */
    var config by mutableStateOf(PosterConfig())

    /** Bumped when the RPDB key changes, so posters reload with it. */
    var keyRev by mutableIntStateOf(0)

    /** The free RPDB key that Stremio addons use. It only gives the default style. */
    const val RPDB_FREE_KEY = "t0-free-rpdb"

    /** The languages btttr.cc offers (English is the default). es-MX is RPDB's code; btttr.cc's code for Latin American Spanish is not published. */
    val LANGUAGES = listOf(
        "es" to "Spanish (Spain)", "es-MX" to "Spanish (Latin America)", "fr" to "French", "de" to "German", "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian",
        "tr" to "Turkish", "ar" to "Arabic", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "hi" to "Hindi",
        "sv" to "Swedish", "cs" to "Czech",
    )

    val RATING_SOURCES = listOf(
        "IM" to "IMDb", "TM" to "TMDB", "RT" to "Rotten Tomatoes", "MC" to "Metacritic",
        "TR" to "Trakt", "LB" to "Letterboxd", "RE" to "Roger Ebert",
    )

    val RPDB_STYLES = listOf("default" to "Default", "certs" to "Age ratings", "mc" to "Metacritic", "rt" to "Rotten Tomatoes")

    /** The RPDB key in use: the one entered in Settings > API keys, or the free one. */
    fun rpdbKey(): String = ApiKeys.get("RPDB_API_KEY").ifBlank { RPDB_FREE_KEY }

    /** Tier from the key's prefix (t0-, t1-, ...); 0 when unknown. Tier 0 only has the default style. */
    fun rpdbTier(key: String = rpdbKey()): Int = Regex("""^t(\d)-""").find(key)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun url(meta: Meta, c: PosterConfig = config): String? {
        val movie = meta.type == "movie"
        val imdb = meta.imdb
        return when (c.provider) {
            PosterConfig.RPDB -> {
                val (idType, id) = when {
                    imdb != null -> "imdb" to imdb
                    meta.tmdbId != null -> "tmdb" to (if (movie) "movie-" else "series-") + meta.tmdbId
                    else -> return null
                }
                val key = rpdbKey()
                val paid = rpdbTier(key) >= 1
                val type = if (paid) (if (c.rpdbTextless) "textless-" else "poster-") + c.rpdbStyle else "poster-default"
                val lang = if (paid) c.rpdbLang?.let { "&lang=$it" }.orEmpty() else ""
                "https://api.ratingposterdb.com/${enc(key)}/$idType/$type/$id.jpg?fallback=true$lang"
            }
            PosterConfig.BETTER -> {
                imdb ?: return null
                // Path letters: r = rating only, g = genre only, n = neither (both is plain "poster"),
                // then q for quality tags and a for age rating.
                var suffix = when {
                    c.bpGenre && c.bpRating -> ""
                    c.bpRating -> "r"
                    c.bpGenre -> "g"
                    else -> "n"
                }
                if (c.bpQuality) suffix += "q"
                if (c.bpAgeRating) suffix += "a"
                val path = if (suffix.isEmpty()) "poster" else "poster-$suffix"
                val query = listOfNotNull(
                    if (!c.bpTrending) "tag=none" else null,
                    c.bpLang?.let { "lang=$it" },
                    if (c.bpRating) c.bpRatingSource?.let { "rs=$it" } else null,
                )
                "https://btttr.cc/$path/imdb/poster-default/$imdb.jpg" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
            }
            else -> null
        }
    }

    /**
     * The title's logo for landscape cards: the addon's own, else Metahub's (Cinemeta's artwork,
     * by IMDb id; free, no key). Null with "photo", or when there is nothing to ask for.
     */
    fun logoUrl(meta: Meta, c: PosterConfig = config): String? {
        if (c.landscape == PosterConfig.LANDSCAPE_PHOTO) return null
        return meta.logo?.takeIf { it.isNotBlank() } ?: meta.imdb?.let { "https://images.metahub.space/logo/medium/$it/img" }
    }

    /** RPDB's backdrop with ratings (Tier 2 keys and up), or null to use the title's own backdrop. */
    fun landscapeUrl(meta: Meta, c: PosterConfig = config): String? {
        if (c.landscape != PosterConfig.LANDSCAPE_RPDB) return null
        val key = rpdbKey()
        if (rpdbTier(key) < 2) return null
        val imdb = meta.imdb
        val (idType, id) = when {
            imdb != null -> "imdb" to imdb
            meta.tmdbId != null -> "tmdb" to (if (meta.type == "movie") "movie-" else "series-") + meta.tmdbId
            else -> return null
        }
        return "https://api.ratingposterdb.com/${enc(key)}/$idType/backdrop-default/$id.jpg?fallback=true"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
