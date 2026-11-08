package com.engin.warpandroidscribe.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.engin.warpandroidscribe.Phase
import com.engin.warpandroidscribe.R
import com.engin.warpandroidscribe.ScribeActions
import com.engin.warpandroidscribe.ScribeChip
import com.engin.warpandroidscribe.ScribeUiState

@Composable
fun ScribeKeyboard(state: ScribeUiState, actions: ScribeActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScribeColors.Background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        when {
            state.phase == Phase.TRANSCRIBING -> TranscribingBar(actions)
            state.phase == Phase.RECORDING -> RecordingBar(state, actions)
            state.error != null -> ErrorBar(state, actions)
            else -> ChipsBar(state, actions)
        }
        Spacer(Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyBox(
                modifier = Modifier.width(48.dp).height(52.dp),
                onClick = actions.onBackspace,
                onLongClick = actions.onHideKeyboard,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_backspace),
                    contentDescription = "Geri sil",
                    tint = ScribeColors.Text,
                    modifier = Modifier.size(22.dp),
                )
            }
            DictateButton(state, actions, Modifier.weight(1f).height(52.dp))
            KeyBox(Modifier.width(48.dp).height(52.dp), onClick = actions.onLanguage) {
                Text(
                    text = if (state.language == "auto") "OTO" else "TR",
                    color = ScribeColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            KeyBox(Modifier.width(48.dp).height(52.dp), onClick = actions.onSwitchKeyboard) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = "Klavye değiştir",
                    tint = ScribeColors.Text,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBox(Modifier.weight(1f).height(42.dp), onClick = actions.onSpace) {
                Text("boşluk", color = ScribeColors.Muted, fontSize = 12.sp)
            }
            KeyBox(Modifier.width(84.dp).height(42.dp), onClick = actions.onEnter) {
                Text("↵", color = ScribeColors.Text, fontSize = 18.sp)
            }
        }
        if (state.lastTranscript.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            LastTranscriptBar(state.lastTranscript, actions.onInsertLast)
        }
    }
}

@Composable
private fun KeyBox(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(ScribeColors.Key)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun DictateButton(
    state: ScribeUiState,
    actions: ScribeActions,
    modifier: Modifier,
) {
    val recording = state.phase == Phase.RECORDING
    val processing = state.phase == Phase.TRANSCRIBING
    val label = when {
        processing -> "Yazıya çevriliyor…"
        recording && state.locked -> "Durdur"
        recording -> "Bırak"
        else -> "Dikte et"
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
            .clip(RoundedCornerShape(24.dp))
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
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = if (recording) ScribeColors.Text else ScribeColors.Background,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = if (recording) ScribeColors.Text else ScribeColors.Background,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChipsBar(state: ScribeUiState, actions: ScribeActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.chips.isEmpty()) {
            Text(
                text = "Pano veya son dikte burada görünür",
                color = ScribeColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        state.chips.forEach { chip ->
            Chip(chip, actions)
        }
    }
}

@Composable
private fun Chip(chip: ScribeChip, actions: ScribeActions) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ScribeColors.Key)
            .combinedClickable(
                onClick = { actions.onInsertChip(chip.text) },
                onLongClick = { actions.onRemoveChip(chip) },
            )
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (chip.clipboard) "PANO" else "SON",
            color = ScribeColors.Muted,
            fontSize = 8.sp,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = chip.label,
            color = ScribeColors.Text,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecordingBar(state: ScribeUiState, actions: ScribeActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(ScribeColors.RecordingBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot()
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Kayıt ${formatElapsed(state.elapsedSecs)}",
            color = ScribeColors.Text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        if (!state.locked) {
            BarAction("Kilitle", actions.onLock, ScribeColors.Muted)
        }
        BarAction("İptal", actions.onCancel, ScribeColors.Text)
    }
}

@Composable
private fun TranscribingBar(actions: ScribeActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(ScribeColors.Surface)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = ScribeColors.Green,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text("Yazıya çevriliyor…", color = ScribeColors.Text, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        BarAction("İptal", actions.onCancel, ScribeColors.Muted)
    }
}

@Composable
private fun ErrorBar(state: ScribeUiState, actions: ScribeActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(ScribeColors.ErrorBg)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
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
            BarAction("Tekrar", actions.onRetry, ScribeColors.Text)
        }
        BarAction("Ayarlar", actions.onSettings, ScribeColors.Muted)
        Box(
            modifier = Modifier
                .size(28.dp)
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
private fun BarAction(
    label: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "recording")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "recording-alpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(ScribeColors.Recording),
    )
}

@Composable
private fun LastTranscriptBar(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ScribeColors.Surface)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SON",
            color = ScribeColors.Muted,
            fontSize = 8.sp,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = ScribeColors.Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("ekle", color = ScribeColors.Green, fontSize = 10.sp)
    }
}

private fun formatElapsed(seconds: Int): String =
    String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
