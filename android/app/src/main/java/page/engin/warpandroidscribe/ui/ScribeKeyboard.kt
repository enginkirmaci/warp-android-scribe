package com.engin.warpandroidscribe.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.engin.warpandroidscribe.ClipKind
import com.engin.warpandroidscribe.ClipboardEntry
import com.engin.warpandroidscribe.ClipboardView
import com.engin.warpandroidscribe.COMMON_LANGUAGE_CODES
import com.engin.warpandroidscribe.DictationLanguage
import com.engin.warpandroidscribe.EmojiGroup
import com.engin.warpandroidscribe.EmojiUse
import com.engin.warpandroidscribe.KeyboardMode
import com.engin.warpandroidscribe.MOST_USED_EMOJI_LIMIT
import com.engin.warpandroidscribe.Phase
import com.engin.warpandroidscribe.R
import com.engin.warpandroidscribe.ScribeActions
import com.engin.warpandroidscribe.ScribeUiState
import com.engin.warpandroidscribe.displayLanguages
import com.engin.warpandroidscribe.languageShort

/** Inert controls keep their shape and lose contrast instead of vanishing. */
private const val DISABLED_ALPHA = 0.6f

/** The one clip under a long-press keeps full contrast; its siblings step back. */
private const val DIMMED_ALPHA = 0.7f

private const val SHIFT_DOUBLE_TAP_MS = 300L

private val LANGUAGE_ROW_GAP = 6.dp
private val LANGUAGE_JUMP_WIDTH = 40.dp

