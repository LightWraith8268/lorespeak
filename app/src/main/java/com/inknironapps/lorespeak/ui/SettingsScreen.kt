package com.inknironapps.lorespeak.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inknironapps.lorespeak.AppGraph
import com.inknironapps.lorespeak.data.SettingsStore
import com.inknironapps.lorespeak.tts.VoicePreviewPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var voiceCount by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<VoicePreviewPlayer?>(null) }
    var activeVoice by remember { mutableStateOf<Int?>(null) }
    var defaultVoice by remember { mutableIntStateOf(settings.defaultVoiceId) }
    var defaultSpeed by remember { mutableFloatStateOf(settings.defaultSpeed) }

    DisposableEffect(Unit) {
        scope.launch {
            val tts = withContext(Dispatchers.Default) { AppGraph.tts(context) }
            voiceCount = tts.numSpeakers
            preview = VoicePreviewPlayer(tts) { activeVoice = it }
        }
        onDispose { preview?.release() }
    }

    BackHandler {
        preview?.stop()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        preview?.stop()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Default speed", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                        FilterChip(
                            selected = defaultSpeed == s,
                            onClick = { defaultSpeed = s; settings.setDefaultSpeed(s) },
                            label = { Text("${s}x") },
                        )
                    }
                }
            }

            item {
                Text(
                    "Default voice",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Tap play to hear each voice, then pick your default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (voiceCount == 0) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items((0 until voiceCount).toList()) { sid ->
                    VoiceRow(
                        sid = sid,
                        selected = defaultVoice == sid,
                        isActive = activeVoice == sid,
                        previewPlayer = preview,
                        speed = defaultSpeed,
                        voiceCount = voiceCount,
                        onSelect = { defaultVoice = sid; settings.setDefaultVoice(sid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    sid: Int,
    selected: Boolean,
    isActive: Boolean,
    previewPlayer: VoicePreviewPlayer?,
    speed: Float,
    voiceCount: Int,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(
                "Voice ${sid + 1} / $voiceCount",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = {
                    if (isActive) previewPlayer?.stop() else previewPlayer?.preview(sid, speed)
                },
                enabled = previewPlayer != null,
            ) {
                Icon(
                    if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isActive) "Stop preview" else "Preview voice",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
