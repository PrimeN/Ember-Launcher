package app.ember.tv

import app.ember.tv.remote.RemoteAuth
import app.ember.tv.remote.RemoteServer
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class RemoteServerLifecycleTest {
    private class MemoryStore : RemoteAuth.Store {
        private val values = hashMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    private fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "ember-remote" }.toSet()
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(condition())
    }

    @Test fun stopClosesAcceptedRequestsAndTerminatesWorkers() {
        val calls = AtomicInteger()
        val before = workers()
        val server = RemoteServer(RemoteAuth(MemoryStore(), iterations = 1000), { _, _ -> calls.incrementAndGet(); "{}" }, address = { "127.0.0.1" }, port = 0)
        val uri = URI(server.start()!!)
        val clients = (1..6).map { Socket("127.0.0.1", uri.port).apply { soTimeout = 2000 } }
        try {
            // All workers wait for the rest of a body; the other accepted connections are queued.
            clients.forEach { c ->
                c.getOutputStream().apply { write("GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nContent-Length: 2\r\n\r\nx".toByteArray()); flush() }
            }
            await { (workers() - before).size == 3 }
            val started = workers() - before
            server.stop()
            assertFalse(server.running); assertNull(server.url)
            clients.forEach { c ->
                val reply = runCatching {
                    c.getOutputStream().apply { write('y'.code); flush() }
                    c.getInputStream().bufferedReader().readText()
                }.getOrDefault("")
                assertFalse("No queued request may be served after shutdown", reply.startsWith("HTTP/1.1 200"))
            }
            await { started.none { it.isAlive } }
            assertEquals(0, calls.get())
        } finally { clients.forEach { it.close() }; server.stop() }
    }

    @Test fun repeatedStopsAndRestartsUseFreshWorkersAndConnectionSlots() {
        val server = RemoteServer(RemoteAuth(MemoryStore(), iterations = 1000), { _, _ -> "{\"ok\":true}" }, address = { "127.0.0.1" }, port = 0)
        try {
            repeat(4) {
                val before = workers()
                val uri = URI(server.start()!!)
                Socket("127.0.0.1", uri.port).use { c ->
                    c.soTimeout = 2000
                    c.getOutputStream().apply { write("GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\n\r\n".toByteArray()); flush() }
                    assertTrue(c.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
                }
                val started = workers() - before
                assertTrue(started.isNotEmpty())
                server.stop(); server.stop()
                await { started.none { it.isAlive } }
            }
        } finally { server.stop() }
    }
}
