package app.ember.tv.ui

import app.ember.tv.data.TileLabels

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import app.ember.tv.data.resumeLabel
import app.ember.tv.data.spokenLabel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.ember.tv.data.AppEntry
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import coil.compose.AsyncImage

/**
 * A focusable box that grows when focused, with a white outline and soft glow. [focusedContainer] and
 * [focusedContent] change the fill and text colour while focused (used by the modern pills).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    radius: Int = TvTokens.Shape.Card,
    container: Color = Panel,
    focusedContainer: Color = container,
    focusedContent: Color = Fg,
    outline: Boolean = true,
    focusedScale: Float = TvTokens.Focus.Card,
    enabled: Boolean = true,
    card: Boolean = false,
    content: @Composable () -> Unit,
) {
    val navigation = LocalNavigationFocus.current
    val scene = LocalFocusScene.current.takeUnless { androidx.compose.ui.platform.LocalView.current.parent is androidx.compose.ui.window.DialogWindowProvider }
    @Suppress("DEPRECATION")
    val itemId = androidx.compose.runtime.currentCompositeKeyHash.toString()
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.DisposableEffect(navigation, scene, itemId) {
        if (scene != null) navigation?.register(scene, itemId, requester)
        onDispose { if (scene != null) navigation?.unregister(scene, itemId, requester) }
    }
    val focusModifier = if (navigation != null && scene != null) {
        modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) navigation.focused(scene, itemId) }
    } else modifier
    val ring = Ring
    val render=LocalRenderOptions.current
    val reduceFeedback=LocalReduceMotion.current || RuntimeBudgets.pressured
    val reduced=reduceFeedback || !render.animationsEnabled
    val style=LocalCardFocusStyle.current
    var cardFocused by remember {mutableStateOf(false)}
    val motion=card && app.ember.tv.data.CardFocusStyle.animated(style,render.lowPower,reduced)
    val pulse=cardPulse(card && outline && app.ember.tv.data.CardFocusStyle.pulse(style,render.lowPower,reduced,cardFocused,LocalSceneInteractive.current))
    val cardScale=render.cardFocusScale(focusedScale,style,reduceFeedback)
    val content = Fg
    val shape = RoundedCornerShape((if(isEmberX && radius==TvTokens.Shape.Card)EmberXLayout.Radius else radius).dp)
    Surface(
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = focusModifier.onFocusChanged {cardFocused=it.isFocused}.semantics(mergeDescendants = true) {
            this.role = role
            if (onLongClick != null) onLongClick("Options") { onLongClick(); true }
        },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
            pressedContainerColor = focusedContainer,
            pressedContentColor = focusedContent,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if(card)cardScale else render.focusScale(focusedScale,reduced),
            pressedScale=if(card && cardScale>1f){if(render.lowPower || render.automaticEffectsReduced).99f else .98f} else 1f),
        border = ClickableSurfaceDefaults.border(
            // Owned by Surface so the border and image share shape, scale and pressed geometry.
            focusedBorder = if(card || outline) Border(
                border=BorderStroke(2.dp,if(card)ring.copy(alpha=1f-pulse.value*.55f) else ControlStateCue.edge(focusedContainer)),
                shape=shape,
            ) else Border.None,
        ),
        // An explicit Glow style gets one soft accent halo. Other styles never add a dark ring.
        glow = if(card && outline && motion && style==app.ember.tv.data.CardFocusStyle.GLOW) {
            ClickableSurfaceDefaults.glow(focusedGlow=androidx.tv.material3.Glow(elevationColor=ring.copy(alpha=.22f),elevation=4.dp))
        } else ClickableSurfaceDefaults.glow(),
    ) { content() }
}

@Composable
fun Section(title: String, subtitle: String? = null, id: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.settingsAnchor(id), verticalArrangement = Arrangement.spacedBy(TvTokens.Space.Row.dp)) {
        // The explanation sits under the title, so long notes never squeeze it.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = TvTokens.Type.Section.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = TvTokens.Type.Compact.sp, color = Mute, lineHeight = 18.sp)
        }
        content()
    }
}

/**
 * A pill with a switch for something that is on or off: [label] then the switch (only the switch
 * when [label] is empty). Replaces the old "Name: on" / "Name: off" chips.
 */
@Composable
/**
 * [locked] (#102): shown off and dimmed because it can't be changed here; it still takes focus,
 * and [onClick] should say why.
 */
