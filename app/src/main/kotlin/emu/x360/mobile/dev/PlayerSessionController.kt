package emu.x360.mobile.dev

import android.content.Context
import emu.x360.mobile.dev.bootstrap.ActivePlayerSessionHandle
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.DiagnosticLaunchProfile
import emu.x360.mobile.dev.bootstrap.GuestPresentationMetricsParser
import emu.x360.mobile.dev.bootstrap.InternalDisplayResolution
import emu.x360.mobile.dev.bootstrap.SharedFramePresentationReader
import emu.x360.mobile.dev.bootstrap.XeniaPresentationSettings
import emu.x360.mobile.dev.bootstrap.applyDiagnosticProfile
import emu.x360.mobile.dev.runtime.AppSettings
import emu.x360.mobile.dev.runtime.ExitClassification
import emu.x360.mobile.dev.runtime.GuestRenderScaleProfile
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object PlayerSessionController {
    private const val DiagnosticsPollIntervalMs = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(PlayerSessionUiState())
    private val controllerUpdateFlow = MutableSharedFlow<PlayerControllerInputUpdate>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var activeSession: ActivePlayerSessionHandle? = null
    private var monitorJob: Job? = null
    private var latestControllerUpdate: PlayerControllerInputUpdate? = null

    val state: StateFlow<PlayerSessionUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            controllerUpdateFlow.collectLatest { update ->
                applyControllerUpdate(update)
            }
        }
    }

    fun start(
        context: Context,
        entryId: String,
        settings: AppSettings,
        diagnosticProfile: DiagnosticLaunchProfile = DiagnosticLaunchProfile.XENIA_REAL_TITLE,
        inputMuted: Boolean = false,
        overlayHidden: Boolean = false,
    ) {
        stop(context, "replaced-session")
        val presentationSettings = settings.toPresentationSettings(diagnosticProfile)
        mutableState.value = PlayerSessionUiState(
            status = PlayerSessionStatus.LAUNCHING,
            entryId = entryId,
            showFpsCounter = settings.showFpsCounter,
            presentationBackend = settings.defaultPresentationBackend.name.lowercase(),
            renderScaleProfile = settings.defaultRenderScaleProfile.name.lowercase(),
            internalDisplayResolution = "1280x720",
            playerPollIntervalMs = presentationSettings.playerPollIntervalMs,
            keepLastVisibleFrame = presentationSettings.keepLastVisibleFrame,
            controllerConnected = latestControllerUpdate?.controllerState?.connected ?: false,
            controllerName = latestControllerUpdate?.controllerName,
            inputMuted = inputMuted,
            overlayHidden = overlayHidden,
            detail = "Starting player session",
        )
        scope.launch {
            val manager = AppRuntimeManager(context.applicationContext)
            val startResult = manager.startPlayerSession(
                entryId = entryId,
                presentationSettings = presentationSettings,
                diagnosticProfile = diagnosticProfile,
                inputMuted = inputMuted,
                overlayHidden = overlayHidden,
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
                    inputMuted = inputMuted,
                    overlayHidden = overlayHidden,
                    detail = startResult.detail,
                    errorMessage = startResult.detail,
                )
                return@launch
            }

            activeSession = session
            latestControllerUpdate?.let { pendingUpdate ->
                val diagnostics = session.submitControllerState(
                    controllerState = pendingUpdate.controllerState,
                    controllerName = pendingUpdate.controllerName,
                    inputEventsPerSecond = pendingUpdate.inputEventsPerSecond,
                )
                mutableState.update { current ->
                    current.copy(
                        controllerConnected = diagnostics.controllerConnected,
                        controllerName = diagnostics.controllerName?.takeIf { diagnostics.controllerConnected },
                        lastInputSequence = diagnostics.lastInputSequence,
                        lastInputAgeMs = diagnostics.lastInputAgeMs,
                    )
                }
            }
            monitorJob = launch {
                monitorSession(manager, session)
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
            titleId = analysis.titleId,
            moduleHash = analysis.moduleHash,
        )
        manager.capturePlayerSessionDiagnostics(session)
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
                    transportChangeFps = metrics.transportChangeFps,
                    decodeFps = metrics.decodeFps,
                    presentFps = metrics.presentFps,
                    visibleChangeFps = metrics.visibleChangeFps,
                    visibleFps = metrics.visibleFps,
                    screenChangeFps = metrics.screenChangeFps,
                    frameSourceStatus = metrics.frameSourceStatus,
                    transportFrameHash = metrics.transportFrameHash,
                    transportFramePerceptualHash = metrics.transportFramePerceptualHash,
                    visibleFrameHash = metrics.visibleFrameHash,
                    visibleFramePerceptualHash = metrics.visibleFramePerceptualHash,
                    screenFrameHash = metrics.screenFrameHash,
                    screenFramePerceptualHash = metrics.screenFramePerceptualHash,
                    screenBlackRatio = metrics.screenBlackRatio,
                    screenAverageLuma = metrics.screenAverageLuma,
                    presenterSubmittedAtEpochMillis = metrics.presenterSubmittedAtEpochMillis,
                    uiLongFrameCount = metrics.uiLongFrameCount,
                    uiLongestFrameMillis = metrics.uiLongestFrameMillis,
                    inputEventsPerSecond = metrics.inputEventsPerSecond,
                    lastLifecycleEvent = metrics.lastLifecycleEvent,
                    lastSurfaceEvent = metrics.lastSurfaceEvent,
                ),
                fps = metrics.visibleFps,
            )
        }
    }

    fun submitControllerInput(
        update: PlayerControllerInputUpdate,
    ) {
        latestControllerUpdate = update
        mutableState.update { current ->
            val nextConnected = update.controllerState.connected
            val nextName = update.controllerName?.takeIf { nextConnected }
            if (current.controllerConnected == nextConnected && current.controllerName == nextName) {
                current
            } else {
                current.copy(
                    controllerConnected = nextConnected,
                    controllerName = nextName,
                )
            }
        }
        controllerUpdateFlow.tryEmit(update)
    }

    fun clearControllerInput() {
        val clearedUpdate = PlayerControllerInputUpdate(
            controllerState = emu.x360.mobile.dev.runtime.SharedInputControllerState.Disconnected,
            controllerName = null,
        )
        latestControllerUpdate = clearedUpdate
        mutableState.update { current ->
            current.copy(
                controllerConnected = false,
                controllerName = null,
            )
        }
        controllerUpdateFlow.tryEmit(clearedUpdate)
    }

    fun pause(): Boolean {
        val session = activeSession ?: return false
        val paused = session.pause()
        if (paused) {
            mutableState.update {
                it.copy(
                    isPaused = true,
                    detail = "Game paused",
                )
            }
        }
        return paused
    }

    fun resume(): Boolean {
        val session = activeSession ?: return false
        val resumed = session.resume()
        if (resumed) {
            mutableState.update {
                it.copy(
                    isPaused = false,
                    detail = if (it.frameIndex >= 0L) "Frame ${it.frameIndex}" else "Resuming game",
                )
            }
        }
        return resumed
    }

    fun setShowFpsCounter(enabled: Boolean) {
        mutableState.update {
            it.copy(showFpsCounter = enabled)
        }
    }

    fun openPresentationReader(): SharedFramePresentationReader? {
        return activeSession?.openPresentationReader()
    }

    fun captureCurrentDiagnostics(
        context: Context,
    ): String? {
        val session = activeSession ?: return null
        val manager = AppRuntimeManager(context.applicationContext)
        val bundle = manager.capturePlayerSessionDiagnostics(session)
        return manager.latestDiagnosticsBundlePath(bundle.sessionId)
    }

    private fun applyControllerUpdate(
        update: PlayerControllerInputUpdate,
    ) {
        val session = activeSession ?: return
        val diagnostics = session.submitControllerState(
            controllerState = update.controllerState,
            controllerName = update.controllerName,
            inputEventsPerSecond = update.inputEventsPerSecond,
        )
        mutableState.update { current ->
            val nextConnected = diagnostics.controllerConnected
            val nextName = diagnostics.controllerName?.takeIf { nextConnected }
            if (current.controllerConnected == nextConnected && current.controllerName == nextName) {
                current
            } else {
                current.copy(
                    controllerConnected = nextConnected,
                    controllerName = nextName,
                )
            }
        }
    }

    private suspend fun monitorSession(
        manager: AppRuntimeManager,
        session: ActivePlayerSessionHandle,
    ) {
        while (currentCoroutineContext().isActive) {
            val analysis = session.readStartupAnalysis()
            val outputPreview = session.readOutputPreview()
            val inputDiagnostics = session.readInputDiagnostics()
            val diagnosticsBundle = manager.capturePlayerSessionDiagnostics(session)
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
                transportChangeFps = currentState.presentationMetrics.transportChangeFps,
                visibleChangeFps = currentState.presentationMetrics.visibleChangeFps,
                screenChangeFps = currentState.presentationMetrics.screenChangeFps,
                frameSourceStatus = if (currentState.presentationMetrics.frameSourceStatus != "idle") {
                    currentState.presentationMetrics.frameSourceStatus
                } else {
                    guestMetrics.frameSourceStatus
                },
                transportFrameHash = currentState.presentationMetrics.transportFrameHash.ifBlank {
                    guestMetrics.transportFrameHash
                },
                transportFramePerceptualHash = currentState.presentationMetrics.transportFramePerceptualHash,
                visibleFrameHash = currentState.presentationMetrics.visibleFrameHash.ifBlank {
                    guestMetrics.visibleFrameHash
                },
                visibleFramePerceptualHash = currentState.presentationMetrics.visibleFramePerceptualHash,
                screenFrameHash = currentState.presentationMetrics.screenFrameHash,
                screenFramePerceptualHash = currentState.presentationMetrics.screenFramePerceptualHash,
                screenBlackRatio = currentState.presentationMetrics.screenBlackRatio,
                screenAverageLuma = currentState.presentationMetrics.screenAverageLuma,
                presenterSubmittedAtEpochMillis = currentState.presentationMetrics.presenterSubmittedAtEpochMillis,
                uiLongFrameCount = currentState.presentationMetrics.uiLongFrameCount,
                uiLongestFrameMillis = currentState.presentationMetrics.uiLongestFrameMillis,
                inputEventsPerSecond = currentState.presentationMetrics.inputEventsPerSecond,
                lastLifecycleEvent = currentState.presentationMetrics.lastLifecycleEvent,
                lastSurfaceEvent = currentState.presentationMetrics.lastSurfaceEvent,
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
                showFpsCounter = currentState.showFpsCounter,
                isPaused = session.isPaused(),
                presentationBackend = currentState.presentationBackend,
                renderScaleProfile = currentState.renderScaleProfile,
                internalDisplayResolution = currentState.internalDisplayResolution,
                playerPollIntervalMs = currentState.playerPollIntervalMs,
                keepLastVisibleFrame = currentState.keepLastVisibleFrame,
                inputMuted = currentState.inputMuted,
                overlayHidden = currentState.overlayHidden,
                presentationMetrics = mergedMetrics,
                controllerConnected = inputDiagnostics.controllerConnected,
                controllerName = inputDiagnostics.controllerName?.takeIf { inputDiagnostics.controllerConnected },
                lastInputSequence = inputDiagnostics.lastInputSequence,
                lastInputAgeMs = inputDiagnostics.lastInputAgeMs,
                titleId = diagnosticsBundle.titleId,
                moduleHash = diagnosticsBundle.moduleHash,
                patchDatabaseLoadedTitleCount = diagnosticsBundle.patchState.loadedTitleCount,
                progressionBucket = diagnosticsBundle.progressionBucket.name.lowercase(),
                progressionReason = diagnosticsBundle.progressionReason,
                lastMeaningfulTransition = diagnosticsBundle.lastMeaningfulGuestTransition,
                videoFreezeCause = diagnosticsBundle.freezeReport.cause.name.lowercase(),
                videoFreezeConfidencePercent = diagnosticsBundle.freezeReport.confidencePercent,
                videoFreezeReason = diagnosticsBundle.freezeReport.reason,
                diagnosticsBundlePath = manager.latestDiagnosticsBundlePath(session.sessionId),
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
                    titleId = analysis.titleId,
                    moduleHash = analysis.moduleHash,
                )
                manager.capturePlayerSessionDiagnostics(session)
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
    val isPaused: Boolean = false,
    val presentationBackend: String = PresentationBackend.FRAMEBUFFER_POLLING.name.lowercase(),
    val renderScaleProfile: String = GuestRenderScaleProfile.ONE.name.lowercase(),
    val internalDisplayResolution: String = "1280x720",
    val playerPollIntervalMs: Long = 120L,
    val keepLastVisibleFrame: Boolean = true,
    val inputMuted: Boolean = false,
    val overlayHidden: Boolean = false,
    val presentationMetrics: PresentationPerformanceMetrics = PresentationPerformanceMetrics.Empty,
    val controllerConnected: Boolean = false,
    val controllerName: String? = null,
    val lastInputSequence: Long = 0L,
    val lastInputAgeMs: Long? = null,
    val titleId: String? = null,
    val moduleHash: String? = null,
    val patchDatabaseLoadedTitleCount: Int = 0,
    val progressionBucket: String = "unknown",
    val progressionReason: String = "insufficient-evidence",
    val lastMeaningfulTransition: String? = null,
    val videoFreezeCause: String = "unknown",
    val videoFreezeConfidencePercent: Int = 0,
    val videoFreezeReason: String = "insufficient-evidence",
    val diagnosticsBundlePath: String? = null,
    val errorMessage: String? = null,
) {
    val hasFrame: Boolean
        get() = frameWidth != null && frameHeight != null && frameIndex >= 0
}

