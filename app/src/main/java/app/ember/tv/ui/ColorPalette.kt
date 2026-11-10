package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import app.ember.tv.data.ColorThemes

/**
 * The Android TV theme (stored as Modern Ember): Google TV's own system colours, sampled from its
 * Settings (#146, #150). A dark slate-navy page, rows and icon circles a step lighter, a near-white
 * focus pill with dark text, Google blue for what's chosen, and cool grey secondary text.
 */
internal object ModernEmberColors {
    val Background=Color(0xFF1F232C)
    val Surface=Color(0xFF273041)
    val SurfaceHi=Color(0xFF323C51)
    val Focus=Color(0xFFE6EAED)
    val OnFocus=Color(0xFF1F2328)
    /** Google blue: radio buttons, switches, slider fills, what's chosen. */
    val Blue=Color(0xFF5286EC)
    /** The pale blue of a focused icon circle. */
    val PaleBlue=Color(0xFFD2E1F8)
    /** Secondary text; a touch lighter than Google's 9AA0A9 so it stays readable on the lighter rows. */
    val Muted=Color(0xFFA3A9B2)
    val OledSurface=Color(0xFF15181E)
    val DayBackground=Color(0xFFF1F3F7)
    val DaySurface=Color(0xFFE4E9F1)
    val DaySurfaceHi=Color(0xFFD4DCE9)
    val DayPrimary=Color(0xFF2E4A7D)
}

/**
 * Ember, Blue, Green and Rose (#150): every colour role comes from the accent, not the TV library's
 * purple defaults, and panels take a faint touch of it. Charcoal, Android TV and Red have their own.
 */
internal object AccentTones {
    private val Page=Color(0xFF111215)
    private val Row=Color(0xFF1F2024)
    private val RowHi=Color(0xFF303136)
    private val OledRow=Color(0xFF0E0E10)
    private val OledRowHi=Color(0xFF1B1B1E)
    private val tinted=setOf(0,2,3,4)

    fun toned(index:Int):Boolean = ColorThemes.normalize(index) in tinted
    private fun touch(index:Int):Color? = ColorThemes.normalize(index).takeIf { it in tinted }?.let { Accents[it].second }
    private fun tone(base:Color,index:Int,amount:Float):Color = touch(index)?.let { lerp(base,it,amount) } ?: base

    fun page(index:Int):Color = tone(Page,index,.025f)
    fun panel(index:Int,oled:Boolean):Color = if(oled)tone(OledRow,index,.025f) else tone(Row,index,.035f)
    fun panelHi(index:Int,oled:Boolean):Color = if(oled)tone(OledRowHi,index,.035f) else tone(RowHi,index,.045f)
    /** Night glass (#155): the same faint touch as the panels; null for themes with their own glass. */
    fun glass(index:Int,oled:Boolean):Color? = if(!toned(index))null else if(oled)tone(Color(0xFF0C0C0D),index,.04f) else tone(Color(0xFF1A1A1C),index,.05f)
    fun floating(index:Int):Color = tone(Color(0xFF1D1E22),index,.035f)
    fun floatingHeader(index:Int):Color = tone(Color(0xFF232428),index,.035f)

    /** The accent turned round the colour wheel, as Search's glow does. */
    fun turned(color:Color,degrees:Float):Color {
        val r=color.red;val g=color.green;val b=color.blue
        val max=maxOf(r,g,b);val d=max-minOf(r,g,b)
        val h=when {d==0f->0f;max==r->60f*(((g-b)/d)%6f);max==g->60f*((b-r)/d+2f);else->60f*((r-g)/d+4f)}
        val hue=((h+degrees)%360f+360f)%360f
        val sat=if(max==0f)0f else d/max
        fun f(n:Int):Float { val k=(n+hue/60f)%6f; return max-max*sat*maxOf(0f,minOf(k,4f-k,1f)) }
        return Color(f(5),f(3),f(1),color.alpha)
    }

    /** Darkened just enough for white text to read on it (4.5:1). */
    fun deepen(color:Color,start:Float=.18f):Color {
        var amount=start
        var c=lerp(color,Color.Black,amount)
        while(contrast(Color.White,c)<4.6 && amount<.9f) { amount+=.03f; c=lerp(color,Color.Black,amount) }
        return c
    }

    /** Very dark text in the colour's own hue, for text on the accent. */
    fun ink(color:Color):Color = lerp(color,Color.Black,.86f)

