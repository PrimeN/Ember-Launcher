package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.iptv.*
import kotlinx.coroutines.*

@Composable
internal fun ProgrammeSearchDialog(channels: List<Channel>, guide: Map<String,List<Programme>>, now: Long,
                                   close: () -> Unit, select: (Channel,Programme) -> Unit) {
    var query by remember {mutableStateOf("")}
    var result by remember {mutableStateOf(ProgrammeSearch.Results(emptyList(),false))}
    var searching by remember {mutableStateOf(false)}
    LaunchedEffect(query,channels,guide,now) {
        result=ProgrammeSearch.Results(emptyList(),false)
        searching=query.trim().length>=2
        if(!searching)return@LaunchedEffect
        delay(300)
        val job=currentCoroutineContext()
        result=withContext(Dispatchers.Default) {ProgrammeSearch.find(query,channels,guide,now,checkCancelled={job.ensureActive()})}
        searching=false
    }
    MediaDialog("Find a programme",close,resizeForKeyboard=true,scrollBody=false) {
        TextInput(query,{query=it.take(120)},"Programme or episode title",width=500)
        Text(when {
            searching->"Searching guide…"
            query.trim().length<2->"Search now and the next seven days in visible channels."
            result.hits.isEmpty() && result.limited->"No matches in the guide entries checked. This large guide reached the search limit."
            result.hits.isEmpty()->"No matching programmes in the available guide."
            result.limited->"Showing the first ${result.hits.size} matches. Refine your search for more."
            else->"${result.hits.size} matches"
        },color=Mute,fontSize=TvTokens.Type.Compact.sp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=300.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            itemsIndexed(result.hits,key={index,hit->"$index:${hit.channel.key}:${hit.programme.start}"}) {_,hit->
                Chip(hit.programme.title+" · "+hit.channel.name+" · "+
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(hit.programme.start))) {
                    select(hit.channel,hit.programme)
                }
            }
        }
    }
}
