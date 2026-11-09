package com.engin.warpandroidscribe

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.io.File
import java.util.UUID
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
    private var clipHistory = mutableListOf<ClipboardEntry>()
    private var clipJob: Job? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var insetListenerAttached = false

    private val actions: ScribeActions by lazy {
        ScribeActions(
            onDictateDown = ::dictateDown,
            onDictateUp = ::dictateUp,
            onCancel = ::cancelActiveWork,
            onRetry = ::retry,
            onDismissError = ::dismissError,
            onSettings = ::openSettings,
            onToggleLanguage = ::toggleLanguagePicker,
            onSelectLanguage = ::selectLanguage,
            onToggleLayout = ::toggleLayout,
            onUndo = ::undo,
            onCut = ::cut,
            onCopy = ::copy,
            onPaste = ::pasteCurrent,
            onMoveCursor = ::moveCursor,
            onToggleClipboard = ::toggleClipboard,
            onExpandClipboard = ::expandClipboard,
            onCloseClipboard = ::closeClipboard,
            onInsertEntry = ::insertEntry,
            onDeleteEntry = ::deleteEntry,
            onClearClips = ::clearClips,
            onCopyEntry = ::copyEntry,
            onSwitchKeyboard = ::switchKeyboard,
            onBackspace = ::backspace,
            onEnter = ::enter,
            onText = ::insertText,
        )
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ScribePrefs(this)
        client = ScribeClient(prefs)
        lifecycleOwner = ImeLifecycleOwner().apply { onCreate() }
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.language = prefs.language
        ui.shortcuts = prefs.shortcuts
        ui.layout = KeyboardMode.COMPACT
        cleanupOldRecordings()
        cleanupClipboardCache()
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
            isHapticFeedbackEnabled = false
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
        window?.window?.decorView?.isHapticFeedbackEnabled = false
        installSystemBarInsetListener()
        return view
    }

    override fun onWindowShown() {
        super.onWindowShown()
        ui.layout = KeyboardMode.COMPACT
        ui.showLanguages = false
        installSystemBarInsetListener()
        updateSystemBarInset()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (ui.showLanguages) {
                ui.showLanguages = false
                return true
            }
            if (ui.clipboardView != ClipboardView.HIDDEN) {
                ui.clipboardView = ClipboardView.HIDDEN
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBarInset()
    }

    private fun attachLifecycleOwner(view: android.view.View) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun installSystemBarInsetListener() {
        val decor = window?.window?.decorView ?: return
        if (insetListenerAttached) return
        insetListenerAttached = true
        decor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateSystemBarInset()
        }
        decor.post(::updateSystemBarInset)
    }

    /**
     * The IME window reaches the display bottom on gestural navigation; the
     * system's two-button keyboard bar (switch/hide) is drawn on top of it and
     * the navigation-bar inset is reported as zero for INPUT_METHOD windows.
     * Measure whether the window really covers that strip, and pad the
     * keyboard by the system's navigation-bar height when it does.
     */
    private fun updateSystemBarInset() {
        val decor = window?.window?.decorView ?: return
        val navInset = decor.rootWindowInsets?.let { insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
        } ?: 0
        val coversBar = decor.height > 0 && imeWindowTouchesDisplayBottom(decor)
        val inset = when {
            navInset > 0 -> navInset
            coversBar -> navigationBarHeightPx()
            else -> 0
        }
        ui.systemBarInsetPx = inset
    }

    private fun imeWindowTouchesDisplayBottom(decor: View): Boolean {
        val displayHeight = displayHeight()
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        return location[1] + decor.height >= displayHeight - 1
    }

    private fun displayHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java)
                .maximumWindowMetrics
                .bounds
                .height()
        } else {
            resources.displayMetrics.heightPixels
        }

    private fun navigationBarHeightPx(): Int {
        val system = Resources.getSystem()
        for (name in listOf("navigation_bar_height", "navigation_bar_frame_height")) {
            val id = system.getIdentifier(name, "dimen", "android")
            if (id == 0) continue
            val px = runCatching { system.getDimensionPixelSize(id) }.getOrDefault(0)
            if (px > 0) return px
        }
        return 0
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleOwner.onStart()
        ui.language = prefs.language
        ui.sendKey = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ==
            EditorInfo.IME_ACTION_SEND
        ui.shortcuts = prefs.shortcuts
        ui.clipboard = buildClipboard()
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = false
        ui.canRetry = pendingFile != null
        if (ui.phase != Phase.RECORDING && ui.phase != Phase.TRANSCRIBING) {
            ui.phase = if (pendingFile != null) Phase.ERROR else Phase.IDLE
            if (pendingFile != null) {
                ui.error = ui.error ?: "The last dictation could not be sent."
            }
        }
        registerClipboard()
        refreshClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleOwner.onStop()
        unregisterClipboard()
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = false
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

    private fun startRecording() {
        if (ui.phase == Phase.TRANSCRIBING || ui.phase == Phase.RECORDING) return
        if (!prefs.isConfigured) {
            showError("Set the token and server URL in Settings first.")
            return
        }
        if (prefs.usesLocalNetwork && !hasLocalNetworkPermission()) {
            showError("Local network permission is missing. Grant it in Settings or switch to Remote mode.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showError("Microphone permission is missing. Grant it in Settings.")
            return
        }

        val file = File(cacheDir, "dictation-${System.currentTimeMillis()}.wav")
        val recorder = WavRecorder(file)
        try {
            recorder.start()
        } catch (error: Exception) {
            file.delete()
            showError("Could not open the microphone: ${error.message}")
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
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = false
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)

        recordTicker = scope.launch {
            while (isActive && ui.phase == Phase.RECORDING) {
                val elapsed = ((SystemClock.elapsedRealtime() - recordStartedAt) / 1000).toInt()
                ui.elapsedSecs = elapsed
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
                showError("Recording could not be completed.")
                return@launch
            }
            val size = withContext(Dispatchers.IO) { file.length() }
            if (size <= 44) {
                file.delete()
                activeFile = null
                showError("The recording was empty.")
                return@launch
            }

            val language = ui.language
            val tone = prefs.tone
            val result = withContext(Dispatchers.IO) {
                runCatching { client.transcribe(file, language, tone) }
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
            showError("Set the token and server URL in Settings first.")
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
            val tone = prefs.tone
            val result = withContext(Dispatchers.IO) {
                runCatching { client.transcribe(file, language, tone) }
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
            showError("No speech was detected.")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            showError("The text field closed.")
            return
        }

        connection.commitText(text, 1)
        prefs.addRecentTranscript(text)
        ui.clipboard = buildClipboard()
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
        is ScribeException -> error.message ?: "Dictation failed."
        is java.io.IOException -> "Could not reach the server. Check the connection."
        else -> "Dictation failed: ${error.message ?: error.javaClass.simpleName}"
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
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = false
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
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = false
    }

    private fun toggleLanguagePicker() {
        if (ui.phase == Phase.RECORDING || ui.phase == Phase.TRANSCRIBING) return
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = !ui.showLanguages
    }

    private fun selectLanguage(code: String) {
        if (!isSupportedLanguage(code)) return
        prefs.language = code
        ui.language = code
        ui.showLanguages = false
    }

    private fun toggleLayout() {
        ui.showLanguages = false
        ui.layout = if (ui.layout == KeyboardMode.COMPACT) {
            KeyboardMode.FULL
        } else {
            KeyboardMode.COMPACT
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun undo() {
        val connection = currentInputConnection ?: return
        if (!connection.performContextMenuAction(android.R.id.undo)) {
            sendCtrl(connection, KeyEvent.KEYCODE_Z)
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun cut() {
        val connection = currentInputConnection ?: return
        if (connection.performContextMenuAction(android.R.id.selectAll)) {
            if (!connection.performContextMenuAction(android.R.id.cut)) {
                sendCtrl(connection, KeyEvent.KEYCODE_X)
            }
        } else {
            sendCtrl(connection, KeyEvent.KEYCODE_A)
            sendCtrl(connection, KeyEvent.KEYCODE_X)
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun copy() {
        val connection = currentInputConnection ?: return
        if (connection.performContextMenuAction(android.R.id.selectAll)) {
            if (!connection.performContextMenuAction(android.R.id.copy)) {
                sendCtrl(connection, KeyEvent.KEYCODE_C)
            }
        } else {
            sendCtrl(connection, KeyEvent.KEYCODE_A)
            sendCtrl(connection, KeyEvent.KEYCODE_C)
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun pasteCurrent() {
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0) {
            showError("Clipboard is empty.")
            return
        }
        scope.launch {
            val entry = withContext(Dispatchers.IO) { collectClips(clip).firstOrNull() }
            if (entry == null) {
                showError("There is nothing in the clipboard to paste.")
            } else {
                insertEntry(entry)
            }
        }
    }

    private fun moveCursor(direction: Int) {
        val connection = currentInputConnection ?: return
        val keyCode = if (direction < 0) {
            KeyEvent.KEYCODE_DPAD_LEFT
        } else {
            KeyEvent.KEYCODE_DPAD_RIGHT
        }
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toggleClipboard() {
        // Opening the panel mid-dictation hid the timer while the microphone
        // kept running; the top bar now dims the button, and this is the
        // matching gate for anything that still reaches it.
        if (ui.phase == Phase.RECORDING || ui.phase == Phase.TRANSCRIBING) return
        ui.showLanguages = false
        if (ui.clipboardView == ClipboardView.QUICK) {
            ui.clipboardView = ClipboardView.HIDDEN
            return
        }
        ui.clipboardView = ClipboardView.QUICK
        refreshClipboard()
        ui.clipboard = buildClipboard()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun expandClipboard() {
        if (ui.phase == Phase.RECORDING || ui.phase == Phase.TRANSCRIBING) return
        ui.showLanguages = false
        ui.clipboardView = ClipboardView.FULL
        refreshClipboard()
        ui.clipboard = buildClipboard()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun closeClipboard() {
        ui.clipboardView = ClipboardView.HIDDEN
        ui.showLanguages = false
    }

    private fun deleteEntry(entry: ClipboardEntry) {
        when (entry.kind) {
            ClipKind.TRANSCRIPT -> prefs.removeRecentTranscript(entry.text)
            else -> {
                clipHistory.removeAll { it.id == entry.id }
                entry.file?.delete()
            }
        }
        ui.clipboard = buildClipboard()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun clearClips() {
        clipHistory.forEach { it.file?.delete() }
        clipHistory.clear()
        ui.clipboard = buildClipboard()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun copyEntry(entry: ClipboardEntry) {
        if (entry.kind == ClipKind.IMAGE || entry.text.isBlank()) return
        clipboard.setPrimaryClip(ClipData.newPlainText("Scribe", entry.text))
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun insertEntry(entry: ClipboardEntry) {
        when (entry.kind) {
            ClipKind.TEXT, ClipKind.TRANSCRIPT -> {
                if (entry.text.isBlank()) return
                currentInputConnection?.commitText(entry.text, 1)
                ui.clipboardView = ClipboardView.HIDDEN
                ui.showLanguages = false
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            ClipKind.IMAGE -> {
                if (commitImage(entry)) {
                    ui.clipboardView = ClipboardView.HIDDEN
                    ui.showLanguages = false
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                } else {
                    showError("This field does not accept images.")
                }
            }
        }
    }

    private fun commitImage(entry: ClipboardEntry): Boolean {
        val file = entry.file ?: return false
        val connection = currentInputConnection ?: return false
        val editorInfo = currentInputEditorInfo ?: return false
        val mime = entry.mime.ifBlank { "image/*" }
        return runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val info = InputContentInfoCompat(
                uri,
                ClipDescription("image", arrayOf(mime)),
                null,
            )
            InputConnectionCompat.commitContent(
                connection,
                editorInfo,
                info,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
        }.getOrDefault(false)
    }

    private fun switchKeyboard() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
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

    private fun enter() {
        // Editors that declare a send/go/search action get it from Enter; a
        // plain or multiline field falls through to a real Enter key.
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            if (sendDefaultEditorAction(true)) {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                return
            }
        }
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun sendCtrl(connection: InputConnection, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON),
        )
        connection.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON),
        )
    }

    private fun insertText(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
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
        val clip = runCatching {
            if (!clipboard.hasPrimaryClip()) null else clipboard.primaryClip
        }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        clipJob?.cancel()
        clipJob = scope.launch {
            val entries = withContext(Dispatchers.IO) { collectClips(clip) }
            entries.forEach(::addClip)
            ui.clipboard = buildClipboard()
        }
    }

    private fun collectClips(clip: ClipData): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            if (uri != null) {
                val mime = runCatching { contentResolver.getType(uri) }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { clip.description.getMimeType(index).orEmpty() }
                if (mime.startsWith("image/")) {
                    val source = uri.toString()
                    val file = copyClipImage(uri, mime) ?: continue
                    entries.add(
                        ClipboardEntry(
                            id = UUID.randomUUID().toString(),
                            kind = ClipKind.IMAGE,
                            file = file,
                            mime = mime,
                            label = "Image",
                            source = source,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    continue
                }
            }
            val text = runCatching { item.coerceToText(this)?.toString()?.trim().orEmpty() }
                .getOrDefault("")
            if (text.isBlank() || text.length > MAX_CLIP_CHARS) continue
            entries.add(
                ClipboardEntry(
                    id = UUID.randomUUID().toString(),
                    kind = ClipKind.TEXT,
                    text = text,
                    label = "Clipboard",
                    source = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return entries
    }

    private fun copyClipImage(uri: Uri, mime: String): File? {
        val dir = File(cacheDir, "clipboard").apply { mkdirs() }
        val extension = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "img"
        }
        val file = File(
            dir,
            "clip-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.$extension",
        )
        val copied = runCatching {
            val input = contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { source ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_CLIP_BYTES) {
                            throw IllegalStateException("clipboard image is too large")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            true
        }.getOrDefault(false)
        if (!copied || file.length() == 0L) {
            file.delete()
            return null
        }
        return file
    }

    private fun addClip(entry: ClipboardEntry) {
        val removed = clipHistory.filter {
            it.kind == entry.kind && it.source == entry.source
        }
        removed.forEach {
            clipHistory.remove(it)
            it.file?.delete()
        }
        clipHistory.add(0, entry)
        while (clipHistory.size > MAX_CLIPS) {
            clipHistory.removeAt(clipHistory.lastIndex).file?.delete()
        }
    }

    private fun buildClipboard(): List<ClipboardEntry> {
        val recent = prefs.recentTranscripts().mapIndexed { index, text ->
            ClipboardEntry(
                id = "recent-$index",
                kind = ClipKind.TRANSCRIPT,
                text = text,
                label = "RECENT",
                source = text,
            )
        }
        return (clipHistory + recent).distinctBy { "${it.kind}:${it.source}" }
    }

    private fun cleanupOldRecordings() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        cacheDir.listFiles { file ->
            file.name.startsWith("dictation-") &&
                file.name.endsWith(".wav") &&
                file.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun cleanupClipboardCache() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        File(cacheDir, "clipboard").listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun haptic(@Suppress("UNUSED_PARAMETER") feedback: Int) {
        // Vibration is off by request; the call sites stay for future toggles.
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
        const val MAX_CLIPS = 8
        const val MAX_CLIP_BYTES = 20L * 1024 * 1024
    }
}
