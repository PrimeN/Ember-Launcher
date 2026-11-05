package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import app.ember.tv.data.ColorThemes

/** Tonal surfaces with blue focus, violet containers and teal accents. */
internal object ModernEmberColors {
    val Background=Color(0xFF101329)
    val Surface=Color(0xFF222B49)
    val SurfaceHi=Color(0xFF35436A)
    val Focus=Color(0xFFD3E3FD)
    val OnFocus=Color(0xFF10213C)
    val Violet=Color(0xFFCEC0FF)
    val Teal=Color(0xFFA4E2D0)
    val Muted=Color(0xFFC0CCE4)
    val DayBackground=Color(0xFFF2F3FF)
    val DaySurface=Color(0xFFE5E9FC)
    val DaySurfaceHi=Color(0xFFD5DDF6)
    val DayPrimary=Color(0xFF31558F)
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
        primaryContainer=Color(0xFFD3E3FD),onPrimaryContainer=ModernEmberColors.OnFocus,
        secondary=Color(0xFF65528F),onSecondary=Color.White,
        secondaryContainer=Color(0xFFEADDFF),onSecondaryContainer=Color(0xFF281A43),
        tertiary=Color(0xFF216A5C),onTertiary=Color.White,
        tertiaryContainer=Color(0xFFBCF0DF),onTertiaryContainer=Color(0xFF072E27),
        background=ModernEmberColors.DayBackground,onBackground=ModernEmberColors.OnFocus,
        surface=ModernEmberColors.DaySurface,onSurface=ModernEmberColors.OnFocus,
        surfaceVariant=ModernEmberColors.DaySurfaceHi,onSurfaceVariant=Color(0xFF465675),
    ) else darkColorScheme(
        primary=ModernEmberColors.Focus,onPrimary=ModernEmberColors.OnFocus,
        primaryContainer=Color(0xFF29477E),onPrimaryContainer=ModernEmberColors.Focus,
        secondary=ModernEmberColors.Violet,onSecondary=Color(0xFF30244F),
        secondaryContainer=Color(0xFF49376E),onSecondaryContainer=Color(0xFFECDDFF),
        tertiary=ModernEmberColors.Teal,onTertiary=Color(0xFF123B32),
        tertiaryContainer=Color(0xFF205747),onTertiaryContainer=Color(0xFFBDF3E1),
        background=if(oled)Color.Black else ModernEmberColors.Background,onBackground=Color(0xFFE9EFFF),
        surface=if(oled)Color(0xFF141A2B) else ModernEmberColors.Surface,onSurface=Color(0xFFE9EFFF),
        surfaceVariant=ModernEmberColors.SurfaceHi,onSurfaceVariant=ModernEmberColors.Muted,
    )
    return if(day) lightColorScheme(
        primary=androidx.compose.ui.graphics.lerp(accent,Color.Black,.18f),onPrimary=Color.White,
        background=Color(0xFFEEF0F3),surface=Color.White,
        onSurface=Color(0xFF1F1F1F),onBackground=Color(0xFF1F1F1F),
    ) else darkColorScheme(
        primary=accent,onPrimary=Color(0xFF1D1408),
        background=if(oled)Color.Black else Color(0xFF111215),
        surface=if(oled)Color(0xFF0E0E10) else Color(0xFF1F2024),
        onSurface=Color(0xFFF1F3F4),onBackground=Color(0xFFF1F3F4),
    )
}
