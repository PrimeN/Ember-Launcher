package app.ember.tv.player

/** Transport addresses live only in memory. Saved positions use a one-way identity. */
internal data class QueueMedia(val uri:String,val title:String,val identity:String?=null) {
    val key:String get()=identity ?: "file:"+java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString(""){"%02x".format(it)}
}
internal class MediaQueue(items:List<QueueMedia>,val resources:List<java.io.Closeable> = emptyList()) {
    val items=items.take(200).toMutableList()
    var index=0;private set
    var repeat="off"
    val current get()=items.getOrNull(index)
    fun select(at:Int):QueueMedia? {if(at !in items.indices)return null;index=at;return current}
    fun next(ended:Boolean=false):QueueMedia? = select(when {ended&&repeat=="one"->index;index<items.lastIndex->index+1;repeat=="all"->0;else->return null})
    fun previous():QueueMedia?=select(index-1)
    fun add(values:List<QueueMedia>){items.addAll(values.take((200-items.size).coerceAtLeast(0)))}
    fun move(from:Int,to:Int) {
        if(from !in items.indices||to !in items.indices)return
        val selected=index;items.add(to,items.removeAt(from));index=when {from==selected->to;from<selected&&to>=selected->selected-1;from>selected&&to<=selected->selected+1;else->selected}
    }
    fun remove(at:Int):Boolean {
        if(at !in items.indices||at==index)return false
        items.removeAt(at);if(at<index)index--;return true
    }
}
internal object MediaQueues {
    private val queues=java.util.concurrent.ConcurrentHashMap<String,MediaQueue>()
    fun put(items:List<QueueMedia>,resources:List<java.io.Closeable> = emptyList()):String=java.util.UUID.randomUUID().toString().also {queues[it]=MediaQueue(items,resources)}
    fun get(id:String?)=id?.let(queues::get)
    fun launch(items:List<QueueMedia>,resources:List<java.io.Closeable> = emptyList(),open:(String)->Unit) {
        val id=put(items,resources)
        try {open(id)} catch(e:Exception) {release(id);throw e}
    }
    fun release(id:String?){id?.let(queues::remove)?.resources?.forEach {runCatching {it.close()}}}
}

/** A repeat range belongs to one item, and never silently repeats a previous title. */
internal class RepeatRange {
    var start:Long?=null;private set
    var end:Long?=null;private set
    fun markStart(position:Long){start=position.coerceAtLeast(0);end=null}
    fun markEnd(position:Long):Boolean {val first=start?:return false;if(position-first<1000)return false;end=position;return true}
    fun destination(position:Long):Long?=start?.takeIf {end!=null && position>=end!!}
    fun clear(){start=null;end=null}
}
