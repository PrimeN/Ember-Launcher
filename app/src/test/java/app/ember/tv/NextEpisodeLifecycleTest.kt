package app.ember.tv

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import app.ember.tv.data.Episode
import app.ember.tv.data.Prefs
import app.ember.tv.player.*
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NextEpisodeLifecycleTest {
    private class Source:AutoCloseable {
        val socket=ServerSocket(0)
        val arrivals=AtomicInteger()
        val release=CountDownLatch(1)
        val clients=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        init {thread(isDaemon=true) {
            while(!socket.isClosed) {
                val client=try {socket.accept()}catch(_:Exception){break}
                clients.add(client)
                thread(isDaemon=true) {client.use {c->runCatching {
                    val input=c.getInputStream().bufferedReader();val first=input.readLine()
                    while(!input.readLine().isNullOrBlank()){}
                    val manifest=first.contains("manifest.json")
                    if(manifest){arrivals.incrementAndGet();release.await(5,TimeUnit.SECONDS)}
                    val body=if(manifest) """{"id":"local","name":"Local","version":"1","types":["series"],"resources":["stream"],"catalogs":[]}""" else """{"streams":[]}"""
                    c.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray());flush()}
                }};clients.remove(client)}
            }
        }}
        override fun close(){release.countDown();socket.close();clients.forEach {runCatching {it.close()}}}
    }
    private fun await(condition:()->Boolean){val until=System.nanoTime()+4_000_000_000L;while(!condition()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)};assertTrue(condition())}
    private fun activity(source:Source):PlayerActivity {
        val a=Robolectric.buildActivity(PlayerActivity::class.java).get()
        a.getSharedPreferences("ember",0).edit().putString("addons","http://127.0.0.1:${source.socket.localPort}/manifest.json").commit()
        a.prefs=Prefs(a);a.view=PlayerView(a);a.upNext=View(a);a.upNextUi.visible=true
        a.nextEpisodes.add(Episode("tt1234567:1:2",1,2,"Next",null,null))
        return a
    }
    @Test fun repeatedNextCommandsShareOneTransition() {
        Source().use {s->val a=activity(s);try {
            a.playNext();a.playNext();await {s.arrivals.get()==1}
            assertEquals(1,s.arrivals.get());assertTrue(a.upNextUi.finding)
        } finally {a.lifecycleScope.cancel()}}
    }
    @Test fun cancellingUpNextCancelsTheLookupAndIgnoresLateFailure() {
        Source().use {s->val a=activity(s);try {
            a.playNext();await {s.arrivals.get()==1};a.dismissUpNext()
            assertFalse(a.upNextUi.visible);assertFalse(a.isFinishing)
            s.release.countDown();await {a.nextEpisodeJob==null};assertFalse(a.isFinishing)
        } finally {a.lifecycleScope.cancel()}}
    }
    @Test fun autoplayUsesTheConfiguredAddonTimeout() {
        Source().use {s->val a=activity(s);try {
            a.prefs.addonTimeout=1;a.playNext();await {s.arrivals.get()==1}
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
            await {a.nextEpisodeJob==null};assertFalse(a.upNextUi.finding);assertTrue(a.isFinishing)
            assertEquals(1,s.arrivals.get())
        } finally {a.lifecycleScope.cancel()}}
    }

}
