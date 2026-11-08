package com.engin.warpandroidscribe.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import com.engin.warpandroidscribe.ClipKind
import com.engin.warpandroidscribe.ClipboardEntry
import com.engin.warpandroidscribe.ConnectionMode
import com.engin.warpandroidscribe.Phase
import com.engin.warpandroidscribe.R
import com.engin.warpandroidscribe.ScribeActions
import com.engin.warpandroidscribe.ScribeUiState

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
        TopBar(state, actions)
        if (state.showClipboard) {
            ClipboardPanel(state, actions)
        } else {
            state.error?.let { ErrorLine(state, actions) }
            KeyboardBody(state, actions)
        }
    }
}

@Composable
private fun TopBar(state: ScribeUiState, actions: ScribeActions) {
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
                    contentDescription = "İptal",
                    tint = ScribeColors.Error,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            ModeChip(state.mode, actions.onToggleMode)
        }
        DictatePill(state, actions, Modifier.weight(1f).fillMaxHeight())
        KeyBox(
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            shape = RoundedCornerShape(21.dp),
            onClick = actions.onToggleLanguage,
        ) {
            Text(
                text = if (state.language == "auto") "OTO" else "TR",
                color = ScribeColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        KeyBox(
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            shape = RoundedCornerShape(21.dp),
            onClick = actions.onToggleClipboard,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clipboard),
                contentDescription = "Pano",
                tint = if (state.showClipboard) ScribeColors.Green else ScribeColors.Text,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(mode: ConnectionMode, onClick: () -> Unit) {
    KeyBox(
        modifier = Modifier.width(66.dp).fillMaxHeight(),
        shape = RoundedCornerShape(21.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (mode == ConnectionMode.LOCAL) ScribeColors.Green else ScribeColors.Blue),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = mode.label,
                color = ScribeColors.Muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

@Composable
private fun DictatePill(state: ScribeUiState, actions: ScribeActions, modifier: Modifier) {
    val recording = state.phase == Phase.RECORDING
    val processing = state.phase == Phase.TRANSCRIBING
    val label = when {
        processing -> "İşleniyor…"
        recording && state.locked -> "Durdur"
        recording -> "Bırak ${formatElapsed(state.elapsedSecs)}"
        else -> "Dikte"
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
            SmallAction("Tekrar", actions.onRetry, ScribeColors.Text)
        }
        SmallAction("Ayarlar", actions.onSettings, ScribeColors.Muted)
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = actions.onDismissError),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Kapat",
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
            LetterRow("qwertyuıopğü", actions, enabled) { ch ->
                actions.onText(letter(ch, shiftOn) { shiftOn = false })
            }
            LetterRow("asdfghjklşi", actions, enabled) { ch ->
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
                for (key in "zxcvbnmöç") {
                    Key(key.toString(), Modifier.weight(1f).fillMaxHeight(), enabled) {
                        actions.onText(letter(key, shiftOn) { shiftOn = false })
                    }
                }
                KeyBox(
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    enabled = enabled,
                    onClick = actions.onBackspace,
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
            "boşluk",
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
            "boşluk",
            Modifier.weight(1f).fillMaxHeight(),
            enabled,
            fontSize = 11.sp,
            textColor = ScribeColors.Muted,
        ) { actions.onText(" ") }
        KeyBox(
            modifier = Modifier.width(54.dp).fillMaxHeight(),
            enabled = enabled,
            onClick = actions.onBackspace,
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
                "PANO",
                color = ScribeColors.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            SmallAction("Sistem klavyesi", actions.onSwitchKeyboard, ScribeColors.Text)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = actions.onCloseClipboard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Kapat",
                    tint = ScribeColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (entries.isEmpty()) {
            Text(
                "Pano boş. Bir metin veya görsel kopyala.",
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
) {
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ScribeColors.Key)
            .combinedClickable(onClick = { onClick(entry) }),
    ) {
        when (entry.kind) {
            ClipKind.IMAGE -> {
                val bitmap = remember(entry.file) { loadThumbnail(entry.file) }
                if (bitmap == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("GÖRSEL", color = ScribeColors.Muted, fontSize = 9.sp)
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Pano görseli",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ClipKind.TEXT, ClipKind.TRANSCRIPT -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp)) {
                    Text(
                        text = entry.label.ifBlank { "PANO" },
                        color = ScribeColors.Muted,
                        fontSize = 8.sp,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    }
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

private fun upper(ch: Char): String = when (ch) {
    'i' -> "İ"
    'ı' -> "I"
    else -> ch.uppercaseChar().toString()
}

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
