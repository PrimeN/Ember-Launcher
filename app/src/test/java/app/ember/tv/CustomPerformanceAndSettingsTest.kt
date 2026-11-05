package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.SettingsAccess
import app.ember.tv.ui.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomPerformanceAndSettingsTest {
    @get:Rule val temp=TemporaryFolder()
    private val big=4L*1024*1024*1024
    private fun custom(c:CustomPerformance,quality:String="high",lowRam:Boolean=false)=
        deviceRenderOptions("standard",true,quality,"custom",lowRam,256,big,c)
    @Test fun customModeNormalizesAndUsesConservativeAutomaticRefreshBudget() {
        assertEquals("custom",DevicePerformance.normalize("custom"))
        assertEquals("low",DevicePerformance.resolve("custom",true,128,big))
        assertEquals("high",DevicePerformance.resolve("custom",false,256,big))
    }
    @Test fun sharpArtCanDisableEveryAnalysisWithoutForcingEconomy() {
        val r=custom(CustomPerformance(heroAnalysis=false,colorAnalysis=false))
        assertEquals("high",r.quality);assertTrue(r.preciseHero)
        assertEquals(1920 to 1080,r.heroSize(3840,2160))
        assertFalse(r.heroAnalysis);assertFalse(r.colorAnalysis)
    }
    @Test fun memorySensitiveDeviceCanChooseSharpArtWithoutPermittingBlur() {
        val r=custom(CustomPerformance(heroAnalysis=false,colorAnalysis=false),lowRam=true)
        assertTrue(r.preciseHero);assertFalse(r.glass);assertFalse(r.heroAnalysis)
    }
    @Test fun motionOffBypassesFadesAndFocusScaleButKeepsOtherCustomEffects() {
        val r=custom(CustomPerformance(animations=false))
        assertEquals(0,r.animationMillis(240,false));assertEquals(1f,r.focusScale(1.05f,false),0f)
        assertEquals(0,PolishMotion.duration(160,r,false))
        assertTrue(r.heroAnalysis);assertTrue(r.collectionMotion)
    }
    @Test fun reducedMotionAlwaysWinsEvenIfCustomMotionIsOn() {
        val r=custom(CustomPerformance())
        assertEquals(0,r.animationMillis(240,true));assertEquals(1f,r.focusScale(1.05f,true),0f)
        assertEquals(0,PolishMotion.duration(160,r,true))
    }
    @Test fun progressiveAndCollectionControlsAreIndependent() {
        val a=custom(CustomPerformance(progressiveArtwork=false,collectionMotion=true))
        val b=custom(CustomPerformance(progressiveArtwork=true,collectionMotion=false))
        assertFalse(a.progressiveHero);assertTrue(a.collectionMotion)
        assertTrue(b.progressiveHero);assertFalse(b.collectionMotion)
    }
    @Test fun customPrefetchOffDisablesHeroMetadataAndImagePrefetch() {
        val r=custom(CustomPerformance(prefetch=0))
        assertEquals(0,r.prefetchCount);assertEquals(0,r.heroPrefetchCount)
        assertEquals(1,RenderOptions(quality="economy").heroPrefetchCount)
    }
    @Test fun prefetchIsBoundedForRestoredAndUntrustedValues() {
        assertEquals(0,custom(CustomPerformance(prefetch=-20)).prefetchCount)
        assertEquals(2,custom(CustomPerformance(prefetch=9000)).prefetchCount)
        assertEquals(0,CustomPerformance(prefetch=-1).normalized().prefetch)
        assertEquals(2,CustomPerformance(prefetch=100).normalized().prefetch)
    }
    @Test fun presetsIgnoreSavedCustomControls() {
        val c=CustomPerformance(heroAnalysis=false,colorAnalysis=false,animations=false,prefetch=0)
        val high=deviceRenderOptions("standard",true,"high","high",false,256,big,c)
        assertTrue(high.heroAnalysis);assertTrue(high.colorAnalysis);assertTrue(high.animationsEnabled)
        assertEquals(2,high.prefetchCount)
        val low=deviceRenderOptions("standard",true,"high","low",false,256,big,c)
        assertEquals("economy",low.quality);assertFalse(low.heroAnalysis)
    }
    @Test fun customControlsSurviveRestartAndBackupWithExistingImageSettings() {
        val from=PersistenceContext(temp.newFolder());val p=Prefs(from)
        val c=CustomPerformance(false,false,false,false,false,0)
        p.devicePerformance="custom";p.customPerformance=c;p.artworkQuality="high";p.navigationGlass=false
        assertEquals(c,Prefs(from).customPerformance)
        val payload=BackupPersistence(from,FixtureSecrets()).snapshot()
        val to=PersistenceContext(temp.newFolder());BackupPersistence(to,FixtureSecrets()).restore(payload)
        val loaded=Prefs(to)
        assertEquals("custom",loaded.devicePerformance);assertEquals(c,loaded.customPerformance)
        assertEquals("high",loaded.artworkQuality);assertFalse(loaded.navigationGlass)
    }
    @Test fun oldAndMalformedStoredControlsUseSafeDefaultsAndPartialJsonGetsNewDefaults() {
        val context=PersistenceContext(temp.newFolder());val p=Prefs(context)
        assertEquals(CustomPerformance(),p.customPerformance)
        val sp=context.getSharedPreferences("ember",0)
        sp.edit().putString("customPerformance","bad").commit();assertEquals(CustomPerformance(),p.customPerformance)
        sp.edit().putString("customPerformance","""{"heroAnalysis":false,"future":true}""").commit()
        assertEquals(CustomPerformance(heroAnalysis=false),p.customPerformance)
    }
    @Test fun invalidCustomBackupFailsBeforeMutatingExistingPreferences() {
        val context=PersistenceContext(temp.newFolder());val backup=BackupPersistence(context,FixtureSecrets())
        val original=backup.snapshot();val prefs=original.getValue("prefs.ember").jsonObject
        val bad=JsonObject(original+("prefs.ember" to JsonObject(prefs+("customPerformance" to buildJsonObject {put("t","s");put("v","broken json")}))))
        assertTrue(runCatching {backup.restore(bad)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(original,backup.snapshot())
    }
    @Test fun performanceSearchFindsTheExactControlsInEitherInterface() {
        val found=SettingDefinitions.matching("performance analysis").single()
        assertEquals("device-performance",found.id)
        assertTrue(found.id in appearanceKeys("modern"));assertTrue(found.id in appearanceKeys("light"))
    }
    private class Guard {
        var profile=Profile("child","Child",kids=true)
        var hash:String?="fixture-hash"
        var pending:(()->Unit)?=null
        var prompts=0;var opens=0;var notices=0
        val gate=SettingsAccess({profile},{hash},{_,go->prompts++;pending=go},{notices++})
        fun open()=gate.open {opens++}
        fun approve() {val action=pending;pending=null;action?.invoke()}
    }
    @Test fun adultAccessRequiresNoPin() {
        val g=Guard();g.profile=Profile("adult","Adult");g.hash=null
        assertTrue(g.gate.allowed);g.open();assertEquals(1,g.opens);assertEquals(0,g.prompts)
    }
    @Test fun childWithoutAnyPinIsBlockedRatherThanSilentlyAuthorized() {
        for(hash in listOf(null,""," ")) {
            val g=Guard();g.hash=hash;assertFalse(g.gate.allowed);g.open()
            assertEquals(0,g.opens);assertEquals(0,g.prompts);assertEquals(1,g.notices)
        }
    }
    @Test fun childWaitsForApprovalAndCanNavigateDuringThatVisit() {
        val g=Guard();g.open();assertFalse(g.gate.allowed);assertEquals(0,g.opens)
        g.approve();assertTrue(g.gate.allowed);assertEquals(1,g.opens)
        g.open();assertEquals(2,g.opens);assertEquals(1,g.prompts)
    }
    @Test fun canceledPinDoesNotGrantSettingsAccess() {
        val g=Guard();g.open();g.pending=null
        assertFalse(g.gate.allowed);assertEquals(0,g.opens)
    }
    @Test fun leavingSettingsRevokesApprovalAndRequiresAnotherPin() {
        val g=Guard();g.open();g.approve();g.gate.revoke()
        assertFalse(g.gate.allowed);g.open();assertEquals(2,g.prompts);assertEquals(1,g.opens)
    }
    @Test fun profileOrPinChangeInvalidatesAnOpenVisit() {
        val g=Guard();g.open();g.approve()
        g.profile=g.profile.copy(id="other");assertFalse(g.gate.allowed)
        g.profile=g.profile.copy(id="child");g.hash="replacement";assertFalse(g.gate.allowed)
    }
    @Test fun staleApprovalCannotOpenSettingsForADifferentProfile() {
        val g=Guard();g.open();g.profile=g.profile.copy(id="other");g.approve()
        assertEquals(0,g.opens);assertFalse(g.gate.allowed)
    }
    @Test fun staleApprovalCannotSurvivePinReplacementOrRemoval() {
        for(next in listOf(null,"replacement")) {
            val g=Guard();g.open();g.hash=next;g.approve()
            assertEquals(0,g.opens);assertFalse(g.gate.allowed)
        }
    }
    @Test fun revokedPendingApprovalCannotReopenAClosedOrBackgroundedVisit() {
        val g=Guard();g.open();g.gate.revoke();g.approve()
        assertEquals(0,g.opens);assertFalse(g.gate.allowed)
    }
    @Test fun onlyTheLatestPinRequestCanAuthorizeTheVisit() {
        val g=Guard();g.open();val old=g.pending;g.open();old!!.invoke()
        assertEquals(0,g.opens);g.approve();assertEquals(1,g.opens)
    }
    @Test fun adminEditingTheCurrentChildProfileInvalidatesTheOldVisitStamp() {
        val g=Guard();g.open();g.approve();g.profile=g.profile.copy(name="Updated")
        assertFalse(g.gate.allowed)
    }
    @Test fun newGuardNeverRestoresAnEarlierApprovedVisit() {
        val first=Guard();first.open();first.approve();assertTrue(first.gate.allowed)
        assertFalse(Guard().gate.allowed)
    }
}
