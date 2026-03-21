package emu.x360.mobile.dev

import android.content.Context
import emu.x360.mobile.dev.bootstrap.ActivePlayerSessionHandle
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.InternalDisplayResolution
import emu.x360.mobile.dev.bootstrap.XeniaPresentationSettings
import emu.x360.mobile.dev.runtime.AppSettings
import emu.x360.mobile.dev.runtime.ExitClassification
import emu.x360.mobile.dev.runtime.GuestRenderScaleProfile
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object PlayerSessionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(PlayerSessionUiState())
    private var activeSession: ActivePlayerSessionHandle? = null
    private var monitorJob: Job? = null

    val state: StateFlow<PlayerSessionUiState> = mutableState.asStateFlow()

    fun start(
        context: Context,
        entryId: String,
        settings: AppSettings,
    ) {
        stop(context, "replaced-session")
        mutableState.value = PlayerSessionUiState(
            status = PlayerSessionStatus.LAUNCHING,
            entryId = entryId,
            showFpsCounter = settings.showFpsCounter,
            presentationBackend = settings.defaultPresentationBackend.name.lowercase(),
            renderScaleProfile = settings.defaultRenderScaleProfile.name.lowercase(),
            internalDisplayResolution = "1280x720",
            detail = "Starting player session",
        )
        scope.launch {
            val manager = AppRuntimeManager(context.applicationContext)
            val startResult = manager.startPlayerSession(
                entryId = entryId,
                presentationSettings = settings.toPresentationSettings(),
            )
            val session = startResult.session
            if (session == null) {
                mutableState.value = PlayerSessionUiState(
                    status = PlayerSessionStatus.FAILED,
                    entryId = entryId,
                    showFpsCounter = settings.showFpsCounter,
                    presentationBackend = settings.defaultPresentationBackend.name.lowercase(),
                    renderScaleProfile = settings.defaultRenderScaleProfile.name.lowercase(),
                    internalDisplayResolution = "1280x720",
                    detail = startResult.detail,
                    errorMessage = startResult.detail,
                )
                return@launch
            }

            activeSession = session
            monitorJob = launch {
                monitorSession(manager, session, settings.showFpsCounter)
            }
        }
    }

    fun stop(
        context: Context,
        reason: String = "user-exit",
    ) {
        val session = activeSession ?: return
        monitorJob?.cancel()
        monitorJob = null
        activeSession = null
        val result = session.stop(reason)
        val manager = AppRuntimeManager(context.applicationContext)
        val analysis = session.readStartupAnalysis()
        manager.recordPlayerSessionOutcome(
            entryId = session.entryId,
            detail = result.detail,
            stage = analysis.stage,
            titleName = analysis.titleName,
        )
        mutableState.update {
            it.copy(
                status = PlayerSessionStatus.STOPPED,
                detail = result.detail,
                errorMessage = null,
            )
        }
    }

    private suspend fun monitorSession(
        manager: AppRuntimeManager,
        session: ActivePlayerSessionHandle,
        showFpsCounter: Boolean,
    ) {
        while (currentCoroutineContext().isActive) {
            val analysis = session.readStartupAnalysis()
            val currentState = mutableState.value

            val streamStatus = when (analysis.stage) {
                XeniaStartupStage.FRAME_STREAM_ACTIVE -> "active"
                XeniaStartupStage.FIRST_FRAME_CAPTURED -> "first-frame"
                else -> "idle"
            }

            val nextState = PlayerSessionUiState(
                status = if (session.isAlive()) PlayerSessionStatus.RUNNING else PlayerSessionStatus.STOPPED,
                entryId = session.entryId,
                sessionId = session.sessionId,
                titleName = analysis.titleName ?: session.entryDisplayName,
                startupStage = analysis.stage.name.lowercase(),
                detail = analysis.detail,
                framebufferPath = session.framebufferPath.toString(),
                frameStreamStatus = streamStatus,
                frameIndex = currentState.frameIndex,
                frameWidth = currentState.frameWidth,
                frameHeight = currentState.frameHeight,
                frameFreshnessSeconds = currentState.frameFreshnessSeconds,
                fps = 0f,
                showFpsCounter = showFpsCounter,
                presentationBackend = PresentationBackend.FRAMEBUFFER_POLLING.name.lowercase(),
                renderScaleProfile = GuestRenderScaleProfile.ONE.name.lowercase(),
                internalDisplayResolution = "1280x720",
            )
            if (mutableState.value != nextState) {
                mutableState.value = nextState
            }

            if (!session.isAlive()) {
                val result = session.finalizeIfExited()
                manager.recordPlayerSessionOutcome(
                    entryId = session.entryId,
                    detail = result?.detail ?: "Player session ended",
                    stage = analysis.stage,
                    titleName = analysis.titleName,
                )
                mutableState.update {
                    it.copy(
                        status = if (result?.exitClassification == ExitClassification.PROCESS_ERROR || analysis.stage == XeniaStartupStage.FAILED) {
                            PlayerSessionStatus.FAILED
                        } else {
                            PlayerSessionStatus.STOPPED
                        },
                        detail = result?.detail ?: it.detail,
                        errorMessage = if (analysis.stage == XeniaStartupStage.FAILED) {
                            result?.detail ?: analysis.detail
                        } else {
                            null
                        },
                    )
                }
                activeSession = null
                monitorJob = null
                return
            }

            delay(120)
        }
    }
}

internal enum class PlayerSessionStatus {
    IDLE,
    LAUNCHING,
    RUNNING,
    STOPPED,
    FAILED,
}

internal data class PlayerSessionUiState(
    val status: PlayerSessionStatus = PlayerSessionStatus.IDLE,
    val entryId: String? = null,
    val sessionId: String? = null,
    val titleName: String? = null,
    val startupStage: String = "idle",
    val detail: String = "Idle",
    val framebufferPath: String = "",
    val frameStreamStatus: String = "idle",
    val frameIndex: Long = -1L,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val frameFreshnessSeconds: Long? = null,
    val fps: Float = 0f,
    val showFpsCounter: Boolean = false,
    val presentationBackend: String = PresentationBackend.FRAMEBUFFER_POLLING.name.lowercase(),
    val renderScaleProfile: String = GuestRenderScaleProfile.ONE.name.lowercase(),
    val internalDisplayResolution: String = "1280x720",
    val errorMessage: String? = null,
) {
    val hasFrame: Boolean
        get() = frameWidth != null && frameHeight != null && frameIndex >= 0
}

private fun AppSettings.toPresentationSettings(): XeniaPresentationSettings {
    return XeniaPresentationSettings(
        presentationBackend = defaultPresentationBackend,
        guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
        internalDisplayResolution = InternalDisplayResolution(1280, 720),
    )
}
