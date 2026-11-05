package app.ember.tv.data

/** Launcher budgets only: never changes video quality, decoder, source selection or refresh intervals. */
object DevicePerformance {
    data class Capabilities(val lowRam: Boolean, val heapMb: Int, val totalBytes: Long)
    fun capabilities(context: android.content.Context): Capabilities = runCatching {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        Capabilities(manager?.isLowRamDevice ?: true, manager?.memoryClass ?: 0, memory.totalMem)
    }.getOrDefault(Capabilities(true, 0, 0))
    /**
     * One performance profile sets image quality, effects and glass together. Ultra (4K artwork and
     * hero pictures) is offered only on a 4K display; Custom keeps every control separate.
     */
    val options=listOf("auto" to "Auto", "low" to "Low", "medium" to "Medium", "high" to "High", "ultra" to "Ultra (4K)", "custom" to "Custom")
    fun choices(uhdDisplay:Boolean)=options.filter {uhdDisplay || it.first!="ultra"}
    fun normalize(value:String?)=value?.takeIf {v->options.any {it.first==v}} ?: "auto"
    /** The memory class: "low" or "high" (Medium, High and Ultra are all "high" here). */
    fun resolve(value:String,lowRam:Boolean,heapMb:Int,totalBytes:Long):String=when(normalize(value)) {
        "low"->"low"
        "medium","high","ultra"->"high"
        else->if(lowRam || heapMb<192 || totalBytes<3L*1024*1024*1024)"low" else "high"
    }
    /** The profile in effect: Auto becomes Low, Medium or High from memory; Ultra needs a 4K display. */
    fun profile(value:String,lowRam:Boolean,heapMb:Int,totalBytes:Long,uhdDisplay:Boolean):String=when(val v=normalize(value)) {
        "auto"->when {
            resolve("auto",lowRam,heapMb,totalBytes)=="low"->"low"
            heapMb>=256 && totalBytes>=4L*1024*1024*1024->"high"
            else->"medium"
        }
        "ultra"->if(uhdDisplay)"ultra" else "high"
        else->v
    }
    /** Medium keeps subtle motion and focus colours but skips hero analysis, progressive artwork and previews. */
    val MEDIUM_EFFECTS=CustomPerformance(heroAnalysis=false,colorAnalysis=true,animations=true,progressiveArtwork=false,collectionMotion=false,prefetch=1)
    fun label(profile:String)=options.firstOrNull {it.first==profile}?.second ?: "Auto"
}
