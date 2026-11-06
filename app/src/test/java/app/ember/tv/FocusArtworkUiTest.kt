package app.ember.tv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.data.FocusBackground
import app.ember.tv.ui.*
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusArtworkUiTest {
    @get:Rule val compose=createComposeRule()
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private lateinit var loader:ImageLoader
    private var load:suspend (String)->Drawable={throw IOException("Artwork unavailable")}
    private val published=AtomicReference<ReadyArtwork?>()
    private val errors=java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun images() {
        Ui.day=true
        loader=ImageLoader.Builder(context).eventListener(object:coil.EventListener {
            override fun onError(request:coil.request.ImageRequest,result:coil.request.ErrorResult) {
                errors.add("${request.data}: ${result.throwable}")
            }
        }).components {
            add(object:Fetcher.Factory<android.net.Uri> {
                override fun create(data:android.net.Uri,options:Options,imageLoader:ImageLoader):Fetcher? =
                    if(data.scheme!="fixture")null else object:Fetcher {
                        override suspend fun fetch():FetchResult=DrawableResult(load(data.toString()),false,DataSource.NETWORK)
                    }
            })
        }.build()
        Coil.setImageLoader(loader)
    }
    @After fun clean() {loader.shutdown();Coil.reset();Ui.day=false}

    private fun image(color:Int,width:Int=160,height:Int=90)=BitmapDrawable(context.resources,
        Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {eraseColor(color)})
    private fun shownColor()=(published.get()?.drawable as? BitmapDrawable)?.bitmap?.getPixel(0,0)
    private fun awaitColor(color:Int) {
        try {compose.waitUntil(5000) {compose.waitForIdle();shownColor()==color}}
        catch(error:ComposeTimeoutException) {
            fail("Expected selected artwork $color, found ${shownColor()} (${published.get()?.drawable}); image errors: $errors")
        }
    }

    private fun scene(state:FocusBackdropState,mode:String=FocusBackground.ARTWORK,navigation:Boolean=false) {
        val nav=NavigationFocus()
        compose.setContent {
            CompositionLocalProvider(LocalArtworkPresented provides {published.set(it)},LocalNavigationFocus provides nav,
                LocalPhotoBackground provides (mode==FocusBackground.ARTWORK),
                LocalRenderOptions provides RenderOptions(glass=false,quality="balanced")) {
                EmberTheme(0) {
                    Box(Modifier.size(600.dp,340.dp).background(Color.White).testTag("scene")) {
                        FocusBackdropLayer(state,false,mode,FocusBackground.OFF)
                        ArtworkPageContent {
                        androidx.tv.material3.Text("Selected media",color=Fg,modifier=Modifier.testTag("caption"))
                        if(navigation) {
                            var selected by remember {mutableStateOf("home")}
                            Row(Modifier.fillMaxWidth().initialNavigationFocus(nav,selected,true)) {
                                for((index,id) in listOf("search","home","live").withIndex()) {
                                    Box(Modifier.size(60.dp).navigationTabTarget(nav,id).focusProperties {
                                        val tabs=listOf("search","home","live")
                                        right=tabs.getOrNull(index+1)?.let(nav::tab) ?: FocusRequester.Cancel
                                    }.openNavigationTab(id,selected==id){selected=id}.testTag(id).focusable())
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    @Test fun failedServerBackdropFallsBackToPosterAndKeepsDayColoursVibrant() {
        val poster=image(android.graphics.Color.BLUE)
        val attempts=java.util.Collections.synchronizedList(mutableListOf<String>())
        load={url->attempts.add(url);if(url.endsWith("backdrop"))throw IOException("Missing backdrop") else poster}
        val tile=FocusTile(FocusTileKind.MEDIA,"server-movie","fixture://backdrop","fixture://poster")
        val state=FocusBackdropState().apply {focus(tile,FocusTileImage(image(android.graphics.Color.RED,100,150),"card"))}
        scene(state)
        awaitColor(android.graphics.Color.BLUE)
        assertEquals(listOf("fixture://backdrop","fixture://poster"),attempts.toList())
        val pixels=compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        val clear=pixels[pixels.width*9/10,pixels.height/2]
        assertTrue("Day mode must not wash the background white: $clear",clear.blue>.95f && clear.red<.05f && clear.green<.05f)
        val text=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("caption").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(text)}
        assertTrue("Captions must stay light over the vivid photo",text.single().layoutInput.style.color.red>.8f)
    }

    @Test fun stalledArtworkKeepsTheSelectedPosterVisibleAndNavigationResponsive() {
        val started=AtomicBoolean(false)
        val pending=CompletableDeferred<Drawable>()
        load={started.set(true);pending.await()}
        val state=FocusBackdropState().apply {
            focus(FocusTile(FocusTileKind.MEDIA,"server-movie","fixture://pending"),
                FocusTileImage(image(android.graphics.Color.RED,100,150),"card"))
        }
        scene(state,navigation=true)
        compose.waitUntil(5000) {started.get() && shownColor()==android.graphics.Color.RED}
        compose.onRoot().performKeyInput {keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)}
        compose.onNodeWithTag("live").assertIsFocused()
        assertFalse("Navigation must work before artwork completes",pending.isCompleted)
    }

    @Test fun aLateImageCannotReplaceTheNewSelection() {
        val pending=CompletableDeferred<Drawable>()
        val started=AtomicBoolean(false)
        load={started.set(true);pending.await()}
        val state=FocusBackdropState().apply {
            focus(FocusTile(FocusTileKind.MEDIA,"old","fixture://pending"),FocusTileImage(image(android.graphics.Color.RED),"old"))
        }
        scene(state)
        compose.waitUntil(5000) {started.get()}
        compose.runOnIdle {state.focus(FocusTile(FocusTileKind.MEDIA,"new",null),FocusTileImage(image(android.graphics.Color.GREEN),"new"))}
        awaitColor(android.graphics.Color.GREEN)
        pending.complete(image(android.graphics.Color.BLUE))
        compose.runOnIdle {assertEquals(android.graphics.Color.GREEN,shownColor())}
    }

    @Test fun themeOnlyDoesNotFetchOrPublishSelectedArtwork() {
        val requested=AtomicBoolean(false)
        load={requested.set(true);image(android.graphics.Color.BLUE)}
        val state=FocusBackdropState().apply {focus(FocusTile(FocusTileKind.MEDIA,"movie","fixture://art"),null)}
        scene(state,FocusBackground.OFF)
        compose.runOnIdle {assertFalse(requested.get());assertNull(published.get()?.drawable)}
    }
}
