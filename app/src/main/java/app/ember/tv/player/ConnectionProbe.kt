package app.ember.tv.player

import kotlinx.coroutines.*
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ProbeResult(val bytes:Long,val milliseconds:Long,val firstByteMs:Long,val slowestMbps:Double?) {
    val averageMbps get()=bytes*8.0/(milliseconds.coerceAtLeast(1)*1000.0)
}
/** A manual read-only sample. No upload, disk movie cache, or startup/background speed test. */
internal object ConnectionProbe {
    fun measure(open:()->InputStream,limit:Long=8*1024*1024,now:()->Long=System::nanoTime,check:()->Unit={}):ProbeResult {
        require(limit>0)
        val started=now();var first:Long?=null;var bytes=0L;var sampleBytes=0L;var sampleAt=started;var slowest:Double?=null
        open().use {input ->
            val buffer=ByteArray(64*1024)
            while(bytes<limit) {
                check();val at=now()
                if(at-started>=10_000_000_000L)break
                val n=input.read(buffer,0,minOf(buffer.size.toLong(),limit-bytes).toInt())
                if(n<0)break
                if(n==0)continue
                val end=now();if(first==null){first=end;sampleAt=end}
                bytes+=n;sampleBytes+=n
                if(end-sampleAt>=500_000_000L) {
                    val rate=sampleBytes*8000.0/(end-sampleAt)
                    slowest=slowest?.let {minOf(it,rate)}?:rate;sampleBytes=0;sampleAt=end
                }
            }
        }
        check()
        require(bytes>0) {"No media data was returned."}
        val end=now()
        return ProbeResult(bytes,((end-started)/1_000_000).coerceAtLeast(1),((first?:end)-started)/1_000_000,slowest)
    }
    suspend fun run(resource:RemoteMedia):ProbeResult = withTimeout(12_000) { coroutineScope {
        val stream=AtomicReference<InputStream?>()
        val closer=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {runCatching {stream.getAndSet(null)?.close()}}
        }
        try {
                val context=currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    try { measure(open={
                        val input=resource.open(0)
                        val once=object:FilterInputStream(input) {
                            val closed=AtomicBoolean()
                            override fun close(){if(closed.compareAndSet(false,true))super.close()}
                        }
                        stream.set(once)
                        // Cancellation may have happened while the protocol was connecting.
                        if(!context.isActive){once.close();context.ensureActive()}
                        once
                    },limit=minOf(resource.length.takeIf {it>0}?:8*1024*1024,8*1024*1024),check={context.ensureActive()}) }
                    catch(error:Exception) {context.ensureActive();throw error}
                }
        } finally {closer.cancel()}
    } }
}
