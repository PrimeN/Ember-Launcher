package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.iptv.*
import coil.compose.AsyncImage

/** A borderless metadata hero above the guide; focus never starts a stream. */
@Composable
internal fun LiveProgrammePanel(channel:Channel?,programme:Programme?,clock:Long,source:String,
    guideMessage:String,compact:Boolean=false,browsingTime:Long=clock) {
    val scale=androidx.compose.ui.platform.LocalDensity.current.fontScale
    val large=scale>1.25f
    val height=if(large)(100+(scale-1)*72).dp else if(compact)108.dp else 140.dp
    Column(Modifier.fillMaxWidth().heightIn(min=height).padding(start=4.dp,end=4.dp,bottom=10.dp),
        verticalArrangement=Arrangement.spacedBy(if(compact)4.dp else 7.dp)) {
        if(channel==null) {
            Text("Your channel guide",color=LiveColors.Text,fontSize=24.sp,modifier=Modifier.semantics {heading()})
            Text("Choose a channel to see its current programme and schedule.",color=LiveColors.Muted,fontSize=13.sp)
        } else {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                channel.logo?.let {AsyncImage(it,null,Modifier.size(26.dp))}
                Text(listOf(source,channel.name).filter(String::isNotBlank).distinct().joinToString("  |  "),color=LiveColors.Muted,fontSize=12.sp,
                    maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            }
            Text(programme?.title ?: channel.name,color=LiveColors.Text,fontSize=if(compact)23.sp else 30.sp,fontWeight=FontWeight.Medium,
                maxLines=1,lineHeight=if(compact)28.sp else 36.sp,overflow=TextOverflow.Ellipsis,modifier=Modifier.semantics {heading()})
            val state=when {programme==null->if(browsingTime>clock+60_000)"No scheduled programme" else "Live channel";clock>=programme.start && clock<programme.stop->"On now";programme.start>clock->"Upcoming";else->"Earlier"}
            Text(listOfNotNull(programme?.let {timeOf(it.start)+"–"+timeOf(it.stop)},programme?.rating,
                programme?.season?.let {s->"S$s"+(programme.episode?.let {" E$it"} ?: "")},programme?.subtitle,
                LivePresentation.remainingMinutes(programme,clock)?.let {"$it min left"},state).joinToString(" · "),
                color=LiveColors.Muted,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!large)LivePresentation.summary(programme,guideMessage)?.let {description ->
                Text(description,color=LiveColors.Muted,fontSize=13.sp,lineHeight=18.sp,maxLines=if(compact)1 else 2,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}
