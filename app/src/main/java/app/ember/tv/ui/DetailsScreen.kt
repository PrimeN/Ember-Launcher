package app.ember.tv.ui
import androidx.compose.runtime.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import app.ember.tv.data.Person
import app.ember.tv.data.appearance
import app.ember.tv.data.mediaKey
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.DetailsUi
import app.ember.tv.data.Episode
import app.ember.tv.data.SourceKind
import app.ember.tv.data.StreamOption
import app.ember.tv.streaming.Offer
import coil.compose.AsyncImage
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Dns
import androidx.tv.material3.Icon

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(
    modifier: Modifier = Modifier,
    d: DetailsUi,
    listState: LazyListState,
    saved: Boolean,
    isContinue: Boolean,
    onToggleSaved: () -> Unit,
    onRemoveContinue: () -> Unit,
    onSeason: (Int) -> Unit,
    onEpisode: (Episode) -> Unit,
    onMoreEpisodes: () -> Unit,
    onPlay: (StreamOption) -> Unit,
    onPlayWith: (StreamOption) -> Unit,
    /** Where to watch on services that are on. */
    offers: List<Offer> = emptyList(),
    onOpenOffer: (Offer) -> Unit = {},
    /** True when the service's TV app is installed; otherwise its row opens the store. */
    offerInstalled: (Offer) -> Boolean = { true },
    /** Name and description from the Stremio format templates, or null for the built-in layout. */
    formatSource: (StreamOption) -> Pair<String, String>? = { null },
    /** Image badges for a source (Settings > Stremio > Badges); empty for Ember's text tags. */
    badgesFor: (StreamOption) -> List<app.ember.tv.data.BadgeFilter> = { emptyList() },
    /** Hidden Detailed categories also suppress native fallback chips; other modes pass an empty set. */
    hiddenBadgeCategories: Set<String> = emptySet(),
    /** Credit for the Where to watch data, e.g. "Data from JustWatch, via TMDB". */
    offersCredit: String? = null,
    /** Rating services that are on, in the user's order. */
    ratingSources: List<app.ember.tv.data.RatingSource> = emptyList(),
    /** Plays a YouTube trailer by video id. */
    onPlayTrailer: (String) -> Unit = {},
    /** One-press play: show the Play button, and the source it plays (null while looking or when none fits). */
    playButton: Boolean = false,
    best: StreamOption? = null,
    /** Why the Play button couldn't pick a source, when it couldn't (#145). */
    bestReason: String? = null,
    playbackLabel: String = "Play",
    watched: Boolean = false,
    /** Marks the movie, or the chosen episode, as watched. */
    onMarkWatched: () -> Unit = {},
    hasAddons: Boolean = false,
    onRetrySources: () -> Unit = {},
    onOpenPersonTitle: (app.ember.tv.data.Meta) -> Unit = {},
    filterPersonTitles: suspend (List<app.ember.tv.data.Meta>) -> List<app.ember.tv.data.Meta> = { it },
    personProfileKey: String = "default",
    /** "Load more as I scroll" (#109): more episodes load near the end of a season. */
    autoLoad: Boolean = true,
    /** Rating on your trackers (#84): shown when a connected tracker takes ratings. */
    canRate: Boolean = false,
    myRating: Int? = null,
    onRate: (Int?) -> Unit = {},
    /** Plays the source the Play button rules pick, whatever the Play button setting (#140). */
    onPlayBest: (() -> Unit)? = null,
) {
    val meta = d.meta
    var rating by remember(meta.id) { mutableStateOf(false) }
    var synopsis by remember(meta.id) { mutableStateOf(false) }
    var more by remember(meta.id) { mutableStateOf(false) }
    // Server sources: holding OK opens player choice, playback quality and server actions.
    var serverOptions by remember(meta.id) { mutableStateOf<StreamOption?>(null) }
    var qualitySource by remember(meta.id) { mutableStateOf<StreamOption?>(null) }
    var serverActions by remember(meta.id) { mutableStateOf<String?>(null) }
    var person by remember(meta.id) { mutableStateOf<Person?>(null) }
    val sourceKeys = remember(d.streams) { sourceRowKeys(d.streams) }
    val serverKeys = remember(d.serverStreams) { sourceRowKeys(d.serverStreams) }
    // Same for every source of this title, so worked out once.
    val runtimeLabel = meta.runtimeMinutes?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
    // Put the remote on the first button when the screen opens, so something is selected.
    val firstButton = remember { FocusRequester() }
    val sourcesFocus = remember {FocusRequester()}
    var sourcesHeadingFocused by remember(meta.mediaKey()) {mutableStateOf(false)}
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latestDetails by androidx.compose.runtime.rememberUpdatedState(d)
    val availability = mediaSourcePresentation(d,offers.size)
    val primaryTarget = detailsPlaybackTarget(playButton,best!=null,offers.size,availability.loading,offers.singleOrNull()?.let(offerInstalled)==true)
    val playbackAction = detailsPlaybackPresentation(playbackLabel,primaryTarget,best,availability,offers.singleOrNull()?.name,
        if(!playButton)null else bestReason)
    var headerHeightPx by remember(meta.mediaKey()) { mutableIntStateOf(0) }
    var headerActionsFocused by remember(meta.mediaKey()) { mutableStateOf(false) }
    var headingFocused by remember(meta.mediaKey()) { mutableStateOf(false) }
    val headingFocus = remember { FocusRequester() }
    val flix=isEmberX
    var panelSources by remember(meta.mediaKey()){androidx.compose.runtime.mutableStateOf(meta.type=="movie")}
    var panelNeedsFocus by remember(meta.mediaKey()){androidx.compose.runtime.mutableStateOf(false)}
    val episodePanelFocus=remember {FocusRequester()}
    val episodeTargets=remember(meta.mediaKey()) {mutableMapOf<String,FocusRequester>()}
    val seasonFocus=remember {FocusRequester()}
    var seasonMenu by remember(meta.mediaKey()){androidx.compose.runtime.mutableStateOf(false)}
    var episodeQuery by remember(meta.mediaKey(),d.season){androidx.compose.runtime.mutableStateOf("")}
    val returnToEpisodes:()->Unit={
        d.episode?.let {ep ->
            if(episodeQuery.isNotBlank() && !ep.title.contains(episodeQuery,true) && ep.episode.toString()!=episodeQuery.trim())episodeQuery=""
        }
        panelSources=false;panelNeedsFocus=true
    }
    if(seasonMenu)OptionMenu("Season",d.seasons.map {season->MenuOption("Season $season",selected=season==d.season){onSeason(season);panelSources=false;panelNeedsFocus=true}}) {seasonMenu=false}

    LaunchedEffect(meta.mediaKey()) {
        withFrameNanos { }
        runCatching { firstButton.requestFocus() }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides DetailsBringIntoView) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val largeText = LocalDensity.current.fontScale > 1.25f
        val split=app.ember.tv.data.MediaPageLayouts.split(LocalMediaPageLayout.current,maxWidth.value,LocalDensity.current.fontScale,maxHeight.value)
        val detailsWidth=maxWidth
    val sourceItems:androidx.compose.foundation.lazy.LazyListScope.()->Unit={
            // ---- sources: streaming services, then your media servers, then addons ----
            val sources = availability
            item(key = "sourcesHeader") {
                Column(Modifier.padding(horizontal = if(split)0.dp else 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ui_sources) + if (sources.total > 0) " · ${sources.total} found" else "", fontSize = TvTokens.Type.Section.sp, fontWeight = FontWeight.SemiBold,
                            modifier=Modifier.focusRequester(sourcesFocus).semantics {heading()}
                                .onFocusChanged {sourcesHeadingFocused=it.isFocused}.focusable()
                                .border(if(sourcesHeadingFocused)2.dp else 0.dp,if(sourcesHeadingFocused)Ring else Color.Transparent,RoundedCornerShape(8.dp))
                                .padding(4.dp))
                        if (hasAddons) Focusable(onClick = onRetrySources,
                            modifier = Modifier.size(42.dp).semantics { contentDescription = "Refresh sources" }, radius = TvTokens.Shape.Pill,
                            container = Wash, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false) {
                            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    if (d.streams.isNotEmpty() || d.serverStreams.isNotEmpty()) {
                        Text(stringResource(R.string.ui_hold_ok_on_a_source_to), color = Mute, fontSize = 13.sp)
                    }
                    if (d.subtitles.isNotEmpty()) {
                        val langs = d.subtitles.map { it.lang }.distinct()
                        Text(
                            stringResource(R.string.ui_subtitles) + langs.take(8).joinToString(", ") + if (langs.size > 8) " and ${langs.size - 8} more" else "",
                            color = Mute,
                            fontSize = 13.sp,
                        )
                    }
                    // An action, not a switch (#140): it never turns the Play button setting off.
                    if(split && onPlayBest!=null && (d.streams.isNotEmpty() || d.serverStreams.isNotEmpty()))
                        GlassControl("Play best",Icons.Outlined.AutoAwesome,Modifier.testTag("play_best"),onClick=onPlayBest)
                    sources.message?.let { Text(it, color = Mute, fontSize = 14.sp) }
                    if (sources.total == 0 && sources.loading) {if(flix)Text(stringResource(R.string.tx_finding_sources),color=Mute) else LoadingCards()}
                    if (app.ember.tv.mediaserver.ServerRef.of(meta) != null && d.serverStreams.isEmpty() && !d.loadingServer) {
                        Chip("Retry server connection", onClick = onRetrySources)
                    }
                }
            }

            // Sources in a fixed order: your servers, then streaming apps, then addon links.
            if (d.serverStreams.isNotEmpty()) {
                item(key = "grp:servers") {
                    GroupHeader("Your media", null, contentPadding=if(split)0.dp else 48.dp)
                }
                itemsIndexed(d.serverStreams, key = { i, _ -> "srv:${serverKeys[i]}" }) { _, s ->
                    Column(Modifier.padding(horizontal = if(split)0.dp else 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SourceRow(s, formatSource(s), badgesFor(s), runtimeLabel, hiddenBadgeCategories, onClick = { onPlay(s) },
                            onLongClick = { if (s.serverPlayback != null) serverOptions = s else onPlayWith(s) })
                    }
                }
            }

            // Only your own services: no section at all when none of them has the title.
            if (offers.isNotEmpty()) {
                item(key = "grp:services") { GroupHeader("Streaming services", offersCredit, contentPadding=if(split)0.dp else 48.dp) }
                items(offers, key = { "offer:${it.externalId ?: it.serviceId}" }) { o ->
                    Box(Modifier.padding(horizontal = if(split)0.dp else 48.dp)) {
                        ServiceRow(o, installed = offerInstalled(o)) { onOpenOffer(o) }
                    }
                }
            }


            if (d.streams.isNotEmpty()) {
                item(key = "grp:addons") {
                    GroupHeader("Addons", null, contentPadding=if(split)0.dp else 48.dp)
                }
                itemsIndexed(d.streams, key = { i, _ -> "src:${sourceKeys[i]}" }) { _, s ->
                    Box(Modifier.padding(horizontal = if(split)0.dp else 48.dp)) {
                        SourceRow(s, formatSource(s), badgesFor(s), runtimeLabel, hiddenBadgeCategories, onClick = { onPlay(s) }, onLongClick = { onPlayWith(s) })
                    }
                }
            }
    }

        val headerHeight = when {
            split -> (maxHeight-24.dp).coerceAtLeast(280.dp)
            else -> (maxHeight * 0.78f).coerceIn(280.dp,420.dp)
        }
        val headerFraction = if (split || largeText || maxWidth < 760.dp) 1f else 0.64f
        val viewportHeight = with(LocalDensity.current) { maxHeight.roundToPx() }
        val topPadding = with(LocalDensity.current) { 8.dp.roundToPx() }
        val tallHeader = headerHeightPx > 0 && headerHeightPx + topPadding > viewportHeight
        val navigation = LocalNavigationFocus.current
        val scene = LocalFocusScene.current
        DisposableEffect(navigation, scene, tallHeader) {
            if (scene != null && tallHeader) navigation?.register(scene, "details-heading", headingFocus)
            onDispose { if (scene != null) navigation?.unregister(scene, "details-heading", headingFocus) }
        }
        LaunchedEffect(headerActionsFocused, headingFocused, headerHeightPx, viewportHeight) {
            withFrameNanos { }
            if (shouldRevealDetailsHeader(headerHeightPx, viewportHeight, topPadding,
                    headerActionsFocused, headingFocused,
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                listState.animateScrollToItem(0)
            }
        }
        Row(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = modifier.weight(1f).fillMaxSize().clipToBounds().focusGroup(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- header ----
            item(key = "header") {
                // The header grows with long titles and wrapped buttons inside the content viewport.
                Box(Modifier.fillMaxWidth().heightIn(min = headerHeight).onSizeChanged { headerHeightPx = it.height }) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start=(if(flix)EmberXLayout.Gutter else TvTokens.Space.Page).dp,end=(if(flix)24 else TvTokens.Space.Page).dp,top=4.dp,bottom=12.dp).fillMaxWidth(headerFraction),
                        verticalArrangement = Arrangement.spacedBy(if(flix)4.dp else 6.dp),
                    ) {
                        // Oversized headers need a real focus stop above the actions so their
                        // title can be reached without keeping an offscreen button focused.
                        DetailsHeroTitle(meta,largeText,Modifier.semantics { heading() }
                            .focusRequester(headingFocus).onFocusChanged {
                                headingFocused=it.isFocused
                                if(it.isFocused && scene!=null)navigation?.focused(scene,"details-heading")
                            }.focusable(tallHeader)
                            .border(if(headingFocused)2.dp else 0.dp,if(headingFocused)Ring else Color.Transparent,RoundedCornerShape(8.dp)))
                        d.episode?.let {ep ->
                            Text(stringResource(R.string.tx_s_e, ep.season, ep.episode)+ep.title.takeIf {it.isNotBlank()}?.let {" · $it"}.orEmpty(),
                                fontSize=if(flix)14.sp else 18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        Text(listOfNotNull(
                            when(meta.type){"movie"->"Movie";"anime"->"Anime";else->"Series"},
                            meta.runtime,d.episode?.released ?: meta.releaseInfo,
                            meta.genres?.take(2)?.joinToString(", ")?.takeUnless {flix}
                        ).plus(d.facts).distinct().joinToString(" · "),fontSize=14.sp,color=Mute,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val shown = ratingSources.filter { d.ratings[it.id] != null }
                        if (shown.isNotEmpty()) {
                            ButtonRow(spacing = 18.dp) {
                                shown.forEach { src ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RatingIcon(src)
                                        Text(d.ratings.getValue(src.id), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        if(flix) {
                            meta.genres?.takeIf {it.isNotEmpty()}?.let {genres->
                                Text(stringResource(R.string.tx_genres),fontSize=TvTokens.Type.Caption.sp,letterSpacing=1.sp,color=EmberXColors.Muted,modifier=Modifier.padding(top=6.dp))
                                ButtonRow(spacing=6.dp) {genres.take(4).forEach {genre->EmberXMetadataPill(genre)}}
                            }
                            val cast=d.credits?.people.orEmpty().filterNot {it.isCrew}.take(3)
                            if(cast.isNotEmpty()) {
                                Text(stringResource(R.string.tx_cast),fontSize=TvTokens.Type.Caption.sp,letterSpacing=1.sp,color=EmberXColors.Muted,modifier=Modifier.padding(top=8.dp))
                                ButtonRow(spacing=6.dp) {cast.forEach {actor->EmberXMetadataPill(actor.name){person=actor}}}
                            }
                            val directors=d.credits?.people.orEmpty().filter {it.role?.contains("director",true)==true}.take(2)
                            if(directors.isNotEmpty()) {
                                Text(stringResource(R.string.tx_directors),fontSize=TvTokens.Type.Caption.sp,letterSpacing=1.sp,color=EmberXColors.Muted,modifier=Modifier.padding(top=8.dp))
                                ButtonRow(spacing=6.dp) {directors.forEach {director->EmberXMetadataPill(director.name){person=director}}}
                            }
                        }
                        detailsSynopsis(d)?.let {
                            ReadableDescription(it,fontSize=if(flix)14.sp else 15.sp,lineHeight=19.sp,maxLines=if(flix)2 else 3,modifier=Modifier.padding(top=if(flix)6.dp else 0.dp))
                        }
                        DetailsActions(saved,watched,meta.type in setOf("movie","series","anime","tv","show"),playbackAction,d.credits?.trailer!=null,firstButton,
                            onWatched=onMarkWatched,onSaved=onToggleSaved,onMore={more=true},
                            onPlay={when(primaryTarget) {
                                DetailsPlaybackTarget.SOURCE -> best?.let(onPlay)
                                DetailsPlaybackTarget.SERVICE -> offers.singleOrNull()?.let(onOpenOffer)
                                DetailsPlaybackTarget.CHOOSE -> scope.launch {
                                    panelSources=true
                                    repeat(2) {
                                        if(!split)listState.animateScrollToItem(detailsSourceHeaderIndex(latestDetails))
                                        withFrameNanos { }
                                        if(runCatching {sourcesFocus.requestFocus()}.getOrDefault(false))return@launch
                                    }
                                }
                            }},onTrailer={d.credits?.trailer?.let(onPlayTrailer)},
                            modifier=Modifier.padding(top=8.dp).fillMaxWidth().onFocusChanged {headerActionsFocused=it.hasFocus}.focusGroup())

                    }
                }
            }

            if(!split) {
            // ---- seasons and episodes ----
            if (d.loadingEpisodes) {
                item(key = "eploading") { Box(Modifier.padding(horizontal = 48.dp)) { LoadingCards(wide = true) } }
            }
            if (d.seasons.size > 1) {
                item(key = "seasons") {
                    Column(Modifier.padding(horizontal = 48.dp)) {
                        Section(stringResource(R.string.ui_season)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                                items(d.seasons, key = { it }) { s ->
                                    Chip(stringResource(R.string.ui_season_n, s), selected = s == d.season) { onSeason(s) }
                                }
                            }
                        }
                    }
                }
            }
            if (d.episodes.isNotEmpty()) {
                item(key = "episodes") {
                    val inSeason = d.episodes.filter {it.season==d.season}.distinctBy {it.id}
                    val state = rememberLazyListState()
                    LaunchedEffect(d.season) {
                        val i = inSeason.indexOfFirst { it.id == d.episode?.id }
                        if (i > 0) state.scrollToItem(i)
                    }
                    Column(Modifier.padding(horizontal = 48.dp)) {
                        Section(
                            stringResource(R.string.ui_episodes),
                            listOfNotNull(
                                d.episode?.let { "Showing sources for S${it.season} · E${it.episode}" },
                                d.metaSources.takeIf { it.isNotEmpty() }?.let { "Details from " + it.joinToString(" and ") },
                            ).joinToString(" · ").ifBlank { null },
                        ) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val episodeWidth=CardSizes.width(maxWidth,if(largeText)3.25f else 5.25f,14.dp,6.dp)
                            LazyRow(
                                state = state,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                            ) {
                                items(inSeason, key = { it.id }) { ep ->
                                    EpisodeCard(ep,selected=ep.id==d.episode?.id,width=episodeWidth,watched=LocalWatchedUi.current.episode(meta,ep)) {onEpisode(ep)}
                                }
                                // More episodes load on their own near the end of the season (#109).
                                if (d.episodeCursor != null && (!autoLoad || d.loadingEpisodes)) item(key = "more") {
                                    if (autoLoad) Box(Modifier.width(episodeWidth).height(episodeWidth * 9f / 16f).background(PanelHi, RoundedCornerShape(12.dp)))
                                    else Chip(if (d.loadingEpisodes) "Loading…" else "More episodes") { onMoreEpisodes() }
                                }
                            }
                            AutoLoadMore(state, inSeason.size, autoLoad && d.episodeCursor != null && !d.loadingEpisodes) { onMoreEpisodes() }
                            }
                            d.episode?.let { ep -> ep.overview?.let { ep to it } }?.let { (ep, plot) ->
                                Text(
                                    "E${ep.episode} · ${ep.title}: $plot",
                                    fontSize = 13.sp,
                                    color = Mute,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(0.75f),
                                )
                            }
                        }
                    }
                }
            }

            }
            // ---- directors and cast ----
            val people = d.credits?.people.orEmpty()
            if (people.isNotEmpty()) {
                item(key = "people") {
                    // Crew as label/value lines (Director, Writers, Created by), then the cast as
                    // large round portraits with the character underneath.
                    Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CrewLines(people.filter { it.isCrew }) { person = it }
                        val cast = people.filterNot { it.isCrew }
                        if (cast.isNotEmpty()) {
                            Text(stringResource(R.string.tx_cast_2), fontSize = TvTokens.Type.Section.sp, fontWeight = FontWeight.Medium, color = Fg, modifier = Modifier.padding(top = 8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                items(cast, key = { "${it.name}|${it.role}" }) { p -> PersonCard(p) { person = p } }
                            }
                        }
                    }
                }
            }

            if(!split)sourceItems()
        }
        if(split) {
            val panelWidth=(detailsWidth*.42f).coerceIn(320.dp,520.dp)
            val episodeList=rememberLazyListState()
            val sourceList=rememberLazyListState()
            val inSeason=remember(d.episodes,d.season,episodeQuery) {d.episodes.filter {it.season==d.season &&
                (episodeQuery.isBlank() || it.title.contains(episodeQuery,true) || it.episode.toString()==episodeQuery.trim())}.distinctBy {it.id}}
            val rightList=if(panelSources || d.episodes.isEmpty() && d.seasons.isEmpty())sourceList else episodeList
            LaunchedEffect(d.season){episodeList.scrollToItem(0)}
            LaunchedEffect(d.episode?.id){sourceList.scrollToItem(0)}
            // Previous/Next keep focus so they can be pressed again (#122); only when the pressed one
            // disappears (first or last episode) does focus move, to the other one.
            val previousFocus=remember {FocusRequester()}
            val nextFocus=remember {FocusRequester()}
            var stepped by remember(meta.mediaKey()) {mutableStateOf<Boolean?>(null)}
            LaunchedEffect(d.episode?.id) {
                val forward=stepped ?: return@LaunchedEffect
                stepped=null
                withFrameNanos { }
                val (pressed,other)=if(forward)nextFocus to previousFocus else previousFocus to nextFocus
                if(!runCatching {pressed.requestFocus()}.getOrDefault(false) && !runCatching {other.requestFocus()}.getOrDefault(false))
                    runCatching {seasonFocus.requestFocus()}
            }
            LaunchedEffect(panelSources,panelNeedsFocus) {
                if(panelNeedsFocus) {
                    val selected=inSeason.indexOfFirst {it.id==d.episode?.id}
                    if(!panelSources && selected>=0)episodeList.scrollToItem(selected)
                    requestSettingsFocus({
                        if(panelSources)sourcesFocus.requestFocus()
                        else (d.episode?.id?.let {episodeTargets[it]} ?: episodePanelFocus).requestFocus()
                    },{withFrameNanos { }}) {runCatching {seasonFocus.requestFocus()}}
                    panelNeedsFocus=false
                }
            }
            androidx.activity.compose.BackHandler(enabled=panelSources && meta.type!="movie" && (d.episodes.isNotEmpty() || d.seasons.isNotEmpty()),onBack=returnToEpisodes)
            Column(Modifier.width(panelWidth).fillMaxHeight().padding(start=6.dp,end=24.dp,top=4.dp,bottom=16.dp)
                .glassSurface(RoundedCornerShape(20.dp)).padding(12.dp)
                .semantics {contentDescription="Episodes and playback sources"},verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val hasEpisodes=d.episodes.isNotEmpty() || d.seasons.isNotEmpty()
                if(hasEpisodes)ButtonRow(spacing=6.dp) {
                    if(panelSources)GlassControl("Episodes",Icons.AutoMirrored.Outlined.ArrowBack,
                        Modifier.focusRequester(seasonFocus),onClick=returnToEpisodes)
                    else {
                        GlassControl("Season ${d.season ?: d.seasons.firstOrNull() ?: 1}",null,
                            Modifier.focusRequester(seasonFocus),dropdown=true) {seasonMenu=true}
                        if(d.episode!=null)GlassControl("Sources",null,dropdown=true) {panelSources=true;panelNeedsFocus=true}
                    }
                }
                if(panelSources) d.episode?.let {ep ->
                    EmberXEpisodeRow(ep,false,LocalWatchedUi.current.episode(meta,ep),onClick=returnToEpisodes)
                }
                if(!panelSources && hasEpisodes)TvTextField(episodeQuery,{episodeQuery=it.take(120)},Modifier.fillMaxWidth(),placeholder="Search episodes",glass=true)
                if(!panelSources)AutoLoadMore(episodeList,d.episodes.count {it.season==d.season},autoLoad && d.episodeCursor!=null && !d.loadingEpisodes) {onMoreEpisodes()}
                LazyColumn(state=rightList,modifier=Modifier.fillMaxWidth().weight(1f).focusRequester(episodePanelFocus),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(3.dp)) {
                    if(!panelSources && hasEpisodes) {
                        if(d.loadingEpisodes && d.episodes.none {it.season==d.season})item {LoadingListRows()}
                        items(inSeason,key={it.id}) {episode->
                            val target=remember(meta.mediaKey(),episode.id) {episodeTargets.getOrPut(episode.id){FocusRequester()}}
                            EmberXEpisodeRow(episode,episode.id==d.episode?.id,LocalWatchedUi.current.episode(meta,episode),
                                Modifier.focusRequester(target)) {onEpisode(episode);panelSources=true;panelNeedsFocus=true}
                        }
                        if(inSeason.isEmpty() && !d.loadingEpisodes)item {Text(stringResource(R.string.tx_no_matching_episodes),color=Mute,fontSize=13.sp)}
                        if(d.episodeCursor!=null && (!autoLoad || d.loadingEpisodes))item(key="more") {if(autoLoad)LoadingListRows(2) else Chip(if(d.loadingEpisodes)"Loading…" else "More episodes",onClick=onMoreEpisodes)}
                    } else sourceItems()
                }
                if(panelSources && d.episode!=null) {
                    val ordered=remember(d.episodes,d.season) {d.episodes.filter {it.season==d.season}.distinctBy {it.id}.sortedBy {it.episode}}
                    val index=ordered.indexOfFirst {it.id==d.episode.id}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        ordered.getOrNull(index-1)?.takeIf {index>0}?.let {ep ->
                            GlassControl("Previous",Icons.AutoMirrored.Outlined.ArrowBack,Modifier.focusRequester(previousFocus).testTag("episode_previous")) {stepped=false;onEpisode(ep)}
                        }
                        ordered.getOrNull(index+1)?.takeIf {index>=0}?.let {ep ->
                            GlassControl("Next",Icons.AutoMirrored.Outlined.ArrowForward,Modifier.focusRequester(nextFocus).testTag("episode_next")) {stepped=true;onEpisode(ep)}
                        }
                    }
                }
            }
        }
        }
      }
    } // LocalBringIntoViewSpec
    serverOptions?.let { s -> s.serverPlayback?.let { playback ->
        OptionMenu("Source options", listOf(
            MenuOption("Play with…", icon=Icons.Outlined.PlayArrow) { onPlayWith(s) },
            MenuOption("Playback quality", icon=Icons.Outlined.Tune) { qualitySource = s },
            MenuOption("Server actions", icon=Icons.Outlined.Dns) { serverActions = playback.ref.value },
        )) { serverOptions = null }
    } }
    qualitySource?.let { ServerQualityDialog(it, onPlay) { qualitySource = null } }
    serverActions?.let { ServerActionsDialog(it) { serverActions = null } }
    if (synopsis) SynopsisDialog(meta.name, meta.description.orEmpty()) { synopsis = false }
    if(more)MediaDialog("About · ${meta.name}",{more=false}) {
        Text(listOfNotNull(meta.releaseInfo,meta.runtime,meta.genres?.joinToString(", ")).plus(d.facts).filter(String::isNotBlank).distinct().joinToString(" · "),color=Mute,fontSize=14.sp)
        d.episode?.let {ep->Text(stringResource(R.string.tx_season_episode, ep.season, ep.episode, ep.title),fontWeight=FontWeight.SemiBold)}
        detailsSynopsis(d)?.let {Text(it,fontSize=16.sp,lineHeight=23.sp)}
        if(!d.episode?.overview.isNullOrBlank() && !meta.description.isNullOrBlank())Chip("About the series") {more=false;synopsis=true}
        if(d.metaSources.isNotEmpty())Text(stringResource(R.string.tx_details_from)+d.metaSources.joinToString(" and "),color=Mute,fontSize=12.sp)
        if(isContinue)Chip("Remove from Continue Watching") {more=false;onRemoveContinue()}
        if(canRate)Chip(if(myRating!=null)"Your rating: $myRating/10 · change" else "Rate on your trackers") {more=false;rating=true}
    }
    if(rating)MediaDialog("Rate · ${meta.name}",{rating=false}) {
        Text(stringResource(R.string.tx_1_is_the_lowest_10_the),color=Mute,fontSize=14.sp)
        ButtonRow(spacing=6.dp) {
            (1..10).forEach {n-> Chip(n.toString(),n==myRating) {rating=false;onRate(n)} }
        }
        if(myRating!=null)Chip("Clear my rating") {rating=false;onRate(null)}
    }
    person?.let { selected -> key(selected,personProfileKey) {
        PersonDialog(selected,meta,{person=null},filterPersonTitles) { title -> person=null;onOpenPersonTitle(title) }
    } }

}
