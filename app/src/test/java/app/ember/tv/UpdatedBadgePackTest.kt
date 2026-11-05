package app.ember.tv

import app.ember.tv.data.BadgePatterns
import app.ember.tv.data.Badges
import app.ember.tv.data.BundledBadgeImages
import app.ember.tv.data.StreamOption
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class UpdatedBadgePackTest {
    private val text = File("src/main/assets/badges/modern.json").readText()
    private val pack = requireNotNull(Badges.parse(text))

    private fun source(name: String = "Source", description: String = "", quality: String = "", hdr: String = "", audio: String = "") =
        StreamOption("Addon", name, description, "https://example.com/video", null, null,
            quality, hdr, "", audio, "", "", null, null, true)

    @Test fun everyBadgeHasAUniqueIdAndAnOfflineDecodableImage() {
        assertEquals(76, pack.filters.size)
        assertEquals(pack.filters.size, pack.filters.map { it.id }.toSet().size)
        pack.filters.forEach { badge ->
            assertTrue("Built-in images must load directly from the APK: ${badge.name}",
                requireNotNull(badge.imageURL).startsWith("bundled:badges/"))
            val path = requireNotNull(BundledBadgeImages.path(requireNotNull(badge.imageURL))) { badge.name }
            assertTrue(path.startsWith("badges/updated/") || path.startsWith("badges/detailed/"))
            val image = requireNotNull(ImageIO.read(File("src/main/assets/$path"))) { badge.name }
            assertTrue(image.width > 0 && image.height > 0)
            assertTrue("Pattern must be accepted by the production matcher: ${badge.name}", BadgePatterns.safe(Regex(badge.pattern)))
        }
        assertNull(BundledBadgeImages.path("https://example.com/unrelated.png"))
        // The bundled scheme only reaches PNGs inside the badge folders.
        assertNull(BundledBadgeImages.path("bundled:badges/../licenses/Apache-2.0.txt"))
        assertNull(BundledBadgeImages.path("bundled:licenses/x.png"))
    }

    @Test fun formatsOnTheDescriptionLineMatchAndHdr10PlusIsDistinct() {
        val plus = Badges.namesFor(text, source(description = "WEB-DL 2160p HDR10+ DTS-HD MA 5.1"))
        assertEquals(listOf("WebDL", "4K", "HDR10+", "DTS-HD MA", "5.1"), plus)
        assertEquals(listOf("HDR10"), Badges.namesFor(text, source(description = "HDR10")))
        for (tag in listOf("HDR10+", "HDR10plus", "HDR10p")) {
            assertEquals(listOf("HDR10+"), Badges.namesFor(text, source(description = tag)))
        }
    }

    @Test fun serverMetadataProducesBadgesWithoutEncodingFormatsInTheTitle() {
        assertTrue(pack.matchParsedDetails)
        assertEquals(listOf("1080p", "TrueHD+DV", "7.1"),
            Badges.namesFor(text, source(quality = "FHD", hdr = "DV", audio = "TrueHD 7.1")))
        assertEquals(listOf("720p"), Badges.namesFor(text, source(quality = "HD")))
        assertEquals(emptyList<String>(), Badges.namesFor(text, source()))
    }

    @Test fun languageBadgesAreBundledAndMultilineSourcesMatch() {
        assertEquals(listOf("ENG", "FRE", "MUL"),
            Badges.namesFor(text, source(description = "English French Dual Audio")))
        val languages = pack.filters.filter { it.groupId == "gl" }
        assertEquals(27, languages.size)
        languages.forEach { assertNotNull(BundledBadgeImages.path(requireNotNull(it.imageURL))) }
    }

    @Test fun existingCustomPacksKeepTheirOriginalMatchingInput() {
        val custom = """{"filters":[{"name":"4K","pattern":"4K"}]}"""
        assertFalse(requireNotNull(Badges.parse(custom)).matchParsedDetails)
        assertEquals(emptyList<String>(), Badges.namesFor(custom, source(quality = "4K")))
        assertEquals(listOf("4K"), Badges.namesFor(custom, source(name = "4K")))
    }
}
