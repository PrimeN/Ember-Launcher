package app.ember.tv.data

/** Home layout, navbar and media pages are independent choices. Light owns its navigation. */
object NavbarStyles {
    const val EMBER="ember"
    const val COLLAPSED="ember_collapsed"
    const val CINEMA="cinema_bar"
    /** A menu down the left side that opens into a panel; formerly Ember with Menu: Side. */
    const val SIDEBAR="sidebar"
    val options=listOf(EMBER to "Ember",COLLAPSED to "Ember Collapsed",SIDEBAR to "Sidebar",CINEMA to "Cinema Bar")
    fun normalize(value:String?):String=value?.takeIf {it in setOf(COLLAPSED,CINEMA,SIDEBAR)} ?: EMBER
    /** A saved Ember menu placed down the side becomes the Sidebar style. */
    fun resolve(style:String?,position:String?):String=normalize(style).let {if(it==EMBER && position=="side")SIDEBAR else it}
    fun available(launcher:String)=InterfaceStyles.normalize(launcher)!=InterfaceStyles.LIGHT
    fun side(style:String,launcher:String,page:String)=available(launcher) && normalize(style)==SIDEBAR && page!=NavTabs.SETTINGS
    /** How soon after a remote press focus on the Collapsed handle counts as that press (#127). */
    const val HANDLE_PRESS_MS=1_000L
    /** Collapsed and Cinema Bar sit on artwork in the dark theme; Day pages are light (#128). */
    fun forcesNight(style:String,day:Boolean)=normalize(style) in setOf(COLLAPSED,CINEMA) && !day
    fun entries(tabs:List<String>)=(tabs.filter {it==NavTabs.SEARCH}+tabs.filter {it !in setOf(NavTabs.SEARCH,NavTabs.SETTINGS,NavTabs.SCREENSAVER,NavTabs.QUICK)}).distinct()
}
