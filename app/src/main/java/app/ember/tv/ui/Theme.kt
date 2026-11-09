package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme

/**
 * Colour themes (Settings > Appearance). Ember is the logo's orange (the default); Charcoal uses
 * neutral charcoal and cool grey surfaces; Android TV follows Google TV's own system colours and
 * reuses the blue branding. Stored by position, so new ones go at the end. Renamed in #148 (were
 * Shadow Ember, Blue ember, Green flame, Rose ember, Modern Ember and Cinema red).
 */
val Accents = listOf(
    "Ember" to Color(0xFFFF7A2F),
    "Charcoal" to ShadowEmberColors.Accent,
    // Sulphur burns blue.
    "Blue" to Color(0xFF4F94FF),
    // Copper burns green.
    "Green" to Color(0xFF34D399),
    // Strontium burns crimson.
    "Rose" to Color(0xFFFF5C8A),
    "Android TV" to ModernEmberColors.Focus,
    "Red" to Color(0xFFE50914),
)

/** The accent's position in [Accents], for drawings that come in each colourway (the logo). */
val LocalInterfaceStyle = androidx.compose.runtime.staticCompositionLocalOf { app.ember.tv.data.InterfaceStyles.MODERN }

val LocalAccentIndex = androidx.compose.runtime.staticCompositionLocalOf { 0 }

/** Ember, Blue, Green and Rose: the themes whose panels take a touch of their colour (#150). */
internal val isAccentToned: Boolean
    @Composable @ReadOnlyComposable get() = app.ember.tv.data.ColorThemes.normalize(LocalAccentIndex.current) in setOf(0, 2, 3, 4)

/**
 * Which look is in use. Modern (the default) uses soft surfaces with full-bleed
 * artwork and pill tabs. Light uses an apps-first Home. Set by HomeViewModel from Settings >
 * Appearance. Reading it in composition makes the screens redraw when it changes.
 */
object Ui {
    /** Light: the modern colours with an apps-first Home (wallpaper, Watch next, app dock). */
    private var lightChoice by mutableStateOf(false)
    var emberX by mutableStateOf(false)
    var light:Boolean
        get()=lightChoice && !emberX
        set(value){lightChoice=value}
    /** Day colours (light backgrounds, dark text). Modern and Light looks only; set from Theme: Day, or Auto by the clock. */
    var day by mutableStateOf(false)
    /** OLED: true black backgrounds at night, for OLED TVs and to save power. Modern and Light looks only. */
    var oled by mutableStateOf(false)

    val isDay: Boolean get() = day && !emberX
    val isOled: Boolean get() = oled && !day
}

/** True inside modern Settings, which sits on a page tinted with the accent colour. */
val LocalSlate = androidx.compose.runtime.staticCompositionLocalOf { false }

/** True inside floating panels (Quick settings, filters). They stay dark in the day theme. */
val LocalFloating = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Keeps night colours inside, whatever the theme (Light Home sits on a dark wallpaper). */
val LocalNight = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Day colours apply here: modern look, day theme, and not inside the dark floating panel or Light Home. */
private val dayHere: Boolean
    @Composable @ReadOnlyComposable
    get() = Ui.isDay && !LocalFloating.current && !LocalNight.current

// The colours below follow the look and theme, so every screen picks them up.
val Ink: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val Panel: Color
    @Composable @ReadOnlyComposable
    get() = when {
        isEmberX -> EmberXColors.Surface
        isShadowEmber && LocalSlate.current -> Tint.of(0.24f)
        isShadowEmber && (LocalFloating.current || LocalNight.current) -> ShadowEmberColors.Surface
        isShadowEmber -> MaterialTheme.colorScheme.surface
        isModernEmber && LocalSlate.current -> Tint.of(0.24f)
        isModernEmber && (LocalFloating.current || LocalNight.current) -> if(Ui.isOled)ModernEmberColors.OledSurface else ModernEmberColors.Surface
        isModernEmber -> MaterialTheme.colorScheme.surface
        LocalFloating.current -> lerp(Color(0xFF2A2B30),MaterialTheme.colorScheme.primary,if(isAccentToned).035f else 0f)
        LocalSlate.current -> Tint.of(0.24f)
        // #150: Ember, Blue, Green and Rose panels carry a faint touch of their colour.
        LocalNight.current -> AccentTones.panel(LocalAccentIndex.current,Ui.isOled)
        Ui.isDay -> Color.White
        else -> AccentTones.panel(LocalAccentIndex.current,Ui.isOled)
    }
