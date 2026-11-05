package app.ember.tv

import app.ember.tv.ui.GLASS_CACHED
import app.ember.tv.ui.GLASS_LIVE
import app.ember.tv.ui.GLASS_STATIC
import app.ember.tv.ui.RenderOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/** Glass follows the performance profile and image quality once Frosted glass is on. */
class GlassTierTest {
    @Test fun highQualityIsLiveBalancedIsPreparedAndLowOrEconomyIsStatic() {
        assertEquals(GLASS_LIVE, RenderOptions(glass = true, quality = "high", device = "high").glassTier)
        assertEquals(GLASS_CACHED, RenderOptions(glass = true, quality = "balanced", device = "high").glassTier)
        assertEquals(GLASS_STATIC, RenderOptions(glass = true, quality = "economy", device = "high").glassTier)
        assertEquals(GLASS_STATIC, RenderOptions(glass = true, quality = "high", device = "low").glassTier)
        assertEquals(GLASS_STATIC, RenderOptions(glass = false, quality = "high", device = "high").glassTier)
        assertEquals(GLASS_LIVE, RenderOptions(glass = true, quality = "high", device = "custom").glassTier)
    }
}
