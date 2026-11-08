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
            onToggleLanguage = ::toggleLanguage,
            onToggleMode = ::toggleMode,
            onToggleClipboard = ::toggleClipboard,
            onCloseClipboard = ::closeClipboard,
            onInsertEntry = ::insertEntry,
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
        ui.mode = prefs.mode
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
        installSystemBarInsetListener()
        return view
    }

    override fun onWindowShown() {
        super.onWindowShown()
        installSystemBarInsetListener()
        updateSystemBarInset()
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
        ui.mode = prefs.mode
        ui.clipboard = buildClipboard()
        ui.showClipboard = false
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
        ui.showClipboard = false
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
            showError("Önce ayarlardan token'ı gir.")
            return
        }
        if (prefs.usesLocalNetwork && !hasLocalNetworkPermission()) {
            showError("Yerel ağ izni yok. Ayarlar'dan izin ver veya UZAK modunu seç.")
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
        ui.showClipboard = false
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
            showError("Önce ayarlardan token'ı gir.")
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
        is ScribeException -> error.message ?: "Dikte başarısız."
        is java.io.IOException -> "Sunucuya ulaşılamadı. Bağlantıyı kontrol et."
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
        ui.showClipboard = false
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
        ui.showClipboard = false
    }

    private fun toggleLanguage() {
        ui.language = if (ui.language == "auto") "tr" else "auto"
        prefs.language = ui.language
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toggleMode() {
        val next = if (prefs.mode == ConnectionMode.LOCAL) {
            ConnectionMode.REMOTE
        } else {
            ConnectionMode.LOCAL
        }
        prefs.mode = next
        ui.mode = next
        if (next == ConnectionMode.LOCAL && !hasLocalNetworkPermission()) {
            showError("Yerel ağ izni yok. Ayarlar'dan izin ver.")
        } else if (ui.phase == Phase.ERROR) {
            ui.error = null
            ui.phase = Phase.IDLE
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toggleClipboard() {
        ui.showClipboard = !ui.showClipboard
        if (ui.showClipboard) {
            refreshClipboard()
            ui.clipboard = buildClipboard()
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun closeClipboard() {
        ui.showClipboard = false
    }

    private fun insertEntry(entry: ClipboardEntry) {
        when (entry.kind) {
            ClipKind.TEXT, ClipKind.TRANSCRIPT -> {
                if (entry.text.isBlank()) return
                currentInputConnection?.commitText(entry.text, 1)
                ui.showClipboard = false
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            ClipKind.IMAGE -> {
                if (commitImage(entry)) {
                    ui.showClipboard = false
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                } else {
                    showError("Bu alan görsel kabul etmiyor.")
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
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
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
                            label = "Görsel",
                            source = source,
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
                    label = "Pano",
                    source = text,
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
                label = "SON",
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
        const val MAX_CLIPS = 8
        const val MAX_CLIP_BYTES = 20L * 1024 * 1024
    }
}
