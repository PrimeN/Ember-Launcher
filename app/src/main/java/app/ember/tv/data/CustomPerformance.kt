package app.ember.tv.data

import kotlinx.serialization.Serializable

/** Independent launcher effects. Image quality and glass retain their existing saved controls. */
@Serializable
data class CustomPerformance(
    val heroAnalysis:Boolean=true,
    val colorAnalysis:Boolean=true,
    val animations:Boolean=true,
    val progressiveArtwork:Boolean=true,
    val collectionMotion:Boolean=true,
    val prefetch:Int=1,
) {
    fun normalized()=copy(prefetch=prefetch.coerceIn(0,2))
}
