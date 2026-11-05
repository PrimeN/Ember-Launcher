package app.ember.tv.data

import java.util.Locale

/** A content lock is enforced after provider filters, including when metadata is missing. */
object KidsContentPolicy {
    enum class Decision { ALLOW, DENY, NEEDS_METADATA }
    val genres=listOf("Animation","Family","Kids","Comedy","Adventure","Action","Documentary","Drama",
        "Fantasy","Science Fiction","Music","Mystery","History","Romance","Thriller","Horror","Western","War","Crime")
    val ratings=listOf("TV-Y","TV-G","TV-Y7","TV-PG","TV-14","TV-MA")
    private val ranks=mapOf("TV-Y" to 0,"TV-G" to 1,"G" to 1,"TV-Y7" to 2,"TV-Y7-FV" to 2,
        "TV-PG" to 3,"PG" to 3,"TV-14" to 4,"PG-13" to 4,"TV-MA" to 5,"R" to 5,"NC-17" to 6)
    fun ratingRank(value:String?):Int?=value?.trim()?.uppercase(Locale.ROOT)?.removePrefix("US:")?.trim()?.let(ranks::get)
    fun restricted(profile:Profile)=profile.kids && (profile.lockedGenre!=null || profile.maxTvRating!=null)
    fun liveAllowed(profile:Profile)=profile.live && !restricted(profile)
    fun switchNeedsPin(current:Profile,target:Profile)=current.kids && current.id!=target.id
    fun playbackAllowed(profile:Profile,requestedProfile:String?,meta:Meta?,live:Boolean=false):Boolean {
        if(requestedProfile!=null && requestedProfile!=profile.id)return false
        if(live)return liveAllowed(profile)
        return meta?.let {allows(profile,it)} ?: !restricted(profile)
    }
    private fun normalized(value:String)=value.lowercase(Locale.ROOT).replace("sci-fi","science fiction").replace("sci fi","science fiction").trim()
    private fun matchesGenre(locked:String,values:List<String>):Boolean {
        val wanted=normalized(locked)
        return values.any {value->normalized(value).split(Regex("\\s*(?:&|/|,)\\s*")).any {it==wanted}}
    }
    fun decision(profile:Profile,meta:Meta):Decision {
        if(!restricted(profile))return Decision.ALLOW
        if(meta.type !in setOf("movie","series","show","anime") || meta.adult==true)return Decision.DENY
        var missing=false
        profile.lockedGenre?.let {genre->
            if(genre !in genres)return Decision.DENY
            if(meta.genres.isNullOrEmpty())missing=true else if(!matchesGenre(genre,meta.genres))return Decision.DENY
        }
        profile.maxTvRating?.let {limit->
            if(limit !in ratings)return Decision.DENY
            val rank=ratingRank(meta.ageRating)
            if(rank==null)missing=true else if(rank>ratingRank(limit)!!)return Decision.DENY
        }
        return if(missing)Decision.NEEDS_METADATA else Decision.ALLOW
    }
    fun allows(profile:Profile,meta:Meta)=decision(profile,meta)==Decision.ALLOW
    fun summary(profile:Profile)=listOfNotNull(profile.lockedGenre,profile.maxTvRating?.let {"Up to $it"}).joinToString(" · ")
}
