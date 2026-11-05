package app.ember.tv.iptv

import java.time.Instant
import java.time.ZoneId
import java.util.PriorityQueue

/** Work and output are bounded even for unusually large imported XMLTV feeds. */
object ProgrammeSearch {
    data class Hit(val channel: Channel, val programme: Programme)
    data class Results(val hits: List<Hit>, val limited: Boolean)
    fun find(query: String, channels: List<Channel>, guide: Map<String,List<Programme>>, now: Long,
             limit: Int = 60, budget: Int = 200_000, checkCancelled: () -> Unit = {}): Results {
        val text=query.trim().take(120)
        if(text.length<2)return Results(emptyList(),false)
        val cap=limit.coerceIn(1,100)
        val order=compareBy<Hit> { if(it.programme.start<=now) 0 else 1 }
            .thenBy {it.programme.start}.thenBy {it.channel.name}.thenBy {it.channel.key}
        val best=PriorityQueue(cap,order.reversed())
        var scanned=0;var matches=0;var limited=false
        outer@ for(channel in channels) for(programme in guide[channel.key].orEmpty()) {
            if(scanned++ >= budget.coerceIn(1,1_000_000)){limited=true;break@outer}
            if(scanned%128==0)checkCancelled()
            if(programme.stop<=now || programme.stop<=programme.start || programme.start>now+7*86_400_000L)continue
            if(!programme.title.contains(text,true) && programme.subtitle?.contains(text,true)!=true)continue
            matches++;best.add(Hit(channel,programme));if(best.size>cap)best.poll()
        }
        checkCancelled()
        return Results(best.toList().sortedWith(order),limited || matches>cap)
    }
    /** If the evening has already started, stay at now rather than jumping into tomorrow. */
    fun tonight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val evening=Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(20,0).atZone(zone).toInstant().toEpochMilli()
        return maxOf(now,evening)
    }
}
