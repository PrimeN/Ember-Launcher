package app.ember.tv.player

import android.app.Application
import app.ember.tv.data.Net
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MkvChapterSourcesTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    @Test fun aLocalFileLoadsChaptersBeyondTheInitialRead()=runBlocking {
        val file=File(app.cacheDir,"chapters.mkv").apply {writeBytes(movie())}
        assertEquals(listOf("Opening" to 0L,"Act two" to 90_000L),MkvChapters.read(app,file.toURI().toString()).map {it.name to it.startMs})
    }
    @Test fun everyRangeCarriesTheStreamsHeadersAndItsOwnBoundedRange()=runBlocking {
        Net.init(app)
        Origin(movie()).use {origin->
            val found=MkvChapters.read(app,origin.url+"/movie.mkv",mapOf("Authorization" to "Bearer chapter-test","Cookie" to "session=chapter-test","Referer" to "https://example.test/","User-Agent" to "chapter-agent","Range" to "bytes=999-"))
            assertEquals(2,found.size)
            assertEquals(2,origin.requests.size)
            for(headers in origin.requests) {
                assertEquals("Bearer chapter-test",headers["Authorization"])
                assertEquals("session=chapter-test",headers["Cookie"])
                assertEquals("https://example.test/",headers["Referer"])
                assertEquals("chapter-agent",headers["User-Agent"])
                assertNotEquals("bytes=999-",headers["Range"])
            }
            assertEquals("bytes=0-131071",origin.requests.first()["Range"])
        }
    }
    @Test fun crossOriginRedirectsKeepTheUserAgentAndStripCredentials()=runBlocking {
        Net.init(app)
        Origin(movie()).use {target->Origin(movie(),redirect=target.url+"/movie.mkv").use {redirect->
            assertEquals(2,MkvChapters.read(app,redirect.url+"/movie.mkv",mapOf("Authorization" to "Bearer chapter-test","Cookie" to "secret=chapter-test","User-Agent" to "chapter-agent")).size)
            assertTrue(target.requests.size>=2)
            for(headers in target.requests) {
                assertNull(headers["Authorization"]);assertNull(headers["Cookie"])
                assertEquals("chapter-agent",headers["User-Agent"])
            }
        }}
    }
    @Test fun invalidRangeResponsesDoNotCreateMisplacedChapters()=runBlocking {
        Origin(movie(),wrongRange=true).use {assertTrue(MkvChapters.read(app,it.url+"/movie.mkv").isEmpty())}
    }
    @Test fun cancellationClosesAStalledRangeRequest()=runBlocking {
        Origin(movie(),stall=true).use {origin->
            val load=async(Dispatchers.Default){MkvChapters.read(app,origin.url+"/movie.mkv")}
            try {
                assertTrue(withContext(Dispatchers.IO){origin.arrived.await(5,TimeUnit.SECONDS)})
                withTimeout(3000){load.cancelAndJoin()}
                assertTrue(load.isCancelled)
            } finally {origin.release.countDown();load.cancel()}
        }
    }
    private class Origin(private val bytes:ByteArray,private val redirect:String?=null,private val wrongRange:Boolean=false,private val stall:Boolean=false):Closeable {
        val requests=CopyOnWriteArrayList<Map<String,String?>>()
        val arrived=CountDownLatch(1);val release=CountDownLatch(1)
        private val executor=Executors.newCachedThreadPool {r->Thread(r,"chapter-origin").apply {isDaemon=true}}
        private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            this.executor=this@Origin.executor
            createContext("/") {exchange->
                try {
                    requests+=listOf("Range","User-Agent","Authorization","Cookie","Referer").associateWith {exchange.requestHeaders.getFirst(it)}
                    if(redirect!=null){exchange.responseHeaders.add("Location",redirect);exchange.sendResponseHeaders(302,-1)}
                    else {
                        val values=Regex("bytes=(\\d+)-(\\d+)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!.groupValues
                        val start=values[1].toInt();val end=minOf(values[2].toInt(),bytes.lastIndex)
                        exchange.responseHeaders.add("Content-Range","bytes ${if(wrongRange)start+1 else start}-$end/${bytes.size}")
                        exchange.sendResponseHeaders(206,(end-start+1).toLong())
                        arrived.countDown()
                        if(stall)release.await(10,TimeUnit.SECONDS)
                        exchange.responseBody.write(bytes,start,end-start+1)
                    }
                } catch(_:Exception){}finally {exchange.close()}
            }
            start()
        }
        val url get()="http://127.0.0.1:${server.address.port}"
        override fun close(){release.countDown();server.stop(0);executor.shutdownNow()}
    }
    private fun element(id:Long,data:ByteArray):ByteArray {
        val out=ByteArrayOutputStream()
        generateSequence(id){it shr 8}.takeWhile {it>0}.map {(it and 255).toByte()}.toList().asReversed().forEach {out.write(it.toInt())}
        out.write(1);for(i in 6 downTo 0)out.write(((data.size.toLong() shr (8*i)) and 255).toInt())
        out.write(data);return out.toByteArray()
    }
    private fun number(id:Long,value:Long)=element(id,ByteArray(8){((value shr (8*(7-it))) and 255).toByte()})
    private fun movie():ByteArray {
        fun chapter(at:Long,name:String)=element(0xb6,number(0x91,at)+element(0x80,element(0x85,name.toByteArray())))
        val chapters=element(0x1043a770,element(0x45b9,chapter(0,"Opening")+chapter(90_000_000_000,"Act two")))
        val seek=element(0x114d9b74,element(0x4dbb,element(0x53ab,byteArrayOf(0x10,0x43,0xa7.toByte(),0x70))+number(0x53ac,180_000)))
        val header=element(0x1a45dfa3,element(0x4282,"matroska".toByteArray()))
        return header+byteArrayOf(0x18,0x53,0x80.toByte(),0x67,0x01,-1,-1,-1,-1,-1,-1,-1)+seek+ByteArray(180_000-seek.size)+chapters
    }
}
