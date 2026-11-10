package app.ember.tv.phone

import app.ember.tv.data.ApiKeys
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Set up from your phone: a small web page served by the TV on your home network while
 * Settings > Phone setup is explicitly enabled (for at most 15 minutes). The phone opens it by QR code and sends addon links, API keys
 * and IPTV logins, so nothing has to be typed with the remote. Every request needs the one-time
 * code in the link, and the server stops when the page closes.
 */
class PhoneSetup(
    private val handlerFor: (PhoneSetupAccess) -> Handler,
    /** The accent colour (Settings > Appearance) as "#RRGGBB", for the phone page. */
    private val accent: String = "#FF7A2F",
    private val address: () -> String? = ::localAddress,
    private val port: Int = 8899,
    private val durationMillis: Long = PhoneSetupAccess.DURATION_MILLIS,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    /** What the TV does with what the phone sends. Called on the server thread; each returns a message for the phone. */
    interface Handler {
        fun addAddon(url: String): String
        fun saveKey(id: String, value: String): String
        fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String): String
        /** Shown on the TV, e.g. "Added addon …". */
        fun log(line: String)
        /** A backup of everything on the TV (encrypted when [passphrase] is not blank). */
        fun exportBackup(passphrase: String): ByteArray
        /** Checks a backup; with [apply], restores it on the TV (which then restarts Ember). Returns a message for the phone. */
        fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean): String
        /** A source badge pack (JSON) for Settings > Stremio > Badges. Returns a message. */
        fun importBadges(text: String): String
        /** Collections JSON for Settings > Stremio > Collections. Returns a message. */
        fun importCollections(text: String): String
        fun exportCollection(id: String, passphrase: String): ByteArray? = null
        fun importPresentation(name:String,bytes:ByteArray):String = "Presentation uploads are unavailable."
        fun content(): PhoneContent? = null
        fun proposeContent(fields: Map<String, String>): String = "Content editing is unavailable."
        /** Setup checklist and version (#49). */
        fun status(): PhoneStatus? = null
        /** Every setting the phone may change, read on the TV; throws with a message when none may be. */
        fun settings(): List<PhoneSettingView>? = null
        /** Applies one setting at once (#49); the value is checked on the TV. */
        fun changeSetting(id: String, value: String): PhoneSettingResult = PhoneSettingResult(false, "Settings are unavailable.")
        /** Crash log entries, newest first (#48). */
        fun crashLog(): List<String>? = null
        fun clearCrashLog(): String = "The crash log is unavailable."
    }

    private class Session(val socket: ServerSocket, val access: PhoneSetupAccess, val handler: Handler) {
        val clients = mutableSetOf<Socket>()
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
    }

    @Volatile private var session: Session? = null
    private val slots = java.util.concurrent.Semaphore(4)
    @Volatile var url: String? = null
        private set
    val remainingMillis: Long get() = session?.access?.remainingMillis ?: 0

    /** Explicit opt-in only; repeated starts do not extend an existing session. */
    @Synchronized fun start(): String? {
        session?.takeIf { it.access.active }?.let { return url }
        stop()
        val ip = address() ?: return null
        val socket = runCatching { ServerSocket(port) }.getOrElse { ServerSocket(0) }
        val access = PhoneSetupAccess(durationMillis, nowMillis)
        val active = try { Session(socket, access, handlerFor(access)) }
            catch (e: Exception) { socket.close(); access.revoke(); throw e }
        session = active
        url = "http://$ip:${socket.localPort}/?k=${access.code}"
        active.timeout = timer.schedule({ stopSession(active) }, durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        thread(name = "ember-phone", isDaemon = true) {
            try {
                while (!socket.isClosed && access.active) {
                    val client = try { socket.accept() } catch (_: SocketException) { break }
                    var busy = false
                    val admitted = synchronized(this) {
                        if (session !== active || !access.active) false
                        else if (!slots.tryAcquire()) { busy = true; false }
                        else { active.clients.add(client); true }
                    }
                    if (!admitted) {
                        // #154: say so instead of dropping the connection, which browsers report as a network error.
                        if (busy) runCatching { respond(client, 503, page("Busy", "<p>The TV is answering other requests. Reload this page in a moment.</p>")) }
                        runCatching { client.close() }; continue
                    }
                    thread(name = "ember-phone-request", isDaemon = true) {
                        try {
                            try { serve(client, active) }
                            catch (_: java.io.IOException) { }
                            catch (e: Exception) {
                                // #154: a page that failed to build still gets an answer. A session that was
                                // switched off stays silent, as before.
                                if (access.active) runCatching { respond(client, 500, page("Something went wrong",
                                    "<p>The TV could not finish that request. Reload this page; if it keeps happening, turn Phone setup off and on again.</p>")) }
                            }
                        }
                        finally {
                            runCatching { client.close() }
                            synchronized(this) { active.clients.remove(client) }
                            slots.release()
                        }
                    }
                }
            } finally { stopSession(active) }
        }
        return url
    }

    @Synchronized fun stop() { session?.let { stopSession(it) } }

    @Synchronized private fun stopSession(active: Session) {
        active.timeout?.cancel(false)
        // Close the connections before revoking the link. Revoking first let a request that was
        // being read answer "Link expired" in the gap; switching off must simply cut it off.
        runCatching { active.socket.close() }
        active.clients.forEach { runCatching { it.close() } }
        active.clients.clear()
        active.access.revoke()
        if (session === active) { session = null; url = null }
    }

    // ---------------------------------------------------------------- HTTP

    private fun serve(c: Socket, session: Session) {
        val access = session.access
        val handler = session.handler
        access.requireActive()
        // Headers must arrive promptly so idle connections cannot hold all four slots; the body (an
        // uploaded backup) gets the longer limit below.
        c.soTimeout = HEADER_TIMEOUT_MILLIS
        val input = BufferedInputStream(c.getInputStream())
        // #152: a browser trying HTTPS first sends a TLS handshake, which never forms an HTTP request
        // line. Waiting out the header timeout made Safari report a failed secure connection; refusing
        // at once lets the browser fall back to http:// straight away and frees the slot.
        input.mark(1)
        val first = input.read()
        if (first == -1) return
        if (isTlsHandshake(first)) { runCatching { c.setSoLinger(true, 0) }; return }
        input.reset()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        var headerCount = 0
        var headersComplete = false
        while (headerCount++ < 64) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) { headersComplete = true; break }
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (!headersComplete) return respond(c, 400, page("Invalid request", ""))
        c.soTimeout = BODY_TIMEOUT_MILLIS
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val target = parts.getOrNull(1) ?: "/"
        val query = parse(target.substringAfter('?', ""))
        val path = target.substringBefore('?')
        // Authenticate before accepting uploads, then recheck the lease before dispatch.
        if (!access.accepts(query["k"])) {
            if (access.throttled) return respond(c, 429, page("Please wait",
                "<p>Too many incorrect links. Wait 30 seconds, then use the current link shown on the TV.</p>"))
            return respond(c, 403, page("Link expired",
                "<p>Turn on <b>Settings &gt; Phone setup</b> on the TV and scan the new code.</p>"))
        }
        val maxBody = if (path == "/restore" || path == "/badges" || path == "/collections" || path=="/presentation") 8_000_000 else 64_000
        val length = headers["content-length"]?.toLongOrNull() ?: if ("content-length" in headers) -1 else 0
        if (length < 0 || "transfer-encoding" in headers) return respond(c, 400, page("Invalid request", ""))
        if (length > maxBody) return respond(c, 413, page("File too large", ""))
        val raw = if (length > 0) readBytes(input, length.toInt()) else ByteArray(0)
        if (raw.size.toLong() != length) return respond(c, 400, page("Incomplete upload", ""))
        access.requireActive()
        val contentType = headers["content-type"].orEmpty()
        val multipart = if (contentType.startsWith("multipart/form-data")) parseMultipart(raw, contentType) else emptyMap()
        val form = if (multipart.isEmpty()) parse(String(raw, Charsets.UTF_8)) else multipart.filterKeys {it!="file"}.mapValues { String(it.value, Charsets.UTF_8) }
        access.requireActive()
        val tab = Tab.of(query["tab"])
        fun shell(shown: Tab, message: String? = null) = respond(c, 200, page(shown.title, tabs(access, shown, handler, message)))
        when {
            method == "POST" && path == "/save" -> shell(Tab.SETUP, handle(form, handler, access))
            method == "POST" && path == "/setting" -> {
                val result = runCatching { handler.changeSetting(form["id"].orEmpty(), form["value"].orEmpty()) }
                    .getOrElse { PhoneSettingResult(false, it.message ?: "Could not change that setting.") }
                access.requireActive()
                if (form["js"] == "1") respondJson(c, kotlinx.serialization.json.buildJsonObject {
                    put("ok", kotlinx.serialization.json.JsonPrimitive(result.ok))
                    put("message", kotlinx.serialization.json.JsonPrimitive(result.message))
                    result.setting?.let { put("value", kotlinx.serialization.json.JsonPrimitive(it.value)) }
                }.toString())
                else shell(if (form["id"].orEmpty().startsWith(PhoneSettings.CATALOGS_PREFIX)) Tab.CONTENT else Tab.SETTINGS, result.message)
            }
            method == "POST" && path == "/backup" -> {
                // The phone page is plain HTTP on the home network: an unencrypted backup would carry
                // every sign-in and key across it readable. Only encrypted backups leave the TV this way.
                val passphrase = form["passphrase"].orEmpty()
                if (passphrase.length < MIN_BACKUP_PASSPHRASE) shell(Tab.BACKUP,
                    "Enter a passphrase of at least $MIN_BACKUP_PASSPHRASE characters. Backups saved to a phone are always encrypted.")
                else run {
                val bytes = runCatching { handler.exportBackup(passphrase) }.getOrNull()
                if (bytes == null) shell(Tab.BACKUP, "Could not make the backup.")
                else { access.requireActive(); respondFile(c, app.ember.tv.data.Backup.fileName(), bytes) }
                }
            }
            method == "POST" && path == "/restore" -> {
                val file = multipart["file"]
                val msg = if (file == null || file.isEmpty()) "Choose a backup file first."
                else handler.importBackup(file, form["passphrase"].orEmpty(), form["apply"] == "on")
                shell(Tab.BACKUP, msg)
            }
            method == "POST" && path == "/crash-log" -> {
                val entries = runCatching { handler.crashLog() }.getOrNull()
                access.requireActive()
                if (entries == null) shell(Tab.BACKUP, "The crash log is unavailable.")
                else respondFile(c, "ember-crash-log.txt", crashLogText(entries, runCatching { handler.status()?.version }.getOrNull()).toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
            }
            method == "POST" && path == "/crash-log-clear" -> shell(Tab.BACKUP,
                if (form["confirm"] != "on") "Tick the box to confirm, then clear the log."
                else runCatching { handler.clearCrashLog() }.getOrElse { it.message ?: "Could not clear the crash log." })
            method == "POST" && path == "/badges" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                val msg = if (text.isBlank()) "Choose a badge pack file, or paste its JSON." else handler.importBadges(text)
                shell(Tab.CONTENT, msg)
            }
            method == "POST" && path == "/collections" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                val msg = if (text.isBlank()) "Choose a collections file, or paste its JSON." else runCatching { app.ember.tv.data.CollectionTransfer.open(text, form["passphrase"].orEmpty()) }
                    .fold({ handler.importCollections(it) }, { "Could not unlock the collection pack. Check its passphrase and file." })
                shell(Tab.CONTENT, msg)
            }
            method == "POST" && path == "/collection-export" -> {
                val bytes = runCatching { handler.exportCollection(form["collectionId"].orEmpty(), form["passphrase"].orEmpty()) }.getOrNull()
                access.requireActive()
                if (bytes == null) respond(c, 200, page("Collection export", "<p>Could not export. Use a passphrase of at least 8 characters and check the collection on TV.</p>"))
                else respondFile(c, "ember-collection.json", bytes)
            }
            method=="POST" && path=="/presentation" -> {
                val bytes=multipart["file"]
                val message=if(bytes==null)"Choose a file." else handler.importPresentation(form["name"].orEmpty(),bytes)
                shell(Tab.CONTENT, message)
            }
            method == "POST" && path == "/content" -> {
                val message = handler.proposeContent(form)
                val content = handler.content()?.let { ContentPhone.render(it, access.code) }.orEmpty()
                respond(c, 200, page("Organize content", nav(access, Tab.CONTENT) + "<p class=ok>${esc(message)}</p>" + content))
            }
            path == "/content" -> respond(c, 200, page("Organize content", nav(access, Tab.CONTENT) + handler.content()?.let { ContentPhone.render(it, access.code) }.orEmpty()))
            path == "/" -> shell(tab)
            else -> respond(c, 404, page("Not found", ""))
        }
    }

    private fun handle(f: Map<String, String>, handler: Handler, access: PhoneSetupAccess): String = when (f["action"]) {
        "addon" -> f["url"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { return "Paste an addon link first." }
            .joinToString(" ") { access.requireActive(); handler.addAddon(it) }
        "keys" -> {
            val saved = ApiKeys.ALL_IDS.mapNotNull { id ->
                f[id]?.trim()?.takeIf { it.isNotEmpty() }?.let { v -> access.requireActive(); handler.saveKey(id, v); id }
            }
            if (saved.isEmpty()) "Nothing to save: fill in at least one field." else "Saved ${saved.size} key(s)."
        }
        "iptv" -> handler.addIptv(
            f["kind"] ?: "m3u", f["name"].orEmpty(), f["url"].orEmpty(),
            f["username"].orEmpty(), f["password"].orEmpty(), f["epg"].orEmpty(),
        )
        else -> "Unknown request."
    }

    private fun respond(c: Socket, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Not Found" }
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun respondJson(c: Socket, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun respondFile(c: Socket, name: String, bytes: ByteArray, type: String = "application/json") {
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Content-Disposition: attachment; filename=\"$name\"\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    /** The fields of a multipart/form-data upload (the backup file and its passphrase), by name. */
    private fun parseMultipart(body: ByteArray, contentType: String): Map<String, ByteArray> {
        val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
        if (boundary.isEmpty()) return emptyMap()
        val delimiter = ("--$boundary").toByteArray()
        val out = HashMap<String, ByteArray>()
        fun indexOf(from: Int, what: ByteArray): Int {
            var i = from
            while (i <= body.size - what.size) {
                var j = 0
                while (j < what.size && body[i + j] == what[j]) j++
                if (j == what.size) return i
                i++
            }
            return -1
        }
        var start = indexOf(0, delimiter)
        val headEnd = "\r\n\r\n".toByteArray()
        while (start >= 0) {
            val partStart = start + delimiter.size + 2 // skip CRLF
            val next = indexOf(partStart, delimiter)
            if (next < 0 || partStart >= body.size) break
            val h = indexOf(partStart, headEnd)
            if (h in 0 until next) {
                val head = String(body, partStart, h - partStart, Charsets.UTF_8)
                val name = Regex("""name="([^"]*)"""").find(head)?.groupValues?.get(1)
                val dataStart = h + headEnd.size
                val dataEnd = (next - 2).coerceAtLeast(dataStart) // drop the CRLF before the delimiter
                if (name != null) out[name] = body.copyOfRange(dataStart, dataEnd)
            }
            start = next
        }
        return out
    }

    private fun readLine(input: BufferedInputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = input.read()
            if (x == -1) return if (b.size() == 0) null else b.toString("UTF-8")
            if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            if (b.size() > 8_000) return null
            b.write(x)
        }
    }

    private fun readBytes(input: BufferedInputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf.copyOf(read)
    }

    private fun parse(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate { p ->
        val k = p.substringBefore('=')
        val v = p.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    // ---------------------------------------------------------------- Page

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The four tabs of the phone page (#49). Plain links, so the page works without JavaScript. */
    private enum class Tab(val id: String, val label: String, val title: String) {
        SETUP("setup", "Setup", "Ember setup"),
        SETTINGS("settings", "Settings", "Settings"),
        CONTENT("content", "Addons & content", "Addons & content"),
        BACKUP("backup", "Backup & diagnostics", "Backup & diagnostics");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SETUP }
    }

    private fun nav(access: PhoneSetupAccess, current: Tab): String = buildString {
        append("<nav>")
        Tab.entries.forEach { t ->
            append("<a href=\"/?k=${access.code}&amp;tab=${t.id}\"")
            if (t == current) append(" aria-current=page")
            append(">${esc(t.label)}</a>")
        }
        append("</nav>")
    }

    private fun tabs(access: PhoneSetupAccess, tab: Tab, handler: Handler, message: String?): String = buildString {
        access.requireActive()
        append(nav(access, tab))
        message?.let { append("<p class=ok role=status>${esc(it)}</p>") }
        val auth = "?k=${access.code}"
        // #159: cards flow into as many columns as the screen has room for.
        append("<div class=cols>")
        when (tab) {
            Tab.SETUP -> setupTab(auth, handler)
            Tab.SETTINGS -> settingsTab(auth, handler)
            Tab.CONTENT -> contentTab(auth, handler)
            Tab.BACKUP -> backupTab(auth, handler)
        }
        append("</div>")
    }

    private fun StringBuilder.setupTab(auth: String, handler: Handler) {
        runCatching { handler.status() }.getOrNull()?.let { status ->
            append("<section class=card><h2>Checklist</h2><p class=m>All optional. Configured is not a check that each service is online.</p><ul class=check>")
            status.steps.forEach { (label, done) -> append("<li class=${if (done) "done" else "todo"}>${esc(label)}<span>${if (done) "Configured" else "Not set up"}</span></li>") }
            append("</ul></section>")
        }
        append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=addon>")
        append("<h2>Add addons</h2><p class=m>Stremio-compatible manifest links ending in manifest.json, one per line.</p>")
        append("<textarea name=url rows=3 placeholder=\"https://…/manifest.json\"></textarea><button>Add</button></form>")

        append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=iptv>")
        append("<h2>Add IPTV</h2><p class=m>An M3U playlist link, or Xtream Codes server, username and password.</p>")
        append("<select name=kind><option value=m3u>M3U playlist</option><option value=xtream>Xtream Codes</option></select>")
        append("<input name=name placeholder=\"Name (optional)\"><input name=url placeholder=\"Playlist link or server address\">")
        append("<input name=username placeholder=\"Username (Xtream)\" autocomplete=off><input name=password type=password placeholder=\"Password (Xtream)\" autocomplete=off>")
        append("<input name=epg placeholder=\"Guide (XMLTV) link, optional\"><button>Add</button></form>")

        append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=keys>")
        append("<h2>API keys and logins</h2><p class=m>Only filled-in fields are saved; leave the rest empty to keep what is on the TV.</p>")
        ApiKeys.SERVICES.forEach { s ->
            append("<h3>${esc(s.name)}</h3><p class=m>${esc(s.usedFor)}<br>${esc(s.where)}</p>")
            s.fields.forEach { fld ->
                val saved = if (ApiKeys.isEntered(fld.id)) " · saved " + ApiKeys.hint(ApiKeys.get(fld.id)) else ""
                val type = if (fld.id.contains("PASSWORD") || fld.id.contains("SECRET") || fld.id.contains("TOKEN")) "password" else "text"
                append("<input name=${fld.id} type=$type autocomplete=off placeholder=\"${esc(fld.label + saved)}\">")
            }
        }
        append("<button>Save keys</button></form>")
    }

    private fun StringBuilder.settingsTab(auth: String, handler: Handler) {
        val all = runCatching { handler.settings() }.getOrElse { append("<p class=ok>${esc(it.message ?: "Settings are unavailable.")}</p>"); return }
            ?: run { append("<p class=m>Settings are unavailable.</p>"); return }
        append("<p class=m>Changes apply on the TV right away.</p>")
        append("<input id=find type=search placeholder=\"Search settings\" aria-label=\"Search settings\" autocomplete=off>")
        PhoneSettings.SETTINGS_GROUPS.forEach { group ->
            val items = all.filter { it.group == group }
            if (items.isEmpty()) return@forEach
            append("<section class=\"card g\"><h2>${esc(group)}</h2>")
            items.forEach { append(settingForm(auth, it)) }
            append("</section>")
        }
        append("<p class=m id=none hidden>No setting matches.</p>")
    }

    private fun StringBuilder.contentTab(auth: String, handler: Handler) {
        append("<section class=card><h2>Collections, folders and addons</h2><p class=m>Create collections, edit folders, and pause or resume addons. Each change is approved on the TV.</p>")
        append("<p><a class=btn href=\"/content$auth\">Organize content</a></p></section>")
        val catalogs = runCatching { handler.settings() }.getOrNull().orEmpty().filter { it.group == PhoneSettings.CATALOGS }
        if (catalogs.isNotEmpty()) {
            append("<input id=find type=search placeholder=\"Search catalogs\" aria-label=\"Search catalogs\" autocomplete=off>")
            catalogs.groupBy { it.note.orEmpty() }.forEach { (addon, rows) ->
                append("<section class=\"card g\"><h2>${esc(addon.ifBlank { "Catalogs" })}</h2><p class=m>Where each catalog shows. Changes apply right away.</p>")
                rows.forEach { append(settingForm(auth, it.copy(note = null))) }
                append("</section>")
            }
            append("<p class=m id=none hidden>No catalog matches.</p>")
        }
        append("<form method=post action=\"/presentation$auth\" enctype=multipart/form-data><h2>Presentation playlist</h2><p class=m>Images and short videos up to 8 MB. Approve each addition on the TV. For larger videos, choose a file on the TV.</p><input name=name required placeholder=\"File name including extension, e.g. photo.jpg or clip.mp4\"><input name=file type=file accept=\"image/*,video/*\" required><button>Send for TV approval</button></form>")
        append("<form method=post action=\"/collections$auth\" enctype=multipart/form-data>")
        append("<h2>Import collections</h2><p class=m>A collections export (JSON) for Settings &gt; Stremio &gt; Collections. Pick the file, or paste its contents.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the JSON here\"></textarea><input type=password name=passphrase autocomplete=off placeholder='Passphrase for encrypted Ember collection packs'><button>Add collections</button></form>")
        append("<form method=post action=\"/badges$auth\" enctype=multipart/form-data>")
        append("<h2>Source badges</h2><p class=m>A badge pack (JSON) for Settings &gt; Stremio &gt; Badges. Pick the file, or paste its contents.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the JSON here\"></textarea><button>Use these badges</button></form>")
    }

    private fun StringBuilder.backupTab(auth: String, handler: Handler) {
        append("<form method=post action=\"/backup$auth\">")
        append("<h2>Save a backup to this phone</h2><p class=m>Everything on the TV: settings, addons, sign-ins and keys. The file is encrypted with your passphrase; you need it to restore.</p>")
        append("<input name=passphrase type=password required minlength=$MIN_BACKUP_PASSPHRASE placeholder=\"Passphrase (at least $MIN_BACKUP_PASSPHRASE characters)\" autocomplete=off><button>Download backup</button></form>")

        append("<form method=post action=\"/restore$auth\" enctype=multipart/form-data>")
        append("<h2>Restore a backup on the TV</h2><p class=m>Pick an Ember backup file. Leave the box unticked to check it first; tick it to replace everything on the TV, which then restarts Ember.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><input name=passphrase type=password placeholder=\"Passphrase, if it has one\" autocomplete=off>")
        append("<label class=tick><input type=checkbox name=apply> Replace the TV's settings with this backup</label>")
        append("<button>Check or restore</button></form>")

        val entries = runCatching { handler.crashLog() }.getOrNull()
        append("<section class=card><h2>Crash log</h2>")
        when {
            entries == null -> append("<p class=m>The crash log is unavailable.</p>")
            entries.isEmpty() -> append("<p class=m>Nothing recorded. Crashes, freezes (ANR) and Android closing Ember appear here.</p>")
            else -> {
                append("<p class=m>${entries.size} entr${if (entries.size == 1) "y" else "ies"}, newest first. Links, passwords and keys are removed.</p>")
                entries.take(CRASH_ENTRIES_SHOWN).forEachIndexed { i, e ->
                    val first = e.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120)
                    append("<details${if (i == 0) " open" else ""}><summary>${esc(first)}</summary><pre>${esc(e)}</pre></details>")
                }
                if (entries.size > CRASH_ENTRIES_SHOWN) append("<p class=m>Download the log to see all ${entries.size}.</p>")
            }
        }
        if (!entries.isNullOrEmpty()) {
            append("<form class=flat method=post action=\"/crash-log$auth\"><button>Download crash log</button></form>")
            append("<form class=flat method=post action=\"/crash-log-clear$auth\"><label class=tick><input type=checkbox name=confirm required> Delete every entry</label><button class=danger>Clear crash log</button></form>")
        }
        append("</section>")
        runCatching { handler.status() }.getOrNull()?.let { append("<p class=m>Ember ${esc(it.version)}</p>") }
    }

    private fun settingForm(auth: String, s: PhoneSettingView): String = buildString {
        val id = esc(s.id)
        val search = esc(listOf(s.group, s.label, s.note.orEmpty(), s.options.joinToString(" ") { it.second }).joinToString(" ").lowercase(java.util.Locale.ROOT))
        append("<form class=s method=post action=\"/setting$auth\" data-q=\"$search\"><input type=hidden name=id value=\"$id\">")
        append("<label for=\"v-$id\">${esc(s.label)}")
        s.note?.let { append("<small>${esc(it)}</small>") }
        append("</label>")
        when (s.kind) {
            "toggle" -> append("<input type=hidden name=value value=false><input class=sw id=\"v-$id\" type=checkbox role=switch name=value value=true${if (s.value == "true") " checked" else ""}>")
            "choice" -> {
                append("<select id=\"v-$id\" name=value>")
                s.options.forEach { (v, l) -> append("<option value=\"${esc(v)}\"${if (v == s.value) " selected" else ""}>${esc(l)}</option>") }
                append("</select>")
            }
            else -> append("<input id=\"v-$id\" type=number inputmode=numeric name=value min=${s.min} max=${s.max} value=\"${esc(s.value)}\">")
        }
        append("<button class=save>Save</button><span class=st role=status aria-live=polite></span></form>")
    }

    /** The crash log as one text file, for sending to whoever is fixing it (#48). */
    internal fun crashLogText(entries: List<String>, version: String?): String = buildString {
        append("Ember crash log\n")
        version?.let { append("Version: ").append(it).append('\n') }
        append("Saved: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(java.util.Date())).append('\n')
        append("Entries: ").append(entries.size).append(", newest first\n\n")
        entries.forEach { append(it.trim()).append("\n\n----------------------------------------\n\n") }
    }

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>
:root{color-scheme:dark;--bg:#111215;--card:#1f2024;--line:#2c2d32;--fg:#e8eaed;--mute:#a9adb4;--accent:$accent}
body{margin:0;font:16px system-ui,sans-serif;background:var(--bg);color:var(--fg);-webkit-text-size-adjust:100%}
main{max-width:1240px;margin:auto;padding:16px max(16px,env(safe-area-inset-right)) 32px max(16px,env(safe-area-inset-left))}
h1{font-size:24px;margin:8px 0 2px}h1 i{display:inline-block;width:12px;height:12px;border-radius:50%;background:var(--accent);margin-right:10px}
h2{font-size:19px;margin:0 0 4px}h3{font-size:15px;margin:16px 0 2px}.m{color:var(--mute);font-size:13px;margin:2px 0 8px}
nav{position:sticky;top:0;z-index:2;display:flex;gap:6px;overflow-x:auto;padding:10px 0;background:var(--bg);scrollbar-width:none}
nav a{flex:none;padding:9px 14px;border-radius:999px;background:var(--card);color:var(--fg);text-decoration:none;font-size:14px;font-weight:600}
nav a[aria-current]{background:var(--accent);color:#111}
form,.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin:14px 0}
.cols{column-width:320px;column-gap:20px}.cols>*{break-inside:avoid;-webkit-column-break-inside:avoid}
.cols>form,.cols>.card,.cols>.grp{margin:0 0 16px}.cols>p,.cols>#find,.cols>h2{column-span:all;-webkit-column-span:all}
.grp>h3{font-size:17px;margin:0 0 2px}.grp>form{margin:8px 0 0}
.cols>#find{margin-bottom:14px}main>h2{margin-top:12px}
input:focus,select:focus,textarea:focus{outline:0;border-color:var(--accent)}
a:focus-visible,button:focus-visible,.sw:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
button:active,.btn:active{opacity:.8}
input,select,textarea{box-sizing:border-box;width:100%;margin:4px 0;padding:12px;border-radius:10px;border:1px solid #3a3b40;background:var(--bg);color:var(--fg);font-size:16px}
button,.btn{display:block;box-sizing:border-box;text-align:center;text-decoration:none;margin-top:10px;width:100%;padding:13px;border:0;border-radius:999px;background:#f1f3f4;color:#1f1f1f;font-size:16px;font-weight:600}
button.danger{background:#5c1f1f;color:#ffd9d6}
.ok{background:#1e3a2a;color:#b9f0cf;padding:12px;border-radius:10px}
.tick{display:flex;gap:10px;align-items:center;margin:8px 0}.tick input{width:auto}
form.flat{background:none;border:0;padding:0;margin:10px 0}
form.s{display:grid;grid-template-columns:1fr auto;gap:4px 12px;align-items:center;background:none;border:0;border-radius:0;margin:0;padding:12px 0;border-top:1px solid var(--line)}
form.s label{font-size:15px}form.s small{display:block;color:var(--mute);font-size:12px;margin-top:2px}
form.s select,form.s input[type=number]{width:auto;max-width:min(52vw,220px);margin:0}
form.s .save{grid-column:1/-1;margin-top:4px;padding:9px}form.s .st{grid-column:1/-1;font-size:12px;color:var(--mute)}form.s .st:empty{display:none}
.st.good{color:#8fe3b0}.st.bad{color:#ffb4ab}
.js form.s .save{display:none}
.sw{appearance:none;-webkit-appearance:none;width:52px;height:30px;margin:0;padding:0;border-radius:999px;background:#3a3b40;position:relative;border:0}
.sw:before{content:"";position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:50%;background:#e8eaed;transition:left .15s}
.sw:checked{background:var(--accent)}.sw:checked:before{left:25px}
.check{list-style:none;padding:0;margin:0}.check li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid var(--line)}
.check span{font-size:13px;color:var(--mute)}.check .done span{color:#8fe3b0}
details{border-top:1px solid var(--line);padding:8px 0}summary{cursor:pointer;font-size:14px}
pre{white-space:pre-wrap;word-break:break-word;font-size:12px;background:var(--bg);padding:10px;border-radius:10px;max-height:50vh;overflow:auto}
@media(min-width:700px){main{padding-top:24px;padding-bottom:40px}h1{font-size:30px}#find{max-width:520px;display:block}
nav{gap:8px;padding:14px 0}nav a{padding:11px 18px;font-size:15px}main>p.m{font-size:14px}}
[hidden]{display:none!important}</style></head>
<body><main><h1><i></i>${esc(title)}</h1><p class=m>Sent straight to your TV on this network. Nothing goes through the internet.</p>$body</main><script>$SCRIPT</script></body></html>"""

    companion object {
        internal const val MIN_BACKUP_PASSPHRASE = 8
        private const val CRASH_ENTRIES_SHOWN = 10
        /**
         * Instant saves (#49): each change posts its form in the background and shows the TV's answer
         * next to the setting. Without JavaScript the Save buttons stay and do the same.
         */
        private const val SCRIPT = """
document.documentElement.classList.add('js');
function show(f,v){var i=f.querySelector('[name=value]:not([type=hidden])');if(!i)return;if(i.type==='checkbox')i.checked=v==='true';else i.value=v;}
document.querySelectorAll('form.s').forEach(function(f){
  var st=f.querySelector('.st'),i=f.querySelector('[name=value]:not([type=hidden])'),last=i.type==='checkbox'?String(i.checked):i.value;
  function send(){
    var d=new URLSearchParams(new FormData(f));d.set('js','1');st.className='st';st.textContent='Saving…';
    fetch(f.action,{method:'POST',body:d}).then(function(r){return r.json();}).then(function(j){
      st.textContent=j.message;st.className='st '+(j.ok?'good':'bad');
      if(j.value!=null){show(f,j.value);last=j.value;}else show(f,last);
    }).catch(function(){st.textContent='Not saved: the TV did not answer. The session may have ended.';st.className='st bad';show(f,last);});
  }
  i.addEventListener('change',send);f.addEventListener('submit',function(e){e.preventDefault();send();});
});
var find=document.getElementById('find');
if(find)find.addEventListener('input',function(){
  var q=find.value.trim().toLowerCase(),any=false;
  document.querySelectorAll('.g').forEach(function(g){var n=0;g.querySelectorAll('form.s').forEach(function(f){var hit=!q||f.dataset.q.indexOf(q)>=0;f.hidden=!hit;if(hit)n++;});g.hidden=n===0;if(n)any=true;});
  var none=document.getElementById('none');if(none)none.hidden=any;
});
"""
        private const val HEADER_TIMEOUT_MILLIS = 4_000

        /** The first byte of a TLS record (0x16, handshake) or an old SSLv2 hello (0x80); no HTTP method starts with either. */
        internal fun isTlsHandshake(firstByte: Int): Boolean = firstByte == 0x16 || firstByte == 0x80
        private const val BODY_TIMEOUT_MILLIS = 10_000
        private val timer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "ember-phone-expiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /** The TV's address on the home network (Wi-Fi or Ethernet). */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
