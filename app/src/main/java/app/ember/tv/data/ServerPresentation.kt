package app.ember.tv.data

import app.ember.tv.mediaserver.ServerLibrary

data class ServerSection(val id:String,val title:String,val source:String,val group:String,val items:List<Meta>,val library:ServerLibrary?=null) {
    fun visible(off:Set<String>)=id !in off && "group:$group" !in off
}
object ServerPresentation {
    val groups=listOf("continue" to "Continue watching", "recent" to "Recently added", "movies" to "Movies", "shows" to "TV shows")
    fun sections(rows:List<CatalogRow>,libraries:List<ServerLibrary>):List<ServerSection> =
        rows.map {ServerSection("row:${HeroBanner.sourceKey(it)}",it.displayTitle,it.addonName,if(it.inProgress)"continue" else "recent",it.items)} +
        libraries.map {ServerSection("library:${it.id}",it.title,it.server,it.kind,it.items,it)}
    fun hero(sections:List<ServerSection>,off:Set<String>,source:String?):List<Meta> = HeroBanner.items(
        sections.filter {it.visible(off)}.map {CatalogRow(it.source,it.title,it.items,catalogKey=it.id)},source,
        {it.type in setOf("movie","series","tv","show")})
}
