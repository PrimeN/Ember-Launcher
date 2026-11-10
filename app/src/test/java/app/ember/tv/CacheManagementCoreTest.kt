package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.tracker.SecretCipher
import app.ember.tv.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CacheManagementCoreTest {
 @get:Rule val temp=TemporaryFolder()
 private val cipher=object:SecretCipher {
  val key=SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")
  override fun encrypt(name:String,value:ByteArray):ByteArray {
   val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(name.toByteArray());return c.iv+c.doFinal(value)
  }
  override fun decrypt(name:String,blob:ByteArray):ByteArray {
   val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,blob,0,12));c.updateAAD(name.toByteArray());return c.doFinal(blob,12,blob.size-12)
  }
 }
 @Test fun durationRangesAreBoundedAndUnitsUseLongArithmetic(){
  val config=CacheConfig(365,720,720,720);assertTrue(config.valid())
  assertEquals(365L*86400000,config.millis(CacheKind.IMAGES));assertEquals(720L*3600000,config.millis(CacheKind.METADATA))
  assertFalse(config.copy(imageDays=0).valid());assertFalse(config.copy(catalogHours=-1).valid());assertFalse(config.copy(networkHours=721).valid())
 }
 @Test fun preferencesRoundTripAllCacheOptionsThroughBackup(){
  val from=PersistenceContext(temp.newFolder());val expected=CacheConfig(7,12,6,48);Prefs(from).cacheConfig=expected
  val payload=BackupPersistence(from,FixtureSecrets()).snapshot()
  val to=PersistenceContext(temp.newFolder());BackupPersistence(to,FixtureSecrets()).restore(payload)
  assertEquals(expected,Prefs(to).cacheConfig)
 }
 @Test fun invalidBackupDurationsCannotMutatePreferences(){
  val context=PersistenceContext(temp.newFolder());val store=BackupPersistence(context,FixtureSecrets())
  Prefs(context).cacheConfig=CacheConfig(7,12,6,48);val before=store.snapshot()
  val core=before.getValue("prefs.ember").jsonObject
  val invalid=JsonObject(before+("prefs.ember" to JsonObject(core+("cacheConfig" to buildJsonObject {put("t","s");put("v","""{"imageDays":-1}""")}))))
  assertTrue(runCatching{store.restore(invalid)}.exceptionOrNull() is Backup.BackupException)
  assertEquals(before,store.snapshot())
 }
 @Test fun exactExpiryBoundaryFetchesFreshData()=runBlocking {
  var now=1000L;var loads=0
  val cache=ResponseCache(temp.newFolder(),cipher,{5000},{"epoch"},{now})
  suspend fun get()=cache.get(CacheKind.METADATA,"title"){"reply${++loads}"}
  assertEquals("reply1",get());now=5999;assertEquals("reply1",get());now=6000;assertEquals("reply2",get())
 }
 @Test fun encryptedCacheSurvivesRestartWithoutPlaintextCredentials()=runBlocking {
  val root=temp.newFolder();val identity="https://example.test/meta?token=fixture-private-token"
  val body="""{"title":"Fixture","private":"fixture-secret-value"}"""
  val first=ResponseCache(root,cipher,{5000},{"epoch"},{1000})
  first.get(CacheKind.METADATA,identity){body}
  val files=root.walkTopDown().filter{it.isFile}.toList();assertEquals(1,files.size)
  assertTrue(files.single().name.matches(Regex("[0-9a-f]{64}\\.cache")))
  assertFalse(files.single().readBytes().toString(Charsets.UTF_8).contains("fixture-secret-value"))
  val restarted=ResponseCache(root,cipher,{5000},{"epoch"},{2000})
  assertEquals(body,restarted.get(CacheKind.METADATA,identity){error("must use cache")})
 }
 @Test fun responseIdentitySeparatesCredentialsAndCacheCategories()=runBlocking {
  val cache=ResponseCache(temp.newFolder(),cipher,{5000},{"epoch"},{1000})
  assertEquals("a",cache.get(CacheKind.METADATA,"credential-a"){"a"})
  assertEquals("b",cache.get(CacheKind.METADATA,"credential-b"){"b"})
  assertEquals("catalog",cache.get(CacheKind.CATALOGS,"credential-a"){"catalog"})
 }
 @Test(timeout=15000) fun clearWinsOverALateCompletion()=runBlocking {
  val root=temp.newFolder();var epoch="before"
  val cache=ResponseCache(root,cipher,{5000},{epoch},{1000})
  val start=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
  val old=async {cache.get(CacheKind.METADATA,"same"){start.complete(Unit);finish.await();"old"}}
  start.await();epoch="after";cache.clear(setOf(CacheKind.METADATA));finish.complete(Unit);assertEquals("old",old.await())
  assertTrue(root.walkTopDown().none{it.isFile})
  assertEquals("fresh",cache.get(CacheKind.METADATA,"same"){"fresh"})
 }
 @Test(timeout=15000) fun concurrentReadersShareASingleSuccessfulFetch()=runBlocking {
  var loads=0;val cache=ResponseCache(temp.newFolder(),cipher,{5000},{"epoch"},{1000})
  val result=List(12){async {cache.get(CacheKind.CATALOGS,"same"){loads++;yield();"shared"}}}.awaitAll()
  assertEquals(List(12){"shared"},result);assertEquals(1,loads)
 }
 @Test fun failedFetchIsNeverCached()=runBlocking {
  val root=temp.newFolder();val cache=ResponseCache(root,cipher,{5000},{"epoch"},{1000})
  assertTrue(runCatching{cache.get(CacheKind.CATALOGS,"same"){error("offline")}}.isFailure)
  assertEquals("recovered",cache.get(CacheKind.CATALOGS,"same"){"recovered"})
 }
 @Test fun memoryPressureFallsBackToEncryptedDiskWithoutRefetch()=runBlocking {
  val cache=ResponseCache(temp.newFolder(),cipher,{5000},{"epoch"},{1000})
  cache.get(CacheKind.METADATA,"id"){"cached"};cache.trimMemory()
  assertEquals("cached",cache.get(CacheKind.METADATA,"id"){error("must use disk")})
 }
 @Test fun unsuccessfulResponsesCanBeReturnedWithoutCaching()=runBlocking {
  val cache=ResponseCache(temp.newFolder(),cipher,{5000},{"epoch"},{1000});var calls=0
  repeat(2){assertEquals("error",cache.get(CacheKind.METADATA,"id",cacheable={false}){calls++;"error"})}
  assertEquals(2,calls)
 }
 @Test fun corruptedCiphertextBecomesAMiss()=runBlocking {
  val root=temp.newFolder();ResponseCache(root,cipher,{5000},{"epoch"},{1000}).get(CacheKind.METADATA,"id"){"old"}
  root.walkTopDown().first{it.isFile}.writeText("not encrypted")
  assertEquals("new",ResponseCache(root,cipher,{5000},{"epoch"},{1000}).get(CacheKind.METADATA,"id"){"new"})
 }
 @Test fun clearDoesNotFollowSymlinksIntoSavedData()=runBlocking {
  val root=temp.newFolder();val saved=temp.newFolder();val library=File(saved,"library.json").apply{writeText("private saved history")}
  val cacheDir=File(root,"metadata").apply{mkdirs()};Files.createSymbolicLink(File(cacheDir,"link").toPath(),saved.toPath())
  assertEquals(0L,CacheFiles.size(cacheDir));ResponseCache(root,cipher).clear(setOf(CacheKind.METADATA))
  assertEquals("private saved history",library.readText());assertFalse(cacheDir.exists())
 }
 @Test fun clearingOneCategoryKeepsOtherDiskData()=runBlocking {
  val root=temp.newFolder();val cache=ResponseCache(root,cipher,{5000},{"epoch"},{1000})
  cache.get(CacheKind.METADATA,"a"){"meta"};cache.get(CacheKind.CATALOGS,"b"){"catalog"}
  cache.clear(setOf(CacheKind.METADATA));assertFalse(File(root,"metadata").exists());assertTrue(File(root,"catalogs").listFiles()!!.isNotEmpty())
 }
 @Test fun cacheSettingsAreSearchableUnderSystem(){
  assertEquals(SettingsCategory.SYSTEM,SettingsCategory.of(SettingsTab.CACHE))
  // #164: Cache Management is part of Storage & diagnostics.
  assertEquals(SettingsTab.DEBUG,SettingsLocation.jump("CACHE")!!.page)
  for(query in listOf("image duration","metadata cache","catalog cache","cache size","network cache"))
   assertTrue(query,SettingDefinitions.matching(query).any{it.tab==SettingsTab.CACHE})
 }
}
