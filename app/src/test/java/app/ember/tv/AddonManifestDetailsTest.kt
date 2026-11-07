package app.ember.tv

import app.ember.tv.data.AddonInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #60: what Settings > Addons shows comes from the manifest. */
class AddonManifestDetailsTest {
    private fun parse(json: String) = AddonInfo.parse("https://a.example/manifest.json", "https://a.example", Json.parseToJsonElement(json))

    @Test fun readsVersionDescriptionLogoAndSettingsHint() {
        val a = parse("""{"id":"aio-metadata","name":"AIOMetadata","version":" 3.8.3 ","description":"A metadata addon.",
            "logo":"https://a.example/logo.svg","types":["movie","series"],"catalogs":[{"type":"movie","id":"x"}],
            "resources":["catalog","meta"],"behaviorHints":{"configurable":true}}""")
        assertEquals("3.8.3", a.version)
        assertEquals("A metadata addon.", a.description)
        assertEquals("https://a.example/logo.svg", a.logo)
        assertEquals("aio-metadata", a.manifestId)
        assertTrue(a.configurable)
        assertEquals(1, a.catalogs.size)
    }

    @Test fun missingOrUnsafeDetailsAreLeftOut() {
        val a = parse("""{"name":"Plain","version":"","icon":"javascript:alert(1)","resources":[]}""")
        assertNull(a.version); assertNull(a.description); assertNull(a.logo); assertFalse(a.configurable)
        assertEquals("https://a.example/icon.png", parse("""{"name":"Icon","icon":"https://a.example/icon.png"}""").logo)
    }
}
