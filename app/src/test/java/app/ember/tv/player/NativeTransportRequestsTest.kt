package app.ember.tv.player

import android.app.Application
import app.ember.tv.data.Net
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.*
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class NativeTransportRequestsTest {
    private fun connection(url:String,range:String?=null,head:Boolean=false):HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=3000;readTimeout=3000
            if(range!=null)setRequestProperty("Range",range)
            if(head)requestMethod="HEAD"
        }
    @Test fun eachNewSegmentAndRangeUsesOneGetEvenWhenHeadIsRejected() {
        Net.init(RuntimeEnvironment.getApplication())
        Origin().use {origin->NativeHttpTransport(emptyMap(),listOf(origin.url)).use {transport->
            for(n in 1..3)assertEquals("0123456789",URL(transport.expose("${origin.url}/part$n.ts?signature=$n")).readText())
            val media=transport.expose("${origin.url}/part1.ts?signature=1")
            val partial=connection(media,"bytes=2-4")
            assertEquals(206,partial.responseCode);assertEquals("bytes 2-4/10",partial.getHeaderField("Content-Range"))
            assertEquals("234",partial.inputStream.bufferedReader().readText());partial.disconnect()
            val suffix=connection(media,"bytes=-2")
            assertEquals(206,suffix.responseCode);assertEquals("89",suffix.inputStream.bufferedReader().readText());suffix.disconnect()
            assertEquals(5,origin.requests.size)
            assertTrue(origin.requests.all {it.startsWith("GET ")})
            val head=connection(media,head=true)
            assertEquals(200,head.responseCode);assertEquals(10,head.contentLength);head.disconnect()
            assertEquals(5,origin.requests.size)
        }}
    }
    @Test fun cachedMetadataKeepsSignedQueryVariantsSeparate() {
        Net.init(RuntimeEnvironment.getApplication())
        Origin().use {origin->NativeHttpTransport(emptyMap(),listOf(origin.url)).use {transport->
            val first=transport.expose("${origin.url}/video?size=short")
            val second=transport.expose("${origin.url}/video?size=long")
            assertEquals("abc",URL(first).readText());assertEquals("0123456789",URL(second).readText())
            for((url,size) in listOf(first to 3,second to 10)) {
                val head=connection(url,head=true);assertEquals(200,head.responseCode);assertEquals(size,head.contentLength);head.disconnect()
            }
            assertEquals(2,origin.requests.size)
        }}
    }
    @Test fun extensionlessLivePlaylistsUseTheirFetchedBodyAndRefresh() {
        Net.init(RuntimeEnvironment.getApplication())
        Origin().use {origin->NativeHttpTransport(emptyMap(),listOf(origin.url)).use {transport->
            val url=transport.expose("${origin.url}/opaque")
            val first=URL(url).readText();val second=URL(url).readText()
            assertTrue(first.startsWith("#EXTM3U"));assertNotEquals(first,second)
            assertEquals(listOf("GET /opaque","GET /opaque"),origin.requests.toList())
        }}
    }
    @Test fun extensionlessPlaylistsApplyRangesToTheCompleteRewrittenManifest() {
        Net.init(RuntimeEnvironment.getApplication())
        Origin().use {origin->NativeHttpTransport(emptyMap(),listOf(origin.url)).use {transport->
            val url=transport.expose("${origin.url}/opaque")
            val full=URL(url).readBytes()
            val prefix=connection(url,"bytes=0-6")
            try {
                assertEquals(206,prefix.responseCode)
                assertEquals("bytes 0-6/${full.size}",prefix.getHeaderField("Content-Range"))
                assertEquals("#EXTM3U",prefix.inputStream.bufferedReader().readText())
            }finally {prefix.disconnect()}
            val suffix=connection(url,"bytes=-5")
            try {
                assertEquals(206,suffix.responseCode)
                assertEquals("bytes ${full.size-5}-${full.size-1}/${full.size}",suffix.getHeaderField("Content-Range"))
                assertEquals("t.ts\n",suffix.inputStream.bufferedReader().readText())
            }finally {suffix.disconnect()}
        }}
    }
    internal class Origin:Closeable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val workers=Executors.newCachedThreadPool {r->Thread(r,"Origin fixture").apply {isDaemon=true}}
        private val revision=AtomicInteger()
        val requests:MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile private var closed=false
        val url="http://127.0.0.1:${socket.localPort}"
        init {workers.execute {while(!closed)try {val client=socket.accept();workers.execute {runCatching {serve(client)}}}catch(_:Exception){}}}
        private fun serve(client:Socket)=client.use {
            val input=it.getInputStream().bufferedReader();val start=input.readLine().split(' ')
            val headers=mutableMapOf<String,String>()
            while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
            requests+="${start[0]} ${start[1]}"
            if(start[0]=="HEAD") {
                it.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return@use
            }
            val manifest=start[1] in listOf("/opaque","/live.m3u8")
            val text=if(manifest)"#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:${revision.incrementAndGet()}\n#EXTINF:5,\npart.ts\n" else if(start[1].contains("size=short"))"abc" else "0123456789"
            val bytes=text.toByteArray();val range=ByteRange.parse(headers["range"],bytes.size.toLong())!!
            val part=bytes.copyOfRange(range.start.toInt(),range.end.toInt()+1);val partial=headers["range"]!=null
            val response="HTTP/1.1 ${if(partial)"206 Partial Content" else "200 OK"}\r\nContent-Type: ${if(manifest)"application/vnd.apple.mpegurl" else "video/mp2t"}\r\nContent-Length: ${part.size}\r\nConnection: close\r\n"+(if(partial)"Content-Range: bytes ${range.start}-${range.end}/${bytes.size}\r\n" else "")+"\r\n"
            it.getOutputStream().apply {write(response.toByteArray());write(part);flush()}
        }
        override fun close(){closed=true;socket.close();workers.shutdownNow()}
    }
}
