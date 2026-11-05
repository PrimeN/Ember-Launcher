package app.ember.tv

import app.ember.tv.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DetailedBadgeCategoriesTest {
    @get:Rule val temp = TemporaryFolder()
    private val text = File("src/main/assets/badges/modern.json").readText()
    private val pack = requireNotNull(Badges.parse(text))
    private fun source(value: String) = StreamOption("Addon", "Source", value, "https://example.com/video",
        null, null, "", "", "", "", "", "", null, null, true)

    @Test fun everyActiveCategoryCanBeHiddenAndRestoredWithoutChangingOtherCategories() {
        val ids = DetailedBadgeCategories.groups.map { it.first }.toSet()
        assertEquals(9, ids.size)
        assertEquals(ids, pack.filters.map { it.groupId }.toSet())
        ids.forEach { id ->
            val hidden = DetailedBadgeCategories.toggle(emptySet(), id)
            assertEquals(pack.filters.filter { it.groupId != id }, DetailedBadgeCategories.filter(Badges.MODERN, hidden, pack.filters))
            assertEquals(pack.filters, DetailedBadgeCategories.filter(Badges.MODERN, DetailedBadgeCategories.toggle(hidden, id), pack.filters))
        }
        assertTrue(DetailedBadgeCategories.filter(Badges.MODERN, ids, pack.filters).isEmpty())
        assertEquals(pack.filters, DetailedBadgeCategories.filter(Badges.CUSTOM, ids, pack.filters))
        assertEquals(pack.filters, DetailedBadgeCategories.filter(Badges.LEGACY, ids, pack.filters))
        assertEquals(setOf("future-category"), DetailedBadgeCategories.toggle(setOf("future-category"), "unknown"))
    }

    @Test fun choicesDefaultToEnabledAndSurvivePreferenceRecreation() {
        val context = PersistenceContext(temp.newFolder())
        val prefs = Prefs(context)
        assertTrue(prefs.detailedBadgeCategoriesOff.isEmpty())
        prefs.detailedBadgeCategoriesOff = setOf("gv", "gc")
        assertEquals(setOf("gv", "gc"), Prefs(context).detailedBadgeCategoriesOff)
        assertEquals("set", PreferenceKeys.expectedType("detailedBadgeCategoriesOff"))
        assertTrue(PreferenceKeys.DETAILED_BADGE_CATEGORIES_OFF.portable)
    }

    @Test fun hiddenCategoriesCannotReturnAsNativeFallbackTags() {
        val s = source("").copy(hdr = "HDR10", audio = "TrueHD 7.1", codec = "HEVC", source = "Remux")
        val hidden = setOf("gv", "ga", "gc", "gq")
        assertEquals(listOf("HEVC"), DetailedBadgeCategories.textTags(s, hidden))
        assertEquals(listOf("HDR10", "HEVC", "TrueHD 7.1", "Remux"), DetailedBadgeCategories.textTags(s, emptySet()))
        assertFalse(DetailedBadgeCategories.textTags(s, setOf("gc")).contains("TrueHD 7.1"))
    }

    @Test fun combinedDolbyBadgesAreExclusiveAndDoNotNeedReleaseFilenameHeuristics() {
        val combinations = mapOf("Atmos DV" to "Atmos+DV", "TrueHD DV" to "TrueHD+DV",
            "DD+ DV" to "DD++DV", "DD DV" to "DD+DV")
        combinations.forEach { (formats, expected) ->
            assertEquals(listOf(expected), Badges.namesFor(text, source(formats)))
            val visible = DetailedBadgeCategories.filter(Badges.MODERN, setOf("gv"),
                pack.filters.filter { it.name in Badges.namesFor(text, source(formats)) })
            assertTrue(visible.isEmpty())
        }
        assertEquals(listOf("HDR10"), Badges.namesFor(text, source("HDR10")))
        assertEquals(listOf("AAC", "2.0"), Badges.namesFor(text, source("AAC 2.0")))
        assertEquals(listOf("3D"), Badges.namesFor(text, source("3D")))
    }

    @Test fun swappedGroupsUseTierStyleAndKeepUntintedWhiteArtwork() {
        val swapped = pack.filters.filter { it.groupId in setOf("ga", "gv", "gc") }
        assertEquals(29, swapped.size)
        swapped.forEach { badge ->
            with(badge.appearance()) {
                assertEquals(0, background)
                assertEquals(0xFF858283.toInt(), border)
                assertEquals(0xFFFFFFFF.toInt(), foreground)
                assertEquals(1f, borderWidth, 0f)
                assertEquals(6f, radius, 0f)
            }
            assertNull(badge.imageTint)
            assertTrue(requireNotNull(badge.imageURL).startsWith("bundled:badges/detailed/"))
        }
    }

    @Test fun matchingDetailedBadgesReplaceOnlyTheirNativeFallbacks() {
        val info = StreamInfoParser.parse("Addon", kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"Remux HEVC English AAC 5.1","url":"https://example.com/video"}""").let {
                it as kotlinx.serialization.json.JsonObject
            }).copy(languages = listOf("English", "Persian"))
        val s = source("").copy(info = info, codec = "HEVC", source = "Remux", audio = "AAC 5.1")
        val names = Badges.namesFor(text, s)
        assertTrue(names.containsAll(listOf("Remux", "HEVC", "ENG", "AAC", "5.1")))
        val matched = pack.filters.filter { it.name in names }
        assertEquals(listOf("Persian"), DetailedBadgeCategories.textTags(s, emptySet(), badges = matched))
        assertEquals(listOf("HEVC", "AAC 5.1", "English · Persian", "Remux"),
            DetailedBadgeCategories.textTags(s, emptySet()))
    }

    @Test fun languageAliasesAndRemainingLanguageLimitDoNotDuplicateArtwork() {
        val info = StreamInfoParser.parse("Addon", kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"Source","url":"https://example.com/video"}""") as kotlinx.serialization.json.JsonObject)
            .copy(languages = listOf("en-US", "French", "German", "Hebrew", "Persian"))
        val s = source("").copy(info = info)
        val images = listOf(BadgeFilter(name = "ENG", groupId = "gl"), BadgeFilter(name = "FRE", groupId = "gl"))
        assertEquals(listOf("German · Hebrew +1"), DetailedBadgeCategories.textTags(s, emptySet(), languageLimit = 2, badges = images))
        assertTrue(DetailedBadgeCategories.textTags(s, setOf("gl"), badges = images).isEmpty())
        assertTrue(DetailedBadgeCategories.textTags(s.copy(info = info.copy(languages = listOf("Dual Audio", "Multi"))),
            emptySet(), badges = listOf(BadgeFilter(name = "MUL", groupId = "gl"))).isEmpty())
    }

    @Test fun partialArtworkPreservesMissingFormatsAndCodecCategoryCanBeHidden() {
        val s = source("").copy(hdr = "HDR10", codec = "HEVC", audio = "AAC 5.1", source = "WEB-DL")
        val language = listOf(BadgeFilter(name = "ENG", groupId = "gl"))
        assertEquals(listOf("HDR10", "HEVC", "AAC 5.1", "WEB-DL"),
            DetailedBadgeCategories.textTags(s, emptySet(), badges = language))
        assertFalse(DetailedBadgeCategories.textTags(s, setOf("gvc")).contains("HEVC"))
        val audio = listOf(BadgeFilter(name = "AAC", groupId = "ga"))
        assertTrue(DetailedBadgeCategories.textTags(s, emptySet(), badges = audio).contains("5.1"))
        assertFalse(DetailedBadgeCategories.textTags(s, emptySet(), badges = audio).contains("AAC 5.1"))
    }

    @Test fun combinedDolbyBadgesAlsoReplaceTheirAudioFallbacks() {
        for (audio in listOf("Atmos", "TrueHD", "DD+", "DD")) {
            val s = source("").copy(hdr = "DV", audio = "$audio 7.1", codec = "HEVC")
            val names = Badges.namesFor(text, s)
            assertTrue(names.any { it.endsWith("+DV") })
            val matches = pack.filters.filter { it.name in names }
            assertTrue(DetailedBadgeCategories.textTags(s, emptySet(), badges = matches).isEmpty())
        }
    }

    @Test fun videoCodecAliasesUseChannelStylingAndBundledArtworkWithoutMislabelingMpeg4Asp() {
        val codecs = pack.filters.filter { it.groupId == "gvc" }
        assertEquals(setOf("HEVC", "AVC", "AV1"), codecs.map { it.name }.toSet())
        for ((input, expected) in mapOf("HEVC" to "HEVC", "h.265" to "HEVC", "x265" to "HEVC",
            "H.264" to "AVC", "x264" to "AVC", "AVC" to "AVC", "AV1" to "AV1")) {
            assertEquals(listOf(expected), Badges.namesFor(text, source(input)).filter { it in setOf("HEVC", "AVC", "AV1") })
        }
        assertFalse(Badges.namesFor(text, source("MPEG-4 ASP Xvid")).contains("AVC"))
        codecs.forEach { badge ->
            assertEquals(pack.filters.first { it.groupId == "gc" }.appearance(), badge.appearance())
            assertTrue(File("src/main/assets/" + requireNotNull(BundledBadgeImages.path(requireNotNull(badge.imageURL)))).isFile)
        }
    }
}
