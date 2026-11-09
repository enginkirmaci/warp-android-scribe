package com.engin.warpandroidscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import com.engin.warpandroidscribe.DictationLanguage
import com.engin.warpandroidscribe.DictationTone
import com.engin.warpandroidscribe.ServerSettings
import com.engin.warpandroidscribe.Vocabulary
import com.engin.warpandroidscribe.displayLanguages

@Composable
internal fun LanguagePickerDialog(
    selected: String,
    languages: List<DictationLanguage>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val ordered = remember(languages) { displayLanguages(languages) }
    val filtered = remember(query, ordered) {
        if (query.isBlank()) {
            ordered
        } else {
            ordered.filter { language ->
                language.name.contains(query, ignoreCase = true) ||
                    language.code.contains(query, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .clip(ScribeShape.Pill)
                .background(ScribeColors.Surface)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Language",
                    color = ScribeColors.Text,
                    fontSize = ScribeText.SheetTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = ScribeSize.Tap, minHeight = ScribeSize.Key)
                        .clip(ScribeShape.Chip)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Close",
                        color = ScribeColors.Muted,
                        fontSize = ScribeText.Body,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter languages") },
                placeholder = { Text("Turkish, tr, …") },
                singleLine = true,
                shape = ScribeShape.Surface,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Text(
                "${filtered.size} of ${ordered.size}",
                color = ScribeColors.Muted,
                fontSize = ScribeText.Caption,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                items(filtered, key = { it.code }) { language ->
                    LanguageRow(
                        language = language,
                        selected = language.code == selected,
                        onClick = { onSelect(language.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: DictationLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScribeSize.Key)
            .clip(ScribeShape.Chip)
            .background(if (selected) ScribeColors.Green.copy(alpha = 0.16f) else ScribeColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            language.name,
            color = if (selected) ScribeColors.Green else ScribeColors.Text,
            fontSize = ScribeText.Label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(language.code.uppercase(), color = ScribeColors.Muted, fontSize = ScribeText.Caption)
    }
}

@Composable
internal fun TonePicker(
    value: String,
    options: List<DictationTone>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == value }?.label ?: value
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            shape = ScribeShape.Surface,
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Field),
        ) {
            Text(
                current,
                color = ScribeColors.Text,
                fontSize = ScribeText.Label,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = ScribeColors.Muted, fontSize = ScribeText.Label)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            for (tone in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            tone.label,
                            color = if (tone.value == value) ScribeColors.Green else ScribeColors.Text,
                        )
                    },
                    onClick = {
                        onSelect(tone.value)
                        open = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VocabularyCard(
    vocabulary: Vocabulary?,
    busy: Boolean,
    onAddTerm: (String) -> Unit,
    onRemoveTerm: (String) -> Unit,
    onAddReplacement: (String, String) -> Unit,
    onRemoveReplacement: (String) -> Unit,
    onSave: () -> Unit,
) {
    var term by rememberSaveable { mutableStateOf("") }
    var original by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }

    if (vocabulary == null) {
        Text(
            "Load the server profile to edit the vocabulary.",
            color = ScribeColors.Muted,
            fontSize = ScribeText.Body,
        )
        return
    }

    Text("Words", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
    if (vocabulary.terms.isEmpty()) {
        Text("No words yet.", color = ScribeColors.Muted, fontSize = ScribeText.Body)
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (item in vocabulary.terms) {
                TermChip(item) { onRemoveTerm(item) }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = term,
            onValueChange = { term = it },
            label = { Text("Add a word") },
            singleLine = true,
            shape = ScribeShape.Surface,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                val value = term.trim()
                if (value.isNotEmpty()) {
                    onAddTerm(value)
                    term = ""
                }
            },
            enabled = !busy && term.isNotBlank(),
            shape = ScribeShape.Surface,
            modifier = Modifier.height(ScribeSize.Field),
        ) {
            Text("Add")
        }
    }

    Spacer(Modifier.height(2.dp))
    Text("Replacements", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
    if (vocabulary.replacements.isEmpty()) {
        Text(
            "No replacements yet. A replacement rewrites what the model heard.",
            color = ScribeColors.Muted,
            fontSize = ScribeText.Body,
        )
    } else {
        for (item in vocabulary.replacements) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ScribeSize.Tap)
                    .clip(ScribeShape.Chip)
                    .background(ScribeColors.Key)
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${item.original} → ${item.with}",
                    color = ScribeColors.Text,
                    fontSize = ScribeText.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = ScribeSize.Key, minHeight = ScribeSize.Key)
                        .clip(CircleShape)
                        .clickable { onRemoveReplacement(item.original) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = ScribeColors.Muted, fontSize = ScribeText.Label)
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = original,
            onValueChange = { original = it },
            label = { Text("Heard") },
            singleLine = true,
            shape = ScribeShape.Surface,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text("Write") },
            singleLine = true,
            shape = ScribeShape.Surface,
            modifier = Modifier.weight(1f),
        )
    }
    Button(
        onClick = {
            val from = original.trim()
            val to = replacement.trim()
            if (from.isNotEmpty() && to.isNotEmpty()) {
                onAddReplacement(from, to)
                original = ""
                replacement = ""
            }
        },
        enabled = !busy && original.isNotBlank() && replacement.isNotBlank(),
        shape = ScribeShape.Surface,
        modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
    ) {
        Text("Add replacement")
    }
    OutlinedButton(
        onClick = onSave,
        enabled = !busy,
        shape = ScribeShape.Surface,
        modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
    ) {
        Text(if (busy) "Saving…" else "Save vocabulary")
    }
    Text(
        "Saved in the server profile. Replacements apply to every mode; terms " +
            "match best with the Offline acoustic vocabulary.",
        color = ScribeColors.Muted,
        fontSize = ScribeText.Body,
    )
}

@Composable
private fun TermChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .height(ScribeSize.Chip)
            .clip(ScribeShape.Chip)
            .background(ScribeColors.Key)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = ScribeColors.Text,
            fontSize = ScribeText.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 190.dp),
        )
        Box(
            modifier = Modifier
                .sizeIn(minWidth = ScribeSize.Chip, minHeight = ScribeSize.Chip)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = ScribeColors.Muted, fontSize = ScribeText.Label)
        }
    }
}

