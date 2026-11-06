package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import app.ember.tv.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

class PlaybackAndPersistenceRegressionTest {
 @get:Rule val temp=TemporaryFolder()
 @Before fun reset(){LocalLibrary.resetForTest()}
 @After fun cleanup(){LocalLibrary.resetForTest()}
 private val movie=Meta(id="tmdb:42",type="movie",name="Movie")
 private val show=Meta(id="tmdb:42",type="series",name="Show")
 @Test fun historySeparatesTypesAndRetainsOnlyLatestEpisode(){
  LocalLibrary.started("m",movie);LocalLibrary.progress("m",.4f)
  LocalLibrary.started("s1",show.copy(season=1,episode=1));LocalLibrary.progress("s1",.2f)
  assertEquals(2,LocalLibrary.inProgress.size)
  LocalLibrary.started("s2",show.copy(season=1,episode=2));LocalLibrary.progress("s2",.3f)
  assertEquals(2,LocalLibrary.inProgress.size);assertEquals(2,LocalLibrary.inProgress.first().episode)
 }
 @Test fun hidingAndRevealingAreTypeSpecific(){
  LocalLibrary.hideRecent(movie);assertTrue(LocalLibrary.isHidden(movie));assertFalse(LocalLibrary.isHidden(show))
  LocalLibrary.clearRecent(listOf(show));LocalLibrary.started("m",movie)
  assertFalse(LocalLibrary.isHidden(movie));assertTrue(LocalLibrary.isHidden(show))
 }
 @Test fun startupPreservesBothTypesAndMigratesLegacyHides()=runBlocking {
  val file=temp.newFile("library.json")
  file.writeText("""{"plays":[{"key":"m","meta":{"id":"tmdb:42","type":"movie"},"progress":0.4},{"key":"s","meta":{"id":"tmdb:42","type":"series"},"progress":0.2}],"hidden":["tt11"],"trackerHistory":[{"id":"tt11","type":"movie"}]}""")
  LocalLibrary.initFile(file);LocalLibrary.awaitReady()
  assertEquals(2,LocalLibrary.inProgress.size)
  assertTrue(LocalLibrary.isHidden(Meta(id="tt11",type="movie")))
  assertFalse(LocalLibrary.isHidden(Meta(id="tt11",type="series")))
 }
 @Test fun unknownLegacyHideNeverMigratesToUnrelatedLaterTitle()=runBlocking {
  val file=temp.newFile("legacy-library.json");file.writeText("""{"hidden":["tmdb:42"]}""")
  LocalLibrary.initFile(file);LocalLibrary.awaitReady()
  LocalLibrary.started("show",show);LocalLibrary.snapshotFiles{}
  LocalLibrary.resetForTest();LocalLibrary.initFile(file);LocalLibrary.awaitReady()
  assertFalse(LocalLibrary.isHidden(show));assertFalse(LocalLibrary.isHidden(movie))
 }
 @Test fun hideSurvivesReload()=runBlocking {
  val file=File(temp.newFolder(),"library.json");LocalLibrary.initFile(file);LocalLibrary.awaitReady()
  LocalLibrary.hideRecent(movie);LocalLibrary.snapshotFiles{}
  LocalLibrary.resetForTest();LocalLibrary.initFile(file);LocalLibrary.awaitReady()
  assertTrue(LocalLibrary.isHidden(movie));assertFalse(LocalLibrary.isHidden(show))
 }
 @Test fun trickplayValidGridClampsWithoutIntegerOverflow(){
  val t=Trickplay(1000,25,5,2,320,180){"tile$it"}
  assertTrue(t.valid);assertEquals(Triple("tile1",2,0),t.at(12000))
  assertEquals(Triple("tile2",4,0),t.at(Long.MAX_VALUE))
  assertEquals(Triple("tile0",0,0),t.at(-1))
 }
 @Test fun jellyfinRejectsInvalidMetadataThroughParser()=runBlocking {
  val secrets=object:ServerSecrets {
   override fun read(key:String):String?=if(key=="ms.deviceId")"fixture-device" else null
   override fun write(key:String,value:String)=true
   override fun remove(key:String){}
  }
  for((cols,rows,count,interval) in listOf(listOf(0,10,20,10000),listOf(10,0,20,10000),listOf(10,10,0,10000),listOf(10,10,20,0),listOf(10,10,20,10000))) {
   val body="""{"Trickplay":{"source":{"320":{"Width":320,"Height":180,"Interval":$interval,"ThumbnailCount":$count,"TileWidth":$cols,"TileHeight":$rows}}}}"""
   val http=ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
   }.build())
   val result=JellyfinClient(Accounts(secrets),http).trickplay(ServerAccount("a","jellyfin","Fixture","https://example.test","u","fixture-token"),"e1")
   if(cols>0 && rows>0 && count>0 && interval>0)assertNotNull(result) else assertNull(result)
  }
 }
 @Test fun invalidPreviewMetadataIsSafe(){
  val bad=listOf(Trickplay(1000,5,0,10,320,180){"bad"},Trickplay(0,5,5,2,320,180){"bad"},
   Trickplay(1000,0,5,2,320,180){"bad"},Trickplay(1000,5,Int.MAX_VALUE,2,320,180){"bad"},
   Trickplay(1000,5,5,2,-1,180){"bad"})
  bad.forEach {assertFalse(it.valid);assertEquals(Triple("",0,0),it.at(5000))}
 }
 private fun payload(profiles:List<Profile>)=buildJsonObject {
  put("prefs.ember",BackupFormat.preferences(mapOf("profiles" to Json.encodeToString(profiles))))
  put("tokens",buildJsonObject{});put("files",buildJsonObject{})
 }
 @Test fun malformedProfilesRejectedBeforeMutation(){
  val ctx=PersistenceContext(temp.newFolder());val persistence=BackupPersistence(ctx,FixtureSecrets())
  Prefs(ctx).profiles=listOf(Profile("main","Original"));val before=persistence.snapshot()
  for(profiles in listOf(listOf(Profile("main","One"),Profile("main","Two")),listOf(Profile(" ","Blank")),emptyList())) {
   assertTrue(runCatching{persistence.restore(payload(profiles))}.exceptionOrNull() is Backup.BackupException)
   assertEquals(before,persistence.snapshot())
  }
 }
 @Test fun invalidStoredProfilesCannotDeleteLastProfile(){
  val prefs=Prefs(PersistenceContext(temp.newFolder()))
  prefs.profiles=listOf(Profile("main","One"),Profile("main","Two"),Profile("","Blank"))
  val state=ProfilesState(prefs,{emptyList()},{emptyList()},{_,go->go()},{},{})
  assertEquals(1,state.all.size);state.deleteProfile("main");assertEquals("main",state.profile.id)
 }
 private fun native(n:Int)=Meta(id="jellyfin:a:e$n",type="series",name="Show",imdbId="tt1234567",season=1,episode=n,serverRef="jellyfin|a|e$n")
 @Test fun nativeResumeIdentityMatchesEpisodePickerAndSeparatesEpisodes(){
  val first=native(1);val second=native(2)
  assertEquals(ServerMetadata.episode(first).id,first.playbackKey())
  assertEquals(first.playbackKey(),show.playbackKey(ServerMetadata.episode(first)))
  assertNotEquals(first.playbackKey(),second.playbackKey())
  assertEquals("tt1234567",first.copy(type="movie").playbackKey())
  assertEquals("provider:episode-1",Meta(id="provider:episode-1",type="series",season=1,episode=1).playbackKey())
 }
 private fun title(scope:CoroutineScope)=TitleState(scope,AddonClient(),{error("unused")},{error("unused")},{error("unused")},{true},{emptyList()},{10},{_,_->},{false})
 @Test fun actualPlayRequestUsesEpisodeIdentity(){
  val unsafe=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true}.get(null) as sun.misc.Unsafe
  val vm=unsafe.allocateInstance(HomeViewModel::class.java) as HomeViewModel
  val profiles=ProfilesState(Prefs(PersistenceContext(temp.newFolder())),{emptyList()},{emptyList()},{_,go->go()},{},{})
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val title=title(scope)
  for((name,value) in listOf("profiles" to profiles,"title" to title))HomeViewModel::class.java.getDeclaredField(name).apply{isAccessible=true}.set(vm,value)
  val source=StreamParser.parse("Fixture",Json.parseToJsonElement("""{"url":"https://example.test/video.mkv","name":"1080p"}"""))!!
  title.details=DetailsUi(meta=native(1));val first=vm.playRequest(source)!!
  title.details=DetailsUi(meta=native(2));val second=vm.playRequest(source)!!
  title.details=DetailsUi(meta=show,episode=ServerMetadata.episode(native(1)))
  assertNotEquals(first.resumeKey,second.resumeKey);assertEquals(first.resumeKey,vm.playRequest(source)!!.resumeKey)
  title.details=DetailsUi(meta=show,loadingEpisodes=true)
  assertNull(vm.playRequest(source))
  scope.cancel()
 }
 private val queued=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){}}
 private fun loaded()=DetailsUi(meta=Meta(id="jellyfinshow:a:show",type="series",serverRef="jellyfin|a|show"),season=1,
  episode=ServerMetadata.episode(native(1)),episodes=listOf(ServerMetadata.episode(native(1))),episodeCursor=100,
  streams=listOf(StreamParser.parse("Fixture",Json.parseToJsonElement("""{"url":"https://example.test/old.mkv"}"""))!!),
  subtitles=listOf(SubtitleOption("Fixture","en","https://example.test/old.srt")))
 @Test fun seasonChangeClearsPreviousEpisodeSourcesAndCancelsJobs(){
  val scope=CoroutineScope(SupervisorJob()+queued);val title=title(scope);title.details=loaded()
  val oldJob=Job();TitleState::class.java.getDeclaredField("streamsJob").apply{isAccessible=true}.set(title,oldJob)
  title.selectSeason(2);val state=title.details!!
  assertEquals(2,state.season);assertTrue(state.loadingEpisodes);assertNull(state.episode)
  assertTrue(state.streams.isEmpty());assertTrue(state.subtitles.isEmpty());assertTrue(state.serverStreams.isEmpty());assertFalse(state.loadingStreams)
  assertTrue(oldJob.isCancelled);scope.cancel()
 }
 @Test fun paginationRetainsSelectedEpisodeSources(){
  val scope=CoroutineScope(SupervisorJob()+queued);val title=title(scope);val before=loaded();title.details=before
  val oldJob=Job();TitleState::class.java.getDeclaredField("streamsJob").apply{isAccessible=true}.set(title,oldJob)
  title.moreServerEpisodes();assertEquals(before.streams,title.details!!.streams);assertEquals(before.episode,title.details!!.episode)
  assertFalse(oldJob.isCancelled);scope.cancel()
 }
}
