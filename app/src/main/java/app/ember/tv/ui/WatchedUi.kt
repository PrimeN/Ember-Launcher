package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ember.tv.data.Episode
import app.ember.tv.data.Meta

internal data class WatchedUi(val showBadges:Boolean=false,val blurEpisodes:Boolean=false,
    val title:(Meta)->Boolean={false},val episode:(Meta,Episode)->Boolean={_,_->false},val ready:Boolean=true) {
    /** Cards ask this on every draw; with badges off nothing is looked up. */
    fun badge(meta:Meta)=showBadges && title(meta)
}
internal val LocalWatchedUi=compositionLocalOf {WatchedUi()}
/** #432: Ember's corner tags on posters; empty when they're off. */
internal val LocalRibbonKinds=compositionLocalOf {emptySet<app.ember.tv.data.PosterRibbons.Kind>()}
/** #426: watched titles in this row are dimmed (Home's catalog and widget rows with "Dim"). */
internal val LocalDimWatched=compositionLocalOf {false}

/** #477: a small round mark tucked into a card's corner: an icon, no words, so it hides little art. */
@Composable private fun CornerMark(icon:androidx.compose.ui.graphics.vector.ImageVector,fill:Color,ink:Color,label:String) {
    Box(Modifier.size(18.dp).background(fill,CircleShape).semantics {contentDescription=label},contentAlignment=Alignment.Center) {
        MaterialGlyph(icon,ink,12.dp)
    }
}

@Composable internal fun WatchedBadge(watched:Boolean,modifier:Modifier=Modifier) {
    if(!watched || !LocalWatchedUi.current.showBadges)return
    Box(modifier.padding(5.dp)) {CornerMark(Icons.Outlined.Check,Color(0xE6252B32),Color.White,"Watched")}
}

/** #477: watched and saved as two small icons side by side in the top-left corner. */
@Composable internal fun CornerMarks(watched:Boolean,saved:Boolean,modifier:Modifier=Modifier) {
    val showWatched=watched && LocalWatchedUi.current.showBadges
    if(!showWatched && !saved)return
    Row(modifier.padding(5.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        if(showWatched)CornerMark(Icons.Outlined.Check,Color(0xE6252B32),Color.White,"Watched")
        if(saved)CornerMark(Icons.Filled.Bookmark,androidx.tv.material3.MaterialTheme.colorScheme.primary,
            androidx.tv.material3.MaterialTheme.colorScheme.onPrimary,"Saved")
    }
}
