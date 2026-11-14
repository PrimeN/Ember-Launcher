package app.ember.tv.data

import app.ember.tv.tracker.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/** Disposable, byte-bounded binary entries. Private payloads are sealed before touching disk. */
internal class ArtworkDiskCache(
    private val root:File,
    private val cipher:SecretCipher?,
    private val budget:Long,
    private val entryLimit:Int,
    private val duration:()->Long={CachePolicy.config.millis(CacheKind.IMAGES)},
    private val epoch:()->String={CachePolicy.epoch(CacheKind.IMAGES)},
    private val access:()->Long={0L},
    private val clock:()->Long=System::currentTimeMillis,
    private val capacity:()->Long={budget},
    private val maxEntries:Int=1024,
    private val partition:(String)->String?={null},
) {
    data class Stamp(val epoch:String,val generation:Long,val access:Long,val partition:String?=null,val partitionVersion:Long=0)
    private val generation=AtomicLong()
    private val gate=Mutex()
    private val partitions=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val blocked=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun stamp(partition:String?=null)=Stamp(epoch(),generation.get(),access(),partition,partition?.let {partitions[it]} ?: 0)
    fun stampFor(identity:String)=stamp(partition(identity))
    fun current(ticket:Stamp)=ticket==stamp(ticket.partition)
    private fun prefix(value:String)=CachePolicy.digest(value)+"-"
    private fun name(identity:String,stamp:Stamp)=partition(identity)?.let(::prefix).orEmpty()+CachePolicy.digest("artwork-v1:${stamp.epoch}:$identity")
    suspend fun clearPartition(value:String) {
        blocked.add(value)
        partitions.compute(value) {_,old->(old ?: 0)+1}
        withContext(Dispatchers.IO) {gate.withLock {
            if(safe())root.listFiles().orEmpty().filter {it.name.startsWith(prefix(value)) || it.name.matches(Regex("[a-f0-9]{64}\\.cache"))}
                .forEach {check(it.delete() || !it.exists())}
            check(safe())
            blocked.remove(value)
        }}
    }
    private fun file(name:String)=File(root,"$name.cache")
    private fun safe()=!Files.isSymbolicLink(root.toPath())

    suspend fun read(identity:String,stamp:Stamp=stampFor(identity)):ByteArray?=withContext(Dispatchers.IO) {
        gate.withLock {
            if(stamp.partition?.let {it in blocked}==true || !current(stamp) || !safe())return@withLock null
            val name=name(identity,stamp);val file=file(name)
            try {
                if(!file.isFile || Files.isSymbolicLink(file.toPath()))return@withLock null
                if(file.length()>entryLimit+256L) {file.delete();return@withLock null}
                val stored=FileInputStream(file).use {input->
                    val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(out.size()<=entryLimit+256) {
                        val count=input.read(buffer,0,minOf(buffer.size,entryLimit+257-out.size()))
                        if(count<0)break
                        out.write(buffer,0,count)
                    }
                    out.toByteArray()
                }
                if(stored.size>entryLimit+256) {file.delete();return@withLock null}
                val plain=cipher?.decrypt(name,stored) ?: stored
                val value=DataInputStream(ByteArrayInputStream(plain)).use {input->
                    check(input.readInt()==MAGIC)
                    val created=input.readLong()
                    check(clock()-created in 0 until duration())
                    val length=input.readInt()
                    check(length in 0..entryLimit && length==input.available())
                    ByteArray(length).also(input::readFully)
                }
                currentCoroutineContext().ensureActive()
                if(!current(stamp))return@withLock null
                file.setLastModified(clock())
                value
            } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
            catch(_:Exception) {file.delete();null}
            catch(_:OutOfMemoryError) {null}
        }
    }

    suspend fun write(identity:String,value:ByteArray,stamp:Stamp=stampFor(identity)):Boolean=withContext(Dispatchers.IO) {
        if(value.size>entryLimit)return@withContext false
        gate.withLock {
            if(stamp.partition?.let {it in blocked}==true || !current(stamp) || !safe())return@withLock false
            val name=name(identity,stamp);val file=file(name)
            try {
                val plain=ByteArrayOutputStream(value.size+16).also {out->DataOutputStream(out).use {
                    it.writeInt(MAGIC);it.writeLong(clock());it.writeInt(value.size);it.write(value)
                }}.toByteArray()
                // Never write unencrypted bytes if opening or using the Keystore fails.
                val stored=cipher?.encrypt(name,plain) ?: plain
                currentCoroutineContext().ensureActive()
                val limit=capacity().coerceAtLeast(0)
                if(!current(stamp) || stored.size>limit)return@withLock false
                AtomicFile.replace(file) {temp->FileOutputStream(temp).use {it.write(stored);it.fd.sync()}}.getOrThrow()
                if(!current(stamp)) {file.delete();return@withLock false}
                file.setLastModified(clock())
                var used=0L
                val files=root.listFiles().orEmpty()
                // A killed process can leave AtomicFile's encrypted temporary file behind.
                files.filter {it.name.startsWith(".") && it.extension=="tmp" && !Files.isSymbolicLink(it.toPath())}.forEach {it.delete()}
                files.filter {it.isFile && !Files.isSymbolicLink(it.toPath()) && it.extension=="cache"}
                    .sortedByDescending {it.lastModified()}.forEachIndexed {index,entry->
                        used+=entry.length()
                        if(used>limit || index>=maxEntries)entry.delete()
                    }
                true
            } catch(cancelled:kotlinx.coroutines.CancellationException) {file.delete();throw cancelled}
            catch(_:Exception) {false}
            catch(_:OutOfMemoryError) {false}
        }
    }
    suspend fun remove(identity:String,stamp:Stamp=stampFor(identity))=withContext(Dispatchers.IO) {
        gate.withLock {if(safe())file(name(identity,stamp)).delete()}
    }
    suspend fun clear() {
        generation.incrementAndGet() // Reject old work before waiting for a pending write.
        withContext(Dispatchers.IO) {gate.withLock {check(CacheFiles.delete(root)) {"Could not clear artwork cache."}}}
    }
    companion object {private const val MAGIC=0x45415231}
}
