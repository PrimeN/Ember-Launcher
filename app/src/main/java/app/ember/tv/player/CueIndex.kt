package app.ember.tv.player

/** Interval tree for caption lookup, preserving overlaps and backward seeks. */
internal class CueIndex<T>(values: List<T>, start: (T)->Long, end: (T)->Long) {
    private data class Entry<T>(val value:T,val start:Long,val end:Long,val order:Int)
    private class Node<T>(val entries:List<Entry<T>>) {
        val maxEnd=entries.maxOfOrNull { it.end } ?: Long.MIN_VALUE
        val middle=entries.size/2
        val left=if(middle>0)Node(entries.subList(0,middle)) else null
        val right=if(middle+1<entries.size)Node(entries.subList(middle+1,entries.size)) else null
        fun find(time:Long,out:MutableList<Entry<T>>) {
            if(time>=maxEnd||entries.isEmpty())return
            left?.find(time,out)
            val e=entries[middle]
            if(e.start<=time) { if(time<e.end)out+=e;right?.find(time,out) }
        }
    }
    private val root=Node(values.mapIndexed { i,v->Entry(v,start(v),end(v),i) }.sortedBy { it.start })
    fun at(time:Long):List<T> {val out=mutableListOf<Entry<T>>();root.find(time,out);return out.sortedBy { it.order }.map { it.value }}
}
