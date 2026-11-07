package app.ember.tv.ui

/** Settings > Look > Size > Interface size and Text size: two steps smaller and two larger than the default. */
object UiSizes {
    val LABELS = listOf(
        "xsmall" to "Smallest",
        "small" to "Smaller",
        "default" to "Default",
        "large" to "Larger",
        "xlarge" to "Largest",
    )

    /**
     * Scale for everything on screen (#38). Default is the former Smaller (0.88); the steps keep the
     * former ratios around it, so the former Default is now Larger.
     */
    fun screen(id: String): Float = when (id) {
        "xsmall" -> 0.67f
        "small" -> 0.77f
        "large" -> 1f
        "xlarge" -> 1.15f
        else -> 0.88f
    }

    /**
     * A size saved before #38 (preference "uiSize", old scale), as the new step showing about the
     * same size. Default, or never changed, takes the new smaller Default as requested.
     */
    fun fromLegacy(id: String?): String = when (id) {
        "xsmall" -> "small"   // 0.76 -> 0.77
        "small" -> "default"  // 0.88 -> 0.88
        "large" -> "xlarge"   // 1.15 -> 1.15
        "xlarge" -> "xlarge"  // 1.30 -> 1.15, the largest step now
        else -> "default"
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
