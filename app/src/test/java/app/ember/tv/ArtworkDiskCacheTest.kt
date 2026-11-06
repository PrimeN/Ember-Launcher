package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import app.ember.tv.tracker.SecretCipher
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class ArtworkFixtureCipher:SecretCipher {
    private val key=SecretKeySpec(ByteArray(32){(it+7).toByte()},"AES")
    override fun encrypt(name:String,value:ByteArray):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(name.toByteArray())
        return cipher.iv+cipher.doFinal(value)
    }
    override fun decrypt(name:String,blob:ByteArray):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,blob,0,12));cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(blob,12,blob.size-12)
    }
}

class ArtworkDiskCacheTest {
    @get:Rule val temp=TemporaryFolder()
    private var now=1000L
    private var epoch="images-a"
    private var access=0L
    private fun cache(root:File,cipher:SecretCipher?=ArtworkFixtureCipher(),budget:Long=4096,limit:Int=1024)=
        ArtworkDiskCache(root,cipher,budget,limit,{5000},{epoch},{access},{now})

    @Test fun encryptedImagesSurviveRestartWithOnlyHashedFilenames()=runBlocking {
        val root=temp.newFolder();val identity="https://server.test/private?token=secret-fixture"
        val bytes="private-image-payload".toByteArray()
        assertTrue(cache(root).write(identity,bytes))
        val file=root.listFiles()!!.single()
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.cache")))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("private-image-payload"))
        assertFalse(file.name.contains("server"))
        assertArrayEquals(bytes,cache(root).read(identity))
    }
    @Test fun publicBlurEntriesAlsoSurviveRestart()=runBlocking {
        val root=temp.newFolder();assertTrue(cache(root,null).write("blur",byteArrayOf(1,2,3)))
        assertArrayEquals(byteArrayOf(1,2,3),cache(root,null).read("blur"))
    }
    @Test fun accessDoesNotExtendExpiryAndExactBoundaryIsAMiss()=runBlocking {
        val root=temp.newFolder();val cache=cache(root);cache.write("image",byteArrayOf(1))
        now=5999;assertNotNull(cache.read("image"));now=6000;assertNull(cache.read("image"));assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun changedEpochOrAccountGenerationRejectsOldWrites()=runBlocking {
        val cache=cache(temp.newFolder());val before=cache.stamp()
        epoch="images-b";assertFalse(cache.write("old",byteArrayOf(1),before))
        val account=cache.stamp();access++;assertFalse(cache.write("account",byteArrayOf(2),account))
    }
    @Test fun clearRejectsInflightWritesEvenWithAnUnchangedEpoch()=runBlocking {
        val root=temp.newFolder();val cache=cache(root);val before=cache.stamp()
        cache.write("first",byteArrayOf(1));cache.clear()
        assertFalse(cache.write("late",byteArrayOf(2),before));assertFalse(root.exists())
        assertTrue(cache.write("fresh",byteArrayOf(3)))
    }
    @Test fun corruptOrTamperedCiphertextIsRemoved()=runBlocking {
        val root=temp.newFolder();val cache=cache(root);cache.write("image",byteArrayOf(1,2))
        root.listFiles()!!.single().writeText("corrupt")
        assertNull(cache.read("image"));assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun keyFailureNeverWritesPlaintextAndDoesNotPreventOtherWork()=runBlocking {
        val root=temp.newFolder();val failed=object:SecretCipher {
            override fun encrypt(name:String,value:ByteArray):ByteArray=error("key unavailable")
            override fun decrypt(name:String,blob:ByteArray):ByteArray=error("key unavailable")
        }
        assertFalse(cache(root,failed).write("image","private-pixels".toByteArray()))
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun leastRecentlyUsedFilesAreEvictedWithinByteBudget()=runBlocking {
        val root=temp.newFolder();val cache=cache(root,null,100,100)
        cache.write("a",ByteArray(20));now++;cache.write("b",ByteArray(20));now++;assertNotNull(cache.read("a"))
        now++;cache.write("c",ByteArray(20))
        assertNotNull(cache.read("a"));assertNull(cache.read("b"));assertNotNull(cache.read("c"))
        assertTrue(CacheFiles.size(root)<=100)
    }
    @Test fun oversizedEntriesAndSymlinkRootsAreRejected()=runBlocking {
        val root=temp.newFolder();assertFalse(cache(root,limit=4).write("large",ByteArray(5)))
        val saved=temp.newFolder();val keep=File(saved,"keep").apply {writeText("saved data")}
        val link=File(root,"linked");Files.createSymbolicLink(link.toPath(),saved.toPath())
        assertFalse(cache(link).write("image",byteArrayOf(1)))
        cache(link).clear();assertEquals("saved data",keep.readText())
    }
    @Test(timeout=15000) fun concurrentPrivateReadersFetchOnceAndRestartUsesDisk()=runBlocking {
        val root=temp.newFolder();var calls=0;val cache=PrivateArtworkCache(cache(root))
        val results=List(12){async {cache.get("account:image",{true}){calls++;delay(30);ServerImageBytes(byteArrayOf(1,2,3),"image/png")}}}.awaitAll()
        assertEquals(1,calls);assertEquals(11,results.count {it.second})
        val restart=PrivateArtworkCache(cache(root)).get("account:image",{true}){error("must use disk")}
        assertEquals("image/png",restart.first.mime);assertArrayEquals(byteArrayOf(1,2,3),restart.first.bytes)
    }
    @Test fun noStoreAndFailedFetchesAreNeverPersisted()=runBlocking {
        val root=temp.newFolder();val cache=PrivateArtworkCache(cache(root));var calls=0
        repeat(2){cache.get("no-store",{true}){calls++;ServerImageBytes(byteArrayOf(1),null,false)}}
        assertEquals(2,calls);assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(runCatching{cache.get("failed",{true}){error("offline")}}.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test(timeout=15000) fun accountRevokedDuringNetworkLoadCannotPersistOrReturnImage()=runBlocking {
        val root=temp.newFolder();val cache=PrivateArtworkCache(cache(root));var authorized=true
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val job=async {cache.get("image",{authorized}){started.complete(Unit);finish.await();ServerImageBytes(byteArrayOf(1),null)}}
        started.await();authorized=false;finish.complete(Unit)
        assertTrue(runCatching {job.await()}.exceptionOrNull() is CancellationException)
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test(timeout=15000) fun clearingWhileAResponseLoadsPreventsLateDiskRepopulation()=runBlocking {
        val root=temp.newFolder();val disk=cache(root);val cache=PrivateArtworkCache(disk)
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val job=async {cache.get("image",{true}){started.complete(Unit);finish.await();ServerImageBytes(byteArrayOf(1),null)}}
        started.await();disk.clear();finish.complete(Unit);job.await()
        assertFalse(root.exists())
    }
    @Test fun incompleteImagesAreRefetchedAndFreshDecodeFailuresAreNotCached()=runBlocking {
        val root=temp.newFolder();val cache=PrivateArtworkCache(cache(root))
        cache.get("image",{true}){ServerImageBytes(byteArrayOf(1),"image/png")}
        var calls=0
        val result=cache.get("image",{true},validate={check(it.bytes[0]==2.toByte())}) {calls++;ServerImageBytes(byteArrayOf(2),"image/png")}
        assertFalse(result.second);assertEquals(1,calls)
        assertTrue(cache.get("image",{true}){error("must be cached")}.second)
        assertTrue(runCatching {cache.get("broken",{true},validate={error("incomplete decode")}){ServerImageBytes(byteArrayOf(3),null)}}.isFailure)
        assertEquals(1,root.listFiles()!!.size)
    }
    @Test fun diskBudgetsGrowWithStorageWithoutGrowingMemoryOrReservingSpace() {
        val mib=1024L*1024
        assertEquals(ArtworkCacheLimits(16*mib,2*mib),ArtworkCacheLimits.available(128*mib))
        assertEquals(ArtworkCacheLimits(128*mib,16*mib),ArtworkCacheLimits.available(4096*mib))
        assertEquals(ArtworkCacheLimits(256*mib,32*mib),ArtworkCacheLimits.available(Long.MAX_VALUE))
        assertEquals(ArtworkCacheLimits(64*mib,8*mib),ArtworkCacheLimits.available(0))
    }
    @Test fun shrinkingStorageBudgetEvictsOldFilesOnNextWrite()=runBlocking {
        val root=temp.newFolder();var capacity=300L
        val disk=ArtworkDiskCache(root,null,300,100,{5000},{epoch},{access},{now},capacity={capacity})
        disk.write("a",ByteArray(50));now++;disk.write("b",ByteArray(50))
        capacity=100;now++;disk.write("c",ByteArray(50))
        assertTrue(CacheFiles.size(root)<=100);assertNull(disk.read("a"));assertNull(disk.read("b"));assertNotNull(disk.read("c"))
    }

    @Test fun aNewWriteDiscardsInterruptedAtomicWriteFiles()=runBlocking {
        val root=temp.newFolder();val orphan=File(root,".interrupted-entry.tmp").apply {writeBytes(ByteArray(100))}
        assertTrue(cache(root).write("fresh",byteArrayOf(1)))
        assertFalse(orphan.exists());assertEquals(1,root.listFiles()!!.size)
    }

    @Test fun privateCacheIdentitySeparatesProfilesUsersTokensAndProviders() {
        val values=mutableMapOf<String,String>();val accounts=Accounts(object:ServerSecrets {
            override fun read(key:String)=values[key]
            override fun write(key:String,value:String):Boolean {values[key]=value;return true}
            override fun remove(key:String){values.remove(key)}
        })
        val ref=ServerRef("jellyfin","a","item");val original=ServerAccount("a","jellyfin","Server","https://server.test","user-a","token-a")
        accounts.saveServers(listOf(original));val first=accounts.artworkRealm(ref,"profile-a")!!
        assertNotEquals(first,accounts.artworkRealm(ref,"profile-b"))
        for(changed in listOf(original.copy(token="token-b"),original.copy(userId="user-b"),original.copy(url="https://other.test"))) {
            accounts.saveServers(listOf(changed));assertNotEquals(first,accounts.artworkRealm(ref,"profile-a"))
        }
        assertNull(accounts.artworkRealm(ref.copy(provider="emby"),"profile-a"))
        accounts.plexToken="owner";assertNotNull(accounts.artworkRealm(ServerRef("plex","server","item"),"profile-a"))
        values["plex.home"]="unreadable";assertNull(accounts.artworkRealm(ServerRef("plex","server","item"),"profile-a"))
        accounts.saveServers(emptyList());assertNull(accounts.artworkRealm(ref,"profile-a"))
    }
}
