package app.ember.tv

import android.app.Application
import android.graphics.Bitmap
import app.ember.tv.data.*
import app.ember.tv.ui.FrostCache
import app.ember.tv.mediaserver.*
import app.ember.tv.tracker.TokenMigration
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ArtworkScopeRetentionTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    @Before fun init(){ArtworkCaches.init(app);ArtworkCaches.accessChanged()}
    @After fun clean(){runBlocking {ArtworkCaches.clear();app.imageLoader.memoryCache?.clear();FrostCache.clear()}}
    @Test fun aProfileChangePreservesPublicMemoryAndPartitionedPrivateBytes()=runBlocking {
        val key=MemoryCache.Key("public-poster")
        app.imageLoader.memoryCache!!.set(key,MemoryCache.Value(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)))
        val disk=ArtworkCaches.originals()!!;disk.write("kept-realm:poster",byteArrayOf(1))
        ArtworkCaches.accessChanged()
        assertNotNull(app.imageLoader.memoryCache!![key]);assertArrayEquals(byteArrayOf(1),disk.read("kept-realm:poster"))
    }
    @Test fun refusedRealmEvictsOnlyItsDecodedImagesAndLateRegistrationsAreRejected() {
        val cache=app.imageLoader.memoryCache!!
        val public=MemoryCache.Key("public");val a=MemoryCache.Key("private-a");val b=MemoryCache.Key("private-b")
        for(key in listOf(public,a,b))cache[key]=MemoryCache.Value(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888))
        val version=ArtworkCaches.revision.value
        ArtworkCaches.rememberMemory("scope-test-a",a,version);ArtworkCaches.rememberMemory("scope-test-b",b,version)
        ArtworkCaches.refused("scope-test-a")
        assertNull(cache[a]);assertNotNull(cache[public]);assertNotNull(cache[b])
        cache[a]=MemoryCache.Value(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888))
        ArtworkCaches.rememberMemory("scope-test-a",a,version);assertNull(cache[a])
    }
    @Test fun explicitReauthenticationCanRestoreAnAccountThatWasRefused()=runBlocking {
        val values=app.getSharedPreferences(TokenMigration.PLAIN,0)
        val account=ServerAccount("scope-server","jellyfin","Server","https://server.example","user","fixture-token")
        values.edit().putString("ms.servers",Json.encodeToString(listOf(account))).commit()
        val ref=ServerRef("jellyfin",account.key,"movie")
        val first=ArtworkCaches.realm(app,ref)!!
        assertTrue(ArtworkCaches.refused(first))
        assertEquals(first,ArtworkCaches.realm(app,ref))
        assertFalse(ArtworkCaches.refused(first))
        val accounts=Accounts(object:ServerSecrets {
            override fun read(key:String)=values.getString(key,null)
            override fun write(key:String,value:String):Boolean=values.edit().putString(key,value).commit()
            override fun remove(key:String){values.edit().remove(key).commit()}
        })
        accounts.saveServers(listOf(account))
        assertEquals(first,ArtworkCaches.realm(app,ref))
        assertTrue(ArtworkCaches.refused(first))
    }
    @Test fun addingAnAccountInvalidatesAnEarlierMissingAccountResult()=runBlocking {
        val values=app.getSharedPreferences(TokenMigration.PLAIN,0)
        values.edit().putString("ms.servers","[]").commit()
        val ref=ServerRef("jellyfin","new-scope-server","movie")
        assertNull(ArtworkCaches.realm(app,ref))
        val accounts=Accounts(object:ServerSecrets {
            override fun read(key:String)=values.getString(key,null)
            override fun write(key:String,value:String):Boolean=values.edit().putString(key,value).commit()
            override fun remove(key:String){values.edit().remove(key).commit()}
        })
        accounts.saveServers(listOf(ServerAccount(ref.account,"jellyfin","Server","https://server.example","user","fixture-token")))
        assertNotNull(ArtworkCaches.realm(app,ref))
    }

}
