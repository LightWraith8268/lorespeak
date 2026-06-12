package com.inknironapps.lorespeak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.inknironapps.lorespeak.data.BookRecord
import com.inknironapps.lorespeak.data.Importer
import com.inknironapps.lorespeak.data.SettingsStore
import com.inknironapps.lorespeak.playback.DownloadManager
import com.inknironapps.lorespeak.playback.DownloadRequest
import com.inknironapps.lorespeak.ui.DownloadsScreen
import com.inknironapps.lorespeak.ui.LibraryScreen
import com.inknironapps.lorespeak.ui.ReaderScreen
import com.inknironapps.lorespeak.ui.SettingsScreen
import com.inknironapps.lorespeak.ui.theme.LoreSpeakTheme
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoreSpeakTheme {
                AppRoot()
            }
        }
    }
}

private sealed interface Screen {
    data object Library : Screen
    data object Settings : Screen
    data object Downloads : Screen
    data class Reader(val bookId: String) : Screen
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AppGraph.store(context) }
    val settings = remember { AppGraph.settings(context) }
    val importer = remember { Importer(context, store) }
    val cache = remember { AppGraph.cache(context) }

    var books by remember { mutableStateOf(store.all()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    val snackbar = remember { SnackbarHostState() }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* notification is optional; ignore result */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun refresh() { books = store.all() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Default) { importer.import(uri, settings.defaultVoiceId) }
                }.onSuccess {
                    refresh()
                    snackbar.showSnackbar("Added \"${it.title}\"")
                }.onFailure {
                    snackbar.showSnackbar(it.message ?: "Import failed")
                }
            }
        }
    }

    when (val current = screen) {
        Screen.Library -> {
            androidx.compose.material3.Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
            ) { _ ->
                LibraryScreen(
                    books = books,
                    onImport = {
                        picker.launch(
                            arrayOf(
                                "application/epub+zip",
                                "text/markdown",
                                "text/plain",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                    onOpen = { screen = Screen.Reader(it.id) },
                    onDelete = {
                        AppGraph.cache(context).clearBook(it.id)
                        store.remove(it.id)
                        refresh()
                    },
                    onSettings = { screen = Screen.Settings },
                    onDownloads = { screen = Screen.Downloads },
                    onDownloadAll = {
                        val voiceId = settings.defaultVoiceId
                        val requests = books.filter { record ->
                            val rendered = cache.renderedCount(record.id, voiceId)
                            record.totalSentences <= 0 || rendered < record.totalSentences
                        }.map { DownloadRequest(it.id, it.title) }
                        DownloadManager.enqueue(context, requests)
                        screen = Screen.Downloads
                    },
                )
            }
        }

        Screen.Settings -> {
            SettingsScreen(settings = settings, onBack = { screen = Screen.Library })
        }

        Screen.Downloads -> {
            val items by DownloadManager.items.collectAsState()
            DownloadsScreen(
                items = items,
                onCancel = { DownloadManager.cancel(it) },
                onCancelAll = { DownloadManager.cancelAll() },
                onClearFinished = { DownloadManager.clearFinished() },
                onBack = { refresh(); screen = Screen.Library },
            )
        }

        is Screen.Reader -> {
            val record: BookRecord? = remember(current.bookId) { store.get(current.bookId) }
            if (record == null) {
                screen = Screen.Library
            } else {
                ReaderScreen(
                    record = record,
                    store = store,
                    initialSpeed = settings.defaultSpeed,
                    onBack = { refresh(); screen = Screen.Library },
                )
            }
        }
    }
}
