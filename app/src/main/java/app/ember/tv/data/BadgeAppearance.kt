package app.ember.tv.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Packs in circulation use both numbers and strings (including px/dp) for border dimensions. */
internal object BadgeDimensionSerializer : KSerializer<Float?> {
    override val descriptor = Float.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): Float? {
        if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(Float.serializer().nullable)
        val raw = (decoder.decodeJsonElement() as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.trim().lowercase().removeSuffix("px").removeSuffix("dp").trim()
            .toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
    }

    override fun serialize(encoder: Encoder, value: Float?) =
        encoder.encodeSerializableValue(Float.serializer().nullable, value?.takeIf { it.isFinite() && it >= 0f })
}

/** Shared by text, bundled images and downloaded images, including the loading/error fallback. */
internal data class BadgeAppearance(
    val foreground: Int?,
    val background: Int?,
    val border: Int?,
    val borderWidth: Float,
    val radius: Float,
    val filled: Boolean,
)

internal fun BadgeFilter.appearance(): BadgeAppearance {
    val style = tagStyle.orEmpty().lowercase().filter(Char::isLetter)
    val filled = style !in setOf("bordered", "outline", "outlined", "plain", "none", "text")
    val bordered = style !in setOf("filled", "plain", "none", "text")
    // The badge is 24 dp tall. A stroke cannot consume it, nor can a corner exceed half its height.
    val width = borderWidth?.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(6f) ?: 1f
    val radius = borderRadius?.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(12f) ?: 6f
    return BadgeAppearance(
        foreground = Badges.color(textColor),
        background = if (filled) Badges.color(tagColor) else null,
        border = if (bordered && width > 0f) Badges.color(borderColor) else null,
        borderWidth = width,
        radius = radius,
        filled = filled,
    )
}
