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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.inknironapps.lorespeak.BuildConfig
import com.inknironapps.lorespeak.data.SettingsStore
import com.inknironapps.lorespeak.playback.PlaybackController
import com.inknironapps.lorespeak.tts.VoicePreviewPlayer
import com.inknironapps.lorespeak.update.UpdateChecker
import com.inknironapps.lorespeak.update.UpdateInfo
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
    var voicesExpanded by remember { mutableStateOf(false) }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { voicesExpanded = !voicesExpanded }
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Default voice", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (voiceCount == 0) {
                                "Loading voices…"
                            } else {
                                "Voice ${defaultVoice + 1} of $voiceCount  ·  tap to change"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (voicesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (voicesExpanded) "Collapse voices" else "Expand voices",
                    )
                }
            }

            if (voicesExpanded) {
                if (voiceCount == 0) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
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
                            onSelect = {
                                defaultVoice = sid
                                settings.setDefaultVoice(sid)
                                PlaybackController.engine?.setVoice(sid)
                            },
                        )
                    }
                }
            }

            item { UpdateCard() }
            item { AboutCard() }
        }
    }
}

private sealed interface UpdateUi {
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val percent: Int) : UpdateUi
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Checking) }

    LaunchedEffect(Unit) {
        val info = UpdateChecker.check(BuildConfig.VERSION_NAME)
        ui = if (info == null) UpdateUi.UpToDate else UpdateUi.Available(info)
    }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (val state = ui) {
                UpdateUi.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking for updates…", modifier = Modifier.padding(start = 12.dp))
                }

                UpdateUi.UpToDate -> Text(
                    "You're on the latest version (v${BuildConfig.VERSION_NAME}).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is UpdateUi.Available -> Column {
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}  →  v${state.info.version}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                ui = UpdateUi.Downloading(0)
                                val file = UpdateChecker.download(context, state.info) { percent ->
                                    ui = UpdateUi.Downloading(percent)
                                }
                                if (file != null) {
                                    UpdateChecker.install(context, file)
                                    ui = UpdateUi.Available(state.info)
                                } else {
                                    ui = UpdateUi.UpToDate
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Download & install") }
                }

                is UpdateUi.Downloading -> Column {
                    Text("Downloading update… ${state.percent}%")
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "LoreSpeak — on-device audiobook reader by Ink & Iron Apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            LinkRow("Send feedback") {
                email(context, "support@inknironapps.com", "LoreSpeak feedback")
            }
            LinkRow("Report a security issue") {
                email(context, "security@inknironapps.com", "LoreSpeak security")
            }
            LinkRow("Privacy policy") {
                open(context, "https://inknironapps.com/privacy-policy.html")
            }
            LinkRow("Terms & conditions") {
                open(context, "https://inknironapps.com/terms.html")
            }
            Text(
                "LoreSpeak v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

private fun email(context: android.content.Context, address: String, subject: String) {
    val body = "\n\n---\nLoreSpeak v${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\n${android.os.Build.MODEL}"
    val uri = android.net.Uri.parse(
        "mailto:$address?subject=${android.net.Uri.encode(subject)}&body=${android.net.Uri.encode(body)}",
    )
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, uri)) }
}

private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
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
