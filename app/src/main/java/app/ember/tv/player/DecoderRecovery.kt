package app.ember.tv.player

/** Automatic choices belong to one item; explicit session choices are kept separately. */
internal class DecoderRecovery {
    private val triedVideo = linkedSetOf<String>()
    private val triedAudio = linkedSetOf<String>()
    var pcm = false; private set
    fun reset() { triedVideo.clear(); triedAudio.clear(); pcm = false }
    fun pcmFallback(): Boolean { if(pcm)return false;pcm=true;return true }
    fun next(audio: Boolean, ffmpeg: Boolean, softwareAllowed: Boolean = true): String? {
        val tried=if(audio)triedAudio else triedVideo
        return listOfNotNull("software".takeIf { softwareAllowed },"ffmpeg".takeIf { ffmpeg && softwareAllowed }).firstOrNull { tried.add(it) }
    }
}
object DolbyVisionPolicy {
    /** DV5 has no HDR10 base. DV8 requires explicit HDR10 static metadata. */
    fun hdr10Base(codecs: String?, hdr10Static: Boolean): Boolean {
        val profile=Regex("(?i)(?:dvhe|dvh1)\\.(\\d{2})\\.").find(codecs.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        return profile==7 || profile==8 && hdr10Static
    }
}
