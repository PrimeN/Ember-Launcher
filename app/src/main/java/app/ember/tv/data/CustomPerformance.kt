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
    fun normalized()=copy(prefetch=prefetch.coerceIn(PREFETCH))
    companion object {
        /** Rows loaded ahead of the one in view; the phone page offers the same range (#51). */
        val PREFETCH=0..2
    }
}