/** Seven emoji to a row leaves room for the bigger glyph. */
private const val EMOJI_COLUMNS = 7

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
        // The error owns the first row, above the top bar: whatever replaces
        // the key area (language, emoji, clipboard) can never hide it.
        state.error?.let { ErrorLine(state, actions) }
        if (state.clipboardView == ClipboardView.FULL) {
            ClipboardFullView(state, actions)
        } else {
            // The clip bar is the keyboard's first row, above the Dictate bar:
            // the quickest insert stays the closest to the app's own content.
            ClipboardDock(state, actions)
            TopBar(state, actions)
            when {
                state.showLanguages -> LanguagePanel(state, actions)
                state.showEmoji -> EmojiPanel(
                    usage = state.emojiUsage,
                    groups = state.emojiGroups,
                    onInsert = actions.onEmoji,
                    onClose = actions.onToggleEmoji,
                )
                state.clipboardView == ClipboardView.QUICK -> ClipboardPanel(state, actions)
                else -> {
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
    Row(
        modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (state.phase == Phase.RECORDING || state.phase == Phase.TRANSCRIBING) {
            KeyBox(
                modifier = Modifier.width(ScribeSize.Key).fillMaxHeight(),
                shape = ScribeShape.Pill,
                onClick = actions.onCancel,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Cancel",
                    tint = ScribeColors.Error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // The pill owns this bar in every layout. The layout switch and delete
        // sit with the keys: the switch left of enter, delete at the right end
        // of the row above.
        DictatePill(state, actions, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun LayoutButton(
    layout: KeyboardMode,
    enabled: Boolean = true,
    shape: Shape = ScribeShape.Pill,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    val full = layout == KeyboardMode.FULL
    Box(
        modifier = Modifier
            .width(ScribeSize.Key)
            .fillMaxHeight()
            .clip(shape)
            .background(ScribeColors.Key)
            .combinedClickable(
                enabled = enabled,
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

/**
 * The product's one primary control. The trailing segment is the dictation
 * language while idle and Undo for a few seconds after a transcript, so the
 * confirmation never adds a row to the keyboard.
 */
@Composable
private fun DictatePill(state: ScribeUiState, actions: ScribeActions, modifier: Modifier) {
    val recording = state.phase == Phase.RECORDING
    val processing = state.phase == Phase.TRANSCRIBING
    val idle = !recording && !processing
    val notice = state.notice
    val canUndo = notice != null && state.noticeUndoCodePoints > 0
    val label = when {
        processing -> "Processing…"
        recording && state.locked -> "Stop"
        recording -> "Release ${formatElapsed(state.elapsedSecs)}"
        notice != null -> notice
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
    val onPill = if (recording) ScribeColors.Text else if (processing) {
        ScribeColors.Text
    } else {
        ScribeColors.Background
    }
    val segmentLabel = if (canUndo) "Undo" else languageShort(state.language)
    val segmentAction = if (canUndo) actions.onUndoInsert else actions.onToggleLanguage

    Box(modifier = modifier.clip(ScribeShape.Pill).background(brush)) {
        // The label centres on the pill itself; the language segment rides on
        // top of the left edge so the two never share the layout width.
        Box(
            modifier = Modifier
                .matchParentSize()
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
                        color = onPill,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = null,
                        tint = onPill,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = onPill,
                    fontSize = ScribeText.Label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxHeight().align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .fillMaxHeight()
                    .alpha(if (idle) 1f else DISABLED_ALPHA)
                    .combinedClickable(enabled = idle, onClick = segmentAction)
                    .semantics {
                        contentDescription = if (canUndo) "Undo insert" else "Dictation language"
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = segmentLabel,
                    color = onPill,
                    fontSize = ScribeText.Body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Box(Modifier.width(1.dp).height(20.dp).background(onPill.copy(alpha = 0.16f)))
        }
    }
}

@Composable
private fun ErrorLine(state: ScribeUiState, actions: ScribeActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp)
            .clip(ScribeShape.Surface)
            .background(ScribeColors.ErrorBg)
            .heightIn(min = ScribeSize.Key)
            .padding(start = 10.dp, end = 4.dp),
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
            fontSize = ScribeText.Body,
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
                .size(ScribeSize.Key)
                .clip(CircleShape)
                .combinedClickable(onClick = actions.onDismissError),
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
}

/** A text action with a real 48 x 44 target; the label stays at caption size. */
@Composable
private fun SmallAction(label: String, onClick: () -> Unit, color: Color) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = ScribeSize.Tap, minHeight = ScribeSize.Key)
            .clip(ScribeShape.Chip)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = ScribeText.Caption, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CompactBody(state: ScribeUiState, actions: ScribeActions) {
    val enabled = state.phase == Phase.IDLE || state.phase == Phase.ERROR
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Quick inserts scroll; delete is pinned outside the scroll area so a
        // long command never pushes it off the row.
        Row(
            Modifier.fillMaxWidth().height(ScribeSize.Chip),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (shortcut in state.shortcuts) {
                    ShortcutChip(shortcut, enabled) {
                        actions.onText(
                            if (shortcut.endsWith(" ")) shortcut else "$shortcut ",
                        )
                    }
                }
            }
            RepeatKey(
                modifier = Modifier.width(ScribeSize.Key).fillMaxHeight(),
                enabled = enabled,
                label = "Delete",
                background = ScribeColors.KeyRaised,
                border = ScribeColors.KeyRaisedEdge,
                onRepeat = actions.onBackspace,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_backspace),
                    contentDescription = null,
                    tint = ScribeColors.Error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(ScribeSize.Key),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EmojiKey(
                modifier = Modifier.width(54.dp).fillMaxHeight(),
                enabled = enabled,
                onClick = actions.onToggleEmoji,
            )
            SpaceKey(Modifier.weight(1f).fillMaxHeight(), enabled) { actions.onText(" ") }
            Key(".", Modifier.width(34.dp).fillMaxHeight(), enabled) { actions.onText(".") }
            LayoutButton(
                layout = state.layout,
                enabled = enabled,
                shape = ScribeShape.Chip,
                onToggle = actions.onToggleLayout,
                onLongClick = actions.onSettings,
            )
            EnterKey(
                modifier = Modifier.width(54.dp).fillMaxHeight(),
                enabled = enabled,
                send = state.sendKey,
                onClick = actions.onEnter,
            )
        }
    }
}

@Composable
private fun ClipboardStrip(
    state: ScribeUiState,
    actions: ScribeActions,
    modifier: Modifier = Modifier,
) {
    val entries = state.clipboard
    if (entries.isEmpty()) return
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth().height(ScribeSize.Key)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scroll),
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
                                .fillMaxHeight()
                                .clip(ScribeShape.Chip)
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
                                Text("IMAGE", color = ScribeColors.Muted, fontSize = ScribeText.Caption)
                            }
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .widthIn(min = 96.dp, max = 180.dp)
                                .fillMaxHeight()
                                .clip(ScribeShape.Chip)
                                .background(ScribeColors.Key)
                                .combinedClickable { actions.onInsertEntry(entry) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = entry.text.replace('\n', ' '),
                                color = ScribeColors.Text,
                                fontSize = ScribeText.Body,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    }
                }
            }
        }
        // The strip is cut by the keyboard edge; fade the last card so it
        // reads as "more to the right" instead of a clipped box.
        if (scroll.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(24.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, ScribeColors.Background),
                        ),
                    ),
            )
        }
    }
}

