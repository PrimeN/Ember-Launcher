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

/** Director, Writers and Created by as label/value lines; each name opens that person. */
@Composable
private fun CrewLines(crew: List<Person>, onPerson: (Person) -> Unit) {
    val groups = remember(crew) {
        crew.groupBy { p ->
            val role = p.role.orEmpty().lowercase(java.util.Locale.ROOT)
            when {
                "director" in role -> "Director"
                "writ" in role || "screenplay" in role || "story" in role || "novel" in role -> "Writers"
                "creat" in role -> "Created by"
                else -> "Crew"
            }
        }.mapValues { (_, people) -> people.distinctBy { it.name }.take(4) }
            .toList().sortedBy { (label, _) -> listOf("Director", "Created by", "Writers", "Crew").indexOf(label) }
    }
    if (groups.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { (label, people) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (label == "Director" && people.size > 1) "Directors" else label, fontSize = 16.sp, color = Mute, modifier = Modifier.width(150.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    people.forEachIndexed { index, p ->
                        Focusable(onClick = { onPerson(p) }, radius = TvTokens.Shape.Small, container = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
                            Text(p.name + if (index < people.lastIndex) "," else "", fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A cast member: a large round portrait (or initials), the name, and the character in a softer colour. */
@Composable
private fun PersonCard(p: Person, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, label = "cast portrait")
    // Fixed width and two text lines keep long names from resizing the row.
    Focusable(onClick = onClick, modifier = Modifier.width(150.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Card, container = androidx.compose.ui.graphics.Color.Transparent, focusedContainer = androidx.compose.ui.graphics.Color.Transparent,
        focusedContent = Fg, outline = false, focusedScale = TvTokens.Focus.None) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.size(126.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(PanelHi)
                    .then(if (focused) Modifier.border(3.dp, androidx.compose.ui.graphics.Color.White, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                    fontSize = TvTokens.Type.Section.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Mute,
                )
                if (p.photo != null) ArtworkImage(model = p.photo, description = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(p.name, modifier = Modifier.fillMaxWidth(), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                color = if (focused) Fg else Fg.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(p.role.orEmpty(), modifier = Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 16.sp, color = Mute, minLines = 1,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Small heading above each group of sources, with an optional status line. */
@Composable
private fun GroupHeader(title: String, note: String?, noteIsWarning: Boolean = false,contentPadding:androidx.compose.ui.unit.Dp=48.dp) {
    Column(Modifier.padding(start = contentPadding, end = contentPadding, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Mute, letterSpacing = 1.2.sp)
        note?.let {
            Text(it, fontSize = 14.sp, color = if (noteIsWarning) MaterialTheme.colorScheme.primary else Mute)
        }
    }
}

@Composable
private fun EpisodeCard(ep: Episode, selected: Boolean, width: androidx.compose.ui.unit.Dp=230.dp, watched:Boolean=false, onClick: () -> Unit) {
    val blurred=SpoilerPolicy.blur(LocalWatchedUi.current.blurEpisodes,watched,LocalWatchedUi.current.ready)
    val context=androidx.compose.ui.platform.LocalContext.current
    val fade=if(blurred)0 else PolishMotion.duration(TvTokens.Motion.Image,LocalRenderOptions.current,LocalReduceMotion.current)
    val image=remember(context,ep.thumbnail,blurred,fade) {coil.request.ImageRequest.Builder(context).data(ep.thumbnail).size(480,270).allowHardware(false).crossfade(fade)
        .apply {if(blurred)transformations(SpoilerBlurTransformation)}.build()}
    var imageReady by remember(image){mutableStateOf(false)}
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Focusable(onClick = onClick, modifier = Modifier.semantics {
            this.selected = selected
            contentDescription = "Season ${ep.season}, episode ${ep.episode}, ${ep.title}"
            stateDescription=if(watched)"Watched" else if(blurred)"Unwatched artwork blurred" else "Unwatched"
        }, container = if (selected) PanelHi else Panel,card=true) {
            Box(Modifier.width(width).height(width*9f/16f).clearAndSetSemantics { }) {
                if(!imageReady)ArtworkPlaceholder(null,Modifier.fillMaxSize())
                key(image) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(if(blurred && !imageReady)0f else 1f),
                    onSuccess={imageReady=true},
                    onError={imageReady=false},
                )
                }
                WatchedBadge(watched,Modifier.align(Alignment.TopEnd))
                if(blurred)Text(stringResource(R.string.tx_unwatched),fontSize=TvTokens.Type.Caption.sp,color=Color.White,modifier=Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color(0xBB202124),RoundedCornerShape(6.dp)).padding(horizontal=6.dp,vertical=3.dp))
                if (selected) {
                    Text(
                        stringResource(R.string.ui_selected),
                        fontSize = TvTokens.Type.Caption.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(stringResource(R.string.ui_episode_line, ep.episode, ep.title), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
        ep.released?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = Mute) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(
    s: StreamOption,
    formatted: Pair<String, String>?,
    /** Every badge the source matches; ones switched off singly (#194) are not drawn but still cover their category. */
    allBadges: List<app.ember.tv.data.BadgeFilter>,
    runtime: String?,
    hiddenCategories: Set<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val images = allBadges.filter { it.isEnabled }
    val accent = MaterialTheme.colorScheme.primary
    if(isEmberX) {
        EmberXSourceRow(s,formatted,allBadges,runtime,hiddenCategories,onClick,onLongClick)
        return
    }
    if (formatted != null) {
        FormattedSourceRow(formatted, images, onClick, onLongClick)
        return
    }
    Focusable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QualityColumn(s.qualityLabel, s.kind, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    app.ember.tv.data.Badges.clean(s.title.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: s.name),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Keep only metadata not already represented by the selected badge pack.
                val badges = listOfNotNull(runtime?.let { "⏱ $it" }) +
                    app.ember.tv.data.DetailedBadgeCategories.textTags(s, hiddenCategories, languageLimit = 3, badges = allBadges) +
                    listOfNotNull(s.sizeGb?.let { "%.1f GB".format(it) })
                if (badges.isNotEmpty() || images.isNotEmpty()) {
                    // One row (wrapping when a source has many details): image badges first, then the
                    // text tags, all the same height and shape.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        images.forEach { SourceBadge(it) }
                        badges.forEach { Badge(it) }
                    }
                }
            }
            Text(s.addonName, fontSize = 12.sp, color = Mute, maxLines = 1)
        }
    }
}

/** Fixed quality column, title, and two badge lines; provider details live in More. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmberXSourceRow(s:StreamOption,formatted:Pair<String,String>?,allBadges:List<app.ember.tv.data.BadgeFilter>,
    runtime:String?,hiddenCategories:Set<String>,onClick:()->Unit,onLongClick:()->Unit) {
    val images=allBadges.filter {it.isEnabled}
    val title=app.ember.tv.data.Badges.clean(formatted?.first ?: s.title.lineSequence().firstOrNull()?.takeIf {it.isNotBlank()} ?: s.name)
    // Duration and file size lead even when image badges or a custom formatter are enabled.
    val primaryPills=emberXSourcePrimaryPills(s,runtime)
    val tags=app.ember.tv.data.DetailedBadgeCategories.textTags(s,emptySet())
    val description=app.ember.tv.data.Badges.clean(formatted?.second ?: (primaryPills+tags).joinToString(" · "))
    val pills=if(formatted!=null && images.isEmpty() && hiddenCategories.isEmpty())listOf(description.replace(Regex("\\s+")," ").trim()).filter(String::isNotBlank)
        else app.ember.tv.data.DetailedBadgeCategories.textTags(s,hiddenCategories,badges=allBadges)
    var info by remember(s) {mutableStateOf(false)}
    val density=LocalDensity.current
    val nameHeight=maxOf(28.dp,with(density){18.sp.toDp()})
    val formatsHeight=maxOf(24.dp,with(density){18.sp.toDp()})
    val badgesHeight=formatsHeight*2+6.dp
    val playFocus=remember {FocusRequester()}
    val infoFocus=remember {FocusRequester()}
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    // Playback and information are siblings: a clickable Surface must not own the info button's focus.
    Row(Modifier.fillMaxWidth().height(nameHeight+badgesHeight+26.dp)
        .background(Panel.copy(alpha=.45f),RoundedCornerShape(14.dp)),verticalAlignment=Alignment.CenterVertically) {
        Focusable(onClick,onLongClick=onLongClick,focusedScale=TvTokens.Focus.None,modifier=Modifier.weight(1f).fillMaxHeight()
            .focusRequester(playFocus).focusProperties {if(rtl)left=infoFocus else right=infoFocus}
            .semantics {contentDescription=listOf(title,description,s.addonName,s.qualityLabel,s.kind.label).filter(String::isNotBlank).joinToString(". ")}) {
            Row(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(64.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(s.qualityLabel,fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(s.kind.label,fontSize=TvTokens.Type.Caption.sp,lineHeight=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                        color=if(s.kind==SourceKind.UNCACHED)Mute else MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(title,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().height(nameHeight))
                    FlowRow(Modifier.fillMaxWidth().height(badgesHeight),horizontalArrangement=Arrangement.spacedBy(6.dp),
                        verticalArrangement=Arrangement.spacedBy(6.dp),maxLines=2) {
                        primaryPills.forEach {tag-> EmberXSourcePill(tag,formatsHeight) }
                        images.forEach {badge->
                            Box(Modifier.height(formatsHeight),contentAlignment=Alignment.Center){SourceBadge(badge)}
                        }
                        pills.forEach {tag-> EmberXSourcePill(tag,formatsHeight) }
                    }
                }
            }
        }
        Focusable(onClick={info=true},modifier=Modifier.padding(end=8.dp).size(32.dp)
            .focusRequester(infoFocus).focusProperties {if(rtl)right=playFocus else left=playFocus}
            .semantics {contentDescription="Source details for $title"},radius=TvTokens.Shape.Pill,
            container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=TvTokens.Focus.None) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Info,null,modifier=Modifier.size(20.dp))}
        }
    }
    if(info)MediaDialog("Source details",{info=false}) {
        Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        Text(description,fontSize=14.sp,color=Mute)
        Text(listOf(s.qualityLabel,s.kind.label).filter(String::isNotBlank).joinToString(" · "),fontSize=12.sp,color=Mute)
        if(s.addonName.isNotBlank())Text(stringResource(R.string.tx_source, s.addonName),fontSize=13.sp,color=Mute)
        if(images.isNotEmpty())BadgeImages(images)
    }
}

@Composable
private fun EmberXSourcePill(tag:String,height:androidx.compose.ui.unit.Dp) {
    Box(Modifier.height(height).widthIn(max=180.dp).clip(RoundedCornerShape(6.dp)).background(PanelHi)
        .padding(horizontal=7.dp),contentAlignment=Alignment.Center) {
        Text(tag,fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

/** An addon source shown with the user's name and description templates (Settings > Stremio). */
@Composable
private fun FormattedSourceRow(formatted: Pair<String, String>, images: List<app.ember.tv.data.BadgeFilter>, onClick: () -> Unit, onLongClick: () -> Unit) {
    val name = app.ember.tv.data.Badges.clean(formatted.first)
    val description = app.ember.tv.data.Badges.clean(formatted.second)
    Focusable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Source name on the left, description beside it.
            Text(
                name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
                modifier = Modifier.width(260.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    description,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                if (images.isNotEmpty()) BadgeImages(images)
            }
        }
    }
}

/** Quality on top (4K, FHD, HD, SD) and how the source plays underneath. */
@Composable
private fun QualityColumn(quality: String, kind: SourceKind, accent: Color) {
    Column(Modifier.width(96.dp)) {
        Text(quality.ifBlank { "—" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            kind.label,
            fontSize = 12.sp,
            color = if (kind == SourceKind.UNCACHED) Mute else accent,
        )
    }
}

/** A streaming service that has the title: opens its TV app, or the store when it is not installed. */
@Composable
private fun ServiceRow(o: Offer, installed: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QualityColumn(o.quality, SourceKind.SERVICE, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(o.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(o.label)
                    if (!installed) Badge("App not installed")
                }
            }
            Text(if (installed) "Opens app" else "Get app", fontSize = 12.sp, color = Mute, maxLines = 1)
        }
    }
}

@Composable
private fun BadgeImages(list: List<app.ember.tv.data.BadgeFilter>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { SourceBadge(it) }
    }
}

/**
 * A pack's styling applies to its image and to the label shown while loading or on failure.
 */
private const val ASSET_PREFIX = "file:///android_asset/"

/** Width ÷ height of a PNG in the app's assets, from its header; null if it is not a PNG. */
private fun pngRatio(context: android.content.Context, path: String): Float? = runCatching {
    context.assets.open(path).use { input ->
        val h = ByteArray(24)
        if (input.read(h) < 24) return null
        val w = java.nio.ByteBuffer.wrap(h, 16, 4).int
        val ht = java.nio.ByteBuffer.wrap(h, 20, 4).int
        if (w <= 0 || ht <= 0) null else BadgeArtwork.ratio(w.toFloat(),ht.toFloat())
    }
}.getOrNull()

private val bundledBadgeRatios=WeightedArtworkCache<String,Float>(4096){4}
private fun bundledBadgeRatio(context:android.content.Context,path:String):Float? {
    bundledBadgeRatios.get(path)?.let {return it}
    return pngRatio(context,path)?.also {bundledBadgeRatios.put(path,it)}
}

@Composable
internal fun SourceBadge(b: app.ember.tv.data.BadgeFilter) {
    val url = b.imageURL?.takeIf { it.isNotBlank() }
    val appearance = remember(b) { b.appearance() }
    val imageTint = remember(b.imageTint) { app.ember.tv.data.Badges.color(b.imageTint)?.let { androidx.compose.ui.graphics.ColorFilter.tint(Color(it)) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bundledRatio = remember(context, url) {
        url?.takeIf { it.startsWith(ASSET_PREFIX) }?.let { bundledBadgeRatio(context, it.removePrefix(ASSET_PREFIX)) }
    }
    val painter = if (url != null) {
        val request = remember(context, url) {
            coil.request.ImageRequest.Builder(context).data(url)
                .size(BadgeArtwork.MAX_WIDTH, BadgeArtwork.MAX_HEIGHT)
                .precision(coil.size.Precision.INEXACT).scale(coil.size.Scale.FIT)
                .allowRgb565(false).build()
        }
        coil.compose.rememberAsyncImagePainter(request)
    } else null
    val imageReady = painter?.state is coil.compose.AsyncImagePainter.State.Success
    val ratio = if (imageReady) bundledRatio ?: BadgeArtwork.ratio(painter.intrinsicSize.width, painter.intrinsicSize.height) else null
    val fill = appearance.background?.let(::Color) ?: if (appearance.filled && !imageReady) PanelHi else Color.Transparent
    BadgeBox(fill, appearance.border?.let(::Color), appearance.borderWidth.dp, appearance.radius.dp) {
        if (ratio != null && painter != null) {
            androidx.compose.foundation.Image(painter, contentDescription = b.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                colorFilter = imageTint,
                modifier = Modifier.height(BADGE_IMAGE_H).width(BADGE_IMAGE_H * ratio))
        } else {
            Text(b.name, fontSize = TvTokens.Type.Caption.sp, fontWeight = FontWeight.SemiBold,
                color = appearance.foreground?.let(::Color) ?: Fg, maxLines = 1, softWrap = false)
        }
    }
}

/** Every tag under a source has this height, image or text. */
private val BADGE_H = 24.dp
private val BADGE_IMAGE_H = 15.dp

/** The tag shape: a rounded box of the badge height with the content centred. */
@Composable
private fun BadgeBox(fill: Color = PanelHi, border: Color? = null,
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp, radius: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .height(BADGE_H)
            .clip(shape)
            .background(fill)
            .then(if (border != null && borderWidth > 0.dp) Modifier.border(borderWidth, border, shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = if (border != null) borderWidth else 0.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Badge(text: String) {
    BadgeBox { Text(text, fontSize = TvTokens.Type.Caption.sp, maxLines = 1) }
}


/** Compact vertical episode rows, with the same spoiler policy as the standard media page. */
@Composable
private fun EmberXEpisodeRow(ep:Episode,selected:Boolean,watched:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val blurred=SpoilerPolicy.blur(LocalWatchedUi.current.blurEpisodes,watched,LocalWatchedUi.current.ready)
    val context=androidx.compose.ui.platform.LocalContext.current
    val request=remember(context,ep.thumbnail,blurred) {coil.request.ImageRequest.Builder(context).data(ep.thumbnail)
        .size(240,135).allowHardware(false).apply {if(blurred)transformations(SpoilerBlurTransformation)}.build()}
    var ready by remember(request){androidx.compose.runtime.mutableStateOf(false)}
    Focusable(onClick,modifier.fillMaxWidth().semantics {
        this.selected=selected;contentDescription="Season ${ep.season}, episode ${ep.episode}, ${ep.title}"
        stateDescription=if(watched)"Watched" else if(blurred)"Unwatched artwork blurred" else "Unwatched"
    },radius=TvTokens.Shape.Small,container=if(selected)EmberXColors.Selected.copy(alpha=.75f) else Color.Transparent,
        focusedContainer=EmberXColors.Selected,focusedContent=Color.White,focusedScale=TvTokens.Focus.Inline,card=true) {
        Row(Modifier.fillMaxWidth().padding(7.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(4.dp)).clearAndSetSemantics {}) {
                if(!ready)ArtworkPlaceholder(null,Modifier.fillMaxSize())
                AsyncImage(request,null,Modifier.fillMaxSize().alpha(if(blurred && !ready)0f else 1f),contentScale=ContentScale.Crop,onSuccess={ready=true},onError={ready=false})
                WatchedBadge(watched,Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("${ep.episode}. ${ep.title}",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                ep.released?.let {Text(it,color=EmberXColors.Muted,fontSize=TvTokens.Type.Caption.sp,maxLines=1)}
                ep.progress?.takeIf {it.isFinite() && it>0f && it<1f}?.let {progress ->
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha=.15f))) {
                        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}


@Composable
private fun EmberXMetadataPill(label:String,onClick:(()->Unit)?=null) {
    if(onClick==null)Text(label,fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,color=Fg,maxLines=1,
        modifier=Modifier.background(Color.White.copy(alpha=.10f),RoundedCornerShape(16.dp)).padding(horizontal=9.dp,vertical=5.dp))
    else Focusable(onClick,modifier=Modifier.widthIn(max=124.dp).semantics {contentDescription=label},radius=TvTokens.Shape.Large,
        container=Color.White.copy(alpha=.10f),focusedContainer=Color.White,focusedContent=Color.Black,outline=false,focusedScale=TvTokens.Focus.Inline) {
        Text(label,fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),
            maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
