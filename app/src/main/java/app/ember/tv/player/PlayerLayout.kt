package app.ember.tv.player

import app.ember.tv.ui.TvTokens

import androidx.compose.ui.graphics.toArgb
import app.ember.tv.iptv.*
import app.ember.tv.R
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// PlayerActivity's native view layout and remote-friendly button styling.
// ---- layout ----
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.buildLayout(): View {
    val root = FrameLayout(this)
    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    if (live) root.addView(androidx.compose.ui.platform.ComposeView(this).apply {
        setContent { app.ember.tv.ui.EmberTheme(prefs.accent,prefs.uiStyle) { app.ember.tv.ui.LivePlaybackBanner(liveBanner.value) } }
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    if (advanced) {
        view.useController = false
        root.addView(
            androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    val render=app.ember.tv.ui.rememberDeviceRenderOptions(prefs.contentDensity,prefs.navigationGlass,prefs.artworkQuality,prefs.devicePerformance,prefs.customPerformance)
                        .copy(glassMethod=prefs.navigationGlassMethod)
                    val reduced=app.ember.tv.ui.rememberReduceMotion()
                    androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalNight provides true,
                        app.ember.tv.ui.LocalRenderOptions provides render,app.ember.tv.ui.LocalReduceMotion provides reduced) {
                        app.ember.tv.ui.EmberTheme(prefs.accent,playerLayoutStyle) {AdvancedControls(controls, controlActions)}
                    }
                }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    upNextTitle = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Color.WHITE)
    }
    upNextCount = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(0xFFB7C0C9.toInt())
        setPadding(0, dp(6), 0, dp(12))
    }
    playNowButton = styled(Button(this)).apply {
        text = getString(R.string.pl_play_now)
        setOnClickListener { playNext() }
    }
    val cancel = styled(Button(this)).apply {
        text = getString(R.string.ui_cancel)
        setOnClickListener { finish() }
    }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(playNowButton)
        addView(cancel)
    }
    upNext = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        setBackgroundColor(0xE6141820.toInt())
        visibility = View.GONE
        addView(upNextTitle)
        addView(upNextCount)
        addView(buttons)
    }
    info = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 1.15f)
    }
    subStatus = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFFB7C0C9.toInt())
    }
    subList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    timingLabel = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        setPadding(0, dp(10), 0, dp(4))
    }
    val timing = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(getString(R.string.pl_0_5_s) to -500L, "−0.1 s" to -100L, "+0.1 s" to 100L, "+0.5 s" to 500L, getString(R.string.pl_reset) to null).forEach { (label, d) ->
            addView(styled(Button(this@buildLayout)).apply {
                text = label
                isAllCaps = false
                setOnClickListener { shiftSubtitles(d) }
            })
        }
    }
    soundLabel = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        setPadding(0, dp(14), 0, dp(4))
    }
    val sound = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf("−100" to -100, "−20" to -20, "+20" to 20, "+100" to 100, getString(R.string.pl_reset) to null).forEach { (label, d) ->
            addView(styled(Button(this@buildLayout)).apply {
                text = if (d == null) label else "$label ms"
                isAllCaps = false
                setOnClickListener { shiftSound(d) }
            })
        }
        addView(styled(Button(this@buildLayout)).apply {
            text = getString(R.string.pl_keep_for_all)
            isAllCaps = false
            setOnClickListener { keepSound() }
        })
    }
    val header = TextView(this).apply {
        text = getString(R.string.settings_subtitles)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Color.WHITE)
        setPadding(0, dp(14), 0, dp(4))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(info)
        addView(header)
        addView(subStatus)
        addView(subList)
        addView(timingLabel)
        addView(android.widget.HorizontalScrollView(this@buildLayout).apply {addView(timing)})
        addView(soundLabel)
        addView(android.widget.HorizontalScrollView(this@buildLayout).apply {addView(sound)})
        addView(TextView(this@buildLayout).apply {
            text = getString(R.string.pl_voices_come_before_the_lips_move)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF8E99A4.toInt())
        })
        addView(TextView(this@buildLayout).apply {
            text = getString(R.string.pl_panel_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF8E99A4.toInt())
            setPadding(0, dp(8), 0, 0)
        })
    }
    panel = android.widget.ScrollView(this).apply {
        setBackgroundColor(0xE6141820.toInt())
        visibility = View.GONE
        addView(content)
    }
    root.addView(
        panel,
        FrameLayout.LayoutParams(dp(360), (resources.displayMetrics.heightPixels*.72f).toInt(), Gravity.CENTER_VERTICAL or Gravity.START)
            .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) },
    )
    skipButton = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(0xFF1F1F1F.toInt())
        minHeight=dp(32);minimumHeight=dp(32);minWidth=dp(48);textSize=TvTokens.Type.NativeButton
    setPadding(dp(10), dp(5), dp(10), dp(5))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xF2F1F3F4.toInt())
        }
        visibility = View.GONE
        isClickable = true
        setOnClickListener { doSkip() }
    }
    root.addView(
        skipButton,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(dp(48), dp(48), dp(56), dp(110)) },
    )
    root.addView(
        upNext,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(dp(48), dp(48), dp(48), dp(48)) },
    )
    return root
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Panel and Up next buttons in Ember's style: a soft pill, filled with the accent colour when focused. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.nativeFocusFill():Int=when(prefs.accent) {
    app.ember.tv.data.ColorThemes.MODERN_EMBER->app.ember.tv.ui.ModernEmberColors.Focus.toArgb()
    app.ember.tv.data.ColorThemes.SHADOW_EMBER->app.ember.tv.ui.ShadowEmberColors.Focus.toArgb()
    else->0xFFF1F3F4.toInt()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.nativeFocusPill(radius:Int):android.graphics.drawable.Drawable {
    val fill=nativeFocusFill()
    return android.graphics.drawable.GradientDrawable().apply {
        cornerRadius=dp(if(playerLayoutStyle==app.ember.tv.data.InterfaceStyles.EMBER_X)8 else radius).toFloat();setColor(fill)
        setStroke(dp(1),app.ember.tv.ui.ControlStateCue.edge(androidx.compose.ui.graphics.Color(fill)).toArgb())
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.styleNativeIcons(node:android.view.View) {
    if(node is android.widget.ImageButton) {
        fun pill(fill:Int)=android.graphics.drawable.GradientDrawable().apply {
            cornerRadius=dp(50).toFloat();setColor(fill);setStroke(dp(1),0x29FFFFFF)
        }
        node.background=android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled,android.R.attr.state_focused),nativeFocusPill(TvTokens.Shape.Pill))
            addState(intArrayOf(android.R.attr.state_enabled,android.R.attr.state_pressed),nativeFocusPill(TvTokens.Shape.Pill))
            addState(intArrayOf(),pill(0x1AFFFFFF))
        }
        node.imageTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),
            intArrayOf(0x66FFFFFF,0xFF1F1F1F.toInt(),0xFF1F1F1F.toInt(),Color.WHITE))
        node.setPadding(dp(8),dp(8),dp(8),dp(8))
    }
    if(node is android.view.ViewGroup)for(i in 0 until node.childCount)styleNativeIcons(node.getChildAt(i))
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.styled(b: Button): Button = b.apply {
    isAllCaps = false
    // Inset, so buttons side by side keep a small gap.
    fun pill(color: Int) = android.graphics.drawable.InsetDrawable(
        android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(if(playerLayoutStyle==app.ember.tv.data.InterfaceStyles.EMBER_X)8 else TvTokens.Shape.NativePill).toFloat(); setColor(color) }, dp(4),
    )
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_enabled,android.R.attr.state_focused),android.graphics.drawable.InsetDrawable(nativeFocusPill(TvTokens.Shape.NativePill),dp(4)))
        addState(intArrayOf(android.R.attr.state_enabled,android.R.attr.state_pressed),android.graphics.drawable.InsetDrawable(nativeFocusPill(TvTokens.Shape.NativePill),dp(4)))
        addState(intArrayOf(), pill(0x26FFFFFF))
    }
    setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),intArrayOf(0x66FFFFFF,0xFF1F1F1F.toInt(),0xFF1F1F1F.toInt(),Color.WHITE)))
    stateListAnimator = null
    minHeight=dp(32);minimumHeight=dp(32);minWidth=dp(48);textSize=TvTokens.Type.NativeButton
    setPadding(dp(10), dp(5), dp(10), dp(5))
}