    fun contrast(a:Color,b:Color):Double {
        val x=a.luminance().toDouble();val y=b.luminance().toDouble()
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
}

/** Neutral charcoal surfaces with cool grey highlights. */
internal object ShadowEmberColors {
    val Accent=Color(0xFF565A60)
    val Background=EmberXColors.Background
    val Surface=Color(0xFF18191C)
    val SurfaceHi=Color(0xFF24262A)
    val Focus=Color(0xFFD4D8DD)
    val OnFocus=Color(0xFF202225)
    val Muted=Color(0xFFB9BEC5)
    val DayPrimary=Color(0xFF48505A)
}

internal val isShadowEmber:Boolean
    @Composable @ReadOnlyComposable get()=LocalAccentIndex.current==ColorThemes.SHADOW_EMBER

/** Settings tints stay charcoal instead of mixing the bright focus colour into every panel. */
internal fun shadowSurfaceTint(amount:Float,day:Boolean,oled:Boolean):Color =
    androidx.compose.ui.graphics.lerp(if(day)Color(0xFFF1F2F4) else if(oled)Color.Black else ShadowEmberColors.Background,
        if(day)Color(0xFFB9BEC5) else Color(0xFF46494F),amount.coerceIn(0f,1f))

internal val isModernEmber:Boolean
    @Composable @ReadOnlyComposable get()=LocalAccentIndex.current==ColorThemes.MODERN_EMBER

/** Pure palette construction also applies to player windows and is contrast-testable. */
internal fun emberColorScheme(accentIndex:Int,day:Boolean,oled:Boolean):androidx.tv.material3.ColorScheme {
    val accent=Accents.getOrElse(accentIndex){Accents[0]}.second
    if(accentIndex==ColorThemes.CINEMA_RED && !day)return darkColorScheme(
        primary=Color(0xFFE50914),onPrimary=Color.White,
        primaryContainer=Color(0xFF4B1018),onPrimaryContainer=EmberXColors.Text,
        secondary=EmberXColors.Secondary,onSecondary=EmberXColors.Background,
        secondaryContainer=EmberXColors.Selected,onSecondaryContainer=EmberXColors.Text,
        background=if(oled)Color.Black else EmberXColors.Background,onBackground=EmberXColors.Text,
        surface=if(oled)Color(0xFF0E0E10) else EmberXColors.Surface,onSurface=EmberXColors.Text,
        surfaceVariant=EmberXColors.SurfaceHi,onSurfaceVariant=EmberXColors.Secondary,
        tertiary=Color(0xFFF5C518),onTertiary=Color.Black,
    )
    if(accentIndex==ColorThemes.SHADOW_EMBER) return if(day) lightColorScheme(
        primary=ShadowEmberColors.DayPrimary,onPrimary=Color.White,
        primaryContainer=Color(0xFFDDE1E6),onPrimaryContainer=ShadowEmberColors.OnFocus,
        secondary=Color(0xFF535B66),onSecondary=Color.White,
        secondaryContainer=Color(0xFFE0E3E8),onSecondaryContainer=Color(0xFF242930),
        tertiary=Color(0xFF4D5C62),onTertiary=Color.White,
        tertiaryContainer=Color(0xFFDAE4E7),onTertiaryContainer=Color(0xFF1D2B30),
        background=Color(0xFFF1F2F4),onBackground=ShadowEmberColors.OnFocus,
        surface=Color(0xFFE5E7EB),onSurface=ShadowEmberColors.OnFocus,
        surfaceVariant=Color(0xFFD5D9DF),onSurfaceVariant=Color(0xFF454B54),
    ) else darkColorScheme(
        primary=ShadowEmberColors.Focus,onPrimary=ShadowEmberColors.OnFocus,
        primaryContainer=Color(0xFF41454C),onPrimaryContainer=Color(0xFFE6E9ED),
        secondary=Color(0xFFC1C7CF),onSecondary=Color(0xFF252A32),
        secondaryContainer=Color(0xFF3D434C),onSecondaryContainer=Color(0xFFE0E5EB),
        tertiary=Color(0xFFBDC9CE),onTertiary=Color(0xFF233137),
        tertiaryContainer=Color(0xFF39474D),onTertiaryContainer=Color(0xFFDEE9ED),
        background=if(oled)Color.Black else ShadowEmberColors.Background,onBackground=Color(0xFFE8EAED),
        surface=if(oled)Color(0xFF111214) else ShadowEmberColors.Surface,onSurface=Color(0xFFE8EAED),
        surfaceVariant=ShadowEmberColors.SurfaceHi,onSurfaceVariant=ShadowEmberColors.Muted,
    )
    if(accentIndex==ColorThemes.MODERN_EMBER) return if(day) lightColorScheme(
        primary=ModernEmberColors.DayPrimary,onPrimary=Color.White,
        primaryContainer=Color(0xFFD6E2F7),onPrimaryContainer=Color(0xFF14284A),
        secondary=Color(0xFF4D5D7A),onSecondary=Color.White,
        secondaryContainer=Color(0xFFDCE4F2),onSecondaryContainer=Color(0xFF1D2A40),
        tertiary=Color(0xFF2559C4),onTertiary=Color.White,
        tertiaryContainer=Color(0xFFD8E4FB),onTertiaryContainer=Color(0xFF0F2A5C),
        background=ModernEmberColors.DayBackground,onBackground=ModernEmberColors.OnFocus,
        surface=ModernEmberColors.DaySurface,onSurface=ModernEmberColors.OnFocus,
        surfaceVariant=ModernEmberColors.DaySurfaceHi,onSurfaceVariant=Color(0xFF46536B),
        border=Color(0xFF8592A8),borderVariant=Color(0xFFC3CCDA),
    ) else darkColorScheme(
        primary=ModernEmberColors.Focus,onPrimary=ModernEmberColors.OnFocus,
        primaryContainer=Color(0xFF2F3B55),onPrimaryContainer=Color(0xFFE3EAF7),
        secondary=ModernEmberColors.PaleBlue,onSecondary=Color(0xFF1C2B45),
        secondaryContainer=Color(0xFF34405A),onSecondaryContainer=Color(0xFFDDE5F5),
        tertiary=ModernEmberColors.Blue,onTertiary=Color(0xFF0A1630),
        tertiaryContainer=Color(0xFF223C6E),onTertiaryContainer=Color(0xFFD6E3FF),
        background=if(oled)Color.Black else ModernEmberColors.Background,onBackground=Color(0xFFE8EAED),
        surface=if(oled)ModernEmberColors.OledSurface else ModernEmberColors.Surface,onSurface=Color(0xFFE8EAED),
        surfaceVariant=ModernEmberColors.SurfaceHi,onSurfaceVariant=ModernEmberColors.Muted,
        border=Color(0xFF5C6577),borderVariant=Color(0xFF3A4253),
    )
    // Ember, Blue, Green and Rose (#150).
    val turn=AccentTones.turned(accent,38f)
    // Red keeps neutral page colours, like Charcoal; only the touch of colour is skipped.
    val touch=if(AccentTones.toned(accentIndex))1f else 0f
    if(day) {
        val deep=AccentTones.deepen(accent)
        val third=AccentTones.deepen(turn,.30f)
        return lightColorScheme(
            primary=deep,onPrimary=Color.White,
            primaryContainer=lerp(Color.White,accent,.20f),onPrimaryContainer=lerp(accent,Color.Black,.72f),
            secondary=AccentTones.deepen(lerp(deep,Color(0xFF4A4F57),.45f),0f),onSecondary=Color.White,
            secondaryContainer=lerp(Color(0xFFF3F3F3),accent,.12f),onSecondaryContainer=lerp(accent,Color.Black,.74f),
            tertiary=third,onTertiary=Color.White,
            tertiaryContainer=lerp(Color.White,turn,.18f),onTertiaryContainer=lerp(turn,Color.Black,.74f),
            background=lerp(Color(0xFFEEF0F3),accent,.025f*touch),onBackground=Color(0xFF1F1F1F),
            surface=Color.White,onSurface=Color(0xFF1F1F1F),
            surfaceVariant=lerp(Color(0xFFE2E5EA),accent,.05f*touch),onSurfaceVariant=Color(0xFF45484E),
            border=lerp(Color(0xFF8A8E95),accent,.10f),borderVariant=lerp(Color(0xFFC9CCD1),accent,.08f),
        )
    }
    val row=AccentTones.panel(accentIndex,false)
    val third=lerp(turn,Color.White,.35f)
    return darkColorScheme(
        primary=accent,onPrimary=AccentTones.ink(accent),
        primaryContainer=lerp(row,accent,.30f),onPrimaryContainer=lerp(accent,Color.White,.82f),
        secondary=lerp(accent,Color.White,.45f),onSecondary=AccentTones.ink(accent),
        secondaryContainer=lerp(row,accent,.18f),onSecondaryContainer=lerp(accent,Color.White,.86f),
        tertiary=third,onTertiary=AccentTones.ink(third),
        tertiaryContainer=lerp(row,turn,.24f),onTertiaryContainer=lerp(turn,Color.White,.84f),
        background=if(oled)Color.Black else AccentTones.page(accentIndex),onBackground=Color(0xFFF1F3F4),
        surface=AccentTones.panel(accentIndex,oled),onSurface=Color(0xFFF1F3F4),
        surfaceVariant=AccentTones.panelHi(accentIndex,oled),onSurfaceVariant=lerp(Color(0xFFC4C7CC),accent,.08f),
        border=lerp(Color(0xFF6F7378),accent,.12f),borderVariant=lerp(Color(0xFF3A3B40),accent,.08f),
    )
}

/** The phone setup page is always dark, so it takes the theme's night accent even in the day theme (#156). */
internal fun phonePageAccent(accentIndex: Int): String {
    val c = emberColorScheme(accentIndex, day = false, oled = false).primary
    fun part(v: Float) = (v * 255f + .5f).toInt().coerceIn(0, 255)
    return String.format(java.util.Locale.ROOT, "#%02X%02X%02X", part(c.red), part(c.green), part(c.blue))
}
