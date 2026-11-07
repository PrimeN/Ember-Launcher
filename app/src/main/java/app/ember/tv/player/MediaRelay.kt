package app.ember.tv.player

import app.ember.tv.data.toHex
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.*

internal interface RemoteMedia {
    val length:Long
    fun open(offset:Long):InputStream
}
internal data class ByteRange(val start:Long,val end:Long) {
    companion object {
        fun parse(value:String?,size:Long):ByteRange? {
            if(size<=0)return null
            if(value==null)return ByteRange(0,size-1)
            val match=Regex("bytes=(\\d*)-(\\d*)").matchEntire(value.trim())?:return null
            val first=match.groupValues[1];val last=match.groupValues[2]
            if(first.isEmpty()) {
                val suffix=last.toLongOrNull()?.takeIf {it>0}?:return null
                return ByteRange((size-suffix).coerceAtLeast(0),size-1)
            }
            val from=first.toLongOrNull()?.takeIf {it in 0 until size}?:return null
            val to=if(last.isEmpty())size-1 else last.toLongOrNull()?.coerceAtMost(size-1)?:return null
            return ByteRange(from,to).takeIf {to>=from}
        }
    }
}

/** Private, read-only, seekable transport for both player engines. No URL/password leaves RAM.
 * Binds loopback only, requires an unguessable token, bounds workers/headers, closes active reads.
 */
internal class MediaRelay:Closeable {
    private val token=ByteArray(32).also(SecureRandom()::nextBytes).toHex()
    private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val media=ConcurrentHashMap<String,RemoteMedia>()
    private val families=ConcurrentHashMap<String,(String)->RemoteMedia>()
    private val clients=ConcurrentHashMap.newKeySet<Closeable>()
    private val workers=ThreadPoolExecutor(2,4,20,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"Ember media read").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    @Volatile private var closed=false
    init {Thread({while(!closed)try {
        val client=socket.accept();client.soTimeout=8000;clients+=client
        try {workers.execute {serve(client)}} catch(_:RejectedExecutionException){clients-=client;client.close()}
    } catch(_:Exception){if(!closed)close()}},"Ember media relay").apply {isDaemon=true;start()}}
    fun register(source:RemoteMedia,name:String):String {
        check(!closed);require(source.length>0)
        val id=java.util.UUID.randomUUID().toString()
        val extension=name.substringAfterLast('.').lowercase().takeIf {it.matches(Regex("[a-z0-9]{1,8}"))}?:"bin"
        val path="/$token/$id/media.$extension";media[path]=source
        return "http://127.0.0.1:${socket.localPort}$path"
    }
    /** The token protects an entire registered HTTP origin, including DASH segment templates. */
    fun origin(resource:(String)->RemoteMedia):String {
        check(!closed);check(families.size<128)
        val prefix="/$token/"+java.util.UUID.randomUUID().toString()
        families[prefix]=resource
        return "http://127.0.0.1:${socket.localPort}$prefix"
    }
    private fun once(input:InputStream):InputStream=object:java.io.FilterInputStream(input) {
        private val released=java.util.concurrent.atomic.AtomicBoolean()
        override fun close(){if(released.compareAndSet(false,true))super.close()}
    }
    private fun serve(client:Socket) {
        try {client.use {
            val input=client.getInputStream();var count=0
            fun line():String {val bytes=java.io.ByteArrayOutputStream();while(true){val c=input.read();if(c<0)throw java.io.EOFException();check(++count<=8192);if(c==10)break;if(c!=13)bytes.write(c)};return bytes.toString("US-ASCII")}
            val request=line().split(' ');if(request.size!=3)return
            val headers=linkedMapOf<String,String>();while(true){val row=line();if(row.isEmpty())break;val colon=row.indexOf(':');if(colon<=0)return;val key=row.substring(0,colon).lowercase();if(headers.put(key,row.substring(colon+1).trim())!=null)return}
            val out=client.getOutputStream()
            fun status(code:String,extra:String="",length:Long=0) {out.write(("HTTP/1.1 $code\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+(if(length>=0)"Content-Length: $length\r\n" else "")+"$extra\r\n").toByteArray())}
            if(headers["host"]!="127.0.0.1:${socket.localPort}"){status("403 Forbidden");return}
            if(request[0] !in setOf("GET","HEAD")){status("405 Method Not Allowed");return}
            val source=media[request[1]]?:run {
                val path=request[1];val prefix=path.substringBefore('?',path).split('/').take(3).joinToString("/")
                val factory=families[prefix]?:run {status("404 Not Found");return}
                try {factory(path.removePrefix(prefix).ifEmpty {"/"})}catch(_:Exception){status("502 Bad Gateway");return}
            }
            if(source.length<0) {
                if(headers["range"]!=null && headers["range"]!="bytes=0-"){status("416 Range Not Satisfiable");return}
                if(request[0]=="HEAD"){status("200 OK",length=-1);return}
                val stream=try {once(source.open(0))}catch(_:Exception){status("502 Bad Gateway");return}
                if(closed){stream.close();return}
                clients+=stream
                try {stream.use {status("200 OK","Content-Type: application/octet-stream\r\n",-1);val buffer=ByteArray(64*1024);while(!closed){val n=it.read(buffer);if(n<0)break;if(n>0)out.write(buffer,0,n)}}}finally{clients-=stream}
                return
            }
            val range=ByteRange.parse(headers["range"],source.length)?:run {status("416 Range Not Satisfiable","Content-Range: bytes */${source.length}\r\n");return}
            val length=range.end-range.start+1
            val extra="Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n"+(if(headers["range"]!=null)"Content-Range: bytes ${range.start}-${range.end}/${source.length}\r\n" else "")
            if(request[0]=="HEAD"){status(if(headers["range"]==null)"200 OK" else "206 Partial Content",extra,length);return}
            val stream=try {once(source.open(range.start))} catch(_:Exception){status("502 Bad Gateway");return}
            if(closed){stream.close();return}
            clients+=stream
            try {stream.use {
                status(if(headers["range"]==null)"200 OK" else "206 Partial Content",extra,length)
                val buffer=ByteArray(64*1024);var remaining=length
                while(remaining>0&&!closed){val n=stream.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(n<0)break;if(n==0)continue;out.write(buffer,0,n);remaining-=n}
            }} finally {clients-=stream}
        }} catch(_:Exception) { /* No addresses, filenames or credentials in transport logs. */ }
        finally {clients-=client;runCatching {client.close()}}
    }
    @Synchronized override fun close() {
        if(closed)return
        closed=true;runCatching {socket.close()}
        val pending=clients.toList();clients.clear();media.clear();families.clear()
        // Local sockets close immediately; a remote share's protocol close may take a round trip.
        pending.filterIsInstance<Socket>().forEach {runCatching {it.close()}}
        workers.shutdownNow()
        Thread({pending.filterNot {it is Socket}.forEach {runCatching {it.close()}}},"Ember media cleanup").apply {isDaemon=true;start()}
    }
}
