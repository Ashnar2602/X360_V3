package emu.x360.mobile.dev.bootstrap

import android.os.Build
import emu.x360.mobile.dev.runtime.AndroidVideoFreezeCause
import emu.x360.mobile.dev.runtime.DiagnosticRootAccessState
import emu.x360.mobile.dev.runtime.InstalledTitleContentSummary
import emu.x360.mobile.dev.runtime.MovieAudioDebugSnapshot
import emu.x360.mobile.dev.runtime.PlayerSessionDiagnosticsBundle
import emu.x360.mobile.dev.runtime.PlayerSessionDiagnosticsBundleCodec
import emu.x360.mobile.dev.runtime.PlayerSessionHostSnapshot
import emu.x360.mobile.dev.runtime.PlayerSessionInputSnapshot
import emu.x360.mobile.dev.runtime.PlayerSessionLaunchSummary
import emu.x360.mobile.dev.runtime.PlayerSessionPatchState
import emu.x360.mobile.dev.runtime.PlayerSessionPresentationSnapshot
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.ProgressionStallClassification
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.TitleContentEntry
import emu.x360.mobile.dev.runtime.VideoFreezeConfidenceReport
import emu.x360.mobile.dev.runtime.VideoFreezeEvidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.readText

internal enum class DiagnosticLaunchProfile(
    val wireName: String,
) {
    XENIA_REAL_TITLE("xenia-real-title"),
    FRAMEBUFFER_POLLING_DIAGNOSTIC("framebuffer-polling-diagnostic"),
    SHARED_MEMORY_SILENT_AUDIO("shared-memory-silent-audio"),
    AUDIO_MUTED("audio-muted"),
    XMA_FAKE("xma-fake"),
    XMA_OLD("xma-old"),
    XMA_SINGLE_THREAD("xma-single-thread"),
    APP_SYNTHETIC_SOURCE("app-synthetic-source"),
    GUEST_SYNTHETIC_SOURCE("guest-synthetic-source"),
    XENIA_SYNTHETIC_EXPORT_SOURCE("xenia-synthetic-export-source"),

    ;

    companion object {
        fun fromWireName(wireName: String?): DiagnosticLaunchProfile {
            return entries.firstOrNull { it.wireName == wireName }
                ?: XENIA_REAL_TITLE
        }
    }
}

internal data class PlayerSessionLaunchContext(
    val entryId: String,
    val entryDisplayName: String,
    val diagnosticProfile: DiagnosticLaunchProfile,
    val guestTitlePath: String,
    val marketplaceContentPolicy: MarketplaceContentPolicy,
    val presentationSettings: XeniaPresentationSettings,
    val resolvedMesaRuntime: ResolvedMesaRuntime,
    val storageRoots: List<DiagnosticRootAccessState>,
    val uriPermissionPresent: Boolean,
    val inputMuted: Boolean = false,
    val overlayHidden: Boolean = false,
) {
    val freezeLabSource: String
        get() = when (diagnosticProfile) {
            DiagnosticLaunchProfile.FRAMEBUFFER_POLLING_DIAGNOSTIC,
            DiagnosticLaunchProfile.SHARED_MEMORY_SILENT_AUDIO,
            DiagnosticLaunchProfile.AUDIO_MUTED,
            DiagnosticLaunchProfile.XMA_FAKE,
            DiagnosticLaunchProfile.XMA_OLD,
            DiagnosticLaunchProfile.XMA_SINGLE_THREAD,
            -> DiagnosticLaunchProfile.XENIA_REAL_TITLE.wireName
            else -> diagnosticProfile.wireName
        }
}

