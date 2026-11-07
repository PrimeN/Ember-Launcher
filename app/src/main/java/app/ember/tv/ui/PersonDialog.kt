package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.*
import app.ember.tv.discover.ExploreRef
import app.ember.tv.discover.ExploreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * Cast & crew (#92): photo, role and a short bio up top, and their movies and shows as poster
 * rows that load as soon as it opens. When several people share the name, you pick one; Ember
 * never guesses. The lookup belongs to the popup: Back closes it and cancels the request.
 */
@Composable
internal fun PersonDialog(person:Person,current:Meta,close:()->Unit,
    filterTitles:suspend(List<Meta>)->List<Meta>,open:(Meta)->Unit) {
    val context=LocalContext.current
    val repo=remember {ExploreRepository(File(context.cacheDir,"person-credits"))}
    val closeFocus=remember {FocusRequester()}
    val firstTitle=remember {FocusRequester()}
    val firstChoice=remember {FocusRequester()}
    var attempt by remember {mutableIntStateOf(0)}
    var loading by remember {mutableStateOf(true)}
    var choices by remember {mutableStateOf<List<ExploreRef>>(emptyList())}
    var selected by remember {mutableStateOf<ExploreRef?>(null)}
    var titles by remember {mutableStateOf<List<Meta>>(emptyList())}
    var info by remember {mutableStateOf<ExploreRepository.PersonInfo?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var canRetry by remember {mutableStateOf(false)}

    // Starts at once. Keyed on the popup's own state, so closing it (Back) cancels the request.
    LaunchedEffect(attempt,selected) {
        loading=true;message=null;canRetry=false;titles=emptyList();choices=emptyList()
        try {
            if(!repo.configured) {message="Add a TMDB API key in Settings › API keys to see their other titles.";return@LaunchedEffect}
            val known=selected ?: person.tmdbId?.takeIf {it>0}?.let {ExploreRef(it,person.name,person.photo)}
            val candidates=if(known!=null)listOf(known) else repo.search(person.name,false)
            val identity=known ?: ExploreRepository.personIdentity(person,candidates)
            if(identity==null) {
                choices=candidates
                if(candidates.isEmpty())message="TMDB doesn't know anyone called ${person.name}."
            } else {
                kotlinx.coroutines.coroutineScope {
                    // The bio is a nice-to-have: it loads beside the titles and may fail on its own.
                    launch {info=runCatching {repo.personInfo(identity.id)}.onFailure {if(it is CancellationException)throw it}.getOrNull()}
                    titles=otherPersonTitles(current,filterTitles(repo.filmography(identity,person.isCrew)))
                }
                if(titles.isEmpty())message="No other movies or shows to show."
            }
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {message="Couldn't load their titles. Check the connection.";canRetry=true}
        finally {loading=false}
    }
    // Land on the first poster (or the first person to choose) when they arrive.
    LaunchedEffect(titles,choices) {
        withFrameNanos { }
        when {
            titles.isNotEmpty() -> runCatching {firstTitle.requestFocus()}
            choices.isNotEmpty() -> runCatching {firstChoice.requestFocus()}
        }
    }

    val movies=remember(titles) {titles.filter {it.type=="movie"}}
    val shows=remember(titles) {titles.filter {it.type!="movie"}}
    val photo=person.photo ?: info?.photo ?: selected?.image

    TvDialog(close,width=1040.dp,height=640.dp,glassSurface=true) {
        LaunchedEffect(Unit) {runCatching {closeFocus.requestFocus()}}
        // Header: photo, name, role, facts and a short bio.
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Box(Modifier.width(112.dp).height(168.dp).clip(RoundedCornerShape(TvTokens.Shape.Card.dp)).background(PanelHi),
                contentAlignment=Alignment.Center) {
                if(photo!=null)ArtworkImage(photo,person.name,Modifier.fillMaxSize())
                else Text(person.name.split(' ').mapNotNull {it.firstOrNull()?.uppercaseChar()}.take(2).joinToString(""),
                    fontSize=34.sp,fontWeight=FontWeight.SemiBold,color=Mute)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(person.name,fontSize=26.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                val role=listOfNotNull(person.role?.takeIf {it.isNotBlank()}?.let {if(person.isCrew)it else "as $it"},
                    info?.knownFor?.let {"Known for $it"}).joinToString(" · ")
                if(role.isNotEmpty())Text(role,color=Mute,fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                val facts=listOfNotNull(info?.born?.take(4)?.let {"Born $it"},info?.place).joinToString(" · ")
                if(facts.isNotEmpty())Text(facts,color=Mute,fontSize=TvTokens.Type.Caption.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                info?.biography?.let {Text(it,fontSize=14.sp,lineHeight=20.sp,maxLines=3,overflow=TextOverflow.Ellipsis)}
            }
            ToolbarIcon(androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_close),Icons.Outlined.Close,
                Modifier.focusRequester(closeFocus),onClick=close)
        }
        Column(Modifier.fillMaxWidth().weight(1f,fill=false).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            when {
                loading -> {
                    PosterShelfPlaceholder("Movies")
                    PosterShelfPlaceholder("Shows")
                }
                choices.isNotEmpty() -> {
                    ShelfTitle("Which ${person.name}?","Several people share this name")
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=6.dp)) {
                        items(choices,key={"person:${it.id}"}) {choice->
                            PersonChoice(choice,if(choice==choices.first())Modifier.focusRequester(firstChoice) else Modifier) {selected=choice}
                        }
                    }
                }
                else -> {
                    if(movies.isNotEmpty())PosterShelf("Movies",movies,firstTitle,open)
                    if(shows.isNotEmpty())PosterShelf("Shows",shows,if(movies.isEmpty())firstTitle else null,open)
                }
            }
            message?.let {Text(it,color=Mute,fontSize=14.sp)}
            if(canRetry)Chip("Try again") {attempt++}
        }
        Text("Filmography from TMDB",fontSize=TvTokens.Type.Caption.sp,color=Mute)
    }
}

@Composable
private fun ShelfTitle(title:String,detail:String?=null) {
    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
        detail?.let {Text(it,color=Mute,fontSize=TvTokens.Type.Caption.sp)}
    }
}

private val POSTER_W=104.dp
private val POSTER_H=156.dp

@Composable
private fun PosterShelf(title:String,items:List<Meta>,first:FocusRequester?,open:(Meta)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        ShelfTitle(title,"${items.size}")
        LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=6.dp,horizontal=2.dp)) {
            items(items,key={it.mediaKey()}) {meta->
                val modifier=if(first!=null && meta==items.first())Modifier.focusRequester(first) else Modifier
                Column(Modifier.width(POSTER_W),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Focusable(onClick={open(meta)},modifier=modifier,card=true) {
                        Box(Modifier.width(POSTER_W).height(POSTER_H).background(PanelHi),contentAlignment=Alignment.Center) {
                            if(meta.poster!=null)ArtworkImage(meta.poster,meta.name,Modifier.fillMaxSize())
                            else Text(meta.name,fontSize=12.sp,color=Mute,maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(8.dp))
                        }
                    }
                    Text(meta.name,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=16.sp)
                    meta.releaseInfo?.take(4)?.let {Text(it,color=Mute,fontSize=TvTokens.Type.Caption.sp)}
                }
            }
        }
    }
}

/** Grey poster shapes while the titles load, so the popup keeps its size. */
@Composable
private fun PosterShelfPlaceholder(title:String) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        ShelfTitle(title)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.padding(vertical=6.dp)) {
            repeat(7) {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(POSTER_W).height(POSTER_H).clip(RoundedCornerShape(TvTokens.Shape.Card.dp)).background(PanelHi))
                    Box(Modifier.width(POSTER_W*0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(PanelHi))
                }
            }
        }
    }
}

@Composable
private fun PersonChoice(choice:ExploreRef,modifier:Modifier,onClick:()->Unit) {
    Column(Modifier.width(POSTER_W),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Focusable(onClick=onClick,modifier=modifier,card=true) {
            Box(Modifier.width(POSTER_W).height(POSTER_H).background(PanelHi),contentAlignment=Alignment.Center) {
                if(choice.image!=null)ArtworkImage(choice.image,choice.name,Modifier.fillMaxSize())
                else Text(choice.name.split(' ').mapNotNull {it.firstOrNull()?.uppercaseChar()}.take(2).joinToString(""),fontSize=28.sp,color=Mute)
            }
        }
        Text(choice.name,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}
