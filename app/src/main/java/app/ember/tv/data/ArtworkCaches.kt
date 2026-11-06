package app.ember.tv.data

import android.content.Context
import app.ember.tv.mediaserver.Accounts
import app.ember.tv.mediaserver.ServerMetadata
import app.ember.tv.mediaserver.ServerRef
import coil.imageLoader
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class ArtworkCacheLimits(val originals:Long,val blur:Long) {
    companion object {
        private const val MIB=1024L*1024
        fun available(bytes:Long)=if(bytes<=0)ArtworkCacheLimits(64*MIB,8*MIB)
            else ArtworkCacheLimits((bytes/32).coerceIn(16*MIB,256*MIB),(bytes/256).coerceIn(2*MIB,32*MIB))
    }
}

/** Separate public blur samples and encrypted private images, all under disposable cacheDir. */
@android.annotation.SuppressLint("StaticFieldLeak")
internal object ArtworkCaches {
    const val PRIVATE_SCOPE="ember.image.private-scope"
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val access=AtomicLong()
    val revision=kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var application:Context?=null
    private val budgetLock=Any()
    @Volatile private var checkedAt=0L
    @Volatile private var limits=ArtworkCacheLimits.available(0)
    // Called by the disk store on IO, at most once a minute; no filesystem probe blocks startup.
    private fun budgets():ArtworkCacheLimits {
        val now=System.nanoTime()
        if(checkedAt==0L || now-checkedAt>60_000_000_000L)synchronized(budgetLock) {
            if(checkedAt==0L || now-checkedAt>60_000_000_000L) {
                limits=ArtworkCacheLimits.available(runCatching {android.os.StatFs(requireNotNull(application).cacheDir.path).availableBytes}.getOrDefault(0))
                checkedAt=now
            }
        }
        return limits
    }
    private var publicBlur:ArtworkDiskCache?=null
    private var privateBlur:ArtworkDiskCache?=null
    private var originals:ArtworkDiskCache?=null
    private val denied=object:LinkedHashMap<String,Boolean>(32,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Boolean>?)=size>512
    }
    private val directories=listOf("artwork-blur-v1","private-artwork-blur-v1","private-artwork-v1")

    @Synchronized fun init(context:Context) {
        if(application!=null)return
        val ctx=context.applicationContext;application=ctx
        val cipher=app.ember.tv.tracker.WrappedKeyCipher.forCache(ctx,"ember_artwork_cache_v1")
        publicBlur=ArtworkDiskCache(File(ctx.cacheDir,directories[0]),null,8L shl 20,1 shl 20,capacity={budgets().blur})
        privateBlur=ArtworkDiskCache(File(ctx.cacheDir,directories[1]),cipher,8L shl 20,1 shl 20,access=access::get,capacity={budgets().blur})
        originals=ArtworkDiskCache(File(ctx.cacheDir,directories[2]),cipher,64L shl 20,(8 shl 20)+1024,access=access::get,capacity={budgets().originals},maxEntries=4096)
    }
    fun blur(private:Boolean)=if(private)privateBlur else publicBlur
    fun originals()=originals
    suspend fun realm(context:Context,ref:ServerRef):String?=withContext(Dispatchers.IO) {
        Accounts(context.applicationContext).artworkRealm(ref,Prefs(context).currentProfile)
    }
    fun ref(meta:Meta?,source:String?):ServerRef?=meta?.let(ServerRef::of) ?:
        source?.let(ServerMetadata::artworkParts)?.first
    private fun deniedKey(realm:String,source:String)=CachePolicy.digest("$realm:$source")
    fun persistenceAllowed(realm:String,source:String?)=source==null || synchronized(denied) {denied[deniedKey(realm,source)]!=true}
    fun noStore(realm:String,source:String)=synchronized(denied) {denied[deniedKey(realm,source)]=true}

    private val refusedRealms=java.util.Collections.synchronizedSet(HashSet<String>())
    /**
     * A server answered 401/403 for one image (#26). Reset private artwork once for this sign-in,
     * not for every refused image: the reset clears every image in memory, the reload asks for the
     * same refused image again, and the launcher kept clearing and redecoding all artwork. A new
     * sign-in or token is a new realm, so it still gets its own reset.
     */
    fun refused(realm:String):Boolean = refusedRealms.add(realm).also { first -> if(first)accessChanged() }

    /** Called after account/profile mutations. The generation changes synchronously; disk work does not. */
    fun accessChanged() {
        revision.value=access.incrementAndGet()
        val ctx=application ?: return
        app.ember.tv.ui.FrostCache.clear()
        io.launch {
            runCatching {privateBlur?.clear()}
            runCatching {originals?.clear()}
            ctx.imageLoader.memoryCache?.clear()
            revision.value=access.incrementAndGet()
        }
    }
    suspend fun clear() {
        access.incrementAndGet()
        synchronized(denied){denied.clear()}
        var failure:Exception?=null
        listOf(publicBlur,privateBlur,originals).forEach {store->
            try {store?.clear()} catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){failure=error}
        }
        failure?.let {throw it}
    }
    fun imageClearCompleted() {revision.value=access.incrementAndGet()}
    fun size(context:Context)=directories.sumOf {CacheFiles.size(File(context.cacheDir,it))}
}
