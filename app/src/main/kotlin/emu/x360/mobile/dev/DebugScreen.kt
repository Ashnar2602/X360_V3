package emu.x360.mobile.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import emu.x360.mobile.dev.bootstrap.DiagnosticLaunchProfile
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch

@Composable
internal fun DebugScreen(
    state: DebugUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onInstall: () -> Unit,
    onImportIso: () -> Unit,
    onLaunchImportedTitle: (String) -> Unit,
    onLaunchImportedTitleDiagnostic: (String, DiagnosticLaunchProfile) -> Unit,
    onOpenFreezeLabRun: (String, DiagnosticLaunchProfile, Boolean, Boolean) -> Unit,
    onCaptureCurrentFreezeReport: () -> Unit,
    onRemoveLibraryEntry: (String) -> Unit,
    onLaunchXeniaBringup: () -> Unit,
    onLaunchTurnipProbe: () -> Unit,
    onLaunchLavapipeProbe: () -> Unit,
    onLaunchDynamicHello: () -> Unit,
    onLaunchFexHello: () -> Unit,
    onLaunchStub: () -> Unit,
    onSetOverride: (MesaRuntimeBranch) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF07121C), Color(0xFF102433), Color(0xFF1E3645)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, enabled = !state.isBusy) {
                    Text("Back")
                }
                Text(
                    text = "Debug Tools",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5F7FA),
                )
            }
            Text(
                text = "Full runtime diagnostics, bring-up controls, logs and framebuffer preview stay here while the user-facing shell moves to library + options.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFD6DEE7),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onInstall, enabled = !state.isBusy) {
                    Text("Install / Extract")
                }
                Button(onClick = onLaunchXeniaBringup, enabled = !state.isBusy) {
                    Text("Launch Xenia Bring-up")
                }
            }

            DebugLibraryCard(
                entries = state.libraryEntries,
                isBusy = state.isBusy,
                onImportIso = onImportIso,
                onRefreshLibrary = onRefreshLibrary,
                onLaunchEntry = onLaunchImportedTitle,
                onLaunchDiagnostic = onLaunchImportedTitleDiagnostic,
                onRemoveEntry = onRemoveLibraryEntry,
            )

            DebugFreezeLabCard(
                entries = state.libraryEntries,
                isBusy = state.isBusy,
                latestDiagnosticsSessionId = state.latestDiagnosticsSessionId,
                latestDiagnosticsFreezeCause = state.latestDiagnosticsFreezeCause,
                latestDiagnosticsFreezeConfidence = state.latestDiagnosticsFreezeConfidence,
                latestDiagnosticsGuestChanging = state.latestDiagnosticsGuestChanging,
                latestDiagnosticsTransportChanging = state.latestDiagnosticsTransportChanging,
                latestDiagnosticsScreenChanging = state.latestDiagnosticsScreenChanging,
                latestDiagnosticsCompareSummary = state.latestDiagnosticsCompareSummary,
                latestDiagnosticsBundlePath = state.latestDiagnosticsBundlePath,
                onOpenFreezeLabRun = onOpenFreezeLabRun,
                onCaptureCurrentFreezeReport = onCaptureCurrentFreezeReport,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onLaunchTurnipProbe, enabled = !state.isBusy) {
                    Text("Launch Turnip Probe")
                }
                OutlinedButton(onClick = onLaunchLavapipeProbe, enabled = !state.isBusy) {
                    Text("Launch Lavapipe Probe")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onLaunchDynamicHello, enabled = !state.isBusy) {
                    Text("Launch Dynamic Hello")
                }
                OutlinedButton(onClick = onLaunchFexHello, enabled = !state.isBusy) {
                    Text("Launch FEX Hello")
                }
                OutlinedButton(onClick = onLaunchStub, enabled = !state.isBusy) {
                    Text("Launch Stub")
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.isBusy) {
                    Text("Refresh")
                }
            }

            DebugStatusCard(
                title = "Mesa Override",
                lines = listOf(
                    "Current override: ${state.overrideMode}",
                    "Selected branch: ${state.selectedMesaBranch}",
                    "Selection reason: ${state.selectionReason}",
                ),
                accent = Color(0xFF89B8FF),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onSetOverride(MesaRuntimeBranch.AUTO) }, enabled = !state.isBusy) {
                    Text("Auto")
                }
                OutlinedButton(onClick = { onSetOverride(MesaRuntimeBranch.MESA25) }, enabled = !state.isBusy) {
                    Text("Mesa25")
                }
                OutlinedButton(onClick = { onSetOverride(MesaRuntimeBranch.MESA26) }, enabled = !state.isBusy) {
                    Text("Mesa26")
                }
                OutlinedButton(onClick = { onSetOverride(MesaRuntimeBranch.LAVAPIPE) }, enabled = !state.isBusy) {
                    Text("LVP")
                }
            }

            DebugStatusCard(
                title = "Runtime Status",
                lines = listOf(
                    "Profile: ${state.manifestProfile}",
                    "Manifest version: ${state.manifestVersion}",
                    "Install: ${state.installSummary}",
                    "Installed phase: ${state.installedPhase.ifBlank { "none" }}",
                    "Runtime root: ${state.runtimeRoot}",
                    "Last action: ${state.lastAction}",
                ),
            )

            DebugStatusCard(
                title = "Xenia Diagnostics",
                lines = listOf(
                    "Commit: ${state.xeniaCommit}",
                    "Patch set: ${state.xeniaPatchSet}",
                    "Build profile: ${state.xeniaBuildProfile}",
                    "Binary installed: ${state.xeniaBinaryInstalled}",
                    "Config present: ${state.xeniaConfigPresent}",
                    "Portable marker present: ${state.xeniaPortableMarkerPresent}",
                    "Content mode: ${state.xeniaContentMode}",
                    "Last startup stage: ${state.xeniaStartupStage}",
                    "Startup detail: ${state.xeniaStartupDetail}",
                    "Alive after module load (s): ${state.xeniaAliveAfterModuleLoadSeconds}",
                    "Cache backend status: ${state.xeniaCacheBackendStatus}",
                    "Cache root: ${state.xeniaCacheRootPath}",
                    "Title metadata seen: ${state.xeniaTitleMetadataSeen}",
                    "Title ID: ${state.xeniaTitleId}",
                    "Module hash: ${state.xeniaModuleHash}",
                    "Patch DB present: ${state.xeniaPatchDatabasePresent}",
                    "Patch DB revision: ${state.xeniaPatchDatabaseRevision}",
                    "Patch DB files: ${state.xeniaPatchDatabaseFileCount}",
                    "Patch DB bundled titles: ${state.xeniaPatchDatabaseBundleTitleCount}",
                    "Patch DB loaded titles: ${state.xeniaPatchDatabaseLoadedTitleCount}",
                    "Applied patches: ${state.xeniaAppliedPatches}",
                    "Last content miss: ${state.xeniaLastContentMiss}",
                    "Last meaningful guest transition: ${state.xeniaLastMeaningfulGuestTransition}",
                    "Last content call: ${state.xeniaLastContentCallResult}",
                    "Last XAM call: ${state.xeniaLastXamCallResult}",
                    "Last XLive call: ${state.xeniaLastXliveCallResult}",
                    "Last XNet call: ${state.xeniaLastXnetCallResult}",
                    "Progression bucket: ${state.xeniaProgressionBucket}",
                    "Progression reason: ${state.xeniaProgressionReason}",
                    "Video freeze cause: ${state.xeniaVideoFreezeCause.ifBlank { "unknown" }}",
                    "Video freeze confidence: ${state.xeniaVideoFreezeConfidence.ifBlank { "n/a" }}",
                    "Video freeze reason: ${state.xeniaVideoFreezeReason.ifBlank { "n/a" }}",
                    "Presentation backend: ${state.xeniaPresentationBackend}",
                    "Guest render scale: ${state.xeniaGuestRenderScaleProfile}",
                    "Internal display resolution: ${state.xeniaInternalDisplayResolution}",
                    "Framebuffer path: ${state.xeniaFramebufferPath}",
                    "Frame stream: ${state.xeniaFrameStreamStatus}",
                    "Last frame dimensions: ${state.xeniaLastFrameDimensions}",
                    "Last frame index: ${state.xeniaLastFrameIndex}",
                    "Frame freshness (s): ${state.xeniaFrameFreshnessSeconds}",
                    "Transport frame hash: ${state.xeniaTransportFrameHash}",
                    "Visible frame hash: ${state.xeniaVisibleFrameHash}",
                    "Last log path: ${state.xeniaLogPath}",
                    "Executable: ${state.xeniaExecutablePath}",
                ),
                accent = Color(0xFFFF8B7B),
            )

            DebugStatusCard(
                title = "Latest Player Diagnostics Bundle",
                lines = listOf(
                    "Session: ${state.latestDiagnosticsSessionId.ifBlank { "none" }}",
                    "Bucket: ${state.latestDiagnosticsBucket.ifBlank { "unknown" }}",
                    "Reason: ${state.latestDiagnosticsReason.ifBlank { "n/a" }}",
                    "Freeze cause: ${state.latestDiagnosticsFreezeCause.ifBlank { "unknown" }}",
                    "Freeze confidence: ${state.latestDiagnosticsFreezeConfidence.ifBlank { "n/a" }}",
                    "Freeze reason: ${state.latestDiagnosticsFreezeReason.ifBlank { "n/a" }}",
                    "Guest changing: ${state.latestDiagnosticsGuestChanging.ifBlank { "n/a" }}",
                    "Transport changing: ${state.latestDiagnosticsTransportChanging.ifBlank { "n/a" }}",
                    "Screen changing: ${state.latestDiagnosticsScreenChanging.ifBlank { "n/a" }}",
                    "Latest compare: ${state.latestDiagnosticsCompareSummary.ifBlank { "n/a" }}",
                    "Last transition: ${state.latestDiagnosticsLastTransition.ifBlank { "n/a" }}",
                    "Storage roots: ${state.latestDiagnosticsStorageSummary.ifBlank { "n/a" }}",
                    "Bundle path: ${state.latestDiagnosticsBundlePath.ifBlank { "n/a" }}",
                ),
                accent = Color(0xFFAED0FF),
            )

            DebugStatusCard(
                title = "FEX Diagnostics",
                lines = listOf(
                    "Commit: ${state.fexCommit}",
                    "Patch set: ${state.fexPatchSet}",
                    "Host artifacts packaged: ${state.hostArtifactsPackaged}",
                    "RootFS installed: ${state.rootfsInstalled}",
                    "Guest runtime profile: ${state.guestRuntimeProfile}",
                    "Guest provenance: ${state.guestRuntimeProvenance}",
                    "Mesa runtime profile: ${state.mesaRuntimeProfile}",
                    "Mesa patch set: ${state.mesaPatchSetId}",
                    "Mesa applied patches: ${state.mesaAppliedPatches}",
                    "glibc loader present: ${state.glibcLoaderPresent}",
                    "Guest libvulkan present: ${state.guestVulkanLoaderPresent}",
                    "Lavapipe driver present: ${state.guestLavapipeDriverPresent}",
                    "Mesa25 installed: ${state.mesa25Installed}",
                    "Mesa26 installed: ${state.mesa26Installed}",
                    "Active ICD: ${state.activeIcd}",
                    "ICD JSON present: ${state.lvpIcdPresent}",
                    "KGSL accessible: ${state.kgslAccessible}",
                    "KGSL detail: ${state.kgslDetail}",
                    "Last probe driver mode: ${state.lastProbeDriverMode}",
                    "Last probe device: ${state.lastProbeDeviceName}",
                    "Hello fixture installed: ${state.helloFixtureInstalled}",
                    "Dynamic hello installed: ${state.dynamicHelloInstalled}",
                    "Vulkan probe installed: ${state.vulkanProbeInstalled}",
                    "Config present: ${state.fexConfigPresent}",
                    "Last launch backend: ${state.lastLaunchBackend}",
                    "Last launch result: ${state.lastLaunchResult}",
                    "Loader: ${state.loaderPath}",
                    "Core: ${state.corePath}",
                ),
                accent = Color(0xFF7BE7C4),
            )

            DebugStatusCard(
                title = "Native Bridge",
                lines = listOf(
                    "Health: ${state.nativeHealth}",
                    "Surface reservation: ${state.surfaceReservation}",
                ),
            )

            OutputPreviewCard(
                preview = state.outputPreview,
                latestSessionId = state.latestSessionId,
            )

            DebugLogCard(title = "App Log", content = state.appLog)
            DebugLogCard(title = "FEX Log", content = state.fexLog)
            DebugLogCard(title = "Guest Log", content = state.guestLog)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DebugLibraryCard(
    entries: List<GameLibraryEntryUi>,
    isBusy: Boolean,
    onImportIso: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onLaunchEntry: (String) -> Unit,
    onLaunchDiagnostic: (String, DiagnosticLaunchProfile) -> Unit,
    onRemoveEntry: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC14202C)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF5FD9FF),
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImportIso, enabled = !isBusy) {
                    Text("Import ISO")
                }
                OutlinedButton(onClick = onRefreshLibrary, enabled = !isBusy) {
                    Text("Refresh Library")
                }
            }
            if (entries.isEmpty()) {
                Text(
                    text = "No imported titles yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC7D2DC),
                )
            } else {
                entries.forEach { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xB3122230)),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = entry.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFF0F4F8),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Status: ${entry.status} | Source: ${entry.sourceKind} | ${entry.sizeSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC7D2DC),
                            )
                            Text(
                                text = "Guest path: ${entry.guestPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC7D2DC),
                            )
                            Text(
                                text = "Title: ${entry.titleName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC7D2DC),
                            )
                            Text(
                                text = "Last launch: ${entry.lastLaunchSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC7D2DC),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { onLaunchEntry(entry.id) },
                                    enabled = !isBusy,
                                ) {
                                    Text("Launch Visible")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onLaunchDiagnostic(
                                            entry.id,
                                            DiagnosticLaunchProfile.FRAMEBUFFER_POLLING_DIAGNOSTIC,
                                        )
                                    },
                                    enabled = !isBusy,
                                ) {
                                    Text("Diag Polling")
                                }
                                OutlinedButton(
                                    onClick = { onRemoveEntry(entry.id) },
                                    enabled = !isBusy,
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
private fun DebugFreezeLabCard(
    entries: List<GameLibraryEntryUi>,
    isBusy: Boolean,
    latestDiagnosticsSessionId: String,
    latestDiagnosticsFreezeCause: String,
    latestDiagnosticsFreezeConfidence: String,
    latestDiagnosticsGuestChanging: String,
    latestDiagnosticsTransportChanging: String,
    latestDiagnosticsScreenChanging: String,
    latestDiagnosticsCompareSummary: String,
    latestDiagnosticsBundlePath: String,
    onOpenFreezeLabRun: (String, DiagnosticLaunchProfile, Boolean, Boolean) -> Unit,
    onCaptureCurrentFreezeReport: () -> Unit,
) {
    var inputMuted by rememberSaveable { mutableStateOf(false) }
    var overlayHidden by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC10212F)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Freeze Lab",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFFFC36A),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Run the live player with controlled diagnostics, then capture and compare freeze reports from the real session path.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFC7D2DC),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCaptureCurrentFreezeReport, enabled = !isBusy) {
                    Text("Capture Current Report")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Mute controller input after launch",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC7D2DC),
                )
                androidx.compose.material3.Switch(
                    checked = inputMuted,
                    onCheckedChange = { inputMuted = it },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Hide FPS/error overlays",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC7D2DC),
                )
                androidx.compose.material3.Switch(
                    checked = overlayHidden,
                    onCheckedChange = { overlayHidden = it },
                )
            }
            Text(
                text = "Latest session: ${latestDiagnosticsSessionId.ifBlank { "none" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Text(
                text = "Cause: ${latestDiagnosticsFreezeCause.ifBlank { "unknown" }} | Confidence: ${latestDiagnosticsFreezeConfidence.ifBlank { "n/a" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Text(
                text = "Guest changing: ${latestDiagnosticsGuestChanging.ifBlank { "n/a" }} | Transport changing: ${latestDiagnosticsTransportChanging.ifBlank { "n/a" }} | Screen changing: ${latestDiagnosticsScreenChanging.ifBlank { "n/a" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Text(
                text = "Compare latest two: ${latestDiagnosticsCompareSummary.ifBlank { "n/a" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Text(
                text = "Bundle: ${latestDiagnosticsBundlePath.ifBlank { "n/a" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            if (entries.isEmpty()) {
                Text(
                    text = "Import a title to run the freeze lab on a live player session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC7D2DC),
                )
            } else {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = entry.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFF0F4F8),
                            fontWeight = FontWeight.Medium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    onOpenFreezeLabRun(
                                        entry.id,
                                        DiagnosticLaunchProfile.XENIA_REAL_TITLE,
                                        inputMuted,
                                        overlayHidden,
                                    )
                                },
                                enabled = !isBusy,
                            ) {
                                Text("Current")
                            }
                            OutlinedButton(
                                onClick = {
                                    onOpenFreezeLabRun(
                                        entry.id,
                                        DiagnosticLaunchProfile.FRAMEBUFFER_POLLING_DIAGNOSTIC,
                                        inputMuted,
                                        overlayHidden,
                                    )
                                },
                                enabled = !isBusy,
                            ) {
                                Text("Polling")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onOpenFreezeLabRun(
                                        entry.id,
                                        DiagnosticLaunchProfile.AUDIO_MUTED,
                                        inputMuted,
                                        overlayHidden,
                                    )
                                },
                                enabled = !isBusy,
                            ) {
                                Text("Muted")
                            }
                            OutlinedButton(
                                onClick = {
                                    onOpenFreezeLabRun(
                                        entry.id,
                                        DiagnosticLaunchProfile.XMA_FAKE,
                                        inputMuted,
                                        overlayHidden,
                                    )
                                },
                                enabled = !isBusy,
                            ) {
                                Text("XMA Fake")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onOpenFreezeLabRun(
                                        entry.id,
                                        DiagnosticLaunchProfile.XMA_OLD,
                                        inputMuted,
                                        overlayHidden,
                                    )
                                },
                                enabled = !isBusy,
                            ) {
                                Text("XMA Old")
                            }
                            OutlinedButton(
                                onClick = {
                                    onOpenFreezeLabRun(
                                        entry.id,
                                        DiagnosticLaunchProfile.XMA_SINGLE_THREAD,
                                        inputMuted,
                                        overlayHidden,
                                    )
                                },
                                enabled = !isBusy,
                            ) {
                                Text("No XMA Thread")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugStatusCard(
    title: String,
    lines: List<String>,
    accent: Color = Color(0xFFF5B942),
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC0C1722)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE5EBF1),
                )
            }
        }
    }
}

@Composable
private fun DebugLogCard(
    title: String,
    content: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xB3122230)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFF0F4F8),
                fontWeight = FontWeight.Medium,
            )
            SelectionContainer {
                Text(
                    text = content.ifBlank { "No session log yet." },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC7D2DC),
                )
            }
        }
    }
}
