package app.ember.tv

import app.ember.tv.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BackupLibraryBarrierTest {
    @get:Rule val temp=TemporaryFolder()
    @Before fun clear()=LocalLibrary.resetForTest()
    @After fun finish()=LocalLibrary.resetForTest()

    @Test fun snapshotWaitsForQueuedLibraryWrites()=runBlocking {
        val ctx=PersistenceContext(temp.root)
        val file=File(ctx.filesDir,"library.json");file.writeText("{}")
        LocalLibrary.initFile(file);LocalLibrary.awaitReady()
        val pool=Executors.newFixedThreadPool(2)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val called=CountDownLatch(1)
        try {
            val lock=pool.submit {synchronized(AtomicFile) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            LocalLibrary.queue(true,Meta(id="tt-queued",name="Queued"))
            val snapshot=pool.submit<kotlinx.serialization.json.JsonObject> {
                called.countDown();LocalLibrary.snapshotFiles {BackupPersistence(ctx,FixtureSecrets()).snapshot()}
            }
            assertTrue(called.await(5,TimeUnit.SECONDS))
            try {snapshot.get(100,TimeUnit.MILLISECONDS);fail("Snapshot skipped the pending write")}
            catch(_:TimeoutException) { /* The writer must finish first. */ }
            release.countDown();lock.get(5,TimeUnit.SECONDS)
            val saved=snapshot.get(5,TimeUnit.SECONDS)["files"]!!.jsonObject["library.json"]!!.jsonPrimitive.content
            assertTrue(saved.contains("tt-queued"))
        } finally {release.countDown();pool.shutdownNow()}
    }

    @Test fun snapshotRefusesToExportAnOlderLibraryIfItsCurrentStateCannotBeSaved()=runBlocking {
        val ctx=PersistenceContext(temp.root);val file=File(ctx.filesDir,"library.json")
        file.writeText("{}");LocalLibrary.initFile(file);LocalLibrary.awaitReady()
        file.delete();file.mkdirs();File(file,"occupied").writeText("fixture")
        LocalLibrary.queue(true,Meta(id="tt-unsaved"))
        var called=false
        val error=runCatching {LocalLibrary.snapshotFiles {called=true}}.exceptionOrNull()
        assertTrue(error is Backup.BackupException);assertFalse(called)
    }

    @Test fun restoreReloadsTheLibraryInMemoryAndOnColdStart()=runBlocking {
        val ctx=PersistenceContext(temp.root);val file=File(ctx.filesDir,"library.json")
        file.writeText("{}");LocalLibrary.initFile(file);LocalLibrary.awaitReady()
        LocalLibrary.queue(true,Meta(id="old"))
        val text="""{"plays":[{"key":"restored","meta":{"id":"tt-restored"},"progress":0.4}],"pending":[{"add":true,"meta":{"id":"tt-pending"},"revision":"restored-revision"}]}"""
        LocalLibrary.restoreFiles {AtomicFile.write(file,text).getOrThrow()}
        assertEquals("tt-restored",LocalLibrary.inProgress.single().id)
        assertEquals("restored-revision",LocalLibrary.pending.single().revision)
        LocalLibrary.resetForTest();LocalLibrary.initFile(file);LocalLibrary.awaitReady()
        assertEquals("tt-restored",LocalLibrary.inProgress.single().id)
        assertEquals("tt-pending",LocalLibrary.pending.single().meta.id)
    }
}
