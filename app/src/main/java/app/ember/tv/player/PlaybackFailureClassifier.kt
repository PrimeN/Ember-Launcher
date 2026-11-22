package app.ember.tv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException

internal enum class PlaybackFailure(val changesDecoder: Boolean = false, val changesCore: Boolean = false) {
    VIDEO_DECODER_INIT(true, true), VIDEO_DECODER_RUNTIME(true, true), AUDIO_DECODER(true, true),
    AUDIO_OUTPUT, UNSUPPORTED_FORMAT(true, true), EXTRACTOR(changesCore = true),
    NETWORK, AUTHENTICATION, SOURCE_MISSING, SOURCE_ACCESS, HTTP_REJECTED, DRM, GPU_RENDERER, UNKNOWN,
}

/** Classify codes and typed causes only. Error messages can contain private stream addresses. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackFailureClassifier {
    fun classify(error: PlaybackException): PlaybackFailure {
        val status = causes(error).filterIsInstance<InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (status != null && error.errorCode in 2000..2999) return classifyHttp(status)
        return classify(error.errorCode, (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType)
    }

    private fun classifyHttp(status: Int): PlaybackFailure = when (status) {
        401, 407 -> PlaybackFailure.AUTHENTICATION
        403, 451 -> PlaybackFailure.SOURCE_ACCESS
        404, 410 -> PlaybackFailure.SOURCE_MISSING
        408, 425, 429, 500, 502, 503, 504 -> PlaybackFailure.NETWORK
        else -> PlaybackFailure.HTTP_REJECTED
    }

    /** A remembered route is invalidated only when the renderer reports both identities. */
    fun failedVideoDecoder(error: PlaybackException): Pair<Format, String>? {
        val format = (error as? ExoPlaybackException)?.rendererFormat
            ?.takeIf { MimeTypes.isVideo(it.sampleMimeType) } ?: return null
        val name = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                causes(error).filterIsInstance<DecoderInitializationException>().firstOrNull()?.codecInfo?.name
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                causes(error).filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo?.name
            else -> null
        }
        return name?.let { format to it }
    }

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause }.take(16)

    fun classify(code: Int, mime: String? = null): PlaybackFailure = when (code) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
            if (MimeTypes.isAudio(mime)) PlaybackFailure.AUDIO_DECODER else PlaybackFailure.VIDEO_DECODER_INIT
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
            if (MimeTypes.isAudio(mime)) PlaybackFailure.AUDIO_DECODER else PlaybackFailure.VIDEO_DECODER_RUNTIME
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> PlaybackFailure.UNSUPPORTED_FORMAT
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackFailure.EXTRACTOR
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> PlaybackFailure.AUDIO_OUTPUT
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> PlaybackFailure.GPU_RENDERER
        in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> PlaybackFailure.DRM
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED -> PlaybackFailure.AUTHENTICATION
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION, PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackFailure.SOURCE_ACCESS
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure.SOURCE_MISSING
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> PlaybackFailure.HTTP_REJECTED
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_DISCONNECTED -> PlaybackFailure.NETWORK
        else -> PlaybackFailure.UNKNOWN
    }
}
