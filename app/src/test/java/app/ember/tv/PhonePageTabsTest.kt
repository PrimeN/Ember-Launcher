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
            for (tab in listOf("setup", "settings", "content", "backup")) assertTrue(tab, home.contains("href=\"/?${uri.rawQuery}&amp;tab=$tab\""))
            assertTrue(home.contains("Metadata key (TMDB)") && home.contains("Not set up"))
            // An unknown tab falls back to Setup rather than an error.
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=nope").contains("Add addons"))
        } finally { setup.stop() }
    }

    @Test fun settingsTabListsEveryGroupWithSearchAndCatalogsShowOnTheContentTab() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            val settings = request(uri, "/?${uri.rawQuery}&tab=settings")
            assertTrue(settings.contains("id=find"))
            assertTrue(settings.contains("<option value=\"night\" selected>Night</option>"))
            assertTrue(settings.contains("role=switch"))
            assertFalse("catalogs belong on the content tab", settings.contains("Popular: Home"))
            val content = request(uri, "/?${uri.rawQuery}&tab=content")
            assertTrue(content.contains("Popular: Home") && content.contains("<h2>Cinemeta</h2>"))
            assertTrue(content.contains("href=\"/content?${uri.rawQuery}\""))
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
            assertTrue(page.contains("Content-Type: text/html") && page.contains("aria-current=page>Settings<"))
            assertTrue(page.contains("<p class=ok role=status>Saved</p>"))
            val catalog = request(uri, "/setting?${uri.rawQuery}", "POST", form("id" to "catalog.abc.home", "value" to "false"))
            assertTrue("a catalog change returns to its tab", catalog.contains("aria-current=page>Addons &amp; content<"))
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
            val backup = request(uri, "/?${uri.rawQuery}&tab=backup")
            assertTrue(backup.contains("2 entries") && backup.contains("&lt;boom&gt;") && !backup.contains("<boom>"))
            assertTrue(backup.contains("Ember 9.9.9 (build 1)"))
            val file = request(uri, "/crash-log?${uri.rawQuery}", "POST")
            assertTrue(file.contains("Content-Type: text/plain") && file.contains("filename=\"ember-crash-log.txt\""))
            assertTrue(file.contains("Version: 9.9.9 (build 1)") && file.contains("<boom>") && file.contains("Older crash"))
            assertTrue(request(uri, "/crash-log-clear?${uri.rawQuery}", "POST").contains("Tick the box"))
            assertEquals(0, handler!!.cleared)
            assertTrue(request(uri, "/crash-log-clear?${uri.rawQuery}", "POST", form("confirm" to "on")).contains("Crash log cleared."))
            assertEquals(1, handler!!.cleared)
            assertTrue(request(uri, "/?${uri.rawQuery}&tab=backup").contains("Nothing recorded"))
        } finally { setup.stop() }
    }
}
