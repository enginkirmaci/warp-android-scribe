package com.engin.warpandroidscribe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Phase { IDLE, RECORDING, TRANSCRIBING, ERROR }

data class ScribeChip(val text: String, val clipboard: Boolean) {
    val label: String
        get() {
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length > 42) flat.take(42) + "…" else flat
        }
}

/** Compose-observable state owned by [ScribeImeService]. */
class ScribeUiState {
    var phase by mutableStateOf(Phase.IDLE)
    var locked by mutableStateOf(false)
    var elapsedSecs by mutableIntStateOf(0)
    var amplitude by mutableFloatStateOf(0f)
    var language by mutableStateOf("tr")
    var error by mutableStateOf<String?>(null)
    var canRetry by mutableStateOf(false)
    var chips by mutableStateOf<List<ScribeChip>>(emptyList())
    var lastTranscript by mutableStateOf("")
}

class ScribeActions(
    val onDictateDown: () -> Unit,
    val onDictateUp: () -> Unit,
    val onLock: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissError: () -> Unit,
    val onSettings: () -> Unit,
    val onLanguage: () -> Unit,
    val onSwitchKeyboard: () -> Unit,
    val onHideKeyboard: () -> Unit,
    val onBackspace: () -> Unit,
    val onSpace: () -> Unit,
    val onEnter: () -> Unit,
    val onInsertChip: (String) -> Unit,
    val onRemoveChip: (ScribeChip) -> Unit,
    val onInsertLast: () -> Unit,
)
