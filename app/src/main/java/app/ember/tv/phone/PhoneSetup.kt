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
 * Settings > Phone setup is explicitly enabled (for at most 30 minutes). The phone opens it by QR code and sends addon links, API keys
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
        /** A search from the widget editor (#161): [kind] is person, company, provider or trakt ([tab] popular or trending). */
        fun lookup(kind: String, tab: String, query: String): List<PhoneLookup> = emptyList()
        /** Every Home widget as a pack file (#163); null when there is none to export. */
        fun exportWidgets(): ByteArray? = null
        /** A widget pack's text, sent to the TV for approval. Returns a message. */
        fun importWidgets(text: String): String = "Widget import is unavailable."
        /** Setup checklist and version (#49). */
        fun status(): PhoneStatus? = null
        /** Every setting the phone may change, read on the TV; throws with a message when none may be. */
        fun settings(): List<PhoneSettingView>? = null
        /** Applies one setting at once (#49); the value is checked on the TV. */
        fun changeSetting(id: String, value: String): PhoneSettingResult = PhoneSettingResult(false, "Settings are unavailable.")
        /** Crash log entries, newest first (#48). */
        fun crashLog(): List<String>? = null
        fun clearCrashLog(): String = "The crash log is unavailable."
        /** #236: the badge option in use and your custom badges; null when unavailable. */
        fun badges(): PhoneBadges? = null
        /** #236: one badge edit from the phone; [picture] is an uploaded file, if any. Returns what happened. */
        fun changeBadge(fields: Map<String, String>, picture: ByteArray?): String = "Badge editing is unavailable."
        /** #236: a badge picture kept on the TV, with its media type, for the phone's preview. */
        fun badgeIcon(id: String): Pair<ByteArray, String>? = null
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
        val maxBody = if (path == "/restore" || path == "/badges" || path == "/badge" || path == "/collections" || path=="/presentation") 8_000_000 else if (path == "/widgets") 2_000_000 else 64_000
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
        fun shell(shown: Tab, message: String? = null, section: String? = null) = respond(c, 200, page(shown.title, tabs(access, shown, section, handler, message)))
        // #165: Organize content is three pages. The page a change came from is the one shown after it.
        fun contentPage(part: String?, message: String? = null) {
            val shown = part?.takeIf { it in ContentPhone.PARTS } ?: "widgets"
            val body = handler.content()?.let { ContentPhone.render(it, access.code, shown) }.orEmpty()
            respond(c, 200, page(ContentPhone.title(shown), nav(access, if (shown == "tools") Tab.ADDONS else Tab.UI) + message?.let { "<p class=ok role=status>${esc(it)}</p>" }.orEmpty() + body))
        }
        when {
            method == "POST" && path == "/save" -> handle(form, handler, access).let { message -> when (form["action"]) {
                "keys" -> shell(Tab.ACCOUNTS, message)
                "iptv" -> shell(Tab.ADDONS, message, "iptv")
                else -> shell(Tab.ADDONS, message, "add")
            } }
            method == "POST" && path == "/setting" -> {
                val result = runCatching { handler.changeSetting(form["id"].orEmpty(), form["value"].orEmpty()) }
                    .getOrElse { PhoneSettingResult(false, it.message ?: "Could not change that setting.") }
                access.requireActive()
                if (form["js"] == "1") respondJson(c, kotlinx.serialization.json.buildJsonObject {
                    put("ok", kotlinx.serialization.json.JsonPrimitive(result.ok))
                    put("message", kotlinx.serialization.json.JsonPrimitive(result.message))
                    result.setting?.let { put("value", kotlinx.serialization.json.JsonPrimitive(it.value)) }
                }.toString())
                else {
                    // Without JavaScript the form reloads the sub-page the setting is on.
                    val id = form["id"].orEmpty()
                    val place = if (id.startsWith(PhoneSettings.CATALOGS_PREFIX)) Tab.ADDONS to "catalogs"
                        else runCatching { handler.settings() }.getOrNull()?.firstOrNull { it.id == id }?.let(PhonePages::place) ?: (Tab.SETTINGS to null)
                    shell(place.first, result.message, place.second)
                }
            }
            method == "POST" && path == "/backup" -> {
                // The phone page is plain HTTP on the home network: an unencrypted backup would carry
                // every sign-in and key across it readable. Only encrypted backups leave the TV this way.
                val passphrase = form["passphrase"].orEmpty()
                if (passphrase.length < MIN_BACKUP_PASSPHRASE) shell(Tab.BACKUP,
                    "Enter a passphrase of at least $MIN_BACKUP_PASSPHRASE characters. Backups saved to a phone are always encrypted.", "backup")
                else run {
                val bytes = runCatching { handler.exportBackup(passphrase) }.getOrNull()
                if (bytes == null) shell(Tab.BACKUP, "Could not make the backup.", "backup")
                else { access.requireActive(); respondFile(c, app.ember.tv.data.Backup.fileName(), bytes) }
                }
            }
            method == "POST" && path == "/restore" -> {
                val file = multipart["file"]
                val msg = if (file == null || file.isEmpty()) "Choose a backup file first."
                else handler.importBackup(file, form["passphrase"].orEmpty(), form["apply"] == "on")
                shell(Tab.BACKUP, msg, "restore")
            }
            method == "POST" && path == "/crash-log" -> {
                val entries = runCatching { handler.crashLog() }.getOrNull()
                access.requireActive()
                if (entries == null) shell(Tab.BACKUP, "The crash log is unavailable.", "crash")
                else respondFile(c, "ember-crash-log.txt", crashLogText(entries, runCatching { handler.status()?.version }.getOrNull()).toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
            }
            method == "POST" && path == "/crash-log-clear" -> shell(Tab.BACKUP,
                if (form["confirm"] != "on") "Tick the box to confirm, then clear the log."
                else runCatching { handler.clearCrashLog() }.getOrElse { it.message ?: "Could not clear the crash log." }, "crash")
            // #236: one badge edit (add, save, move, delete, copy); the page returns to the badge list.
            method == "POST" && path == "/badge" -> {
                val picture = multipart["file"]?.takeIf { it.isNotEmpty() }
                val msg = runCatching { handler.changeBadge(form, picture) }.getOrElse { it.message ?: "Could not change the badge." }
                shell(Tab.ADDONS, msg, "badges")
            }
            path == BadgePhone.ICON_PATH -> {
                val icon = query["id"]?.let { id -> runCatching { handler.badgeIcon(id) }.getOrNull() }
                if (icon == null) respond(c, 404, page("Not found", "")) else respondImage(c, icon.first, icon.second)
            }
            method == "POST" && path == "/badges" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                val msg = if (text.isBlank()) "Choose a badge pack file, or paste its JSON." else handler.importBadges(text)
                shell(Tab.ADDONS, msg, "badges")
            }
            method == "POST" && path == "/collections" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                val msg = if (text.isBlank()) "Choose a collections file, or paste its JSON." else runCatching { app.ember.tv.data.CollectionTransfer.open(text, form["passphrase"].orEmpty()) }
                    .fold({ handler.importCollections(it) }, { "Could not unlock the collection pack. Check its passphrase and file." })
                shell(Tab.UI, msg, "import")
            }
            // #163: widget packs, like collection packs. Export downloads; import is approved on the TV.
            method == "POST" && path == "/widgets-export" -> {
                val bytes = runCatching { handler.exportWidgets() }.getOrNull()
                access.requireActive()
                if (bytes == null) respond(c, 200, page("Widget export", "<p>There are no widgets to export yet.</p>"))
                else respondFile(c, "ember-widgets.json", bytes)
            }
            method == "POST" && path == "/widgets" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                contentPage("widgets", if (text.isBlank()) "Choose a widget pack file, or paste its contents." else handler.importWidgets(text))
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
                shell(Tab.SETTINGS, message, "presentation")
            }
            method == "POST" && path == "/content" -> {
                contentPage(ContentPhone.partOf(form["action"]), handler.proposeContent(form))
            }
            // #161: searches made by the widget editor; answers are names and numbers only.
            method == "GET" && path == "/lookup" -> {
                // #165: a search that fails says why, instead of looking like "nothing found".
                val found = runCatching { handler.lookup(query["kind"].orEmpty(), query["tab"].orEmpty(), query["q"].orEmpty().take(100)) }
                access.requireActive()
                respondJson(c, found.fold({ rows -> kotlinx.serialization.json.buildJsonArray { rows.take(40).forEach { row -> add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(row.id)); put("name", kotlinx.serialization.json.JsonPrimitive(row.name)); put("note", kotlinx.serialization.json.JsonPrimitive(row.note))
                }) } }.toString() }, { failure -> kotlinx.serialization.json.buildJsonObject {
                    put("error", kotlinx.serialization.json.JsonPrimitive(PhoneLookups.reason(query["kind"].orEmpty(), failure)))
                }.toString() }))
            }
            // #161: so is the widget editor.
            path == "/content" && query["widget"] != null -> respond(c, 200, page("Edit widget", nav(access, Tab.UI) +
                handler.content()?.let { ContentPhone.widgetEditor(it, access.code, query["widget"].orEmpty()) }.orEmpty()))
            // #160: the folder editor is its own page.
            path == "/content" && query["edit"] != null -> respond(c, 200, page("Edit collection", nav(access, Tab.UI) +
                handler.content()?.let { ContentPhone.editor(it, access.code, query["edit"].orEmpty(), query["in"]) }.orEmpty()))
            path == "/content" -> contentPage(query["part"])
            path == "/" -> shell(tab, section = query["s"])
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

    /** A badge picture shown in the page (#236), only the image types the TV recognised. */
    private fun respondImage(c: Socket, bytes: ByteArray, type: String) {
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: private, max-age=300\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nConnection: close\r\n\r\n").toByteArray(),
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

    /**
     * The sections of the phone and tablet page (#49, regrouped in #165). Each opens on a short menu
     * of sub-pages, so no page is a long scroll. Plain links, so the page works without JavaScript.
     */
    internal enum class Tab(val id: String, val label: String) {
        SETUP("setup", "Setup"),
        SETTINGS("settings", "Settings"),
        UI("ui", "UI"),
        PLAYER("player", "Player"),
        ADDONS("addons", "Addons"),
        ACCOUNTS("accounts", "Accounts & APIs"),
        BACKUP("backup", "Backup & diagnostics");
        val title: String get() = if (this == SETUP) "Ember setup" else label
        companion object {
            /** "content" was the old Addons & content tab; links to it still land somewhere sensible. */
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: if (id == "content") ADDONS else SETUP
        }
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

    private fun tabs(access: PhoneSetupAccess, tab: Tab, section: String?, handler: Handler, message: String?): String = buildString {
        access.requireActive()
        append(nav(access, tab))
        message?.let { append("<p class=ok role=status>${esc(it)}</p>") }
        val auth = "?k=${access.code}"
        when (tab) {
            Tab.SETUP -> setupTab(auth, handler)
            Tab.SETTINGS -> settingsTab(auth, handler, section)
            Tab.UI -> uiTab(auth, handler, section)
            Tab.PLAYER -> playerTab(auth, handler, section)
            Tab.ADDONS -> addonsTab(auth, handler, section)
            Tab.ACCOUNTS -> accountsTab(auth)
            Tab.BACKUP -> backupTab(auth, handler, section)
        }
    }

    /** One entry of a section's menu: a sub-page id (or a full link starting with "/"), its title and a line about it. */
    private data class MenuItem(val target: String, val title: String, val summary: String)

    private fun StringBuilder.menu(auth: String, tab: Tab, items: List<MenuItem>) {
        append("<div class=menu>")
        items.forEach { item ->
            val href = if (item.target.startsWith("/")) item.target else "/$auth&amp;tab=${tab.id}&amp;s=${item.target}"
            append("<a class=mi href=\"$href\"><span><b>${esc(item.title)}</b><small>${esc(item.summary)}</small></span><i>&rsaquo;</i></a>")
        }
        append("</div>")
    }

    private fun StringBuilder.back(auth: String, tab: Tab) { append("<p><a class=back href=\"/$auth&amp;tab=${tab.id}\">&lsaquo; ${esc(tab.label)}</a></p>") }

    private fun count(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

    private fun settingsOf(handler: Handler): List<PhoneSettingView>? = runCatching { handler.settings() }.getOrNull()

    /**
     * A sub-page of settings. Rows that share a "Name: " prefix (Custom profile, Light Home, Labels
     * under tiles…) fold into one collapsible group, so the page stays short; search opens the groups
     * that match.
     */
    private fun StringBuilder.settingsList(auth: String, rows: List<PhoneSettingView>) {
        if (rows.isEmpty()) { append("<p class=m>Nothing to change here on this TV.</p>"); return }
        append("<p class=m>Changes apply on the TV right away.</p>")
        if (rows.size > 8) append("<input id=find type=search placeholder=\"Search these settings\" aria-label=\"Search these settings\" autocomplete=off>")
        val (plain, groups) = PhonePages.fold(rows)
        if (plain.isNotEmpty()) {
            append("<section class=\"card g\">")
            plain.forEach { append(settingForm(auth, it)) }
            append("</section>")
        }
        groups.forEach { (name, members) ->
            append("<details class=\"card g\"><summary><span>${esc(name)}</span><small>${count(members.size, "setting")}</small></summary><div class=in>")
            members.forEach { append(settingForm(auth, it.copy(label = it.label.substringAfter(": ").replaceFirstChar { c -> c.uppercase() }))) }
            append("</div></details>")
        }
        append("<p class=m id=none hidden>No setting matches.</p>")
    }

    private fun StringBuilder.setupTab(auth: String, handler: Handler) {
        runCatching { handler.status() }.getOrNull()?.let { status ->
            val done = status.steps.count { it.second }
            append("<section class=card><div class=head><h2>Checklist</h2><span class=m>$done of ${status.steps.size} set up</span></div><p class=m>All optional. Configured is not a check that each service is online.</p><ul class=check>")
            status.steps.forEach { (label, ok) -> append("<li class=${if (ok) "done" else "todo"}>${esc(label)}<span>${if (ok) "Configured" else "Not set up"}</span></li>") }
            append("</ul></section>")
        }
        append("<h2>Start here</h2>")
        menu(auth, Tab.SETUP, listOf(
            MenuItem("/$auth&amp;tab=addons&amp;s=add", "Add addons", "Paste Stremio-compatible manifest links."),
            MenuItem("/$auth&amp;tab=addons&amp;s=iptv", "Add Live TV", "An M3U playlist or Xtream Codes account."),
            MenuItem("/$auth&amp;tab=accounts", "API keys and logins", "TMDB, trackers, ratings and artwork services."),
            MenuItem("/content$auth&amp;part=widgets", "Home widgets", "Your own rows and banners on Home."),
            MenuItem("/$auth&amp;tab=backup&amp;s=restore", "Restore a backup", "Bring settings over from another Ember TV."),
        ))
    }

    private fun StringBuilder.settingsTab(auth: String, handler: Handler, section: String?) {
        // No settings from the TV (an older build, or none for this profile): the menus and forms still work.
        val all = settingsOf(handler).orEmpty()
        fun rows(part: String) = all.filter { PhonePages.place(it).let { place -> place.first == Tab.SETTINGS && place.second == part } }
        when (section) {
            "home", "sources", "system" -> {
                back(auth, Tab.SETTINGS)
                append("<h2>${when (section) { "home" -> "Home"; "sources" -> "Sources"; else -> "System and remote" }}</h2>")
                settingsList(auth, rows(section))
            }
            "presentation" -> {
                back(auth, Tab.SETTINGS)
                append("<form method=post action=\"/presentation$auth\" enctype=multipart/form-data><h2>Presentation playlist</h2><p class=m>Images and short videos up to 8 MB. Approve each addition on the TV. For larger videos, choose a file on the TV.</p><input name=name required placeholder=\"File name including extension, e.g. photo.jpg\"><input type=file name=file required><button>Send to presentation playlist</button></form>")
            }
            else -> menu(auth, Tab.SETTINGS, listOf(
                MenuItem("home", "Home", "Rows, lists and what Home shows · " + count(rows("home").size, "setting")),
                MenuItem("sources", "Sources", "Streams, metadata and where to watch · " + count(rows("sources").size, "setting")),
                MenuItem("system", "System and remote", "Updates, remote and device · " + count(rows("system").size, "setting")),
                MenuItem("presentation", "Presentation playlist", "Send images and short videos to the TV's slideshow."),
            ))
        }
    }

    private fun StringBuilder.uiTab(auth: String, handler: Handler, section: String?) {
        // No settings from the TV (an older build, or none for this profile): the menus and forms still work.
        val all = settingsOf(handler).orEmpty()
        fun rows(part: String) = all.filter { PhonePages.place(it).let { place -> place.first == Tab.UI && place.second == part } }
        val titles = listOf("interface" to "Interface and colours", "size" to "Size and spacing", "tiles" to "Home and tiles", "effects" to "Performance and effects")
        val current = titles.firstOrNull { it.first == section }
        when {
            current != null -> { back(auth, Tab.UI); append("<h2>${current.second}</h2>"); settingsList(auth, rows(current.first)) }
            section == "import" -> {
                back(auth, Tab.UI)
                append("<form method=post action=\"/collections$auth\" enctype=multipart/form-data>")
                append("<h2>Import collections</h2><p class=m>A collections export (JSON) for Settings &gt; Home &gt; Widgets &amp; collections. Pick the file, or paste its contents.</p>")
                append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the JSON here\"></textarea><input type=password name=passphrase autocomplete=off placeholder='Passphrase for encrypted Ember collection packs'><button>Add collections</button></form>")
            }
            else -> menu(auth, Tab.UI, titles.map { (id, title) -> MenuItem(id, title, when (id) {
                "interface" -> "Interface, theme, colour theme, navbar"; "size" -> "Interface and text size, margins, density"
                "tiles" -> "App icons, cards, labels, Ember Light Home"; else -> "Performance profile, glass, focus effects" } + " · " + count(rows(id).size, "setting")) } + listOf(
                MenuItem("/content$auth&amp;part=widgets", "Home widgets", "Your own rows, numbered lists, carousels and banners."),
                MenuItem("/content$auth&amp;part=collections", "Collections", "Rows of folder tiles: create, edit, reorder, delete."),
                MenuItem("import", "Import collections", "Add collections from a file or pasted pack."),
            ))
        }
    }

    private fun StringBuilder.playerTab(auth: String, handler: Handler, section: String?) {
        // No settings from the TV (an older build, or none for this profile): the menus and forms still work.
        val all = settingsOf(handler).orEmpty()
        fun rows(part: String) = all.filter { PhonePages.place(it).let { place -> place.first == Tab.PLAYER && place.second == part } }
        when (section) {
            "playback", "subtitles" -> { back(auth, Tab.PLAYER); append("<h2>${if (section == "playback") "Playback" else "Languages and subtitles"}</h2>"); settingsList(auth, rows(section)) }
            else -> menu(auth, Tab.PLAYER, listOf(
                MenuItem("playback", "Playback", "Player, audio, video and the Play button · " + count(rows("playback").size, "setting")),
                MenuItem("subtitles", "Languages and subtitles", "Subtitle look and preferred languages · " + count(rows("subtitles").size, "setting")),
            ))
        }
    }

    private fun StringBuilder.addonsTab(auth: String, handler: Handler, section: String?) {
        val catalogs = settingsOf(handler).orEmpty().filter { it.group == PhoneSettings.CATALOGS }
        when (section) {
            "add" -> {
                back(auth, Tab.ADDONS)
                append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=addon>")
                append("<h2>Add addons</h2><p class=m>Stremio-compatible manifest links ending in manifest.json, one per line.</p>")
                append("<textarea name=url rows=3 placeholder=\"https://…/manifest.json\"></textarea><button>Add</button></form>")
            }
            "iptv" -> {
                back(auth, Tab.ADDONS)
                append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=iptv>")
                append("<h2>Add Live TV</h2><p class=m>An M3U playlist link, or Xtream Codes server, username and password.</p>")
                append("<select name=kind><option value=m3u>M3U playlist</option><option value=xtream>Xtream Codes</option></select>")
                append("<input name=name placeholder=\"Name (optional)\"><input name=url placeholder=\"Playlist link or server address\">")
                append("<input name=username placeholder=\"Username (Xtream)\" autocomplete=off><input name=password type=password placeholder=\"Password (Xtream)\" autocomplete=off>")
                append("<input name=epg placeholder=\"Guide (XMLTV) link, optional\"><button>Add</button></form>")
            }
            "catalogs" -> {
                back(auth, Tab.ADDONS)
                append("<h2>Catalogs</h2>")
                if (catalogs.isEmpty()) append("<p class=m>No catalogs yet. Add an addon first.</p>") else {
                    append("<p class=m>Where each catalog shows. Open an addon to see its catalogs; changes apply right away.</p>")
                    append("<input id=find type=search placeholder=\"Search catalogs\" aria-label=\"Search catalogs\" autocomplete=off>")
                    val byAddon = catalogs.groupBy { it.note.orEmpty() }
                    byAddon.forEach { (addon, rows) ->
                        append("<details class=\"card g\"${if (byAddon.size == 1) " open" else ""}><summary><span>${esc(addon.ifBlank { "Catalogs" })}</span><small>${count(rows.size, "switch", "switches")}</small></summary><div class=in>")
                        rows.forEach { append(settingForm(auth, it.copy(note = null))) }
                        append("</div></details>")
                    }
                    append("<p class=m id=none hidden>No catalog matches.</p>")
                }
            }
            "badges" -> {
                back(auth, Tab.ADDONS)
                // #236: edit your badges here; importing a whole pack stays at the end.
                append(BadgePhone.render(runCatching { handler.badges() }.getOrNull(), auth))
            }
            else -> menu(auth, Tab.ADDONS, listOf(
                MenuItem("add", "Add addons", "Paste Stremio-compatible manifest links."),
                MenuItem("catalogs", "Catalogs", if (catalogs.isEmpty()) "Where each catalog shows." else "Where each catalog shows · " + count(catalogs.map { it.note.orEmpty() }.distinct().size, "addon")),
                MenuItem("/content$auth&amp;part=tools", "Sources and addon tools", "Add catalogs to folders, edit filters, export, pause addons."),
                MenuItem("iptv", "Add Live TV", "An M3U playlist or Xtream Codes account."),
                MenuItem("badges", "Source badges", "Edit badge patterns, colours and pictures, or import a pack."),
            ))
        }
    }

    /** One collapsible form per service, so thirty key fields are not one long page. */
    private fun StringBuilder.accountsTab(auth: String) {
        append("<h2>API keys and logins</h2><p class=m>Open a service to add or replace its keys. Empty fields keep what is on the TV.</p>")
        // #233: service tiles as on the TV (#214): a green check when ready, red when a required key is missing.
        append("<div class=svc>")
        ApiKeys.SERVICES.forEach { s ->
            val saved = s.fields.count { ApiKeys.isEntered(it.id) }
            val required = ApiKeys.required(s)
            val (mark, glyph) = when (ApiKeys.status(ApiKeys.ready(s), required)) {
                ApiKeys.Status.SET -> "ok" to "&#10003;"; ApiKeys.Status.MISSING_REQUIRED -> "bad" to "!"; ApiKeys.Status.MISSING_OPTIONAL -> "off" to "&ndash;"
            }
            append("<details class=\"card tile\"><summary><i class=\"mk $mark\" aria-hidden=true>$glyph</i><span><b>${esc(s.name)}</b><small>${if (saved == 0) "Not set" else if (saved == s.fields.size) "Saved" else "$saved of ${s.fields.size} saved"}</small></span>")
            append("<em class=${if (required) "req" else "opt"}>${if (required) "Required" else "Optional"}</em></summary><div class=in>")
            append("<form class=flat method=post action=\"/save$auth\"><input type=hidden name=action value=keys><p class=m>${esc(s.usedFor)}<br>${esc(s.where)}${ApiKeys.LIMITS[s.name]?.let { "<br>" + esc(it) }.orEmpty()}</p>")
            s.fields.forEach { fld ->
                val hint = if (ApiKeys.isEntered(fld.id)) " · saved " + ApiKeys.hint(ApiKeys.get(fld.id)) else ""
                val type = if (fld.id.contains("PASSWORD") || fld.id.contains("SECRET") || fld.id.contains("TOKEN")) "password" else "text"
                append("<input name=${fld.id} type=$type autocomplete=off placeholder=\"${esc(fld.label + hint)}\" aria-label=\"${esc(s.name + " " + fld.label)}\">")
            }
            append("<button>Save ${esc(s.name)}</button></form></div></details>")
        }
        append("</div>")
    }

    private fun StringBuilder.backupTab(auth: String, handler: Handler, section: String?) {
        val entries = runCatching { handler.crashLog() }.getOrNull()
        when (section) {
            "backup" -> {
                back(auth, Tab.BACKUP)
                append("<form method=post action=\"/backup$auth\">")
                append("<h2>Save a backup to this phone</h2><p class=m>Everything on the TV: settings, addons, sign-ins and keys. The file is encrypted with your passphrase; you need it to restore.</p>")
                append("<input name=passphrase type=password required minlength=$MIN_BACKUP_PASSPHRASE placeholder=\"Passphrase (at least $MIN_BACKUP_PASSPHRASE characters)\" autocomplete=off><button>Download backup</button></form>")
            }
            "restore" -> {
                back(auth, Tab.BACKUP)
                append("<form method=post action=\"/restore$auth\" enctype=multipart/form-data>")
                append("<h2>Restore a backup on the TV</h2><p class=m>Pick an Ember backup file. Leave the box unticked to check it first; tick it to replace everything on the TV, which then restarts Ember.</p>")
                append("<input type=file name=file accept=\".json,application/json\"><input name=passphrase type=password placeholder=\"Passphrase, if it has one\" autocomplete=off>")
                append("<label class=tick><input type=checkbox name=apply> Replace the TV's settings with this backup</label>")
                append("<button>Check or restore</button></form>")
            }
            "crash" -> {
                back(auth, Tab.BACKUP)
                append("<section class=card><h2>Crash log</h2>")
                when {
                    entries == null -> append("<p class=m>The crash log is unavailable.</p>")
                    entries.isEmpty() -> append("<p class=m>Nothing recorded. Crashes, freezes (ANR) and Android closing Ember appear here.</p>")
                    else -> {
                        append("<p class=m>${entries.size} entr${if (entries.size == 1) "y" else "ies"}, newest first. Links, passwords and keys are removed.</p>")
                        append("<div id=crashes>")
                        entries.take(CRASH_ENTRIES_SHOWN).forEachIndexed { i, e ->
                            val first = e.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120)
                            append("<details${if (i == 0) " open" else ""}><summary>${esc(first)}</summary><pre>${esc(e)}</pre></details>")
                        }
                        append("</div>")
                        if (entries.size > CRASH_ENTRIES_SHOWN) append("<p class=m>Download the log to see all ${entries.size}.</p>")
                    }
                }
                if (!entries.isNullOrEmpty()) {
                    // #257: copy straight into a message, no file needed.
                    append("<button type=button class=flat data-copy=\"#crashes pre\">Copy crash log</button>")
                    append("<form class=flat method=post action=\"/crash-log$auth\"><button>Download crash log</button></form>")
                    append("<form class=flat method=post action=\"/crash-log-clear$auth\"><label class=tick><input type=checkbox name=confirm required> Delete every entry</label><button class=danger>Clear crash log</button></form>")
                }
                append("</section>")
            }
            else -> menu(auth, Tab.BACKUP, listOf(
                MenuItem("backup", "Save a backup", "Download everything on the TV, encrypted with a passphrase."),
                MenuItem("restore", "Restore a backup", "Check a backup file, or replace the TV's settings with it."),
                MenuItem("crash", "Crash log", when { entries == null -> "Unavailable."; entries.isEmpty() -> "Nothing recorded."; else -> count(entries.size, "entry", "entries") + ", newest first." }),
            ))
        }
        runCatching { handler.status() }.getOrNull()?.let { append("<p class=m>Ember ${esc(it.version)}</p>") }
    }

    private fun settingForm(auth: String, s: PhoneSettingView): String = buildString {
        val id = esc(s.id)
        val search = esc(listOf(s.group, s.label, s.note.orEmpty(), s.options.joinToString(" ") { it.second }).joinToString(" ").lowercase(java.util.Locale.ROOT))
        append("<form class=s method=post action=\"/setting$auth\" data-q=\"$search\"><input type=hidden name=id value=\"$id\">")
        append("<label for=\"v-$id\">${esc(s.label)}")
        s.note?.let { append("<small>${esc(it)}</small>") }
        append("</label>")
        when {
            s.kind == "toggle" -> append("<input type=hidden name=value value=false><input class=sw id=\"v-$id\" type=checkbox role=switch name=value value=true${if (s.value == "true") " checked" else ""}>")
            // #232: the TV's pictures, five to a row.
            s.kind == "choice" && PhonePictures.has(s.id) -> {
                append("<div class=pics role=radiogroup aria-label=\"${esc(s.label)}\">")
                s.options.forEachIndexed { i, (v, l) ->
                    append("<label><input type=radio name=value value=\"${esc(v)}\"${if (i == 0) " id=\"v-$id\"" else ""}${if (v == s.value) " checked" else ""}>")
                    append("<span>${PhonePictures.of(s.id, v).orEmpty()}<b>${esc(l)}</b></span></label>")
                }
                append("</div>")
            }
            // #232: numbers in steps slide; without scripts the list below the slider does the same.
            s.kind == "choice" && PhonePictures.stepped(s.options) -> {
                val at = s.options.indexOfFirst { it.first == s.value }.coerceAtLeast(0)
                val steps = esc(s.options.joinToString(",", "[", "]") { "\"" + it.first + "\"" })
                val labels = esc(s.options.joinToString(",", "[", "]") { "\"" + it.second.replace("\\", "").replace("\"", "") + "\"" })
                append("<div class=rw><input type=hidden class=hv name=value value=\"${esc(s.options[at].first)}\"><input class=\"rng wj\" id=\"v-$id\" type=range min=0 max=${s.options.lastIndex} step=1 value=$at data-steps=\"$steps\" data-labels=\"$labels\" aria-label=\"${esc(s.label)}\">")
                append("<output class=wj>${esc(s.options[at].second)}</output><noscript><select name=value aria-label=\"${esc(s.label)}\">")
                s.options.forEach { (v, l) -> append("<option value=\"${esc(v)}\"${if (v == s.value) " selected" else ""}>${esc(l)}</option>") }
                append("</select></noscript></div>")
            }
            s.kind == "choice" && PhonePictures.pills(s.options) -> {
                append("<div class=\"picks wide\" role=radiogroup aria-label=\"${esc(s.label)}\">")
                s.options.forEachIndexed { i, (v, l) -> append("<label><input type=radio name=value value=\"${esc(v)}\"${if (i == 0) " id=\"v-$id\"" else ""}${if (v == s.value) " checked" else ""}><span>${esc(l)}</span></label>") }
                append("</div>")
            }
            s.kind == "choice" -> {
                append("<select id=\"v-$id\" name=value>")
                s.options.forEach { (v, l) -> append("<option value=\"${esc(v)}\"${if (v == s.value) " selected" else ""}>${esc(l)}</option>") }
                append("</select>")
            }
            PhonePictures.slides(s.min, s.max) -> append("<div class=rw><input id=\"v-$id\" type=range name=value min=${s.min} max=${s.max} value=\"${esc(s.value)}\" aria-label=\"${esc(s.label)}\"><output class=wj>${esc(s.value)}</output></div>")
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

    /** The page shell sent to the phone: styles, script and [body]. */
    internal fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>
:root{color-scheme:dark;--bg:#111215;--card:#1f2024;--line:#2c2d32;--fg:#e8eaed;--mute:#a9adb4;--accent:$accent}
body{margin:0;font:16px system-ui,sans-serif;background:var(--bg);color:var(--fg);-webkit-text-size-adjust:100%}
main{max-width:760px;margin:auto;padding:16px max(16px,env(safe-area-inset-right)) 32px max(16px,env(safe-area-inset-left))}
h1{font-size:24px;margin:8px 0 2px}h1 i{display:inline-block;width:12px;height:12px;border-radius:50%;background:var(--accent);margin-right:10px}
h2{font-size:19px;margin:0 0 4px}h3{font-size:15px;margin:16px 0 2px}.m{color:var(--mute);font-size:13px;margin:2px 0 8px}
nav{position:sticky;top:0;z-index:2;display:flex;gap:6px;overflow-x:auto;padding:10px 0;background:var(--bg);scrollbar-width:none}
nav a{flex:none;padding:9px 14px;border-radius:999px;background:var(--card);color:var(--fg);text-decoration:none;font-size:14px;font-weight:600}
nav a[aria-current]{background:var(--accent);color:#111}
form,.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin:14px 0}
.cols>form,.cols>.card,.cols>.grp{margin:0 0 16px}
.grp>h3{font-size:17px;margin:0 0 2px}.grp>form{margin:8px 0 0}
#find{margin:6px 0 12px}main>h2{margin:16px 0 6px}
.menu{display:grid;gap:10px;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));margin:12px 0}
.mi{display:flex;align-items:center;gap:12px;padding:15px 16px;border-radius:16px;background:var(--card);border:1px solid var(--line);color:var(--fg);text-decoration:none}
.mi span{flex:1;min-width:0}.mi b{display:block;font-size:16px}.mi small{display:block;color:var(--mute);font-size:13px;margin-top:3px}.mi i{font-style:normal;color:var(--mute);font-size:24px;line-height:1}
.mi:active{background:#2c2d32}
details.card{padding:0;margin:10px 0}details.card>summary{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:15px 16px;font-size:16px;font-weight:600;list-style:none}
details.card>summary::-webkit-details-marker{display:none}details.card>summary span{flex:1;min-width:0}
details.card>summary small{color:var(--mute);font-weight:400;font-size:13px}
details.card>summary:after{content:"\25BE";color:var(--mute)}details.card[open]>summary:after{content:"\25B4"}
details.card>.in{padding:0 16px 14px}details.card>.in>form.flat{margin:0}
details.fold{border-top:0;padding:0}details.fold>summary{color:var(--mute);font-size:13px;padding:6px 0}
.head{display:flex;align-items:baseline;gap:10px}.head h2{flex:1;margin:0 0 6px}
.row{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px;padding:10px 0;border-top:1px solid var(--line)}
.thumb{flex:none;width:52px;height:52px;border-radius:10px;background:var(--bg);display:grid;place-items:center;font-weight:700;font-size:20px;color:var(--mute);overflow:hidden}
.thumb img{width:100%;height:100%;object-fit:cover}
.row .t{flex:1 1 150px;min-width:0}.row .t b,.row .t small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.row .t small{color:var(--mute);font-size:12px;margin-top:3px}
.chip{display:inline-block;padding:1px 7px;border-radius:6px;background:#34353b;color:var(--fg);font-size:11px;font-weight:600;margin-right:6px}
.acts{display:flex;gap:6px;margin-left:auto}.acts form{background:none;border:0;padding:0;margin:0}
.ib{display:grid;place-items:center;flex:none;width:40px;height:40px;margin:0;padding:0;border:0;border-radius:50%;background:#2c2d32;color:var(--fg);font-size:16px;font-weight:600;text-decoration:none}
.ib.danger{background:#3a2222;color:#ffb4ab}.ib:disabled{opacity:.3}
.btn.quiet,button.quiet{background:#2c2d32;color:var(--fg)}
.card.wide{margin:14px 0}.thumb.st-numbered{font-size:26px;color:var(--fg)}
.thumb.wt{width:56px}.wt i{display:block;flex:none;height:36px;border-radius:5px;background:#5b5d63;transform:scale(.62)}
.wj{display:none}#worder.live .wj{display:grid}#worder.live+form{margin-top:10px}
.grip{background:none;color:var(--mute);cursor:grab;touch-action:none;width:28px}.row.drag{background:var(--bg);border-radius:10px;opacity:.85}
.shapes .w-row,.wt .w-row{width:68px;height:36px;border-radius:0;background:repeating-linear-gradient(90deg,#5b5d63 0 14px,transparent 14px 18px)}
.shapes .w-numbered,.wt .w-numbered{width:70px;height:36px;border-radius:0;box-sizing:border-box;padding:16px 0 0 3px;font:900 15px/20px ui-monospace,Menlo,Consolas,monospace;font-style:normal;letter-spacing:15px;color:#fff;text-align:left;white-space:pre;overflow:hidden;background:repeating-linear-gradient(90deg,#5b5d63 0 20px,transparent 20px 24px)}
.shapes .w-numbered::before,.wt .w-numbered::before{content:"123"}
.shapes .w-carousel,.wt .w-carousel{width:68px;height:38px;border-radius:2px;background:linear-gradient(#d5d8dc,#d5d8dc) 50% 76%/26px 4px no-repeat,linear-gradient(#a9adb3,#a9adb3) 50% 90%/12px 2px no-repeat,#5b5d63}
.wt .w-apps{width:68px;height:18px;border-radius:0;background:repeating-linear-gradient(90deg,#8a8d93 0 10px,transparent 10px 13px)}
.shapes .w-hero,.wt .w-hero{width:68px;height:36px;background:linear-gradient(#d5d8dc,#d5d8dc) 8px 14px/26px 4px no-repeat,linear-gradient(#a9adb3,#a9adb3) 8px 21px/18px 3px no-repeat,linear-gradient(#d5d8dc,#d5d8dc) 8px 27px/12px 5px no-repeat,#5b5d63}
.shapes.styles{grid-template-columns:repeat(auto-fit,minmax(140px,1fr))}.shapes small{display:block;margin-top:4px;font-size:11px;line-height:1.35;color:var(--mute)}
.tabs2{display:flex;gap:8px}.tabs2 button{flex:1;margin-top:4px;padding:9px;background:#2c2d32;color:var(--fg);font-size:14px}
button.res{background:#2c2d32;color:var(--fg);text-align:left;border-radius:12px;padding:10px 12px;margin-top:6px;font-size:14px}
button.res small{display:block;color:var(--mute);font-size:12px;font-weight:400;margin-top:2px}
.chosen{display:flex;flex-wrap:wrap;gap:8px}.chosen label{margin:4px 0 0!important;padding:7px 12px;border-radius:999px;border:1px solid var(--accent);background:#2c2d32;color:var(--fg)!important;font-size:13px;cursor:pointer}
.picks{display:flex;flex-wrap:wrap;gap:8px;margin:6px 0}.picks label{margin:0;position:relative}.picks input{position:absolute;opacity:0;width:1px;height:1px}
.picks span{display:block;padding:8px 12px;border-radius:999px;border:1px solid #3a3b40;background:var(--bg);color:var(--mute);font-size:13px}
.picks input:checked+span{border-color:var(--accent);background:#2c2d32;color:var(--fg)}.picks input:focus-visible+span{outline:2px solid var(--accent);outline-offset:2px}
.back{color:var(--fg);text-decoration:none;font-weight:600}
form.ed{max-width:820px}form.ed label{display:block;margin-top:14px;font-size:14px;color:var(--mute)}form.ed h3{font-size:17px;margin-top:22px}
.shapes{display:grid;grid-template-columns:repeat(auto-fit,minmax(96px,1fr));gap:10px}.shapes label{margin:4px 0 0;position:relative}
.shapes input{position:absolute;opacity:0;width:1px;height:1px}
.shapes span{display:block;text-align:center;padding:14px 6px 10px;border-radius:12px;border:1px solid #3a3b40;background:var(--bg);color:var(--mute);font-size:13px}
.shapes i{display:block;margin:0 auto 8px;height:36px;background:#5b5d63;border-radius:5px}.shapes .poster{width:24px}.shapes .wide{width:58px}.shapes .square{width:36px}
.shapes input:checked+span{border-color:var(--accent);background:#2c2d32;color:var(--fg)}.shapes input:checked+span i:not([class^=w-]){background:#d5d8dc}
.shapes input:focus-visible+span{outline:2px solid var(--accent);outline-offset:2px}
form.ed .tick{display:flex;justify-content:space-between;color:var(--fg);font-size:15px;padding:10px 0;margin:0;border-top:1px solid var(--line)}
form.ed .tick.opt{margin-top:16px;border:1px solid var(--line);border-radius:12px;padding:12px}
form.ed .tick small{display:block;color:var(--mute);font-size:12px}.tick .sw{width:52px;flex:none}form.ed .tick span{flex:1;min-width:0}
.cover{display:block;margin-top:8px;width:140px;max-height:140px;object-fit:cover;border-radius:10px;background:var(--bg)}
fieldset{border:1px solid var(--line);border-radius:12px;margin:10px 0;padding:8px 12px}legend{color:var(--mute);font-size:13px}
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
@media(min-width:700px){main{padding-top:24px;padding-bottom:40px}h1{font-size:30px}}
@media(min-width:900px){main{max-width:1120px;display:grid;grid-template-columns:220px minmax(0,1fr);column-gap:36px;align-items:start}
main>h1,main>.intro{grid-column:1/-1}
main>nav{grid-column:1;grid-row:3/span 400;flex-direction:column;gap:6px;top:16px;overflow:visible;padding:8px 0;background:none}
main>nav a{border-radius:12px;padding:12px 14px;font-size:15px}
main>*:not(nav):not(h1):not(.intro){grid-column:2;max-width:800px;width:100%;box-sizing:border-box}}
.pics{grid-column:1/-1;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px;margin:6px 0 2px}
@media(max-width:430px){.pics{grid-template-columns:repeat(3,minmax(0,1fr))}}
.pics label{position:relative;margin:0;min-width:0}.pics input{position:absolute;opacity:0;width:1px;height:1px}
.pics span{display:block;padding:6px;border-radius:12px;border:1px solid #3a3b40;background:var(--bg);text-align:center;cursor:pointer}
.pics svg{display:block;width:100%;height:auto;border-radius:6px}
.pics b{display:block;margin-top:5px;font-size:12px;font-weight:600;color:var(--mute);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pics input:checked+span{border-color:var(--accent);background:#2c2d32}.pics input:checked+span b{color:var(--fg)}
.pics input:focus-visible+span{outline:2px solid var(--accent);outline-offset:2px}
.picks.wide{grid-column:1/-1;margin:6px 0 2px}.picks span{cursor:pointer}
.rw{grid-column:1/-1;display:flex;align-items:center;gap:14px;margin-top:4px}.rw select{width:100%;max-width:none}
input[type=range]{flex:1;width:auto;margin:0;padding:0;border:0;background:none;accent-color:var(--accent);height:28px}
.rw output{flex:none;min-width:72px;text-align:right;font-size:14px;font-weight:600}
.js .rw .wj{display:block}
.svc{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin:12px 0}
.svc details.card{margin:0}.svc details[open]{grid-column:1/-1}
.svc summary{gap:10px!important}.svc summary span b{display:block;font-size:15px}.svc summary span small{display:block;color:var(--mute);font-weight:400;font-size:12px;margin-top:2px}
.svc details.card>summary:after{content:none}
.mk{flex:none;display:grid;place-items:center;width:28px;height:28px;border-radius:50%;font-style:normal;font-size:15px;font-weight:700}
.mk.ok{background:#1e3a2a;color:#8fe3b0}.mk.bad{background:#3a2222;color:#ffb4ab}.mk.off{background:#2c2d32;color:var(--mute)}
.svc em{flex:none;font-style:normal;font-size:11px;font-weight:600;padding:2px 7px;border-radius:6px}.svc em.req{background:#3a2222;color:#ffd9d6}.svc em.opt{background:#2c2d32;color:var(--mute)}
.ch{align-items:center}.cv{flex:none;width:64px;height:36px;border-radius:8px;background:var(--bg);overflow:hidden;display:grid;place-items:center;font-weight:700;color:var(--mute)}.cv img{width:100%;height:100%;object-fit:cover}
.strip{display:inline-flex;align-items:center;gap:5px;margin-right:10px;vertical-align:middle}.strip .pf{height:40px;font-size:14px}.strip small{color:var(--mute);font-size:12px}
details.fold[open]>summary .strip{display:none}details.fold>summary{display:flex;align-items:center;flex-wrap:wrap;gap:6px}
.fgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;margin-top:6px}
.ft{display:flex;flex-direction:column;gap:6px;min-width:0;background:var(--bg);border-radius:12px;padding:8px}
.ft .pic{display:grid;place-items:center;height:96px}.ft .pic .pf{height:100%;max-width:100%;font-size:24px}
.pf{display:grid;place-items:center;box-sizing:border-box;border-radius:7px;background:#2c2d32;overflow:hidden;font-weight:700;color:var(--mute)}.pf img{width:100%;height:100%;object-fit:cover}
.ft .t{min-width:0}.ft .t b,.ft .t small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.ft .t b{font-size:14px}.ft .t small{color:var(--mute);font-size:12px;margin-top:3px}
.ft .acts{margin:0;justify-content:space-between}.ft .ib{width:34px;height:34px;font-size:14px}
${BadgePhone.CSS}
[hidden]{display:none!important}</style></head>
<body><main><h1><i></i>${esc(title)}</h1><p class="m intro">Sent straight to your TV on this network. Nothing goes through the internet.</p>$body</main><script>$SCRIPT</script></body></html>"""

    companion object {
        internal const val MIN_BACKUP_PASSPHRASE = 8
        private const val CRASH_ENTRIES_SHOWN = 10
        /**
         * Instant saves (#49): each change posts its form in the background and shows the TV's answer
         * next to the setting. Without JavaScript the Save buttons stay and do the same.
         */
        private const val SCRIPT = """
document.documentElement.classList.add('js');
// #257: Copy buttons put the text of what they point at on the clipboard.
document.querySelectorAll('[data-copy]').forEach(function(b){b.onclick=function(){
  var t=Array.prototype.map.call(document.querySelectorAll(b.dataset.copy),function(e){return e.textContent}).join('\n\n----\n\n');
  var a=document.createElement('textarea');a.value=t;a.setAttribute('readonly','');a.style.position='fixed';a.style.opacity='0';document.body.appendChild(a);a.select();a.setSelectionRange(0,t.length);
  var ok=false;try{ok=document.execCommand('copy')}catch(e){}document.body.removeChild(a);
  if(!ok&&navigator.clipboard){navigator.clipboard.writeText(t).then(function(){b.textContent='Copied'},function(){b.textContent='Could not copy'});return}
  b.textContent=ok?'Copied':'Could not copy';};});
function steps(g){return g&&g.dataset.steps?JSON.parse(g.dataset.steps):null;}
function label(f){var g=f.querySelector('input[type=range]'),o=f.querySelector('output');if(!g)return;var s=steps(g);
  if(s){var l=JSON.parse(g.dataset.labels);f.querySelector('.hv').value=s[g.value];if(o)o.textContent=l[g.value];}else if(o)o.textContent=g.value;}
function cur(f){var r=f.querySelectorAll('input[type=radio]');if(r.length){var c=f.querySelector('input[type=radio]:checked');return c?c.value:'';}
  var w=f.querySelector('input.sw');if(w)return String(w.checked);var h=f.querySelector('.hv');if(h)return h.value;
  var i=f.querySelector('[name=value]:not([type=hidden])');return i?i.value:'';}
function show(f,v){var r=f.querySelectorAll('input[type=radio]');if(r.length){r.forEach(function(x){x.checked=x.value===v;});return;}
  var w=f.querySelector('input.sw');if(w){w.checked=v==='true';return;}
  var g=f.querySelector('input[type=range]'),s=steps(g);if(s){var k=s.indexOf(v);if(k>=0)g.value=k;label(f);return;}
  var i=f.querySelector('[name=value]:not([type=hidden])');if(i){i.value=v;label(f);}}
document.querySelectorAll('form.s').forEach(function(f){
  var st=f.querySelector('.st'),last=cur(f);
  function send(){
    var d=new URLSearchParams(new FormData(f));d.set('js','1');st.className='st';st.textContent='Saving…';
    fetch(f.action,{method:'POST',body:d}).then(function(r){return r.json();}).then(function(j){
      st.textContent=j.message;st.className='st '+(j.ok?'good':'bad');
      if(j.value!=null){show(f,j.value);last=j.value;}else show(f,last);
    }).catch(function(){st.textContent='Not saved: the TV did not answer. The session may have ended.';st.className='st bad';show(f,last);});
  }
  f.addEventListener('input',function(e){if(e.target.type==='range')label(f);});
  f.addEventListener('change',function(e){if(e.target.type==='range')label(f);if(e.target.name==='value'||e.target.classList.contains('rng'))send();});
  f.addEventListener('submit',function(e){e.preventDefault();send();});
});
var find=document.getElementById('find');
if(find)find.addEventListener('input',function(){
  var q=find.value.trim().toLowerCase(),any=false;
  document.querySelectorAll('.g').forEach(function(g){var n=0;g.querySelectorAll('form.s').forEach(function(f){var hit=!q||f.dataset.q.indexOf(q)>=0;f.hidden=!hit;if(hit)n++;});g.hidden=n===0;if(n)any=true;if(q&&n&&g.tagName==='DETAILS')g.open=true;});
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
