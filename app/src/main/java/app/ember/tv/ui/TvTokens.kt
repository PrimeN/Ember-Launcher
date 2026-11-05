package app.ember.tv.ui

/** Role-based values shared by Compose and native player controls. dp/sp conversion stays at the caller. */
internal object TvTokens {
    object Space {
        const val XS=4;const val S=8;const val M=12;const val L=16;const val XL=20;const val XXL=24
        const val Page=48;const val Row=10;const val ButtonX=18;const val ButtonY=8
    }
    object Type {
        const val Page=30;const val Hero=30;const val HeroExpanded=40;const val Details=38;const val DetailsLarge=34
        const val Pane=28;const val Dialog=22;const val Section=20;const val Navigation=16;const val SettingsLabel=16;const val Label=15
        const val Body=15;const val CompactBody=14;const val Menu=14;const val Compact=13;const val Caption=12
        const val Card=14;const val Detail=11;const val NativeButton=13f
    }
    object Shape {const val Card=12;const val Control=14;const val Panel=24;const val Pill=50;const val NativePill=22}
    object Motion {const val Image=120;const val Page=160;const val Panel=140;const val PanelExit=100;const val Hero=240;const val Text=180;const val Rail=180}
}

/** New cosmetic effects never spend a frame budget on Low/Economy or reduced motion. */
internal object PolishMotion {
    fun duration(normal:Int,options:RenderOptions,reduced:Boolean)=
        if(reduced || !options.animationsEnabled || options.lowPower || options.quality=="economy")0 else normal.coerceAtLeast(0)
}
