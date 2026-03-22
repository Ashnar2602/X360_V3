package emu.x360.mobile.dev

import android.content.Context
import emu.x360.mobile.dev.bootstrap.ActivePlayerSessionHandle
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.GuestPresentationMetricsParser
import emu.x360.mobile.dev.bootstrap.InternalDisplayResolution
import emu.x360.mobile.dev.bootstrap.SharedFramePresentationReader
import emu.x360.mobile.dev.bootstrap.XeniaPresentationSettings
import emu.x360.mobile.dev.runtime.AppSettings
import emu.x360.mobile.dev.runtime.ExitClassification
import emu.x360.mobile.dev.runtime.GuestRenderScaleProfile
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
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
    private const val DiagnosticsPollIntervalMs = 1_000L
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
        val presentationSettings = settings.toPresentationSettings()
        mutableState.value = PlayerSessionUiState(
            status = PlayerSessionStatus.LAUNCHING,
            entryId = entryId,
            showFpsCounter = settings.showFpsCounter,
            presentationBackend = settings.defaultPresentationBackend.name.lowercase(),
            renderScaleProfile = settings.defaultRenderScaleProfile.name.lowercase(),
            internalDisplayResolution = "1280x720",
            playerPollIntervalMs = presentationSettings.playerPollIntervalMs,
            keepLastVisibleFrame = presentationSettings.keepLastVisibleFrame,
            detail = "Starting player session",
        )
        scope.launch {
            val manager = AppRuntimeManager(context.applicationContext)
            val startResult = manager.startPlayerSession(
                entryId = entryId,
                presentationSettings = presentationSettings,
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
                    playerPollIntervalMs = presentationSettings.playerPollIntervalMs,
                    keepLastVisibleFrame = presentationSettings.keepLastVisibleFrame,
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

    fun reportVisibleMetrics(metrics: PresentationPerformanceMetrics) {
        val session = activeSession ?: return
        session.reportPlayerMetrics(metrics)
        mutableState.update { current ->
            current.copy(
                presentationMetrics = current.presentationMetrics.copy(
                    exportFrameCount = metrics.exportFrameCount,
                    decodedFrameCount = metrics.decodedFrameCount,
                    presentedFrameCount = metrics.presentedFrameCount,
                    exportFps = metrics.exportFps,
                    decodeFps = metrics.decodeFps,
                    presentFps = metrics.presentFps,
                    visibleFps = metrics.visibleFps,
                    frameSourceStatus = metrics.frameSourceStatus,
                ),
                fps = metrics.visibleFps,
            )
        }
    }

    fun openPresentationReader(): SharedFramePresentationReader? {
        return activeSession?.openPresentationReader()
    }

    private suspend fun monitorSession(
        manager: AppRuntimeManager,
        session: ActivePlayerSessionHandle,
        showFpsCounter: Boolean,
    ) {
        while (currentCoroutineContext().isActive) {
            val analysis = session.readStartupAnalysis()
            val outputPreview = session.readOutputPreview()
            val guestMetrics = GuestPresentationMetricsParser.parse(
                logContent = session.readGuestLog(),
                exportedFrameCount = outputPreview.frameIndex,
                elapsedMillis = session.elapsedMillis(),
                frameSourceStatus = outputPreview.status,
            )
            val currentState = mutableState.value

            val stageDerivedStatus = when (analysis.stage) {
                XeniaStartupStage.FRAME_STREAM_ACTIVE -> "active"
                XeniaStartupStage.FIRST_FRAME_CAPTURED -> "first-frame"
                else -> "idle"
            }
            val streamStatus = when {
                outputPreview.status != "idle" -> outputPreview.status
                currentState.presentationMetrics.frameSourceStatus != "idle" -> currentState.presentationMetrics.frameSourceStatus
                else -> stageDerivedStatus
            }

            val mergedMetrics = guestMetrics.copy(
                decodedFrameCount = currentState.presentationMetrics.decodedFrameCount,
                presentedFrameCount = currentState.presentationMetrics.presentedFrameCount,
                decodeFps = currentState.presentationMetrics.decodeFps,
                presentFps = currentState.presentationMetrics.presentFps,
                visibleFps = currentState.presentationMetrics.visibleFps,
                frameSourceStatus = if (currentState.presentationMetrics.frameSourceStatus != "idle") {
                    currentState.presentationMetrics.frameSourceStatus
                } else {
                    guestMetrics.frameSourceStatus
                },
            )

            val nextState = PlayerSessionUiState(
                status = if (session.isAlive()) PlayerSessionStatus.RUNNING else PlayerSessionStatus.STOPPED,
                entryId = session.entryId,
                sessionId = session.sessionId,
                titleName = analysis.titleName ?: session.entryDisplayName,
                startupStage = analysis.stage.name.lowercase(),
                detail = analysis.detail,
                framebufferPath = outputPreview.framebufferPath,
                frameStreamStatus = streamStatus,
                frameIndex = outputPreview.frameIndex,
                frameWidth = outputPreview.width,
                frameHeight = outputPreview.height,
                frameFreshnessSeconds = outputPreview.freshnessSeconds,
                fps = mergedMetrics.visibleFps,
                showFpsCounter = showFpsCounter,
                presentationBackend = currentState.presentationBackend,
                renderScaleProfile = currentState.renderScaleProfile,
                internalDisplayResolution = currentState.internalDisplayResolution,
                playerPollIntervalMs = currentState.playerPollIntervalMs,
                keepLastVisibleFrame = currentState.keepLastVisibleFrame,
                presentationMetrics = mergedMetrics,
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

            delay(maxOf(currentState.playerPollIntervalMs, DiagnosticsPollIntervalMs))
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
    val presentationBackend: String = PresentationBackend.FRAMEBUFFER_SHARED_MEMORY.name.lowercase(),
    val renderScaleProfile: String = GuestRenderScaleProfile.ONE.name.lowercase(),
    val internalDisplayResolution: String = "1280x720",
    val playerPollIntervalMs: Long = 120L,
    val keepLastVisibleFrame: Boolean = true,
    val presentationMetrics: PresentationPerformanceMetrics = PresentationPerformanceMetrics.Empty,
    val errorMessage: String? = null,
) {
    val hasFrame: Boolean
        get() = frameWidth != null && frameHeight != null && frameIndex >= 0
}

private fun AppSettings.toPresentationSettings(): XeniaPresentationSettings {
    return when (defaultPresentationBackend) {
        PresentationBackend.HEADLESS_ONLY -> XeniaPresentationSettings.HeadlessBringup
        PresentationBackend.FRAMEBUFFER_SHARED_MEMORY -> XeniaPresentationSettings.FramebufferSharedMemory.copy(
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
        )
        PresentationBackend.FRAMEBUFFER_POLLING,
        PresentationBackend.SURFACE_BRIDGE,
        -> XeniaPresentationSettings.FramebufferPollingPerformance.copy(
            presentationBackend = defaultPresentationBackend,
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
        )
    }
}
