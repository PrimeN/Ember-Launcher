package app.ember.tv.data

import android.content.Intent

/** Reserved tile IDs never enter Android's package launch or uninstall paths. */
object LightNavigation {
    private const val PREFIX="ember:nav:"
    private val destinations=NavTabs.ALL.map {it.id}.toSet()
    fun id(tab:String)=PREFIX+tab
    fun destination(id:String):String?=id.takeIf {it.startsWith(PREFIX)}?.removePrefix(PREFIX)?.takeIf {it in destinations}
    /**
     * Only content pages become tiles (#134): Home is where the tiles live (#129), Search sits in
     * the header, and Apps and Settings are reached from Control Center and the profile menu.
     */
    val TILE_PAGES=listOf(NavTabs.DISCOVER,NavTabs.LIVE,NavTabs.LIBRARY,NavTabs.WATCHLIST)
    fun entries(tabs:List<String>):List<AppEntry> = tabs.distinct().filter {it in destinations && it in TILE_PAGES}.map {
        AppEntry(id(it),NavTabs.label(it),Intent())
    }
    fun ordered(apps:List<AppEntry>,tabs:List<String>,order:List<String>,hidden:Set<String>,showHidden:Boolean):List<AppEntry> {
        val entries=(apps+entries(tabs)).distinctBy {it.pkg}.filter {showHidden || it.pkg !in hidden}
        val positions=order.withIndex().associate {it.value to it.index}
        return entries.sortedBy {positions[it.pkg] ?: Int.MAX_VALUE}
    }
}
