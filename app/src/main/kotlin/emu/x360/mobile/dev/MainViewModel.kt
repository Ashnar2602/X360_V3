package emu.x360.mobile.dev

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.RuntimeSnapshot
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.RuntimeInstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val manager = AppRuntimeManager(application.applicationContext)
    private val mutableState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = mutableState.asStateFlow()

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
            mutableState.value = MainUiState.from(snapshot)
        }
    }
}

data class MainUiState(
    val isBusy: Boolean = true,
    val manifestProfile: String = "",
    val manifestVersion: Int = 0,
    val installState: RuntimeInstallState = RuntimeInstallState.NotInstalled,
    val installSummary: String = "Bootstrapping runtime status...",
    val nativeHealth: String = "",
    val surfaceReservation: String = "",
    val runtimeRoot: String = "",
    val outputPreview: String = "",
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
    val xeniaLogPath: String = "",
    val xeniaExecutablePath: String = "",
    val lastLaunchBackend: String = "",
    val lastLaunchResult: String = "",
    val appLog: String = "",
    val fexLog: String = "",
    val guestLog: String = "",
    val lastAction: String = "",
) {
    companion object {
        fun from(snapshot: RuntimeSnapshot): MainUiState {
            val installSummary = when (val state = snapshot.installState) {
                RuntimeInstallState.NotInstalled -> "Not installed"
                is RuntimeInstallState.Installed -> "Installed at ${state.installedAt}"
                is RuntimeInstallState.Invalid -> "Invalid: ${state.issue}"
            }
            return MainUiState(
                isBusy = false,
                manifestProfile = snapshot.manifest.profile,
                manifestVersion = snapshot.manifest.version,
                installState = snapshot.installState,
                installSummary = installSummary,
                nativeHealth = snapshot.nativeHealth,
                surfaceReservation = snapshot.surfaceHookReservation,
                runtimeRoot = snapshot.directories.baseDir.toString(),
                outputPreview = snapshot.outputPreview.summary,
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
                xeniaLogPath = snapshot.xeniaDiagnostics.lastLogPath,
                xeniaExecutablePath = snapshot.xeniaDiagnostics.executablePath,
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