val PanelHi: Color
    @Composable @ReadOnlyComposable
    get() = when {
        isEmberX -> EmberXColors.SurfaceHi
        isShadowEmber && LocalSlate.current -> Tint.of(0.34f)
        isShadowEmber && (LocalFloating.current || LocalNight.current) -> ShadowEmberColors.SurfaceHi
        isShadowEmber -> MaterialTheme.colorScheme.surfaceVariant
        isModernEmber && LocalSlate.current -> Tint.of(0.34f)
        isModernEmber && (LocalFloating.current || LocalNight.current) -> ModernEmberColors.SurfaceHi
        isModernEmber -> MaterialTheme.colorScheme.surfaceVariant
        LocalFloating.current -> lerp(Color(0xFF38393F),MaterialTheme.colorScheme.primary,if(isAccentToned).045f else 0f)
        LocalSlate.current -> Tint.of(0.34f)
        LocalNight.current -> AccentTones.panelHi(LocalAccentIndex.current,Ui.isOled)
        Ui.isDay -> Color(0xFFE2E5EA)
        else -> AccentTones.panelHi(LocalAccentIndex.current,Ui.isOled)
    }
/**
 * The glass material's own tint (#42), for the dock and unsampled glass: Day, Night and OLED
 * (true black) each get their own, and Ember X uses its surface. Sampled glass uses [PanelHi].
 */
internal val GlassTintDay = Color(0xFFE2E5EA)
internal val GlassTintNight = Color(0xFF171C26)
internal val GlassTintOled = Color(0xFF0B0C10)
@Composable @ReadOnlyComposable
internal fun glassTint(day: Boolean): Color = when {
    isEmberX -> EmberXColors.Surface
    // Shadow Ember (#139): charcoal glass, not the default navy grey.
    isShadowEmber -> if (day) Color(0xFFE3E5E8) else if (Ui.isOled) Color(0xFF0B0B0C) else Color(0xFF1A1B1E)
    // Android TV (#150): Google TV's slate navy.
    isModernEmber -> if (day) ModernEmberColors.DaySurface else if (Ui.isOled) ModernEmberColors.OledSurface else Color(0xFF232937)
    day -> GlassTintDay
    Ui.isOled -> GlassTintOled
    else -> GlassTintNight
}

val Mute: Color
    @Composable @ReadOnlyComposable
    get() = when {
        isEmberX -> EmberXColors.Secondary
        LocalSlate.current -> lerp(if (dayHere) Color(0xFF5B5F66) else Color(0xFFC8CCD4), MaterialTheme.colorScheme.primary, 0.18f)
        dayHere -> Color(0xFF5F6368)
        isShadowEmber -> ShadowEmberColors.Muted
        isModernEmber -> ModernEmberColors.Muted
        else -> Color(0xFFA9ADB4)
    }

/** Main text and icon colour. */
val Fg: Color
    @Composable @ReadOnlyComposable
    get() = when {
        isEmberX -> EmberXColors.Text
        dayHere -> Color(0xFF1F1F1F)
        else -> Color(0xFFE8EAED)
    }

/** Faint fill for unselected pills and round buttons, and a stronger one for the selected one. */
val Wash: Color
    @Composable @ReadOnlyComposable
    get() = if(isEmberX)Color.White.copy(alpha=.07f) else if(isModernEmber) {
        if(dayHere)ModernEmberColors.DayPrimary.copy(alpha=.10f) else ModernEmberColors.PaleBlue.copy(alpha=.11f)
    } else if (dayHere) Color(0x14000000) else Color(0x1AFFFFFF)
/** A chosen pill (selected tab, chip or button): tinted with the accent colour, so every look shows it. */
val WashHi: Color
    @Composable @ReadOnlyComposable
    get() = if(isEmberX)EmberXColors.Selected else
        // Android TV marks what's chosen in Google blue (#146, #150).
        (if(isModernEmber && !dayHere)ModernEmberColors.Blue else MaterialTheme.colorScheme.primary)
            .copy(alpha = if (dayHere) 0.24f else 0.34f)

