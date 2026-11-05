package app.ember.tv

import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import app.ember.tv.data.*
import app.ember.tv.iptv.*
import app.ember.tv.ui.emberColorScheme
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceGuideAndCacheSafetyTest {
    @get:Rule val temp=TemporaryFolder()
    private fun source(url:String?="https://example.test/video",name:String="First",quality:String="4K")=StreamOption(
        "Provider",name,name,url,null,null,quality,"DV","HEVC","Atmos","","",10.0,10,true)
    private fun channel(i:Int)=Channel("a:$i","a","Channel $i","https://example.test/$i")

    @Test fun blankAddonLinksAreRejectedAndValidLinksAreTrimmed() {
        for(json in listOf("""{"url":""}""","""{"url":"   ","infoHash":" "}"""))assertNull(StreamParser.parse("Provider",Json.parseToJsonElement(json)))
        assertEquals("https://example.test/video",StreamParser.parse("Provider",Json.parseToJsonElement("""{"url":"  https://example.test/video  "}"""))!!.url)
        val torrent=StreamParser.parse("Provider",Json.parseToJsonElement("""{"url":" ","infoHash":"0123456789abcdef"}"""))!!
        assertNull(torrent.url);assertFalse(torrent.isDirect)
        assertFalse(source(" ").isDirect)
        assertFalse(source("  magnet:?xt=urn:btih:abc").isDirect)
    }

    @Test fun emptyEndpointsCannotWinAutomaticOrNextEpisodeSelection() {
        val invalid=source(" ","Invalid")
        val valid=source(quality="HD")
        assertEquals(valid,PlayPicker.pick(listOf(invalid,valid),emptyList(),PlayRules(readyOnly=false)))
        assertEquals(valid,StreamParser.pickSimilar(listOf(invalid,valid),null,null))
        assertNull(PlayPicker.pick(listOf(source(null)),emptyList(),PlayRules(readyOnly=false)))
    }

    @Test fun singlePassPickKeepsRankingAndFirstEqualCandidate() {
        val first=source();val equal=first.copy(name="Equal")
        assertEquals(first,PlayPicker.pick(listOf(first,equal),emptyList(),PlayRules()))
        assertEquals(first,StreamParser.pickSimilar(listOf(first,equal),null,null))
        val options=(0..500).map {i->source(name="$i",quality=if(i==299)"4K" else "HD")}
        assertEquals("299",PlayPicker.pick(options,emptyList(),PlayRules())!!.name)
        assertEquals("299",StreamParser.pickSimilar(options,null,null)!!.name)
        assertNull(PlayPicker.pick(emptyList(),emptyList(),PlayRules()))
    }

    @Test fun repeatedFavouritesAndGuideOverlapsStillFillFeaturedRow() {
        val channels=(1..40).map(::channel)
        val duplicates=List(30){channels.first()}+channels
        val favourites=channels.take(19).mapTo(linkedSetOf()){it.key}
        val guide=channels.associate {it.key to listOf(Programme(0,1000,"On now"))}
        val row=LivePresentation.featured(duplicates,favourites,channels.take(20).map{it.key},guide,500)
        assertEquals(channels.take(20),row)
        assertEquals(20,row.distinctBy{it.key}.size)
        assertEquals(channels.take(20),LivePresentation.featured(duplicates,emptySet(),emptyList(),emptyMap(),500))
        assertTrue(LivePresentation.featured(channels,emptySet(),emptyList(),guide,500,0).isEmpty())
    }

    @Test fun xmltvTimestampPrecisionOffsetsAndLeapDaysAreExact() {
        val expected=Instant.parse("2026-10-26T12:30:00Z").toEpochMilli()
        listOf("20261026123000 +0000","20261026123000+0000","20261026123000","202610261230","20261026083000 -0400","202610261430+0200","20261026123000Z").forEach {assertEquals(it,expected,Xmltv.time(it))}
        assertEquals(Instant.parse("2024-02-29T00:00:00Z").toEpochMilli(),Xmltv.time("20240229000000 +0000"))
    }

    @Test fun malformedGuideTimesNeverRollOverOrIgnoreTrailingGarbage() {
        listOf(null,"","20260230000000 +0000","20260229000000 +0000","20261301120000","20261026250000","20261026123000 +2460","20261026123000 trailing","202610261230000").forEach {assertEquals(it,0L,Xmltv.time(it))}
    }

    @Test fun guideFormattersAreSafeAcrossParallelFeeds() {
        val workers=Executors.newFixedThreadPool(4)
        try {
            val expected=Instant.parse("2026-10-26T12:30:00Z").toEpochMilli()
            val tasks=(1..100).map {workers.submit<Long>{Xmltv.time("20261026083000 -0400")}}
            tasks.forEach{assertEquals(expected,it.get().toLong())}
        } finally {workers.shutdownNow()}
    }

    @Test fun invalidThemeValuesUseTheSameFallbackForPaletteAndBrand() {
        for(index in listOf(-1,ColorThemes.LAST+1,Int.MAX_VALUE,Int.MIN_VALUE)) {
            assertEquals(0,ColorThemes.normalize(index));assertEquals(0,ColorThemes.artworkIndex(index))
            assertEquals(Color(0xFFFF7A2F),emberColorScheme(ColorThemes.normalize(index),false,false).primary)
        }
        assertEquals(5,ColorThemes.normalize(5));assertEquals(2,ColorThemes.artworkIndex(5))
    }

    private fun context()=object:ContextWrapper(null) {
        private val directory=temp.newFolder()
        override fun getFilesDir()=directory
    }

    @Test fun homeCacheUsesUniqueAtomicTempsAndStillBoundsRows() {
        val ctx=context()
        File(ctx.filesDir,"home-snapshot.json.tmp").mkdir()
        val row=HomeSnapshot.Row("Provider","Top",(1..100).map{Meta("$it","movie","Film $it")})
        HomeSnapshot.save(ctx,HomeSnapshot.Data(rows=listOf(row),savedAt=42))
        val restored=HomeSnapshot.load(ctx)!!
        assertEquals(42L,restored.savedAt);assertEquals(30,restored.rows.single().items.size)
        assertTrue(ctx.filesDir.listFiles()!!.none{it.name.startsWith(".home-snapshot.json-")})
    }

    @Test fun simultaneousHomeCacheSavesAlwaysLeaveOneCompleteSnapshot() {
        val ctx=context();val workers=Executors.newFixedThreadPool(4);val gate=CountDownLatch(1)
        try {
            val tasks=(1..20).map {i->workers.submit {gate.await();HomeSnapshot.save(ctx,HomeSnapshot.Data(rows=listOf(HomeSnapshot.Row("P","Row $i",listOf(Meta("$i","movie","Film $i")))),savedAt=i.toLong()))}}
            gate.countDown();tasks.forEach{it.get(10,TimeUnit.SECONDS)}
            val restored=HomeSnapshot.load(ctx)!!
            assertEquals("Row ${restored.savedAt}",restored.rows.single().title)
            assertEquals(restored.savedAt.toString(),restored.rows.single().items.single().id)
            HomeSnapshot.clear(ctx);assertNull(HomeSnapshot.load(ctx))
        } finally {workers.shutdownNow()}
    }
}
