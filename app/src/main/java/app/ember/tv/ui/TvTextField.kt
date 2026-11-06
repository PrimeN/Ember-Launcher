package app.ember.tv.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A text field that works with a remote.
 *
 * Moving onto it only highlights it; press OK to type (the keyboard opens). The D-pad always gets
 * out again: Up and Down move to the next control (in a multi-line box only from its first or last
 * line), Left at the start and Right at the end too, and the keyboard's Done key moves on. A plain
 * text field would keep every D-pad press, which left you stuck in it.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    minHeight: Dp = 0.dp,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    /** Done or Search on the keyboard. Moves to the next control when not set. */
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    /** Open the keyboard as soon as the field gets focus (e.g. the Search page). */
    startEditing: Boolean = false,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    var consumedBack by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val current = if (tfv.text == value) tfv else TextFieldValue(value, TextRange(value.length))

    fun leave(direction: FocusDirection): Boolean {
        editing = false
        keyboard?.hide()
        focus.moveFocus(direction)
        return true
    }

    // The on-screen keyboard covers the lower part of the screen: once it is up, scroll the page
    // so this field sits above it (the window shrinks to the space left, adjustResize).
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(350)
            runCatching { bring.bringIntoView() }
            kotlinx.coroutines.delay(400)
            runCatching { bring.bringIntoView() }
        }
    }
    Box(
        modifier = modifier
            .bringIntoViewRequester(bring)
            .background(PanelHi, RoundedCornerShape(10.dp))
            .focusCue(focused,10,ControlStateCue.edge(PanelHi))
            .padding(14.dp),
    ) {
        BasicTextField(
            value = current,
            onValueChange = {
                tfv = it
                if (it.text != value) onValueChange(it.text)
            },
            readOnly = !editing,
            singleLine = singleLine,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = fontSize, lineHeight = lineHeight),
            cursorBrush = SolidColor(if (editing) accent else androidx.compose.ui.graphics.Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) imeAction else ImeAction.Default),
            keyboardActions = KeyboardActions(onAny = {
                onSubmit?.invoke()
                if (onSubmit == null) leave(FocusDirection.Down) else {
                    editing = false
                    keyboard?.hide()
                }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) editing = false
                    if (it.isFocused && startEditing && !editing) {
                        editing = true
                        keyboard?.show()
                    }
                }
                .onPreviewKeyEvent { e ->
                    if ((e.key == Key.Back || e.key == Key.Escape) && consumedBack) {
                        if (e.type == KeyEventType.KeyUp) consumedBack = false
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (!editing) {
                        return@onPreviewKeyEvent when (e.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                editing = true
                                keyboard?.show()
                                true
                            }
                            Key.DirectionUp -> leave(FocusDirection.Up)
                            Key.DirectionDown -> leave(FocusDirection.Down)
                            Key.DirectionLeft -> leave(FocusDirection.Left)
                            Key.DirectionRight -> leave(FocusDirection.Right)
                            else -> false
                        }
                    }
                    val text = current.text
                    val cursor = current.selection.start.coerceIn(0, text.length)
                    when (e.key) {
                        Key.DirectionUp -> if (singleLine || '\n' !in text.substring(0, cursor)) leave(FocusDirection.Up) else false
                        Key.DirectionDown -> if (singleLine || '\n' !in text.substring(cursor)) leave(FocusDirection.Down) else false
                        Key.DirectionLeft -> if (cursor == 0) leave(FocusDirection.Left) else false
                        Key.DirectionRight -> if (cursor >= text.length) leave(FocusDirection.Right) else false
                        Key.Back, Key.Escape -> {
                            // Keyboard already closed: Back stops typing; the next Back leaves the screen as usual.
                            consumedBack = true
                            editing = false
                            keyboard?.hide()
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        if (focused && !editing && placeholder.isNotEmpty()) "$placeholder  (OK to type)" else placeholder,
                        color = Mute,
                        fontSize = fontSize,
                    )
                }
                inner()
            },
        )
    }
}

/**
 * Inside a Dialog (floating Settings): lets the window make room for the on-screen keyboard, so a
 * text field there can scroll above it (the dialog's content uses imePadding).
 */
@Composable
fun KeyboardResizesDialog() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(view) {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