/** A focused pill: white with dark text at night, dark with white text by day. */
val PillColor: Color
    @Composable @ReadOnlyComposable
    get() = when {
        isEmberX -> Color.White
        isShadowEmber -> if(dayHere)ShadowEmberColors.DayPrimary else ShadowEmberColors.Focus
        isModernEmber -> if(dayHere)ModernEmberColors.DayPrimary else ModernEmberColors.Focus
        dayHere -> Color(0xFF202124)
        // Light look: focused buttons and pills take the accent colour.
        Ui.light -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFF1F3F4)
    }
val OnPillColor: Color
    @Composable @ReadOnlyComposable
    get() = readablePillText(PillColor)

internal fun readablePillText(surface:Color):Color =
    if((surface.luminance()+.05f)/.05f >= 1.05f/(surface.luminance()+.05f))Color.Black else Color.White

/** The modern focus outline. */
val Ring: Color
    @Composable @ReadOnlyComposable
    get() = when {
        isEmberX -> Color.White
        isShadowEmber -> if(dayHere)ShadowEmberColors.DayPrimary else ShadowEmberColors.Focus
        isModernEmber -> if(dayHere)ModernEmberColors.DayPrimary else ModernEmberColors.Focus
        dayHere -> Color(0xFF202124)
        // Light look: the focus outline is the accent colour.
        Ui.light -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }

/** The "LIVE" badge and the guide's now-line. */
val LiveRed = Color(0xFFE53935)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmberTheme(accentIndex: Int, interfaceStyle: String = app.ember.tv.data.InterfaceStyles.MODERN, content: @Composable () -> Unit) {
    val index=app.ember.tv.data.ColorThemes.normalize(accentIndex)
    val style = app.ember.tv.data.InterfaceStyles.normalize(interfaceStyle)
    val scheme = emberColorScheme(index,style!=app.ember.tv.data.InterfaceStyles.EMBER_X && Ui.isDay && !LocalNight.current,Ui.isOled)
    MaterialTheme(colorScheme = scheme) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAccentIndex provides index, LocalInterfaceStyle provides style, content = content)
    }
}

/**
 * Colours for modern Settings mixed from the selected colour theme, so the page follows
 * the chosen theme colour and the day, night or OLED theme.
 */
object Tint {
    /** The accent mixed into the page background colour ([amount] 0..1). */
    @Composable @ReadOnlyComposable
    fun of(amount: Float): Color = when {
        isEmberX -> EmberXColors.Surface
        isShadowEmber -> shadowSurfaceTint(amount,dayHere,Ui.isOled)
        // Android TV (#150): slate navy, not violet.
        isModernEmber && dayHere -> lerp(ModernEmberColors.DayBackground,Color(0xFF8FA3C8),amount*.6f)
        isModernEmber -> lerp(if(Ui.isOled)Color.Black else ModernEmberColors.Background,Color(0xFF4A5E86),amount)
        dayHere -> lerp(Color(0xFFF4F5F7), MaterialTheme.colorScheme.primary, amount * 0.8f)
        Ui.isOled -> lerp(Color.Black, MaterialTheme.colorScheme.primary, amount * 0.55f)
        else -> lerp(Color(0xFF15161A), MaterialTheme.colorScheme.primary, amount)
    }

    /** Text colour with a touch of the accent. */
    @Composable @ReadOnlyComposable
    fun ink(amount: Float): Color =
        lerp(if (dayHere) Color(0xFF1F1F1F) else Color.White, MaterialTheme.colorScheme.primary, amount)

    /** The focused card in the side list and on switch rows, and its text. */
    @Composable @ReadOnlyComposable
    fun card(): Color = if (dayHere) lerp(Color(0xFF26272C), MaterialTheme.colorScheme.primary, 0.22f) else lerp(Color.White, MaterialTheme.colorScheme.primary, 0.12f)

    @Composable @ReadOnlyComposable
    fun cardText(): Color = if (dayHere) Color.White else Color(0xFF2B2D33)

    /** The round icon behind a focused entry. */
    @Composable @ReadOnlyComposable
    fun cardIcon(): Color = if (dayHere) lerp(Color(0xFF3A3B42), MaterialTheme.colorScheme.primary, 0.35f) else lerp(Color.White, MaterialTheme.colorScheme.primary, 0.28f)

    /** The accent made readable on the focused card. */
    @Composable @ReadOnlyComposable
    fun deep(): Color = if (dayHere) lerp(MaterialTheme.colorScheme.primary, Color.White, 0.35f) else lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.45f)
}
