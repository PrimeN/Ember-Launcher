package app.ember.tv

import app.ember.tv.state.HomeSectionsState
import org.junit.Assert.*
import org.junit.Test

class HomeCatalogLayoutTest {
    private val a = HomeSectionsState.catalogSection("addon-a:movies")
    private val b = HomeSectionsState.catalogSection("addon-b:series")
    private val base = listOf("hero", "apps", "catalogs", "servers")
    private val defaults = setOf("hero", "apps")

    @Test fun bulkSlotExpandsInPlaceWithExistingIndividualVisibility() {
        val saved = listOf("apps" to true, "catalogs" to true, "hero" to false, "servers" to true)
        val result = HomeSectionsState.catalogLayout(base, saved, linkedMapOf(a to true, b to false), defaults)
        assertEquals(listOf("apps" to true, a to true, b to false, "hero" to false, "servers" to true), result)
        assertFalse(result.any { it.first == "catalogs" })
    }

    @Test fun anIntentionalBulkOffDoesNotEnableAnyCatalogDuringMigration() {
        val result = HomeSectionsState.catalogLayout(base, listOf("catalogs" to false), linkedMapOf(a to true, b to true), defaults)
        assertFalse(result.first { it.first == a }.second)
        assertFalse(result.first { it.first == b }.second)
    }

    @Test fun catalogsKeepIndependentPositionsBetweenOtherHomeSections() {
        val saved = listOf(a to true, "hero" to true, b to true, "apps" to true, "servers" to false)
        val result = HomeSectionsState.catalogLayout(base, saved, linkedMapOf(a to true, b to true), defaults)
        assertEquals(saved, result)
        // A switch changed on Settings > Catalogs is reflected without moving the row.
        assertEquals(saved.map { if (it.first == a) a to false else it },
            HomeSectionsState.catalogLayout(base, saved, linkedMapOf(a to false, b to true), defaults))
    }

    @Test fun aNewCatalogIsInsertedWithoutResettingExistingChoicesOrOrder() {
        val c = HomeSectionsState.catalogSection("addon-c:new")
        val saved = listOf("apps" to false, a to true, "hero" to true, b to false, "servers" to true)
        val result = HomeSectionsState.catalogLayout(base, saved, linkedMapOf(a to true, b to false, c to true), defaults)
        assertEquals(saved, result.filterNot { it.first == c })
        assertEquals(result.indexOfFirst { it.first == b } + 1, result.indexOfFirst { it.first == c })
    }

    @Test fun anOfflineCatalogKeepsItsPositionAndSwitchWhenAnotherSectionMoves() {
        val saved = listOf("hero" to true, a to false, "apps" to true, b to true, "servers" to false)
        val edited = listOf("apps" to true, "hero" to true, b to true, "servers" to false)
        val retained = HomeSectionsState.retainUnavailable(edited, saved)
        assertEquals(listOf("apps" to true, "hero" to true, a to false, b to true, "servers" to false), retained)
        val restored = HomeSectionsState.catalogLayout(base, retained, linkedMapOf(a to false, b to true), defaults)
        assertEquals(retained, restored)
    }

    @Test fun offlineStartupRetainsTheLegacyBulkSlotUntilCatalogsArrive() {
        val saved = listOf("hero" to true, "catalogs" to false, "apps" to true)
        val result = HomeSectionsState.retainUnavailable(listOf("apps" to true, "hero" to true), saved)
        assertEquals(listOf("apps" to true, "hero" to true, "catalogs" to false), result)
    }

    @Test fun catalogIdsRoundTripAndCannotBreakPreferenceDelimiters() {
        val key = "https://example.com/a,b/manifest.json:series:トレンド"
        val id = HomeSectionsState.catalogSection(key)
        assertFalse(id.contains(','))
        assertFalse(id.removePrefix("catalog:").contains(':'))
        assertEquals(key, HomeSectionsState.catalogKey(id))
    }
}
