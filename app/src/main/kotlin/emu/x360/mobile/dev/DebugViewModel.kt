package emu.x360.mobile.dev

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.DiagnosticLaunchProfile
import emu.x360.mobile.dev.bootstrap.RuntimeSnapshot
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.RuntimeInstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DebugViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val manager = AppRuntimeManager(application.applicationContext)
    private val mutableState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        runAction { manager.snapshot(lastAction = "Runtime snapshot refreshed") }
    }

    fun installRuntime() {
        runAction { manager.install() }
    }

    fun launchTurnipProbe() {
        runAction { manager.launchTurnipProbe() }
    }

    fun launchXeniaBringup() {
        runAction { manager.launchXeniaBringup() }
    }

    fun importIso(uri: Uri) {
        runAction { manager.importIso(uri) }
    }

    fun refreshLibrary() {
        runAction { manager.refreshLibrary() }
    }

    fun removeLibraryEntry(entryId: String) {
        runAction { manager.removeLibraryEntry(entryId) }
    }

    fun launchImportedTitle(entryId: String) {
        runAction { manager.launchImportedTitle(entryId) }
    }

    internal fun launchImportedTitleDiagnostic(
        entryId: String,
        diagnosticProfile: DiagnosticLaunchProfile,
    ) {
        runAction { manager.launchImportedTitleDiagnostic(entryId, diagnosticProfile) }
    }

    fun launchLavapipeProbe() {
        runAction { manager.launchLavapipeProbe() }
    }

    fun launchDynamicHello() {
        runAction { manager.launchDynamicHello() }
    }

    fun launchFexHello() {
        runAction { manager.launchFexHello() }
    }

    fun launchStub() {
        runAction { manager.launchStub() }
    }

    fun setMesaOverride(overrideMode: MesaRuntimeBranch) {
        runAction { manager.setMesaOverride(overrideMode) }
    }

    private fun runAction(action: () -> RuntimeSnapshot) {
        mutableState.update { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = runCatching(action).getOrElse { throwable ->
                manager.snapshot(lastAction = "Action failed: ${throwable.message}")
            }
            mutableState.value = DebugUiState.from(snapshot)
        }
    }
}

