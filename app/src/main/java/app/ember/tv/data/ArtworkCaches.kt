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

/** Separate public blur samples and private server images, partitioned by account, all under disposable cacheDir. */
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
        // No app-level encryption (#28): the private folders keep server artwork apart by profile and
        // account; Android's sandbox and storage encryption protect the files themselves.
        val cipher:app.ember.tv.tracker.SecretCipher?=null
        publicBlur=ArtworkDiskCache(File(ctx.cacheDir,directories[0]),null,8L shl 20,1 shl 20,capacity={budgets().blur})
        privateBlur=ArtworkDiskCache(File(ctx.cacheDir,directories[1]),cipher,8L shl 20,1 shl 20,capacity={budgets().blur},partition={it.removePrefix("frost-v1:").substringBefore(':')})
        originals=ArtworkDiskCache(File(ctx.cacheDir,directories[2]),cipher,64L shl 20,(8 shl 20)+1024,capacity={budgets().originals},maxEntries=4096,partition={it.substringBefore(':')})
    }
    fun blur(private:Boolean)=if(private)privateBlur else publicBlur
    fun originals()=originals
    /**
     * Realm per server, account and profile until access changes (#30). Every private image asked
     * for it several times, and each answer read the sign-ins and built a Prefs (#31).
     */
    private val realms=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,String?>>()
    private val retiring=java.util.concurrent.ConcurrentHashMap<String,Deferred<Unit>>()
    suspend fun realm(context:Context,ref:ServerRef):String?=withContext(Dispatchers.IO) {
        while(true) {
        val profile=context.getSharedPreferences("ember",Context.MODE_PRIVATE).read(PreferenceKeys.CURRENT_PROFILE) ?: Profile.MAIN
        val key="${ref.provider}\u0000${ref.account}\u0000$profile"
        val generation=access.get()
        val value=realms[key]?.takeIf {it.first==generation}?.second
            ?: Accounts(context.applicationContext).artworkRealm(ref,profile).also {realms[key]=generation to it}
        value?.let {retiring[it]?.await()}
        currentCoroutineContext().ensureActive()
        if(generation==access.get())return@withContext value
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    fun ref(meta:Meta?,source:String?):ServerRef?=meta?.let(ServerRef::of) ?:
        source?.let(ServerMetadata::artworkParts)?.first
    private fun deniedKey(realm:String,source:String)=CachePolicy.digest("$realm:$source")
    fun persistenceAllowed(realm:String,source:String?)=source==null || synchronized(denied) {denied[deniedKey(realm,source)]!=true}
    fun noStore(realm:String,source:String)=synchronized(denied) {denied[deniedKey(realm,source)]=true}

    private val refusedRealms=java.util.Collections.synchronizedSet(HashSet<String>())
    /**
     * A server answered 401/403 for one image (#26). Reset private artwork once for this sign-in,
     * not for every refused image. Other public/account images stay cached, and fresh requests
     * remain allowed because a single forbidden image need not invalidate the account. A new
     * sign-in or token is a new realm, so it still gets its own reset.
     */
    private val scopeLock=Any()
    private val memoryKeys=mutableMapOf<String,MutableSet<coil.memory.MemoryCache.Key>>()
    fun rememberMemory(realm:String,key:coil.memory.MemoryCache.Key?,version:Long) = synchronized(scopeLock) {
        if(key==null)return@synchronized
        if(version!=access.get()) {application?.imageLoader?.memoryCache?.remove(key);return@synchronized}
        synchronized(memoryKeys) {
            memoryKeys.getOrPut(realm) {hashSetOf()}.add(key)
            if(memoryKeys.values.sumOf {it.size}>1024) {
                val live=application?.imageLoader?.memoryCache?.keys.orEmpty()
                memoryKeys.values.forEach {it.retainAll(live)}
                memoryKeys.entries.removeAll {it.value.isEmpty()}
            }
        }
    }
    private fun evictRealm(realm:String) {
        if(application==null)return
        app.ember.tv.ui.FrostCache.clearRealm(realm)
        val keys=synchronized(memoryKeys) {memoryKeys.remove(realm).orEmpty()}
        keys.forEach {application?.imageLoader?.memoryCache?.remove(it)}
        val previous=retiring[realm]
        val task=io.async(start=CoroutineStart.LAZY) {
            previous?.join()
            runCatching {privateBlur?.clearPartition(realm)}
            runCatching {originals?.clearPartition(realm)}
            Unit
        }
        retiring[realm]=task
        task.invokeOnCompletion {retiring.remove(realm,task)}
        task.start()
    }
    fun refused(realm:String):Boolean = synchronized(scopeLock) {
        val first=refusedRealms.add(realm)
        if(first) {accessChanged();evictRealm(realm)}
        first
    }
    /** Profile changes select another cache realm; unrelated bytes and decoded public images stay. */
    fun accessChanged() = synchronized(scopeLock) { revision.value=access.incrementAndGet() }
    fun refusedAccounts(provider:String):Set<String> = realms.entries.filter {entry->
        entry.key.substringBefore('\u0000')==provider && entry.value.second in refusedRealms
    }.mapTo(hashSetOf()) {it.key.split('\u0000')[1]}
    /** Account mutations retire only the previously observed realms of affected accounts. */
    fun accountsChanged(provider:String,accounts:Set<String>?=null) = synchronized(scopeLock) {
        accessChanged()
        val affected=realms.entries.filter { entry ->
            val parts=entry.key.split('\u0000')
            parts[0]==provider && (accounts==null || parts[1] in accounts)
        }
        affected.forEach {entry->
            realms.remove(entry.key)
            entry.value.second?.let {refusedRealms.remove(it);evictRealm(it)}
        }
    }
    suspend fun clear() {
        access.incrementAndGet()
        synchronized(denied){denied.clear()}
        synchronized(memoryKeys){memoryKeys.clear()}
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
