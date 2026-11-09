package app.ember.tv.data

/** Stored independently from artwork quality; existing live/cached preferences remain valid. */
object GlassStyle {
    /** Stock Google TV / Android TV (#147): solid surfaces, no blur, highlight or edge line. */
    const val SOLID = "solid"
    val choices = listOf("live" to "Auto", SOLID to "Google TV", "tint" to "Tint", "cached" to "Frosted", "liquid" to "Liquid")
    fun normalize(value: String?) = value?.takeIf { candidate -> choices.any { it.first == candidate } } ?: "live"
}