/**
 * The keyboard's first row: the clipboard button, then the clip history. It is
 * chrome like the Dictate bar, so a panel replaces the keys below it without
 * taking the clips away, and the button stays reachable while the history is
 * still empty.
 */
@Composable
private fun ClipboardDock(state: ScribeUiState, actions: ScribeActions) {
    val enabled = state.phase == Phase.IDLE || state.phase == Phase.ERROR
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyBox(
            modifier = Modifier
                .width(ScribeSize.Key)
                .height(ScribeSize.Key)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            enabled = enabled,
            shape = ScribeShape.Chip,
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
        if (state.clipboard.isEmpty()) {
            Text(
                text = "No clips yet",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Body,
                modifier = Modifier.padding(start = 2.dp),
            )
        } else {
            ClipboardStrip(state, actions, Modifier.weight(1f))
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
            .clip(ScribeShape.Chip)
            .background(ScribeColors.Key)
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ScribeColors.Text,
            fontSize = ScribeText.Caption,
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
    fontSize: TextUnit = ScribeText.Body,
    textColor: Color = ScribeColors.Text,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(ScribeShape.Chip)
            .background(ScribeColors.Key)
            .combinedClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class ShiftMode { OFF, ONCE, LOCKED }

@Composable
private fun KeyboardBody(state: ScribeUiState, actions: ScribeActions) {
    var shift by remember { mutableStateOf(ShiftMode.OFF) }
    var lastShiftTap by remember { mutableLongStateOf(0L) }
    var symbols by remember { mutableStateOf(false) }
    val enabled = state.phase == Phase.IDLE || state.phase == Phase.ERROR

    fun typeLetter(ch: Char) {
        actions.onText(if (shift == ShiftMode.OFF) ch.toString() else upper(ch))
        if (shift == ShiftMode.ONCE) shift = ShiftMode.OFF
    }

    fun tapShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftMode.LOCKED -> ShiftMode.OFF
            now - lastShiftTap < SHIFT_DOUBLE_TAP_MS -> ShiftMode.LOCKED
            else -> ShiftMode.ONCE
        }
        lastShiftTap = now
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (symbols) {
            SymbolRows(actions, enabled, sendKey = state.sendKey, onAbc = { symbols = false })
        } else {
            Row(
                Modifier.fillMaxWidth().height(ScribeSize.Key),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (key in "1234567890") {
                    Key(key.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                        actions.onText(key.toString())
                    }
                }
            }
            LetterRow("qwertyuiop", enabled) { typeLetter(it) }
            LetterRow("asdfghjkl", enabled) { typeLetter(it) }
            Row(
                Modifier.fillMaxWidth().height(ScribeSize.Key),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeyBox(
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    enabled = enabled,
                    background = if (shift == ShiftMode.OFF) ScribeColors.Key else ScribeColors.Green,
                    onClick = ::tapShift,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shift),
                            contentDescription = if (shift == ShiftMode.LOCKED) {
                                "Shift locked"
                            } else {
                                "Shift"
                            },
                            tint = if (shift == ShiftMode.OFF) {
                                ScribeColors.Text
                            } else {
                                ScribeColors.Background
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        // A locked shift keeps a small bar under the arrow so
                        // it is not just the same green as a one-shot shift.
                        if (shift == ShiftMode.LOCKED) {
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height(2.dp)
                                    .background(ScribeColors.Background),
                            )
                        }
                    }
                }
                for (key in "zxcvbnm") {
                    Key(key.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                        typeLetter(key)
                    }
                }
                RepeatKey(
                    modifier = Modifier.width(44.dp).fillMaxHeight(),
                    enabled = enabled,
                    label = "Delete",
                    background = ScribeColors.KeyRaised,
                    border = ScribeColors.KeyRaisedEdge,
                    onRepeat = actions.onBackspace,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_backspace),
                        contentDescription = null,
                        tint = ScribeColors.Error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            BottomRow(
                layout = state.layout,
                enabled = enabled,
                onMode = { symbols = true },
                onSpace = { actions.onText(" ") },
                onEnter = actions.onEnter,
                onEmoji = actions.onToggleEmoji,
                onLayout = actions.onToggleLayout,
                onSettings = actions.onSettings,
                sendKey = state.sendKey,
                onText = actions.onText,
            )
        }
    }
}

