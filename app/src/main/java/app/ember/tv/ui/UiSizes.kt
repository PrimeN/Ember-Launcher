package app.ember.tv.ui

/** Settings > Appearance > Interface size and Text size: two steps smaller and two larger than the default. */
object UiSizes {
    val LABELS = listOf(
        "xsmall" to "Smallest",
        "small" to "Smaller",
        "default" to "Default",
        "large" to "Larger",
        "xlarge" to "Largest",
    )

    /** Scale for everything on screen. */
    fun screen(id: String): Float = when (id) {
        "xsmall" -> 0.76f
        "small" -> 0.88f
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> 1f
    }

    /** Text on top of that. */
    fun text(id: String): Float = when (id) {
        "xsmall" -> 0.8f
        "small" -> 0.9f
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> 1f
    }
}
