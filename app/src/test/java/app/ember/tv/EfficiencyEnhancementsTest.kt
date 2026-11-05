package app.ember.tv

import app.ember.tv.iptv.*
import app.ember.tv.ui.*
import app.ember.tv.data.CustomPerformance
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EfficiencyEnhancementsTest {
    private fun legacy(list:List<Programme>,at:Long):Pair<Programme?,Programme?> {
        val now=list.firstOrNull {it.start<=at && at<it.stop}
        return now to list.firstOrNull {it.start>=(now?.stop ?: at)}
    }
    @Test fun indexedGuidePreservesOriginalOrderAcrossGapsOverlapsAndInvalidEntries() {
        val programmes=listOf(Programme(30,90,"Later first"),Programme(0,60,"Earlier second"),
            Programme(50,55,"Nested"),Programme(100,100,"Empty"),Programme(120,100,"Reversed"),Programme(200,250,"Future"))
        val index=ProgrammeLookup(programmes)
        for(time in -1L..260L) {
            assertEquals("time=$time",legacy(programmes,time),index.nowNext(time))
            for(count in 0..7)assertEquals(programmes.filter {it.stop>time}.take(count),index.upcoming(time,count))
        }
    }
    @Test fun indexedGuideMatchesLegacyOnRandomSortedAndUnsortedSnapshots() {
        val random=Random(857)
        repeat(50) {
            val raw=List(random.nextInt(0,200)) {n->
                val start=random.nextLong(-10000,10000);Programme(start,start+random.nextLong(-500,3000),"Entry $n")
            }
            for(list in listOf(raw,raw.sortedBy {it.start})) {
                val index=ProgrammeLookup(list)
                repeat(100) {
                    val at=random.nextLong(-11000,14000)
                    assertEquals(legacy(list,at),index.nowNext(at))
                    val count=random.nextInt(0,25)
                    assertEquals(list.filter {it.stop>at}.take(count),index.upcoming(at,count))
                }
            }
        }
    }
    @Test fun guideBoundariesAndExtremeTimesDoNotOverflow() {
        val list=listOf(Programme(Long.MIN_VALUE,Long.MIN_VALUE+1,"Min"),Programme(0,1,"One"),Programme(Long.MAX_VALUE-1,Long.MAX_VALUE,"Max"))
        val index=ProgrammeLookup(list)
        for(at in listOf(Long.MIN_VALUE,Long.MIN_VALUE+1,-1,0,1,Long.MAX_VALUE-1,Long.MAX_VALUE)) {
            assertEquals(legacy(list,at),index.nowNext(at))
            assertEquals(list.filter {it.stop>at},index.upcoming(at,Int.MAX_VALUE))
        }
    }
    @Test fun emptyGuideAndZeroUpcomingAreEmpty() {
        assertEquals(null to null,ProgrammeLookup(emptyList()).nowNext(0))
        assertTrue(ProgrammeLookup(emptyList()).upcoming(0,5).isEmpty())
        assertTrue(ProgrammeLookup(listOf(Programme(0,1,"One"))).upcoming(0,0).isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun negativeUpcomingCountRetainsPriorFailureContract() {
        ProgrammeLookup(emptyList()).upcoming(0,-1)
    }
    @Test fun guideCacheSharesIndexAndInvalidatesReplacementSnapshot() {
        val cache=ProgrammeLookupCache();val old=listOf(Programme(0,10,"Old"))
        val first=cache.get("a",old);assertSame(first,cache.get("a",old))
        val fresh=listOf(Programme(0,10,"New"));val second=cache.get("a",fresh)
        assertNotSame(first,second);assertEquals("New",second.nowNext(0).first?.title)
    }
    @Test fun guideCacheIsBoundedLruAndCanReleaseOldSnapshots() {
        val cache=ProgrammeLookupCache(2);val list=listOf(Programme(0,10,"One"))
        val a=cache.get("a",list);val b=cache.get("b",list)
        assertSame(a,cache.get("a",list));cache.get("c",list)
        assertNotSame(b,cache.get("b",list));cache.clear();assertNotSame(a,cache.get("a",list))
    }
    @Test fun guideIndexLargeFixturePreservesResultsAndRecordsHostLookupCost() {
        val programmes=List(20000){Programme(it*1000L,it*1000L+1000,"Programme $it")}
        val queries=List(2000){(it*7919L)%20_000_000}
        val index=ProgrammeLookup(programmes)
        val oldStart=System.nanoTime();val old=queries.map {legacy(programmes,it)};val oldNs=System.nanoTime()-oldStart
        val newStart=System.nanoTime();val new=queries.map {index.nowNext(it)};val newNs=System.nanoTime()-newStart
        assertEquals(old,new)
        println("GUIDE_LOOKUP_HOST_BENCHMARK entries=${programmes.size} queries=${queries.size} scanNs=$oldNs indexNs=$newNs")
    }
    @Test fun badgesHaveFiniteBoundedRatiosAndTransparentDecodeBudget() {
        assertEquals(4f,BadgeArtwork.ratio(400f,100f),0f)
        assertEquals(.25f,BadgeArtwork.ratio(1f,10000f),0f)
        assertEquals(12f,BadgeArtwork.ratio(10000f,1f),0f)
        for(value in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertEquals(3f,BadgeArtwork.ratio(value,10f),0f)
            assertEquals(3f,BadgeArtwork.ratio(10f,value),0f)
        }
        assertTrue(BadgeArtwork.MAX_WIDTH*BadgeArtwork.MAX_HEIGHT*4<=256*1024)
    }
    @Test fun appArtworkKeysSeparatePackageRevisionStyleAndConfiguration() {
        val key=AppArtworkKey("package",1,false,"320:night:en")
        assertNotEquals(key,key.copy(pkg="other"));assertNotEquals(key,key.copy(revision=2))
        assertNotEquals(key,key.copy(circle=true));assertNotEquals(key,key.copy(configuration="320:day:en"))
    }
    @Test fun weightedCacheEvictsByBytesAndRetainsRecentlyReadEntries() {
        val cache=WeightedArtworkCache<String,String>(6){it.length}
        cache.put("a","aaa");cache.put("b","bbb");assertEquals("aaa",cache.get("a"))
        cache.put("c","ccc");assertNull(cache.get("b"));assertEquals("aaa",cache.get("a"));assertEquals(6,cache.bytes)
    }
    @Test fun replacingOrOversizedArtworkDoesNotLeakCacheWeight() {
        val cache=WeightedArtworkCache<String,String>(6){it.length}
        cache.put("a","aaa");cache.put("a","x");assertEquals(1,cache.bytes)
        cache.put("a","1234567");assertNull(cache.get("a"));assertEquals(0,cache.bytes)
        cache.put("b","1234567");assertNull(cache.get("b"));assertEquals(0,cache.bytes)
    }
    @Test fun clearingDerivedArtworkDropsOnlyCacheReferencesAndIsReusable() {
        val cache=WeightedArtworkCache<String,String>(10){it.length};val visible="still visible"
        cache.put("a","image");cache.clear();assertEquals(0,cache.bytes);assertNull(cache.get("a"))
        assertEquals("still visible",visible);cache.put("b","new");assertEquals("new",cache.get("b"))
    }
    @Test fun bannerRasterizationIsBoundedAspectPreservingAndDoesNotUpscale() {
        assertEquals(320 to 180,ArtworkStorageBudget.banner(0,0))
        assertEquals(640 to 360,ArtworkStorageBudget.banner(3840,2160))
        assertEquals(160 to 90,ArtworkStorageBudget.banner(160,90))
        for(size in listOf(99999 to 1,1 to 99999,Int.MAX_VALUE to Int.MAX_VALUE,-1 to -8,900 to 1300)) {
            val result=ArtworkStorageBudget.banner(size.first,size.second)
            assertTrue(result.first in 1..640);assertTrue(result.second in 1..640)
        }
    }
    @Test fun artworkCacheBudgetsAreBoundedForSmallUnknownAndLargeDevices() {
        val mib=1024L*1024
        assertEquals(128*mib,ArtworkStorageBudget.disk(0))
        assertEquals(16*mib,ArtworkStorageBudget.disk(100*mib))
        assertEquals(100*mib,ArtworkStorageBudget.disk(2000*mib))
        assertEquals(512*mib,ArtworkStorageBudget.disk(Long.MAX_VALUE))
        assertEquals(4*mib,ArtworkStorageBudget.apps(1024,true).toLong())
        assertEquals(4*mib,ArtworkStorageBudget.apps(0,false).toLong())
        assertEquals(8*mib,ArtworkStorageBudget.apps(256,false).toLong())
        assertEquals(12*mib,ArtworkStorageBudget.apps(Int.MAX_VALUE,false).toLong())
    }
    @Test fun highQualityHeroesCanUseGraphicsBitmapsOnlyWithoutPixelConsumers() {
        val c=CustomPerformance(heroAnalysis=false,colorAnalysis=false)
        val high=RenderOptions(quality="high",custom=c)
        assertTrue(high.hardwareHero);assertEquals("gpu",high.heroStorageKey)
        assertFalse(high.copy(glass=true).hardwareHero)
        assertFalse(high.copy(custom=c.copy(heroAnalysis=true)).hardwareHero)
        assertFalse(high.copy(quality="economy").hardwareHero)
        assertFalse(RenderOptions(device="low",quality="economy").hardwareHero)
        assertFalse(RenderOptions().hardwareHero)
    }
    @Test fun softwareAndGraphicsHeroCachesCannotCollideAcrossSettingChanges() {
        val gpu=RenderOptions(custom=CustomPerformance(heroAnalysis=false))
        val cpu=gpu.copy(glass=true);val economy=RenderOptions(quality="economy")
        assertNotEquals(gpu.heroStorageKey,cpu.heroStorageKey)
        assertNotEquals(cpu.heroStorageKey,economy.heroStorageKey)
        assertEquals("cpu32",cpu.heroStorageKey);assertEquals("cpu16",economy.heroStorageKey)
    }
    @Test fun fallbackClockDelayIsAlignedToMinutesIncludingNegativeAndExtremeTimestamps() {
        assertEquals(60000L,untilNextClockMinute(0));assertEquals(1L,untilNextClockMinute(59999))
        assertEquals(59000L,untilNextClockMinute(61000));assertEquals(1L,untilNextClockMinute(-1))
        for(t in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-60000L,1L,123456789L))assertTrue(untilNextClockMinute(t) in 1..60000L)
    }
    @Test fun imageCacheSearchLinksToTheProtectedDiagnosticsPage() {
        val hit=SettingDefinitions.matching("artwork cache").single()
        assertEquals("image-cache",hit.id);assertEquals(SettingsTab.DEBUG,hit.tab)
    }
}