private fun AppSettings.toPresentationSettings(
    diagnosticProfile: DiagnosticLaunchProfile,
): XeniaPresentationSettings {
    val baseSettings = when (diagnosticProfile) {
        DiagnosticLaunchProfile.XENIA_REAL_TITLE -> when (defaultPresentationBackend) {
            PresentationBackend.HEADLESS_ONLY -> XeniaPresentationSettings.HeadlessBringup
            PresentationBackend.FRAMEBUFFER_SHARED_MEMORY -> XeniaPresentationSettings.FramebufferSharedMemory
            PresentationBackend.FRAMEBUFFER_POLLING,
            PresentationBackend.SURFACE_BRIDGE,
            -> XeniaPresentationSettings.FramebufferPollingPerformance.copy(
                presentationBackend = defaultPresentationBackend,
            )
        }
        DiagnosticLaunchProfile.FRAMEBUFFER_POLLING_DIAGNOSTIC,
        DiagnosticLaunchProfile.SHARED_MEMORY_SILENT_AUDIO,
        DiagnosticLaunchProfile.AUDIO_MUTED,
        DiagnosticLaunchProfile.XMA_FAKE,
        DiagnosticLaunchProfile.XMA_OLD,
        DiagnosticLaunchProfile.XMA_SINGLE_THREAD,
        DiagnosticLaunchProfile.APP_SYNTHETIC_SOURCE,
        DiagnosticLaunchProfile.GUEST_SYNTHETIC_SOURCE,
        DiagnosticLaunchProfile.XENIA_SYNTHETIC_EXPORT_SOURCE,
        -> when (defaultPresentationBackend) {
            PresentationBackend.HEADLESS_ONLY -> XeniaPresentationSettings.FramebufferPollingPerformance
            PresentationBackend.FRAMEBUFFER_SHARED_MEMORY -> XeniaPresentationSettings.FramebufferSharedMemory
            PresentationBackend.FRAMEBUFFER_POLLING,
            PresentationBackend.SURFACE_BRIDGE,
            -> XeniaPresentationSettings.FramebufferPollingPerformance.copy(
                presentationBackend = defaultPresentationBackend,
            )
        }
    }
    return baseSettings.applyDiagnosticProfile(diagnosticProfile).copy(
        guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
        internalDisplayResolution = InternalDisplayResolution(1280, 720),
    )
}
