package app.ember.tv.player

import app.ember.tv.data.InterfaceStyles

/** Player layout is independent of the launcher once a user makes an explicit choice. */
object PlayerInterfaces {
    const val BASIC = "basic"
    const val MODERN = "modern"
    const val EMBER_X = "ember_x"
    /** Streaming-app layout: transport top left, title top right, logo on the left, tracks under the bar. */
    const val STREAMING = "streaming"
    /** #192: the names changed (Basic, Ember Modern, Ember X before); the stored values did not, so a saved choice keeps its layout. */
    val options = listOf(BASIC to "Ember", MODERN to "Glass", EMBER_X to "Basic", STREAMING to "Streaming")

    fun normalize(value: String?): String = when (value) {
        BASIC, "simple" -> BASIC
        EMBER_X -> EMBER_X
        STREAMING -> STREAMING
        else -> MODERN
    }

    /** Old settings/backups retain their previous effective layout until explicitly changed. */
    fun resolve(config: PlaybackConfig, launcher: String): String = config.playerInterface?.let(::normalize)
        ?: if (InterfaceStyles.normalize(launcher) == InterfaceStyles.EMBER_X) EMBER_X else normalize(config.playerLook)

    fun customControls(style: String, live: Boolean, trailer: Boolean): Boolean =
        !live && !trailer

    fun layoutStyle(style: String): String = if (normalize(style) == EMBER_X) InterfaceStyles.EMBER_X else InterfaceStyles.MODERN
}
