package emu.x360.mobile.dev

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import emu.x360.mobile.dev.ui.theme.X360RebuildTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingImportUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri = extractImportUri(intent)
        enableEdgeToEdge()
        setContent {
            X360RebuildTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val importFromIntent = pendingImportUri
                    val isoPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
                        if (uri != null) {
                            viewModel.importIso(uri)
                        }
                    }
                    if (importFromIntent != null) {
                        LaunchedEffect(importFromIntent) {
                            viewModel.importIso(importFromIntent)
                            pendingImportUri = null
                        }
                    }
                    MainShell(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onImportIso = { isoPicker.launch(arrayOf("*/*")) },
                        onRefreshLibrary = viewModel::refreshLibrary,
                        onRemoveLibraryEntry = viewModel::removeLibraryEntry,
                        onSetShowFpsCounter = viewModel::setShowFpsCounter,
                        onLaunchPlayer = { entryId ->
                            startActivity(PlayerActivity.intent(this, entryId))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingImportUri = extractImportUri(intent)
    }

    private fun extractImportUri(intent: Intent?): Uri? {
        val data = intent?.data ?: return null
        val pathish = data.lastPathSegment.orEmpty().lowercase()
        return if (pathish.contains(".iso")) {
            data
        } else {
            null
        }
    }
}

private enum class MainDestination {
    LIBRARY,
    OPTIONS,
    DEBUG,
    GAME_OPTIONS,
}

@Composable
private fun MainShell(
    state: MainUiState,
    onRefresh: () -> Unit,
    onImportIso: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRemoveLibraryEntry: (String) -> Unit,
    onSetShowFpsCounter: (Boolean) -> Unit,
    onLaunchPlayer: (String) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(MainDestination.LIBRARY) }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedEntry = state.libraryEntries.firstOrNull { it.id == selectedEntryId }
    val selectedOptions = selectedEntryId?.let { state.optionsFor(it) }

    if (destination == MainDestination.DEBUG) {
        val debugViewModel: DebugViewModel = viewModel()
        val debugState by debugViewModel.uiState.collectAsStateWithLifecycle()
        val isoPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
            if (uri != null) {
                debugViewModel.importIso(uri)
            }
        }
        DebugScreen(
            state = debugState,
            onBack = { destination = MainDestination.OPTIONS },
            onRefresh = debugViewModel::refresh,
            onRefreshLibrary = debugViewModel::refreshLibrary,
            onInstall = debugViewModel::installRuntime,
            onImportIso = { isoPicker.launch(arrayOf("*/*")) },
            onLaunchImportedTitle = debugViewModel::launchImportedTitle,
            onRemoveLibraryEntry = debugViewModel::removeLibraryEntry,
            onLaunchXeniaBringup = debugViewModel::launchXeniaBringup,
            onLaunchTurnipProbe = debugViewModel::launchTurnipProbe,
            onLaunchLavapipeProbe = debugViewModel::launchLavapipeProbe,
            onLaunchDynamicHello = debugViewModel::launchDynamicHello,
            onLaunchFexHello = debugViewModel::launchFexHello,
            onLaunchStub = debugViewModel::launchStub,
            onSetOverride = debugViewModel::setMesaOverride,
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF07131D), Color(0xFF0E2230), Color(0xFF163547)),
                ),
            ),
    ) {
        when (destination) {
            MainDestination.LIBRARY -> LibraryScreen(
                state = state,
                onImportIso = onImportIso,
                onRefreshLibrary = onRefreshLibrary,
                onOpenOptions = { destination = MainDestination.OPTIONS },
                onPlay = onLaunchPlayer,
                onOpenGameOptions = { entryId ->
                    selectedEntryId = entryId
                    destination = MainDestination.GAME_OPTIONS
                },
                onRemoveEntry = onRemoveLibraryEntry,
            )

            MainDestination.OPTIONS -> OptionsScreen(
                state = state,
                onBack = { destination = MainDestination.LIBRARY },
                onRefresh = onRefresh,
                onSetShowFpsCounter = onSetShowFpsCounter,
                onOpenDebug = { destination = MainDestination.DEBUG },
            )

            MainDestination.GAME_OPTIONS -> GameOptionsScreen(
                entry = selectedEntry,
                options = selectedOptions,
                onBack = { destination = MainDestination.LIBRARY },
                onPlay = selectedEntry?.id?.let { id -> { onLaunchPlayer(id) } } ?: {},
            )

            MainDestination.DEBUG -> Unit
        }
    }
}

