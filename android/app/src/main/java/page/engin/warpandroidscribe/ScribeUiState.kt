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

enum class KeyboardMode { COMPACT, FULL }

enum class ClipboardView { HIDDEN, QUICK, FULL }

data class ClipboardEntry(
    val id: String,
    val kind: ClipKind,
    val text: String = "",
    val file: File? = null,
    val mime: String = "image/*",
    val source: String = "",
    val createdAt: Long = 0L,
)

/** Compose-observable state owned by [ScribeImeService]. */
class ScribeUiState {
    var phase by mutableStateOf(Phase.IDLE)
    var locked by mutableStateOf(false)
    var elapsedSecs by mutableIntStateOf(0)
    var language by mutableStateOf("tr")
    /** True when the focused editor asks for a send action, so Enter shows ↑. */
    var sendKey by mutableStateOf(false)
    var layout by mutableStateOf(KeyboardMode.COMPACT)
    var error by mutableStateOf<String?>(null)
    var canRetry by mutableStateOf(false)
    var clipboardView by mutableStateOf(ClipboardView.HIDDEN)
    var showLanguages by mutableStateOf(false)
    /** Height of the system navigation bar drawn over the IME window. */
    var systemBarInsetPx by mutableIntStateOf(0)
    var clipboard by mutableStateOf<List<ClipboardEntry>>(emptyList())
    var shortcuts by mutableStateOf<List<String>>(emptyList())
    /** Transient confirmation after a transcript lands, e.g. "Inserted". */
    var notice by mutableStateOf<String?>(null)
    /** Code points the last dictation inserted, so the notice can undo it. */
    var noticeUndoCodePoints by mutableIntStateOf(0)
}

class ScribeActions(
    val onDictateDown: () -> Unit,
    val onDictateUp: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissError: () -> Unit,
    val onSettings: () -> Unit,
    val onToggleLanguage: () -> Unit,
    val onSelectLanguage: (String) -> Unit,
    val onToggleLayout: () -> Unit,
    val onUndo: () -> Unit,
    val onCut: () -> Unit,
    val onCopy: () -> Unit,
    val onPaste: () -> Unit,
    val onMoveCursor: (Int) -> Unit,
    val onToggleClipboard: () -> Unit,
    val onExpandClipboard: () -> Unit,
    val onCloseClipboard: () -> Unit,
    val onInsertEntry: (ClipboardEntry) -> Unit,
    val onUndoInsert: () -> Unit,
    val onDeleteEntry: (ClipboardEntry) -> Unit,
    val onClearClips: () -> Unit,
    val onCopyEntry: (ClipboardEntry) -> Unit,
    val onBackspace: () -> Unit,
    val onEnter: () -> Unit,
    val onText: (String) -> Unit,
)
