package app.ember.tv

import app.ember.tv.phone.ContentPhone
import app.ember.tv.phone.PhoneCollection
import app.ember.tv.phone.PhoneContent
import app.ember.tv.phone.PhoneFolder
import app.ember.tv.phone.PhonePictures
import app.ember.tv.phone.PhoneSettingResult
import app.ember.tv.phone.PhoneSettingView
import app.ember.tv.phone.PhoneSetup
import app.ember.tv.phone.PhoneSetupAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI

/** #232–#234: the TV's pictures, sliders, service tiles and collection previews on the phone page. */
class PhonePicturesTest {
    private val pictured = listOf(
        PhoneSettingView("look.interface", "Look", "Interface", null, "choice", app.ember.tv.data.InterfaceStyles.options, "modern"),
        PhoneSettingView("look.navbar", "Look", "Navbar style", null, "choice", app.ember.tv.data.NavbarStyles.options, app.ember.tv.data.NavbarStyles.EMBER),
        PhoneSettingView("look.mediaPageLayout", "Look", "Media page layout", null, "choice", app.ember.tv.data.MediaPageLayouts.options, app.ember.tv.data.MediaPageLayouts.MODERN),
        PhoneSettingView("look.theme", "Look", "Theme", null, "choice", listOf("night" to "Night", "day" to "Day", "auto" to "Auto"), "night"),
        PhoneSettingView("look.accent", "Look", "Colour theme", null, "choice", app.ember.tv.ui.SettingDefinitions.colourTheme.options, "0"),
        PhoneSettingView("look.menu", "Look", "Menu position (Ember)", null, "choice", listOf("top" to "Top", "side" to "Side"), "top"),
        PhoneSettingView("look.size", "Look", "Interface size", null, "choice", app.ember.tv.ui.UiSizes.LABELS, "default"),
        PhoneSettingView("look.text", "Look", "Text size", null, "choice", app.ember.tv.ui.UiSizes.LABELS, "default"),
        PhoneSettingView("look.density", "Look", "Media card density", null, "choice", listOf("comfortable" to "Comfortable", "standard" to "Standard", "dense" to "Dense"), "standard"),
        PhoneSettingView("look.margins", "Look", "Screen margins", null, "choice", app.ember.tv.ui.MarginSteps.values.map { app.ember.tv.ui.MarginSteps.text(it) to app.ember.tv.ui.MarginSteps.label(it) }, app.ember.tv.ui.MarginSteps.text(25)),
        PhoneSettingView("look.appIcons", "Look", "App icons", null, "choice", app.ember.tv.ui.AppShapes.options, "banner"),
        PhoneSettingView("look.cards", "Look", "Home cards (Ember)", null, "choice", listOf("wide" to "Wide", "poster" to "Poster"), "wide"),
        PhoneSettingView("look.wallpaper", "Look", "Light Home wallpaper", null, "choice", app.ember.tv.data.LightLayout.backgrounds, "art"),
        PhoneSettingView("look.quality", "Look", "Image quality (Custom profile)", null, "choice", listOf("economy" to "Economy", "balanced" to "Balanced", "high" to "High"), "balanced"),
        PhoneSettingView("play.interface", "Playback", "Player interface", null, "choice", app.ember.tv.player.PlayerInterfaces.options, app.ember.tv.player.PlayerInterfaces.BASIC),
        PhoneSettingView("play.next", "Playback", "Play the next episode automatically", null, "choice", app.ember.tv.ui.NextEpisodeSteps.values.map { "$it" to app.ember.tv.ui.NextEpisodeSteps.label(it) }, "10"),
        PhoneSettingView("play.player", "Playback", "Player", null, "choice", (1..7).map { "p$it" to "Player app number $it" }, "p1"),
    )

