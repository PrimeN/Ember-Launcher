package app.ember.tv.ui

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import app.ember.tv.data.CardFocusStyle

internal val LocalCardFocusStyle=compositionLocalOf {CardFocusStyle.SUBTLE}

@Composable internal fun cardPulse(enabled:Boolean):State<Float> {
    if(!enabled)return rememberUpdatedState(0f)
    return rememberInfiniteTransition(label="cardPulse").animateFloat(.12f,.85f,
        infiniteRepeatable(tween(1400,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="focusOutline")
}
