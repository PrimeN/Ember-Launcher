package app.ember.tv

import androidx.compose.ui.unit.dp
import app.ember.tv.data.BadgeFilter
import app.ember.tv.data.BadgePack
import app.ember.tv.data.Badges
import app.ember.tv.data.appearance
import app.ember.tv.ui.MenuSizing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CustomBadgeAppearanceTest {
    private fun filter(fields: String): BadgeFilter = requireNotNull(Badges.parse(
        """{"filters":[{"name":"4K","pattern":"2160p",$fields}]}"""
    )).filters.single()

    @Test fun imagePackKeepsAllAppearanceFieldsAcrossSerialization() {
        val badge = filter(""""imageURL":"https://example.com/4k.png","tagColor":"#80112233","textColor":"#FFEEDD","borderColor":"#FF00FF37","borderWidth":2.5,"borderRadius":8,"tagStyle":"filled and bordered"""")
        val restored = requireNotNull(Badges.parse(Json.encodeToString(BadgePack(filters = listOf(badge))))).filters.single()
        assertEquals(badge, restored)
        assertEquals("https://example.com/4k.png", restored.imageURL)
        with(restored.appearance()) {
            assertEquals(0x80112233.toInt(), background)
            assertEquals(0xFFFFEEDD.toInt(), foreground)
            assertEquals(0xFF00FF37.toInt(), border)
            assertEquals(2.5f, borderWidth, 0f)
            assertEquals(8f, radius, 0f)
        }
    }

    @Test fun alternateExportNamesAndStringDimensionsAreRead() {
        val badge = filter(""""image":"https://example.com/b.png","backgroundColor":"#123456","color":"#ABCDEF","borderColor":"#123","borderSize":"2px","cornerRadius":"4dp"""")
        assertEquals("https://example.com/b.png", badge.imageURL)
        with(badge.appearance()) {
            assertEquals(0xFF123456.toInt(), background)
            assertEquals(0xFFABCDEF.toInt(), foreground)
            assertEquals(0xFF112233.toInt(), border)
            assertEquals(2f, borderWidth, 0f)
            assertEquals(4f, radius, 0f)
        }
        assertEquals(3f, filter(""""borderThickness":"3"""").appearance().borderWidth, 0f)
        assertEquals("image.png", filter(""""imageUrl":"image.png"""").imageURL)
    }

    @Test fun fillOutlineAndPlainStylesRespectExplicitChoices() {
        val base = BadgeFilter(tagColor = "#123456", borderColor = "#ABCDEF", borderWidth = 2f)
        assertNull(base.copy(tagStyle = "outlined").appearance().background)
        assertNotNull(base.copy(tagStyle = "outlined").appearance().border)
        assertNull(base.copy(tagStyle = "filled").appearance().border)
        assertNotNull(base.copy(tagStyle = "filled").appearance().background)
        with(base.copy(tagStyle = "plain").appearance()) {
            assertNull(background)
            assertNull(border)
            assertFalse(filled)
        }
        assertNull(base.copy(borderWidth = 0f).appearance().border)
    }

    @Test fun invalidAndExcessiveDimensionsCannotBreakTheSourceRow() {
        for (value in listOf("-1", "\"NaN\"", "\"Infinity\"", "\"wide\"", "{}", "null")) {
            val appearance = filter(""""borderWidth":$value,"borderRadius":$value""").appearance()
            assertEquals(1f, appearance.borderWidth, 0f)
            assertEquals(6f, appearance.radius, 0f)
        }
        with(filter(""""borderWidth":100000,"borderRadius":100000""").appearance()) {
            assertEquals(6f, borderWidth, 0f)
            assertEquals(12f, radius, 0f)
        }
        assertEquals(1f, BadgeFilter(borderWidth = Float.NaN).appearance().borderWidth, 0f)
    }

    @Test fun transparentAndArgbColoursKeepTheirAlpha() {
        assertEquals(0, Badges.color("transparent"))
        assertEquals(0x80112233.toInt(), Badges.color(" #80112233 "))
        assertEquals(0x88112233.toInt(), Badges.color("#8123"))
        assertEquals(0xFF112233.toInt(), Badges.color("#123"))
        for (bad in listOf("", "#12", "#12345", "#-12345", "#GGGGGG", "#FFFFFFFFF")) assertNull(Badges.color(bad))
    }

    @Test fun bundledPackStillSuppliesItsOwnColoursAndImages() {
        val pack = requireNotNull(Badges.parse(File("src/main/assets/badges/modern.json").readText()))
        assertTrue(pack.filters.isNotEmpty())
        pack.filters.forEach { badge ->
            assertFalse(badge.imageURL.isNullOrBlank())
            assertNotNull(badge.appearance().background)
            if (badge.tagStyle == "filled") assertNull(badge.appearance().border)
            else assertNotNull(badge.appearance().border)
        }
    }

    @Test fun mediaTitlesDoNotInflateMenusButLongActionsStillFit() {
        assertEquals(MenuSizing.actionMenuWidth(240.dp, 150.dp, 900.dp),
            MenuSizing.actionMenuWidth(240.dp, 1600.dp, 900.dp))
        assertEquals(314.dp, MenuSizing.actionMenuWidth(240.dp, 1600.dp, 900.dp))
        assertEquals(202.dp, MenuSizing.actionMenuWidth(80.dp, 1600.dp, 900.dp))
        assertEquals(160.dp, MenuSizing.actionMenuWidth(240.dp, 1600.dp, 160.dp))
    }
}