internal fun XeniaPresentationSettings.applyDiagnosticProfile(
    diagnosticProfile: DiagnosticLaunchProfile,
): XeniaPresentationSettings = when (diagnosticProfile) {
    DiagnosticLaunchProfile.XENIA_REAL_TITLE -> this
    DiagnosticLaunchProfile.FRAMEBUFFER_POLLING_DIAGNOSTIC -> XeniaPresentationSettings.FramebufferPollingPerformance.copy(
        presentationBackend = emu.x360.mobile.dev.runtime.PresentationBackend.FRAMEBUFFER_POLLING,
        readbackResolveMode = XeniaReadbackResolveMode.FULL,
    )
    DiagnosticLaunchProfile.SHARED_MEMORY_SILENT_AUDIO -> XeniaPresentationSettings.FramebufferSharedMemory.copy(
        apuBackend = XeniaApuBackend.NOP,
    )
    DiagnosticLaunchProfile.AUDIO_MUTED -> copy(muteAudioOutput = true)
    DiagnosticLaunchProfile.XMA_FAKE -> copy(xmaDecoderMode = XeniaXmaDecoderMode.FAKE)
    DiagnosticLaunchProfile.XMA_OLD -> copy(xmaDecoderMode = XeniaXmaDecoderMode.OLD)
    DiagnosticLaunchProfile.XMA_SINGLE_THREAD -> copy(useDedicatedXmaThread = false)
    DiagnosticLaunchProfile.APP_SYNTHETIC_SOURCE,
    DiagnosticLaunchProfile.GUEST_SYNTHETIC_SOURCE,
    DiagnosticLaunchProfile.XENIA_SYNTHETIC_EXPORT_SOURCE,
    -> copy(
        presentationBackend = when (presentationBackend) {
            emu.x360.mobile.dev.runtime.PresentationBackend.HEADLESS_ONLY ->
                emu.x360.mobile.dev.runtime.PresentationBackend.FRAMEBUFFER_POLLING
            else -> presentationBackend
        },
    )
}

internal data class PlayerSessionStallEvidence(
    val sessionAlive: Boolean,
    val startupAnalysis: XeniaStartupAnalysis,
    val outputPreview: OutputPreviewState,
    val presentationMetrics: PresentationPerformanceMetrics,
    val inputDiagnostics: PlayerInputDiagnostics,
    val storageRoots: List<DiagnosticRootAccessState>,
)

internal val NonBlockingDiagnosticRootLabels = setOf(
    "title-portal",
    "patches-root",
    "input-transport",
    "presentation-transport",
)

internal fun DiagnosticRootAccessState.isBlockingFailure(): Boolean {
    if (label in NonBlockingDiagnosticRootLabels) {
        return false
    }
    return !exists || !readable || !writable
}

