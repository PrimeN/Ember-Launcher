package app.ember.tv.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.URI

internal object SmbShares {
    fun visible(values:List<Pair<String?,Int>>):List<String> = values.asSequence()
        .filter {(name,type)->type and 0xffff==0 && name!=null && name.length in 1..255 && !name.endsWith('$') &&
            runCatching {NetworkLocation.child("",name)}.isSuccess}
        .map {it.first!!}.distinctBy {it.lowercase(java.util.Locale.ROOT)}.take(256)
        .sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
    fun address(host:String,port:Int):String? = runCatching {
        require(host.isNotBlank() && port in 1..65535)
        URI("smb",null,host,if(port==445)-1 else port,null,null,null).toASCIIString()
    }.getOrNull()
}

internal data class SmbServer(val name:String,val address:String)

/** DNS-SD only, on explicit request. No subnet scans, saved passwords, or startup work. */
@Suppress("DEPRECATION")
internal class SmbDiscovery(context:Context,private val changed:(List<SmbServer>)->Unit,private val finished:(Boolean)->Unit):AutoCloseable {
    private val manager=context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main=Handler(Looper.getMainLooper())
    private val pending=java.util.ArrayDeque<NsdServiceInfo>()
    private val seen=mutableSetOf<String>()
    private val servers=linkedMapOf<String,SmbServer>()
    private fun publish()=changed(servers.values.distinctBy {it.address})
    private var resolving=false
    private var resolution:NsdManager.ResolveListener?=null
    private var active=true
    private val timeout=Runnable {stop(false)}
    private val listener=object:NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type:String) {}
        override fun onDiscoveryStopped(type:String) {}
        override fun onStartDiscoveryFailed(type:String,error:Int) {main.post {stop(true)}}
        override fun onStopDiscoveryFailed(type:String,error:Int) {}
        override fun onServiceLost(info:NsdServiceInfo) {main.post {
            if(active){servers.remove(info.serviceName);publish()}
        }}
        override fun onServiceFound(info:NsdServiceInfo) {main.post {
            if(active && info.serviceType.trimEnd('.')=="_smb._tcp" && seen.size<32 && seen.add(info.serviceName)) {
                pending.add(info);resolveNext()
            }
        }}
    }
    init {
        try {manager.discoverServices("_smb._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);main.postDelayed(timeout,15_000)}
        catch(_:RuntimeException) {main.post {stop(true)}}
    }
    private fun resolveNext() {
        if(!active || resolving || pending.isEmpty())return
        val info=pending.removeFirst();resolving=true
        val callback=object:NsdManager.ResolveListener {
            override fun onResolveFailed(info:NsdServiceInfo,error:Int) {main.post {resolving=false;resolveNext()}}
            override fun onServiceResolved(info:NsdServiceInfo) {main.post {
                if(active) {
                    val address=info.host?.hostAddress?.let {SmbShares.address(it,info.port)}
                    if(address!=null){servers[info.serviceName]=SmbServer(info.serviceName.take(100),address);publish()}
                }
                resolving=false;resolveNext()
            }}
        }
        resolution=callback
        try {manager.resolveService(info,callback)} catch(_:RuntimeException){resolving=false;resolveNext()}
    }
    private fun stop(failed:Boolean,notify:Boolean=true) {
        if(!active)return
        active=false;pending.clear();main.removeCallbacks(timeout)
        runCatching {manager.stopServiceDiscovery(listener)}
        if(android.os.Build.VERSION.SDK_INT>=34)resolution?.let {runCatching {manager.stopServiceResolution(it)}}
        resolution=null
        if(notify)finished(failed)
    }
    override fun close() {stop(false,notify=false)}
}
