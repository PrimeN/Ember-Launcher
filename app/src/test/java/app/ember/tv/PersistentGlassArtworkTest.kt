package app.ember.tv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import app.ember.tv.data.*
import app.ember.tv.mediaserver.decodePrivateArtwork
import app.ember.tv.ui.*
import coil.decode.DataSource
import coil.request.Options
import coil.size.Size
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistentGlassArtworkTest {
    @get:Rule val temp=TemporaryFolder()
    @After fun clean(){FrostCache.clear()}
    private fun source(color:Int=Color.BLUE)=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
    private fun store(root:File,private:Boolean=false)=ArtworkDiskCache(root,if(private)ArtworkFixtureCipher() else null,8L shl 20,1 shl 20)
    @Test fun restartedGlassReadsPreparedPixelsWithoutRepeatingBlur()=runBlocking {
        val root=temp.newFolder();val original=source()
        val first=FrostCache.prepare(original,GlassMode.FROSTED,store(root))!!
        assertEquals(384,first.width);assertEquals(216,first.height)
        FrostCache.clear()
        val loaded=FrostCache.prepare(source(),GlassMode.FROSTED,store(root),blur={_,_,_,_->error("blur must not run on disk hit")})!!
        assertTrue(first.sameAs(loaded));assertFalse(first.isRecycled);assertFalse(original.isRecycled)
    }
    @Test fun changedPixelsAndBlurRadiusDoNotUseStaleSamples()=runBlocking {
        val disk=store(temp.newFolder());FrostCache.prepare(source(),GlassMode.FROSTED,disk);FrostCache.clear()
        var calls=0
        val changed=FrostCache.prepare(source(Color.RED),GlassMode.FROSTED,disk,blur={p,_,_,_->calls++;p})!!
        assertEquals(Color.RED,changed.getPixel(20,20));assertEquals(1,calls)
        FrostCache.clear()
        FrostCache.prepare(source(),GlassMode.LIQUID,disk,blur={p,_,_,_->calls++;p})
        assertEquals(2,calls)
    }
    @Test fun privateBlurIsEncryptedAndPartitionedByAccount()=runBlocking {
        val root=temp.newFolder();val disk=store(root,true)
        FrostCache.prepare(source(),GlassMode.FROSTED,disk,"account-a")
        val file=root.listFiles()!!.single();val bytes=file.readBytes()
        assertFalse(bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
        FrostCache.clear()
        assertNotNull(FrostCache.prepare(source(),GlassMode.FROSTED,store(root,true),"account-a",blur={_,_,_,_->error("must decrypt") }))
        var calls=0
        FrostCache.prepare(source(),GlassMode.FROSTED,disk,"account-b",blur={p,_,_,_->calls++;p})
        assertEquals(1,calls);assertEquals(2,root.listFiles()!!.size)
    }
    @Test fun tintModeDoesNoCacheOrBlurWork()=runBlocking {
        val root=temp.newFolder()
        assertNull(FrostCache.prepare(source(),GlassMode.TINT,store(root),blur={_,_,_,_->error("disabled") }))
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun noStoreSkipsMemoryRetentionAndDiskPersistence()=runBlocking {
        val original=source();var calls=0
        repeat(2){assertNotNull(FrostCache.prepare(original,GlassMode.FROSTED,retain=false,blur={p,_,_,_->calls++;p}))}
        assertEquals(2,calls)
    }
    @Test fun accountRevokedDuringPreparationCannotPublishOrPersistSample()=runBlocking {
        val root=temp.newFolder();var allowed=true
        val result=FrostCache.prepare(source(),GlassMode.FROSTED,store(root),valid={allowed},blur={p,_,_,_->allowed=false;p})
        assertNull(result);assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun invalidSavedPngIsRebuiltAndOptionalDiskFailureStillShowsGlass()=runBlocking {
        val root=temp.newFolder();val disk=store(root)
        FrostCache.prepare(source(),GlassMode.FROSTED,disk);FrostCache.clear()
        root.listFiles()!!.single().writeText("broken png")
        assertNotNull(FrostCache.prepare(source(),GlassMode.FROSTED,disk))
        val blocked=File(temp.newFolder(),"not-a-directory").apply {writeText("file")}
        FrostCache.clear();assertNotNull(FrostCache.prepare(source(),GlassMode.FROSTED,store(blocked)))
    }
    @Test fun privateDecoderUsesRequestedSizeAndCreatesNoPlaintextCacheFiles() {
        val context:Application=RuntimeEnvironment.getApplication()
        val png=ByteArrayOutputStream().also {source().compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val before=context.cacheDir.walkTopDown().filter{it.isFile}.map{it.absolutePath}.toSet()
        val result=decodePrivateArtwork(png,Options(context,size=Size(320,180)),DataSource.DISK)
        assertEquals(320,(result.drawable as BitmapDrawable).bitmap.width)
        assertEquals(180,(result.drawable as BitmapDrawable).bitmap.height);assertTrue(result.isSampled)
        assertEquals(DataSource.DISK,result.dataSource)
        assertEquals(before,context.cacheDir.walkTopDown().filter{it.isFile}.map{it.absolutePath}.toSet())
    }
    @Test fun imageManagementCountsAndClearsPreparedFilesWithoutTouchingPreferences()=runBlocking {
        val context:Application=RuntimeEnvironment.getApplication()
        val prefs=File(context.filesDir,"keep.json").apply {writeText("saved preferences")}
        Net.init(context);IconArtworkCache.init(context);CacheManagement.init(context);CacheManagement.awaitReady()
        val store=ArtworkCaches.blur(false)!!;assertTrue(store.write("sample",ByteArray(100)))
        assertTrue(CacheManagement.usage(context).categories.getValue(CacheKind.IMAGES)>=116)
        CacheManagement.clear(setOf(CacheKind.IMAGES))
        assertNull(store.read("sample"));assertEquals(0L,ArtworkCaches.size(context))
        assertEquals("saved preferences",prefs.readText())
    }
}