internal object PlayerSessionStallClassifier {
    fun classify(evidence: PlayerSessionStallEvidence): Pair<ProgressionStallClassification, String> {
        val storageFailure = evidence.storageRoots.firstOrNull { root ->
            root.isBlockingFailure()
        }
        if (storageFailure != null) {
            return ProgressionStallClassification.HOST_PERMISSION_OR_STORAGE_FAILURE to
                "${storageFailure.label}:${storageFailure.detail}"
        }

        if (!evidence.sessionAlive) {
            return ProgressionStallClassification.FEX_OR_GUEST_PROCESS_FAILURE to
                "guest-process-not-alive"
        }

        evidence.startupAnalysis.lastContentMiss?.let { lastContentMiss ->
            return ProgressionStallClassification.CONTENT_OR_PROFILE_BLOCKED to lastContentMiss
        }

        val movieAudioLoop = evidence.startupAnalysis.movieDecodeThreadCount >= 2 &&
            evidence.startupAnalysis.audioClientRegisterCount >= 2
        if (movieAudioLoop) {
            val reason = buildString {
                append("movie-audio-loop movie-threads=${evidence.startupAnalysis.movieDecodeThreadCount}")
                append(" audio-clients=${evidence.startupAnalysis.audioClientRegisterCount}")
                evidence.startupAnalysis.lastMovieAudioState?.let { append(" last=$it") }
            }
            return ProgressionStallClassification.GUEST_PROGRESS_STALLED to reason
        }

        val rootProbeNearFreeze = evidence.startupAnalysis.emptyDiscResolveCount >= 8 &&
            evidence.startupAnalysis.lastTrueFileMiss != null &&
            evidence.startupAnalysis.lastMeaningfulTransition == evidence.startupAnalysis.lastRootProbe
        if (rootProbeNearFreeze) {
            val reason = buildString {
                append("media-file-miss-near-root-probe count=${evidence.startupAnalysis.emptyDiscResolveCount}")
                evidence.startupAnalysis.lastTrueFileMiss?.let { append(" miss=$it") }
                evidence.startupAnalysis.lastRootProbe?.let { append(" probe=$it") }
            }
            return ProgressionStallClassification.GUEST_PROGRESS_STALLED to reason
        }

        if (evidence.startupAnalysis.movieDecodeThreadCount >= 2) {
            val reason = buildString {
                append("movie-thread-churn count=${evidence.startupAnalysis.movieDecodeThreadCount}")
                if (evidence.startupAnalysis.audioClientRegisterCount > 0) {
                    append(" audio-clients=${evidence.startupAnalysis.audioClientRegisterCount}")
                }
                evidence.startupAnalysis.lastMovieAudioState?.let { append(" last=$it") }
            }
            return ProgressionStallClassification.GUEST_PROGRESS_STALLED to reason
        }

        val metrics = evidence.presentationMetrics
        if (metrics.swapFps > 1f && metrics.captureFps > 1f && metrics.exportFps > 1f && metrics.presentFps <= 1f) {
            return ProgressionStallClassification.PRESENTATION_STALE_BUT_GUEST_RUNNING to
                "guest-swaps-active-visible-presents-idle"
        }

        if (metrics.exportFps > 1f && metrics.presentFps > 1f && evidence.outputPreview.freshnessSeconds != null &&
            evidence.outputPreview.freshnessSeconds > 3L
        ) {
            return ProgressionStallClassification.TRANSPORT_OR_PLAYER_SESSION_DESYNC to
                "player-presents-active-but-frame-preview-stale"
        }

        if (evidence.startupAnalysis.stage.reaches(emu.x360.mobile.dev.runtime.XeniaStartupStage.TITLE_MODULE_LOADING) &&
            metrics.swapFps <= 1f && metrics.captureFps <= 1f && metrics.exportFps <= 1f
        ) {
            return ProgressionStallClassification.GPU_OR_READBACK_PIPELINE_STALLED to
                "title-loaded-without-frame-pipeline-activity"
        }

        if (evidence.startupAnalysis.stage.reaches(emu.x360.mobile.dev.runtime.XeniaStartupStage.TITLE_MODULE_LOADING)) {
            return ProgressionStallClassification.GUEST_PROGRESS_STALLED to
                (evidence.startupAnalysis.lastMeaningfulTransition ?: evidence.startupAnalysis.detail)
        }

        return ProgressionStallClassification.UNKNOWN to evidence.startupAnalysis.detail
    }
}

internal object VideoFreezeClassifier {
    fun classify(
        evidence: VideoFreezeEvidence,
        progressionBucket: ProgressionStallClassification,
    ): VideoFreezeConfidenceReport {
        val corroboratingSignals = mutableListOf<String>()
        if (evidence.storageFailureLabels.isNotEmpty()) {
            corroboratingSignals += "storage=${evidence.storageFailureLabels.joinToString(",")}"
            evidence.lastLifecycleEvent.takeIf { it.isNotBlank() }?.let {
                corroboratingSignals += "lifecycle=$it"
            }
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.HOST_STORAGE_PERMISSION_OR_SESSION_DESYNC,
                confidencePercent = 96,
                reason = "storage-root-or-session-access-failed",
                corroboratingSignals = corroboratingSignals,
            )
        }

