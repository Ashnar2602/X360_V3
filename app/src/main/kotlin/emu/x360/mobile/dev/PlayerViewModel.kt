package emu.x360.mobile.dev

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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

    fun currentSettings(): AppSettings = appSettingsStore.load()
}
