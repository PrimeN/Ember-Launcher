package app.ember.tv.system

import android.content.Context
import app.ember.tv.phone.PhoneSetup
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The phone page for pairing (#408). Google TV closes "Pair device with pairing code" as soon as
 * another app opens, so the code can't be typed into Ember on the TV; the phone sends it instead
 * while the TV keeps showing the code. Served on the home network only while the pairing window
 * is open (at most 10 minutes), and every request needs the one-time token in the link.
 */
internal object AdbPairPage {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""
    @Volatile private var openedAt: Long = 0L

    /** Starts the page; returns its link for the QR code, or null without a home network. */
    @Synchronized
    fun start(context: Context): String? {
        stop()
        val address = PhoneSetup.localAddress() ?: return null
        val app = context.applicationContext
        val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        openedAt = android.os.SystemClock.elapsedRealtime()
        server = socket
        thread(name = "ember-pair-page", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (android.os.SystemClock.elapsedRealtime() - openedAt > LIFETIME_MILLIS) {
                    runCatching { client.close() }
                    stop()
                    break
                }
                thread(name = "ember-pair-request", isDaemon = true) { serve(app, client) }
            }
        }
        return "http://$address:${socket.localPort}/pair/$token"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        token = ""
    }

    private fun serve(context: Context, client: Socket) = runCatching {
        client.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val head = readHead(input) ?: return@use
            val lines = head.split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 2) return@use
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val expected = "/pair/$token"
            if (token.isEmpty() || path != expected) {
                respond(s, 404, page("Not found", "<p class=m>This link has expired. Open the pairing screen in Ember on the TV again.</p>"))
                return@use
            }
            when (method) {
                "GET" -> respond(s, 200, form(null))
                "POST" -> {
                    val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    if (length !in 0..MAX_BODY) {
                        respond(s, 413, form("That was too long."))
                        return@use
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val fields = String(body, 0, read, Charsets.UTF_8).split('&').mapNotNull {
                        val k = it.substringBefore('=')
                        if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }.toMap()
                    val code = fields["code"].orEmpty()
                    val message = runBlocking { SystemAccess.pair(context, code) }
                    val done = SystemAccess.state.value == SystemAccess.State.CONNECTED || message.startsWith("Paired")
                    respond(s, 200, if (done) page("Paired", "<p>${esc(message)}</p><p class=m>You can close this page.</p>") else form(message))
                    if (done) stop()
                }
                else -> respond(s, 405, form(null))
            }
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var last = 0
        while (out.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) return out.toString("UTF-8")
        }
        return null
    }

    private fun respond(s: Socket, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Payload Too Large" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    private fun form(message: String?) = page(
        "Pair Ember",
        (message?.let { "<p class=r>${esc(it)}</p>" } ?: "") +
            "<p class=m>On the TV, open Developer options › Wireless debugging › Pair device with pairing code and leave it open. " +
            "Type the 6-digit code it shows.</p>" +
            "<form method=post><input name=code inputmode=numeric autocomplete=one-time-code maxlength=6 pattern=\"[0-9]{6}\" " +
            "placeholder=\"6-digit code\" required autofocus><button>Pair</button></form>",
    )

    internal fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>
:root{color-scheme:dark}body{margin:0;font:16px system-ui,sans-serif;background:#111215;color:#e8eaed}
main{max-width:520px;margin:auto;padding:24px 16px}h1{font-size:24px;margin:8px 0 12px}
.m{color:#a9adb4;font-size:14px}.r{color:#ffb4ab}form{display:flex;gap:10px;margin-top:16px}
input{flex:1;min-width:0;font:24px system-ui;letter-spacing:4px;padding:12px;border-radius:12px;border:1px solid #2c2d32;background:#1f2024;color:#e8eaed}
button{font:600 16px system-ui;padding:0 20px;border:0;border-radius:12px;background:#FF7A2F;color:#111}
</style></head><body><main><h1>${esc(title)}</h1>$body</main></body></html>"""

    internal fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val LIFETIME_MILLIS = 10 * 60_000L
    private const val MAX_HEAD = 8 * 1024
    private const val MAX_BODY = 256
}
