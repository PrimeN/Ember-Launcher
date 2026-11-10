package app.ember.tv.ui

/** The item keys of pages that hold a merged page, in page order, for scrolling to a search hit (#164). */
internal object SettingsMerges {
    val diagnosticsKeys:List<String> get()=CACHE_KEYS+listOf("debug-build","image-cache","provider-diagnostics","crash-log")
}

/** Categories keep the sidebar short while every existing page remains addressable. */
internal enum class SettingsCategory(val representative:SettingsTab) {
    SEARCH(SettingsTab.SEARCH), LOOK(SettingsTab.APPEARANCE), HOME(SettingsTab.HOME_ROWS), PLAYBACK(SettingsTab.PLAYER),
    SOURCES(SettingsTab.ADDONS), ACCOUNTS(SettingsTab.PROFILES), SYSTEM(SettingsTab.DEVICE_TOOLS), ABOUT(SettingsTab.ABOUT);
    /** A category's pages in the order SettingsTab declares them. */
    val pages:List<SettingsTab> get()=SettingsTab.entries.filter {it.group==representative.group && it!=SettingsTab.SEARCH && !it.merged}
    companion object {
        fun of(tab:SettingsTab)=entries.first {it.representative.group==tab.group}
    }
}

internal data class SettingsLocation(val category:SettingsCategory=SettingsCategory.LOOK,val page:SettingsTab?=null) {
    /** Ignore stale sidebar restoration while the requested destination is receiving focus. */
    fun fromSidebar(value:SettingsCategory,pendingFocus:Boolean):SettingsLocation =
        if(pendingFocus || value==category && page!=null)this else SettingsLocation.category(value)
    fun topics():SettingsLocation?=if(page!=null && page!=SettingsTab.SEARCH)SettingsLocation(category) else null
    companion object {
        const val OVERVIEW="OVERVIEW"
        fun category(value:SettingsCategory)=if(value==SettingsCategory.SEARCH)open(SettingsTab.SEARCH) else SettingsLocation(value)
        /** A page that joined another (#164) opens the page that holds it. */
        fun open(tab:SettingsTab)=tab.host.let {SettingsLocation(SettingsCategory.of(it),it)}
        fun jump(value:String)=if(value==OVERVIEW)SettingsLocation() else SettingsTab.entries.firstOrNull {it.name==value}?.let(::open)
        fun restore(category:String,page:String):SettingsLocation =
            SettingsTab.entries.firstOrNull {it.name==page}?.let(::open)
                ?: SettingsLocation.category(SettingsCategory.entries.firstOrNull {it.name==category} ?: SettingsCategory.LOOK)
    }
}

/** Lazy destinations may need another frame; never leave focus on a disposed invoker. */
internal suspend fun requestSettingsFocus(request:()->Boolean,frame:suspend ()->Unit,fallback:()->Unit):Boolean {
    repeat(3) {
        frame()
        if(runCatching(request).getOrDefault(false))return true
    }
    fallback()
    return false
}
