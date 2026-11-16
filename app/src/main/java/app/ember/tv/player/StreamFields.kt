package app.ember.tv.player

internal data class StreamField(val label:String,val unit:String,val suggested:List<Int>,val read:(StreamConfig)->Int,val write:(StreamConfig,Int)->StreamConfig) {
    fun choices(p:StreamConfig)=(suggested+read(p.normalized())).distinct().sorted()
}
internal val timeoutFields=listOf(
    StreamField("Connect timeout","s",listOf(5,10,15,20,30,60,120),{it.connectSeconds},{p,v->p.copy(connectSeconds=v)}),
    StreamField("Read timeout","s",listOf(5,10,20,30,60,90,120,180,300),{it.readSeconds},{p,v->p.copy(readSeconds=v)}))
internal val bufferFields=listOf(
    StreamField("Forward buffer","MiB",listOf(8,16,24,32,48,64,96,128,192,256,384,512),{it.forwardMiB},{p,v->p.copy(forwardMiB=v)}),
    StreamField("Rewind buffer · Compatibility","MiB",listOf(0,4,8,16,24,32,48,64,96,128),{it.rewindMiB},{p,v->p.copy(rewindMiB=v)}),
    StreamField("Buffer ahead","s",listOf(10,20,30,60,120,180,240,300,600),{it.aheadSeconds},{p,v->p.copy(aheadSeconds=v)}),
    StreamField("Refill before resuming","s",listOf(1,2,3,5,10,15,20,30),{it.refillSeconds},{p,v->p.copy(refillSeconds=v)}),
    StreamField("Read-ahead target","s",listOf(0,1,5,10,20,30,60,120,180,300),{it.readAheadSeconds},{p,v->p.copy(readAheadSeconds=v)}))
