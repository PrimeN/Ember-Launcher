package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Place
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.ember.tv.R
import app.ember.tv.data.*
import app.ember.tv.discover.ExploreRef
import app.ember.tv.discover.ExploreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Cast & crew (#92, redesigned in #416): a full person page. Their photo, roles and facts on the
 * left with the biography; on the right Overview (best known titles), Filmography (every title,
 * filtered and sorted), Awards (from Wikidata) and Photos. When several people share the name,
 * you pick one; Ember never guesses. Back closes it and cancels the requests.
 */
@Composable
internal fun PersonDialog(person:Person,current:Meta,close:()->Unit,
    filterTitles:suspend(List<Meta>)->List<Meta>,open:(Meta)->Unit) {
    val context=LocalContext.current
    val repo=remember {ExploreRepository(File(context.cacheDir,"person-credits"))}
    val firstTab=remember {FocusRequester()}
    val firstChoice=remember {FocusRequester()}
    var attempt by remember {mutableIntStateOf(0)}
    var loading by remember {mutableStateOf(true)}
    var choices by remember {mutableStateOf<List<ExploreRef>>(emptyList())}
    var selected by remember {mutableStateOf<ExploreRef?>(null)}
    var page by remember {mutableStateOf<ExploreRepository.PersonPage?>(null)}
    var credits by remember {mutableStateOf<List<ExploreRepository.PersonCredit>>(emptyList())}
    var message by remember {mutableStateOf<String?>(null)}
    var canRetry by remember {mutableStateOf(false)}

    LaunchedEffect(attempt,selected) {
        loading=true;message=null;canRetry=false;choices=emptyList();page=null;credits=emptyList()
        try {
            if(!repo.configured) {message="Add a TMDB API key in ${SettingsTab.KEYS.path} to see their page.";return@LaunchedEffect}
            val known=selected ?: person.tmdbId?.takeIf {it>0}?.let {ExploreRef(it,person.name,person.photo)}
            val candidates=if(known!=null)listOf(known) else repo.search(person.name,false)
            val identity=known ?: ExploreRepository.personIdentity(person,candidates)
            if(identity==null) {
                choices=candidates
                if(candidates.isEmpty())message="TMDB doesn't know anyone called ${person.name}."
            } else {
                val loaded=repo.personPage(identity.id)
                // Parental and hidden-title rules apply to their titles like everywhere else; this title itself is left out.
                val allowed=otherPersonTitles(current,filterTitles(loaded.titles.map {it.meta})).map {it.mediaKey()}.toSet()
                credits=loaded.credits.filter {it.meta.mediaKey() in allowed}
                page=loaded
            }
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {message="Could not load their page. Check the connection.";canRetry=true}
        finally {loading=false}
    }
    LaunchedEffect(page,choices) {
        withFrameNanos { }
        when {
            choices.isNotEmpty() -> runCatching {firstChoice.requestFocus()}
            else -> runCatching {firstTab.requestFocus()}
        }
    }

    val titles=remember(credits) {credits.distinctBy {it.meta.type to it.meta.tmdbId}}
    val photo=person.photo ?: page?.photo ?: selected?.image
    val backdrop=remember(titles) {titles.filter {it.meta.background!=null && it.votes>=50}.maxByOrNull {kotlin.math.ln(1.0+it.votes)*it.rating}?.meta?.background
        ?.replace("/w780/","/w1280/")}

    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        DialogMotion()
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg) {
            Box(Modifier.fillMaxSize().background(Ink)) {
                // The backdrop: their best known title, fading into the page on the left and below.
                if(backdrop!=null)Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.66f).height(330.dp)) {
                    ArtworkImage(backdrop,null,Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink,Ink.copy(alpha=0.55f),Color.Transparent))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Ink.copy(alpha=0.6f),Ink))))
                }
                Column(Modifier.fillMaxSize().padding(horizontal=40.dp,vertical=22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    ToolbarIcon(stringResource(R.string.ui_close),Icons.AutoMirrored.Outlined.ArrowBack,onClick=close)
                    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                        PersonSide(person,page,photo,titles,Modifier.width(300.dp).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            when {
                                choices.isNotEmpty() -> PersonChoices(person,choices,firstChoice) {selected=it}
                                page!=null -> PersonTabs(page!!,titles,repo,firstTab,open)
                                loading -> PosterGridPlaceholder()
                            }
                            message?.let {Text(it,color=Mute,fontSize=14.sp)}
                            if(canRetry)Chip("Try again") {attempt++}
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- left side

@Composable
private fun PersonSide(person:Person,page:ExploreRepository.PersonPage?,photo:String?,titles:List<ExploreRepository.PersonCredit>,modifier:Modifier) {
    var bioOpen by remember {mutableStateOf(false)}
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(PanelHi),contentAlignment=Alignment.Center) {
                if(photo!=null)ArtworkImage(photo,person.name,Modifier.fillMaxSize())
                else Text(initials(person.name),fontSize=30.sp,fontWeight=FontWeight.SemiBold,color=Mute)
            }
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(page?.name?.takeIf {it.isNotBlank()} ?: person.name,fontSize=24.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,maxLines=3,overflow=TextOverflow.Ellipsis)
                val roles=page?.roles.orEmpty()
                if(roles.isNotEmpty())Text(roles.joinToString(" • "),color=Mute,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                person.role?.takeIf {it.isNotBlank()}?.let {Text(if(person.isCrew)it else "as $it",color=Mute,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}
            }
        }
        if(page!=null) {
            val facts=buildList {
                page.born?.let {born->
                    val age=Period.between(born,page.died ?: LocalDate.now()).years
                    add(Triple(Icons.Outlined.Cake,"Born",born.format(DateTimeFormatter.ofPattern("MMM d, yyyy",Locale.getDefault()))+
                        if(page.died==null)" ($age years)" else ""))
                }
                page.died?.let {add(Triple(Icons.Outlined.Cake,"Died",it.format(DateTimeFormatter.ofPattern("MMM d, yyyy",Locale.getDefault()))+
                    page.born?.let {b->" (aged ${Period.between(b,it).years})"}.orEmpty()))}
                page.place?.let {add(Triple(Icons.Outlined.Place,"Birthplace",it))}
                if(titles.isNotEmpty())add(Triple(Icons.Outlined.Movie,"Credits","${titles.size} titles"+(page.since?.let {" • since $it"} ?: "")))
            }
            if(facts.isNotEmpty())PersonCard {
                facts.forEach {(icon,label,value)->FactRow(icon,label,value)}
            }
            page.biography?.let {bio->
                PersonCard {
                    Text("Biography",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    Text(bio,fontSize=14.sp,lineHeight=20.sp,maxLines=if(bioOpen)Int.MAX_VALUE else 7,overflow=TextOverflow.Ellipsis)
                    if(bio.length>320)Chip(if(bioOpen)"Show less" else "Read more") {bioOpen=!bioOpen}
                }
            }
        } else Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(18.dp)).background(Panel.copy(alpha=0.7f)))
    }
}

