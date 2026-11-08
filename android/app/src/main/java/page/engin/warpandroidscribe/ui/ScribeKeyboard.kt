package com.engin.warpandroidscribe.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.engin.warpandroidscribe.ClipKind
import com.engin.warpandroidscribe.ClipboardEntry
import com.engin.warpandroidscribe.ClipboardView
import com.engin.warpandroidscribe.KeyboardMode
import com.engin.warpandroidscribe.Phase
import com.engin.warpandroidscribe.R
import com.engin.warpandroidscribe.ScribeActions
import com.engin.warpandroidscribe.ScribeUiState
import com.engin.warpandroidscribe.SUPPORTED_LANGUAGES
import com.engin.warpandroidscribe.languageShort

@Composable
fun ScribeKeyboard(state: ScribeUiState, actions: ScribeActions) {
    val density = LocalDensity.current
    val systemInset = with(density) { state.systemBarInsetPx.toDp() }
    // The system keyboard bar (switch/hide) is drawn over the bottom of the
    // IME window. Keep the whole keyboard, not just the last key row, above
    // it; when the window already ends above the bar this collapses to the
    // small design gap.
    val bottomPadding = if (systemInset > 0.dp) systemInset + 4.dp else 20.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScribeColors.Background)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = bottomPadding),
    ) {
        if (state.clipboardView == ClipboardView.FULL) {
            ClipboardFullView(state, actions)
        } else {
            TopBar(state, actions)
            when {
                state.showLanguages -> LanguagePanel(state, actions)
                state.clipboardView == ClipboardView.QUICK -> ClipboardPanel(state, actions)
                else -> {
                    state.error?.let { ErrorLine(state, actions) }
                    if (state.layout == KeyboardMode.COMPACT) {
                        CompactBody(state, actions)
                    } else {
                        KeyboardBody(state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: ScribeUiState, actions: ScribeActions) {
    val keysEnabled = state.phase == Phase.IDLE || state.phase == Phase.ERROR
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (state.phase == Phase.RECORDING || state.phase == Phase.TRANSCRIBING) {
            KeyBox(
                modifier = Modifier.width(42.dp).fillMaxHeight(),
                shape = RoundedCornerShape(21.dp),
                onClick = actions.onCancel,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Cancel",
                    tint = ScribeColors.Error,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            LayoutButton(state.layout, actions.onToggleLayout, actions.onSettings)
        }
        KeyBox(
            modifier = Modifier.width(46.dp).fillMaxHeight(),
            shape = RoundedCornerShape(21.dp),
            onClick = actions.onToggleLanguage,
        ) {
            Text(
                text = languageShort(state.language),
                color = ScribeColors.Text,
                fontSize = if (state.language == "auto") 10.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
        }
        DictatePill(state, actions, Modifier.weight(1f).fillMaxHeight())
        KeyBox(
            modifier = Modifier.width(44.dp).fillMaxHeight(),
            shape = RoundedCornerShape(21.dp),
            onClick = actions.onToggleClipboard,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clipboard),
                contentDescription = "Clipboard",
                tint = if (state.clipboardView != ClipboardView.HIDDEN) {
                    ScribeColors.Green
                } else {
                    ScribeColors.Text
                },
                modifier = Modifier.size(20.dp),
            )
        }
        RepeatKey(
            modifier = Modifier.width(46.dp).fillMaxHeight(),
            enabled = keysEnabled,
            label = "Delete",
            shape = RoundedCornerShape(21.dp),
            onRepeat = actions.onBackspace,
        ) {
            Text("⌫", color = ScribeColors.Text, fontSize = 18.sp)
        }
    }
}

@Composable
private fun LayoutButton(
    layout: KeyboardMode,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    val full = layout == KeyboardMode.FULL
    Box(
        modifier = Modifier
            .width(42.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(21.dp))
            .background(ScribeColors.Key)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (full) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard,
            ),
            contentDescription = if (full) "Back to compact" else "Open full keyboard",
            tint = ScribeColors.Text,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LanguagePanel(state: ScribeUiState, actions: ScribeActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(top = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LANGUAGE",
                color = ScribeColors.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            SmallAction("Close", actions.onToggleLanguage, ScribeColors.Text)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SUPPORTED_LANGUAGES.chunked(2).forEach { rowLanguages ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowLanguages.forEach { language ->
                        LanguageButton(
                            name = language.name,
                            selected = state.language == language.code,
                            modifier = Modifier.weight(1f),
                            onClick = { actions.onSelectLanguage(language.code) },
                        )
                    }
                    if (rowLanguages.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageButton(
    name: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ScribeColors.Green else ScribeColors.Key)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            color = if (selected) ScribeColors.Background else ScribeColors.Text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun DictatePill(state: ScribeUiState, actions: ScribeActions, modifier: Modifier) {
    val recording = state.phase == Phase.RECORDING
    val processing = state.phase == Phase.TRANSCRIBING
    val label = when {
        processing -> "Processing…"
        recording && state.locked -> "Stop"
        recording -> "Release ${formatElapsed(state.elapsedSecs)}"
        else -> "Dictate"
    }
    val brush = when {
        recording -> Brush.horizontalGradient(
            listOf(ScribeColors.Recording, ScribeColors.RecordingDeep),
        )
        processing -> Brush.horizontalGradient(
            listOf(ScribeColors.Surface, ScribeColors.Key),
        )
        else -> Brush.horizontalGradient(
            listOf(ScribeColors.Green, ScribeColors.GreenDeep),
        )
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(21.dp))
            .background(brush)
            .then(
                if (processing) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                actions.onDictateDown()
                                tryAwaitRelease()
                                actions.onDictateUp()
                            },
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (processing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = ScribeColors.Text,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    tint = if (recording) ScribeColors.Text else ScribeColors.Background,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = if (recording) ScribeColors.Text else ScribeColors.Background,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ErrorLine(state: ScribeUiState, actions: ScribeActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ScribeColors.ErrorBg)
            .padding(start = 9.dp, end = 3.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = ScribeColors.Error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = state.error.orEmpty(),
            color = ScribeColors.Error,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.canRetry) {
            SmallAction("Retry", actions.onRetry, ScribeColors.Text)
        }
        SmallAction("Settings", actions.onSettings, ScribeColors.Muted)
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = actions.onDismissError),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Close",
                tint = ScribeColors.Muted,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CompactBody(state: ScribeUiState, actions: ScribeActions) {
    val enabled = state.phase == Phase.IDLE || state.phase == Phase.ERROR
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.shortcuts.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for (shortcut in state.shortcuts) {
                    ShortcutChip(shortcut, enabled) {
                        actions.onText(
                            if (shortcut.endsWith(" ")) shortcut else "$shortcut ",
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RepeatKey(
                modifier = Modifier.width(44.dp).fillMaxHeight(),
                enabled = enabled,
                label = "Move cursor left",
                onRepeat = { actions.onMoveCursor(-1) },
            ) {
                Text("←", color = ScribeColors.Text, fontSize = 18.sp)
            }
            RepeatKey(
                modifier = Modifier.width(44.dp).fillMaxHeight(),
                enabled = enabled,
                label = "Move cursor right",
                onRepeat = { actions.onMoveCursor(1) },
            ) {
                Text("→", color = ScribeColors.Text, fontSize = 18.sp)
            }
            Key(
                "space",
                Modifier.weight(1f).fillMaxHeight(),
                enabled,
                fontSize = 11.sp,
                textColor = ScribeColors.Muted,
            ) { actions.onText(" ") }
            KeyBox(
                modifier = Modifier.width(54.dp).fillMaxHeight(),
                enabled = enabled,
                background = ScribeColors.Blue,
                onClick = actions.onEnter,
            ) {
                Text("↵", color = ScribeColors.Background, fontSize = 20.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorIcon(R.drawable.ic_undo, "Undo", enabled, Modifier.weight(1f)) {
                actions.onUndo()
            }
            EditorIcon(R.drawable.ic_cut, "Cut", enabled, Modifier.weight(1f)) {
                actions.onCut()
            }
            EditorIcon(R.drawable.ic_copy, "Copy", enabled, Modifier.weight(1f)) {
                actions.onCopy()
            }
            EditorIcon(R.drawable.ic_paste, "Paste", enabled, Modifier.weight(1f)) {
                actions.onPaste()
            }
        }
        ClipboardStrip(state, actions)
    }
}

@Composable
private fun EditorIcon(
    icon: Int,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(ScribeColors.Key)
            .combinedClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = ScribeColors.Text,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun ClipboardStrip(state: ScribeUiState, actions: ScribeActions) {
    val entries = state.clipboard
    if (entries.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (entry in entries) {
            key(entry.id) {
                when (entry.kind) {
                ClipKind.IMAGE -> {
                    val bitmap = remember(entry.file) { loadThumbnail(entry.file) }
                    Box(
                        modifier = Modifier
                            .width(66.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ScribeColors.Key)
                            .combinedClickable { actions.onInsertEntry(entry) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Clipboard image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("IMAGE", color = ScribeColors.Muted, fontSize = 8.sp)
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .widthIn(min = 96.dp, max = 180.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ScribeColors.Key)
                            .combinedClickable { actions.onInsertEntry(entry) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = entry.text.replace('\n', ' '),
                            color = ScribeColors.Text,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(ScribeColors.Key)
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ScribeColors.Text,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactChip(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    fontSize: TextUnit = 10.sp,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(ScribeColors.Key)
            .combinedClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ScribeColors.Text,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun KeyboardBody(state: ScribeUiState, actions: ScribeActions) {
    var shiftOn by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val enabled = state.phase == Phase.IDLE || state.phase == Phase.ERROR

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (symbols) {
            SymbolRows(actions, enabled, onAbc = { symbols = false })
        } else {
            Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (key in "1234567890") {
                    Key(key.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                        actions.onText(key.toString())
                    }
                }
            }
            LetterRow("qwertyuiop", actions, enabled) { ch ->
                actions.onText(letter(ch, shiftOn) { shiftOn = false })
            }
            LetterRow("asdfghjkl", actions, enabled) { ch ->
                actions.onText(letter(ch, shiftOn) { shiftOn = false })
            }
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyBox(
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    enabled = enabled,
                    background = if (shiftOn) ScribeColors.Green else ScribeColors.Key,
                    onClick = { shiftOn = !shiftOn },
                ) {
                    Text(
                        text = "⇧",
                        color = if (shiftOn) ScribeColors.Background else ScribeColors.Text,
                        fontSize = 19.sp,
                    )
                }
                for (key in "zxcvbnm") {
                    Key(key.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                        actions.onText(letter(key, shiftOn) { shiftOn = false })
                    }
                }
                RepeatKey(
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    enabled = enabled,
                    label = "Delete",
                    onRepeat = actions.onBackspace,
                ) {
                    Text("⌫", color = ScribeColors.Text, fontSize = 18.sp)
                }
            }
            BottomRow(
                enabled = enabled,
                onMode = { symbols = true },
                onSpace = { actions.onText(" ") },
                onEnter = actions.onEnter,
                onText = actions.onText,
            )
        }
    }
}

@Composable
private fun LetterRow(
    letters: String,
    actions: ScribeActions,
    enabled: Boolean,
    onLetter: (Char) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (ch in letters) {
            Key(ch.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                onLetter(ch)
            }
        }
    }
}

@Composable
private fun BottomRow(
    enabled: Boolean,
    onMode: () -> Unit,
    onSpace: () -> Unit,
    onEnter: () -> Unit,
    onText: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            onClick = onMode,
        ) {
            Text("?123", color = ScribeColors.Text, fontSize = 12.sp)
        }
        Key(",", Modifier.width(34.dp).fillMaxHeight(), enabled) { onText(",") }
        Key(
            "space",
            Modifier.weight(1f).fillMaxHeight(),
            enabled,
            fontSize = 11.sp,
            textColor = ScribeColors.Muted,
        ) { onSpace() }
        Key(".", Modifier.width(34.dp).fillMaxHeight(), enabled) { onText(".") }
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            background = ScribeColors.Blue,
            onClick = onEnter,
        ) {
            Text("↵", color = ScribeColors.Background, fontSize = 20.sp)
        }
    }
}

@Composable
private fun SymbolRows(actions: ScribeActions, enabled: Boolean, onAbc: () -> Unit) {
    val rows = listOf(
        listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
        listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":"),
        listOf("'", "\"", "\\", "|", ",", ".", "/", "?", "<", ">"),
    )
    for (row in rows) {
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (key in row) {
                Key(key, Modifier.weight(1f).fillMaxHeight(), enabled, fontSize = 16.sp) {
                    actions.onText(key)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            onClick = onAbc,
        ) {
            Text("ABC", color = ScribeColors.Text, fontSize = 12.sp)
        }
        Key(
            "space",
            Modifier.weight(1f).fillMaxHeight(),
            enabled,
            fontSize = 11.sp,
            textColor = ScribeColors.Muted,
        ) { actions.onText(" ") }
        RepeatKey(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            label = "Delete",
            onRepeat = actions.onBackspace,
        ) {
            Text("⌫", color = ScribeColors.Text, fontSize = 18.sp)
        }
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            background = ScribeColors.Blue,
            onClick = actions.onEnter,
        ) {
            Text("↵", color = ScribeColors.Background, fontSize = 20.sp)
        }
    }
}

@Composable
private fun ClipboardPanel(state: ScribeUiState, actions: ScribeActions) {
    val entries = state.clipboard
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .padding(top = 6.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CLIPBOARD",
                color = ScribeColors.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = actions.onExpandClipboard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand),
                    contentDescription = "Open full clipboard",
                    tint = ScribeColors.Text,
                    modifier = Modifier.size(16.dp),
                )
            }
            SmallAction("System keyboard", actions.onSwitchKeyboard, ScribeColors.Text)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = actions.onCloseClipboard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = ScribeColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (entries.isEmpty()) {
            Text(
                "Clipboard is empty. Copy some text or an image.",
                color = ScribeColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entries.chunked(2).forEach { rowEntries ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowEntries.forEach { entry ->
                            ClipCard(entry, actions.onInsertEntry, Modifier.weight(1f))
                        }
                        if (rowEntries.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    entry: ClipboardEntry,
    onClick: (ClipboardEntry) -> Unit,
    modifier: Modifier = Modifier,
    showMeta: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ScribeColors.Key)
            .combinedClickable(
                onClick = { onClick(entry) },
                onLongClick = onLongClick,
            ),
    ) {
        when (entry.kind) {
            ClipKind.IMAGE -> {
                val bitmap = remember(entry.file) { loadThumbnail(entry.file) }
                if (bitmap == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("IMAGE", color = ScribeColors.Muted, fontSize = 9.sp)
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Clipboard image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ClipKind.TEXT, ClipKind.TRANSCRIPT -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.label.ifBlank { "CLIPBOARD" },
                            color = ScribeColors.Muted,
                            fontSize = 8.sp,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (showMeta) {
                            relativeTime(entry.createdAt)?.let { age ->
                                Text(
                                    text = " · $age",
                                    color = ScribeColors.Muted,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.text.replace('\n', ' '),
                        color = ScribeColors.Text,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showMeta && entry.kind == ClipKind.IMAGE) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.label.ifBlank { "IMAGE" },
                    color = Color.White,
                    fontSize = 7.sp,
                )
                relativeTime(entry.createdAt)?.let { age ->
                    Text(
                        text = " · $age",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 7.sp,
                    )
                }
            }
        }
    }
}

private enum class ClipFilter(val label: String) {
    ALL("ALL"),
    TEXT("TEXT"),
    IMAGE("IMAGES"),
    VOICE("RECENT"),
}

@Composable
private fun ClipboardFullView(state: ScribeUiState, actions: ScribeActions) {
    var filter by remember { mutableStateOf(ClipFilter.ALL) }
    var selected by remember { mutableStateOf<ClipboardEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val entries = state.clipboard.filter { entry ->
        when (filter) {
            ClipFilter.ALL -> true
            ClipFilter.TEXT -> entry.kind == ClipKind.TEXT
            ClipFilter.IMAGE -> entry.kind == ClipKind.IMAGE
            ClipFilter.VOICE -> entry.kind == ClipKind.TRANSCRIPT
        }
    }

    LaunchedEffect(state.clipboard) {
        val current = selected ?: return@LaunchedEffect
        if (state.clipboard.none { it.id == current.id }) selected = null
    }

    Column(Modifier.fillMaxWidth().height(420.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = actions.onCloseClipboard),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", color = ScribeColors.Text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "CLIPBOARD",
                color = ScribeColors.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = actions.onCloseClipboard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = ScribeColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (value in ClipFilter.values()) {
                FilterButton(value, filter == value) { filter = value }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (entries.isEmpty()) {
                Text(
                    "Clipboard is empty. Copy some text or an image.",
                    color = ScribeColors.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.chunked(2).forEach { rowEntries ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowEntries.forEach { entry ->
                                ClipCard(
                                    entry = entry,
                                    onClick = actions.onInsertEntry,
                                    modifier = Modifier.weight(1f),
                                    showMeta = true,
                                    onLongClick = { selected = entry },
                                )
                            }
                            if (rowEntries.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        selected?.let { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactChip(
                    label = "Paste",
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    onClick = {
                        selected = null
                        actions.onInsertEntry(entry)
                    },
                )
                if (entry.kind != ClipKind.IMAGE) {
                    CompactChip(
                        label = "Copy",
                        modifier = Modifier.weight(1f),
                        enabled = true,
                        onClick = {
                            actions.onCopyEntry(entry)
                            selected = null
                        },
                    )
                }
                CompactChip(
                    label = "Delete",
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    onClick = {
                        actions.onDeleteEntry(entry)
                        selected = null
                        confirmClear = false
                    },
                )
                CompactChip(
                    label = "Cancel",
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    onClick = { selected = null },
                )
            }
        }
        if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallAction(
                    label = if (confirmClear) "Are you sure?" else "Clear all",
                    onClick = {
                        if (confirmClear) {
                            actions.onClearClips()
                            confirmClear = false
                        } else {
                            confirmClear = true
                        }
                    },
                    color = if (confirmClear) ScribeColors.Error else ScribeColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun FilterButton(
    filter: ClipFilter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) ScribeColors.Green else ScribeColors.Key)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = filter.label,
            color = if (selected) ScribeColors.Background else ScribeColors.Muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

private fun relativeTime(createdAt: Long): String? {
    if (createdAt <= 0L) return null
    val diff = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        else -> "${diff / 86_400_000L}d"
    }
}

@Composable
private fun RepeatKey(
    modifier: Modifier,
    enabled: Boolean = true,
    label: String,
    shape: Shape = RoundedCornerShape(8.dp),
    onRepeat: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(ScribeColors.Key)
            .semantics {
                onClick(label = label) {
                    if (enabled) {
                        onRepeat()
                        true
                    } else {
                        false
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onRepeat()
                        coroutineScope {
                            val repeatJob = launch {
                                delay(350)
                                while (isActive) {
                                    onRepeat()
                                    delay(60)
                                }
                            }
                            tryAwaitRelease()
                            repeatJob.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun Key(
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 17.sp,
    textColor: Color = ScribeColors.Text,
    onClick: () -> Unit,
) {
    KeyBox(modifier, enabled = enabled, onClick = onClick) {
        Text(label, color = textColor, fontSize = fontSize, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyBox(
    modifier: Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    background: Color = ScribeColors.Key,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .combinedClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private fun letter(ch: Char, shiftOn: Boolean, resetShift: () -> Unit): String {
    resetShift()
    return if (shiftOn) upper(ch) else ch.toString()
}

private fun upper(ch: Char): String = ch.uppercaseChar().toString()

private fun formatElapsed(seconds: Int): String =
    String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

private fun loadThumbnail(file: File?): Bitmap? {
    if (file == null || !file.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}
