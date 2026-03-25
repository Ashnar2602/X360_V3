package emu.x360.mobile.dev.bootstrap

import android.os.Build
import emu.x360.mobile.dev.runtime.DiagnosticRootAccessState
import emu.x360.mobile.dev.runtime.InstalledTitleContentSummary
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.readText

internal enum class DiagnosticLaunchProfile(
    val wireName: String,
) {
    DEFAULT("default"),
    FRAMEBUFFER_POLLING_DIAGNOSTIC("framebuffer-polling-diagnostic"),
    SHARED_MEMORY_SILENT_AUDIO("shared-memory-silent-audio"),
}

internal data class PlayerSessionLaunchContext(
    val entryId: String,
    val entryDisplayName: String,
    val diagnosticProfile: DiagnosticLaunchProfile,
    val guestTitlePath: String,
    val presentationSettings: XeniaPresentationSettings,
    val resolvedMesaRuntime: ResolvedMesaRuntime,
    val storageRoots: List<DiagnosticRootAccessState>,
    val uriPermissionPresent: Boolean,
)

internal data class PlayerSessionStallEvidence(
    val sessionAlive: Boolean,
    val startupAnalysis: XeniaStartupAnalysis,
    val outputPreview: OutputPreviewState,
    val presentationMetrics: PresentationPerformanceMetrics,
    val inputDiagnostics: PlayerInputDiagnostics,
    val storageRoots: List<DiagnosticRootAccessState>,
)

internal object PlayerSessionStallClassifier {
    fun classify(evidence: PlayerSessionStallEvidence): Pair<ProgressionStallClassification, String> {
        val storageFailure = evidence.storageRoots.firstOrNull { root ->
            !root.exists || !root.readable || (!root.writable && root.label != "title-portal")
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
        val root = directories.diagnosticsLogs
        if (!root.exists()) {
            return null
        }
        val latest = Files.list(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bundle.json") }
                .max { left, right -> Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right)) }
                .orElse(null)
        } ?: return null
        return runCatching { PlayerSessionDiagnosticsBundleCodec.decode(latest.readText()) }.getOrNull()
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
        presentationBackend = launchContext.presentationSettings.presentationBackend.name.lowercase(),
        guestRenderScaleProfile = launchContext.presentationSettings.guestRenderScaleProfile.name.lowercase(),
        internalDisplayResolution = "${launchContext.presentationSettings.internalDisplayResolution.width}x${launchContext.presentationSettings.internalDisplayResolution.height}",
        readbackResolveMode = readbackResolve,
        apuBackend = apuBackend,
        mesaBranch = launchContext.resolvedMesaRuntime.branch.name.lowercase(),
        mesaReason = launchContext.resolvedMesaRuntime.reason,
        diagnosticProfile = launchContext.diagnosticProfile.wireName,
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
        decodeFps = metrics.decodeFps,
        visiblePresentFps = metrics.presentFps,
        visibleFps = metrics.visibleFps,
        transportFrameHash = metrics.transportFrameHash,
        visibleFrameHash = metrics.visibleFrameHash,
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
