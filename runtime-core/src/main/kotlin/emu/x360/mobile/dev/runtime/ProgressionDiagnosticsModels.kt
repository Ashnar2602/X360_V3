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
    val presentationBackend: String,
    val guestRenderScaleProfile: String,
    val internalDisplayResolution: String,
    val readbackResolveMode: String,
    val apuBackend: String,
    val mesaBranch: String,
    val mesaReason: String,
    val diagnosticProfile: String,
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
    val decodeFps: Float,
    val visiblePresentFps: Float,
    val visibleFps: Float,
    val transportFrameHash: String,
    val visibleFrameHash: String,
)

@Serializable
data class PlayerSessionInputSnapshot(
    val controllerConnected: Boolean,
    val controllerName: String? = null,
    val lastInputSequence: Long,
    val lastInputAgeMs: Long? = null,
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
data class PlayerSessionDiagnosticsBundle(
    val version: Int = 1,
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
    val appLogPath: String,
    val fexLogPath: String,
    val guestLogPath: String,
    val launchSummary: PlayerSessionLaunchSummary,
    val storageRoots: List<DiagnosticRootAccessState>,
    val installedContent: List<InstalledTitleContentSummary>,
    val patchState: PlayerSessionPatchState,
    val presentation: PlayerSessionPresentationSnapshot,
    val input: PlayerSessionInputSnapshot,
    val host: PlayerSessionHostSnapshot,
)

object PlayerSessionDiagnosticsBundleCodec {
    fun decode(raw: String): PlayerSessionDiagnosticsBundle =
        RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(bundle: PlayerSessionDiagnosticsBundle): String =
        RuntimeManifestCodec.json.encodeToString(PlayerSessionDiagnosticsBundle.serializer(), bundle)
}
