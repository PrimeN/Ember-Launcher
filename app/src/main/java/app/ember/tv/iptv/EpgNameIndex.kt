package app.ember.tv.iptv

/** A channel may retain a safe alias after another alias becomes ambiguous. */
internal class EpgNameIndex(private val wanted:Set<String>) {
    val names=hashMapOf<String,String>()
    private val ambiguous=hashSetOf<String>()
    private val references=hashMapOf<String,Int>()
    fun add(label:String,id:String) {
        val key=Xmltv.norm(label)
        if(key !in wanted || key in ambiguous)return
        val old=names[key]
        if(old==id)return
        if(old!=null) {
            names.remove(key);ambiguous.add(key)
            val count=references.getValue(old)-1
            if(count==0)references.remove(old) else references[old]=count
        } else {
            names[key]=id
            references[id]=(references[id]?:0)+1
        }
    }
    fun containsId(id:String?)=id!=null && id in references
}
