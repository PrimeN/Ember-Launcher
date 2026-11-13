package app.ember.tv

import app.ember.tv.data.BadgeEditor
import app.ember.tv.data.BadgeFilter
import app.ember.tv.data.BadgePack
import app.ember.tv.data.Badges
import app.ember.tv.phone.BadgePhone
import app.ember.tv.phone.PhoneBadges
import app.ember.tv.phone.PhoneSetup
import app.ember.tv.phone.PhoneSetupAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder

/** #235–#237: a longer phone session, and editing badges on the TV and the phone. */
class BadgeEditingTest {
    @Test fun phoneSetupLastsHalfAnHour() {
        assertEquals(30 * 60 * 1000L, PhoneSetupAccess.DURATION_MILLIS)
        // What the TV and the privacy notes tell people matches.
        val legal = app.ember.tv.data.Legal.let { l -> (l.PRIVACY + l.OPEN_SOURCE + l.SERVICES).flatMap { it.items }.joinToString("\n") { it.text } + l.DISCLAIMER.joinToString("\n") }
        val strings = java.io.File("src/main/res/values/strings_ui.xml").readText()
        listOf(legal, strings).forEach { assertTrue(it.contains("30 minutes")); assertFalse(it.contains("after 15 minutes")) }
    }

    @Test fun patternsAreCheckedBeforeTheyAreUsed() {
        assertNull(BadgeEditor.check("\\bREMUX\\b"))
        assertNotNull(BadgeEditor.check(""))
        assertTrue(BadgeEditor.check("(REMUX")!!.startsWith("That pattern is not a valid"))
        assertTrue(BadgeEditor.check("(a+)+")!!.contains("freeze"))
        assertEquals(true, BadgeEditor.matches("(?i)\\bremux\\b", "Movie 2160p REMUX DV"))
        assertEquals(false, BadgeEditor.matches("\\bWEB\\b", "Movie BluRay"))
        assertNull(BadgeEditor.matches("(", "x"))
    }

    @Test fun editsAddMoveChangeAndDelete() {
        val pack = BadgeEditor.withIds(BadgePack(listOf(BadgeFilter(name = "4K", pattern = "4K"), BadgeFilter(id = "b-9", name = "HDR", pattern = "HDR"))))
        assertTrue(pack.filters.all { it.id.isNotBlank() }); assertEquals(2, pack.filters.map { it.id }.toSet().size)
        val added = BadgeEditor.add(pack, "REMUX", "\\bREMUX\\b")
        assertEquals("REMUX", added.filters.last().name); assertEquals(3, added.filters.map { it.id }.toSet().size)
        val id = added.filters.last().id
        assertEquals(id, BadgeEditor.move(added, id, -1).filters[1].id)
        assertEquals(added, BadgeEditor.move(added, added.filters.first().id, -1))
        val renamed = BadgeEditor.update(added, id) { it.copy(name = "Remux") }
        assertEquals("Remux", renamed.filters.last().name)
        assertEquals(2, BadgeEditor.delete(renamed, id).filters.size)
        // Saved packs read back the same.
        assertEquals(renamed, Badges.parse(BadgeEditor.encode(renamed)))
    }

    @Test fun coloursKeepTheirOpacity() {
        assertEquals("#80FF0000", BadgeEditor.withOpacity("#FF0000", 50))
        assertEquals("#FF0000", BadgeEditor.withOpacity("#ff0000", 100))
        assertEquals(50, BadgeEditor.opacity("#80FF0000")); assertEquals(100, BadgeEditor.opacity(null))
        assertEquals("#FF0000", BadgeEditor.rgb("#80FF0000")); assertNull(BadgeEditor.rgb(null))
        val half = BadgeFilter(tagColor = "#80FF0000")
        assertEquals("#8034D399", BadgeEditor.tagColour(half, "#34D399").tagColor)
        val bordered = BadgeEditor.border(BadgeFilter(tagStyle = "filled"), 2)
        assertEquals(2f, bordered.borderWidth); assertEquals("#FFFFFF", bordered.borderColor); assertNull(bordered.tagStyle)
        assertEquals(6, BadgeEditor.nearest(BadgeEditor.radiusSteps, null, 6)); assertEquals(4, BadgeEditor.nearest(BadgeEditor.radiusSteps, 4.4f, 6))
    }

