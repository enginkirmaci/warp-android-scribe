package com.engin.warpandroidscribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.engin.warpandroidscribe.ConnectionMode
import com.engin.warpandroidscribe.DictationTone
import com.engin.warpandroidscribe.Replacement
import com.engin.warpandroidscribe.SUPPORTED_LANGUAGES
import com.engin.warpandroidscribe.ScribeClient
import com.engin.warpandroidscribe.ScribePrefs
import com.engin.warpandroidscribe.ServerSettings
import com.engin.warpandroidscribe.TONES
import com.engin.warpandroidscribe.Vocabulary
import com.engin.warpandroidscribe.isSupportedLanguage
import com.engin.warpandroidscribe.languageName

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { ScribePrefs(context) }
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(prefs.mode) }
    var token by rememberSaveable { mutableStateOf(prefs.token) }
    var language by rememberSaveable { mutableStateOf(prefs.language) }
    var tone by rememberSaveable { mutableStateOf(prefs.tone) }
    var shortcuts by rememberSaveable {
        mutableStateOf(prefs.shortcuts.joinToString(", "))
    }
    var localUrl by rememberSaveable { mutableStateOf(prefs.localUrl) }
    var remoteUrl by rememberSaveable { mutableStateOf(prefs.remoteUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var languagePickerOpen by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf(SUPPORTED_LANGUAGES) }
    var server by remember { mutableStateOf<ServerSettings?>(null) }
    var vocabulary by remember { mutableStateOf<Vocabulary?>(null) }
    var vocabularyDirty by remember { mutableStateOf(false) }
    var serverBusy by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var editingInactiveUrl by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var imeActive by remember { mutableStateOf(isImeActive(context)) }
    var micGranted by remember { mutableStateOf(hasMicrophone(context)) }
    var localGranted by remember { mutableStateOf(hasLocalNetwork(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeEnabled = isImeEnabled(context)
                imeActive = isImeActive(context)
                micGranted = hasMicrophone(context)
                localGranted = hasLocalNetwork(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The inactive URL is one line until the user asks to edit it, and the
    // choice of mode resets that, so the field the mode uses is always the
    // one on screen.
    LaunchedEffect(mode) { editingInactiveUrl = false }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        message = if (granted) "Microphone permission granted." else "Microphone permission denied."
    }
    val localPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localGranted = granted
        message = if (granted) "Local network permission granted." else "Local network permission denied."
    }

    fun selectedUrl(): String = if (mode == ConnectionMode.LOCAL) localUrl else remoteUrl

    fun validate(): Boolean {
        if (token.isBlank()) {
            message = "Token is required."
            return false
        }
        val url = selectedUrl().trim()
        if (url.isBlank()) {
            message = "The ${if (mode == ConnectionMode.LOCAL) "Local" else "Remote"} URL is required."
            return false
        }
        val normalized = url.lowercase()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            message = "The server URL must start with http:// or https://."
            return false
        }
        if (mode == ConnectionMode.REMOTE && !normalized.startsWith("https://")) {
            message = "The Remote URL must use HTTPS."
            return false
        }
        if (!isSupportedLanguage(language)) {
            message = "Choose a supported dictation language."
            return false
        }
        return true
    }

    fun save(): Boolean {
        if (!validate()) return false
        if (!prefs.saveSettings(
                mode = mode,
                token = token,
                language = language,
                tone = tone,
                shortcuts = shortcuts,
                localUrl = localUrl,
                remoteUrl = remoteUrl,
            )
        ) {
            message = "Settings could not be saved."
            return false
        }
        return true
    }

    fun applyServer(snapshot: ServerSettings) {
        server = snapshot
        if (!vocabularyDirty) {
            vocabulary = snapshot.vocabulary
        }
        if (snapshot.languages.isNotEmpty()) {
            languages = snapshot.languages
        }
    }

    fun refreshServer(showMessage: Boolean = false) {
        if (!prefs.isConfigured) {
            if (showMessage) {
                serverMessage = "Save the token and server URL first."
            }
            return
        }
        scope.launch {
            serverBusy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { ScribeClient(prefs).settings() }
            }
            result.onSuccess { snapshot ->
                applyServer(snapshot)
                if (showMessage) serverMessage = "Server profile loaded."
            }.onFailure { error ->
                serverMessage = "Server profile unavailable: ${error.message}"
            }
            serverBusy = false
        }
    }

    LaunchedEffect(Unit) {
        refreshServer()
    }

    LaunchedEffect(server?.processingTarget) {
        while (server?.processingTarget != null) {
            delay(2_000)
            val next = withContext(Dispatchers.IO) {
                runCatching { ScribeClient(prefs).settings() }.getOrNull()
            }
            if (next != null) applyServer(next)
        }
    }

    fun testServer() {
        if (!save()) return
        if (mode == ConnectionMode.LOCAL &&
            Build.VERSION.SDK_INT >= 37 &&
            !hasLocalNetwork(context)
        ) {
            message = "Local network permission is required: grant it below or use Remote."
            return
        }
        scope.launch {
            busy = true
            val client = ScribeClient(prefs)
            val result = withContext(Dispatchers.IO) {
                runCatching { client.health() }.fold(
                    onSuccess = { "Server is running (version $it)." },
                    onFailure = { "Server error: ${it.message}" },
                )
            }
            busy = false
            message = result
            if (result.startsWith("Server is running")) {
                refreshServer(showMessage = true)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(ScribeColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 18.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Warp Scribe",
                        color = ScribeColors.Text,
                        fontSize = ScribeText.PageTitle,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Dictation for Android",
                        color = ScribeColors.Muted,
                        fontSize = ScribeText.Label,
                    )
                }
                StateBadge(active = imeActive, enabled = imeEnabled)
            }

            SettingsCard("SERVER") {
                Text("Mode", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ConnectionMode.LOCAL,
                        onClick = { mode = ConnectionMode.LOCAL },
                        label = { Text("Local") },
                    )
                    FilterChip(
                        selected = mode == ConnectionMode.REMOTE,
                        onClick = { mode = ConnectionMode.REMOTE },
                        label = { Text("Remote") },
                    )
                }
                val localActive = mode == ConnectionMode.LOCAL
                OutlinedTextField(
                    value = if (localActive) localUrl else remoteUrl,
                    onValueChange = {
                        if (localActive) localUrl = it else remoteUrl = it
                    },
                    label = { Text(if (localActive) "Local URL" else "Remote URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = ScribeShape.Surface,
                    modifier = Modifier.fillMaxWidth(),
                )
                val otherLabel = if (localActive) "Remote URL" else "Local URL"
                val otherValue = if (localActive) remoteUrl else localUrl
                if (editingInactiveUrl) {
                    OutlinedTextField(
                        value = otherValue,
                        onValueChange = {
                            if (localActive) remoteUrl = it else localUrl = it
                        },
                        label = { Text(otherLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = ScribeShape.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ScribeSize.Tap)
                            .clip(ScribeShape.Surface)
                            .background(ScribeColors.Key)
                            .combinedClickable { editingInactiveUrl = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "$otherLabel · not in use",
                                color = ScribeColors.Muted,
                                fontSize = ScribeText.Caption,
                            )
                            Text(
                                text = otherValue,
                                color = ScribeColors.Muted,
                                fontSize = ScribeText.Body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "Edit",
                            color = ScribeColors.Text,
                            fontSize = ScribeText.Body,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = ScribeShape.Surface,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { refreshServer(showMessage = true) },
                    enabled = !serverBusy,
                    shape = ScribeShape.Surface,
                    modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
                ) {
                    Text(if (serverBusy) "Loading profile…" else "Refresh server profile")
                }
                serverMessage?.let {
                    Text(
                        it,
                        color = if (isErrorMessage(it)) ScribeColors.Error else ScribeColors.Muted,
                        fontSize = ScribeText.Body,
                    )
                }
            }

            SettingsCard("DICTATION") {
                Text("Language", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
                OutlinedButton(
                    onClick = { languagePickerOpen = true },
                    shape = ScribeShape.Surface,
                    modifier = Modifier.fillMaxWidth().height(ScribeSize.Field),
                ) {
                    Text(
                        text = languageName(language),
                        color = ScribeColors.Text,
                        fontSize = ScribeText.Label,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                    Text("▾", color = ScribeColors.Muted, fontSize = ScribeText.Label)
                }
                Text("Tone", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
                TonePicker(
                    value = tone,
                    options = toneChoices(server),
                    onSelect = { tone = it },
                )
                Text(
                    "Casual is lowercase, formal expands contractions. The cleanup model applies it.",
                    color = ScribeColors.Muted,
                    fontSize = ScribeText.Body,
                )
                if (languagePickerOpen) {
                    LanguagePickerDialog(
                        selected = language,
                        languages = languages,
                        onSelect = {
                            language = it
                            languagePickerOpen = false
                        },
                        onDismiss = { languagePickerOpen = false },
                    )
                }
                OutlinedTextField(
                    value = shortcuts,
                    onValueChange = { shortcuts = it },
                    label = { Text("Quick inserts") },
                    placeholder = { Text("/goal, /compact") },
                    singleLine = true,
                    shape = ScribeShape.Surface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Comma-separated words or commands shown above the keyboard. " +
                        "Tap one to insert it. Leave empty to hide the row.",
                    color = ScribeColors.Muted,
                    fontSize = ScribeText.Body,
                )
            }

            SettingsCard("VOCABULARY") {
                VocabularyCard(
                    vocabulary = vocabulary,
                    busy = serverBusy,
                    onAddTerm = { term ->
                        vocabulary?.let { current ->
                            vocabularyDirty = true
                            vocabulary = current.copy(
                                terms = (current.terms + term).distinct(),
                            )
                        }
                    },
                    onRemoveTerm = { term ->
                        vocabulary?.let { current ->
                            vocabularyDirty = true
                            vocabulary = current.copy(terms = current.terms - term)
                        }
                    },
                    onAddReplacement = { original, with ->
                        vocabulary?.let { current ->
                            vocabularyDirty = true
                            vocabulary = current.copy(
                                replacements = current.replacements
                                    .filterNot { it.original == original } +
                                    Replacement(original, with),
                            )
                        }
                    },
                    onRemoveReplacement = { original ->
                        vocabulary?.let { current ->
                            vocabularyDirty = true
                            vocabulary = current.copy(
                                replacements = current.replacements
                                    .filterNot { it.original == original },
                            )
                        }
                    },
                    onSave = {
                        vocabulary?.let { current ->
                            scope.launch {
                                serverBusy = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { ScribeClient(prefs).saveVocabulary(current) }
                                }
                                result.onSuccess { snapshot ->
                                    vocabularyDirty = false
                                    applyServer(snapshot)
                                    serverMessage = "Vocabulary saved."
                                }.onFailure { error ->
                                    serverMessage = "Vocabulary was not saved: ${error.message}"
                                }
                                serverBusy = false
                            }
                        }
                    },
                )
            }

            SettingsCard("PROCESSING") {
                ProcessingCard(
                    settings = server,
                    busy = serverBusy,
                    onSelect = { selected ->
                        if (selected != server?.processing) {
                            scope.launch {
                                serverBusy = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { ScribeClient(prefs).setProcessing(selected) }
                                }
                                result.onSuccess { snapshot ->
                                    applyServer(snapshot)
                                    serverMessage = "Processing set to ${snapshot.processing}."
                                }.onFailure { error ->
                                    serverMessage = "Processing change failed: ${error.message}"
                                }
                                serverBusy = false
                            }
                        }
                    },
                    onCancel = {
                        scope.launch {
                            serverBusy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ScribeClient(prefs).setProcessing("cloud") }
                            }
                            result.onSuccess { applyServer(it) }
                                .onFailure { serverMessage = "Cancel failed: ${it.message}" }
                            serverBusy = false
                        }
                    },
                    onRefresh = { refreshServer(showMessage = true) },
                )
            }

            SettingsCard("KEYBOARD") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (imeActive) ScribeColors.Green else ScribeColors.Muted),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Warp Scribe keyboard",
                            color = ScribeColors.Text,
                            fontSize = ScribeText.Label,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                imeActive -> "In use as the current keyboard"
                                imeEnabled -> "Enabled, not the current keyboard"
                                else -> "Enable it to start dictating"
                            },
                            color = if (imeActive) ScribeColors.Green else ScribeColors.Muted,
                            fontSize = ScribeText.Body,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(ScribeShape.Pill)
                            .background(
                                if (imeEnabled) {
                                    ScribeColors.Green.copy(alpha = 0.15f)
                                } else {
                                    ScribeColors.Key
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = if (imeEnabled) "ON" else "OFF",
                            color = if (imeEnabled) ScribeColors.Green else ScribeColors.Muted,
                            fontSize = ScribeText.Caption,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imeEnabled) {
                        Button(
                            onClick = {
                                val manager =
                                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                manager.showInputMethodPicker()
                            },
                            shape = ScribeShape.Surface,
                            modifier = Modifier.weight(1f).height(ScribeSize.Tap),
                        ) {
                            Text("Choose keyboard")
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            },
                            shape = ScribeShape.Surface,
                            modifier = Modifier.weight(1f).height(ScribeSize.Tap),
                        ) {
                            Text("Keyboard settings")
                        }
                    } else {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            },
                            shape = ScribeShape.Surface,
                            modifier = Modifier.weight(1f).height(ScribeSize.Tap),
                        ) {
                            Text("Enable keyboard")
                        }
                        OutlinedButton(
                            onClick = {
                                val manager =
                                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                manager.showInputMethodPicker()
                            },
                            shape = ScribeShape.Surface,
                            modifier = Modifier.weight(1f).height(ScribeSize.Tap),
                        ) {
                            Text("Choose keyboard")
                        }
                    }
                }
            }

            SettingsCard("PERMISSIONS") {
                Text("Local network", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
                val localOk = Build.VERSION.SDK_INT < 37 || localGranted
                Text(
                    text = when {
                        Build.VERSION.SDK_INT < 37 -> "Not required on this Android version."
                        mode == ConnectionMode.REMOTE -> "Not required in Remote mode."
                        localOk -> "Granted."
                        else -> "Required for Local mode."
                    },
                    color = if (localOk || mode == ConnectionMode.REMOTE) ScribeColors.Muted else ScribeColors.Error,
                    fontSize = ScribeText.Body,
                )
                if (Build.VERSION.SDK_INT >= 37 && !localGranted) {
                    Button(
                        onClick = { localPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
                        shape = ScribeShape.Surface,
                        modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
                    ) {
                        Text("Grant local network")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("Microphone", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
                Text(
                    text = if (micGranted) "Granted." else "Missing; dictation cannot start.",
                    color = if (micGranted) ScribeColors.Muted else ScribeColors.Error,
                    fontSize = ScribeText.Body,
                )
                if (!micGranted) {
                    Button(
                        onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        shape = ScribeShape.Surface,
                        modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
                    ) {
                        Text("Grant microphone")
                    }
                }
            }

            Text(
                text = "Server URLs and the token are stored in this app's private storage. " +
                    "The keyboard uses the selected mode, language and tone the next time it opens.",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Body,
            )
            Text(
                text = "warp-android-scribe · ${appVersion(context)}",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Caption,
            )
        }

        SaveBar(
            message = message,
            busy = busy,
            onSave = { if (save()) message = "Saved." },
            onTest = { testServer() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Enabled is not the same as being the keyboard that takes input. */
@Composable
private fun StateBadge(active: Boolean, enabled: Boolean) {
    val label = when {
        active -> "ACTIVE"
        enabled -> "ENABLED"
        else -> "SETUP"
    }
    Box(
        modifier = Modifier
            .clip(ScribeShape.Pill)
            .background(
                if (active) ScribeColors.Green.copy(alpha = 0.15f) else ScribeColors.Key,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = when {
                active -> ScribeColors.Green
                enabled -> ScribeColors.Text
                else -> ScribeColors.Muted
            },
            fontSize = ScribeText.Caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * Save sat at the end of a seven-card scroll, three screens below the token
 * field it saves. It lives at the bottom of the screen now, with the result
 * message beside it.
 */
@Composable
private fun SaveBar(
    message: String?,
    busy: Boolean,
    onSave: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ScribeColors.Surface)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message?.let {
            Text(
                text = it,
                color = if (isErrorMessage(it)) ScribeColors.Error else ScribeColors.Green,
                fontSize = ScribeText.Caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                shape = ScribeShape.Surface,
                modifier = Modifier.weight(1f).height(ScribeSize.Tap),
            ) {
                Text("Save")
            }
            OutlinedButton(
                onClick = onTest,
                enabled = !busy,
                shape = ScribeShape.Surface,
                modifier = Modifier.weight(1f).height(ScribeSize.Tap),
            ) {
                Text(if (busy) "Testing…" else "Save & test")
            }
        }
    }
}

private fun isErrorMessage(message: String): Boolean =
    message.contains("error", ignoreCase = true) ||
        message.contains("required", ignoreCase = true) ||
        message.contains("denied", ignoreCase = true) ||
        message.contains("failed", ignoreCase = true) ||
        message.contains("missing", ignoreCase = true) ||
        message.contains("could not", ignoreCase = true)

private fun toneChoices(settings: ServerSettings?): List<DictationTone> =
    settings?.tones
        ?.takeIf { it.isNotEmpty() }
        ?.map { DictationTone(it.value, it.label) }
        ?: TONES

private fun isImeEnabled(context: Context): Boolean {
    // Settings.Secure.ENABLED_INPUT_METHODS is unreadable to apps targeting
    // Android 14+; the input-method service list is the public replacement.
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return runCatching {
        manager.enabledInputMethodList.any { it.packageName == context.packageName }
    }.getOrDefault(false)
}

private fun isImeActive(context: Context): Boolean = runCatching {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    )
    current?.startsWith("${context.packageName}/") == true
}.getOrDefault(false)

private fun hasMicrophone(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun hasLocalNetwork(context: Context): Boolean =
    Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"