    private class Handler(private val access: PhoneSetupAccess, private val rows: List<PhoneSettingView>) : PhoneSetup.Handler {
        override fun addAddon(url: String) = "ok"
        override fun saveKey(id: String, value: String) = "ok"
        override fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String) = "ok"
        override fun log(line: String) {}
        override fun exportBackup(passphrase: String) = ByteArray(0)
        override fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean) = "ok"
        override fun importBadges(text: String) = "ok"
        override fun importCollections(text: String) = "ok"
        override fun settings() = rows
        override fun changeSetting(id: String, value: String): PhoneSettingResult { access.requireActive(); return PhoneSettingResult(true, "Saved", rows.first { it.id == id }.copy(value = value)) }
    }

    private fun get(uri: URI, target: String): String = Socket(uri.host, uri.port).apply { soTimeout = 4000 }.use { socket ->
        socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n")
    }

    @Test fun everyPicturedChoiceHasAPictureAndTheRightColours() {
        pictured.filter { PhonePictures.has(it.id) }.forEach { s -> s.options.forEach { (v, _) ->
            val svg = PhonePictures.of(s.id, v)
            assertNotNull("${s.id}=$v", svg)
            assertTrue(svg!!.startsWith("<svg viewBox=\"0 0 80 50\"") && svg.endsWith("</svg>"))
            assertFalse("pictures load nothing", svg.contains("href") || svg.contains("http"))
        } }
        // The colour theme shows its own colour; the wallpaper its own gradient.
        assertTrue(PhonePictures.of("look.accent", "3")!!.contains(PhonePictures.hex(app.ember.tv.ui.Accents[3].second)))
        assertTrue(PhonePictures.of("look.wallpaper", "ocean")!!.contains("#235F70"))
        assertEquals(null, PhonePictures.of("look.quality", "high"))
        assertTrue(PhonePictures.stepped(listOf("0" to "Off", "5" to "5", "10" to "10", "15" to "15")))
        assertFalse("not in rising order", PhonePictures.stepped(listOf("5" to "", "0" to "", "10" to "", "15" to "")))
        assertFalse("too few to slide", PhonePictures.stepped(listOf("5" to "", "10" to "")))
        assertTrue(PhonePictures.pills(listOf("a" to "Economy", "b" to "Balanced"))); assertFalse(PhonePictures.pills((1..7).map { "$it" to "x" }))
    }

    @Test fun settingsPagesUsePicturesSlidersAndButtons() {
        val setup = PhoneSetup({ Handler(it, pictured) }, address = { "127.0.0.1" }, port = 0, nowMillis = { System.nanoTime() / 1_000_000 })
        try {
            val uri = URI(setup.start()!!)
            val pages = listOf("ui&s=interface", "ui&s=size", "ui&s=tiles", "ui&s=effects", "player&s=playback").associateWith { get(uri, "/?${uri.rawQuery}&tab=$it") }
            val ui = pages.getValue("ui&s=interface")
            assertTrue(ui.contains("class=pics role=radiogroup aria-label=\"Theme\"") && ui.contains("value=\"day\"><span><svg"))
            val size = pages.getValue("ui&s=size")
            assertTrue("margins slide", size.contains("class=\"rng wj\" id=\"v-look.margins\"") && size.contains("<output class=wj>2.5%</output>"))
            assertTrue("without scripts a list does the same", size.contains("<noscript><select name=value aria-label=\"Screen margins\">"))
            val player = pages.getValue("player&s=playback")
            assertTrue(player.contains("id=\"v-play.next\"") && player.contains("After 10s") && player.contains("data-steps=\"[&quot;0&quot;,&quot;5&quot;"))
            assertTrue("a long list stays a list", player.contains("<select id=\"v-play.player\" name=value>"))
            assertTrue("a few short choices are buttons", pages.values.any { it.contains("class=\"picks wide\" role=radiogroup aria-label=\"Image quality (Custom profile)\"") })
            pages.values.forEach { assertEquals(Regex("<div").findAll(it).count(), Regex("</div>").findAll(it).count()) }
            // A copy of each page in the test report, to look at.
            pages.forEach { (name, html) -> println("==== PHONE PAGE $name\n$html") }
            val accounts = get(uri, "/?${uri.rawQuery}&tab=accounts")
            assertTrue(accounts.contains("<div class=svc>") && accounts.contains("<em class=req>Required</em>") && accounts.contains("<em class=opt>Optional</em>"))
            assertTrue(accounts.contains("class=\"mk "))
            println("==== PHONE PAGE accounts\n$accounts")
        } finally { setup.stop() }
    }

    @Test fun collectionsShowBackdropsAndFolderPictures() {
        val folders = (1..8).map { PhoneFolder("f-$it", "Folder $it", if (it % 2 == 0) "poster" else "wide", false, if (it == 1) "https://example.com/1.jpg" else null, listOf("s" to "Popular")) }
        val content = PhoneContent(emptyList(), emptyList(), emptyList(), library = listOf(
            PhoneCollection("c-1", "Studios", folders, "https://example.com/backdrop.jpg"),
            PhoneCollection("c-2", "Small", folders.take(2))))
        val html = ContentPhone.render(content, "lease", "collections")
        assertTrue(html.contains("<span class=cv><img loading=lazy referrerpolicy=no-referrer alt=\"\" src=\"https://example.com/backdrop.jpg\">"))
        assertTrue("without a backdrop the first folder cover stands in", Regex("src=\"https://example.com/1.jpg\"").findAll(html).count() >= 3)
        assertTrue(html.contains("<span class=strip aria-hidden=true>") && html.contains("<small>+3</small>"))
        assertTrue(html.contains("style=\"aspect-ratio:16/9\"") && html.contains("style=\"aspect-ratio:2/3\""))
        assertEquals(10, Regex("<div class=ft>").findAll(html).count())
        assertEquals(Regex("<div").findAll(html).count(), Regex("</div>").findAll(html).count())
        println("==== PHONE PAGE collections\n$html")
    }
}
