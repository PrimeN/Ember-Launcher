package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.iptv.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.Executor

class BackgroundWorkTest {
    @get:Rule val temp=TemporaryFolder()
    private class ManualExecutor:Executor {
        val pending=ArrayDeque<Runnable>()
        override fun execute(command:Runnable){pending.addLast(command)}
        fun drain(){while(pending.isNotEmpty())pending.removeFirst().run()}
    }
    @Test fun queuedSnapshotsCollapseToTheLatestValueAndKeepBarrierOrdering() {
        val executor=ManualExecutor();val saved=mutableListOf<Int>()
        val queue=LatestWriteQueue<Int>(executor,{saved.add(it)})
        repeat(100){queue.submit(it)}
        assertEquals(1,executor.pending.size)
        executor.execute {assertEquals(listOf(99),saved)}
        executor.drain();assertEquals(listOf(99),saved)
    }
    @Test fun updatesArrivingDuringAWriteAreDrainedWithoutLosingTheLatestValue() {
        val executor=ManualExecutor();val saved=mutableListOf<Int>();lateinit var queue:LatestWriteQueue<Int>
        queue=LatestWriteQueue(executor,{value->saved.add(value);if(value==1){queue.submit(2);queue.submit(3)}})
        queue.submit(1);executor.drain();assertEquals(listOf(1,3),saved)
        queue.submit(4);executor.drain();assertEquals(listOf(1,3,4),saved)
    }
    @Test fun progressSavesPreserveUnchangedTrackerListsAndFlushLatestProgress()=runBlocking {
        LocalLibrary.resetForTest()
        try {
            val c=PersistenceContext(temp.newFolder());val file=File(c.filesDir,"library.json")
            LocalLibrary.initFile(file){"{}"};LocalLibrary.awaitReady()
            LocalLibrary.saveTrackerWatchlist((1..1000).map {Meta("tt$it","movie","Film $it")})
            val before=LocalLibrary.trackerWatchlist
            LocalLibrary.started("film",Meta("tt1","movie","Film"))
            repeat(10){LocalLibrary.progress("film",it/20f)}
            assertSame(before,LocalLibrary.trackerWatchlist)
            val stored=LocalLibrary.snapshotFiles {Json.parseToJsonElement(file.readText()).jsonObject}
            assertEquals(1000,stored.getValue("trackerWatchlist").jsonArray.size)
            assertEquals(.45f,stored.getValue("plays").jsonArray.single().jsonObject.getValue("progress").jsonPrimitive.float,.001f)
        } finally {LocalLibrary.resetForTest()}
    }
    @Test fun guideProjectionKeepsOffsetsManualMatchesAndEnabledSources()=runBlocking {
        val feed=EpgFeed("provider:a","Guide","https://guide.example/xml")
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest((feed.url+"\n").toByteArray()).toHex()
        val now=10000000L
        val parsed=mapOf(feed.id to ParsedEpg(programmes=mapOf("news" to listOf(Programme(now,now+600000,"News"))),fingerprint=hash))
        val one=Channel("one","a","News","https://example/1",epgId="news")
        val disabled=one.copy(key="two",sourceId="b")
        val result=buildGuideProjection(listOf(one,disabled),setOf("a"),listOf(feed),EpgConfiguration(offsets=mapOf(feed.id to 5)),parsed,now)
        assertEquals(setOf("one"),result.matches.keys)
        assertEquals(now+300000,result.guide.getValue("one").single().start)
        assertEquals(1,parsed.getValue(feed.id).programmes.getValue("news").size)
    }
    @Test fun aChangedGuideFingerprintCannotReuseOldProgrammes()=runBlocking {
        val feed=EpgFeed("provider:a","Guide","https://guide.example/new")
        val parsed=mapOf(feed.id to ParsedEpg(programmes=mapOf("news" to listOf(Programme(1,1000,"Old"))),fingerprint="old"))
        val result=buildGuideProjection(listOf(Channel("one","a","News","https://example",epgId="news")),setOf("a"),listOf(feed),EpgConfiguration(),parsed,100)
        assertTrue(result.guide.isEmpty())
    }
}