@Composable
private fun PersonCard(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel.copy(alpha=0.82f))
        .border(1.dp,Color(0x1FFFFFFF),RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}

@Composable
private fun FactRow(icon:ImageVector,label:String,value:String) {
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,tint=Mute,modifier=Modifier.size(18.dp))
        Text(label,color=Mute,fontSize=13.sp,modifier=Modifier.width(78.dp))
        Text(value,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- tabs

private enum class PersonTab(val label:String) {OVERVIEW("Overview"),FILMOGRAPHY("Filmography"),AWARDS("Awards"),PHOTOS("Photos")}

private enum class CreditSort(val label:String) {NEWEST("Release date ↓"),OLDEST("Release date ↑"),RATING("Rating"),POPULAR("Popularity")}

@Composable
private fun PersonTabs(page:ExploreRepository.PersonPage,titles:List<ExploreRepository.PersonCredit>,repo:ExploreRepository,
    firstTab:FocusRequester,open:(Meta)->Unit) {
    val tabs=remember(page) {PersonTab.entries.filter {
        when(it) {PersonTab.AWARDS->page.wikidata!=null;PersonTab.PHOTOS->page.photos.size>1;else->true}
    }}
    var tab by rememberSaveable {mutableIntStateOf(0)}
    Box(Modifier.focusRequester(firstTab)) {Segments(tabs.map {it.label},tab) {tab=it}}
    when(tabs.getOrNull(tab)) {
        PersonTab.OVERVIEW,null -> PersonOverview(page,titles,open)
        PersonTab.FILMOGRAPHY -> PersonFilmography(titles,open)
        PersonTab.AWARDS -> PersonAwards(page,repo)
        PersonTab.PHOTOS -> PersonPhotos(page)
    }
}

@Composable
private fun PersonOverview(page:ExploreRepository.PersonPage,titles:List<ExploreRepository.PersonCredit>,open:(Meta)->Unit) {
    val known=remember(titles) {titles.filter {it.votes>=50}.sortedByDescending {kotlin.math.ln(1.0+it.votes)*it.rating+it.popularity/10}.take(12)}
    val today=LocalDate.now()
    val upcoming=remember(titles) {titles.filter {it.date==null || it.date.isAfter(today)}.sortedBy {it.date ?: LocalDate.MAX}.take(12)}
    val recent=remember(titles) {titles.filter {it.date!=null && !it.date.isAfter(today)}.sortedByDescending {it.date}.take(12)}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        if(known.isNotEmpty())item {CreditShelf("Known for",known,open)}
        if(upcoming.isNotEmpty())item {CreditShelf("Coming up",upcoming,open)}
        if(recent.isNotEmpty())item {CreditShelf("Latest",recent,open)}
        if(known.isEmpty() && upcoming.isEmpty() && recent.isEmpty())item {Text("No other movies or shows to show.",color=Mute,fontSize=14.sp)}
        item {Text(stringResource(R.string.tx_filmography_from_tmdb),fontSize=TvTokens.Type.Caption.sp,color=Mute)}
    }
}

