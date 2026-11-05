package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
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

@Composable internal fun WatchedBadge(watched:Boolean,modifier:Modifier=Modifier) {
    if(!watched || !LocalWatchedUi.current.showBadges)return
    Box(modifier.padding(6.dp).size(22.dp).background(Color(0xE6252B32),CircleShape)
        .semantics {contentDescription="Watched"},contentAlignment=Alignment.Center) {
        MaterialGlyph(Icons.Outlined.Check,Color.White,16.dp)
    }
}
