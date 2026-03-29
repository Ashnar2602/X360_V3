package emu.x360.mobile.dev.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProgressionStallClassification {
    @SerialName("guest-progress-stalled")
    GUEST_PROGRESS_STALLED,

    @SerialName("content-or-profile-blocked")
    CONTENT_OR_PROFILE_BLOCKED,

    @SerialName("presentation-stale-but-guest-running")
    PRESENTATION_STALE_BUT_GUEST_RUNNING,

    @SerialName("transport-or-player-session-desync")
    TRANSPORT_OR_PLAYER_SESSION_DESYNC,

    @SerialName("gpu-or-readback-pipeline-stalled")
    GPU_OR_READBACK_PIPELINE_STALLED,

    @SerialName("host-permission-or-storage-failure")
    HOST_PERMISSION_OR_STORAGE_FAILURE,

    @SerialName("fex-or-guest-process-failure")
    FEX_OR_GUEST_PROCESS_FAILURE,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class AndroidVideoFreezeCause {
    @SerialName("android-presenter-stale")
    ANDROID_PRESENTER_STALE,

    @SerialName("transport-export-stale")
    TRANSPORT_EXPORT_STALE,

    @SerialName("xenia-readback-or-export-pipeline-stalled")
    XENIA_READBACK_OR_EXPORT_PIPELINE_STALLED,

    @SerialName("guest-progress-stalled-not-android-video")
    GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO,

    @SerialName("input-or-ui-thread-starvation")
    INPUT_OR_UI_THREAD_STARVATION,

    @SerialName("host-storage-permission-or-session-desync")
    HOST_STORAGE_PERMISSION_OR_SESSION_DESYNC,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class DiagnosticRootAccessState(
    val label: String,
    val hostPath: String,
    val guestPath: String? = null,
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val detail: String,
)

@Serializable
data class InstalledTitleContentSummary(
    val id: String,
    val displayName: String,
    val installStatus: String,
    val contentType: String,
    val packageSignature: String,
)

@Serializable
data class PlayerSessionLaunchSummary(
    val entryId: String,
    val entryDisplayName: String,
    val guestTitlePath: String,
    val freezeLabSource: String,
    val presentationBackend: String,
    val guestRenderScaleProfile: String,
    val internalDisplayResolution: String,
    val readbackResolveMode: String,
    val apuBackend: String,
    val mesaBranch: String,
    val mesaReason: String,
    val diagnosticProfile: String,
    val dlcPolicy: String = "enabled",
    val inputMuted: Boolean = false,
    val overlayHidden: Boolean = false,
    val args: List<String>,
    val environmentSummary: Map<String, String>,
)

@Serializable
data class PlayerSessionPatchState(
    val databasePresent: Boolean,
    val revision: String,
    val fileCount: Int,
    val bundledTitleCount: Int,
    val loadedTitleCount: Int,
    val titleMatchState: String,
    val appliedPatches: List<String>,
)

@Serializable
data class PlayerSessionPresentationSnapshot(
    val framebufferPath: String,
    val frameStreamStatus: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameIndex: Long,
    val frameFreshnessSeconds: Long?,
    val issueSwapCount: Long,
    val captureSuccessCount: Long,
    val exportFrameCount: Long,
    val decodedFrameCount: Long,
    val presentedFrameCount: Long,
    val guestSwapFps: Float,
    val captureFps: Float,
    val transportPublishFps: Float,
    val transportChangeFps: Float,
    val decodeFps: Float,
    val visiblePresentFps: Float,
    val visibleChangeFps: Float,
    val visibleFps: Float,
    val screenChangeFps: Float,
    val transportFrameHash: String,
    val transportFramePerceptualHash: String,
    val visibleFrameHash: String,
    val visibleFramePerceptualHash: String,
    val screenFrameHash: String,
    val screenFramePerceptualHash: String,
    val screenBlackRatio: Float,
    val screenAverageLuma: Float,
    val presenterSubmittedAtEpochMillis: Long,
    val uiLongFrameCount: Long,
    val uiLongestFrameMillis: Long,
    val lastLifecycleEvent: String,
    val lastSurfaceEvent: String,
)

@Serializable
data class PlayerSessionInputSnapshot(
    val controllerConnected: Boolean,
    val controllerName: String? = null,
    val lastInputSequence: Long,
    val lastInputAgeMs: Long? = null,
    val inputEventsPerSecond: Float = 0f,
)

@Serializable
data class PlayerSessionHostSnapshot(
    val board: String,
    val hardware: String,
    val socModel: String,
    val androidRelease: String,
    val androidApiLevel: Int,
    val availableAppBytes: Long,
    val guestProcessAlive: Boolean,
    val guestProcessPid: Int? = null,
    val mesaBranch: String,
    val mesaReason: String,
    val uriPermissionPresent: Boolean,
    val fexWarningSummary: List<String> = emptyList(),
)

@Serializable
data class MovieAudioDebugSnapshot(
    val playerState: String = "unavailable",
    val activeAudioClients: Int = 0,
    val audioClientRegisterCount: Int = 0,
    val movieThreadBurstCount: Int = 0,
    val lastThreadChurnEvent: String? = null,
    val lastMediaEvent: String? = null,
    val lastVfsEvent: String? = null,
)

@Serializable
data class VideoFreezeEvidence(
    val presentationBackend: String,
    val diagnosticProfile: String,
    val freezeLabSource: String,
    val guestProcessAlive: Boolean,
    val transportFreshnessSeconds: Long? = null,
    val outputFrameIndex: Long = -1L,
    val issueSwapCount: Long = 0L,
    val captureSuccessCount: Long = 0L,
    val exportFrameCount: Long = 0L,
    val decodedFrameCount: Long = 0L,
    val presentedFrameCount: Long = 0L,
    val guestSwapFps: Float = 0f,
    val captureFps: Float = 0f,
    val transportPublishFps: Float = 0f,
    val transportChangeFps: Float = 0f,
    val visiblePresentFps: Float = 0f,
    val visibleChangeFps: Float = 0f,
    val screenChangeFps: Float = 0f,
    val visibleFps: Float = 0f,
    val transportFrameHash: String = "",
    val transportFramePerceptualHash: String = "",
    val visibleFrameHash: String = "",
    val visibleFramePerceptualHash: String = "",
    val screenFrameHash: String = "",
    val screenFramePerceptualHash: String = "",
    val screenBlackRatio: Float = 0f,
    val screenAverageLuma: Float = 0f,
    val presenterSubmittedAtEpochMillis: Long = 0L,
    val uiLongFrameCount: Long = 0L,
    val uiLongestFrameMillis: Long = 0L,
    val inputEventsPerSecond: Float = 0f,
    val lastLifecycleEvent: String = "",
    val lastSurfaceEvent: String = "",
    val storageFailureLabels: List<String> = emptyList(),
    val lastMeaningfulGuestTransition: String? = null,
    val lastContentCallResult: String? = null,
    val lastXamCallResult: String? = null,
    val lastXliveCallResult: String? = null,
    val lastXnetCallResult: String? = null,
    val lastDiscResolveLine: String? = null,
    val lastHostResolveLine: String? = null,
    val lastEmptyDiscResolveLine: String? = null,
    val lastTrueFileMiss: String? = null,
    val lastRootProbe: String? = null,
    val emptyDiscResolveCount: Int = 0,
    val movieDecodeThreadCount: Int = 0,
    val audioClientRegisterCount: Int = 0,
    val movieThreadBurstCount: Int = 0,
    val lastMovieDecodeThreadLine: String? = null,
    val lastMovieAudioState: String? = null,
    val guestTimelineMarkers: List<String> = emptyList(),
)

@Serializable
data class VideoFreezeConfidenceReport(
    val cause: AndroidVideoFreezeCause = AndroidVideoFreezeCause.UNKNOWN,
    val confidencePercent: Int = 0,
    val reason: String = "insufficient-evidence",
    val corroboratingSignals: List<String> = emptyList(),
)

@Serializable
data class PlayerSessionDiagnosticsBundle(
    val version: Int = 5,
    val sessionId: String,
    val capturedAtEpochMillis: Long,
    val startupStage: String,
    val startupDetail: String,
    val titleName: String? = null,
    val titleId: String? = null,
    val moduleHash: String? = null,
    val progressionBucket: ProgressionStallClassification = ProgressionStallClassification.UNKNOWN,
    val progressionReason: String = "insufficient-evidence",
    val lastMeaningfulGuestTransition: String? = null,
    val lastContentCallResult: String? = null,
    val lastXamCallResult: String? = null,
    val lastXliveCallResult: String? = null,
    val lastXnetCallResult: String? = null,
    val guestTimelineMarkers: List<String> = emptyList(),
    val lastThreadSnapshotHeader: String? = null,
    val lastThreadSnapshotLines: List<String> = emptyList(),
    val appLogPath: String,
    val fexLogPath: String,
    val guestLogPath: String,
    val launchSummary: PlayerSessionLaunchSummary,
    val storageRoots: List<DiagnosticRootAccessState>,
    val installedContent: List<InstalledTitleContentSummary>,
    val installedMarketplaceContentCount: Int = 0,
    val patchState: PlayerSessionPatchState,
    val presentation: PlayerSessionPresentationSnapshot,
    val input: PlayerSessionInputSnapshot,
    val host: PlayerSessionHostSnapshot,
    val movieAudioState: MovieAudioDebugSnapshot = MovieAudioDebugSnapshot(),
    val audioClientEvents: List<String> = emptyList(),
    val threadCreationEvents: List<String> = emptyList(),
    val vfsAccessTimeline: List<String> = emptyList(),
    val freezeEvidence: VideoFreezeEvidence = VideoFreezeEvidence(
        presentationBackend = "unknown",
        diagnosticProfile = "unknown",
        freezeLabSource = "unknown",
        guestProcessAlive = false,
    ),
    val freezeReport: VideoFreezeConfidenceReport = VideoFreezeConfidenceReport(),
)

object PlayerSessionDiagnosticsBundleCodec {
    fun decode(raw: String): PlayerSessionDiagnosticsBundle =
        RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(bundle: PlayerSessionDiagnosticsBundle): String =
        RuntimeManifestCodec.json.encodeToString(PlayerSessionDiagnosticsBundle.serializer(), bundle)
}
