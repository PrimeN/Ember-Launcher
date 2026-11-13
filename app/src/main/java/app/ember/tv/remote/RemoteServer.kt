package app.ember.tv.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * #241: the phone remote's web server. Unlike Phone setup it has no time limit: it runs while the
 * remote is switched on. It only answers requests addressed to the TV's own address (no other host
 * name, which stops a web page from reaching it through DNS tricks), only lets in phones that signed
 * in when a password is set, and only accepts changes that carry the page's own header, which other
 * web pages cannot send without the TV agreeing (and it never does).
 */
class RemoteServer(
    private val auth: RemoteAuth,
    /** Answers "/api/[name]" with JSON, or null for an unknown name. */
    private val api: (name: String, params: Map<String, String>) -> String?,
    private val tvName: () -> String = { "TV" },
    private val accent: () -> String = { "#FF7A2F" },
    private val address: () -> String? = { app.ember.tv.phone.PhoneSetup.localAddress() },
    private val port: Int = DEFAULT_PORT,
) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile var url: String? = null; private set
    private val workers = Executors.newFixedThreadPool(3) { r -> Thread(r, "ember-remote").apply { isDaemon = true } }
    private val slots = Semaphore(6)

    val running: Boolean get() = socket?.isClosed == false

    @Synchronized fun start(): String? {
        if (running) return url
        val ip = address() ?: return null
        // A fixed port, so a bookmark keeps working; another one only when it is taken.
        val s = runCatching { ServerSocket(port) }.getOrElse { ServerSocket(0) }
        socket = s
        url = "http://$ip:${s.localPort}/"
        thread(name = "ember-remote-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
                if (!slots.tryAcquire()) { runCatching { client.close() }; continue }
                workers.execute { try { client.use { serve(it) } } catch (_: Exception) { } finally { slots.release() } }
            }
        }
        return url
    }

    @Synchronized fun stop() { runCatching { socket?.close() }; socket = null; url = null }

    internal class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String) {
        fun cookie(name: String): String? = headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    private fun serve(c: Socket) {
        c.soTimeout = 10_000
        val request = read(BufferedInputStream(c.getInputStream())) ?: return respond(c, 400, "text/plain", "Bad request")
        val (status, type, body, extra) = route(request)
        respond(c, status, type, body, extra)
    }

    /** One answer: status, media type, body and extra headers. */
    internal data class Answer(val status: Int, val type: String, val body: String, val headers: List<String> = emptyList())

    internal fun route(r: Request): Answer {
        if (!hostAllowed(r.headers["host"])) return Answer(421, "text/plain", "Open the remote with the address shown on the TV.")
        val token = r.cookie(COOKIE)
        if (r.method == "GET" && r.path == "/") return Answer(200, HTML, if (auth.allows(token)) RemotePage.app(tvName(), accent()) else RemotePage.login(tvName(), accent()))
        if (r.method == "GET" && r.path == "/favicon.ico") return Answer(404, "text/plain", "")
        if (r.method != "POST" && r.method != "GET") return Answer(405, "text/plain", "")
        // Changes must come from the remote page itself.
        if (r.method == "POST" && r.headers[HEADER] != "1") return Answer(403, JSON, error("Open the remote page to do that."))
        if (r.path == "/login" && r.method == "POST") {
            if (!auth.hasPassword) return Answer(200, JSON, """{"ok":true}""")
            if (auth.locked()) return Answer(429, JSON, error("Too many wrong passwords. Wait a minute and try again."))
            val password = form(r.body)["password"].orEmpty()
            if (!auth.check(password)) return Answer(401, JSON, error(if (auth.locked()) "Too many wrong passwords. Wait a minute and try again." else "That password is not right."))
            val issued = auth.issue()
            return Answer(200, JSON, """{"ok":true}""", listOf("Set-Cookie: $COOKIE=$issued; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict"))
        }
        if (!r.path.startsWith("/api/")) return Answer(404, "text/plain", "Not found")
        if (!auth.allows(token)) return Answer(401, JSON, error("Sign in again."))
        if (r.path == "/api/logout" && r.method == "POST") {
            auth.revoke(token)
            return Answer(200, JSON, """{"ok":true}""", listOf("Set-Cookie: $COOKIE=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"))
        }
        val name = r.path.removePrefix("/api/")
        // Reading is a GET; anything that changes the TV is a POST.
        if (r.method == "GET" && name !in READS) return Answer(405, JSON, error("Use the remote page."))
        val answer = runCatching { api(name, r.query + form(r.body)) }.getOrElse { return Answer(500, JSON, error(it.message ?: "The TV could not do that.")) }
            ?: return Answer(404, JSON, error("Unknown request."))
        return Answer(200, JSON, answer)
    }

    /** The TV's own address (any IPv4 address, or localhost), never a name another site could point here. */
    internal fun hostAllowed(host: String?): Boolean {
        val h = host?.substringBeforeLast(':')?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return false
        return h == "localhost" || h == "127.0.0.1" || IPV4.matches(h)
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read(); if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3) return null
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return null
        val body = ByteArray(length)
        var at = 0
        while (at < length) { val n = input.read(body, at, length - at); if (n < 0) return null; at += n }
        val target = parts[1]
        return Request(parts[0], target.substringBefore('?'), form(target.substringAfter('?', "")), headers, String(body, Charsets.UTF_8))
    }

    private fun respond(c: Socket, status: Int, type: String, body: String, extra: List<String> = emptyList()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 421 -> "Misdirected Request"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1)); out.write(bytes); out.flush()
    }

    companion object {
        const val DEFAULT_PORT = 8642
        const val COOKIE = "ember_remote"
        /** Sent by the remote page with every change; a request from another site cannot carry it. */
        const val HEADER = "x-ember-remote"
        const val HTML = "text/html"
        const val JSON = "application/json"
        private const val MAX_BODY = 32_768
        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
        /** Requests that only read. */
        val READS = setOf("state", "search", "lists", "details")

        internal fun form(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }.toMap()

        internal fun error(message: String) = kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.JsonPrimitive(false)); put("error", kotlinx.serialization.json.JsonPrimitive(message))
        }.toString()
    }
}
