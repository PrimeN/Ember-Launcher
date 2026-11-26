package app.ember.tv.system

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.ember.tv.data.StartupGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * #442: the recovery page. Opens by itself when Ember fails to start three times in a row, in its
 * own process with plain Android views, so whatever broke Home can't break it too. From here:
 * try Ember again, the TV's settings and apps, turning Google TV's Home back on, turning on
 * debugging (ADB), and installing an app from a link (a fixed Ember, most often).
 * #443: updating to the latest Ember or picking a specific release.
 */
class RecoveryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var link: EditText
    private lateinit var releaseList: LinearLayout
    private val installed by lazy { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty() }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, note: String? = null, action: () -> Unit): View {
        val b = Button(this).apply {
            text = if (note == null) label else "$label\n$note"
            isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFFF6A1A.toInt()) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF2A211D.toInt()) })
            }
            setOnClickListener { action() }
        }
        return b.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
    }

    private fun say(s: String) { status.text = s }

    private fun open(vararg actions: String) {
        for (a in actions) if (runCatching { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        say("This TV doesn't have that settings page.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        column.addView(text("Ember recovery", 30f, bold = true))
        column.addView(text((if (StartupGuard.needsRecovery(this)) "Ember didn't start properly ${StartupGuard.LIMIT} times in a row, so it opened this page instead of a black screen. "
            else "Something stopped Ember's Home from working, or you opened this page yourself. ") +
            "From here you can get the TV working again or install a fixed Ember.", 16f, 0xFFD6CBC3.toInt()))
        // #448: how to come back here by hand.
        column.addView(text("To open this page any time: press Up Up Down Down Left Right Left Right on the remote while Ember is in front, " +
            "or choose Ember Recovery in the TV's app list (Settings > Apps).", 13f, 0xFF9E928A.toInt()))
        StartupGuard.lastCrash(this)?.let { column.addView(text("Last error: $it", 13f, 0xFF9E928A.toInt())) }
        status = text("", 15f, 0xFFFFB37A.toInt()); column.addView(status)

        val again = button("Try Ember again", "Starts Home normally. If it fails three more times, this page comes back.") {
            StartupGuard.healthy(this)
            runCatching { startActivity(Intent().setClassName(packageName, "app.ember.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            finish()
        }
        column.addView(again)

        column.addView(text("Update Ember", 18f, bold = true).apply { setPadding(0, dp(20), 0, 0) })
        column.addView(text("This TV has Ember ${installed.ifEmpty { "(unknown version)" }}. Updating keeps your settings.", 14f, 0xFFD6CBC3.toInt()))
        column.addView(button("Update to the latest Ember", "Downloads the newest release and installs it.") { installFrom(DEFAULT_LINK) })
        column.addView(button("Pick a release", "Lists Ember's releases so you can install a specific one.") { listReleases() })
        releaseList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(releaseList)

        column.addView(text("Install an app from a link", 18f, bold = true).apply { setPadding(0, dp(20), 0, 0) })
        column.addView(text("An APK link, a GitHub release page, or a zip with an APK inside. Installing Ember keeps your settings.", 14f, 0xFFD6CBC3.toInt()))
        link = EditText(this).apply {
            setText(DEFAULT_LINK); setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; setTextColor(Color.WHITE); setHintTextColor(0xFF9E928A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnEditorActionListener { _, _, _ -> install(); true }
        }
        column.addView(link)
        column.addView(button("Download and install") { install() })

        column.addView(text("Your TV", 18f, bold = true).apply { setPadding(0, dp(20), 0, 0) })
        column.addView(button("Turn Google TV's Home back on", "Uses debugging if Ember was set up with System access; otherwise opens the apps list.") { restoreHome() })
        column.addView(button("Turn on debugging (ADB)", "So a computer or phone can connect to fix things.") { turnOnAdb() })
        column.addView(button("TV settings") { open(Settings.ACTION_SETTINGS) })
        column.addView(button("All apps", "Enable Google TV Home, or clear Ember's data.") {
            open(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS)
        })
        column.addView(button("Ember's app info", "Force stop, clear cache or data, or uninstall.") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { say("This TV doesn't have that settings page.") }
        })
        column.addView(button("Developer options") { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF120D0B.toInt()); isFillViewport = true
            addView(column)
        })
        again.requestFocus()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ---- debugging and Home ----

    private fun turnOnAdb() {
        if (!SystemAccess.canWriteSettings(this)) {
            say("Ember can't switch debugging on by itself on this TV. In Developer options turn on USB debugging or Wireless debugging. " +
                "No Developer options? In TV settings › System › About, press Build 7 times.")
            open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS)
            return
        }
        val r = contentResolver
        val done = runCatching {
            Settings.Global.putInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(r, Settings.Global.ADB_ENABLED, 1)
            if (android.os.Build.VERSION.SDK_INT >= 30) Settings.Global.putInt(r, "adb_wifi_enabled", 1)
        }.isSuccess
        val ip = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
        }.getOrNull()
        say(if (done) "Debugging is on." + (ip?.let { " From a computer: adb connect $it:5555 (or pair in Wireless debugging)." } ?: "")
            else "Couldn't switch debugging on. Open Developer options and turn it on there.")
    }

    private fun restoreHome() {
        say("Turning Google TV's Home back on…")
        scope.launch {
            val out = runCatching {
                SystemAccess.run(this@RecoveryActivity, HOMES.joinToString("; ") { "pm enable $it" })
            }.getOrNull()
            if (out != null && out.contains("enabled", ignoreCase = true)) say("Google TV's Home is back on. Press Home on the remote, then choose it if the TV asks.")
            else {
                say("Ember couldn't do it over debugging. In All apps, show system apps, open Google TV Home (or Android TV Home) and choose Enable.")
                open(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS)
            }
        }
    }

    // ---- install from a link ----

    private fun install() {
        val url = link.text.toString().trim()
        if (!url.startsWith("http")) { say("Enter a link starting with http or https."); return }
        installFrom(url)
    }

    private fun listReleases() {
        say("Looking up Ember's releases…")
        scope.launch {
            val found = runCatching { withContext(Dispatchers.IO) { RecoveryLinks.releases(get(RELEASES_API).readText()) } }
            found.onFailure { say("Couldn't list the releases: ${it.message ?: it.javaClass.simpleName}") }
            val list = found.getOrNull() ?: return@launch
            releaseList.removeAllViews()
            if (list.isEmpty()) { say("No releases with an app were found."); return@launch }
            say("Choose a release to install.")
            list.forEachIndexed { i, r ->
                val age = RecoveryLinks.compare(r.version, installed)
                val note = buildList {
                    if (i == 0) add("Latest")
                    if (age == 0) add("Installed now")
                    if (age < 0) add("Older: Android only installs it after Ember is uninstalled, which clears its settings")
                    r.date.takeIf { it.isNotEmpty() }?.let { add(it) }
                }.joinToString(" · ")
                val b = button(listOf(r.tag, r.title).filter { it.isNotEmpty() }.distinct().joinToString(" · "), note.ifEmpty { null }) { pick(r, age) }
                releaseList.addView(b)
                if (i == 0) b.requestFocus()
            }
        }
    }

    private fun pick(r: RecoveryLinks.ReleaseItem, age: Int) {
        if (age < 0) {
            link.setText(r.apk)
            say("${r.tag} is older than the Ember on this TV, and Android won't install an older version over a newer one. " +
                "To go back: note this link (it's now in the box below), uninstall Ember from its app info, then install the link with a downloader app.")
            return
        }
        installFrom(r.apk)
    }

    private fun installFrom(url: String) {
        say("Finding the download…")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val direct = RecoveryLinks.resolve(url) { api -> get(api).readText() }
                    withContext(Dispatchers.Main) { say("Downloading…") }
                    val dir = File(cacheDir, "updates").apply { mkdirs() }
                    val raw = File(dir, "recovery-download")
                    get(direct).use { input -> raw.outputStream().use { input.copyTo(it) } }
                    val apk = File(dir, "recovery.apk").also { it.delete() }
                    if (RecoveryLinks.isApk(raw)) raw.renameTo(apk) else extractApk(raw, apk)
                    raw.delete()
                    check(apk.length() > 0) { "No app was found at that link." }
                    apk
                }
            }
            result.onSuccess { apk ->
                val pkg = packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
                say(if (pkg == packageName) "Installing Ember… The TV may ask you to confirm." else "Opening the installer…")
                if (pkg == packageName) withContext(Dispatchers.IO) { app.ember.tv.data.Updater.install(applicationContext, apk) }
                else runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@RecoveryActivity, "$packageName.files", apk)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { say("Couldn't open the installer: ${it.message}") }
                StartupGuard.healthy(this@RecoveryActivity)
            }.onFailure { say("Couldn't get the app: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun get(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Ember-Recovery"); c.setRequestProperty("Accept", "application/vnd.github+json, */*")
        val code = c.responseCode
        check(code in 200..299) { "The server answered $code." }
        return c.inputStream
    }

    private fun java.io.InputStream.readText() = use { it.readBytes().toString(Charsets.UTF_8) }

    private fun extractApk(zip: File, out: File) {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".apk", ignoreCase = true)) { out.outputStream().use { z.copyTo(it) }; return }
            }
        }
        error("The zip has no APK in it.")
    }

    companion object {
        const val DEFAULT_LINK = "https://github.com/PrimeN/Ember-Launcher/releases/latest"
        const val RELEASES_API = "https://api.github.com/repos/PrimeN/Ember-Launcher/releases?per_page=30"
        /** Google TV's and Android TV's own Home apps. */
        val HOMES = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")
    }
}

