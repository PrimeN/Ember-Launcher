package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.home.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupAssetsTest {
    @get:Rule val temp=TemporaryFolder()
    private val font=byteArrayOf(0,1,0,0,42)
    private fun source():PersistenceContext {
        val c=PersistenceContext(temp.newFolder());val prefs=Prefs(c)
        prefs.subtitleConfig=SubtitleConfig(font="custom",customFontName="My font")
        SubtitleFonts.file(c).apply {parentFile!!.mkdirs();writeBytes(font)}
        val slide=File(c.filesDir,"presentation/one.png").apply {parentFile!!.mkdirs();writeText("uploaded-image")}
        prefs.launcherExtrasJson=Json.encodeToString(LauncherExtras(slides=listOf(PresentationItem("one","Slide",slide.toURI().toString()))))
        return c
    }
    @Test fun fontAndUploadedMediaRoundTripToTheNewInstallationsOwnPaths() {
        val from=source();val payload=BackupPersistence(from,FixtureSecrets()).snapshot()
        val to=PersistenceContext(temp.newFolder());BackupPersistence(to,FixtureSecrets()).restore(payload)
        assertArrayEquals(font,SubtitleFonts.file(to).readBytes())
        val extras=Json.decodeFromString<LauncherExtras>(Prefs(to).launcherExtrasJson)
        val file=File(java.net.URI(extras.slides.single().uri))
        assertEquals(File(to.filesDir,"presentation/one.png").canonicalFile,file.canonicalFile)
        assertEquals("uploaded-image",file.readText());assertEquals("custom",Prefs(to).subtitleConfig.font)
    }
    @Test fun aFailedRestoreRestoresPreviousAssetsAndPreferences() {
        val payload=BackupPersistence(source(),FixtureSecrets()).snapshot()
        val to=source();val old=byteArrayOf(0,1,0,0,99);SubtitleFonts.file(to).writeBytes(old)
        File(to.filesDir,"presentation/one.png").writeText("previous-image")
        val previous=Prefs(to).launcherExtrasJson
        var fail=true
        val target=BackupPersistence(to,FixtureSecrets()){file,bytes->
            if(file.name=="library.json"&&fail){fail=false;error("Write failed")}
            if(bytes==null)file.delete() else AtomicFile.write(file,bytes).getOrThrow()
        }
        assertTrue(runCatching {target.restore(payload)}.isFailure)
        assertArrayEquals(old,SubtitleFonts.file(to).readBytes());assertEquals(previous,Prefs(to).launcherExtrasJson)
        assertEquals("previous-image",File(to.filesDir,"presentation/one.png").readText())
    }
    @Test fun assetTraversalAndMissingReferencedAssetsFailBeforeChangingSettings() {
        val payload=BackupPersistence(source(),FixtureSecrets()).snapshot()
        val to=PersistenceContext(temp.newFolder());Prefs(to).theme="day"
        val badPaths=JsonObject(payload+("assets" to buildJsonObject {put("../outside","AA==")}))
        val missing=JsonObject(payload+("assets" to JsonObject(emptyMap())))
        for(bad in listOf(badPaths,missing)) {
            assertTrue(runCatching {BackupPersistence(to,FixtureSecrets()).restore(bad)}.exceptionOrNull() is Backup.BackupException)
            assertEquals("day",Prefs(to).theme);assertFalse(SubtitleFonts.hasCustom(to))
        }
    }
    @Test fun legacyBackupsStillRestoreAndDoNotRemoveUnrecordedFontFiles() {
        val payload=JsonObject(BackupPersistence(source(),FixtureSecrets()).snapshot()-"assets")
        val to=source();val old=byteArrayOf(0,1,0,0,99);SubtitleFonts.file(to).writeBytes(old)
        BackupPersistence(to,FixtureSecrets()).restore(payload)
        assertArrayEquals(old,SubtitleFonts.file(to).readBytes())
    }
    @Test fun aBackupWithoutACustomFontDoesNotRetainThePreviousInstallationsFont() {
        val from=PersistenceContext(temp.newFolder());val to=source()
        BackupPersistence(to,FixtureSecrets()).restore(BackupPersistence(from,FixtureSecrets()).snapshot())
        assertFalse(SubtitleFonts.hasCustom(to));assertFalse(File(to.filesDir,"presentation/one.png").exists())
    }
}
