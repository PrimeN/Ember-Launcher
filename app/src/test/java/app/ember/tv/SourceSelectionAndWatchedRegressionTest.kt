package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Assert.*

class SourceSelectionAndWatchedRegressionTest {
 private fun source(audio:String="",codec:String="HEVC",size:Double=10.0)=StreamOption(
  addonName="Fixture",name=audio,title=audio,url="https://example.test/video.mkv",infoHash=null,fileIdx=null,
  quality="FHD",hdr="",codec=codec,audio=audio,source="",releaseGroup="",sizeGb=size,seeders=null,cached=true,serverRef="jellyfin|a|item")
 @Test fun channelSuffixDoesNotChangeAudioPriority(){
  val plain=listOf("Atmos","DTS:X","TrueHD","DTS-HD MA","DTS-HD","DD+","DTS","DD")
  for(codec in plain){
   val better=source(audio="$codec 7.1");val stereo=source(audio="AAC 2.0",size=20.0)
   assertEquals("$codec should beat stereo",better,PlayPicker.pick(emptyList(),listOf(stereo,better),PlayRules()))
   assertEquals(stereo,PlayPicker.pick(emptyList(),listOf(stereo,better),PlayRules(bestAudio=false)))
  }
 }
 @Test fun codecExclusionsAcceptProviderAliases(){
  for((blocked,aliases) in mapOf("H.264" to listOf("h264","H264","avc","avc1","x264"),"HEVC" to listOf("hevc","h265","h.265","x265"),"AV1" to listOf("av1"),"VP9" to listOf("vp9"))){
   aliases.forEach {assertNull("$it must be excluded",PlayPicker.pick(listOf(source(codec=it)),emptyList(),PlayRules(avoidCodecs=setOf(blocked))))}
  }
  assertEquals("HEVC",PlayPicker.pick(listOf(source(codec="h264"),source(codec="HEVC")),emptyList(),PlayRules(avoidCodecs=setOf("H.264")))!!.codec)
 }
 private fun info(resources:String)=AddonInfo.parse("https://example.test/manifest.json","https://example.test",Json.parseToJsonElement("""{"types":["series"],"idPrefixes":["tt"],"resources":$resources}"""))
 @Test fun objectResourcesDoNotInheritPrefixesButStringsDo(){
  val manifest=info("""[{"name":"stream","types":["series"]},"meta"]""")
  assertTrue(manifest.supports("stream","series","kitsu:42"));assertFalse(manifest.supports("stream","movie","tt1"))
  assertFalse(manifest.supports("meta","series","kitsu:42"));assertTrue(manifest.supports("meta","series","tt1"))
 }
 @Test fun explicitObjectPrefixesAndDuplicateResourcesMatchIndependently(){
  val manifest=info("""[{"name":"stream","types":["series"],"idPrefixes":["kitsu:"]},{"name":"stream","types":["movie"],"idPrefixes":["tt"]}]""")
  assertFalse(manifest.supports("stream","series","tt1"));assertTrue(manifest.supports("stream","series","kitsu:42"))
  assertTrue(manifest.supports("stream","movie","tt1"));assertFalse(manifest.supports("stream","movie","kitsu:42"))
 }
 @Test fun emptyObjectPrefixesAllowAllIds(){assertTrue(info("""[{"name":"stream","types":["series"],"idPrefixes":[]}]""").supports("stream","series","custom:1"))}
 @Test fun specificEpisodeOverrideWinsAcrossEnrichedAliases(){
  val full=Meta(id="tmdb:42",type="series",name="Show",imdbId="tt42",tmdbId=42)
  val partial=full.copy(imdbId=null,season=1,episode=1)
  val index=WatchedIndex().changed(full,true).changed(partial,false)
  assertFalse(index.watched(partial));assertFalse(index.watched(partial.copy(imdbId="tt42")))
  assertTrue(index.watched(full.copy(season=1,episode=2)))
 }
 @Test fun episodeWatchedAlsoWinsOverWholeShowUnwatched(){
  val full=Meta(id="tmdb:42",type="series",imdbId="tt42",tmdbId=42)
  val partial=full.copy(imdbId=null,season=1,episode=1)
  val index=WatchedIndex().changed(full,false).changed(partial,true)
  assertTrue(index.watched(partial.copy(imdbId="tt42")));assertFalse(index.watched(full.copy(season=1,episode=2)))
 }
 @Test fun wholeServerChoiceOverridesOnlyStaleOpaqueFallback() {
  val series=Meta(id="series",type="series",serverRef="plex|account|series")
  val episode=Meta(id="episode",type="series",serverRef="plex|account|episode",serverSeriesRef=series.serverRef,season=1,episode=2)
  val index=WatchedIndex().changed(episode,true).changed(series,false)
  assertFalse(index.watched(episode));assertTrue(index.changed(episode,true).watched(episode))
 }
 @Test fun explicitUnwatchedWinsWhenAliasesDisagree(){
  val full=Meta(id="tmdb:42",type="series",imdbId="tt42",tmdbId=42,season=1,episode=1)
  val bases=WatchedKeys.keys(full)
  assertFalse(WatchedIndex(yes=setOf(bases.first()),no=setOf(bases[1])).watched(full))
 }
 private class Secrets:ServerSecrets {
  val values=mutableMapOf("ms.deviceId" to "fixture-device","ms.plex" to "fixture-token")
  override fun read(key:String)=values[key]
  override fun write(key:String,value:String):Boolean{values[key]=value;return true}
  override fun remove(key:String){values.remove(key)}
 }
 private fun http(handler:(Request)->String)=ServerHttp(OkHttpClient.Builder().addInterceptor {chain->
  Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(handler(chain.request()).toResponseBody()).build()
 }.build())
 @Test fun jellyfinMappingAndPickerPreferLosslessAudio()=runBlocking {
  val client=JellyfinClient(Accounts(Secrets()),http {"""{"Id":"item","MediaSources":[{"Id":"aac","Size":21474836480,"MediaStreams":[{"Type":"Video","Height":1080,"Codec":"h264"},{"Type":"Audio","Codec":"aac","Channels":2}]},{"Id":"lossless","Size":10737418240,"MediaStreams":[{"Type":"Video","Height":1080,"Codec":"h264"},{"Type":"Audio","Codec":"truehd","Channels":8}]}]}"""})
  val sources=client.sourcesFor(ServerAccount("a","jellyfin","Fixture","https://example.test","u","fixture-token"),"item")
  assertEquals("TrueHD 7.1",PlayPicker.pick(emptyList(),sources,PlayRules())!!.audio)
 }
 @Test fun plexMappingCannotBypassH264Exclusion()=runBlocking {
  val accounts=Accounts(Secrets());accounts.plexToken="fixture-token"
  val client=PlexClient(accounts,http {req->when{
   req.url.host=="plex.tv" -> """[{"provides":"server","clientIdentifier":"server","name":"Fixture","connections":[{"uri":"https://server.example","local":true}]}]"""
   req.url.encodedPath=="/identity" -> """{"MediaContainer":{"machineIdentifier":"server"}}"""
   else -> """{"MediaContainer":{"Metadata":[{"type":"movie","ratingKey":"42","Media":[{"videoCodec":"h264","height":1080,"Part":[{"key":"/video/42"}]}]}]}}"""
  }})
  val sources=client.sourcesFor("server","42");assertEquals("H264",sources.single().codec)
  assertNull(PlayPicker.pick(emptyList(),sources,PlayRules(avoidCodecs=setOf("H.264"))))
 }
}
