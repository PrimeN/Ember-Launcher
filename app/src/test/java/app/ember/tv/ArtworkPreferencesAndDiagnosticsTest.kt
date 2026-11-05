package app.ember.tv

import android.content.Context
import androidx.compose.ui.unit.dp
import app.ember.tv.data.*
import app.ember.tv.ui.detailsActionWidth
import java.net.SocketTimeoutException
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ArtworkPreferencesAndDiagnosticsTest {
    @get:Rule val temp=TemporaryFolder()
    @Before fun clear(){SourceDiagnostics.clear()}
    @After fun cleanup(){SourceDiagnostics.clear()}

    @Test fun modernPlayWidthFitsShortTextAndRetainsLongResumeDetails() {
        assertEquals(89.dp,detailsActionWidth(35.dp,0.dp,true))
        assertEquals(72.dp,detailsActionWidth(40.dp,0.dp,false))
        assertEquals(184.dp,detailsActionWidth(80.dp,160.dp,true))
        assertEquals(224.dp,detailsActionWidth(400.dp,500.dp,true))
    }
    @Test fun visibleAppArtworkIsLazyAndPreservesActivityBannerPriority() {
        val calls=mutableListOf<String>()
        assertEquals("banner",visibleAppArtwork(false,
            {calls+="banner";"banner"},{calls+="application";"other"},{calls+="provided";null},
            {calls+="icon";"icon"},{calls+="app icon";"icon"},{calls+="fallback";"default"}))
        assertEquals(listOf("banner"),calls)
    }
    @Test fun circlesDoNotLoadUnusedBannersAndBrokenMetadataKeepsFallbacks() {
        var banner=0
        assertEquals("icon",visibleAppArtwork(true,{banner++;"banner"},{banner++;"banner"},{null},
            {throw IllegalStateException("broken activity icon")},{"icon"},{"fallback"}))
        assertEquals(0,banner)
        assertEquals("fallback",visibleAppArtwork(false,{null},{null},{null},{null},{null},{"fallback"}))
    }
    @Test fun typedPreferenceDefaultsAndLegacyProbesRetainTheirBehavior() {
        val context=PersistenceContext(temp.newFolder());val p=Prefs(context)
        assertEquals("night",p.theme);assertEquals("circle",p.appIcons);assertEquals(1,p.homeAppRows)
        assertEquals("modern",p.uiStyle);assertEquals(10,p.addonTimeout)
        assertFalse(p.updateBeta);assertEquals("releases",p.updateChannel)
        context.getSharedPreferences("ember",Context.MODE_PRIVATE).edit().putString("updateChannel","beta").commit()
        assertTrue(p.updateBeta);assertEquals("builds",p.updateChannel)
        p.updateBeta=false;assertFalse(Prefs(context).updateBeta)
        assertEquals(PreferenceKeys.all.size,PreferenceKeys.all.map {it.name}.toSet().size)
    }
    @Test fun typedRegistryAndBackupGuardShareEveryFixedPreferenceType() {
        for(key in PreferenceKeys.all) assertEquals(key.type.tag,PreferenceKeys.expectedType("ember",key.name))
        assertEquals("s",PreferenceKeys.expectedType("ember","explore:fixture"))
        assertEquals("b",PreferenceKeys.expectedType("ember","search:fixtureenabled"))
        assertEquals("s",PreferenceKeys.expectedType("ember","search:fixture"))
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        prefs.theme="day";prefs.heroOn=false;prefs.updateBeta=true;prefs.homeAppRows=2
        prefs.playbackConfig=prefs.playbackConfig.copy(playerInterface="ember_x")
        val source=PersistenceContext(temp.newFolder());val data=Prefs(source)
        data.theme=prefs.theme;data.heroOn=prefs.heroOn;data.updateBeta=prefs.updateBeta;data.homeAppRows=prefs.homeAppRows;data.playbackConfig=prefs.playbackConfig
        val target=PersistenceContext(temp.newFolder())
        BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
        val restored=Prefs(target)
        assertEquals("day",restored.theme);assertFalse(restored.heroOn);assertTrue(restored.updateBeta)
        assertEquals(2,restored.homeAppRows);assertEquals("ember_x",restored.playbackConfig.playerInterface)
    }
    @Test fun timingUsesElapsedDurationAndRenamingDoesNotResetTheAttempt() {
        val health=SourceHealth(readSaved={null},saveSuccess={_,_->}).apply {diagnosticGroup="search"}
        var elapsed=100L;health.elapsedMillis={elapsed}
        health.start("https://private.test/token","a private user name")
        elapsed+=250;health.rename("https://private.test/token","Changed label")
        elapsed+=50;health.finish("https://private.test/token",false,true,10,SourceFailure.TIMEOUT)
        assertEquals(300L,health.entries.values.single().lastDurationMillis)
        val diagnostic=SourceDiagnostics.records.value.single()
        assertEquals(300L,diagnostic.durationMillis);assertEquals(SourceFailure.TIMEOUT,diagnostic.failure)
        val exported=SourceDiagnostics.export()
        for(secret in listOf("private.test","token","private user","Changed label","https://"))assertFalse(secret,secret in exported)
        assertTrue("timeout" in exported);assertTrue("300" in exported)
    }
    @Test fun diagnosticsBoundHistoryAndDoNotRecordCancelledAttempts() {
        val health=SourceHealth(readSaved={null},saveSuccess={_,_->})
        for(i in 0..99){health.start("private-$i","private name");health.finish("private-$i",true,false)}
        assertEquals(64,SourceDiagnostics.records.value.size)
        SourceDiagnostics.clear();health.start("cancelled","hidden");health.cancelLoading()
        assertTrue(SourceDiagnostics.records.value.isEmpty())
    }
    @Test fun failureCategoriesUseTypedStatusRatherThanPrivateExceptionText() {
        assertEquals(SourceFailure.ACCESS,SourceFailure.from(AddonHttpException(403)))
        assertEquals(SourceFailure.QUOTA,SourceFailure.from(AddonHttpException(429)))
        assertEquals(SourceFailure.TIMEOUT,SourceFailure.from(SocketTimeoutException("private url")))
        assertEquals(SourceFailure.QUOTA,SourceFailure.from(app.ember.tv.iptv.IptvHttpException(429)))
        assertEquals(SourceFailure.UNAVAILABLE,SourceFailure.from(Exception("https://private.test/token=secret")))
    }
}
