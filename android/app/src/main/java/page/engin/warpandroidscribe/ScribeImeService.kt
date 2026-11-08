package com.engin.warpandroidscribe

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.engin.warpandroidscribe.ui.ScribeKeyboard
import com.engin.warpandroidscribe.ui.ScribeTheme

class ScribeImeService : InputMethodService() {
    private val ui = ScribeUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: ScribePrefs
    private lateinit var client: ScribeClient
    private lateinit var lifecycleOwner: ImeLifecycleOwner
    private lateinit var clipboard: ClipboardManager

    private var recorder: WavRecorder? = null
    private var recordFile: File? = null
    private var recordStartedAt = 0L
    private var recordTicker: Job? = null
    private var transcribeJob: Job? = null
    private var activeFile: File? = null
    private var pendingFile: File? = null
    private var clipHistory = mutableListOf<String>()
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val actions: ScribeActions by lazy {
        ScribeActions(
            onDictateDown = ::dictateDown,
            onDictateUp = ::dictateUp,
            onLock = ::lockRecording,
            onCancel = ::cancelActiveWork,
            onRetry = ::retry,
            onDismissError = ::dismissError,
            onSettings = ::openSettings,
            onLanguage = ::toggleLanguage,
            onSwitchKeyboard = ::switchKeyboard,
            onHideKeyboard = ::hideKeyboard,
            onBackspace = ::backspace,
            onSpace = ::space,
            onEnter = ::enter,
            onInsertChip = ::insertText,
            onRemoveChip = ::removeChip,
            onInsertLast = { insertText(ui.lastTranscript) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ScribePrefs(this)
        client = ScribeClient(prefs)
        lifecycleOwner = ImeLifecycleOwner().apply { onCreate() }
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.language = prefs.language
        ui.lastTranscript = prefs.lastTranscript
        cleanupOldRecordings()
    }

    override fun onCreateInputView(): ComposeView {
        val view = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            attachLifecycleOwner(this)
            setContent {
                ScribeTheme {
                    ScribeKeyboard(ui, actions)
                }
            }
        }
        // Compose resolves the window recomposer from the IME dialog's decor
        // view, not from this ComposeView. Tag the whole window as well or the
        // first show throws "ViewTreeLifecycleOwner not found".
        window?.window?.decorView?.let(::attachLifecycleOwner)
        return view
    }

    private fun attachLifecycleOwner(view: android.view.View) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleOwner.onStart()
        ui.language = prefs.language
        ui.lastTranscript = prefs.lastTranscript
        ui.chips = buildChips()
        ui.canRetry = pendingFile != null
        if (ui.phase != Phase.RECORDING && ui.phase != Phase.TRANSCRIBING) {
            ui.phase = if (pendingFile != null) Phase.ERROR else Phase.IDLE
            if (pendingFile != null) {
                ui.error = ui.error ?: "Son dikte gönderilemedi."
            }
        }
        registerClipboard()
        refreshClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleOwner.onStop()
        unregisterClipboard()
        if (finishingInput) {
            cancelActiveWork()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (ui.phase == Phase.RECORDING) {
            cancelActiveWork()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        scope.cancel()
        cancelActiveWork()
        lifecycleOwner.onDestroy()
        super.onDestroy()
    }

    private fun dictateDown() {
        when (ui.phase) {
            Phase.RECORDING -> if (ui.locked) stopAndTranscribe() else Unit
            Phase.TRANSCRIBING -> Unit
            else -> startRecording()
        }
    }

    private fun dictateUp() {
        if (ui.phase != Phase.RECORDING || ui.locked) return
        val elapsed = SystemClock.elapsedRealtime() - recordStartedAt
        if (elapsed < TAP_LOCK_MS) {
            // A quick tap starts hands-free recording; a hold is push-to-talk.
            ui.locked = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
        } else {
            stopAndTranscribe()
        }
    }

    private fun lockRecording() {
        if (ui.phase == Phase.RECORDING) {
            ui.locked = true
        }
    }

    private fun startRecording() {
        if (ui.phase == Phase.TRANSCRIBING || ui.phase == Phase.RECORDING) return
        if (!prefs.isConfigured) {
            showError("Önce ayarları doldur: sunucu ve token.")
            return
        }
        if (prefs.usesLocalNetwork && !hasLocalNetworkPermission()) {
            showError("Yerel ağ izni yok. Ayarlar'dan izin ver veya Cloudflare'i seç.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showError("Mikrofon izni yok. Ayarlar'dan izin ver.")
            return
        }

        val file = File(cacheDir, "dictation-${System.currentTimeMillis()}.wav")
        val recorder = WavRecorder(file)
        try {
            recorder.start()
        } catch (error: Exception) {
            file.delete()
            showError("Mikrofon açılamadı: ${error.message}")
            return
        }

        pendingFile?.delete()
        pendingFile = null
        this.recorder = recorder
        recordFile = file
        recordStartedAt = SystemClock.elapsedRealtime()
        ui.phase = Phase.RECORDING
        ui.locked = false
        ui.elapsedSecs = 0
        ui.error = null
        ui.canRetry = false
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)

        recordTicker = scope.launch {
            while (isActive && ui.phase == Phase.RECORDING) {
                val elapsed = ((SystemClock.elapsedRealtime() - recordStartedAt) / 1000).toInt()
                ui.elapsedSecs = elapsed
                ui.amplitude = ((this@ScribeImeService.recorder?.amplitude ?: 0) / 32767f)
                    .coerceIn(0f, 1f)
                if (elapsed >= MAX_RECORD_SECONDS) {
                    stopAndTranscribe(force = true)
                    break
                }
                delay(100)
            }
        }
    }

    private fun stopAndTranscribe(force: Boolean = false) {
        if (ui.phase != Phase.RECORDING) return
        val recorder = recorder ?: return
        val file = recordFile ?: return
        val elapsed = SystemClock.elapsedRealtime() - recordStartedAt
        recordTicker?.cancel()
        recordTicker = null
        this.recorder = null
        recordFile = null
        ui.locked = false

        if (!force && elapsed < MIN_RECORD_MS) {
            recorder.cancel()
            ui.phase = Phase.IDLE
            return
        }

        ui.phase = Phase.TRANSCRIBING
        ui.elapsedSecs = 0
        activeFile = file
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)

        transcribeJob = scope.launch {
            val stopped = withContext(Dispatchers.IO) { runCatching { recorder.stop() } }
            if (stopped.isFailure) {
                file.delete()
                activeFile = null
                showError("Kayıt tamamlanamadı.")
                return@launch
            }
            val size = withContext(Dispatchers.IO) { file.length() }
            if (size <= 44) {
                file.delete()
                activeFile = null
                showError("Kayıt boş çıktı.")
                return@launch
            }

            val language = ui.language
            val result = withContext(Dispatchers.IO) {
                runCatching { client.transcribe(file, language) }
            }
            result.onSuccess { transcript ->
                file.delete()
                activeFile = null
                handleTranscript(transcript)
            }.onFailure { error ->
                activeFile = null
                handleFailure(file, error)
            }
        }
    }

    private fun retry() {
        val file = pendingFile ?: return
        if (!prefs.isConfigured) {
            showError("Önce ayarları doldur: sunucu ve token.")
            return
        }
        transcribeJob?.cancel()
        pendingFile = null
        activeFile = file
        ui.phase = Phase.TRANSCRIBING
        ui.error = null
        ui.canRetry = false
        transcribeJob = scope.launch {
            val language = ui.language
            val result = withContext(Dispatchers.IO) {
                runCatching { client.transcribe(file, language) }
            }
            result.onSuccess { transcript ->
                file.delete()
                activeFile = null
                handleTranscript(transcript)
            }.onFailure { error ->
                activeFile = null
                handleFailure(file, error)
            }
        }
    }

    private fun handleTranscript(transcript: ScribeClient.Result) {
        val text = transcript.text.trim()
        if (text.isEmpty()) {
            showError("Konuşma algılanmadı.")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            showError("Metin alanı kapandı.")
            return
        }

        connection.commitText(text, 1)
        prefs.addRecentTranscript(text)
        prefs.lastTranscript = text
        ui.lastTranscript = text
        ui.chips = buildChips()
        ui.phase = Phase.IDLE
        ui.error = null
        ui.canRetry = false
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun handleFailure(file: File, error: Throwable) {
        pendingFile = file
        ui.phase = Phase.ERROR
        ui.error = friendlyError(error)
        ui.canRetry = true
        ui.locked = false
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is ScribeException -> error.message ?: "Dikte başarısız."
        is java.io.IOException -> "Sunucuya ulaşılamadı. Wi-Fi ve NAS'ı kontrol et."
        else -> "Dikte başarısız: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun cancelActiveWork() {
        recordTicker?.cancel()
        recordTicker = null
        recorder?.cancel()
        recorder = null
        recordFile?.delete()
        recordFile = null
        transcribeJob?.cancel()
        transcribeJob = null
        activeFile?.delete()
        activeFile = null
        pendingFile?.delete()
        pendingFile = null
        ui.phase = Phase.IDLE
        ui.locked = false
        ui.elapsedSecs = 0
        ui.error = null
        ui.canRetry = false
    }

    private fun dismissError() {
        pendingFile?.delete()
        pendingFile = null
        ui.error = null
        ui.canRetry = false
        ui.phase = Phase.IDLE
    }

    private fun showError(message: String) {
        ui.phase = Phase.ERROR
        ui.error = message
        ui.canRetry = false
        ui.locked = false
    }

    private fun toggleLanguage() {
        ui.language = if (ui.language == "auto") "tr" else "auto"
        prefs.language = ui.language
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun switchKeyboard() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun hideKeyboard() {
        requestHideSelf(0)
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun backspace() {
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun space() {
        currentInputConnection?.commitText(" ", 1)
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun enter() {
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun insertText(text: String) {
        if (text.isBlank()) return
        currentInputConnection?.commitText(text, 1)
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun removeChip(chip: ScribeChip) {
        if (chip.clipboard) {
            clipHistory.remove(chip.text)
        } else {
            prefs.removeRecentTranscript(chip.text)
        }
        ui.chips = buildChips()
    }

    private fun buildChips(): List<ScribeChip> {
        val clips = clipHistory.map { ScribeChip(it, clipboard = true) }
        val recent = prefs.recentTranscripts().map { ScribeChip(it, clipboard = false) }
        return (clips + recent).distinctBy { it.text }.take(MAX_CHIPS)
    }

    private fun registerClipboard() {
        if (clipListener != null) return
        val listener = ClipboardManager.OnPrimaryClipChangedListener { refreshClipboard() }
        clipListener = listener
        clipboard.addPrimaryClipChangedListener(listener)
    }

    private fun unregisterClipboard() {
        clipListener?.let { clipboard.removePrimaryClipChangedListener(it) }
        clipListener = null
    }

    private fun refreshClipboard() {
        val text = runCatching {
            if (!clipboard.hasPrimaryClip()) return@runCatching ""
            val clip = clipboard.primaryClip ?: return@runCatching ""
            if (clip.itemCount == 0) return@runCatching ""
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        }.getOrDefault("")

        if (text.isBlank() || text.length > MAX_CLIP_CHARS) return
        clipHistory.remove(text)
        clipHistory.add(0, text)
        while (clipHistory.size > MAX_CLIP_HISTORY) {
            clipHistory.removeAt(clipHistory.lastIndex)
        }
        ui.chips = buildChips()
    }

    private fun cleanupOldRecordings() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        cacheDir.listFiles { file ->
            file.name.startsWith("dictation-") &&
                file.name.endsWith(".wav") &&
                file.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun haptic(feedback: Int) {
        window?.window?.decorView?.performHapticFeedback(feedback)
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_RECORD_SECONDS = 120
        const val MIN_RECORD_MS = 400L
        const val TAP_LOCK_MS = 350L
        const val MAX_CLIP_CHARS = 4000
        const val MAX_CLIP_HISTORY = 5
        const val MAX_CHIPS = 8
    }
}
