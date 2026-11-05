package app.ember.tv

import android.app.Application
import app.ember.tv.data.*
import app.ember.tv.home.*
import app.ember.tv.light.*
import app.ember.tv.state.PinState
import app.ember.tv.state.WeatherState
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.weather.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64
import java.time.ZoneId

class HomeEnhancementsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun prefs()=Prefs(PersistenceContext(temp.newFolder()))
    private val ids=setOf("main","other")
    private fun profiles(p:Prefs){p.profiles=listOf(Profile("main","Main"),Profile("other","Other"))}
    @Test fun firstSwitchInheritsTheExistingLayoutWithoutSharingLaterEdits() {
        val p=prefs();profiles(p);p.order=listOf("com.first","com.second");p.hidden=setOf("com.hidden");p.lightWallpaper="ocean"
        val homes=PersonalHomes(p);homes.putFolder(null,"Streaming",listOf("com.first"))
        homes.update {it.copy(mutedApps=setOf("com.noisy"))}
        p.currentProfile="other";homes.switchTo("other",ids)
        assertEquals(listOf("com.first","com.second"),p.order);assertEquals("ocean",p.lightWallpaper)
        assertTrue(homes.current.folders.isEmpty());assertTrue(homes.current.mutedApps.isEmpty())
        p.order=listOf("com.second","com.first");p.hidden=emptySet();p.lightWallpaper="ember";p.lightHome=LightHomeOptions(recommendations=false)
        p.currentProfile="main";homes.switchTo("main",ids)
        assertEquals(listOf("com.first","com.second"),p.order);assertEquals(setOf("com.hidden"),p.hidden);assertEquals("ocean",p.lightWallpaper)
        assertEquals("Streaming",homes.current.folders.single().name);assertEquals(setOf("com.noisy"),homes.current.mutedApps)
        p.currentProfile="other";homes.switchTo("other",ids)
        assertEquals(listOf("com.second","com.first"),p.order);assertEquals("ember",p.lightWallpaper);assertFalse(p.lightHome.recommendations)
    }
    @Test fun coldRestartKeepsUnswitchSavedChangesInTheActiveDesktop() {
        val p=prefs();profiles(p);val home=PersonalHomes(p)
        p.order=listOf("com.a");p.currentProfile="other";home.switchTo("other",ids)
        p.order=listOf("com.b");p.lightWallpaper="black"
        val cold=PersonalHomes(p)
        p.currentProfile="main";cold.switchTo("main",ids);assertEquals(listOf("com.a"),p.order)
        p.currentProfile="other";cold.switchTo("other",ids);assertEquals(listOf("com.b"),p.order);assertEquals("black",p.lightWallpaper)
    }
    @Test fun foldersMoveAppsWithoutDuplicatingMembershipOrReorderingEdits() {
        val home=PersonalHomes(prefs())
        home.putFolder("one","  Stream\n apps  ",listOf("com.a","com.a"));home.putFolder("two","Games",listOf("com.b"))
        home.assign("com.a","two")
        assertTrue(home.current.folders.first().packages.isEmpty());assertEquals(listOf("com.b","com.a"),home.current.folders[1].packages)
        home.putFolder("one","Renamed",listOf("com.b"))
        assertEquals(listOf("one","two"),home.current.folders.map {it.id});assertEquals(listOf("com.a"),home.current.folders[1].packages)
        home.removeFolder("one");assertNull(home.folderFor("com.b"));assertEquals("two",home.folderFor("com.a")?.id)
    }
    @Test fun removingProfileDataLeavesOtherPersonalizationsIntact() {
        val p=prefs();profiles(p);val home=PersonalHomes(p);home.putFolder("one","Main",listOf("com.a"))
        p.currentProfile="other";home.switchTo("other",ids);home.putFolder("two","Other",listOf("com.b"))
        home.removeProfile("main");assertEquals("Other",home.current.folders.single().name)
        assertFalse(Json.decodeFromString<Map<String,PersonalHome>>(p.personalHomesJson).containsKey("main"))
    }
    private fun art():AppArt {
        val bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMr8AAAAASUVORK5CYII=")
        return AppArt(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},Base64.getEncoder().encodeToString(bytes))
    }
    @Test fun portableBackupRestoresBothDesktopsArtworkAndGlobalAppLocks() {
        val source=PersistenceContext(temp.newFolder());val p=Prefs(source);profiles(p);p.order=listOf("com.main")
        val home=PersonalHomes(p);home.putFolder("one","Main folder",listOf("com.main"));home.update {it.copy(artwork=mapOf("com.main" to art()),mutedApps=setOf("com.noisy"))}
        val pin=PinState(p){};pin.setPin("1234")
        LauncherExtrasState(p).update {it.copy(lockedApps=setOf("com.main"),destination="app",target="com.main",onWake=true)}
        p.currentProfile="other";home.switchTo("other",ids);p.order=listOf("com.other");p.lightWallpaper="ember"
        val target=PersistenceContext(temp.newFolder())
        BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
        val restored=Prefs(target);assertEquals(listOf("com.other"),restored.order);assertEquals("ember",restored.lightWallpaper)
        val cold=PersonalHomes(restored);restored.currentProfile="main";cold.switchTo("main",ids)
        assertEquals(listOf("com.main"),restored.order);assertEquals(art(),cold.current.artwork["com.main"])
        assertEquals("Main folder",cold.current.folders.single().name)
        assertEquals(setOf("com.main"),LauncherExtrasState(restored).options.lockedApps)
    }
    @Test fun backupRejectsAppLocksWithoutTheAdministratorPin() {
        val c=PersistenceContext(temp.newFolder());val p=Prefs(c)
        LauncherExtrasState(p).update {it.copy(lockedApps=setOf("com.private"))}
        assertThrows(Backup.BackupException::class.java){BackupPersistence(c,FixtureSecrets()).snapshot()}
    }
    @Test fun retiredExtrasDoNotDiscardSavedSettingsOrBreakBackupRestore() {
        val source=PersistenceContext(temp.newFolder());val p=Prefs(source)
        // A setting from older backups must be ignored without resetting the remaining options.
        p.launcherExtrasJson="""{"destination":"app","target":"com.main","onWake":true,"nowPlaying":true,"airPlay":true,"slideSeconds":30}"""
        val expected=LauncherExtras(destination="app",target="com.main",onWake=true,nowPlaying=true,slideSeconds=30)
        assertEquals(expected,LauncherExtrasState(p).options)
        val target=PersistenceContext(temp.newFolder())
        BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
        val restored=Prefs(target);val extras=LauncherExtrasState(restored)
        assertEquals(expected,extras.options)
        extras.update {it.copy(keepAwake=true)}
        assertEquals(expected.copy(keepAwake=true),LauncherExtrasState(restored).options)
        assertFalse(restored.launcherExtrasJson.contains("airPlay"))
    }
    @Test fun appPinCannotBeRemovedWhileAppLocksExist() {
        val p=prefs();val pin=PinState(p){};pin.setPin("1234");pin.appLocks={true}
        val before=p.pinHash;pin.setPin(null);assertEquals(before,p.pinHash)
        pin.appLocks={false};pin.setPin(null);assertNull(p.pinHash)
    }
    @Test fun appLocksRemainAcrossProfileSwitchAndProfileRemoval() {
        val p=prefs();profiles(p);val extras=LauncherExtrasState(p);extras.update {it.copy(lockedApps=setOf("com.private"))}
        val home=PersonalHomes(p);p.currentProfile="other";home.switchTo("other",ids);home.removeProfile("main")
        assertEquals(setOf("com.private"),LauncherExtrasState(p).options.lockedApps)
    }
    @Test fun artworkLimitAllowsReplacingExistingImagesWithoutUnboundedGrowth() {
        val homes=PersonalHomes(prefs());val sample=art()
        homes.update {it.copy(artwork=(0 until 100).associate {i->"com.app$i" to sample})}
        assertFalse(homes.canStoreArtwork("com.new",sample))
        assertTrue(homes.canStoreArtwork("com.app0",sample))
        assertEquals(100,homes.current.artwork.size)
    }
    @Test fun customizedArtworkAndPlaylistsHaveValidationLimits() {
        HomeConfigValidation.homes(mapOf("main" to PersonalHome(artwork=mapOf("com.app" to art()))),setOf("main"))
        assertThrows(IllegalArgumentException::class.java){HomeConfigValidation.homes(mapOf("missing" to PersonalHome()),setOf("main"))}
        assertThrows(IllegalArgumentException::class.java){HomeConfigValidation.homes(mapOf("main" to PersonalHome(artwork=mapOf("app" to art().copy(id="0".repeat(64))))),setOf("main"))}
        assertThrows(IllegalArgumentException::class.java){HomeConfigValidation.extras(LauncherExtras(slides=listOf(PresentationItem("x","Remote","https://site.test/video.mp4",true))))}
    }
    private fun item(id:String,continuing:Boolean=true,engaged:Long=100,pkg:String="com.app")=LightMedia(id,pkg,id,"","https://image.test/$id","app://$id",.5f,engaged,continuing,1_200_000,600_000)
    @Test fun dismissalsReturnWhenPlaybackChangesIncludingUndatedPreviewRows() {
        val old=item("one");val home=PersonalHome(dismissed=mapOf(old.id to LightMediaProvider.engagementToken(old)))
        assertTrue(LightMediaProvider.personalized(listOf(old),home).isEmpty())
        assertEquals(1,LightMediaProvider.personalized(listOf(old.copy(engaged=101)),home).size)
        val preview=old.copy(engaged=0);val hidden=PersonalHome(dismissed=mapOf(preview.id to LightMediaProvider.engagementToken(preview)))
        assertTrue(LightMediaProvider.personalized(listOf(preview),hidden).isEmpty())
        assertEquals(1,LightMediaProvider.personalized(listOf(preview.copy(position=610_000)),hidden).size)
    }
    @Test fun mutedRecommendationsDoNotHideContinueWatchingFromThatApp() {
        val records=listOf(item("resume"),item("suggestion",false))
        assertEquals(listOf("resume"),LightMediaProvider.personalized(records,PersonalHome(mutedApps=setOf("com.app"))).map {it.id})
    }
    @Test fun clipAndCreditsFiltersDoNotDiscardShortEpisodesByDefault() {
        val short=item("short").copy(duration=300_000,position=200_000)
        val credits=item("credits").copy(position=1_150_000)
        assertEquals(listOf("short"),LightMediaProvider.personalized(listOf(short,credits),PersonalHome()).map {it.id})
        assertTrue(LightMediaProvider.personalized(listOf(short),PersonalHome(hideShortClips=true)).isEmpty())
        assertEquals(2,LightMediaProvider.personalized(listOf(short,credits),PersonalHome(hideCredits=false)).size)
    }
    @Test fun providerResumeChannelsAndTwoHourlyRecommendationRotationAreRecognized() {
        assertTrue(LightMediaProvider.resumeChannel("Continue Watching"));assertTrue(LightMediaProvider.resumeChannel("In progress"));assertFalse(LightMediaProvider.resumeChannel("Recently added"))
        val pool=(0..7).flatMap {i->listOf(item("a$i",false,pkg="com.a").copy(rank=8L-i),item("b$i",false,pkg="com.b").copy(rank=8L-i))}
        assertEquals(listOf("a0","b0","a1","b1"),LightMediaProvider.select(pool,0).map {it.id})
        assertEquals(listOf("a1","b1","a2","b2"),LightMediaProvider.select(pool,7_200_000).map {it.id})
    }
    @Test fun manyContinueWatchingEntriesStillReserveRecommendationSlots() {
        val pool=(1..30).map {item("resume$it",true,it.toLong(),"com.$it")}+(1..8).map {item("next$it",false,pkg="com.more")}
        val selected=LightMediaProvider.select(pool,0)
        assertEquals(12,selected.count {it.continuing});assertTrue(selected.any {!it.continuing})
    }
    @Test fun startupChoicesAreOffByDefaultAndAlwaysDisabledForChildren() {
        assertFalse(LauncherExtras().startup("boot",false))
        val opts=LauncherExtras(destination="app",target="com.app",atBoot=true,onWake=true)
        assertTrue(opts.startup("boot",false));assertTrue(opts.startup("wake",false))
        assertFalse(opts.startup("wake",true));assertFalse(opts.startup("unknown",false))
        assertEquals("home",opts.copy(destination="unknown").normalized().destination)
        assertEquals(5,opts.copy(slideSeconds=0).normalized().slideSeconds)
        assertFalse(opts.nowPlaying);assertFalse(opts.keepAwake)
    }
    @Test fun weatherSourcesUseTheChosenProviderAndCanDisableFallback() {
        assertEquals(listOf("met","openmeteo","wttr"),WeatherProviders.order("met",true))
        assertEquals(listOf("wttr"),WeatherProviders.order("wttr",false))
    }
    @Test fun weatherCoordinatesUseAtMostFourDecimalsWithoutExponents() {
        // MET Norway blocks more than four decimals; geocoded places usually have five.
        assertEquals("43.7001",WeatherProviders.coordinate(43.70011))
        assertEquals("-79.4163",WeatherProviders.coordinate(-79.41630))
        assertEquals("0.0001",WeatherProviders.coordinate(0.0001))
        assertEquals("2",WeatherProviders.coordinate(2.0))
    }
    @Test fun failedAndTimedOutWeatherSourcesFallBackButCancellationDoesNot()=runBlocking {
        val visits=mutableListOf<String>();val place=Place("City",1.0,2.0);val value=WeatherNow(12,0,15,5,"C")
        assertEquals(value,WeatherProviders.forecast(place,false,"openmeteo",true,loader={source,_,_->visits+=source;if(source=="openmeteo")error("offline") else value}))
        assertEquals(listOf("openmeteo","met"),visits);visits.clear()
        assertEquals(value,WeatherProviders.forecast(place,false,"openmeteo",true,loader={source,_,_->visits+=source;if(source=="openmeteo")delay(1000);value},timeoutMillis=50))
        assertEquals(listOf("openmeteo","met"),visits);visits.clear()
        try {WeatherProviders.forecast(place,false,"openmeteo",true,loader={source,_,_->visits+=source;throw CancellationException("fixture")});fail("Cancellation swallowed")}
        catch(_:CancellationException){}
        assertEquals(listOf("openmeteo"),visits)
    }
    @Test fun metNorwayForecastConvertsUnitsAndCollectsFiveDays() {
        val rows=(4..8).joinToString(","){day->"""{"time":"2026-11-0${day}T12:00:00Z","data":{"instant":{"details":{"air_temperature":10}},"next_1_hours":{"summary":{"symbol_code":"clearsky_day"}}}}"""}
        val root=Json.parseToJsonElement("""{"properties":{"timeseries":[$rows]}}""")
        val weather=WeatherProviders.met(root,true,java.time.Instant.parse("2026-11-04T12:00:00Z").toEpochMilli(),ZoneId.of("UTC"))
        assertEquals(50,weather.temp);assertEquals(5,weather.days.size);assertEquals("MET Norway",weather.source);assertEquals(0,weather.code)
        assertEquals(95,WeatherProviders.metCode("heavyrainandthunder_day"))
    }
    @Test fun wttrForecastRetainsItsActualThreeDayCoverage() {
        val root=Json.parseToJsonElement("""{"current_condition":[{"temp_C":"10","temp_F":"50","weatherCode":"116"}],"weather":[{"date":"2026-11-04","maxtempC":"14","mintempC":"5","hourly":[{"time":"1200","weatherCode":"113"}]}]}""")
        val weather=WeatherProviders.wttr(root,false)
        assertEquals(10,weather.temp);assertEquals(1,weather.days.size);assertEquals(14,weather.high);assertEquals("wttr.in",weather.source)
    }
    @Test fun savedForecastIsBoundToPlaceUnitAndAgeAndSurvivesRestart() {
        val c=PersistenceContext(temp.newFolder());val p=Prefs(c);val place=Place("City",1.0,2.0);val now=System.currentTimeMillis()
        val saved=SavedForecast(place,false,now,WeatherNow(12,2,15,5,"C",source="MET Norway"))
        assertTrue(saved.usable(place,false,now));assertFalse(saved.usable(place,true,now));assertFalse(saved.usable(place.copy(lat=3.0),false,now));assertFalse(saved.usable(place,false,now+8*86_400_000L))
        p.weatherPlace=place.encode();p.weatherCache=Json.encodeToString(saved)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {val state=WeatherState(Application(),p,scope,ScreensaverFeed(c));assertEquals(saved.weather,state.details);assertEquals(saved.weather.short,state.weatherText)
            state.weatherOff();assertNull(p.weatherCache);assertNull(state.details)}finally{scope.cancel()}
    }
}
