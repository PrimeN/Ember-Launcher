package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import java.io.File

/** Search is explicit; identity ambiguity is resolved by the user rather than guessed. */
@Composable
internal fun PersonDialog(person:Person,current:Meta,close:()->Unit,
    filterTitles:suspend(List<Meta>)->List<Meta>,open:(Meta)->Unit) {
    val context=LocalContext.current
    val repo=remember {ExploreRepository(File(context.cacheDir,"person-credits"))}
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    val search=remember {FocusRequester()};val results=remember {FocusRequester()}
    val state=rememberLazyListState()
    var attempt by remember {mutableIntStateOf(0)}
    var loading by remember {mutableStateOf(false)}
    var choices by remember {mutableStateOf<List<ExploreRef>>(emptyList())}
    var selected by remember {mutableStateOf<ExploreRef?>(null)}
    var titles by remember {mutableStateOf<List<Meta>>(emptyList())}
    var message by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(attempt,selected) {
        if(attempt==0)return@LaunchedEffect
        // The chosen row is about to disappear: park on a mounted control even if the request fails.
        if(selected!=null)runCatching {search.requestFocus()}
        loading=true;message=null;titles=emptyList();choices=emptyList()
        try {
            if(!repo.configured) {message="Filmography search is unavailable. Add a TMDB API key in Settings.";return@LaunchedEffect}
            val known=selected ?: person.tmdbId?.takeIf {it>0}?.let {ExploreRef(it,person.name,person.photo)}
            val candidates=if(known!=null)listOf(known) else repo.search(person.name,false)
            val identity=known ?: ExploreRepository.personIdentity(person,candidates)
            if(identity==null) {
                choices=candidates
                message=if(candidates.isEmpty())"No matching person found." else "Choose the person to search."
            } else {
                titles=otherPersonTitles(current,filterTitles(repo.filmography(identity,person.isCrew)))
                if(titles.isEmpty())message="No other titles available for this profile."
            }
            state.scrollToItem(0)
            // A chosen person disappears with the list it was picked from; keep remote focus in the dialog.
            if(selected!=null)requestSettingsFocus(
                {if(titles.isNotEmpty())results.requestFocus() else search.requestFocus()},
                {withFrameNanos { }},{runCatching {search.requestFocus()}})
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {message="Could not load filmography. Select Search to retry."}
        finally {loading=false}
    }
    MediaDialog(person.name,close,initialFocus=search,scrollBody=false) {
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Column(Modifier.width(154.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                person.photo?.let {ArtworkImage(it,person.name,Modifier.width(112.dp).height(168.dp))}
                Text(person.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text(person.role ?: if(person.isCrew)"Crew" else "Cast",color=Mute,fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                Chip(if(loading)"Searching…" else "Search",modifier=Modifier.focusRequester(search).focusProperties {if(choices.isNotEmpty() || titles.isNotEmpty()){if(rtl)left=results else right=results}}) {
                    if(!loading){selected=null;attempt++}
                }
                Text("Filmography from TMDB",fontSize=TvTokens.Type.Caption.sp,color=Mute)
            }
            Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Other movies and shows",fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                if(attempt==0)Text("Select Search to find other titles.",color=Mute,fontSize=13.sp)
                if(loading)Text("Searching…",color=Mute,fontSize=13.sp)
                message?.let {Text(it,color=Mute,fontSize=13.sp)}
                LazyColumn(state=state,modifier=Modifier.weight(1f).fillMaxWidth().focusRequester(results),
                    contentPadding=PaddingValues(4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(choices,key={"person:${it.id}"}) {choice->
                        Focusable(onClick={selected=choice},modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                choice.image?.let {ArtworkImage(it,choice.name,Modifier.width(32.dp).height(48.dp))}
                                Text(choice.name,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                    items(titles,key={it.mediaKey()}) {title->
                        Focusable(onClick={open(title)},modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.width(38.dp).height(57.dp).background(PanelHi)) {title.poster?.let {ArtworkImage(it,null,Modifier.fillMaxSize())}}
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(title.name,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(listOfNotNull(title.releaseInfo,if(title.type=="movie")"Movie" else "Show").joinToString(" · "),color=Mute,fontSize=TvTokens.Type.Caption.sp,maxLines=1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
