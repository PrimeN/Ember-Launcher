package app.ember.tv

import app.ember.tv.data.DevicePerformance
import app.ember.tv.ui.*
import app.ember.tv.iptv.*
import org.junit.Assert.*
import org.junit.Test

class DevicePerformanceTest {
    private val big=4L*1024*1024*1024
    @Test fun autoIsConservativeForLowOrUnknownMemoryAndCanBeOverridden() {
        assertEquals("low",DevicePerformance.resolve("auto",true,256,big))
        assertEquals("low",DevicePerformance.resolve("auto",false,128,big))
        assertEquals("low",DevicePerformance.resolve("auto",false,256,2L*1024*1024*1024))
        assertEquals("low",DevicePerformance.resolve("auto",false,0,0))
        assertEquals("high",DevicePerformance.resolve("auto",false,256,big))
        assertEquals("high",DevicePerformance.resolve("high",true,128,big))
        assertEquals("low",DevicePerformance.resolve("low",false,512,big))
        assertEquals("auto",DevicePerformance.normalize("future-mode"))
    }
    @Test fun lowProfileCapsEffectsWithoutOverwritingRequestedQuality() {
        val low=deviceRenderOptions("comfortable",true,"high","low",false,512,big)
        assertEquals("economy",low.quality);assertEquals(0,low.prefetchCount);assertFalse(low.glass);assertFalse(low.preciseHero)
        assertEquals("comfortable",low.density)
        val high=deviceRenderOptions("comfortable",true,"high","high",false,512,big)
        assertEquals("high",high.quality);assertEquals(2,high.prefetchCount);assertTrue(high.glass);assertTrue(high.preciseHero)
    }
    @Test fun manualHighRetainsHardwareSafetyAndCustomKeepsExplicitEconomy() {
        val forced=deviceRenderOptions("standard",true,"high","high",true,128,big)
        assertFalse(forced.glass)
        val economy=deviceRenderOptions("standard",true,"economy","custom",false,512,big)
        assertFalse(economy.preciseHero);assertEquals("economy",economy.quality)
        assertEquals("balanced",deviceRenderOptions("standard",false,"invalid","custom",false,512,big).quality)
    }
    @Test fun eachProfileSetsQualityEffectsAndGlassTogether() {
        val low=deviceRenderOptions("standard",true,"high","low",false,512,big)
        assertEquals("economy",low.quality);assertEquals(GLASS_STATIC,low.glassTier)
        val medium=deviceRenderOptions("standard",true,"high","medium",false,512,big)
        assertEquals("balanced",medium.quality);assertEquals(GLASS_CACHED,medium.glassTier);assertFalse(medium.heroAnalysis);assertTrue(medium.animationsEnabled)
        val high=deviceRenderOptions("standard",true,"economy","high",false,512,big)
        assertEquals("high",high.quality);assertEquals(GLASS_LIVE,high.glassTier);assertEquals(1920,high.heroMaxWidth);assertFalse(high.ultra)
        val ultra=deviceRenderOptions("standard",true,"economy","ultra",false,512,big,uhdDisplay=true)
        assertTrue(ultra.ultra);assertEquals(3840,ultra.heroMaxWidth);assertEquals(2160,ultra.heroMaxHeight)
        // Without a 4K display Ultra behaves as High and is not offered.
        assertFalse(deviceRenderOptions("standard",true,"high","ultra",false,512,big,uhdDisplay=false).ultra)
        assertFalse(DevicePerformance.choices(false).any {it.first=="ultra"})
        assertTrue(DevicePerformance.choices(true).any {it.first=="ultra"})
    }
    @Test fun autoPicksLowMediumOrHighButNeverUltra() {
        assertEquals("low",DevicePerformance.profile("auto",true,512,big,true))
        assertEquals("medium",DevicePerformance.profile("auto",false,192,3L*1024*1024*1024,true))
        assertEquals("high",DevicePerformance.profile("auto",false,256,big,true))
        assertEquals("medium",DevicePerformance.normalize("medium"))
    }
    @Test fun artworkBoundsNeverUpscaleOrExceedProfileMemoryBudget() {
        for(mode in listOf("low","high")) {
            val options=deviceRenderOptions("standard",true,"high",mode,false,512,big)
            for(size in listOf(3840 to 2160,7680 to 4320,1920 to 1080,640 to 360,0 to 0,-1 to -9,300 to 2000)) {
                val result=options.heroSize(size.first,size.second)
                assertTrue(result.first in 1..options.heroMaxWidth);assertTrue(result.second in 1..options.heroMaxHeight)
                assertTrue(result.first<=size.first.coerceAtLeast(1));assertTrue(result.second<=size.second.coerceAtLeast(1))
            }
            assertEquals(640 to 360,options.heroSize(640,360))
            assertEquals(options.heroMaxWidth to options.heroMaxHeight,options.heroSize(3840,2160))
        }
    }
    @Test fun performanceSearchLinksToBothInterfacesExactSection() {
        val hit=SettingDefinitions.matching("device performance").single()
        assertEquals("device-performance",hit.id)
        assertTrue(hit.id in appearanceKeys("modern"));assertTrue(hit.id in appearanceKeys("light"))
        assertEquals(DevicePerformance.options,hit.options)
    }
    @Test fun ambiguousAliasRemovalKeepsOtherSafeAliasesButNotDuplicateReferences() {
        val index=EpgNameIndex(setOf("one","two"))
        index.add("One HD","a");index.add("One","a");index.add("Two","a")
        assertTrue(index.containsId("a"))
        index.add("One","b")
        assertTrue(index.containsId("a"));assertFalse(index.containsId("b"));assertFalse("one" in index.names)
        index.add("Two","c")
        assertFalse(index.containsId("a"));assertFalse(index.containsId("c"))
        index.add("One","d");index.add("Other","z")
        assertTrue(index.names.isEmpty());assertFalse(index.containsId("d"));assertFalse(index.containsId(null))
    }
    @Test fun indexedGuideMembershipMatchesLegacyLookupOnALargeFixture() {
        val count=3000
        val wanted=(0 until count).mapTo(hashSetOf()){Xmltv.norm("Channel $it")}
        val index=EpgNameIndex(wanted)
        repeat(count){index.add("Channel $it","id$it")}
        val queries=(0 until 12000).map {"id${(it*997)%(count+10)}"}
        var oldCount=0;var newCount=0
        val oldStart=System.nanoTime()
        for(id in queries)if(id in index.names.values)oldCount++
        val scan=System.nanoTime()-oldStart
        val newStart=System.nanoTime()
        for(id in queries)if(index.containsId(id))newCount++
        val lookup=System.nanoTime()-newStart
        assertEquals(oldCount,newCount)
        println("EPG_NAME_LOOKUP_BENCHMARK {\"aliases\":$count,\"lookups\":${queries.size},\"scanNanos\":$scan,\"indexNanos\":$lookup}")
    }
}
