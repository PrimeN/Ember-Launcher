package app.ember.tv.data

/** #330: "1 channel", "2 channels": a count with the right form of its noun. */
internal fun Int.counted(one: String, many: String = one + "s"): String = "$this ${if (this == 1) one else many}"
internal fun Long.counted(one: String, many: String = one + "s"): String = "$this ${if (this == 1L) one else many}"