@Composable
private fun LetterRow(
    letters: String,
    enabled: Boolean,
    onLetter: (Char) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(ScribeSize.Key),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (ch in letters) {
            Key(ch.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                onLetter(ch)
            }
        }
    }
}

@Composable
private fun BottomRow(
    layout: KeyboardMode,
    enabled: Boolean,
    onMode: () -> Unit,
    onSpace: () -> Unit,
    onEnter: () -> Unit,
    onEmoji: () -> Unit,
    onLayout: () -> Unit,
    onSettings: () -> Unit,
    sendKey: Boolean,
    onText: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(ScribeSize.Key),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            onClick = onMode,
        ) {
            Text("?123", color = ScribeColors.Text, fontSize = ScribeText.Body)
        }
        Key(",", Modifier.width(34.dp).fillMaxHeight(), enabled) { onText(",") }
        EmojiKey(
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            enabled = enabled,
            onClick = onEmoji,
        )
        SpaceKey(Modifier.weight(1f).fillMaxHeight(), enabled) { onSpace() }
        Key(".", Modifier.width(34.dp).fillMaxHeight(), enabled) { onText(".") }
        LayoutButton(
            layout = layout,
            enabled = enabled,
            shape = ScribeShape.Chip,
            onToggle = onLayout,
            onLongClick = onSettings,
        )
        EnterKey(
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            enabled = enabled,
            send = sendKey,
            onClick = onEnter,
        )
    }
}

@Composable
private fun SymbolRows(
    actions: ScribeActions,
    enabled: Boolean,
    sendKey: Boolean,
    onAbc: () -> Unit,
) {
    val rows = listOf(
        listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
        listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":"),
        listOf("'", "\"", "\\", "|", ",", ".", "/", "?", "<", ">"),
    )
    rows.forEachIndexed { index, row ->
        Row(
            Modifier.fillMaxWidth().height(ScribeSize.Key),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (key in row) {
                Key(key, Modifier.weight(1f).fillMaxHeight(), enabled) {
                    actions.onText(key)
                }
            }
            if (index == rows.lastIndex) {
                RepeatKey(
                    modifier = Modifier.width(44.dp).fillMaxHeight(),
                    enabled = enabled,
                    label = "Delete",
                    background = ScribeColors.KeyRaised,
                    border = ScribeColors.KeyRaisedEdge,
                    onRepeat = actions.onBackspace,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_backspace),
                        contentDescription = null,
                        tint = ScribeColors.Error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(ScribeSize.Key),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            onClick = onAbc,
        ) {
            Text("ABC", color = ScribeColors.Text, fontSize = ScribeText.Body)
        }
        SpaceKey(Modifier.weight(1f).fillMaxHeight(), enabled) { actions.onText(" ") }
        LayoutButton(
            layout = KeyboardMode.FULL,
            enabled = enabled,
            shape = ScribeShape.Chip,
            onToggle = actions.onToggleLayout,
            onLongClick = actions.onSettings,
        )
        EnterKey(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            send = sendKey,
            onClick = actions.onEnter,
        )
    }
}