fun SwitchChip(label: String, on: Boolean, modifier: Modifier = Modifier, locked: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val shownOn = on && !locked
    Focusable(
        onClick = onClick,
        role = Role.Switch,
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = if (shownOn) ToggleableState.On else ToggleableState.Off
            stateDescription = if (locked) "Off, not available" else if (on) "On" else "Off"
        }.onFocusChanged { focused = it.isFocused || it.hasFocus }.then(if (locked) Modifier.alpha(0.5f) else Modifier),
        radius = TvTokens.Shape.Pill,
        container = Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.Control,
    ) {
        Row(
            Modifier.padding(start = if (label.isEmpty()) 10.dp else 18.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (label.isNotEmpty()) Text(label, fontSize = TvTokens.Type.Label.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            // On: accent track (darker on the white focused pill); off: grey track.
            val track = when {
                shownOn && focused -> lerp(accent, Color.Black, 0.25f)
                shownOn -> accent
                focused -> OnPillColor.copy(alpha = 0.35f)
                else -> Fg.copy(alpha = 0.3f)
            }
            Box(
                Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(50)).background(track),
                contentAlignment = if (shownOn) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(3.dp).size(16.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

/**
 * Landscape card art (Settings > Posters and ratings > Landscape cards): the backdrop, or RPDB's
 * backdrop with ratings (Tier 2 keys), falling back to the title's own backdrop, then its poster.
 */
@Composable
fun LandscapeImage(meta: Meta, modifier: Modifier = Modifier, onImage: (Drawable, String) -> Unit = { _, _ -> }) {
    val c = app.ember.tv.data.Posters.config
    val own = meta.background?.takeIf { it.isNotBlank() }?.let { cardArt(it, wide = true, uhd = Art.uhd && !LocalRenderOptions.current.lowPower) }
    val rated = app.ember.tv.data.Posters.landscapeUrl(meta, c)
    var ratedFailed by remember(rated) { mutableStateOf(false) }
    // Media servers now always offer a backdrop URL (parent/series fallback); one that does not
    // exist must fall back to the poster rather than leave the wide card blank.
    var ownFailed by remember(own) { mutableStateOf(false) }
    val art = rated?.takeUnless { ratedFailed } ?: own?.takeUnless { ownFailed }
    if (art != null) {
        ArtworkImage(
            model = art,
            description = meta.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            onError = { if (art == rated) ratedFailed = true else ownFailed = true },
            onSuccess = { onImage(it, art) },
        )
    } else {
        PosterImage(meta, modifier, onImage = onImage)
    }
}

/**
 * The name on a landscape card: the title's logo when there is one,
 * else the name in text. The name shows while the logo loads and stays if it fails.
 */
@Composable
fun LandscapeTitle(meta: Meta, fontSize: androidx.compose.ui.unit.TextUnit, logoHeight: androidx.compose.ui.unit.Dp, logoWidth: androidx.compose.ui.unit.Dp) {
    val logo = app.ember.tv.data.Posters.logoUrl(meta)
    var state by remember(logo) { mutableIntStateOf(if (logo == null) 2 else 0) } // 0 loading, 1 shown, 2 none
    if (state != 2) {
        Box(Modifier.height(logoHeight).width(logoWidth), contentAlignment = Alignment.BottomStart) {
            AsyncImage(
                model = logo,
                contentDescription = meta.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier.fillMaxSize().alpha(if (state == 1) 1f else 0f),
                onSuccess = { state = 1 },
                onError = { state = 2 },
            )
            if (state == 0) Text(meta.name, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Text(meta.name, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// The parameter order is established across the app; positional selected/modifier calls depend on it.
@Suppress("ModifierParameter")
@Composable
fun Chip(label: String, selected: Boolean = false, modifier: Modifier = Modifier, centered: Boolean = false, maxLines: Int = 2, onClick: () -> Unit) {
    // Selection uses a persistent accent fill; focus uses the brighter navigation pill.
    Focusable(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        radius = if(isEmberX)EmberXLayout.Radius else TvTokens.Shape.Pill,
        container = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .38f) else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.Control,
    ) {
        Row((if(centered)Modifier.fillMaxWidth() else Modifier).padding(horizontal=18.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(centered)Arrangement.Center else Arrangement.Start) {
            Text(label,fontSize=TvTokens.Type.Label.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,
                maxLines=maxLines,softWrap=maxLines>1,overflow=TextOverflow.Ellipsis,
                textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else null)
        }
    }
}

@Composable
fun AppTile(app: AppEntry, dim: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit, width: androidx.compose.ui.unit.Dp = 150.dp) {
    val bitmap=rememberAppArtwork(app)
    val backdrop = rememberAppFocus(app, bitmap)
    Focusable(onClick = onClick, onLongClick = onLongClick,
        modifier = Modifier.width(width).height(width * 0.5625f).focusBackdrop(backdrop),card=true) {
        if(bitmap!=null)Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = app.label + if (dim) " (hidden)" else "",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = if (dim) 0.4f else 1f,
        ) else ArtworkPlaceholder(app.label,Modifier.fillMaxSize())
    }
}

@Composable
fun TitleCard(meta: Meta, saved: Boolean, width: androidx.compose.ui.unit.Dp = 150.dp, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val actions = LocalTitleActions.current
    // Square and landscape art keep their shape instead of being cropped to a portrait poster (#72).
    @Suppress("NAME_SHADOWING") val width = width * meta.cardWidthScale
    val backdrop = rememberMediaFocus(meta)
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Focusable(onClick = onClick, onLongClick = onLongClick ?: actions?.let { action -> { action(meta) } }, modifier = Modifier.restoreFocus(meta.mediaKey()).mediaSemantics(meta, saved).focusBackdrop(backdrop),card=true) {
            Box(Modifier.clearAndSetSemantics { }) {
                PosterImage(meta, Modifier.width(width).height(width * meta.cardRatio), onImage = backdrop::loaded)
                WatchedBadge(LocalWatchedUi.current.badge(meta),Modifier.align(Alignment.TopStart))
                meta.safeProgress?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(Color(0x66000000)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(5.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                if (saved) {
                    Text(
                        stringResource(R.string.ui_saved),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = TvTokens.Type.Caption.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        // Labels under tiles > Titles (#43): off removes the title and its line; the card keeps
        // its spoken name through mediaSemantics.
        if (tileLabelShown(TileLabels.MEDIA)) Text(meta.name, fontSize = if (width < 120.dp) 12.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
        if (tileLabelShown(TileLabels.MEDIA)) (meta.resumeLabel() ?: meta.badge)?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { }) }
    }
}

@Composable
fun TitleRow(items: List<Meta>, isSaved: (Meta) -> Boolean, onSeeAll: (() -> Unit)? = null, onFocused: ((Meta)->Unit)? = null, onOpen: (Meta) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val w = CardSizes.width(maxWidth, CardSizes.POSTER, 14.dp, 6.dp)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
    ) {
        // Catalogs sometimes list the same title twice; duplicate keys would crash the row.
        items(items.distinctBy { it.mediaKey() }, key = { it.mediaKey() }) { m ->
            Box(Modifier.onFocusChanged {if(it.hasFocus)onFocused?.invoke(m)}) {TitleCard(m, isSaved(m), width = w) { onOpen(m) }}
        }
        if (onSeeAll != null) item(key = "seeall") { SeeAllCard(w, w * 1.5f, onSeeAll) }
    }
    }
}

/** The last card of a row: opens the whole catalog in Discover. */
@Composable
fun SeeAllCard(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.width(width).height(height), container = PanelHi) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("See all ›", fontSize = if (width < 120.dp) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        }
    }
}

/** How many cards fit across a row or grid, so rows show enough titles on any screen. */
object CardSizes {
    /**
     * Landscape cards (titles, apps, collection folders) across a row: five and a quarter, so the
     * next one peeks in and shows the row goes on.
     */
    const val WIDE = 5.25f
    /** Posters (2:3) across a row: about twice as tall as a landscape card. */
    const val POSTER = 7.25f
    /** Posters across a grid (Discover, collections, search), whole cards only. */
    const val POSTER_GRID = 7

    /** Card width so [count] cards with [gap] between them (and [padding] at the start) fill [available]. */
    fun width(available: androidx.compose.ui.unit.Dp, count: Float, gap: androidx.compose.ui.unit.Dp, padding: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        ((available - padding * 2 - gap * (count - 1f)) / count).coerceAtLeast(60.dp)
}

/** A grid of title cards that fills the page, with an optional item after the last card (e.g. "Load more"). */
@Composable
fun TitleGrid(
    items: List<Meta>,
    isSaved: (Meta) -> Boolean,
    onOpen: (Meta) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    densityDelta: Int = 0,
    /** Hold OK on a card (e.g. options to remove it from a list). */
    onLongPress: ((Meta) -> Unit)? = null,
    state: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
    val columns = (LocalRenderOptions.current.columns(maxWidth.value, androidx.compose.ui.platform.LocalDensity.current.fontScale) + densityDelta).coerceIn(3, 12)
    LazyVerticalGrid(
        // Eight posters across, like the rows.
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
    ) {
        gridItems(items.distinctBy { it.mediaKey() }, key = { it.mediaKey() }) { m ->
            androidx.compose.foundation.layout.BoxWithConstraints { TitleCard(m, isSaved(m), width = maxWidth, onLongClick = onLongPress?.let { f -> { f(m) } }) { onOpen(m) } }
        }
        if (footer != null) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    }
    }
}

/**
 * Card-sized artwork: rows ask for smaller images than the full-screen banner, which loads and
 * scrolls faster and uses less memory. TMDB posters at 342 px wide and backdrops at 780 px;
 * metahub images at "medium". Other links are left as they are.
 */
fun cardArt(url: String, wide: Boolean, uhd: Boolean = Art.uhd): String =
    sizedArtworkUrl(url, wide, if (uhd) { if (wide) 1280 else 780 } else { if (wide) 780 else 342 })

/** Picture sizes for the screen: [uhd] when the TV's display mode is 4K or more. */
object Art {
    /** The TV shows a 4K picture (so the Ultra profile is offered). */
    private val shows4K = androidx.compose.runtime.mutableStateOf(false)
    val display4K: Boolean get() = shows4K.value
    /** The Ultra profile is in effect (set from the active performance profile). */
    @Volatile var ultra = false
    /** 4K card artwork: a 4K display with the Ultra profile. */
    val uhd: Boolean get() = display4K && ultra

    /** Reads the current display mode (called when Ember comes to the front). */
    fun update(activity: android.app.Activity) {
        val display = if (android.os.Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        shows4K.value = (display?.mode?.physicalWidth ?: 0) >= 3000
    }
}

/**
 * A title's poster, enhanced by RPDB or Better Posters when one is chosen in Settings > Posters.
 * If the enhanced image cannot be loaded, the addon's own poster is shown instead.
 */
@Composable
fun PosterImage(meta: Meta, modifier: Modifier = Modifier, config: app.ember.tv.data.PosterConfig = app.ember.tv.data.Posters.config, onImage: (Drawable, String) -> Unit = { _, _ -> }) {
    if(app.ember.tv.mediaserver.ServerRef.of(meta)!=null) {
        ServerPosterImage(meta,modifier,config,onImage)
        return
    }
    val enhanced = remember(meta.id, meta.imdbId, meta.tmdbId, config, app.ember.tv.data.Posters.keyRev) { app.ember.tv.data.Posters.url(meta, config) }
    var failed by remember(enhanced) { mutableStateOf(false) }
    val model = if (enhanced != null && !failed) enhanced else meta.poster?.let { cardArt(it, wide = false, uhd = Art.uhd && !LocalRenderOptions.current.lowPower) }
    ArtworkImage(
        model = model,
        description = meta.name,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        onError = { if (model == enhanced) failed = true },
        onSuccess = { onImage(it, model.orEmpty()) },
    )
}

/**
 * A rating service's icon, loaded once and cached. Wikimedia asks for a
 * descriptive User-Agent. The bundled copy shows while it loads and if it cannot.
 */
@Composable
fun RatingIcon(source: app.ember.tv.data.RatingSource, height: Int = 22) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(source.iconUrl) {
        coil.request.ImageRequest.Builder(context)
            .data(source.iconUrl.takeIf { it.isNotBlank() } ?: source.fallbackIcon)
            .addHeader("User-Agent", "EmberTV/0.9 (Android TV launcher; github.com/PrimeN/Ember-Launcher)")
            .build()
    }
    val fallback = androidx.compose.ui.res.painterResource(source.fallbackIcon)
    AsyncImage(
        model = request,
        contentDescription = source.label,
        placeholder = fallback,
        error = fallback,
        contentScale = ContentScale.Fit,
        modifier = Modifier.width((source.iconWidth * height / 22).dp).height(height.dp),
    )
}


/**
 * A row of buttons that wraps onto the next line when they do not fit, instead of squeezing the
 * last ones or running off the side.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}

/** Spoken card information is attached to its action, while artwork/text children stay silent. */
@Composable
fun Modifier.mediaSemantics(meta: Meta, saved: Boolean): Modifier {
    val watched=LocalWatchedUi.current.let {it.showBadges && it.title(meta)}
    return semantics {
    contentDescription = meta.spokenLabel(saved)+if(watched)", Watched" else ""
    meta.safeProgress?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) }
}
}
