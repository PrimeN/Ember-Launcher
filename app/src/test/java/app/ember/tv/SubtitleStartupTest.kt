package app.ember.tv

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import app.ember.tv.data.*
import app.ember.tv.player.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleStartupTest {
    @Test fun sourceSelectionDoesNotWaitForTheSupplementarySubtitleEndpoint()=runBlocking {
        val listener=ServerSocket(0);val release=CountDownLatch(1);val requested=CountDownLatch(1);val count=AtomicInteger()
        val clients=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        val accept=thread(isDaemon=true) {
            while(!listener.isClosed) {
                val c=try{listener.accept()}catch(_:Exception){break};clients.add(c)
                thread(isDaemon=true){c.use {runCatching {
                    val input=c.getInputStream().bufferedReader();val path=input.readLine()
                    while(!input.readLine().isNullOrBlank()){}
                    val body=when {
                        path.contains("manifest.json")->"""{"id":"local","name":"Local","version":"1","types":["series"],"resources":["stream","subtitles"],"catalogs":[]}"""
                        path.contains("/stream/")->"""{"streams":[{"url":"https://example.invalid/play.mp4","name":"1080p"}]}"""
                        else->{count.incrementAndGet();requested.countDown();release.await(5,TimeUnit.SECONDS);"""{"subtitles":[{"id":"one","url":"https://example.invalid/sub.srt","lang":"en"}]}"""}
                    }
                    c.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray());flush()}
                }};clients.remove(c)}
            }
        }
        try {
            val c:Application=RuntimeEnvironment.getApplication()
            c.getSharedPreferences("ember",0).edit().putString("addons","http://127.0.0.1:${listener.localPort}/manifest.json").commit()
            val selected=withTimeout(3000){NextEpisodeLoader(c).select(Episode("tt1234567:1:2",1,2,"Next",null,null),null,null)}!!
            assertEquals("https://example.invalid/play.mp4",selected.stream.url);assertEquals(0,count.get())
            val subtitles=async {selected.subtitles()}
            assertTrue(withContext(Dispatchers.IO){requested.await(2,TimeUnit.SECONDS)})
            assertFalse(subtitles.isCompleted)
            release.countDown();assertEquals("https://example.invalid/sub.srt",subtitles.await().single().url)
        } finally {release.countDown();listener.close();clients.forEach {runCatching {it.close()}};accept.join(1000)}
    }
    @Test fun lateOptionsDoNotUndoAnExplicitSubtitlesOffChoice() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).get()
        val player=ExoPlayer.Builder(activity).build()
        try {
            val controller=SubtitleController(activity,{player},SubtitleEngine(SubtitleView(activity)),SubtitleConfig(autoSelect="always"),{})
            controller.selectOff();controller.onReady()
            controller.addOptions(listOf(SubtitleOption("Local","en","https://example.invalid/sub.srt")))
            assertTrue(controller.candidates.isNotEmpty());assertEquals(SubtitleController.Selection.Off,controller.selection)
        } finally {player.release()}
    }
}
