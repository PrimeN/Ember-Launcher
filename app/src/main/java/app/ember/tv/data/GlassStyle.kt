package app.ember.tv.data

/** Stored independently from artwork quality; existing live/cached preferences remain valid. */
object GlassStyle {
    val choices = listOf("live" to "Auto", "tint" to "Tint", "cached" to "Frosted", "liquid" to "Liquid")
    fun normalize(value: String?) = value?.takeIf { candidate -> choices.any { it.first == candidate } } ?: "live"
}
