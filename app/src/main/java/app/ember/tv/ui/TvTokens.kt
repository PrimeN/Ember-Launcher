package app.ember.tv.ui

/** Role-based values shared by Compose and native player controls. dp/sp conversion stays at the caller. */
internal object TvTokens {
    object Space {
        const val XS=4;const val S=8;const val M=12;const val L=16;const val XL=20;const val XXL=24
        const val Page=48;const val Row=10;const val ButtonX=18;const val ButtonY=8
    }
    /** Text sizes in sp. Nothing on screen goes below 12 sp, the TV minimum for secondary text (#37). */
    object Type {
        const val Page=30;const val Hero=30;const val HeroExpanded=40;const val Details=38;const val DetailsLarge=34
        const val Pane=28;const val Dialog=22;const val Section=20;const val Navigation=16;const val SettingsLabel=16;const val Label=15
        const val Body=15;const val CompactBody=14;const val Menu=14;const val Compact=13;const val Caption=12
        const val Card=14;const val Detail=12;const val NativeButton=13f
    }
    /** Corner radii in dp (#35). Tight is the Streaming player's square style; Pill is fully round. */
    object Shape {const val None=0;const val Tight=4;const val Small=8;const val Card=12;const val Control=14;const val Large=16;const val Panel=24;const val Pill=50;const val NativePill=22}
    /**
     * How much a control grows when focused (#35). One level per kind of control, for every
     * interface; anything else is a documented exception.
     */
    object Focus {
        /** Rows and items that show focus by their fill (menus, dense lists, the player sheets). */
        const val None=1f
        /** Wide rows and tabs inside a bar, where growing would collide with neighbours. */
        const val Inline=1.02f
        /** Chips, pills, switches and action buttons. */
        const val Control=1.04f
        /** Media and app cards (the card focus style can change it further). */
        const val Card=1.05f
        /** Small round icons, avatars, round app tiles and the player transport. */
        const val Round=1.08f
    }
    object Motion {const val Image=120;const val Page=160;const val Panel=140;const val PanelExit=100;const val Hero=240;const val Text=180;const val Rail=180}
}

/** New cosmetic effects never spend a frame budget on Low/Economy or reduced motion. */
internal object PolishMotion {
    fun duration(normal:Int,options:RenderOptions,reduced:Boolean)=
        if(reduced || !options.animationsEnabled || options.lowPower || options.quality=="economy")0 else normal.coerceAtLeast(0)
}
