package app.ember.tv

import app.ember.tv.iptv.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class IptvGuideToolsTest {
    private val channel = Channel("a:1", "a", "BBC One HD", "https://example.com/live", epgId = "bbc.exact")
    private val feeds = listOf(EpgFeed("provider:a", "Provider", "https://example.com/guide"), EpgFeed("shared", "Shared", "https://example.com/shared"))
    private val parsed = mapOf("provider:a" to ParsedEpg(names = mapOf("bbcone" to "name.match"), labels = mapOf("name.match" to "BBC One")),
        "shared" to ParsedEpg(labels = mapOf("bbc.exact" to "BBC One", "manual" to "Another channel")))

    @Test fun legacyConfigurationKeepsProviderFeedAndExplicitEmptyAssignmentDisablesIt() {
        val old = Json.decodeFromString<EpgConfiguration>("{}")
        assertEquals(listOf("provider:a"), old.feedIds("a", feeds))
        assertTrue(old.copy(assignments = mapOf("a" to emptyList())).feedIds("a", feeds).isEmpty())
        assertEquals(listOf("shared"), old.copy(assignments = mapOf("a" to listOf("missing", "shared", "shared"))).feedIds("a", feeds))
    }
    @Test fun exactIdAcrossAllAssignedFeedsBeatsEarlierNameMatch() {
        assertEquals(EpgMatch("shared", "bbc.exact", false), EpgMatching.select(channel, feeds.map { it.id }, EpgConfiguration(), parsed))
        assertEquals(EpgMatch("provider:a", "name.match", false), EpgMatching.select(channel.copy(epgId = null), feeds.map { it.id }, EpgConfiguration(), parsed))
    }
    @Test fun explicitOverrideIsAuthoritativeAndCannotReadUnassignedFeed() {
        val config = EpgConfiguration(overrides = mapOf(channel.key to EpgOverride("shared", "manual")))
        assertEquals(EpgMatch("shared", "manual", true), EpgMatching.select(channel, feeds.map { it.id }, config, parsed))
        assertNull(EpgMatching.select(channel, listOf("provider:a"), config, parsed))
        assertNull(EpgMatching.select(channel, feeds.map { it.id }, config.copy(overrides = mapOf(channel.key to EpgOverride("shared", "missing"))), parsed))
    }
    @Test fun multiplePlaylistsCanUseOneSharedFeedWithoutCrossingUnassignedFeeds() {
        val config = EpgConfiguration(assignments = mapOf("a" to listOf("shared"), "b" to listOf("shared")))
        assertEquals(EpgMatching.select(channel, config.feedIds("a", feeds), config, parsed),
            EpgMatching.select(channel.copy(key = "b:1", sourceId = "b"), config.feedIds("b", feeds), config, parsed))
        assertNull(EpgMatching.select(channel, emptyList(), config, parsed))
    }
    @Test fun offsetsShiftAndFilterWithoutMutatingProviderTimes() {
        val programmes = listOf(Programme(0, 1000, "Before"), Programme(2000, 3000, "Now"), Programme(4000, 5000, "Later"))
        val shifted = EpgMatching.shifted(programmes, 1000, 2000, 5000)
        assertEquals(listOf(Programme(3000, 4000, "Now")), shifted)
        assertEquals(2000L, programmes[1].start)
        assertEquals(86_400_000L, EpgConfiguration(offsets = mapOf("shared" to Int.MAX_VALUE)).offset("shared"))
        assertEquals(-86_400_000L, EpgConfiguration(offsets = mapOf("shared" to Int.MIN_VALUE)).offset("shared"))
    }
    @Test fun matchingNormalizationDoesNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("itv", Xmltv.norm("ITV UHD")) }
        finally { Locale.setDefault(original) }
    }
    @Test fun oldDisplayPreferencesDecodeWithSafeDefaults() {
        val old = Json.decodeFromString<LiveLibrary>("{}")
        assertEquals(6, old.guideRows)
        assertTrue(old.guideLogos)
        assertTrue(old.showProgrammePanel)
        assertFalse(old.compactProgramme)
    }
}