    @Test fun onlyPicturesAreAcceptedAsIcons() {
        assertEquals("png", BadgeEditor.imageType(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("jpg", BadgeEditor.imageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("gif", BadgeEditor.imageType("GIF89a".toByteArray()))
        assertNull(BadgeEditor.imageType("<svg onload=alert(1)>".toByteArray()))
    }

    @Test fun thePhonePageShowsEachBadgeAsDrawnAndItsForm() {
        val filters = listOf(
            BadgeFilter(id = "b-1", name = "REMUX <1>", pattern = "\\bREMUX\\b", tagColor = "#80E50914", textColor = "#FFFFFF", borderRadius = 4f),
            BadgeFilter(id = "b-2", name = "DV", pattern = "DV", imageURL = "file:///data/x/badge-icons/a.png", isEnabled = false),
            BadgeFilter(id = "b-3", name = "Atmos", pattern = "Atmos", imageURL = "https://example.com/atmos.png", borderColor = "#FFFFFF", borderWidth = 2f))
        val html = BadgePhone.render(PhoneBadges(Badges.CUSTOM, filters), "?k=code")
        assertTrue(html.contains("REMUX &lt;1&gt;") && !html.contains("<1>"))
        assertTrue("colours as the TV draws them", html.contains("background:rgba(229,9,20,0.50)") && html.contains("border-radius:4px"))
        assertTrue("TV pictures come from the TV", html.contains("src=\"/badge-icon?k=code&amp;id=b-2\"") && !html.contains("file:///"))
        assertTrue(html.contains("src=\"https://example.com/atmos.png\"") && html.contains("border:2px solid"))
        assertTrue(html.contains("<em class=off>Off</em>"))
        assertEquals(3, Regex("name=action value=save").findAll(html).count())
        assertEquals(1, Regex("value=up><input type=hidden name=id value=\"b-1\"><button class=\"quiet\" disabled>").findAll(html).count())
        assertTrue(html.contains("name=action value=add") && html.contains("name=action value=copy"))
        assertEquals(Regex("<div").findAll(html).count(), Regex("</div>").findAll(html).count())
        assertEquals(Regex("<details").findAll(html).count(), Regex("</details>").findAll(html).count())
    }

    private class Handler : PhoneSetup.Handler {
        val changes = mutableListOf<Map<String, String>>()
        override fun addAddon(url: String) = "ok"
        override fun saveKey(id: String, value: String) = "ok"
        override fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String) = "ok"
        override fun log(line: String) {}
        override fun exportBackup(passphrase: String) = ByteArray(0)
        override fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean) = "ok"
        override fun importBadges(text: String) = "ok"
        override fun importCollections(text: String) = "ok"
        override fun badges() = PhoneBadges(Badges.CUSTOM, listOf(BadgeFilter(id = "b-1", name = "REMUX", pattern = "REMUX", imageURL = "file:///x.png")))
        override fun changeBadge(fields: Map<String, String>, picture: ByteArray?): String { changes += fields; return "Saved ${fields["name"]}." }
        override fun badgeIcon(id: String) = if (id == "b-1") "GIF89a".toByteArray() to "image/gif" else null
    }

    @Test fun thePhoneSendsBadgeEditsAndShowsTvPictures() {
        val handler = Handler()
        val setup = PhoneSetup({ handler }, address = { "127.0.0.1" }, port = 0, nowMillis = { System.nanoTime() / 1_000_000 })
        try {
            val uri = URI(setup.start()!!)
            fun request(target: String, method: String = "GET", body: String = "") = Socket(uri.host, uri.port).apply { soTimeout = 4000 }.use { s ->
                s.getOutputStream().write(("$method $target HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
                s.getInputStream().bufferedReader().readText()
            }
            val page = request("/?${uri.rawQuery}&tab=addons&s=badges")
            assertTrue(page.contains("<h2>Source badges</h2>") && page.contains("class=blist") && page.contains("/badge-icon?${uri.rawQuery}&amp;id=b-1"))
            val body = listOf("action" to "save", "id" to "b-1", "name" to "Remux", "pattern" to "REMUX", "tag" to "#e50914", "opacity" to "50").joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            val saved = request("/badge?${uri.rawQuery}", "POST", body)
            assertTrue(saved.contains("Saved Remux.") && saved.contains("<h2>Source badges</h2>"))
            assertEquals("50", handler.changes.single()["opacity"]); assertEquals("#e50914", handler.changes.single()["tag"])
            val icon = request("/badge-icon?${uri.rawQuery}&id=b-1")
            assertTrue(icon.contains("Content-Type: image/gif") && icon.endsWith("GIF89a"))
            assertTrue(request("/badge-icon?${uri.rawQuery}&id=nope").startsWith("HTTP/1.1 404"))
        } finally { setup.stop() }
    }

    @Test fun theTvBadgeSettingsAreCardsAndOneOptionsDialog() {
        assertEquals(listOf("4K", "HDR10", "Atmos"), app.ember.tv.ui.BadgeSamples.basic.map { it.name })
        val picked = app.ember.tv.ui.BadgeSamples.pick(listOf("Best", "Good", "DV", "Atmos", "4K").map { BadgeFilter(name = it) })
        assertEquals(listOf("DV", "Atmos", "4K"), picked.map { it.name })
    }
}
