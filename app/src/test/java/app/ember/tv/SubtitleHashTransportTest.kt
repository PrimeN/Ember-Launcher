package app.ember.tv

import app.ember.tv.data.Subtitles
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SubtitleHashTransportTest {
    private class Server(val reply:(Socket,Long,Long)->Unit):AutoCloseable {
        val listener=ServerSocket(0)
        private val clients=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        init {thread(isDaemon=true) {
            while(!listener.isClosed) {
                val c=try{listener.accept()}catch(_:Exception){break};clients.add(c)
                thread(isDaemon=true) {c.use {runCatching {
                    val input=c.getInputStream().bufferedReader();input.readLine();var range=""
                    while(true){val h=input.readLine()?:break;if(h.isEmpty())break;if(h.startsWith("Range:",true))range=h.substringAfter("bytes=")}
                    val from=range.substringBefore('-').toLong();val to=range.substringAfter('-').toLong()
                    reply(c,from,to)
                }};clients.remove(c)}
            }
        }}
        val url get()="http://127.0.0.1:${listener.localPort}/film.mp4"
        override fun close(){listener.close();clients.forEach {runCatching {it.close()}}}
    }
    private fun response(c:Socket,from:Long,to:Long,body:ByteArray,length:Boolean=true) {
        c.getOutputStream().apply {
            write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/131072\r\n"+
                (if(length)"Content-Length: ${body.size}\r\n" else "")+"Connection: close\r\n\r\n").toByteArray())
            write(body);flush()
        }
    }
    @Test fun exactRangesStillProduceTheExpectedHash()=runBlocking {
        Server {c,from,to->response(c,from,to,ByteArray(65536))}.use {s->
            assertEquals("0000000000020000" to 131072L,Subtitles.movieHash(s.url))
        }
    }
    @Test fun oversizedKnownAndUnknownLengthResponsesAreRejected()=runBlocking {
        for(known in listOf(true,false))Server {c,from,to->response(c,from,to,ByteArray(65537),known)}.use {s->
            assertNull(Subtitles.movieHash(s.url))
        }
    }
    @Test fun truncatedOrIncorrectlyPositionedRangesAreRejected()=runBlocking {
        Server {c,from,to->response(c,from,to,ByteArray(10))}.use {assertNull(Subtitles.movieHash(it.url))}
        Server {c,_,to->response(c,1,to,ByteArray(65536))}.use {assertNull(Subtitles.movieHash(it.url))}
    }
    @Test fun cancellationClosesAStalledRangeRead()=runBlocking {
        val headers=CountDownLatch(1);val release=CountDownLatch(1)
        Server {c,from,to->
            c.getOutputStream().apply {write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/131072\r\nContent-Length: 65536\r\n\r\n".toByteArray());flush()}
            headers.countDown();release.await(3,TimeUnit.SECONDS)
        }.use {s->
            val job=async(Dispatchers.IO){Subtitles.movieHash(s.url)}
            try {assertTrue(headers.await(2,TimeUnit.SECONDS));withTimeout(2000){job.cancelAndJoin()};assertTrue(job.isCancelled)}
            finally {release.countDown();job.cancel()}
        }
    }
}
