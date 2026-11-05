package app.ember.tv.iptv

/** Original provider order wins, including overlaps and unsorted legacy guide snapshots. */
internal class ProgrammeLookup(private val programmes:List<Programme>) {
    private val base=Integer.highestOneBit((programmes.size-1).coerceAtLeast(1)) shl 1
    private val minStart=LongArray(base*2){Long.MAX_VALUE}
    private val maxStart=LongArray(base*2){Long.MIN_VALUE}
    private val maxStop=LongArray(base*2){Long.MIN_VALUE}
    init {
        programmes.forEachIndexed {i,p->minStart[base+i]=p.start;maxStart[base+i]=p.start;maxStop[base+i]=p.stop}
        for(i in base-1 downTo 1) {
            minStart[i]=minOf(minStart[i*2],minStart[i*2+1])
            maxStart[i]=maxOf(maxStart[i*2],maxStart[i*2+1])
            maxStop[i]=maxOf(maxStop[i*2],maxStop[i*2+1])
        }
    }
    private fun first(from:Int,time:Long,kind:Int,node:Int=1,left:Int=0,right:Int=base):Int {
        if(right<=from || left>=programmes.size)return -1
        val possible=when(kind) {0->minStart[node]<=time && maxStop[node]>time;1->maxStart[node]>=time;else->maxStop[node]>time}
        if(!possible)return -1
        if(right-left==1)return left
        val middle=(left+right).ushr(1)
        val earlier=first(from,time,kind,node*2,left,middle)
        return if(earlier>=0)earlier else first(from,time,kind,node*2+1,middle,right)
    }
    fun nowNext(at:Long):Pair<Programme?,Programme?> {
        val current=programmes.getOrNull(first(0,at,0))
        return current to programmes.getOrNull(first(0,current?.stop ?: at,1))
    }
    fun upcoming(at:Long,count:Int):List<Programme> {
        require(count>=0){"Requested element count $count is less than zero."}
        return buildList {
            var from=0
            repeat(minOf(count,programmes.size)) {
                val next=first(from,at,2)
                if(next<0)return@buildList
                add(programmes[next]);from=next+1
            }
        }
    }
}

/** Bounded by the active channels; replacing an immutable guide list invalidates its index. */
internal class ProgrammeLookupCache(private val limit:Int=64) {
    private data class Entry(val programmes:List<Programme>,val index:ProgrammeLookup)
    private val entries=LinkedHashMap<String,Entry>(16,.75f,true)
    fun get(channel:String,programmes:List<Programme>):ProgrammeLookup {
        entries[channel]?.takeIf {it.programmes===programmes}?.let {return it.index}
        return ProgrammeLookup(programmes).also {index->
            entries[channel]=Entry(programmes,index)
            while(entries.size>limit.coerceAtLeast(1))entries.remove(entries.keys.first())
        }
    }
    fun clear()=entries.clear()
}
