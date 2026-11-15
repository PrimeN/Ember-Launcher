package app.ember.tv.player

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerActivity.rememberWorkingDecoder() {
    val p=player ?: return
    val format=p.videoFormat ?: return
    val name=videoDecoder ?: return
    if(!config.rememberDecoderChoices || decoderLearned || !p.isPlaying || playbackEngine?.compatibility!=null || userVideoMode!=null || config.ffmpegVideo=="always")return
    if(config.decoderRules.preference(currentUrl.orEmpty(),format.sampleMimeType,codecs.sourceFilename) !in setOf("inherit","auto"))return
    val now=android.os.SystemClock.elapsedRealtime()
    if(decoderStableSince==0L || now-decoderStableSince<15_000)return
    val software=codecs.ffmpegVideo || name.contains("ffmpeg",true)
    if(software && !DecoderHealth.softwareSafe(format))return
    val health=codecs.decoderHealth ?: return
    health.remember(format,name,software);decoderLearned=true
}
