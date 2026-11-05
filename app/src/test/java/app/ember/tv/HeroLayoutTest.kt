package app.ember.tv

import app.ember.tv.ui.HeroLayout
import org.junit.Assert.*
import org.junit.Test

class HeroLayoutTest {
    @Test fun heroAnchorFollowsConfiguredOrderIncludingLeadingSpacer() {
        assertEquals(0, HeroLayout.index(listOf("hero", "apps")))
        assertEquals(2, HeroLayout.index(listOf("apps", "hero", "catalog")))
        assertEquals(3, HeroLayout.index(listOf("apps", "catalog", "hero")))
        assertEquals(-1, HeroLayout.index(listOf("apps")))
    }
    @Test fun visibilityUsesTheActualHeroBounds() {
        assertEquals(1f, HeroLayout.visibleFraction(100, 300, 0, 500), 0f)
        assertEquals(.5f, HeroLayout.visibleFraction(-150, 300, 0, 500), 0f)
        assertEquals(0f, HeroLayout.visibleFraction(600, 300, 0, 500), 0f)
        assertEquals(0f, HeroLayout.visibleFraction(0, 0, 0, 500), 0f)
    }
}
