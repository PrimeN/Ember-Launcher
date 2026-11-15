package app.ember.tv

import android.app.Application
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import app.ember.tv.player.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.net.InetAddress
import java.time.Duration

@Implements(NsdManager::class)
class ShareDiscoveryShadow {
    companion object {
        var discovery:NsdManager.DiscoveryListener?=null
        val resolves=mutableListOf<Pair<NsdServiceInfo,NsdManager.ResolveListener>>()
        var stops=0
    }
    @Implementation fun discoverServices(type:String,protocol:Int,listener:NsdManager.DiscoveryListener) {discovery=listener}
    @Implementation fun stopServiceDiscovery(listener:NsdManager.DiscoveryListener) {stops++}
    @Implementation fun resolveService(info:NsdServiceInfo,listener:NsdManager.ResolveListener) {resolves+=info to listener}
    @Implementation fun stopServiceResolution(listener:NsdManager.ResolveListener) {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,shadows=[ShareDiscoveryShadow::class])
class SmbDiscoveryLifecycleTest {
    @Before fun reset(){ShareDiscoveryShadow.discovery=null;ShareDiscoveryShadow.resolves.clear();ShareDiscoveryShadow.stops=0}
    private fun service(name:String)=NsdServiceInfo().apply {serviceName=name;serviceType="_smb._tcp.";port=445;host=InetAddress.getByName("192.0.2.1")}
    @Test fun resolutionIsSerializedAndLateCallbacksCannotReopenClosedDiscovery() {
        var updates=0
        val finder=SmbDiscovery(RuntimeEnvironment.getApplication(),{updates++},{error("Closing is silent")})
        val listener=ShareDiscoveryShadow.discovery!!
        listener.onServiceFound(service("One"));listener.onServiceFound(service("Two"));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,ShareDiscoveryShadow.resolves.size)
        val first=ShareDiscoveryShadow.resolves.single()
        first.second.onServiceResolved(first.first);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,updates);assertEquals(2,ShareDiscoveryShadow.resolves.size)
        finder.close();finder.close();assertEquals(1,ShareDiscoveryShadow.stops)
        ShareDiscoveryShadow.resolves.last().let {it.second.onServiceResolved(it.first)}
        listener.onServiceFound(service("Three"));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,updates);assertEquals(2,ShareDiscoveryShadow.resolves.size)
    }
    @Test fun discoveryTimesOutOnceAndStopsItsSystemListener() {
        var finished=0
        val finder=SmbDiscovery(RuntimeEnvironment.getApplication(),{}, {failed->assertFalse(failed);finished++})
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        assertEquals(1,finished);assertEquals(1,ShareDiscoveryShadow.stops)
        finder.close();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1,finished);assertEquals(1,ShareDiscoveryShadow.stops)
    }
    @Test fun multipleAdvertisementsForOneServerStayDeduplicatedAfterAnotherServerDisappears() {
        var found=emptyList<SmbServer>()
        val finder=SmbDiscovery(RuntimeEnvironment.getApplication(),{found=it},{})
        try {
            val listener=ShareDiscoveryShadow.discovery!!
            val third=service("Other").apply {host=InetAddress.getByName("192.0.2.2")}
            listOf(service("Name"),service("Alias"),third).forEach {listener.onServiceFound(it)}
            shadowOf(Looper.getMainLooper()).idle()
            repeat(3){index->ShareDiscoveryShadow.resolves[index].let {it.second.onServiceResolved(it.first)};shadowOf(Looper.getMainLooper()).idle()}
            assertEquals(2,found.size)
            listener.onServiceLost(third);shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,found.size)
        } finally {finder.close()}
    }
}
