package app.ember.tv

import androidx.compose.ui.graphics.luminance

import app.ember.tv.data.*
import app.ember.tv.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.pow

class WatchedPresentationTest {
    @get:Rule val temp=TemporaryFolder()
    private val movie=Meta(id="movie",type="movie",name="Movie",imdbId="tt1234567",tmdbId=42)
    private val show=movie.copy(id="show",type="series",name="Show")
    private fun ep(number:Int)=show.copy(season=1,episode=number)

    @Test fun movieAndShowIdsNeverShareWatchedStatus() {
        val index=WatchedIndex().changed(movie,true)
        assertTrue(index.watched(movie))
        assertFalse(index.watched(show))
    }
    @Test fun knownPublicIdsWorkAcrossCatalogProviders() {
        val index=WatchedIndex().changed(movie,true)
        assertTrue(index.watched(movie.copy(id="other-provider")))
        assertTrue(index.watched(movie.copy(id="tmdb:movie:42",imdbId=null,tmdbId=null)))
        assertFalse(index.watched(movie.copy(id="tmdb:tv:42",type="series",imdbId=null,tmdbId=null)))
    }
    @Test fun anEpisodeNeverMarksTheWholeShowOrAnotherEpisodeComplete() {
        val index=WatchedIndex().changed(ep(1),true)
        assertTrue(index.watched(ep(1)))
        assertFalse(index.watched(ep(2)))
        assertFalse(index.watched(show))
    }
    @Test fun wholeShowMarksApplyToEpisodesAndExplicitEpisodeOverridesWork() {
        val all=WatchedIndex().changed(ep(1),false).changed(show,true)
        assertTrue(all.watched(show));assertTrue(all.watched(ep(1)));assertTrue(all.watched(ep(2)))
        val except=all.changed(ep(2),false)
        assertFalse(except.watched(ep(2)));assertTrue(except.watched(ep(1)))
        assertFalse(except.watched(show))
        assertTrue(except.changed(show,true).watched(ep(2)))
        val cleared=except.changed(show,false)
        assertFalse(cleared.watched(ep(1)));assertFalse(cleared.watched(show))
        assertTrue(cleared.changed(ep(1),true).watched(ep(1)))
    }
    @Test fun anUnwatchedEpisodeInvalidatesProviderWholeShowCompletion() {
        val index=WatchedIndex().changed(ep(1),false)
        assertFalse(index.watched(show.copy(watched=true)))
        assertTrue(index.watched(ep(2).copy(watched=true)))
    }
    @Test fun opaqueProviderIdsDoNotCrossSourcesWithoutVerifiedPublicIds() {
        val first=movie.copy(id="provider-a:123",imdbId=null,tmdbId=null)
        val second=first.copy(id="provider-b:123")
        val index=WatchedIndex().changed(first,true)
        assertTrue(index.watched(first));assertFalse(index.watched(second))
    }
    @Test fun inheritedServerIdentityWinsOverAnOlderOpaqueEpisodeAlias() {
        val series=Meta(id="series",type="series",name="Show",serverRef="plex|account|series")
        val native=Meta(id="episode",type="series",name="Show",serverRef="plex|account|episode",serverSeriesRef=series.serverRef,season=1,episode=2)
        val index=WatchedIndex().changed(native,true).changed(series,false)
        assertFalse(index.watched(native))
        assertTrue(index.changed(native,true).watched(native))
    }
    @Test fun providersAndExplicitLocalUnwatchedMarksHaveDefinedPriority() {
        assertTrue(WatchedIndex().watched(movie.copy(watched=true)))
        assertFalse(WatchedIndex().changed(movie,false).watched(movie.copy(watched=true,progress=1f)))
        assertTrue(WatchedIndex().changed(movie,true).watched(movie.copy(watched=false)))
        assertTrue(WatchedIndex().watched(movie.copy(progress=.93f)))
        assertFalse(WatchedIndex().watched(show.copy(progress=1f)))
    }
    @Test fun episodeMetadataUsesItsOwnCompletionAndSeriesReference() {
        val native=show.copy(serverRef="jellyfin|a|series",watched=false)
        val episode=Episode("episode",1,2,"Episode",null,"image",serverRef="jellyfin|a|episode",serverSeriesRef=native.serverRef,watched=true)
        val meta=WatchedKeys.episode(native,episode)
        assertTrue(WatchedIndex().watched(meta));assertEquals(episode.serverSeriesRef,meta.serverSeriesRef)
        assertFalse(WatchedIndex().watched(native))
    }
    @Test fun profileMarkersPersistIndependentlyAcrossStoreInstances() {
        val context=PersistenceContext(temp.newFolder());val store=WatchedStore(context)
        store.mark("parent",movie,true)
        assertTrue(WatchedStore(context).load("parent").watched(movie))
        assertFalse(store.load("child").watched(movie))
        store.mark("child",movie,false)
        assertTrue(store.load("parent").watched(movie))
    }
    @Test fun retrievedTrackerHistoryIsRememberedAfterItsRecentListChanges() {
        val context=PersistenceContext(temp.newFolder());val store=WatchedStore(context)
        assertTrue(store.learn("p",listOf(movie)).watched(movie))
        store.learn("p",emptyList())
        assertTrue(store.load("p").watched(movie))
        assertFalse(store.load("other").watched(movie))
    }
    @Test fun olderTrackerHistoryCannotOverrideAnExplicitUnwatchedChoice() {
        val context=PersistenceContext(temp.newFolder());val store=WatchedStore(context)
        store.mark("p",movie,false)
        assertFalse(store.learn("p",listOf(movie)).watched(movie))
        assertFalse(store.learn("p",listOf(show)).watched(show))
        assertTrue(store.learn("p",listOf(ep(1))).watched(ep(1)))
        assertFalse(store.load("p").watched(show))
    }
    @Test fun watchedMarksOutliveTheShortRecentHistoryWindow() {
        val context=PersistenceContext(temp.newFolder());val store=WatchedStore(context)
        repeat(LocalLibrary.MAX_TITLES+15) {i->store.mark("p",movie.copy(id="m$i",imdbId="tt${1000000+i}",tmdbId=i+1L),true)}
        assertTrue(store.load("p").watched(movie.copy(id="m0",imdbId="tt1000000",tmdbId=1)))
    }
    @Test fun markerKeysNeverPersistProviderUrlsOrEmbeddedCredentials() {
        val private=movie.copy(id="https://example.test/private?token=fixture-secret",imdbId=null,tmdbId=null)
        val context=PersistenceContext(temp.newFolder());WatchedStore(context).mark("private-profile",private,true)
        val raw=context.getSharedPreferences(WatchedStore.FILE,0).all.toString()
        assertFalse(raw.contains("fixture-secret"));assertFalse(raw.contains("example.test"));assertFalse(raw.contains("private-profile"))
        assertTrue(WatchedStore(context).load("private-profile").watched(private))
    }
    @Test fun aForgedOrStalePlaybackProfileCannotWriteAnotherProfilesMarkers() {
        assertEquals("child",WatchedStore.playProfile("child",null))
        assertEquals("child",WatchedStore.playProfile("child","child"))
        assertNull(WatchedStore.playProfile("child","parent"))
    }
    @Test fun allPresentationPreferencesAndProfileMarksRoundTripThroughBackup() {
        val from=PersistenceContext(temp.newFolder());val prefs=Prefs(from)
        assertTrue(prefs.showWatchedBadges);assertFalse(prefs.blurUnwatchedEpisodes);assertEquals(CardFocusStyle.SUBTLE,prefs.cardFocusStyle)
        prefs.showWatchedBadges=false;prefs.blurUnwatchedEpisodes=true;prefs.cardFocusStyle=CardFocusStyle.PULSE
        WatchedStore(from).mark("parent",movie,true);WatchedStore(from).mark("child",show,false)
        val to=PersistenceContext(temp.newFolder())
        BackupPersistence(to,FixtureSecrets()).restore(BackupPersistence(from,FixtureSecrets()).snapshot())
        val restored=Prefs(to)
        assertFalse(restored.showWatchedBadges);assertTrue(restored.blurUnwatchedEpisodes);assertEquals(CardFocusStyle.PULSE,restored.cardFocusStyle)
        assertTrue(WatchedStore(to).load("parent").watched(movie))
        assertFalse(WatchedStore(to).load("child").watched(show.copy(watched=true)))
    }
    @Test fun queuedPlaybackMarksAreIncludedWhenBackupStartsImmediately() {
        val from=PersistenceContext(temp.newFolder())
        WatchedStore.record(from,"p",movie)
        val snapshot=BackupPersistence(from,FixtureSecrets()).snapshot()
        val to=PersistenceContext(temp.newFolder())
        BackupPersistence(to,FixtureSecrets()).restore(snapshot)
        assertTrue(WatchedStore(to).load("p").watched(movie))
    }
    @Test fun wrongTypedMarkersAreRejectedBeforeRestoringAnyPreferences() {
        val from=PersistenceContext(temp.newFolder());val valid=BackupPersistence(from,FixtureSecrets()).snapshot()
        val to=PersistenceContext(temp.newFolder());Prefs(to).blurUnwatchedEpisodes=true
        val malformed=JsonObject(valid+("prefs.ember_watched" to buildJsonObject {put("bad",buildJsonObject {put("t","s");put("v","not-a-set")})}))
        assertTrue(runCatching {BackupPersistence(to,FixtureSecrets()).restore(malformed)}.isFailure)
        assertTrue(Prefs(to).blurUnwatchedEpisodes)
    }
    @Test fun olderBackupsWithoutTheNewStoreRestoreWithEmptyMarkers() {
        val from=PersistenceContext(temp.newFolder())
        val current=BackupPersistence(from,FixtureSecrets()).snapshot()
        val old=JsonObject(current-"prefs.ember_watched")
        val to=PersistenceContext(temp.newFolder());WatchedStore(to).mark("p",movie,true)
        BackupPersistence(to,FixtureSecrets()).restore(old)
        assertFalse(WatchedStore(to).load("p").watched(movie))
        assertTrue(Prefs(to).showWatchedBadges);assertFalse(Prefs(to).blurUnwatchedEpisodes)
    }
    @Test fun corruptLocalMarkerTypesDoNotCrashRendering() {
        val context=PersistenceContext(temp.newFolder())
        context.getSharedPreferences(WatchedStore.FILE,0).edit().putBoolean(HomeTitleVisibility.prefix("p")+"yes",true).commit()
        assertFalse(WatchedStore(context).load("p").watched(movie))
    }
    @Test fun spoilerBlurRemovesSharpTransitionsWithoutMutatingOriginalPixels() {
        val original=IntArray(64){if(it%8<4)0xff000000.toInt() else 0xffffffff.toInt()}
        val copy=original.copyOf();val blurred=SpoilerPixels.blur(original,8,8)
        assertArrayEquals(copy,original)
        assertTrue((blurred[3] and 255)>0);assertTrue((blurred[4] and 255)<255)
        assertTrue((blurred[4] and 255)-(blurred[3] and 255)<100)
        assertTrue(blurred.all {it ushr 24==255})
    }
    @Test fun unknownProfileStateStaysBlurredUntilWatchedOverridesAreLoaded() {
        assertTrue(SpoilerPolicy.blur(true,true,false))
        assertTrue(SpoilerPolicy.blur(true,false,true))
        assertFalse(SpoilerPolicy.blur(true,true,true))
        assertFalse(SpoilerPolicy.blur(false,false,false))
    }
    @Test fun blurPreservesFlatColorsAndRejectsMalformedDimensions() {
        val flat=IntArray(16){0xffd02030.toInt()}
        assertArrayEquals(flat,SpoilerPixels.blur(flat,4,4))
        assertThrows(IllegalArgumentException::class.java){SpoilerPixels.blur(IntArray(3),2,2)}
    }
    @Test fun lowReducedMotionUnfocusedAndBackgroundedCardsDoNotPulse() {
        assertTrue(CardFocusStyle.pulse("pulse",false,false,true,true))
        assertFalse(CardFocusStyle.pulse("pulse",true,false,true,true))
        assertFalse(CardFocusStyle.pulse("pulse",false,true,true,true))
        assertFalse(CardFocusStyle.pulse("pulse",false,false,false,true))
        assertFalse(CardFocusStyle.pulse("pulse",false,false,true,false))
        assertFalse(CardFocusStyle.animated("off",false,false))
        assertEquals(CardFocusStyle.SUBTLE,CardFocusStyle.normalize("unknown"))
        assertTrue(CardFocusStyle.outline(false,"pulse",true,false))
        assertTrue(CardFocusStyle.outline(false,"off",false,false))
        assertTrue(CardFocusStyle.outline(false,"glow",false,true))
        assertFalse(CardFocusStyle.outline(false,"glow",false,false))
    }
    @Test fun heroGeometryStaysFixedWhenFocusCyclesBetweenContentAndNavigation() {
        val height=HeroLayout.height(400f,1f)
        assertTrue(height>=300f)
        assertEquals(0f,HeroLayout.focusScroll(true,96f),0f)
        assertEquals(96f,HeroLayout.focusScroll(false,96f),0f)
        assertEquals(96f,HeroLayout.focusScroll(true,96f,fitsViewport=false),0f)
        assertTrue(HeroLayout.height(400f,1.5f)>height)
        assertTrue(HeroLayout.height(800f,1f)>height)
    }
    private fun luminance(color:Int):Double {
        fun channel(v:Int):Double {val s=v/255.0;return if(s<=.04045)s/12.92 else ((s+.055)/1.055).pow(2.4)}
        return .2126*channel(color shr 16 and 255)+.7152*channel(color shr 8 and 255)+.0722*channel(color and 255)
    }
    @Test fun descriptionTextHasContrastAgainstEveryThemeBaseWithoutAnOpaqueTextPanel() {
        for(accent in 0..app.ember.tv.data.ColorThemes.MODERN_EMBER)for(day in listOf(true,false))for(oled in listOf(true,false)) {
            val palette=emberColorScheme(accent,day,oled)
            val foreground=luminance(if(day)DescriptionContrast.DAY_TEXT else DescriptionContrast.NIGHT_TEXT)
            val background=palette.background.luminance().toDouble()
            assertTrue((maxOf(foreground,background)+.05)/(minOf(foreground,background)+.05)>=4.5)
        }
    }
    @Test fun settingsDestinationsAndSearchFollowTheNewSections() {
        for(tab in listOf(SettingsTab.UPDATES,SettingsTab.DEBUG)) {
            assertEquals(SettingsCategory.SYSTEM,SettingsCategory.of(tab))
            assertEquals(tab,SettingsLocation.jump(tab.name)?.page)
        }
        assertEquals(SettingsCategory.PLAYBACK,SettingsCategory.of(SettingsTab.WATCH_STATUS))
        assertTrue(SettingDefinitions.matching("crash log").any {it.tab==SettingsTab.DEBUG && it.id=="crash-log"})
        assertTrue(SettingDefinitions.matching("updates").any {it.tab==SettingsTab.UPDATES})
        assertTrue(SettingDefinitions.matching("blur unwatched").any {it.tab==SettingsTab.WATCH_STATUS})
        assertTrue(SettingDefinitions.matching("focus effects").any {it.id=="card-focus" && it.id in appearanceKeys("modern")})
    }
}
