package app.ember.tv.data

import kotlinx.serialization.json.Json

/**
 * #236, #237: editing the custom badge pack one pattern at a time, from the TV or the phone page.
 * Every function returns a new pack; nothing here touches storage.
 */
internal object BadgeEditor {
    private val writer = Json { encodeDefaults = false; explicitNulls = false; prettyPrint = true }

    fun encode(pack: BadgePack): String = writer.encodeToString(BadgePack.serializer(), pack)

    /** Colours offered as swatches; any hex colour can still be typed on the phone. */
    val swatches = listOf(
        "#FFFFFF", "#000000", "#2B2D31", "#5B5D63", "#E50914", "#FF7A2F", "#F5B942", "#34D399", "#22B8CF", "#4F94FF", "#8B5CF6", "#FF5C8A",
    )
    val opacitySteps = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    val radiusSteps = listOf(0, 2, 4, 6, 8, 10, 12)
    /** Border thickness in dp; 0 is no border. */
    val borderSteps = listOf(0, 1, 2, 3, 4, 6)

    /** Why a pattern cannot be used, or null when it can. */
    fun check(pattern: String): String? {
        if (pattern.isBlank()) return "Enter a pattern."
        if (pattern.length > BadgePatterns.MAX_LENGTH) return "The pattern is too long (at most ${BadgePatterns.MAX_LENGTH} characters)."
        val regex = runCatching { Regex(pattern) }.getOrElse { return "That pattern is not a valid regular expression: ${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "check the brackets"}." }
        if (!BadgePatterns.safe(regex)) return "That pattern repeats a group that repeats itself, like (a+)+, which can freeze the TV. Simplify it."
        return null
    }

    /** True or false for a sample source name, or null when the pattern cannot run. */
    fun matches(pattern: String, sample: String): Boolean? =
        if (check(pattern) != null) null else runCatching { Regex(pattern).containsMatchIn(sample) }.getOrNull()

    /** Every filter gets an id, so the TV and phone can name one without its position. */
    fun withIds(pack: BadgePack): BadgePack {
        val used = pack.filters.mapNotNull { it.id.takeIf(String::isNotBlank) }.toMutableSet()
        return pack.copy(filters = pack.filters.map { f -> if (f.id.isNotBlank()) f else f.copy(id = freshId(used).also { used += it }) })
    }

    private fun freshId(used: Set<String>): String {
        var n = used.size + 1
        while ("b-$n" in used) n++
        return "b-$n"
    }

    fun add(pack: BadgePack, name: String, pattern: String): BadgePack {
        val ided = withIds(pack)
        val id = freshId(ided.filters.map { it.id }.toSet())
        return ided.copy(filters = ided.filters + BadgeFilter(id = id, name = name.trim().ifBlank { "New badge" }.take(60), pattern = pattern,
            tagColor = "#5B5D63", textColor = "#FFFFFF", borderRadius = 6f))
    }

    fun update(pack: BadgePack, id: String, change: (BadgeFilter) -> BadgeFilter): BadgePack =
        pack.copy(filters = pack.filters.map { if (it.id == id) change(it) else it })

    fun delete(pack: BadgePack, id: String): BadgePack = pack.copy(filters = pack.filters.filterNot { it.id == id })

    fun move(pack: BadgePack, id: String, step: Int): BadgePack {
        val from = pack.filters.indexOfFirst { it.id == id }
        if (from < 0) return pack
        val to = (from + step).coerceIn(0, pack.filters.lastIndex)
        if (to == from) return pack
        val list = pack.filters.toMutableList()
        list.add(to, list.removeAt(from))
        return pack.copy(filters = list)
    }

    /** "#RRGGBB" of a colour (any accepted form), or null for none. */
    fun rgb(hex: String?): String? = Badges.color(hex)?.let { String.format(java.util.Locale.ROOT, "#%06X", it and 0xFFFFFF) }

    /** Opacity in percent of a colour; a missing colour counts as fully opaque. */
    fun opacity(hex: String?): Int = Badges.color(hex)?.let { ((it ushr 24) * 100 + 127) / 255 } ?: 100

    /** [rgb] with [percent] opacity, as Android's #AARRGGBB; null keeps no colour. */
    fun withOpacity(rgb: String?, percent: Int): String? {
        val colour = Badges.color(rgb) ?: return null
        val alpha = (percent.coerceIn(0, 100) * 255 + 50) / 100
        return if (alpha == 255) String.format(java.util.Locale.ROOT, "#%06X", colour and 0xFFFFFF)
        else String.format(java.util.Locale.ROOT, "#%02X%06X", alpha, colour and 0xFFFFFF)
    }

    /** A new tag colour keeps the tag's opacity. */
    fun tagColour(f: BadgeFilter, rgb: String?): BadgeFilter = f.copy(tagColor = rgb?.let { withOpacity(it, opacity(f.tagColor)) })

    fun nearest(steps: List<Int>, value: Float?, fallback: Int): Int =
        value?.let { v -> steps.minByOrNull { kotlin.math.abs(it - v) } } ?: fallback

    /** Which bordered style a filter draws with; a thickness above zero turns the border on. */
    fun border(f: BadgeFilter, width: Int): BadgeFilter = f.copy(borderWidth = width.toFloat(),
        borderColor = if (width > 0) f.borderColor ?: "#FFFFFF" else f.borderColor,
        tagStyle = if (width > 0 && f.tagStyle.orEmpty().lowercase().filter(Char::isLetter) in setOf("filled", "plain", "none", "text")) null else f.tagStyle)

    /** A short line about how a badge is drawn, for lists. */
    fun summary(f: BadgeFilter): String = buildList {
        if (!f.isEnabled) add("Off")
        add(if (!f.imageURL.isNullOrBlank()) "Image" else "Text tag")
        rgb(f.tagColor)?.let { add("Colour $it") }
    }.joinToString(" · ")

    /** Pictures sent from the phone: PNG, JPEG, WebP or GIF, recognised by their first bytes. */
    fun imageType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        bytes.size >= 6 && String(bytes, 0, 4, Charsets.US_ASCII) == "GIF8" -> "gif"
        else -> null
    }

    const val MAX_ICON_BYTES = 512_000
}