        val guestMoving = evidence.guestSwapFps > 1f || evidence.captureFps > 1f || evidence.transportPublishFps > 1f
        val transportMoving = evidence.transportPublishFps > 1f || evidence.transportChangeFps > 1f
        val presenterMoving = evidence.visiblePresentFps > 1f
        val screenMoving = evidence.screenChangeFps > 1f || evidence.visibleChangeFps > 1f
        val screenStatic = !screenMoving
        val transportStatic = !transportMoving
        val inputSpike = evidence.inputEventsPerSecond >= 20f
        val uiStarved = evidence.uiLongestFrameMillis >= 700L || evidence.uiLongFrameCount >= 3L
        val guestBlockedByCalls = sequenceOf(
            evidence.lastContentCallResult,
            evidence.lastXamCallResult,
            evidence.lastXliveCallResult,
            evidence.lastXnetCallResult,
            evidence.lastMeaningfulGuestTransition,
        ).filterNotNull().any { signal ->
            signal.contains("content", ignoreCase = true) ||
                signal.contains("xam", ignoreCase = true) ||
                signal.contains("xlive", ignoreCase = true) ||
                signal.contains("xnet", ignoreCase = true)
        }
        val mediaFileMissNearFreeze = evidence.lastTrueFileMiss != null &&
            (progressionBucket == ProgressionStallClassification.CONTENT_OR_PROFILE_BLOCKED ||
                evidence.lastMeaningfulGuestTransition == evidence.lastTrueFileMiss)
        val rootProbeNearFreeze = evidence.emptyDiscResolveCount >= 8 &&
            evidence.lastTrueFileMiss != null &&
            evidence.lastMeaningfulGuestTransition == evidence.lastRootProbe
        val movieAudioLoop = evidence.movieThreadBurstCount >= 2 &&
            evidence.audioClientRegisterCount >= 2
        val movieThreadChurn = evidence.movieDecodeThreadCount >= 2

        if (screenStatic && movieAudioLoop) {
            corroboratingSignals += "movie-thread-count=${evidence.movieThreadBurstCount}"
            corroboratingSignals += "audio-client-count=${evidence.audioClientRegisterCount}"
            evidence.lastMovieAudioState?.let { corroboratingSignals += "movie-audio=$it" }
            evidence.lastMeaningfulGuestTransition?.let { corroboratingSignals += "transition=$it" }
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO,
                confidencePercent = 95,
                reason = "guest-stalled-during-movie-audio-loop",
                corroboratingSignals = corroboratingSignals.take(5),
            )
        }

        if (screenStatic && mediaFileMissNearFreeze) {
            evidence.lastTrueFileMiss?.let { corroboratingSignals += "miss=$it" }
            evidence.lastMeaningfulGuestTransition?.let { corroboratingSignals += "transition=$it" }
            evidence.lastDiscResolveLine?.let { corroboratingSignals += "disc=$it" }
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO,
                confidencePercent = 93,
                reason = "guest-stalled-during-media-file-resolution",
                corroboratingSignals = corroboratingSignals.take(5),
            )
        }

        if (screenStatic && rootProbeNearFreeze) {
            corroboratingSignals += "root-probe-count=${evidence.emptyDiscResolveCount}"
            evidence.lastRootProbe?.let { corroboratingSignals += "probe=$it" }
            evidence.lastTrueFileMiss?.let { corroboratingSignals += "miss=$it" }
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO,
                confidencePercent = 90,
                reason = "guest-stalled-near-root-probe-after-file-miss",
                corroboratingSignals = corroboratingSignals.take(5),
            )
        }

        if (screenStatic && movieThreadChurn) {
            corroboratingSignals += "movie-thread-count=${evidence.movieDecodeThreadCount}"
            if (evidence.audioClientRegisterCount > 0) {
                corroboratingSignals += "audio-client-count=${evidence.audioClientRegisterCount}"
            }
            evidence.lastMovieAudioState?.let { corroboratingSignals += "movie-audio=$it" }
            evidence.lastMeaningfulGuestTransition?.let { corroboratingSignals += "transition=$it" }
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO,
                confidencePercent = 91,
                reason = "guest-stalled-during-movie-thread-churn",
                corroboratingSignals = corroboratingSignals.take(5),
            )
        }

        if (transportMoving && presenterMoving && screenStatic && guestMoving) {
            corroboratingSignals += "guest-moving"
            corroboratingSignals += "transport-moving"
            corroboratingSignals += "presenter-moving"
            corroboratingSignals += "screen-static"
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.ANDROID_PRESENTER_STALE,
                confidencePercent = 93,
                reason = "transport-and-present-counters-advance-while-screen-stays-static",
                corroboratingSignals = corroboratingSignals,
            )
        }

        if (guestMoving && transportStatic && screenStatic) {
            corroboratingSignals += "guest-moving"
            corroboratingSignals += "transport-static"
            corroboratingSignals += "screen-static"
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.TRANSPORT_EXPORT_STALE,
                confidencePercent = 92,
                reason = "guest-swaps-or-capture-continue-but-transport-does-not-change",
                corroboratingSignals = corroboratingSignals,
            )
        }

        if (inputSpike && uiStarved && screenStatic) {
            corroboratingSignals += "input-events-per-second=${evidence.inputEventsPerSecond}"
            corroboratingSignals += "ui-longest-frame-ms=${evidence.uiLongestFrameMillis}"
            corroboratingSignals += "screen-static"
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.INPUT_OR_UI_THREAD_STARVATION,
                confidencePercent = 91,
                reason = "input-rate-and-ui-stall-signals-spiked-during-screen-freeze",
                corroboratingSignals = corroboratingSignals,
            )
        }

        if (progressionBucket == ProgressionStallClassification.CONTENT_OR_PROFILE_BLOCKED ||
            (screenStatic && guestBlockedByCalls)
        ) {
            evidence.lastMeaningfulGuestTransition?.let { corroboratingSignals += "transition=$it" }
            evidence.lastContentCallResult?.let { corroboratingSignals += "content=$it" }
            evidence.lastXliveCallResult?.let { corroboratingSignals += "xlive=$it" }
            evidence.lastXnetCallResult?.let { corroboratingSignals += "xnet=$it" }
            corroboratingSignals += "screen-static"
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO,
                confidencePercent = 94,
                reason = "guest-transition-stalled-around-content-or-xam-xlive-xnet-calls",
                corroboratingSignals = corroboratingSignals.take(5),
            )
        }

        if (evidence.outputFrameIndex >= 0L && !transportMoving && !guestMoving && presenterMoving) {
            corroboratingSignals += "title-past-first-frame"
            corroboratingSignals += "guest-export-idle"
            corroboratingSignals += "presenter-moving"
            return VideoFreezeConfidenceReport(
                cause = AndroidVideoFreezeCause.XENIA_READBACK_OR_EXPORT_PIPELINE_STALLED,
                confidencePercent = 90,
                reason = "frame-pipeline-went-idle-before-transport-changes-could-reach-screen",
                corroboratingSignals = corroboratingSignals,
            )
        }

        return VideoFreezeConfidenceReport(
            cause = AndroidVideoFreezeCause.UNKNOWN,
            confidencePercent = if (guestMoving || presenterMoving) 45 else 20,
            reason = "insufficient-multi-layer-agreement",
            corroboratingSignals = buildList {
                if (guestMoving) add("guest-moving")
                if (transportMoving) add("transport-moving")
                if (presenterMoving) add("presenter-moving")
                if (screenMoving) add("screen-moving")
                evidence.lastMeaningfulGuestTransition?.let { add("transition=$it") }
            },
        )
    }
}