@Composable
private fun CreditShelf(title:String,items:List<ExploreRepository.PersonCredit>,open:(Meta)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=6.dp,horizontal=2.dp)) {
            items(items,key={"${title}:${it.meta.mediaKey()}"}) {CreditCard(it,open)}
        }
    }
}

@Composable
private fun PersonFilmography(titles:List<ExploreRepository.PersonCredit>,open:(Meta)->Unit) {
    var kind by rememberSaveable {mutableIntStateOf(0)}
    var sort by rememberSaveable {mutableIntStateOf(0)}
    val shown=remember(titles,kind,sort) {
        val filtered=when(kind) {1->titles.filter {it.meta.type=="movie"};2->titles.filter {it.meta.type!="movie"};else->titles}
        when(CreditSort.entries[sort]) {
            CreditSort.NEWEST->filtered.sortedByDescending {it.date ?: LocalDate.MAX}
            CreditSort.OLDEST->filtered.sortedBy {it.date ?: LocalDate.MAX}
            CreditSort.RATING->filtered.sortedByDescending {if(it.votes>=20)it.rating else 0.0}
            CreditSort.POPULAR->filtered.sortedByDescending {it.popularity}
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Filmography",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text("${shown.size}",color=Mute,fontSize=14.sp)
        Spacer(Modifier.weight(1f))
        val movies=titles.count {it.meta.type=="movie"}
        Segments(listOf("All","Movies $movies","Shows ${titles.size-movies}"),kind) {kind=it}
        Chip(CreditSort.entries[sort].label) {sort=(sort+1)%CreditSort.entries.size}
    }
    LazyVerticalGrid(GridCells.Adaptive(CARD_W+14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(top=4.dp,bottom=24.dp,start=2.dp,end=2.dp)) {
        items(shown,key={"grid:${it.meta.mediaKey()}"}) {CreditCard(it,open)}
    }
}

private val CARD_W=128.dp
private val CARD_H=192.dp

/** A poster with its rating, then the title, year, type and role. */
@Composable
private fun CreditCard(credit:ExploreRepository.PersonCredit,open:(Meta)->Unit) {
    val meta=credit.meta
    Column(Modifier.width(CARD_W),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Focusable(onClick={open(meta)},card=true) {
            Box(Modifier.width(CARD_W).height(CARD_H).background(PanelHi),contentAlignment=Alignment.Center) {
                if(meta.poster!=null)ArtworkImage(meta.poster,meta.name,Modifier.fillMaxSize())
                else Text(meta.name,fontSize=13.sp,color=Mute,maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(8.dp))
                if(credit.votes>=20)Text("★ %.1f".format(Locale.US,credit.rating),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Color.White,
                    modifier=Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal=6.dp,vertical=2.dp))
            }
        }
        Text(meta.name,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(listOfNotNull(credit.date?.year?.toString() ?: "TBA",if(meta.type=="movie")"Movie" else "Show").joinToString(" • "),
            color=Mute,fontSize=12.sp,maxLines=1)
        credit.role?.let {Text(if(credit.crew)it else "as $it",color=Mute,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
    }
}

@Composable
private fun PersonAwards(page:ExploreRepository.PersonPage,repo:ExploreRepository) {
    val wikidata=page.wikidata ?: return
    var awards by remember(wikidata) {mutableStateOf<List<ExploreRepository.PersonAward>?>(null)}
    var failed by remember(wikidata) {mutableStateOf(false)}
    LaunchedEffect(wikidata) {
        try {awards=repo.personAwards(wikidata)} catch(e:CancellationException) {throw e} catch(_:Exception) {failed=true}
    }
    val list=awards
    when {
        failed -> Text("Award records are unavailable right now.",color=Mute,fontSize=14.sp)
        list==null -> Text("Loading awards…",color=Mute,fontSize=14.sp)
        list.isEmpty() -> Text("No awards or nominations on record.",color=Mute,fontSize=14.sp)
        else -> {
            val wins=list.count {it.won}
            Text("$wins wins • ${list.size-wins} nominations",color=Mute,fontSize=14.sp)
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                items(list) {award->
                    Focusable(onClick={},outline=false,radius=14) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(if(award.won)"Won" else "Nominated",fontSize=12.sp,fontWeight=FontWeight.SemiBold,
                                color=if(award.won)Color(0xFF1A1300) else Fg,
                                modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(if(award.won)Color(0xFFF2C14E) else PanelHi)
                                    .padding(horizontal=8.dp,vertical=3.dp).width(72.dp))
                            Column(Modifier.weight(1f)) {
                                Text(award.award,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                award.work?.let {Text(it,color=Mute,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                            }
                            award.year?.let {Text("$it",color=Mute,fontSize=14.sp)}
                        }
                    }
                }
                item {Text("Awards from Wikidata",fontSize=TvTokens.Type.Caption.sp,color=Mute)}
            }
        }
    }
}

@Composable
private fun PersonPhotos(page:ExploreRepository.PersonPage) {
    LazyVerticalGrid(GridCells.Adaptive(CARD_W+14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(top=4.dp,bottom=24.dp,start=2.dp,end=2.dp)) {
        items(page.photos,key={it}) {url->
            Focusable(onClick={},card=true) {
                Box(Modifier.width(CARD_W).height(CARD_H).background(PanelHi)) {ArtworkImage(url,page.name,Modifier.fillMaxSize())}
            }
        }
    }
}

// ---------------------------------------------------------------- loading and choosing

@Composable
private fun PosterGridPlaceholder() {
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        repeat(4) {Box(Modifier.width(110.dp).height(36.dp).clip(RoundedCornerShape(50)).background(PanelHi))}
    }
    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.padding(top=8.dp)) {
        repeat(5) {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(CARD_W).height(CARD_H).clip(RoundedCornerShape(TvTokens.Shape.Card.dp)).background(PanelHi))
                Box(Modifier.width(CARD_W*0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(PanelHi))
            }
        }
    }
}

@Composable
private fun PersonChoices(person:Person,choices:List<ExploreRef>,first:FocusRequester,pick:(ExploreRef)->Unit) {
    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Which ${person.name}?",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text("Several people share this name",color=Mute,fontSize=TvTokens.Type.Caption.sp)
    }
    LazyRow(horizontalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=6.dp)) {
        items(choices,key={"person:${it.id}"}) {choice->
            Column(Modifier.width(CARD_W),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Focusable(onClick={pick(choice)},modifier=if(choice==choices.first())Modifier.focusRequester(first) else Modifier,card=true) {
                    Box(Modifier.width(CARD_W).height(CARD_H).background(PanelHi),contentAlignment=Alignment.Center) {
                        if(choice.image!=null)ArtworkImage(choice.image,choice.name,Modifier.fillMaxSize())
                        else Text(initials(choice.name),fontSize=28.sp,color=Mute)
                    }
                }
                Text(choice.name,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

private fun initials(name:String)=name.split(' ').mapNotNull {it.firstOrNull()?.uppercaseChar()}.take(2).joinToString("")
