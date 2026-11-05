package app.ember.tv.data

/** One range and formatter for compact/full settings; render the exact saved value. */
object AudioTiming {
    const val MIN = -1000
    const val MAX = 1000
    const val STEP = 50
    fun adjust(value: Int, direction: Int) = (value.toLong() + direction * STEP).coerceIn(MIN.toLong(), MAX.toLong()).toInt()
    fun label(value: Int) = when {
        value == 0 -> "In step"
        value > 0 -> "Sound $value ms later"
        else -> "Sound ${-value.toLong()} ms earlier"
    }
}
