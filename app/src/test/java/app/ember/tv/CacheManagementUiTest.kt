package app.ember.tv

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.ember.tv.data.*
import app.ember.tv.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CacheManagementUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun settingsEditRestoreAndClearKeepPersonalData() {
  val app=RuntimeEnvironment.getApplication()
  Net.init(app);CacheManagement.init(app);IconArtworkCache.init(app)
  runBlocking{CacheManagement.awaitReady()}
  val captured=mutableListOf<coil.request.ImageRequest>()
  val loader=coil.ImageLoader.Builder(app).components {
   add(ImageCacheRetention())
   add(object:coil.intercept.Interceptor {
    override suspend fun intercept(chain:coil.intercept.Interceptor.Chain):coil.request.ImageResult {
     captured+=chain.request
     return coil.request.SuccessResult(android.graphics.drawable.ColorDrawable(android.graphics.Color.RED),chain.request,coil.decode.DataSource.NETWORK)
    }
   })
  }.build()
  val prefs=Prefs(app)
  val secret=app.getSharedPreferences("ember_secrets",0)
  secret.edit().putString("fixture.credential","fixture-encrypted-user-data").commit()
  val library=File(app.filesDir,"library.json").apply{writeText("""{"trackerWatchlist":[{"id":"tt1234567","name":"Saved title"}]}""")}
  val libraryBefore=library.readText()
  val activeUpdate=File(app.cacheDir,"updates/active.apk").apply{parentFile!!.mkdirs();writeText("keep active download")}
  var part by mutableStateOf("image-duration")
  compose.setContent {EmberTheme(0){key(part){CacheManagementSettings(part)}}}
  compose.runOnIdle {
   runBlocking {
    repeat(2){loader.execute(coil.request.ImageRequest.Builder(app).data("https://example.test/poster.jpg").setParameter("fixture.decoder","still","still").build())}
   }
  }
  assertEquals(captured[0].diskCacheKey,captured[1].diskCacheKey)
  assertTrue(captured[0].diskCacheKey!!.matches(Regex("[a-f0-9]{64}")))
  assertEquals("still",captured[0].parameters.value<String>("fixture.decoder"))
  compose.onNodeWithText("1").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.waitUntil(10000){prefs.cacheConfig.imageDays==1 && CacheManagement.configuration.imageDays==1}
  compose.onNodeWithText("Custom…").performSemanticsAction(SemanticsActions.OnClick){it()}
  val editor=compose.onNode(hasText("1") and hasAnyAncestor(isDialog()),useUnmergedTree=true)
  editor.performSemanticsAction(SemanticsActions.RequestFocus)
  editor.performKeyInput { keyDown(androidx.compose.ui.input.key.Key.DirectionCenter);keyUp(androidx.compose.ui.input.key.Key.DirectionCenter) }
  compose.onNode(hasSetTextAction()).performTextReplacement("0")
  compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.onAllNodesWithText("Enter a number from 1 to 365.").fetchSemanticsNodes().also{assertTrue(it.isNotEmpty())}
  assertEquals(1,prefs.cacheConfig.imageDays)
  compose.onNode(hasSetTextAction()).performTextReplacement("2")
  compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.waitUntil(10000){CacheManagement.configuration.imageDays==2}
  compose.runOnIdle {runBlocking {loader.execute(coil.request.ImageRequest.Builder(app).data("https://example.test/poster.jpg").build())}}
  assertNotEquals(captured[0].diskCacheKey,captured.last().diskCacheKey)
  assertEquals("max-age=172800",captured.last().headers["Cache-Control"])
  val backup=BackupPersistence(app,FixtureSecrets()).snapshot()
  compose.runOnIdle{part="metadata-duration"}
  compose.onNodeWithText("6").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.waitUntil(10000){CacheManagement.configuration.metadataHours==6}
  assertEquals(2,prefs.cacheConfig.imageDays)
  BackupPersistence(app,FixtureSecrets()).restore(backup)
  compose.waitUntil(10000){CacheManagement.configuration.metadataHours==24 && CacheManagement.configuration.imageDays==2}
  runBlocking{CacheManagement.usage(app)}
  val metadata=File(app.cacheDir,"managed-responses/metadata/fixture.cache").apply{parentFile!!.mkdirs();writeText("disposable data")}
  compose.runOnIdle{part="cache-size"}
  compose.onNodeWithText("Current Cache Size").assertExists()
  compose.onNodeWithText("Clear Cache Data").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.onNodeWithText("Clear Cache Data?").assertExists()
  compose.onNodeWithText("Clear cache data").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.waitUntil(10000){!metadata.exists() && compose.onAllNodesWithText("Clear Cache Data?").fetchSemanticsNodes().isEmpty()}
  assertEquals(libraryBefore,library.readText())
  assertEquals("fixture-encrypted-user-data",secret.getString("fixture.credential",null))
  assertEquals("keep active download",activeUpdate.readText())
  assertEquals(2,prefs.cacheConfig.imageDays)
  loader.shutdown()
 }
}
