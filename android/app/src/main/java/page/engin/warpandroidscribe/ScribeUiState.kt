package com.engin.warpandroidscribe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class ConnectionMode(val label: String) {
    LOCAL("YEREL"),
    REMOTE("UZAK"),
}

enum class Phase { IDLE, RECORDING, TRANSCRIBING, ERROR }

enum class ClipKind { TEXT, IMAGE, TRANSCRIPT }

data class ClipboardEntry(
    val id: String,
    val kind: ClipKind,
    val text: String = "",
    val file: File? = null,
    val mime: String = "image/*",
    val label: String = "",
    val source: String = "",
)

/** Compose-observable state owned by [ScribeImeService]. */
class ScribeUiState {
    var phase by mutableStateOf(Phase.IDLE)
    var locked by mutableStateOf(false)
    var elapsedSecs by mutableIntStateOf(0)
    var language by mutableStateOf("tr")
    var mode by mutableStateOf(ConnectionMode.REMOTE)
    var error by mutableStateOf<String?>(null)
    var canRetry by mutableStateOf(false)
    var showClipboard by mutableStateOf(false)
    /** Height of the system navigation bar drawn over the IME window. */
    var systemBarInsetPx by mutableIntStateOf(0)
    var clipboard by mutableStateOf<List<ClipboardEntry>>(emptyList())
}

class ScribeActions(
    val onDictateDown: () -> Unit,
    val onDictateUp: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissError: () -> Unit,
    val onSettings: () -> Unit,
    val onToggleLanguage: () -> Unit,
    val onToggleMode: () -> Unit,
    val onToggleClipboard: () -> Unit,
    val onCloseClipboard: () -> Unit,
    val onInsertEntry: (ClipboardEntry) -> Unit,
    val onSwitchKeyboard: () -> Unit,
    val onBackspace: () -> Unit,
    val onEnter: () -> Unit,
    val onText: (String) -> Unit,
)
