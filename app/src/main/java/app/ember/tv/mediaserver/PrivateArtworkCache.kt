package app.ember.tv.mediaserver

import app.ember.tv.data.ArtworkDiskCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*

/** Encoded image bytes and MIME stay together inside the cached entry. */
internal class PrivateArtworkCache(private val store:ArtworkDiskCache) {
    private class Flight {val gate=Mutex();var users=0}
    private val flights=hashMapOf<String,Flight>()
    suspend fun get(identity:String,valid:suspend ()->Boolean,validate:(ServerImageBytes)->Unit={},load:suspend ()->ServerImageBytes):Pair<ServerImageBytes,Boolean> {
        val flight=synchronized(flights) {flights.getOrPut(identity){Flight()}.also {it.users++}}
        val stamp=store.stamp()
        try {return flight.gate.withLock {
            suspend fun authorize() {if(!valid())throw CancellationException("Artwork account changed")}
            authorize()
            val packed=store.read(identity,stamp)
            var saved=packed?.let {bytes->runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use {input->
                    val mime=input.readUTF().takeIf(String::isNotEmpty)
                    val length=input.readInt()
                    check(length in 1..(8 shl 20) && length==input.available())
                    ServerImageBytes(ByteArray(length).also(input::readFully),mime)
                }
            }.getOrNull()}
            if(packed!=null && saved==null)store.remove(identity,stamp)
            saved?.let {image->
                try {validate(image)} catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){store.remove(identity,stamp);saved=null}
            }
            if(saved!=null && store.current(stamp)) {authorize();return@withLock saved to true}
            val fresh=load()
            currentCoroutineContext().ensureActive();authorize();validate(fresh)
            if(fresh.cacheable && fresh.bytes.size in 1..(8 shl 20)) {
                try {val packed=ByteArrayOutputStream().also {out->DataOutputStream(out).use {
                    it.writeUTF(fresh.mime.orEmpty().take(512));it.writeInt(fresh.bytes.size);it.write(fresh.bytes)
                }}.toByteArray()
                store.write(identity,packed,stamp)
                } catch(_:OutOfMemoryError) { /* Optional persistence must not prevent playback artwork. */ }
            }
            authorize()
            fresh to false
        }} finally {synchronized(flights) {flight.users--;if(flight.users==0)flights.remove(identity)}}
    }
}
