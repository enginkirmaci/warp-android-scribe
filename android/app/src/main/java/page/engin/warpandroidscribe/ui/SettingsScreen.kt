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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.engin.warpandroidscribe.ConnectionMode
import com.engin.warpandroidscribe.SUPPORTED_LANGUAGES
import com.engin.warpandroidscribe.ScribeClient
import com.engin.warpandroidscribe.ScribePrefs
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
    var shortcuts by rememberSaveable {
        mutableStateOf(prefs.shortcuts.joinToString(", "))
    }
    var localUrl by rememberSaveable { mutableStateOf(prefs.localUrl) }
    var remoteUrl by rememberSaveable { mutableStateOf(prefs.remoteUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var micGranted by remember { mutableStateOf(hasMicrophone(context)) }
    var localGranted by remember { mutableStateOf(hasLocalNetwork(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeEnabled = isImeEnabled(context)
                micGranted = hasMicrophone(context)
                localGranted = hasLocalNetwork(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            message = withContext(Dispatchers.IO) {
                runCatching { client.health() }.fold(
                    onSuccess = { "Server is running (version $it)." },
                    onFailure = { "Server error: ${it.message}" },
                )
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScribeColors.Background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp),
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
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Dictation for Android",
                    color = ScribeColors.Muted,
                    fontSize = 13.sp,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (imeEnabled) ScribeColors.Green.copy(alpha = 0.15f) else ScribeColors.Key)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (imeEnabled) "ACTIVE" else "SETUP",
                    color = if (imeEnabled) ScribeColors.Green else ScribeColors.Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
            }
        }

        SettingsCard("SERVER") {
            Text("Mode", color = ScribeColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
            OutlinedTextField(
                value = localUrl,
                onValueChange = { localUrl = it },
                label = { Text("Local URL") },
                placeholder = { Text("http://<nas-lan>:8787") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = remoteUrl,
                onValueChange = { remoteUrl = it },
                label = { Text("Remote URL") },
                placeholder = { Text("https://<nas-host>") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "The same token is used by both modes. Both URLs are stored; " +
                    "only the selected mode is used.",
                color = ScribeColors.Muted,
                fontSize = 11.sp,
            )
        }

        SettingsCard("DICTATION") {
            Text("Language", color = ScribeColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { languageMenuOpen = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        text = languageName(language),
                        color = ScribeColors.Text,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                    Text("▾", color = ScribeColors.Muted, fontSize = 14.sp)
                }
                DropdownMenu(
                    expanded = languageMenuOpen,
                    onDismissRequest = { languageMenuOpen = false },
                    modifier = Modifier.widthIn(min = 220.dp).heightIn(max = 360.dp),
                ) {
                    for (item in SUPPORTED_LANGUAGES) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = item.name,
                                    color = if (language == item.code) {
                                        ScribeColors.Green
                                    } else {
                                        ScribeColors.Text
                                    },
                                )
                            },
                            onClick = {
                                language = item.code
                                languageMenuOpen = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = shortcuts,
                onValueChange = { shortcuts = it },
                label = { Text("Shortcuts") },
                placeholder = { Text("/goal, /compact") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Comma-separated commands shown above the keyboard. Tap one to insert it. " +
                    "Leave empty to hide the row.",
                color = ScribeColors.Muted,
                fontSize = 11.sp,
            )
        }

        SettingsCard("KEYBOARD") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (imeEnabled) ScribeColors.Green else ScribeColors.Muted),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Warp Scribe keyboard",
                        color = ScribeColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (imeEnabled) {
                            "Enabled and ready"
                        } else {
                            "Enable it to start dictating"
                        },
                        color = if (imeEnabled) ScribeColors.Green else ScribeColors.Muted,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (imeEnabled) {
                                ScribeColors.Green.copy(alpha = 0.15f)
                            } else {
                                ScribeColors.Key
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (imeEnabled) "ON" else "OFF",
                        color = if (imeEnabled) ScribeColors.Green else ScribeColors.Muted,
                        fontSize = 9.sp,
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
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Choose keyboard", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Keyboard settings", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Enable keyboard", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val manager =
                                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            manager.showInputMethodPicker()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Choose keyboard", fontSize = 12.sp)
                    }
                }
            }
        }

        SettingsCard("PERMISSIONS") {
            Text("Local network", color = ScribeColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            val localOk = Build.VERSION.SDK_INT < 37 || localGranted
            Text(
                text = when {
                    Build.VERSION.SDK_INT < 37 -> "Not required on this Android version."
                    mode == ConnectionMode.REMOTE -> "Not required in Remote mode."
                    localOk -> "Granted."
                    else -> "Required for Local mode."
                },
                color = if (localOk || mode == ConnectionMode.REMOTE) ScribeColors.Muted else ScribeColors.Error,
                fontSize = 11.sp,
            )
            if (Build.VERSION.SDK_INT >= 37 && !localGranted) {
                Button(
                    onClick = { localPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("Grant local network", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("Microphone", color = ScribeColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                text = if (micGranted) "Granted." else "Missing; dictation cannot start.",
                color = if (micGranted) ScribeColors.Muted else ScribeColors.Error,
                fontSize = 11.sp,
            )
            if (!micGranted) {
                Button(
                    onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("Grant microphone", fontSize = 12.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (save()) message = "Saved." },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text("Save", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { testServer() },
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(if (busy) "Testing…" else "Save & test", fontSize = 13.sp)
            }
        }

        message?.let {
            Text(
                text = it,
                color = if (it.contains("error", ignoreCase = true) ||
                    it.contains("required", ignoreCase = true) ||
                    it.contains("denied", ignoreCase = true) ||
                    it.contains("failed", ignoreCase = true) ||
                    it.contains("missing", ignoreCase = true) ||
                    it.contains("could not", ignoreCase = true)
                ) {
                    ScribeColors.Error
                } else {
                    ScribeColors.Green
                },
                fontSize = 12.sp,
            )
        }

        Text(
            text = "Server URLs and the token are stored in this app's private storage. " +
                "The keyboard uses the selected mode and language the next time it opens.",
            color = ScribeColors.Muted,
            fontSize = 11.sp,
        )
        Text(
            text = "warp-android-scribe · ${appVersion(context)}",
            color = ScribeColors.Muted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ScribeColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            color = ScribeColors.Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        content()
    }
}

private fun isImeEnabled(context: Context): Boolean {
    // Settings.Secure.ENABLED_INPUT_METHODS is unreadable to apps targeting
    // Android 14+; the input-method service list is the public replacement.
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return runCatching {
        manager.enabledInputMethodList.any { it.packageName == context.packageName }
    }.getOrDefault(false)
}

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