data class DebugUiState(
    val isBusy: Boolean = true,
    val manifestProfile: String = "",
    val manifestVersion: Int = 0,
    val installState: RuntimeInstallState = RuntimeInstallState.NotInstalled,
    val installSummary: String = "Bootstrapping runtime status...",
    val nativeHealth: String = "",
    val surfaceReservation: String = "",
    val runtimeRoot: String = "",
    val outputPreview: OutputPreviewUiState = OutputPreviewUiState(),
    val latestSessionId: String = "",
    val installedPhase: String = "",
    val fexCommit: String = "",
    val fexPatchSet: String = "",
    val hostArtifactsPackaged: String = "",
    val loaderPath: String = "",
    val corePath: String = "",
    val rootfsInstalled: String = "",
    val helloFixtureInstalled: String = "",
    val dynamicHelloInstalled: String = "",
    val vulkanProbeInstalled: String = "",
    val fexConfigPresent: String = "",
    val guestRuntimeProfile: String = "",
    val guestRuntimeProvenance: String = "",
    val mesaRuntimeProfile: String = "",
    val mesaPatchSetId: String = "",
    val mesaAppliedPatches: String = "",
    val glibcLoaderPresent: String = "",
    val guestVulkanLoaderPresent: String = "",
    val guestLavapipeDriverPresent: String = "",
    val activeIcd: String = "",
    val lvpIcdPresent: String = "",
    val mesa25Installed: String = "",
    val mesa26Installed: String = "",
    val kgslAccessible: String = "",
    val kgslDetail: String = "",
    val selectedMesaBranch: String = "",
    val overrideMode: String = "",
    val selectionReason: String = "",
    val lastProbeDeviceName: String = "",
    val lastProbeDriverMode: String = "",
    val xeniaCommit: String = "",
    val xeniaPatchSet: String = "",
    val xeniaBuildProfile: String = "",
    val xeniaBinaryInstalled: String = "",
    val xeniaConfigPresent: String = "",
    val xeniaPortableMarkerPresent: String = "",
    val xeniaContentMode: String = "",
    val xeniaStartupStage: String = "",
    val xeniaStartupDetail: String = "",
    val xeniaAliveAfterModuleLoadSeconds: String = "",
    val xeniaCacheBackendStatus: String = "",
    val xeniaCacheRootPath: String = "",
    val xeniaTitleMetadataSeen: String = "",
    val xeniaTitleId: String = "",
    val xeniaModuleHash: String = "",
    val xeniaPatchDatabasePresent: String = "",
    val xeniaPatchDatabaseRevision: String = "",
    val xeniaPatchDatabaseFileCount: String = "",
    val xeniaPatchDatabaseBundleTitleCount: String = "",
    val xeniaPatchDatabaseLoadedTitleCount: String = "",
    val xeniaAppliedPatches: String = "",
    val xeniaLastContentMiss: String = "",
    val xeniaLastMeaningfulGuestTransition: String = "",
    val xeniaLastContentCallResult: String = "",
    val xeniaLastXamCallResult: String = "",
    val xeniaLastXliveCallResult: String = "",
    val xeniaLastXnetCallResult: String = "",
    val xeniaProgressionBucket: String = "",
    val xeniaProgressionReason: String = "",
    val xeniaPresentationBackend: String = "",
    val xeniaGuestRenderScaleProfile: String = "",
    val xeniaInternalDisplayResolution: String = "",
    val xeniaFramebufferPath: String = "",
    val xeniaFrameStreamStatus: String = "",
    val xeniaLastFrameDimensions: String = "",
    val xeniaLastFrameIndex: String = "",
    val xeniaFrameFreshnessSeconds: String = "",
    val xeniaTransportFrameHash: String = "",
    val xeniaVisibleFrameHash: String = "",
    val xeniaLogPath: String = "",
    val xeniaExecutablePath: String = "",
    val latestDiagnosticsSessionId: String = "",
    val latestDiagnosticsBucket: String = "",
    val latestDiagnosticsReason: String = "",
    val latestDiagnosticsLastTransition: String = "",
    val latestDiagnosticsStorageSummary: String = "",
    val latestDiagnosticsBundlePath: String = "",
    val libraryEntries: List<GameLibraryEntryUi> = emptyList(),
    val lastLaunchBackend: String = "",
    val lastLaunchResult: String = "",
    val appLog: String = "",
    val fexLog: String = "",
    val guestLog: String = "",
    val lastAction: String = "",
) {
    companion object {
        fun from(snapshot: RuntimeSnapshot): DebugUiState {
            val installSummary = when (val state = snapshot.installState) {
                RuntimeInstallState.NotInstalled -> "Not installed"
                is RuntimeInstallState.Installed -> "Installed at ${state.installedAt}"
                is RuntimeInstallState.Invalid -> "Invalid: ${state.issue}"
            }
            return DebugUiState(
                isBusy = false,
                manifestProfile = snapshot.manifest.profile,
                manifestVersion = snapshot.manifest.version,
                installState = snapshot.installState,
                installSummary = installSummary,
                nativeHealth = snapshot.nativeHealth,
                surfaceReservation = snapshot.surfaceHookReservation,
                runtimeRoot = snapshot.directories.baseDir.toString(),
                outputPreview = OutputPreviewUiState.from(snapshot.outputPreview),
                latestSessionId = snapshot.latestLogs.sessionId.orEmpty(),
                installedPhase = (snapshot.installState as? RuntimeInstallState.Installed)?.installedPhase?.name?.lowercase().orEmpty(),
                fexCommit = snapshot.fexDiagnostics.commit,
                fexPatchSet = snapshot.fexDiagnostics.patchSetId,
                hostArtifactsPackaged = snapshot.fexDiagnostics.hostArtifactsPackaged.toString(),
                loaderPath = snapshot.fexDiagnostics.loaderPath,
                corePath = snapshot.fexDiagnostics.corePath,
                rootfsInstalled = snapshot.fexDiagnostics.rootfsInstalled.toString(),
                helloFixtureInstalled = snapshot.fexDiagnostics.helloFixtureInstalled.toString(),
                dynamicHelloInstalled = snapshot.fexDiagnostics.dynamicHelloInstalled.toString(),
                vulkanProbeInstalled = snapshot.fexDiagnostics.vulkanProbeInstalled.toString(),
                fexConfigPresent = snapshot.fexDiagnostics.configPresent.toString(),
                guestRuntimeProfile = snapshot.fexDiagnostics.guestRuntimeProfile,
                guestRuntimeProvenance = snapshot.fexDiagnostics.guestRuntimeProvenance,
                mesaRuntimeProfile = snapshot.fexDiagnostics.mesaRuntimeProfile,
                mesaPatchSetId = snapshot.fexDiagnostics.mesaPatchSetId,
                mesaAppliedPatches = snapshot.fexDiagnostics.mesaAppliedPatches,
                glibcLoaderPresent = snapshot.fexDiagnostics.glibcLoaderPresent.toString(),
                guestVulkanLoaderPresent = snapshot.fexDiagnostics.guestVulkanLoaderPresent.toString(),
                guestLavapipeDriverPresent = snapshot.fexDiagnostics.guestLavapipeDriverPresent.toString(),
                activeIcd = snapshot.fexDiagnostics.activeIcd,
                lvpIcdPresent = snapshot.fexDiagnostics.lvpIcdPresent.toString(),
                mesa25Installed = snapshot.fexDiagnostics.mesa25Installed.toString(),
                mesa26Installed = snapshot.fexDiagnostics.mesa26Installed.toString(),
                kgslAccessible = snapshot.fexDiagnostics.kgslAccessible.toString(),
                kgslDetail = snapshot.fexDiagnostics.kgslDetail,
                selectedMesaBranch = snapshot.fexDiagnostics.selectedMesaBranch,
                overrideMode = snapshot.fexDiagnostics.overrideMode,
                selectionReason = snapshot.fexDiagnostics.selectionReason,
                lastProbeDeviceName = snapshot.fexDiagnostics.lastProbeDeviceName,
                lastProbeDriverMode = snapshot.fexDiagnostics.lastProbeDriverMode,
                xeniaCommit = snapshot.xeniaDiagnostics.commit,
                xeniaPatchSet = snapshot.xeniaDiagnostics.patchSetId,
                xeniaBuildProfile = snapshot.xeniaDiagnostics.buildProfile,
                xeniaBinaryInstalled = snapshot.xeniaDiagnostics.binaryInstalled.toString(),
                xeniaConfigPresent = snapshot.xeniaDiagnostics.configPresent.toString(),
                xeniaPortableMarkerPresent = snapshot.xeniaDiagnostics.portableMarkerPresent.toString(),
                xeniaContentMode = snapshot.xeniaDiagnostics.contentMode,
                xeniaStartupStage = snapshot.xeniaDiagnostics.lastStartupStage,
                xeniaStartupDetail = snapshot.xeniaDiagnostics.lastStartupDetail,
                xeniaAliveAfterModuleLoadSeconds = snapshot.xeniaDiagnostics.aliveAfterModuleLoadSeconds.toString(),
                xeniaCacheBackendStatus = snapshot.xeniaDiagnostics.cacheBackendStatus,
                xeniaCacheRootPath = snapshot.xeniaDiagnostics.cacheRootPath,
                xeniaTitleMetadataSeen = snapshot.xeniaDiagnostics.titleMetadataSeen.toString(),
                xeniaTitleId = snapshot.xeniaDiagnostics.titleId,
                xeniaModuleHash = snapshot.xeniaDiagnostics.moduleHash,
                xeniaPatchDatabasePresent = snapshot.xeniaDiagnostics.patchDatabasePresent.toString(),
                xeniaPatchDatabaseRevision = snapshot.xeniaDiagnostics.patchDatabaseRevision,
                xeniaPatchDatabaseFileCount = snapshot.xeniaDiagnostics.patchDatabaseFileCount.toString(),
                xeniaPatchDatabaseBundleTitleCount = snapshot.xeniaDiagnostics.patchDatabaseBundleTitleCount.toString(),
                xeniaPatchDatabaseLoadedTitleCount = snapshot.xeniaDiagnostics.patchDatabaseLoadedTitleCount.toString(),
                xeniaAppliedPatches = snapshot.xeniaDiagnostics.lastAppliedPatches.joinToString(" | ").ifBlank { "none" },
                xeniaLastContentMiss = snapshot.xeniaDiagnostics.lastContentMiss,
                xeniaLastMeaningfulGuestTransition = snapshot.xeniaDiagnostics.lastMeaningfulGuestTransition,
                xeniaLastContentCallResult = snapshot.xeniaDiagnostics.lastContentCallResult,
                xeniaLastXamCallResult = snapshot.xeniaDiagnostics.lastXamCallResult,
                xeniaLastXliveCallResult = snapshot.xeniaDiagnostics.lastXliveCallResult,
                xeniaLastXnetCallResult = snapshot.xeniaDiagnostics.lastXnetCallResult,
                xeniaProgressionBucket = snapshot.xeniaDiagnostics.progressionBucket,
                xeniaProgressionReason = snapshot.xeniaDiagnostics.progressionReason,
                xeniaPresentationBackend = snapshot.xeniaDiagnostics.presentationBackend,
                xeniaGuestRenderScaleProfile = snapshot.xeniaDiagnostics.guestRenderScaleProfile,
                xeniaInternalDisplayResolution = snapshot.xeniaDiagnostics.internalDisplayResolution,
                xeniaFramebufferPath = snapshot.xeniaDiagnostics.framebufferPath,
                xeniaFrameStreamStatus = snapshot.xeniaDiagnostics.frameStreamStatus,
                xeniaLastFrameDimensions = if (snapshot.xeniaDiagnostics.lastFrameWidth > 0 && snapshot.xeniaDiagnostics.lastFrameHeight > 0) {
                    "${snapshot.xeniaDiagnostics.lastFrameWidth}x${snapshot.xeniaDiagnostics.lastFrameHeight}"
                } else {
                    "none"
                },
                xeniaLastFrameIndex = snapshot.xeniaDiagnostics.lastFrameIndex.toString(),
                xeniaFrameFreshnessSeconds = snapshot.xeniaDiagnostics.frameFreshnessSeconds?.toString() ?: "n/a",
                xeniaTransportFrameHash = snapshot.xeniaDiagnostics.transportFrameHash.ifBlank { "none" },
                xeniaVisibleFrameHash = snapshot.xeniaDiagnostics.visibleFrameHash.ifBlank { "none" },
                xeniaLogPath = snapshot.xeniaDiagnostics.lastLogPath,
                xeniaExecutablePath = snapshot.xeniaDiagnostics.executablePath,
                latestDiagnosticsSessionId = snapshot.latestPlayerSessionDiagnostics?.sessionId.orEmpty(),
                latestDiagnosticsBucket = snapshot.latestPlayerSessionDiagnostics?.progressionBucket?.name?.lowercase().orEmpty(),
                latestDiagnosticsReason = snapshot.latestPlayerSessionDiagnostics?.progressionReason.orEmpty(),
                latestDiagnosticsLastTransition = snapshot.latestPlayerSessionDiagnostics?.lastMeaningfulGuestTransition.orEmpty(),
                latestDiagnosticsStorageSummary = snapshot.latestPlayerSessionDiagnostics
                    ?.storageRoots
                    ?.joinToString(" | ") { root ->
                        "${root.label}:${if (root.exists && root.readable && (root.writable || root.label == "title-portal" || root.label == "patches-root")) "ok" else "blocked"}"
                    }
                    .orEmpty(),
                latestDiagnosticsBundlePath = snapshot.latestPlayerSessionDiagnostics
                    ?.let { "${snapshot.directories.diagnosticsLogs}/session-${it.sessionId}.bundle.json" }
                    .orEmpty(),
                libraryEntries = snapshot.gameLibraryEntries.map(GameLibraryEntryUi::from),
                lastLaunchBackend = snapshot.fexDiagnostics.lastLaunchBackend,
                lastLaunchResult = snapshot.fexDiagnostics.lastLaunchResult,
                appLog = snapshot.latestLogs.appLog,
                fexLog = snapshot.latestLogs.fexLog,
                guestLog = snapshot.latestLogs.guestLog,
                lastAction = snapshot.lastAction,
            )
        }
    }
}
