package app.ember.tv.mediaserver

/** Bounded presets negotiated with the server; Original keeps the selected source unchanged. */
enum class ServerQuality(val key: String, val label: String, val bitrate: Int, val width: Int, val height: Int) {
    ORIGINAL("original", "Original", 0, 0, 0),
    FULL_HD("1080p", "Up to 1080p · 8 Mbps", 8_000_000, 1920, 1080),
    HD("720p", "Up to 720p · 4 Mbps", 4_000_000, 1280, 720);
    companion object { fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ORIGINAL }
}
