package app.ember.tv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.InterfaceStyles
import app.ember.tv.data.NavbarStyles
import app.ember.tv.phone.PhoneSettingResult
import app.ember.tv.phone.PhoneSettingView
import app.ember.tv.phone.PhoneSetup
import app.ember.tv.phone.PhoneStatus
import app.ember.tv.remote.RemotePage
import app.ember.tv.ui.Accents
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket
import java.net.URI

/**
 * #444: the phone pages as a showcase shows them, with invented settings. Each page's HTML is packed
 * into a grey PNG (4-byte length, then one byte a pixel) beside the UI screenshots, which go to the
 * private screenshots store, so pictures of it can be taken elsewhere. Checks only that each page drew.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneShowcaseTest {
    private class Handler : PhoneSetup.Handler {
        override fun addAddon(url: String) = "ok"
        override fun saveKey(id: String, value: String) = "ok"
        override fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String) = "ok"
        override fun log(line: String) {}
        override fun exportBackup(passphrase: String) = ByteArray(0)
        override fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean) = "ok"
        override fun importBadges(text: String) = "ok"
        override fun importCollections(text: String) = "ok"
        override fun status() = PhoneStatus("1.4.1", listOf(
            "Metadata key (TMDB)" to true, "Catalogs and playback addons" to true,
            "Personal media servers" to true, "Watch history accounts" to false,
        ))
        override fun crashLog() = emptyList<String>()
        override fun settings() = listOf(
            PhoneSettingView("look.interface", "Look", "Home style", null, "choice", InterfaceStyles.options, InterfaceStyles.MODERN),
            PhoneSettingView("look.navbar", "Look", "Navigation bar", null, "choice", NavbarStyles.options, NavbarStyles.EMBER),
            PhoneSettingView("look.theme", "Look", "Theme", "Day and night follow the clock on Auto", "choice",
                listOf("night" to "Night", "day" to "Day", "auto" to "Auto"), "night"),
            PhoneSettingView("look.accent", "Look", "Accent colour", null, "choice",
                Accents.take(5).mapIndexed { i, (name, _) -> i.toString() to name }, "0"),
            PhoneSettingView("look.oled", "Look", "OLED black", "Pure black at night", "toggle", emptyList(), "true"),
            PhoneSettingView("play.autoNext", "Playback", "Play the next episode", "Counts down at the credits", "toggle", emptyList(), "true"),
            PhoneSettingView("play.skipIntro", "Playback", "Skip intros", null, "toggle", emptyList(), "true"),
            PhoneSettingView("play.frameRate", "Playback", "Match the TV to the video", "Frame rate and resolution", "toggle", emptyList(), "true"),
            PhoneSettingView("play.passthrough", "Playback", "Sound passthrough", "Dolby and DTS to your soundbar", "toggle", emptyList(), "false"),
            PhoneSettingView("play.buffer", "Playback", "Buffer (seconds)", null, "number", emptyList(), "30", 10, 120),
        )
        override fun changeSetting(id: String, value: String) = PhoneSettingResult(false, "Showcase only.")
    }

    private fun get(uri: URI, target: String): String =
        Socket(uri.host, uri.port).apply { soTimeout = 4000 }.use { s ->
            s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n")
        }

    @Test fun phonePagesForTheShowcase() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        runCatching {
            ApiKeys.init(context)
            ApiKeys.set("TMDB_API_KEY", "showcase-key-0000")
            ApiKeys.set("TRAKT_CLIENT_ID", "showcase-id-0000")
        }
        val out = File("build/reports/ui").apply { mkdirs() }
        fun save(name: String, html: String) {
            val bytes = html.toByteArray()
            val all = java.nio.ByteBuffer.allocate(bytes.size + 4).putInt(bytes.size).put(bytes).array()
            val w = 1024; val h = (all.size + w - 1) / w
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            val raster = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
            System.arraycopy(all, 0, raster, 0, all.size)
            javax.imageio.ImageIO.write(img, "png", File(out, "phonepage-$name.png"))
        }
        save("remote", RemotePage.app("Living room TV", "#FF7A2F"))
        val setup = PhoneSetup({ Handler() }, address = { "127.0.0.1" }, port = 0, nowMillis = { System.nanoTime() / 1_000_000 })
        try {
            val uri = URI(setup.start()!!)
            val auth = "?${uri.rawQuery}"
            mapOf(
                "setup" to "/$auth&tab=setup",
                "ui" to "/$auth&tab=ui",
                "ui-interface" to "/$auth&tab=ui&s=interface",
                "player-playback" to "/$auth&tab=player&s=playback",
                "addons" to "/$auth&tab=addons",
                "accounts" to "/$auth&tab=accounts",
            ).forEach { (name, target) ->
                val html = get(uri, target)
                assertTrue(name, html.contains("<nav>"))
                save(name, html)
            }
        } finally { setup.stop() }
    }
}
