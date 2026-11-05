package app.ember.tv.mediaserver

import android.content.Context
import android.net.wifi.WifiManager
import app.ember.tv.tracker.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A server that answered on the local network. [kind] is "plex", "jellyfin" or "emby". */
data class FoundServer(val kind: String, val name: String, val url: String)

/**
 * Finds media servers on the home network:
 * Jellyfin and Emby answer "who is JellyfinServer?" / "who is EmbyServer?" on UDP 7359,
 * Plex answers a GDM "M-SEARCH" on UDP 32414.
 */
object LanDiscovery {
    private const val FAMILY_PORT = 7359
    private const val GDM_PORT = 32414
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(context: Context, listenMs: Long = 2500): List<FoundServer> = withContext(Dispatchers.IO) {
        // Some TVs drop broadcast replies unless a multicast lock is held.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("ember-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            coroutineScope {
                val family = async { runCatching { family(listenMs / 2) }.getOrDefault(emptyList()) }
                val plex = async { runCatching { plexGdm(listenMs) }.getOrDefault(emptyList()) }
                (family.await() + plex.await()).distinctBy { it.url }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** 255.255.255.255 plus each network's own broadcast address. */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        return out.distinct()
    }

    /** Jellyfin first, then Emby, so a server that answers both is labelled once. */
    private fun family(listenMs: Long): List<FoundServer> {
        val found = LinkedHashMap<String, FoundServer>()
        for ((message, kind) in listOf("who is JellyfinServer?" to "jellyfin", "who is EmbyServer?" to "emby")) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val bytes = message.toByteArray()
                for (addr in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, addr, FAMILY_PORT)) }
                }
                receiveUntil(socket, listenMs) { text, _ ->
                    val reply = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return@receiveUntil
                    val url = reply.str("Address")?.trimEnd('/') ?: return@receiveUntil
                    if (url !in found) found[url] = FoundServer(kind, reply.str("Name") ?: kind, url)
                }
            }
        }
        return found.values.toList()
    }

    private fun plexGdm(listenMs: Long): List<FoundServer> {
        val found = ArrayList<FoundServer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 400
            val bytes = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray()
            for (addr in listOf(InetAddress.getByName("239.0.0.250")) + broadcastAddresses()) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, addr, GDM_PORT)) }
            }
            receiveUntil(socket, listenMs) { text, sender ->
                if (!text.contains("plex/media-server", ignoreCase = true)) return@receiveUntil
                val headers = text.lines().mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i > 0) line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim() else null
                }.toMap()
                val port = headers["port"] ?: "32400"
                found += FoundServer("plex", headers["name"] ?: "Plex", "http://${sender.hostAddress}:$port")
            }
        }
        return found
    }

    private fun receiveUntil(socket: DatagramSocket, listenMs: Long, onReply: (String, InetAddress) -> Unit) {
        val deadline = System.currentTimeMillis() + listenMs
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
                onReply(String(packet.data, 0, packet.length), packet.address)
            } catch (e: SocketTimeoutException) {
                // keep listening until the deadline
            }
        }
    }
}
