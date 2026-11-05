package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.*
import app.ember.tv.discover.*
import app.ember.tv.iptv.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ApprovedEnhancementsTest {
    private val big=4L*1024*1024*1024
    @Test fun slowEvidenceNeedsTwoConsecutiveSustainedWindows() {
        val e=FrameBudgetEvidence(10,10)
        repeat(9){assertFalse(e.add(30))};assertFalse(e.add(30))
        repeat(9){assertFalse(e.add(30))};assertTrue(e.add(30))
    }
    @Test fun invalidAndTransientFramesDoNotRetuneAuto() {
        val e=FrameBudgetEvidence(10,10)
        repeat(100){assertFalse(e.add(-1));assertFalse(e.add(0));assertFalse(e.add(Long.MAX_VALUE))}
        repeat(10){assertFalse(e.add(30))}
        repeat(10){assertFalse(e.add(10))}
        repeat(10){assertFalse(e.add(30))}
    }
    @Test fun observedSlowAutoKeepsHighImageQualityAndDimensions() {
        val options=deviceRenderOptions("dense",true,"high","auto",false,512,big,observedSlow=true)
        assertEquals("high",options.quality);assertEquals(1920,options.heroMaxWidth)
        assertFalse(options.glass);assertFalse(options.heroAnalysis);assertFalse(options.animationsEnabled)
        assertEquals(0,options.heroPrefetchCount)
    }
    @Test fun frameEvidenceNeverOverridesManualHighOrCustom() {
        for(mode in listOf("high","custom","low")) {
            assertEquals(deviceRenderOptions("standard",true,"high",mode,false,512,big),
                deviceRenderOptions("standard",true,"high",mode,false,512,big,observedSlow=true))
        }
    }
    @Test fun effectPresetsKeepExplicitQualityIndependent() {
        for(preset in EffectPresets.options)for(quality in listOf("economy","balanced","high")) {
            val result=deviceRenderOptions("standard",preset.glass,quality,"custom",false,512,big,preset.effects)
            assertEquals(quality,result.quality);assertEquals(preset.effects,result.custom)
        }
    }
    @Test fun liftKeepsStaticFocusFallbackOnReducedDevices() {
        assertEquals("lift",CardFocusStyle.normalize("lift"))
        assertTrue(CardFocusStyle.outline(false,"lift",true,false))
        assertFalse(CardFocusStyle.pulse("lift",false,false,true,true))
    }
    private val channel=Channel("a","source","News","fixture")
    private fun p(title:String,start:Long=900,stop:Long=1100)=Programme(start,stop,title)
    @Test fun programmeSearchIncludesCurrentAndFutureButNotExpiredOrHiddenChannels() {
        val hidden=channel.copy(key="hidden")
        val guide=mapOf("a" to listOf(p("News past",100,999),p("News now"),p("News tonight",2000,3000)),
            "hidden" to listOf(p("News hidden")))
        val result=ProgrammeSearch.find("news",listOf(channel),guide,1000)
        assertEquals(listOf("News now","News tonight"),result.hits.map {it.programme.title})
        assertFalse(result.limited);assertTrue(hidden.key !in result.hits.map {it.channel.key})
    }
    @Test fun programmeSearchMatchesEpisodeSubtitlesCaseInsensitively() {
        val result=ProgrammeSearch.find("BROTHERS",listOf(channel),mapOf("a" to listOf(p("Star Trek").copy(subtitle="Brothers"))),1000)
        assertEquals(1,result.hits.size)
        assertTrue(ProgrammeSearch.find("b",listOf(channel),emptyMap(),1000).hits.isEmpty())
    }
    @Test fun searchRetainsEarliestMatchesRegardlessOfFeedOrderAndCapsOutput() {
        val items=(1..1000).map {p("Film $it",2000L+it,4000L+it)}.reversed()
        val result=ProgrammeSearch.find("film",listOf(channel),mapOf("a" to items),1000,limit=5)
        assertEquals(listOf("Film 1","Film 2","Film 3","Film 4","Film 5"),result.hits.map {it.programme.title})
        assertTrue(result.limited)
    }
    @Test fun searchHasBoundedWorkAndCancellationCheckpoints() {
        var checks=0
        val result=ProgrammeSearch.find("film",listOf(channel),mapOf("a" to List(500){p("Film")}),1000,budget=129,checkCancelled={checks++})
        assertTrue(result.limited);assertEquals(2,checks)
    }
    @Test(expected=java.util.concurrent.CancellationException::class)
    fun cancelledProgrammeSearchDoesNotPublishPartialResults() {
        ProgrammeSearch.find("film",listOf(channel),mapOf("a" to List(500){p("Film")}),1000,
            checkCancelled={throw java.util.concurrent.CancellationException()})
    }
    @Test fun tonightRespectsLocalZoneAndDoesNotJumpIntoTomorrowAfterEight() {
        val before=Instant.parse("2026-10-28T19:00:00Z").toEpochMilli()
        val after=Instant.parse("2026-10-29T02:00:00Z").toEpochMilli()
        val zone=ZoneId.of("America/Toronto")
        assertEquals(Instant.parse("2026-10-29T00:00:00Z").toEpochMilli(),ProgrammeSearch.tonight(before,zone))
        assertEquals(after,ProgrammeSearch.tonight(after,zone))
    }
    @Test fun recoveryChangesOnlyChosenGroupAndPreservesSortingAndType() {
        val q=ExploreQuery(kind=ExploreKind.MOVIE,sort=ExploreSort.NEWEST,genres=setOf("drama"),allGenres=true,
            fromYear=1990,minRating=8.0,providers=listOf(ExploreRef(1,"Service")),includeRentBuy=true,oscars=OscarMode.WIN)
        val choices=ExploreRecovery.choices(q)
        assertEquals(q.copy(providers=emptyList(),includeRentBuy=false),choices.single {it.label=="Any streaming service"}.query)
        assertEquals(q.copy(genres=emptySet(),allGenres=false),choices.single {it.label=="Any genre"}.query)
        assertTrue(choices.all {it.query.kind==q.kind && it.query.sort==q.sort})
        assertEquals(5,choices.size)
    }
    @Test fun emptyDefaultSearchHasNoPretendConflictingGroups() {
        assertTrue(ExploreRecovery.choices(ExploreQuery()).isEmpty())
        assertFalse(ExploreRecovery.explanation(ExploreQuery(),"CA",false).contains("providers may"))
        val text=ExploreRecovery.explanation(ExploreQuery(providers=listOf(ExploreRef(1,"Service")),oscars=OscarMode.WIN),"ca",true)
        assertTrue(text.contains("CA"));assertTrue(text.contains("award records"));assertTrue(text.contains("available provider pages"))
    }
    @Test fun ratingRecoveryRetainsThresholdsUntilThatGroupIsChosen() {
        val q=ExploreQuery(sort=ExploreSort.RATING,minRating=8.0)
        assertEquals(500,ExploreRecovery.choices(q).first().query.minMovieVotes)
        val votes=ExploreRecovery.choices(q).last().query
        assertEquals(0,votes.minMovieVotes);assertEquals(8.0,votes.minRating)
    }
    @Test fun blurPreservesUniformColourAlphaAndInputs() {
        val source=IntArray(12){0xff56789a.toInt()};val copy=source.copyOf()
        assertArrayEquals(source,FrostBlur.apply(source,4,3,4));assertArrayEquals(copy,source)
        assertArrayEquals(intArrayOf(0x80010203.toInt()),FrostBlur.apply(intArrayOf(0x80010203.toInt()),1,1,32))
    }
    @Test fun blurClampsEdgesAndWorksOnNarrowImages() {
        val source=intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xff000000.toInt())
        assertArrayEquals(IntArray(3){0xff555555.toInt()},FrostBlur.apply(source,3,1,1))
        assertArrayEquals(IntArray(3){0xff555555.toInt()},FrostBlur.apply(source,1,3,1))
    }
    @Test fun zeroBlurCopiesAndExtremeRadiusIsBounded() {
        val source=intArrayOf(1,2)
        assertArrayEquals(source,FrostBlur.apply(source,2,1,0));assertNotSame(source,FrostBlur.apply(source,2,1,0))
        assertArrayEquals(FrostBlur.apply(source,2,1,32),FrostBlur.apply(source,2,1,Int.MAX_VALUE))
    }
    @Test(expected=IllegalArgumentException::class)fun blurRejectsInvalidDimensions() {FrostBlur.apply(IntArray(2),2,2,1)}
    @Test fun setupSearchHasConcreteDestination() {
        val target=SettingDefinitions.matching("setup checklist").single()
        assertEquals(SettingsTab.FEATURES,target.tab);assertEquals("setup-checklist",target.id)
    }
}