@Composable
private fun ClipboardPanel(state: ScribeUiState, actions: ScribeActions) {
    val entries = state.clipboard
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = imePanelHeight(0.5f, 420.dp))
            .padding(top = 6.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CLIPBOARD",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Caption,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(ScribeSize.Key)
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
            Box(
                modifier = Modifier
                    .size(ScribeSize.Key)
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
                emptyClipText(ClipFilter.ALL),
                color = ScribeColors.Muted,
                fontSize = ScribeText.Body,
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
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = ScribeShape.Surface
    Box(
        modifier = modifier
            .height(ScribeSize.Card)
            .clip(shape)
            .background(ScribeColors.Key)
            // A long-press picks one card for the action row; the outline is
            // the only thing that says which one.
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, ScribeColors.Green, shape)
                } else {
                    Modifier
                },
            )
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
                        Text(
                            clipKindLabel(entry.kind),
                            color = ScribeColors.Muted,
                            fontSize = ScribeText.Caption,
                        )
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
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = clipKindLabel(entry.kind),
                            color = ScribeColors.Muted,
                            fontSize = ScribeText.Caption,
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
                                    fontSize = ScribeText.Caption,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.text.replace('\n', ' '),
                        color = ScribeColors.Text,
                        fontSize = ScribeText.Body,
                        lineHeight = 16.sp,
                        maxLines = 3,
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
                    .clip(ScribeShape.Chip)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = clipKindLabel(entry.kind),
                    color = Color.White,
                    fontSize = ScribeText.Caption,
                )
                relativeTime(entry.createdAt)?.let { age ->
                    Text(
                        text = " · $age",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = ScribeText.Caption,
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
    DICTATION("DICTATION"),
}

private fun clipKindLabel(kind: ClipKind): String = when (kind) {
    ClipKind.TEXT -> "TEXT"
    ClipKind.IMAGE -> "IMAGE"
    ClipKind.TRANSCRIPT -> "DICTATION"
}

private fun emptyClipText(filter: ClipFilter): String = when (filter) {
    ClipFilter.ALL -> "Clipboard is empty. Copy text or an image."
    ClipFilter.TEXT -> "No text clips yet."
    ClipFilter.IMAGE -> "No images yet."
    ClipFilter.DICTATION -> "No dictations yet. Dictate once and the text stays here."
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
            ClipFilter.DICTATION -> entry.kind == ClipKind.TRANSCRIPT
        }
    }

    LaunchedEffect(state.clipboard) {
        val current = selected ?: return@LaunchedEffect
        if (state.clipboard.none { it.id == current.id }) selected = null
    }

    Column(Modifier.fillMaxWidth().height(imePanelHeight(0.5f, 420.dp))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CLIPBOARD",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Caption,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(ScribeSize.Key)
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
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
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
                    emptyClipText(filter),
                    color = ScribeColors.Muted,
                    fontSize = ScribeText.Body,
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
                                val isSelected = selected?.id == entry.id
                                ClipCard(
                                    entry = entry,
                                    onClick = actions.onInsertEntry,
                                    modifier = Modifier
                                        .weight(1f)
                                        .alpha(
                                            if (selected == null || isSelected) {
                                                1f
                                            } else {
                                                DIMMED_ALPHA
                                            },
                                        ),
                                    showMeta = true,
                                    selected = isSelected,
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
                modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactChip(
                    label = "Insert",
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
                    textColor = ScribeColors.Error,
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
                modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
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
            .fillMaxHeight()
            .clip(ScribeShape.Chip)
            .background(if (selected) ScribeColors.Green else ScribeColors.Key)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = filter.label,
            color = if (selected) ScribeColors.Background else ScribeColors.Muted,
            fontSize = ScribeText.Caption,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * The emoji surface replaces the key area like the language panel: every emoji
 * the system font draws, in lazy rows so the catalogue costs nothing until it
 * is scrolled. One tap commits the emoji and leaves the panel up for the next.
 */
@Composable
private fun EmojiPanel(
    usage: List<EmojiUse>,
    groups: List<EmojiGroup>,
    onInsert: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(imePanelHeight(0.42f, 340.dp))
            .padding(top = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EMOJI",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Caption,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            SmallAction("Close", onClose, ScribeColors.Text)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(EMOJI_COLUMNS),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            // The learned row leads: the emoji this hand actually reaches for,
            // most tapped first, capped at one glance's worth.
            if (usage.isNotEmpty()) {
                item(key = "most-used", span = { GridItemSpan(maxLineSpan) }) {
                    EmojiHeading("MOST USED")
                }
                items(
                    items = usage.take(MOST_USED_EMOJI_LIMIT),
                    key = { "used:${it.emoji}" },
                ) { use ->
                    EmojiCell(use.emoji, onInsert)
                }
            }
            for (group in groups) {
                item(key = "group:${group.label}", span = { GridItemSpan(maxLineSpan) }) {
                    EmojiHeading(group.label)
                }
                items(
                    items = group.emoji,
                    key = { "group:${group.label}:$it" },
                ) { emoji ->
                    EmojiCell(emoji, onInsert)
                }
            }
        }
    }
}

@Composable
private fun EmojiHeading(label: String) {
    Text(
        text = label,
        color = ScribeColors.Muted,
        fontSize = ScribeText.Caption,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 1.dp),
    )
}

/** The emoji is the whole target here, so it draws at the title step. */
@Composable
private fun EmojiCell(emoji: String, onInsert: (String) -> Unit) {
    Box(
        modifier = Modifier
            .height(ScribeSize.Tap)
            .combinedClickable { onInsert(emoji) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            color = ScribeColors.Text,
            fontSize = ScribeText.PageTitle,
            maxLines = 1,
        )
    }
}

/** One line of the language panel: either a section label or a pair of languages. */
private sealed interface LanguageItem {
    data class Heading(val label: String) : LanguageItem
    data class Row(val languages: List<DictationLanguage>) : LanguageItem
}

private fun languageItems(): List<LanguageItem> {
    val display = displayLanguages()
    val common = display.take(COMMON_LANGUAGE_CODES.size)
    val rest = display.drop(COMMON_LANGUAGE_CODES.size)
    return buildList {
        add(LanguageItem.Heading("COMMON"))
        common.chunked(2).forEach { add(LanguageItem.Row(it)) }
        // The picker used to follow Superwhisper's own order, which is not
        // alphabetical in any language the user reads. Group by the first
        // letter of the English name so the jump rail is meaningful.
        rest.groupBy { it.name.first().uppercaseChar() }.forEach { (letter, languages) ->
            add(LanguageItem.Heading(letter.toString()))
            languages.chunked(2).forEach { add(LanguageItem.Row(it)) }
        }
    }
}

@Composable
private fun LanguagePanel(state: ScribeUiState, actions: ScribeActions) {
    val entries = remember { languageItems() }
    val letters = remember(entries) {
        entries.filterIsInstance<LanguageItem.Heading>()
            .map { it.label }
            .filter { it.length == 1 }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val selectedRow = remember(entries, state.language) {
        entries.indexOfFirst { item ->
            item is LanguageItem.Row && item.languages.any { it.code == state.language }
        }
    }
    LaunchedEffect(selectedRow) {
        if (selectedRow < 0) return@LaunchedEffect
        // Wait for the list to be measured: before that the viewport is 0 and
        // every offset clamps to the top row. Then center the current
        // language instead of pinning it to a clipped edge.
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }
            .first { it > 0 }
        val rowHeight = with(density) { ScribeSize.Key.toPx() }
        listState.scrollToItem(selectedRow)
        listState.scrollBy((viewport - rowHeight) / 2f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(imePanelHeight(0.42f, 340.dp))
            .padding(top = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Key),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LANGUAGE",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Caption,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            SmallAction("Close", actions.onToggleLanguage, ScribeColors.Text)
        }
        LanguageJumpRail(letters) { letter ->
            val index = entries.indexOfFirst {
                it is LanguageItem.Heading && it.label == letter
            }
            if (index >= 0) scope.launch { listState.scrollToItem(index) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(LANGUAGE_ROW_GAP),
        ) {
            items(entries) { item ->
                when (item) {
                    is LanguageItem.Heading -> Text(
                        text = item.label,
                        color = ScribeColors.Muted,
                        fontSize = ScribeText.Caption,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 6.dp),
                    )
                    is LanguageItem.Row -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.languages.forEach { language ->
                            LanguageButton(
                                name = language.name,
                                selected = state.language == language.code,
                                modifier = Modifier.weight(1f),
                                onClick = { actions.onSelectLanguage(language.code) },
                            )
                        }
                        if (item.languages.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** A–Z rail: one tap lands on the first language that starts with the letter. */
@Composable
private fun LanguageJumpRail(letters: List<String>, onJump: (String) -> Unit) {
    if (letters.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScribeSize.Key)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (letter in letters) {
            Box(
                modifier = Modifier
                    .width(LANGUAGE_JUMP_WIDTH)
                    .fillMaxHeight()
                    .clip(ScribeShape.Chip)
                    .background(ScribeColors.Key)
                    .combinedClickable { onJump(letter) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    color = ScribeColors.Muted,
                    fontSize = ScribeText.Body,
                    fontWeight = FontWeight.SemiBold,
                )
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
            .height(ScribeSize.Key)
            .clip(ScribeShape.Surface)
            .background(if (selected) ScribeColors.Green else ScribeColors.Key)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            color = if (selected) ScribeColors.Background else ScribeColors.Text,
            fontSize = ScribeText.Body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

/** An IME panel may take at most this share of the screen. */
@Composable
private fun imePanelHeight(fraction: Float, max: Dp): Dp {
    val screen = LocalConfiguration.current.screenHeightDp
    return minOf(max, (screen * fraction).dp)
}

@Composable
private fun RepeatKey(
    modifier: Modifier,
    enabled: Boolean = true,
    label: String,
    shape: Shape = ScribeShape.Chip,
    background: Color = ScribeColors.Key,
    border: Color? = null,
    onRepeat: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (border == null) Modifier else Modifier.border(1.dp, border, shape))
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
    fontSize: TextUnit = ScribeText.Key,
    textColor: Color = ScribeColors.Text,
    onClick: () -> Unit,
) {
    KeyBox(modifier, enabled = enabled, onClick = onClick) {
        Text(
            label,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The space bar carries no label; it only needs a name for TalkBack. */
@Composable
private fun SpaceKey(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    KeyBox(
        modifier = modifier.semantics { contentDescription = "Space" },
        enabled = enabled,
        background = ScribeColors.KeyRaised,
        border = ScribeColors.KeyRaisedEdge,
        onClick = onClick,
    ) {}
}

/** The emoji key sits to the left of Enter in both the compact and full rows. */
@Composable
private fun EmojiKey(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    KeyBox(modifier = modifier, enabled = enabled, onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_emoji),
            contentDescription = "Emoji",
            tint = ScribeColors.Text,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Enter is neutral until the editor declares a send action; then it carries
 * the one accent colour instead of being a second, unrelated blue.
 */
@Composable
private fun EnterKey(
    modifier: Modifier,
    enabled: Boolean,
    send: Boolean,
    onClick: () -> Unit,
) {
    KeyBox(
        modifier = modifier,
        enabled = enabled,
        background = if (send) ScribeColors.Green else ScribeColors.KeyRaised,
        border = if (send) null else ScribeColors.Green.copy(alpha = 0.5f),
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(if (send) R.drawable.ic_send else R.drawable.ic_enter),
            contentDescription = if (send) "Send" else "Enter",
            tint = if (send) ScribeColors.Background else ScribeColors.Green,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun KeyBox(
    modifier: Modifier,
    enabled: Boolean = true,
    shape: Shape = ScribeShape.Chip,
    background: Color = ScribeColors.Key,
    border: Color? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (border == null) Modifier else Modifier.border(1.dp, border, shape))
            .combinedClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
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