/** #442: turns a link into a direct download: GitHub release pages are looked up for their APK. */
object RecoveryLinks {
    private val release = Regex("""https?://github\.com/([^/]+)/([^/]+)/releases(?:/(latest|tag/([^/?#]+)))?/?(?:[?#].*)?""")

    /** The GitHub API address for a release page link, or null for any other link. */
    fun releaseApi(url: String): String? {
        val m = release.matchEntire(url.trim()) ?: return null
        val (owner, repo) = m.destructured
        val tag = m.groupValues[4]
        return "https://api.github.com/repos/$owner/$repo/releases/" + if (tag.isNotEmpty()) "tags/$tag" else "latest"
    }

    /** The first APK's download link in a GitHub release (JSON). */
    fun apkFrom(json: String): String? {
        val assets = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull()
            ?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("assets") as? kotlinx.serialization.json.JsonArray } ?: return null
        return assets.firstNotNullOfOrNull { a ->
            val o = a as? kotlinx.serialization.json.JsonObject ?: return@firstNotNullOfOrNull null
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            (o["browser_download_url"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { name.endsWith(".apk", true) }
        }
    }

    data class ReleaseItem(val tag: String, val title: String, val version: String, val date: String, val apk: String)

    /** #443: Ember's releases (the GitHub API's list, newest first) that carry an APK; drafts are skipped. */
    fun releases(json: String): List<ReleaseItem> {
        val arr = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (str("draft") == "true" || (o["draft"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") return@mapNotNull null
            val apk = apkFrom(o.toString()) ?: return@mapNotNull null
            val tag = str("tag_name")
            ReleaseItem(tag, str("name").removePrefix(tag).trim(' ', '-', '·', ':', '—'), tag.trimStart('v', 'V'), str("published_at").take(10), apk)
        }
    }

    /** Compares dotted versions like 1.4.1 and 1.10.0: negative when [a] is older than [b]. Unknown versions compare equal. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String): List<Int>? = v.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() }.takeIf { null !in it }?.filterNotNull()
        val x = parts(a) ?: return 0; val y = parts(b) ?: return 0
        if (x.isEmpty() || y.isEmpty()) return 0
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun resolve(url: String, fetch: (String) -> String): String {
        val api = releaseApi(url) ?: return url
        return apkFrom(fetch(api)) ?: error("That release has no APK.")
    }

    /** An APK is a zip with AndroidManifest.xml at its root; other zips are searched for an APK. */
    fun isApk(file: File): Boolean = runCatching {
        ZipInputStream(file.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { if (e.name == "AndroidManifest.xml") return true; e = z.nextEntry }
            false
        }
    }.getOrDefault(false)
}
