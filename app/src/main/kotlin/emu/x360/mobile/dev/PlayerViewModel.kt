package emu.x360.mobile.dev

import android.app.Application
import emu.x360.mobile.dev.bootstrap.SharedFramePresentationReader
import androidx.lifecycle.AndroidViewModel
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.AppSettings
import kotlinx.coroutines.flow.StateFlow

internal class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appSettingsStore = AppSettingsStore(application.applicationContext.filesDir.toPath())
    val uiState: StateFlow<PlayerSessionUiState> = PlayerSessionController.state

    fun start(entryId: String) {
        PlayerSessionController.start(
            context = getApplication<Application>().applicationContext,
            entryId = entryId,
            settings = appSettingsStore.load(),
        )
    }

    fun stop(reason: String = "user-exit") {
        PlayerSessionController.stop(
            context = getApplication<Application>().applicationContext,
            reason = reason,
        )
    }

    fun reportVisibleMetrics(metrics: PresentationPerformanceMetrics) {
        PlayerSessionController.reportVisibleMetrics(metrics)
    }

    fun openPresentationReader(): SharedFramePresentationReader? = PlayerSessionController.openPresentationReader()

    fun pause(): Boolean = PlayerSessionController.pause()

    fun resume(): Boolean = PlayerSessionController.resume()

    fun setShowFpsCounter(enabled: Boolean) {
        appSettingsStore.update { current -> current.copy(showFpsCounter = enabled) }
        PlayerSessionController.setShowFpsCounter(enabled)
    }

    fun submitControllerInput(update: PlayerControllerInputUpdate) {
        PlayerSessionController.submitControllerInput(update)
    }

    fun clearControllerInput() {
        PlayerSessionController.clearControllerInput()
    }

    fun currentSettings(): AppSettings = appSettingsStore.load()
}