@Composable
internal fun ProcessingCard(
    settings: ServerSettings?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (settings == null) {
        Text(
            "Load the server profile to choose how dictation is processed.",
            color = ScribeColors.Muted,
            fontSize = ScribeText.Body,
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    val current = settings.processingModes
        .firstOrNull { it.value == settings.processing }
        ?.label ?: settings.processing
    val target = settings.processingTarget
    val targetMode = settings.processingModes.firstOrNull { it.value == target }

    Text("Processing", color = ScribeColors.Text, fontSize = ScribeText.Body, fontWeight = FontWeight.Medium)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = !busy && target == null,
            shape = ScribeShape.Surface,
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Field),
        ) {
            Text(
                current,
                color = ScribeColors.Text,
                fontSize = ScribeText.Label,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = ScribeColors.Muted, fontSize = ScribeText.Label)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 230.dp),
        ) {
            for (mode in settings.processingModes) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                mode.label,
                                color = if (mode.value == settings.processing) {
                                    ScribeColors.Green
                                } else {
                                    ScribeColors.Text
                                },
                            )
                            val detail = if (mode.missing.isEmpty()) {
                                "Ready"
                            } else {
                                "Needs ${formatBytes(mode.downloadBytes)} · ${mode.missing.size} models"
                            }
                            Text(detail, color = ScribeColors.Muted, fontSize = ScribeText.Caption)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(mode.value)
                    },
                )
            }
        }
    }
    if (targetMode != null) {
        Text(
            "Downloading ${targetMode.label} models to the server.",
            color = ScribeColors.Text,
            fontSize = ScribeText.Body,
        )
        LinearProgressIndicator(
            progress = { settings.downloadPercent / 100f },
            color = ScribeColors.Green,
            trackColor = ScribeColors.Key,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Text(
            "${settings.downloadPercent}% · ${formatBytes(settings.downloadBytes)} / " +
                formatBytes(settings.downloadTotal),
            color = ScribeColors.Muted,
            fontSize = ScribeText.Body,
        )
        Text(
            "Dictation keeps using ${current} until the download finishes.",
            color = ScribeColors.Muted,
            fontSize = ScribeText.Body,
        )
        OutlinedButton(
            onClick = onCancel,
            enabled = !busy,
            shape = ScribeShape.Surface,
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
        ) {
            Text("Cancel download")
        }
    }
    settings.processingError?.let { error ->
        Text(error, color = ScribeColors.Error, fontSize = ScribeText.Body)
    }
    if (target == null) {
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            shape = ScribeShape.Surface,
            modifier = Modifier.fillMaxWidth().height(ScribeSize.Tap),
        ) {
            Text("Refresh server status")
        }
    }
    Text(
        "Cloud uses Superwhisper's servers. Offline and Offline Lite download " +
            "their models to the NAS and keep audio on the local network.",
        color = ScribeColors.Muted,
        fontSize = ScribeText.Body,
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / 1_000_000.0
    if (mb >= 1.0) return String.format(Locale.US, "%.0f MB", mb)
    return "${bytes / 1000} KB"
}
