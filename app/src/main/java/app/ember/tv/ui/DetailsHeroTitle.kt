package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey

/** Available title logos match the hero; missing/failed logos keep a readable full title. */
@Composable
internal fun DetailsHeroTitle(meta:Meta,largeText:Boolean,modifier:Modifier=Modifier) {
    val displayed=LocalBackdropArtwork.current.meta?.takeIf { it.mediaKey()==meta.mediaKey() } ?: meta
    HeroTitle(displayed,modifier,details=true,largeText=largeText)
}
