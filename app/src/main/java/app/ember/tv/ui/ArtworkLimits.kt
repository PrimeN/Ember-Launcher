package app.ember.tv.ui

/** Small external badges never need original-resolution decoding or unbounded layout width. */
internal object BadgeArtwork {
    const val MAX_WIDTH=512
    const val MAX_HEIGHT=128
    fun ratio(width:Float,height:Float):Float =
        if(!width.isFinite() || !height.isFinite() || width<=0 || height<=0)3f else (width/height).coerceIn(.25f,12f)
}

/** A byte-bounded cache drops references only: visible Compose images may still own a bitmap. */
internal class WeightedArtworkCache<K:Any,V:Any>(private val maxBytes:Int,private val weight:(V)->Int) {
    private val entries=LinkedHashMap<K,V>(16,.75f,true)
    var bytes:Int=0;private set
    @Synchronized fun get(key:K):V?=entries[key]
    @Synchronized fun put(key:K,value:V) {
        entries.remove(key)?.let {bytes-=weight(it).coerceAtLeast(0)}
        val size=weight(value).coerceAtLeast(0)
        if(size>maxBytes.coerceAtLeast(0))return
        entries[key]=value;bytes+=size
        while(bytes>maxBytes && entries.isNotEmpty()) {
            val first=entries.keys.first();bytes-=weight(entries.remove(first)!!).coerceAtLeast(0)
        }
    }
    @Synchronized fun clear(){entries.clear();bytes=0}
}

internal object ArtworkStorageBudget {
    private const val MIB=1024L*1024
    fun disk(available:Long)=if(available<=0)128*MIB else (available/20).coerceIn(16*MIB,512*MIB)
    fun apps(heapMb:Int,lowRam:Boolean)=if(lowRam)4*1024*1024 else (heapMb.toLong()*1024*1024/32).coerceIn(4*MIB,12*MIB).toInt()
    fun banner(width:Int,height:Int):Pair<Int,Int> {
        val w=width.takeIf {it>0} ?: 320;val h=height.takeIf {it>0} ?: 180
        val scale=minOf(1.0,640.0/maxOf(w,h))
        return (w*scale).toInt().coerceAtLeast(1) to (h*scale).toInt().coerceAtLeast(1)
    }
}
