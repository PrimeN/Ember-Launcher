package app.ember.tv

import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibraryStartupTest {
    @get:Rule val temp=TemporaryFolder()
    private val saved="""{"plays":[{"key":"saved","meta":{"id":"tt1","type":"movie","name":"Saved"},"progress":0.4}],"pending":[{"add":true,"meta":{"id":"tt2","type":"movie","name":"Queued"},"revision":"disk-revision"}]}"""
    @Before fun clear()=LocalLibrary.resetForTest()
    @After fun finish()=LocalLibrary.resetForTest()

    @Test fun initializationReturnsBeforeTheDiskReadCompletes()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            LocalLibrary.initFile(File(temp.root,"library.json")) {entered.countDown();check(release.await(5,TimeUnit.SECONDS));saved}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(LocalLibrary.recent.isEmpty())
        } finally {release.countDown()}
        LocalLibrary.awaitReady()
        assertEquals("tt1",LocalLibrary.inProgress.single().id)
        assertEquals("disk-revision",LocalLibrary.pending.single().revision)
    }
    @Test fun earlyWatchlistChangePreservesSavedProgressAndQueueAndPersistsMergedData()=runBlocking {
        val file=File(temp.root,"library.json")
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val called=CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        try {
            LocalLibrary.initFile(file){entered.countDown();check(release.await(5,TimeUnit.SECONDS));saved}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val action=worker.submit {called.countDown();LocalLibrary.queue(false,Meta("tt3","movie","New"))}
            assertTrue(called.await(5,TimeUnit.SECONDS));assertFalse(action.isDone)
            release.countDown();action.get(5,TimeUnit.SECONDS);LocalLibrary.awaitReady()
            assertEquals("tt1",LocalLibrary.inProgress.single().id)
            assertEquals(setOf("tt2","tt3"),LocalLibrary.pending.mapTo(hashSetOf()){it.meta.id})
            LocalLibrary.resetForTest() // drains writes; the next load must see both changes
            LocalLibrary.initFile(file);LocalLibrary.awaitReady()
            assertEquals(setOf("tt2","tt3"),LocalLibrary.pending.mapTo(hashSetOf()){it.meta.id})
        } finally {release.countDown();worker.shutdownNow()}
    }
    @Test fun restoreDuringLoadingCannotDeadlockOrBeReplacedByTheOldLoad()=runBlocking {
        val file=File(temp.root,"library.json")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        try {
            LocalLibrary.initFile(file){entered.countDown();check(release.await(5,TimeUnit.SECONDS));saved}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val restoring=worker.submit {LocalLibrary.restoreFiles {file.writeText("""{"trackerWatchlist":[{"id":"restored","type":"movie","name":"Restored"}]}""")}}
            release.countDown();restoring.get(5,TimeUnit.SECONDS);LocalLibrary.awaitReady()
            assertEquals("restored",LocalLibrary.trackerWatchlist.single().id)
            assertTrue(LocalLibrary.inProgress.isEmpty());assertTrue(LocalLibrary.pending.isEmpty())
        } finally {release.countDown();worker.shutdownNow()}
    }
    @Test fun malformedSavedDataStillAllowsNewPlaybackAndNextEpisodeUpdates()=runBlocking {
        val file=File(temp.root,"library.json")
        LocalLibrary.initFile(file){"invalid json"}
        LocalLibrary.started("s1",Meta("show","series","Show",season=1,episode=1))
        LocalLibrary.startedEpisode("s1","s2",1,2,"Next")
        LocalLibrary.progress("s2",.3f)
        LocalLibrary.awaitReady()
        assertEquals(2,LocalLibrary.inProgress.single().episode)
        LocalLibrary.resetForTest();LocalLibrary.initFile(file);LocalLibrary.awaitReady()
        assertEquals(2,LocalLibrary.inProgress.single().episode)
    }
}
