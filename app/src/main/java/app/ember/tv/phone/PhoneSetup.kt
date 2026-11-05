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
                    val admitted = synchronized(this) {
                        if (session !== active || !access.active || !slots.tryAcquire()) false
                        else { active.clients.add(client); true }
                    }
                    if (!admitted) { runCatching { client.close() }; continue }
                    thread(name = "ember-phone-request", isDaemon = true) {
                        try { runCatching { serve(client, active) } }
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
        when {
            method == "POST" && path == "/save" -> respond(c, 200, page("Ember", "<p class=ok>${esc(handle(form, handler, access))}</p>" + forms(access)))
            method == "POST" && path == "/backup" -> {
                // The phone page is plain HTTP on the home network: an unencrypted backup would carry
                // every sign-in and key across it readable. Only encrypted backups leave the TV this way.
                val passphrase = form["passphrase"].orEmpty()
                if (passphrase.length < MIN_BACKUP_PASSPHRASE) respond(c, 200, page("Ember",
                    "<p class=ok>Enter a passphrase of at least $MIN_BACKUP_PASSPHRASE characters. Backups saved to a phone are always encrypted.</p>" + forms(access)))
                else run {
                val bytes = runCatching { handler.exportBackup(passphrase) }.getOrNull()
                if (bytes == null) respond(c, 200, page("Ember", "<p class=ok>Could not make the backup.</p>" + forms(access)))
                else { access.requireActive(); respondFile(c, app.ember.tv.data.Backup.fileName(), bytes) }
                }
            }
            method == "POST" && path == "/restore" -> {
                val file = multipart["file"]
                val msg = if (file == null || file.isEmpty()) "Choose a backup file first."
                else handler.importBackup(file, form["passphrase"].orEmpty(), form["apply"] == "on")
                respond(c, 200, page("Ember", "<p class=ok>${esc(msg)}</p>" + forms(access)))
            }
            method == "POST" && path == "/badges" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                val msg = if (text.isBlank()) "Choose a badge pack file, or paste its JSON." else handler.importBadges(text)
                respond(c, 200, page("Ember", "<p class=ok>${esc(msg)}</p>" + forms(access)))
            }
            method == "POST" && path == "/collections" -> {
                val text = multipart["file"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: form["json"].orEmpty()
                val msg = if (text.isBlank()) "Choose a collections file, or paste its JSON." else runCatching { app.ember.tv.data.CollectionTransfer.open(text, form["passphrase"].orEmpty()) }
                    .fold({ handler.importCollections(it) }, { "Could not unlock the collection pack. Check its passphrase and file." })
                respond(c, 200, page("Ember", "<p class=ok>${esc(msg)}</p>" + forms(access)))
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
                respond(c,200,page("Presentation","<p class=ok>${esc(message)}</p>"+forms(access)))
            }
            method == "POST" && path == "/content" -> {
                val message = handler.proposeContent(form)
                val content = handler.content()?.let { ContentPhone.render(it, access.code) }.orEmpty()
                respond(c, 200, page("Organize content", "<p class=ok>${esc(message)}</p>" + content))
            }
            path == "/content" -> respond(c, 200, page("Organize content", handler.content()?.let { ContentPhone.render(it, access.code) }.orEmpty()))
            path == "/" -> respond(c, 200, page("Ember setup", forms(access)))
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
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Not Found" }
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun respondFile(c: Socket, name: String, bytes: ByteArray) {
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
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

    private fun forms(access: PhoneSetupAccess): String = buildString {
        access.requireActive()
        val auth = "?k=${access.code}"
        append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=addon>")
        append("<h2>Add addons</h2><p class=m>Stremio-compatible manifest links ending in manifest.json, one per line.</p>")
        append("<textarea name=url rows=3 placeholder=\"https://…/manifest.json\"></textarea><button>Add</button></form>")

        append("<form method=post action=\"/save$auth\"><input type=hidden name=action value=iptv>")
        append("<h2>Add IPTV</h2><p class=m>An M3U playlist link, or Xtream Codes server, username and password.</p>")
        append("<select name=kind><option value=m3u>M3U playlist</option><option value=xtream>Xtream Codes</option></select>")
        append("<input name=name placeholder=\"Name (optional)\"><input name=url placeholder=\"Playlist link or server address\">")
        append("<input name=username placeholder=\"Username (Xtream)\" autocomplete=off><input name=password type=password placeholder=\"Password (Xtream)\" autocomplete=off>")
        append("<input name=epg placeholder=\"Guide (XMLTV) link, optional\"><button>Add</button></form>")

        append("<form method=post action=\"/presentation$auth\" enctype=multipart/form-data><h2>Presentation playlist</h2><p>Images and short videos up to 8 MB. Approve each addition on the TV. For larger videos, choose a file on the TV.</p><input name=name required placeholder=\"File name including extension, e.g. photo.jpg or clip.mp4\"><input name=file type=file accept=\"image/*,video/*\" required><button>Send for TV approval</button></form>")
        append("<form method=post action=\"/backup$auth\">")
        append("""<p><a href="/content$auth">Create collections, edit folders and pause add-ons</a></p>""")
        append("<h2>Save a backup to this phone</h2><p class=m>Everything on the TV: settings, addons, sign-ins and keys. The file is encrypted with your passphrase; you need it to restore.</p>")
        append("<input name=passphrase type=password required minlength=$MIN_BACKUP_PASSPHRASE placeholder=\"Passphrase (at least $MIN_BACKUP_PASSPHRASE characters)\" autocomplete=off><button>Download backup</button></form>")

        append("<form method=post action=\"/restore$auth\" enctype=multipart/form-data>")
        append("<h2>Restore a backup on the TV</h2><p class=m>Pick an Ember backup file. Leave the box unticked to check it first; tick it to replace everything on the TV, which then restarts Ember.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><input name=passphrase type=password placeholder=\"Passphrase, if it has one\" autocomplete=off>")
        append("<label style=\"display:flex;gap:10px;align-items:center;margin:8px 0\"><input type=checkbox name=apply style=\"width:auto\"> Replace the TV's settings with this backup</label>")
        append("<button>Check or restore</button></form>")

        append("<form method=post action=\"/badges$auth\" enctype=multipart/form-data>")
        append("<h2>Source badges</h2><p class=m>A badge pack (JSON) for Settings &gt; Stremio &gt; Badges. Pick the file, or paste its contents.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the JSON here\"></textarea><button>Use these badges</button></form>")

        append("<form method=post action=\"/collections$auth\" enctype=multipart/form-data>")
        append("<h2>Collections</h2><p class=m>A collections export (JSON) for Settings &gt; Stremio &gt; Collections. Pick the file, or paste its contents.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the JSON here\"></textarea><button>Add collections</button><input type=password name=passphrase autocomplete=off placeholder='Passphrase for encrypted Ember collection packs'></form>")

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

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>
body{margin:0;font:16px system-ui,sans-serif;background:#111215;color:#e8eaed}main{max-width:640px;margin:auto;padding:20px}
h1{font-size:26px;margin:8px 0 2px}h1 i{display:inline-block;width:12px;height:12px;border-radius:50%;background:$accent;margin-right:10px}
h2{font-size:19px;margin:0 0 4px}h3{font-size:15px;margin:16px 0 2px}.m{color:#a9adb4;font-size:13px;margin:2px 0 8px}
form{background:#1f2024;border-radius:16px;padding:16px;margin:16px 0}input,select,textarea{box-sizing:border-box;width:100%;margin:4px 0;
padding:12px;border-radius:10px;border:1px solid #3a3b40;background:#111215;color:#e8eaed;font-size:16px}
button{margin-top:10px;width:100%;padding:13px;border:0;border-radius:999px;background:#f1f3f4;color:#1f1f1f;font-size:16px;font-weight:600}
.ok{background:#1e3a2a;color:#b9f0cf;padding:12px;border-radius:10px}</style></head>
<body><main><h1><i></i>${esc(title)}</h1><p class=m>Sent straight to your TV on this network. Nothing goes through the internet.</p>$body</main></body></html>"""

    companion object {
        internal const val MIN_BACKUP_PASSPHRASE = 8
        private const val HEADER_TIMEOUT_MILLIS = 4_000
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
