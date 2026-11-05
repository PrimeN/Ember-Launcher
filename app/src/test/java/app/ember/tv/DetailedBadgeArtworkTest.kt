package app.ember.tv

import app.ember.tv.data.BadgePack
import app.ember.tv.data.Badges
import app.ember.tv.data.StreamInfoParser
import app.ember.tv.data.StreamOption
import app.ember.tv.data.appearance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DetailedBadgeArtworkTest {
    private val text = File("src/main/assets/badges/modern.json").readText()
    private val pack = requireNotNull(Badges.parse(text))
    private fun source(description: String) = StreamOption("Addon", "Source", description,
        "https://example.com/video", null, null, "", "", "", "", "", "", null, null, true)
    private fun names(description: String, group: String): List<String> {
        val included = pack.filters.filter { it.groupId == group }.map { it.name }.toSet()
        return Badges.namesFor(text, source(description)).filter { it in included }
    }

    @Test fun regionalAudioLanguagesAreDistinctAndSubtitleOnlyLabelsAreIgnored() {
        val samples = mapOf("Spanish" to "SPA", "Castellano" to "ESP", "Latino" to "LAT",
            "Portuguese Brazil" to "BRA", "Portuguese" to "POR")
        samples.forEach { (input, expected) -> assertEquals(listOf(expected), names(input, "gl")) }
        assertEquals(emptyList<String>(), names("English subtitles", "gl"))
        assertEquals(listOf("CZE", "SVK", "SLO", "SWE"), names("Czech Slovak Slovenian Swedish", "gl"))
    }

    @Test fun allStreamingServicesMatchTextAliasesAndTheirOwnMarkers() {
        val aliases = linkedMapOf("NF" to "NETFLIX", "AMZN" to "PRIME", "ATVP" to "APPLE TV+",
            "DSNP" to "DISNEY+", "HMAX" to "MAX", "Hulu" to "HULU", "PCOK" to "PEACOCK",
            "PMTP" to "PARAMOUNT+", "Crunchyroll" to "CRUNCHYROLL")
        assertEquals(9, pack.filters.count { it.groupId == "gs" })
        aliases.entries.forEachIndexed { index, (alias, expected) ->
            assertEquals(listOf(expected), names("Film.$alias.WEB-DL", "gs"))
            assertEquals(listOf(expected), names("\u2060" + (0x2061 + index).toChar(), "gs"))
        }
        assertEquals(emptyList<String>(), names("Mad Max", "gs"))
    }

    @Test fun structuredNetworkAndAudioLanguagesWorkWithoutFormattingThemInTheSourceName() {
        val info = StreamInfoParser.parse("Addon", Json.parseToJsonElement(
            """{"name":"Source","url":"https://example.com/video"}""").jsonObject)
            .copy(network = "AMZN", languages = listOf("English"))
        assertEquals(listOf("PRIME", "ENG"), Badges.namesFor(text, source("").copy(info = info)))
    }

    @Test fun languageArtworkTintAndFallbackKeepResolutionStylingAcrossSerialization() {
        val resolution = pack.filters.first { it.id == "r-4k" }
        val languages = pack.filters.filter { it.groupId == "gl" }
        val restored = requireNotNull(Badges.parse(Json.encodeToString(BadgePack(filters = languages)))).filters
        assertEquals(languages, restored)
        languages.forEach { badge ->
            assertEquals(resolution.tagStyle, badge.tagStyle)
            assertEquals(resolution.appearance().background, badge.appearance().background)
            assertEquals(resolution.appearance().radius, badge.appearance().radius, 0f)
            assertNull(badge.appearance().border)
            assertEquals(badge.textColor, badge.imageTint)
            assertNotEquals(badge.appearance().background, badge.appearance().foreground)
        }
        pack.filters.filter { it.groupId != "gl" }.forEach { assertNull(it.imageTint) }
    }
}