internal class PlayerSessionDiagnosticsStore(
    private val directories: RuntimeDirectories,
) {
    fun pathFor(sessionId: String): Path =
        directories.diagnosticsLogs.resolve("session-$sessionId.bundle.json")

    fun save(bundle: PlayerSessionDiagnosticsBundle): Path {
        val path = pathFor(bundle.sessionId)
        path.parent?.createDirectories()
        path.toFile().writeText(PlayerSessionDiagnosticsBundleCodec.encode(bundle))
        return path
    }

    fun load(sessionId: String): PlayerSessionDiagnosticsBundle? {
        val path = pathFor(sessionId)
        if (!path.exists()) {
            return null
        }
        return runCatching { PlayerSessionDiagnosticsBundleCodec.decode(path.readText()) }.getOrNull()
    }

    fun latest(): PlayerSessionDiagnosticsBundle? {
        return latestBundles(limit = 1).firstOrNull()
    }

    fun latestBundles(
        limit: Int,
    ): List<PlayerSessionDiagnosticsBundle> {
        val root = directories.diagnosticsLogs
        if (!root.exists() || limit <= 0) {
            return emptyList()
        }
        val latest = buildList {
            Files.list(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bundle.json") }
                    .sorted { left, right -> Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left)) }
                    .limit(limit.toLong())
                    .forEachOrdered { add(it) }
            }
        }
        return latest.mapNotNull { path ->
            runCatching { PlayerSessionDiagnosticsBundleCodec.decode(path.readText()) }.getOrNull()
        }
    }
}

