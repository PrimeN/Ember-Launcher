package app.ember.tv

import app.ember.tv.phone.PhoneSettingResult
import app.ember.tv.phone.PhoneSettingView
import app.ember.tv.phone.PhoneSetup
import app.ember.tv.phone.PhoneSetupAccess
import app.ember.tv.phone.PhoneStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder

/** The tabbed phone page (#49) and its crash log (#48). */
class PhonePageTabsTest {
    private class Handler(private val access: PhoneSetupAccess) : PhoneSetup.Handler {
        val changes = mutableListOf<Pair<String, String>>()
        var cleared = 0
        var entries = listOf("Crash at 12:00\njava.lang.IllegalStateException: <boom>", "Older crash")
        private var theme = "night"
        private var oled = false
        override fun addAddon(url: String) = "ok"
        override fun saveKey(id: String, value: String) = "ok"
        override fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String) = "ok"
        override fun log(line: String) {}
        override fun exportBackup(passphrase: String) = ByteArray(0)
        override fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean) = "ok"
        override fun importBadges(text: String) = "ok"
        override fun importCollections(text: String) = "ok"
        override fun status() = PhoneStatus("9.9.9 (build 1)", listOf("Metadata key (TMDB)" to true, "Watch history accounts" to false))
        override fun settings() = listOf(
            PhoneSettingView("look.theme", "Look", "Theme", null, "choice", listOf("night" to "Night", "day" to "Day"), theme),
            PhoneSettingView("look.oled", "Look", "OLED black", "At night", "toggle", emptyList(), oled.toString()),
            PhoneSettingView("catalog.abc.home", "Catalogs", "Popular: Home", "Cinemeta", "toggle", emptyList(), "true"),
        )
        override fun changeSetting(id: String, value: String): PhoneSettingResult {
            access.requireActive()
            changes += id to value
            when (id) {
                "look.theme" -> if (value in setOf("night", "day")) theme = value else return PhoneSettingResult(false, "Choose one of the listed options.")
                "look.oled" -> oled = value == "true"
                else -> return PhoneSettingResult(false, "That setting is not available.")
            }
            return PhoneSettingResult(true, "Saved", settings().first { it.id == id })
        }
        override fun crashLog() = entries
        override fun clearCrashLog(): String { cleared++; entries = emptyList(); return "Crash log cleared." }
    }

    private var handler: Handler? = null
    private fun server() = PhoneSetup({ Handler(it).also { h -> handler = h } }, address = { "127.0.0.1" }, port = 0,
        nowMillis = { System.nanoTime() / 1_000_000 })

    private fun request(uri: URI, target: String, method: String = "GET", body: String = ""): String =
        Socket(uri.host, uri.port).apply { soTimeout = 4000 }.use { socket ->
            socket.getOutputStream().write(("$method $target HTTP/1.1\r\nHost: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }

    private fun form(vararg fields: Pair<String, String>) = fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    @Test fun tabsAreLinksThatKeepTheSessionCode() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            val home = request(uri, "/?${uri.rawQuery}")
            // #165: seven sections.
            for (tab in listOf("setup", "settings", "ui", "player", "addons", "accounts", "backup")) assertTrue(tab, home.contains("href=\"/?${uri.rawQuery}&amp;tab=$tab\""))
            assertEquals(7, Regex("<nav>(.*?)</nav>").find(home)!!.groupValues[1].split("<a ").size - 1)
            assertTrue(home.contains("Metadata key (TMDB)") && home.contains("Not set up"))
            // An unknown tab falls back to Setup rather than an error.
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=nope").contains("Add addons"))
        } finally { setup.stop() }
    }

    @Test fun settingsTabListsEveryGroupWithSearchAndCatalogsShowOnTheContentTab() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            // #165: a section opens on a menu of sub-pages; the settings themselves are one tap in.
            val ui = request(uri, "/?${uri.rawQuery}&tab=ui")
            assertTrue(ui.contains("class=menu") && ui.contains("href=\"/?${uri.rawQuery}&amp;tab=ui&amp;s=interface\""))
            assertTrue(ui.contains("Interface and colours") && ui.contains("2 settings"))
            assertTrue(ui.contains("href=\"/content?${uri.rawQuery}&amp;part=widgets\"") && ui.contains("href=\"/content?${uri.rawQuery}&amp;part=collections\""))
            assertFalse("the menu holds no settings", ui.contains("role=switch"))
            val settings = request(uri, "/?${uri.rawQuery}&tab=ui&s=interface")
            assertTrue(settings.contains("name=value value=\"night\" id=\"v-look.theme\" checked>")); assertTrue(settings.contains("class=pics"))
            assertTrue(settings.contains("role=switch"))
            assertTrue(settings.contains("href=\"/?${uri.rawQuery}&amp;tab=ui\">&lsaquo; UI</a>"))
            assertFalse("catalogs belong under Addons", settings.contains("Popular: Home"))
            val catalogs = request(uri, "/?${uri.rawQuery}&tab=addons&s=catalogs")
            assertTrue(catalogs.contains("Popular: Home") && catalogs.contains("<span>Cinemeta</span>") && catalogs.contains("id=find"))
            assertTrue("one addon opens by itself", catalogs.contains("<details class=\"card g\" open>"))
            // The old Addons & content tab's address still opens Addons.
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=content").contains("aria-current=page>Addons<"))
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=accounts").contains("name=action value=keys"))
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=player").contains("&amp;s=playback"))
        } finally { setup.stop() }
    }

    @Test fun instantSavesAnswerWithJsonAndTheFormFallbackAnswersWithThePage() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            val json = request(uri, "/setting?${uri.rawQuery}", "POST", form("id" to "look.theme", "value" to "day", "js" to "1"))
            assertTrue(json, json.contains("Content-Type: application/json"))
            assertTrue(json, json.contains("\"ok\":true") && json.contains("\"value\":\"day\""))
            // A ticked switch posts the hidden "false" and then "true": the last one wins.
            request(uri, "/setting?${uri.rawQuery}", "POST", form("id" to "look.oled", "value" to "false", "value" to "true", "js" to "1"))
            assertEquals("look.oled" to "true", handler!!.changes.last())
            val rejected = request(uri, "/setting?${uri.rawQuery}", "POST", form("id" to "look.theme", "value" to "<script>", "js" to "1"))
            assertTrue(rejected.contains("\"ok\":false") && !rejected.contains("\"value\""))
            val page = request(uri, "/setting?${uri.rawQuery}", "POST", form("id" to "look.theme", "value" to "night"))
            assertTrue(page.contains("Content-Type: text/html") && page.contains("aria-current=page>UI<") && page.contains("&lsaquo; UI</a>"))
            assertTrue(page.contains("<p class=ok role=status>Saved</p>"))
            val catalog = request(uri, "/setting?${uri.rawQuery}", "POST", form("id" to "catalog.abc.home", "value" to "false"))
            assertTrue("a catalog change returns to its page", catalog.contains("aria-current=page>Addons<") && catalog.contains("<h2>Catalogs</h2>"))
        } finally { setup.stop() }
    }

    @Test fun settingChangesNeedTheSessionCode() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            assertTrue(request(uri, "/setting?k=wrong", "POST", form("id" to "look.theme", "value" to "day", "js" to "1")).startsWith("HTTP/1.1 403"))
            assertTrue(handler!!.changes.isEmpty())
        } finally { setup.stop() }
    }

    @Test fun crashLogCanBeReadDownloadedAndClearedFromThePhone() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=backup").contains("2 entries, newest first."))
            val backup = request(uri, "/?${uri.rawQuery}&tab=backup&s=crash")
            assertTrue(backup.contains("2 entries") && backup.contains("&lt;boom&gt;") && !backup.contains("<boom>"))
            assertTrue(backup.contains("Ember 9.9.9 (build 1)"))
            val file = request(uri, "/crash-log?${uri.rawQuery}", "POST")
            assertTrue(file.contains("Content-Type: text/plain") && file.contains("filename=\"ember-crash-log.txt\""))
            assertTrue(file.contains("Version: 9.9.9 (build 1)") && file.contains("<boom>") && file.contains("Older crash"))
            assertTrue(request(uri, "/crash-log-clear?${uri.rawQuery}", "POST").contains("Tick the box"))
            assertEquals(0, handler!!.cleared)
            assertTrue(request(uri, "/crash-log-clear?${uri.rawQuery}", "POST", form("confirm" to "on")).contains("Crash log cleared."))
            assertEquals(1, handler!!.cleared)
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=backup&s=crash").contains("Nothing recorded"))
        } finally { setup.stop() }
    }

    @Test fun settingsLandOnTheirSectionAndLongListsFold() {
        // #165
        fun view(id: String, group: String, label: String = id) = PhoneSettingView(id, group, label, null, "toggle", emptyList(), "true")
        assertEquals(PhoneSetup.Tab.UI to "interface", app.ember.tv.phone.PhonePages.place(view("look.theme", "Look")))
        assertEquals(PhoneSetup.Tab.UI to "size", app.ember.tv.phone.PhonePages.place(view("look.text", "Look")))
        assertEquals(PhoneSetup.Tab.UI to "effects", app.ember.tv.phone.PhonePages.place(view("look.custom.blur", "Look")))
        assertEquals(PhoneSetup.Tab.UI to "tiles", app.ember.tv.phone.PhonePages.place(view("look.light.clock", "Look")))
        assertEquals(PhoneSetup.Tab.SETTINGS to "system", app.ember.tv.phone.PhonePages.place(view("look.remoteSounds", "Look")))
        assertEquals(PhoneSetup.Tab.PLAYER to "playback", app.ember.tv.phone.PhonePages.place(view("play.x", "Playback")))
        assertEquals(PhoneSetup.Tab.PLAYER to "subtitles", app.ember.tv.phone.PhonePages.place(view("subs.x", "Subtitles")))
        assertEquals(PhoneSetup.Tab.SETTINGS to "home", app.ember.tv.phone.PhonePages.place(view("home.x", "Home")))
        assertEquals(PhoneSetup.Tab.SETTINGS to "sources", app.ember.tv.phone.PhonePages.place(view("src.x", "Sources")))
        assertEquals(PhoneSetup.Tab.ADDONS to "catalogs", app.ember.tv.phone.PhonePages.place(view("catalog.a.home", "Catalogs")))
        assertEquals(PhoneSetup.Tab.ADDONS, PhoneSetup.Tab.of("content")); assertEquals(PhoneSetup.Tab.SETUP, PhoneSetup.Tab.of("nope")); assertEquals(PhoneSetup.Tab.SETUP, PhoneSetup.Tab.of(null))

        val rows = listOf(view("a", "Look", "Theme"), view("b", "Look", "Light Home: clock"), view("c", "Look", "Light Home: weather"), view("d", "Look", "Light Home: dock"),
            view("e", "Look", "Focused background: media rows"), view("f", "Look", "Glass"))
        val (plain, groups) = app.ember.tv.phone.PhonePages.fold(rows)
        assertEquals(listOf("a", "e", "f"), plain.map { it.id })
        assertEquals(listOf("Light Home" to listOf("b", "c", "d")), groups.map { it.first to it.second.map { row -> row.id } })
        assertEquals(rows, app.ember.tv.phone.PhonePages.fold(rows, least = 9).first)
    }

    @Test fun aFailedSearchSaysWhatToDo() {
        // #165: Trakt search used to show "Nothing found" for every failure.
        val reason = app.ember.tv.phone.PhoneLookups::reason
        assertTrue(reason("trakt", IllegalStateException("Trakt lists need a client id.")).contains("Trakt client ID"))
        assertTrue(reason("trakt", IllegalStateException("The collection provider returned HTTP 403.")).contains("refused"))
        assertTrue(reason("trakt", java.io.IOException("timeout")).contains("could not be reached"))
        // #166: the status is named, so a refused client ID is not mistaken for a network problem.
        assertTrue(reason("trakt", IllegalStateException("The collection provider returned HTTP 403.")).contains("HTTP 403"))
        assertTrue(reason("trakt", IllegalStateException("The collection provider returned HTTP 502.")).contains("HTTP 502"))
        val tv = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertTrue(tv.contains("PhoneLookups.reason(\"trakt\"")); assertFalse(tv.contains("Check the connection and try again"))
        // #246: one Trakt request builder now carries it for every Trakt call.
        assertEquals(1, Regex("header\\(\"User-Agent\", TRAKT_AGENT\\)").findAll(java.io.File("src/main/java/app/ember/tv/data/CollectionRequests.kt").readText()).count())
        assertTrue(reason("person", IllegalStateException("TMDB sources need an API key.")).contains("TMDB key"))
        assertTrue(reason("person", IllegalStateException("Switch to an adult profile.")).contains("adult profile"))
        for (kind in listOf("trakt", "person", "company", "provider")) assertFalse(reason(kind, RuntimeException("secret-token-123")).contains("secret-token-123"))
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            val answer = request(uri, "/lookup?${uri.rawQuery}&kind=trakt&q=marvel")
            assertTrue(answer, answer.contains("Content-Type: application/json") && answer.trimEnd().endsWith("[]"))
        } finally { setup.stop() }
        val script = java.io.File("src/main/java/app/ember/tv/phone/ContentPhone.kt").readText()
        assertTrue(script.contains("if(j&&j.error)done([],j.error)") && script.contains("if(err){note(res,err);return}"))
    }
}
