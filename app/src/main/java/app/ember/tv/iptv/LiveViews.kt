package app.ember.tv.iptv

/** Saved IDs keep the existing programme guide default and migrate the former list view safely. */
object LiveViews {
    const val GUIDE = "grid"
    const val CANVAS = "canvas"
    fun normalize(value: String?) = if (value == CANVAS) CANVAS else GUIDE
    fun scoped(channels: List<Channel>, source: String?) = if (source == null) channels else channels.filter { it.sourceId == source }
    fun sourceAfterChoice(current: LiveChoice, next: LiveChoice, source: String?): String? = when (next.kind) {
        "source" -> next.source
        "all" -> null
        else -> if (current.kind == "source") current.source else source
    }
}
