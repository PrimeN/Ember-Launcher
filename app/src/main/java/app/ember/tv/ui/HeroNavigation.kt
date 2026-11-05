package app.ember.tv.ui

import androidx.compose.ui.input.key.Key

/** The first/last action moves with layout direction; only the outer edge pages the carousel. */
internal fun heroEdgeKey(previous:Boolean,rtl:Boolean):Key =
    if(previous!=rtl)Key.DirectionLeft else Key.DirectionRight
