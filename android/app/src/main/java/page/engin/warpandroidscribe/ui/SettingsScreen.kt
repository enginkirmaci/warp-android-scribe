package com.engin.warpandroidscribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.engin.warpandroidscribe.ScribeClient
import com.engin.warpandroidscribe.ScribePrefs

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { ScribePrefs(context) }
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf(prefs.host) }
    var port by rememberSaveable { mutableStateOf(prefs.port.toString()) }
    var token by rememberSaveable { mutableStateOf(prefs.token) }
    var language by rememberSaveable { mutableStateOf(prefs.language) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var imeStatus by remember { mutableStateOf(describeIme(context)) }
    var micGranted by remember { mutableStateOf(hasMicrophone(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeStatus = describeIme(context)
                micGranted = hasMicrophone(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        message = if (granted) "Mikrofon izni verildi." else "Mikrofon izni reddedildi."
    }

    fun save(): Boolean {
        val parsedPort = port.toIntOrNull()
        if (host.isBlank() || parsedPort == null || parsedPort !in 1..65535 || token.isBlank()) {
            message = "Host, port ve token zorunlu."
            return false
        }
        prefs.host = host
        prefs.port = parsedPort
        prefs.token = token
        prefs.language = language
        return true
    }

    fun testServer() {
        if (!save()) return
        scope.launch {
            busy = true
            val client = ScribeClient(prefs)
            message = withContext(Dispatchers.IO) {
                runCatching { client.health() }.fold(
                    onSuccess = { "Sunucu çalışıyor (sürüm $it)." },
                    onFailure = { "Sunucu hatası: ${it.message}" },
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Warp Scribe",
            color = ScribeColors.Text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Telefon → NAS köprüsü → Superwhisper S1-Voice",
            color = ScribeColors.Muted,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel("SUNUCU")
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(2f),
            )
        }
        if (host.trim() != ScribePrefs.DEFAULT_HOST) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Uyarı: düz HTTP yalnızca ${ScribePrefs.DEFAULT_HOST} için açık. " +
                    "Başka bir host için HTTPS gerekir.",
                color = ScribeColors.Error,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("DİL")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = language == "tr",
                onClick = { language = "tr" },
                label = { Text("Türkçe") },
            )
            FilterChip(
                selected = language == "auto",
                onClick = { language = "auto" },
                label = { Text("Otomatik") },
            )
        }
        Text(
            text = "Otomatik, kısa cümlelerde dili yanlış seçebilir; Türkçe daha güvenli.",
            color = ScribeColors.Muted,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = { if (save()) message = "Kaydedildi." },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Kaydet")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { testServer() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (busy) "Test ediliyor…" else "Kaydet ve sunucuyu test et")
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = if (it.contains("hata", ignoreCase = true) ||
                    it.contains("reddetti", ignoreCase = true) ||
                    it.contains("ulaşılamadı", ignoreCase = true) ||
                    it.contains("zorunlu", ignoreCase = true)
                ) {
                    ScribeColors.Error
                } else {
                    ScribeColors.Green
                },
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("KLAVYE")
        Text("Durum: $imeStatus", color = ScribeColors.Text, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Text("Klavyeyi etkinleştir")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val manager =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Text("Klavye seç")
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("MİKROFON")
        Text(
            text = if (micGranted) "İzin verildi." else "İzin yok; dikte başlatılamaz.",
            color = if (micGranted) ScribeColors.Text else ScribeColors.Error,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (hasMicrophone(context)) {
                    message = "Mikrofon izni zaten verilmiş."
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Text("Mikrofon izni ver")
        }

        Spacer(Modifier.height(26.dp))
        Text(
            text = "Kurulum: NAS'ta köprü servisi çalışır (8787), klavye sistem " +
                "ayarlarından etkinleştirilir ve giriş yöntemi olarak seçilir. " +
                "Token yalnızca bu uygulamanın özel alanında saklanır.",
            color = ScribeColors.Muted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "warp-android-scribe · ${appVersion(context)}",
            color = ScribeColors.Muted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = ScribeColors.Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(8.dp))
}

private fun describeIme(context: Context): String {
    // Settings.Secure.ENABLED_INPUT_METHODS is unreadable to apps targeting
    // Android 14+; the input-method service list is the public replacement.
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabled = runCatching {
        manager.enabledInputMethodList.any { it.packageName == context.packageName }
    }.getOrDefault(false)
    return if (enabled) "etkin" else "etkin değil"
}

private fun hasMicrophone(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"
