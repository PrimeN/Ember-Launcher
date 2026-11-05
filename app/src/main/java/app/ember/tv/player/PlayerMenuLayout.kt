package app.ember.tv.player

/** Compact panels occupy roughly one third of the previous area, with readable labels. */
internal object PlayerMenuLayout {
    const val QUICK_WIDTH=280
    const val PANEL_WIDTH=360
    const val ROW_HEIGHT=32
    val categories=listOf("playback" to "Playback and queue","video" to "Picture and decoding","audio" to "Audio","subtitles" to "Subtitles")
}
