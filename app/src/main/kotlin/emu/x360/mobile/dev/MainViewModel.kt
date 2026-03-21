package emu.x360.mobile.dev

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.ShellSnapshot
import emu.x360.mobile.dev.runtime.AppSettings
import emu.x360.mobile.dev.runtime.GameLibraryEntry
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
    private val baseDir = application.applicationContext.filesDir.toPath()
    private val manager = AppRuntimeManager(application.applicationContext)
    private val appSettingsStore = AppSettingsStore(baseDir)
    private val gameOptionsStore = GameOptionsStore(baseDir)
    private val mutableState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        loadShell { manager.shellSnapshot(lastAction = "Library refreshed", autoPrepareRuntime = true) }
    }

    fun importIso(uri: Uri) {
        runShellMutation {
            manager.importIso(uri).lastAction
        }
    }

    fun refreshLibrary() {
        runShellMutation {
            manager.refreshLibrary().lastAction
        }
    }

    fun removeLibraryEntry(entryId: String) {
        runShellMutation {
            manager.removeLibraryEntry(entryId).lastAction
        }
    }

    fun setShowFpsCounter(enabled: Boolean) {
        mutableState.update { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsStore.update { current -> current.copy(showFpsCounter = enabled) }
            mutableState.value = MainUiState.from(
                shell = manager.shellSnapshot(lastAction = "FPS counter ${if (enabled) "enabled" else "disabled"}"),
                appSettings = appSettingsStore.load(),
                gameOptions = currentGameOptions(manager.shellSnapshot(autoPrepareRuntime = false)),
            )
        }
    }

    private fun loadShell(snapshotProvider: () -> ShellSnapshot) {
        mutableState.update { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val shell = runCatching(snapshotProvider).getOrElse { throwable ->
                manager.shellSnapshot(lastAction = "Shell load failed: ${throwable.message}", autoPrepareRuntime = false)
            }
            mutableState.value = MainUiState.from(
                shell = shell,
                appSettings = appSettingsStore.load(),
                gameOptions = currentGameOptions(shell),
            )
        }
    }

    private fun runShellMutation(action: () -> String) {
        mutableState.update { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val lastAction = runCatching(action).getOrElse { throwable ->
                "Action failed: ${throwable.message}"
            }
            val shell = manager.shellSnapshot(lastAction = lastAction, autoPrepareRuntime = true)
            mutableState.value = MainUiState.from(
                shell = shell,
                appSettings = appSettingsStore.load(),
                gameOptions = currentGameOptions(shell),
            )
        }
    }

    private fun currentGameOptions(shell: ShellSnapshot): Map<String, GameOptionsUi> {
        return shell.libraryEntries.associate { entry ->
            entry.id to GameOptionsUi.from(entry, gameOptionsStore.optionsFor(entry.id))
        }
    }
}

data class MainUiState(
    val isBusy: Boolean = true,
    val runtimeReady: Boolean = false,
    val runtimeSummary: String = "Preparing runtime...",
    val lastAction: String = "Preparing runtime...",
    val libraryEntries: List<GameLibraryEntryUi> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
    val gameOptions: Map<String, GameOptionsUi> = emptyMap(),
) {
    fun optionsFor(entryId: String): GameOptionsUi? = gameOptions[entryId]

    companion object {
        fun from(
            shell: ShellSnapshot,
            appSettings: AppSettings,
            gameOptions: Map<String, GameOptionsUi>,
        ): MainUiState {
            return MainUiState(
                isBusy = false,
                runtimeReady = shell.installState is RuntimeInstallState.Installed,
                runtimeSummary = when (val installState = shell.installState) {
                    RuntimeInstallState.NotInstalled -> "Runtime not installed"
                    is RuntimeInstallState.Installed -> "Runtime ready"
                    is RuntimeInstallState.Invalid -> "Runtime invalid: ${installState.issue}"
                },
                lastAction = shell.lastAction,
                libraryEntries = shell.libraryEntries.map(GameLibraryEntryUi::from),
                appSettings = appSettings,
                gameOptions = gameOptions,
            )
        }
    }
}
