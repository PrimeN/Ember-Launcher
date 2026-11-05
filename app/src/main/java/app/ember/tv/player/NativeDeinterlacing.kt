package app.ember.tv.player

/** CPU filters require readable frames; an explicit software-decoder choice keeps priority. */
internal object NativeDeinterlacing {
    fun mode(value: String): String = when(value) { "auto" -> "auto"; "always" -> "yes"; else -> "no" }
    fun decoder(software: Boolean, value: String): String = when {
        software -> "no"
        mode(value) != "no" -> "mediacodec-copy"
        else -> "mediacodec,mediacodec-copy"
    }
}
