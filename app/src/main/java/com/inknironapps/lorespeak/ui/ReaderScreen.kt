package com.inknironapps.lorespeak.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.inknironapps.lorespeak.AppGraph
import com.inknironapps.lorespeak.data.BookRecord
import com.inknironapps.lorespeak.data.LibraryStore
import com.inknironapps.lorespeak.playback.DownloadManager
import com.inknironapps.lorespeak.playback.DownloadRequest
import com.inknironapps.lorespeak.playback.PlaybackController
import com.inknironapps.lorespeak.playback.PlaybackService
import com.inknironapps.lorespeak.reader.ReaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val engine: ReaderEngine, val voiceCount: Int) : LoadState
    data class Failed(val message: String) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    record: BookRecord,
    store: LibraryStore,
    initialSpeed: Float = 1.25f,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var load by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var speed by remember { mutableFloatStateOf(initialSpeed) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controllerFuture by remember { mutableStateOf<ListenableFuture<MediaController>?>(null) }

    // The engine is owned by PlaybackController so it keeps playing when we leave this screen.
    // Playback is driven through a MediaController so Media3 manages the foreground notification and
    // keeps audio alive when backgrounded / screen-off.
    LaunchedEffect(record.id) {
        try {
            val ready = withContext(Dispatchers.Default) {
                val engine = PlaybackController.openBook(context, record, store, speed)
                engine.speed = speed
                LoadState.Ready(engine, AppGraph.tts(context).numSpeakers)
            }
            load = ready
            // Engine is loaded; connect a controller (the service reads the engine in onCreate).
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            controllerFuture = future
            future.addListener(
                { controller = runCatching { future.get() }.getOrNull() },
                ContextCompat.getMainExecutor(context),
            )
        } catch (t: Throwable) {
            load = LoadState.Failed(t.message ?: "Could not open this book")
        }
    }

    DisposableEffect(record.id) {
        onDispose {
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controller = null
        }
    }

    // System back returns to the library (playback keeps running in the background).
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { DownloadButton(record) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = load) {
                LoadState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Preparing book…", Modifier.padding(top = 12.dp))
                }
                is LoadState.Failed -> Text(s.message, Modifier.padding(24.dp), textAlign = TextAlign.Center)
                is LoadState.Ready -> Player(
                    engine = s.engine,
                    voiceCount = s.voiceCount,
                    initialVoice = record.voiceId,
                    speed = speed,
                    onToggle = {
                        // Drive playback through the MediaController so Media3 posts the media
                        // notification and manages the foreground service (survives screen-off).
                        val mediaController = controller
                        if (mediaController != null) {
                            if (s.engine.state.value.playing) mediaController.pause() else mediaController.play()
                        }
                    },
                    onSpeed = { newSpeed -> speed = newSpeed; s.engine.speed = newSpeed },
                    onVoice = { sid -> s.engine.setVoice(sid); store.setVoice(record.id, sid) },
                )
            }
        }
    }
}

@Composable
private fun DownloadButton(record: BookRecord) {
    val context = LocalContext.current
    val items by DownloadManager.items.collectAsState()
    val item = items.firstOrNull { it.bookId == record.id }
    val rendered = remember(items, record.id) {
        AppGraph.cache(context).renderedCount(record.id, record.voiceId)
    }
    val downloaded = record.totalSentences > 0 && rendered >= record.totalSentences

    when {
        item?.active == true -> Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { item.fraction },
                modifier = Modifier.size(34.dp),
                strokeWidth = 2.dp,
            )
            IconButton(onClick = { DownloadManager.cancel(record.id) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel download",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        downloaded -> IconButton(onClick = {}) {
            Icon(
                Icons.Default.DownloadDone,
                contentDescription = "Downloaded for offline",
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        else -> IconButton(
            onClick = {
                DownloadManager.enqueue(context, listOf(DownloadRequest(record.id, record.title)))
            },
        ) {
            Icon(Icons.Default.Download, contentDescription = "Download for offline")
        }
    }
}

@Composable
private fun Player(
    engine: ReaderEngine,
    voiceCount: Int,
    initialVoice: Int,
    speed: Float,
    onToggle: () -> Unit,
    onSpeed: (Float) -> Unit,
    onVoice: (Int) -> Unit,
) {
    val state by engine.state.collectAsState()
    var voice by remember { mutableStateOf(initialVoice) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            state.chapterTitle.ifBlank { "—" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        // Chapter text — full chapter, smaller font, current sentence highlighted, tap to jump.
        val sentences = remember(engine, state.chapterIndex) { engine.sentencesIn(state.chapterIndex) }
        val listState = rememberLazyListState()
        LaunchedEffect(state.chapterIndex, state.sentenceIndex) {
            if (sentences.isNotEmpty()) {
                listState.animateScrollToItem(state.sentenceIndex.coerceIn(0, sentences.size - 1))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(sentences) { index, sentence ->
                val isCurrent = index == state.sentenceIndex
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { engine.seekToChapterSentence(state.chapterIndex, index) }
                        .padding(vertical = 4.dp),
                )
            }
        }

        // Progress
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${(state.progress * 100).toInt()}%  ·  sentence ${state.globalSentence + 1} / ${state.totalSentences}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Transport controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { engine.skipChapter(-1) }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
            }
            IconButton(onClick = { engine.skipSentence(-1) }) {
                Text("−1", style = MaterialTheme.typography.titleMedium)
            }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { engine.skipSentence(1) }) {
                Text("+1", style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = { engine.skipChapter(1) }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
            }
        }

        // Speed
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                FilterChip(
                    selected = speed == s,
                    onClick = { onSpeed(s) },
                    label = { Text("${s}x") },
                )
            }
        }

        // Voice
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {
                    val next = (voice + 1) % voiceCount
                    voice = next
                    onVoice(next)
                },
                label = { Text("Voice ${voice + 1} / $voiceCount") },
            )
        }
    }
}