@Composable
private fun LibraryScreen(
    state: MainUiState,
    onImportIso: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onOpenOptions: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenGameOptions: (String) -> Unit,
    onRemoveEntry: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProductHeader(
            title = "X360 Mobile",
            subtitle = "0.2.1 alpha",
            onOpenOptions = onOpenOptions,
        )

        ProductCard(
            title = "Library",
            accent = Color(0xFF7DFFE5),
        ) {
            Text(
                text = state.runtimeSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD9E3EA),
            )
            Text(
                text = state.lastAction,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAFC0CF),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImportIso, enabled = !state.isBusy) {
                    Text("Import ISO")
                }
                OutlinedButton(onClick = onRefreshLibrary, enabled = !state.isBusy) {
                    Text("Refresh")
                }
            }
        }

        if (state.libraryEntries.isEmpty()) {
            ProductCard(
                title = "No Games Yet",
                accent = Color(0xFFFFC56A),
            ) {
                Text(
                    text = "Import an Xbox 360 ISO to start building your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE6ECF2),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.libraryEntries, key = { it.id }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xCC122231)),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = entry.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFFF5F7FA),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Status: ${entry.status} | ${entry.sizeSummary}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCFD9E1),
                            )
                            Text(
                                text = "Title: ${entry.titleName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFAFC0CF),
                            )
                            Text(
                                text = "Last launch: ${entry.lastLaunchSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFAFC0CF),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { onPlay(entry.id) },
                                    enabled = !state.isBusy && state.runtimeReady && entry.status == "ready",
                                ) {
                                    Text("Play")
                                }
                                OutlinedButton(
                                    onClick = { onOpenGameOptions(entry.id) },
                                    enabled = !state.isBusy,
                                ) {
                                    Text("Options")
                                }
                                OutlinedButton(
                                    onClick = { onRemoveEntry(entry.id) },
                                    enabled = !state.isBusy,
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetShowFpsCounter: (Boolean) -> Unit,
    onOpenDebug: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Text(
                text = "Options",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFF5F7FA),
                fontWeight = FontWeight.Bold,
            )
        }

        ProductCard(
            title = "Display",
            accent = Color(0xFF47C0FF),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "FPS Counter",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFF5F7FA),
                    )
                    Text(
                        text = "Show the live framebuffer cadence in the top-left corner while playing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCFD9E1),
                    )
                }
                Switch(
                    checked = state.appSettings.showFpsCounter,
                    onCheckedChange = onSetShowFpsCounter,
                )
            }
        }

        ProductCard(
            title = "Render Scale",
            accent = Color(0xFFFFC56A),
        ) {
            Text(
                text = "True guest render scale is already part of the settings contract, but only 1.0x is active in this milestone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}, enabled = false) { Text("0.5x") }
                Button(onClick = {}, enabled = true) { Text("1.0x") }
                Button(onClick = {}, enabled = false) { Text("1.5x") }
                Button(onClick = {}, enabled = false) { Text("2.0x") }
            }
        }

        ProductCard(
            title = "Runtime",
            accent = Color(0xFF7DFFE5),
        ) {
            Text(
                text = state.runtimeSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Text(
                text = state.lastAction,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAFC0CF),
            )
            OutlinedButton(onClick = onRefresh, enabled = !state.isBusy) {
                Text("Refresh Status")
            }
        }

        ProductCard(
            title = "Debug",
            accent = Color(0xFFFF8B7B),
        ) {
            Text(
                text = "All bring-up tools, probes, logs and diagnostics stay available here without cluttering the main user shell.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Button(onClick = onOpenDebug, enabled = !state.isBusy) {
                Text("Open Debug Tools")
            }
        }
    }
}

@Composable
private fun GameOptionsScreen(
    entry: GameLibraryEntryUi?,
    options: GameOptionsUi?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Text(
                text = "Game Options",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFF5F7FA),
                fontWeight = FontWeight.Bold,
            )
        }

        if (entry == null || options == null) {
            ProductCard(title = "Unavailable", accent = Color(0xFFFF8B7B)) {
                Text(
                    text = "This library entry is no longer available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE6ECF2),
                )
            }
            return
        }

        ProductCard(title = entry.displayName, accent = Color(0xFF7DFFE5)) {
            Text(
                text = "Title: ${options.titleName}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Text(
                text = "Render scale override: ${options.renderScaleOverrideLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Text(
                text = "FPS counter override: ${options.fpsCounterOverrideLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Text(
                text = "Presentation backend override: ${options.presentationBackendOverrideLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE6ECF2),
            )
            Text(
                text = options.note,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAFC0CF),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay, enabled = entry.status == "ready") {
                    Text("Play")
                }
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("Coming Soon")
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(
    title: String,
    subtitle: String,
    onOpenOptions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color(0xFFF5F7FA),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFAFC0CF),
            )
        }
        OutlinedButton(onClick = onOpenOptions) {
            Text("Options")
        }
    }
}

@Composable
private fun ProductCard(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC11212E)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                content()
            },
        )
    }
}
