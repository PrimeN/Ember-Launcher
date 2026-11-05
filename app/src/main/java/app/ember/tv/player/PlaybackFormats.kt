package app.ember.tv.player

/** Names describe the input signal; a source label or display capability is not proof of HDR output. */
internal object PlaybackFormats {
    fun video(mime: String?, codecs: String?): String = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/vvc", "video/h266" -> "VVC/H.266"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/avs3" -> "AVS3"
        "video/mpeg2" -> "MPEG-2"
        "video/dolby-vision" -> when {
            codecs.orEmpty().startsWith("dav1", true) -> "AV1"
            codecs.orEmpty().startsWith("dva", true) -> "H.264"
            else -> "HEVC"
        }
        else -> mime?.substringAfter('/') ?: "?"
    }

    fun dolbyVision(mime: String?, codecs: String?): Boolean = mime == "video/dolby-vision" ||
        listOf("dvhe", "dvh1", "dav1", "dvav", "dva1").any { codecs.orEmpty().startsWith(it, true) }

    fun hdr(mime: String?, codecs: String?, transfer: Int?, hdr10Plus: Boolean = false, unknown: Boolean = false): String = when {
        dolbyVision(mime, codecs) -> "Dolby Vision"
        hdr10Plus -> "HDR10+ metadata observed"
        transfer == 6 -> "HDR10/PQ"
        transfer == 7 -> "HLG"
        unknown && transfer == null -> "HDR not reported"
        else -> "SDR"
    }

    fun audio(mime: String?): String? = when (mime) {
        "audio/ac4" -> "Dolby AC-4"
        "audio/mhm1", "audio/mha1", "audio/mpeg-h", "audio/mpegh_3d_audio" -> "MPEG-H 3D Audio"
        "audio/iamf" -> "IAMF"
        else -> null
    }

    fun nativeFirst(mime: String?, standardSupported: Boolean = false) = !standardSupported && mime in setOf("video/vvc", "video/h266")
}
