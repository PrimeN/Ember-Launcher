package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.data.MetaConfig
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Posters
import app.ember.tv.data.RatingSource
import app.ember.tv.data.Ratings

/** Settings > Metadata, Ratings and Posters. Split out of HomeViewModel. */
class MetaSettingsState(private val prefs: Prefs) {
    var metaConfig by mutableStateOf(prefs.metaConfig); private set

    fun updateMetaConfig(change: (MetaConfig) -> MetaConfig) {
        metaConfig = change(metaConfig)
        prefs.metaConfig = metaConfig
    }

    // ---- ratings ----
    var ratingsOn by mutableStateOf(prefs.ratingsOn); private set
    var ratingsOrder by mutableStateOf(prefs.ratingsOrder); private set
    var ratingsOff by mutableStateOf(prefs.ratingsOff); private set
    val ratingSources: List<RatingSource> get() = Ratings.ordered(ratingsOrder)

    fun updateRatingsOn(on: Boolean) {
        ratingsOn = on
        prefs.ratingsOn = on
    }

    fun toggleRatingSource(id: String) {
        ratingsOff = if (id in ratingsOff) ratingsOff - id else ratingsOff + id
        prefs.ratingsOff = ratingsOff
    }

    fun moveRatingSource(id: String, delta: Int) {
        val list = ratingSources.map { it.id }.toMutableList()
        val i = list.indexOf(id)
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        ratingsOrder = list
        prefs.ratingsOrder = list
    }

    // ---- posters ----
    var posterConfig by mutableStateOf(prefs.posterConfig.also { Posters.config = it }); private set

    fun updatePosterConfig(change: (PosterConfig) -> PosterConfig) {
        posterConfig = change(posterConfig)
        prefs.posterConfig = posterConfig
        Posters.config = posterConfig
    }
}