internal fun installedContentSummaries(entries: List<TitleContentEntry>): List<InstalledTitleContentSummary> {
    return entries.map { entry ->
        InstalledTitleContentSummary(
            id = entry.id,
            displayName = entry.displayName,
            installStatus = entry.installStatus.name.lowercase(),
            contentType = entry.contentTypeLabel,
            packageSignature = entry.packageSignature,
        )
    }
}

internal fun installedMarketplaceContentCount(entries: List<TitleContentEntry>): Int {
    return entries.count { entry ->
        entry.contentType.equals("00000002", ignoreCase = true) &&
            entry.installStatus.name.equals("INSTALLED", ignoreCase = true)
    }
}

internal fun buildHostSnapshot(
    deviceProperties: DeviceProperties,
    resolvedMesaRuntime: ResolvedMesaRuntime,
    uriPermissionPresent: Boolean,
    sessionAlive: Boolean,
    guestProcessPid: Int?,
    fexLog: String,
    filesDirPath: Path,
): PlayerSessionHostSnapshot {
    val fexWarnings = fexLog.lineSequence()
        .map { it.trim() }
        .filter {
            it.contains("error", ignoreCase = true) ||
                it.contains("warn", ignoreCase = true) ||
                it.contains("SIG", ignoreCase = true) ||
                it.contains("seccomp", ignoreCase = true)
        }
        .take(6)
        .toList()
    return PlayerSessionHostSnapshot(
        board = deviceProperties.board,
        hardware = deviceProperties.hardware,
        socModel = deviceProperties.socModel,
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        androidApiLevel = Build.VERSION.SDK_INT,
        availableAppBytes = runCatching { filesDirPath.toFile().usableSpace }.getOrDefault(-1L),
        guestProcessAlive = sessionAlive,
        guestProcessPid = guestProcessPid,
        mesaBranch = resolvedMesaRuntime.branch.name.lowercase(),
        mesaReason = resolvedMesaRuntime.reason,
        uriPermissionPresent = uriPermissionPresent,
        fexWarningSummary = fexWarnings,
    )
}

internal fun buildLaunchSummary(
    request: emu.x360.mobile.dev.runtime.GuestLaunchRequest,
    launchContext: PlayerSessionLaunchContext,
): PlayerSessionLaunchSummary {
    val readbackResolve = request.args
        .firstOrNull { it.startsWith("--readback_resolve=") }
        ?.substringAfter('=')
        ?: "none"
    val apuBackend = request.args
        .firstOrNull { it.startsWith("--apu=") }
        ?.substringAfter('=')
        ?: launchContext.presentationSettings.apuBackend.cliValue
    val environmentSummary = request.environment
        .filterKeys { key ->
            key.startsWith("X360_") ||
                key == "HOME" ||
                key == "VK_ICD_FILENAMES" ||
                key == "VK_DRIVER_FILES" ||
                key == "SDL_AUDIODRIVER" ||
                key == "MESA_LOADER_DRIVER_OVERRIDE"
        }
        .toSortedMap()
    return PlayerSessionLaunchSummary(
        entryId = launchContext.entryId,
        entryDisplayName = launchContext.entryDisplayName,
        guestTitlePath = launchContext.guestTitlePath,
        freezeLabSource = launchContext.freezeLabSource,
        presentationBackend = launchContext.presentationSettings.presentationBackend.name.lowercase(),
        guestRenderScaleProfile = launchContext.presentationSettings.guestRenderScaleProfile.name.lowercase(),
        internalDisplayResolution = "${launchContext.presentationSettings.internalDisplayResolution.width}x${launchContext.presentationSettings.internalDisplayResolution.height}",
        readbackResolveMode = readbackResolve,
        apuBackend = apuBackend,
        mesaBranch = launchContext.resolvedMesaRuntime.branch.name.lowercase(),
        mesaReason = launchContext.resolvedMesaRuntime.reason,
        diagnosticProfile = launchContext.diagnosticProfile.wireName,
        dlcPolicy = launchContext.marketplaceContentPolicy.wireValue,
        inputMuted = launchContext.inputMuted,
        overlayHidden = launchContext.overlayHidden,
        args = request.args,
        environmentSummary = environmentSummary,
    )
}

