package app.ember.tv.ui

import app.ember.tv.data.toHex
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** A focused action can be visible while the non-focusable title is still above the viewport. */
internal fun shouldRevealDetailsHeader(headerHeight: Int, viewportHeight: Int, topPadding: Int,
    actionsFocused: Boolean, headingFocused: Boolean, scrolled: Boolean): Boolean =
    scrolled && (headingFocused || (actionsFocused && headerHeight > 0 && headerHeight + topPadding <= viewportHeight))

/** Keep a visible hero button in place instead of moving it to the TV focus pivot. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal object DetailsBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset >= 0 && offset + size <= containerSize -> 0f
        size >= containerSize && offset <= 0 && offset + size >= containerSize -> 0f
        size >= containerSize && offset > 0 -> offset
        offset < 0 -> offset
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}

/** Sorting in-flight results must not move remote focus to a different source at the same index. */
internal fun sourceRowKeys(sources: List<app.ember.tv.data.StreamOption>): List<String> {
    val counts = mutableMapOf<String, Int>()
    return sources.map { source ->
        val identity = kotlinx.serialization.json.JsonArray(listOf(source.addonName, source.url,
            source.infoHash, source.fileIdx?.toString(), source.serverRef, source.name, source.title)
            .map { it?.let { value -> kotlinx.serialization.json.JsonPrimitive(value) } ?: kotlinx.serialization.json.JsonNull }).toString()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .toHex()
        val occurrence = counts[hash] ?: 0
        counts[hash] = occurrence + 1
        "$hash:$occurrence"
    }
}
