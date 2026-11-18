package app.ember.tv.data

/** Shared by the primary action and the built-in player's existing resume confirmation. */
object ResumeAction {
    fun label(saved:Pair<Long,Long>?,fraction:Float?):String? {
        if(saved!=null && saved.first>30_000 && saved.second>0 && saved.first<saved.second*0.95) {
            return "Resume from "+clock(saved.first)
        }
        return fraction?.takeIf {it.isFinite()&&it in 0.02f..0.95f}?.let {"Resume at ${(it*100).toInt()}%"}
    }
    /** "28:00" or "1:02:03". */
    fun clock(ms:Long):String {val t=ms/1000;val h=t/3600;val m=t%3600/60;val sec=t%60
        return if(h>0)String.format(java.util.Locale.US,"%d:%02d:%02d",h,m,sec) else String.format(java.util.Locale.US,"%d:%02d",m,sec)}
    /** Exact saved position wins. Percentage-only progress keeps its uncertainty explicit. */
    fun buttonLabel(saved:Pair<Long,Long>?,fraction:Float?,durationMs:Long?=null):String {
        if(label(saved,fraction)==null)return "Play"
        val exact=saved!=null && saved.first>30_000 && saved.second>0 && saved.first<saved.second*.95
        val position=if(exact)saved.first
            else if(fraction!=null && fraction.isFinite() && fraction in .02f..0.95f && durationMs!=null && durationMs>0)(durationMs*fraction.toDouble()).toLong() else null
        if(position==null)return "Resume at ${(fraction!!*100).toInt()}%"
        // #332: the same wording as the resume question: "Resume from 28:00", "Resume from 1:02:03".
        return (if(exact)"Resume from " else "Resume from ≈ ")+clock(position)
    }

}