internal fun buildPresentationSnapshot(
    outputPreview: OutputPreviewState,
    metrics: PresentationPerformanceMetrics,
): PlayerSessionPresentationSnapshot {
    return PlayerSessionPresentationSnapshot(
        framebufferPath = outputPreview.framebufferPath,
        frameStreamStatus = outputPreview.status,
        frameWidth = outputPreview.width ?: 0,
        frameHeight = outputPreview.height ?: 0,
        frameIndex = outputPreview.frameIndex,
        frameFreshnessSeconds = outputPreview.freshnessSeconds,
        issueSwapCount = metrics.issueSwapCount,
        captureSuccessCount = metrics.captureSuccessCount,
        exportFrameCount = metrics.exportFrameCount,
        decodedFrameCount = metrics.decodedFrameCount,
        presentedFrameCount = metrics.presentedFrameCount,
        guestSwapFps = metrics.swapFps,
        captureFps = metrics.captureFps,
        transportPublishFps = metrics.exportFps,
        transportChangeFps = metrics.transportChangeFps,
        decodeFps = metrics.decodeFps,
        visiblePresentFps = metrics.presentFps,
        visibleChangeFps = metrics.visibleChangeFps,
        visibleFps = metrics.visibleFps,
        screenChangeFps = metrics.screenChangeFps,
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
        lastLifecycleEvent = metrics.lastLifecycleEvent,
        lastSurfaceEvent = metrics.lastSurfaceEvent,
    )
}

internal fun buildInputSnapshot(
    diagnostics: PlayerInputDiagnostics,
): PlayerSessionInputSnapshot {
    return PlayerSessionInputSnapshot(
        controllerConnected = diagnostics.controllerConnected,
        controllerName = diagnostics.controllerName,
        lastInputSequence = diagnostics.lastInputSequence,
        lastInputAgeMs = diagnostics.lastInputAgeMs,
        inputEventsPerSecond = diagnostics.inputEventsPerSecond,
    )
}

internal fun buildMovieAudioSnapshot(
    startupAnalysis: XeniaStartupAnalysis,
): MovieAudioDebugSnapshot {
    return MovieAudioDebugSnapshot(
        playerState = startupAnalysis.lastMovieAudioState ?: "unavailable",
        activeAudioClients = startupAnalysis.audioClientRegisterCount,
        audioClientRegisterCount = startupAnalysis.audioClientRegisterCount,
        movieThreadBurstCount = startupAnalysis.movieThreadBurstCount,
        lastThreadChurnEvent = startupAnalysis.threadCreationEvents.lastOrNull(),
        lastMediaEvent = startupAnalysis.audioClientEvents.lastOrNull(),
        lastVfsEvent = startupAnalysis.vfsAccessTimeline.lastOrNull(),
    )
}

internal fun pathGuestAccessState(
    label: String,
    hostPath: Path,
    guestPath: String? = null,
    requireWritable: Boolean,
): DiagnosticRootAccessState {
    val exists = hostPath.exists()
    val readable = exists && hostPath.isReadable()
    val writable = if (!exists) {
        false
    } else if (!requireWritable) {
        true
    } else {
        canWriteToPath(hostPath)
    }
    return DiagnosticRootAccessState(
        label = label,
        hostPath = hostPath.toString(),
        guestPath = guestPath,
        exists = exists,
        readable = readable,
        writable = writable,
        detail = buildString {
            append(if (exists) "exists" else "missing")
            append(',')
            append(if (readable) "readable" else "not-readable")
            if (requireWritable) {
                append(',')
                append(if (writable) "writable" else "not-writable")
            }
        },
    )
}

private fun canWriteToPath(path: Path): Boolean {
    return runCatching {
        val target = if (Files.isDirectory(path)) {
            path.resolve(".x360-write-probe")
        } else {
            path.parent?.resolve(".x360-write-probe") ?: path.resolveSibling(".x360-write-probe")
        }
        target.parent?.createDirectories()
        target.toFile().writeText("ok")
        Files.deleteIfExists(target)
        true
    }.getOrDefault(false)
}
