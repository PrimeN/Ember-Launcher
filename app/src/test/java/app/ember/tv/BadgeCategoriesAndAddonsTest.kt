package app.ember.tv

import app.ember.tv.data.BadgeFilter
import app.ember.tv.data.Badges
import app.ember.tv.data.DetailedBadgeCategories
import app.ember.tv.ui.AddonSummary
import org.junit.Assert.*
import org.junit.Test

class BadgeCategoriesAndAddonsTest {
    private fun b(name: String, group: String) = BadgeFilter(id = name, name = name, pattern = name, groupId = group)

    @Test fun categoriesShowInTheChosenOrder() {
        // #397: move a category up and its badges come first under a source.
        val badges = listOf(b("Remux", "gq"), b("4K", "gr"), b("Atmos", "ga"), b("NETFLIX", "gs"))
        assertEquals(listOf("gq", "gr", "ga", "gs"), DetailedBadgeCategories.order(emptyList(), badges))
        val moved = DetailedBadgeCategories.move(emptyList(), badges, "ga", -1)
        assertEquals(listOf("gq", "ga", "gr", "gs"), moved)
        assertEquals(listOf("Remux", "Atmos", "4K", "NETFLIX"), DetailedBadgeCategories.sorted(Badges.MODERN, moved, badges).map { it.name })
        // The first can't go earlier; other badge packs keep their own order.
        assertEquals(moved, DetailedBadgeCategories.move(moved, badges, "gq", -1))
        assertEquals(badges, DetailedBadgeCategories.sorted(Badges.CUSTOM, moved, badges))
    }

    @Test fun streamingBadgesHaveTheSoundBadgesBorder() {
        // #396
        val pack = java.io.File("src/main/assets/badges/modern.json").readText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(pack).let { it as kotlinx.serialization.json.JsonObject }
        val filters = (json["filters"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
        fun borders(group: String) = filters.filter { it["groupId"].toString().trim('"') == group }.map { it["borderColor"].toString().trim('"') }.toSet()
        assertEquals(borders("ga"), borders("gs"))
    }

    @Test fun anAddonCardSaysWhatItHasAndWhatItDoes() {
        // #400
        assertEquals("97 catalogs · movie, series, anime.movie, anime.series +4",
            AddonSummary.has(97, listOf("movie", "series", "anime.movie", "anime.series", "anime", "Trakt", "collection", "person")))
        assertEquals("1 catalog", AddonSummary.has(1, emptyList()))
        assertEquals("Rows · Details · Subtitles", AddonSummary.does(listOf("catalog", "meta", "subtitles"), false))
        assertEquals("Nothing this launcher uses", AddonSummary.does(emptyList(), false))
    }
}
