package emu.x360.mobile.dev

import emu.x360.mobile.dev.bootstrap.OutputPreviewState
import emu.x360.mobile.dev.runtime.GameLibraryEntry
import emu.x360.mobile.dev.runtime.GameOptionsEntry

data class OutputPreviewUiState(
    val framebufferPath: String = "",
    val status: String = "idle",
    val summary: String = "",
    val frameIndex: Long = -1L,
    val dimensions: String = "none",
    val freshnessSeconds: String = "n/a",
) {
    companion object {
        fun from(state: OutputPreviewState): OutputPreviewUiState {
            return OutputPreviewUiState(
                framebufferPath = state.framebufferPath,
                status = state.status,
                summary = state.summary,
                frameIndex = state.frameIndex,
                dimensions = if (state.width != null && state.height != null) {
                    "${state.width}x${state.height}"
                } else {
                    "none"
                },
                freshnessSeconds = state.freshnessSeconds?.toString() ?: "n/a",
            )
        }
    }
}

data class GameLibraryEntryUi(
    val id: String,
    val displayName: String,
    val status: String,
    val sourceKind: String,
    val guestPath: String,
    val titleName: String,
    val lastLaunchSummary: String,
    val sizeSummary: String,
) {
    companion object {
        fun from(entry: GameLibraryEntry): GameLibraryEntryUi {
            return GameLibraryEntryUi(
                id = entry.id,
                displayName = entry.displayName,
                status = entry.lastKnownStatus.name.lowercase(),
                sourceKind = entry.sourceKind.name.lowercase(),
                guestPath = entry.lastResolvedGuestPath ?: "none",
                titleName = entry.lastKnownTitleName ?: "unknown",
                lastLaunchSummary = entry.lastLaunchSummary ?: "none",
                sizeSummary = if (entry.sizeBytes > 0L) "${entry.sizeBytes} bytes" else "size unavailable",
            )
        }
    }
}

data class GameOptionsUi(
    val entryId: String,
    val titleName: String,
    val renderScaleOverrideLabel: String,
    val fpsCounterOverrideLabel: String,
    val presentationBackendOverrideLabel: String,
    val note: String,
) {
    companion object {
        fun from(entry: GameLibraryEntry, options: GameOptionsEntry): GameOptionsUi {
            return GameOptionsUi(
                entryId = entry.id,
                titleName = entry.lastKnownTitleName ?: entry.displayName,
                renderScaleOverrideLabel = options.renderScaleOverride?.name?.lowercase()?.replace('_', ' ') ?: "inherit global (1.0x)",
                fpsCounterOverrideLabel = when (options.showFpsCounterOverride) {
                    true -> "on"
                    false -> "off"
                    null -> "inherit global"
                },
                presentationBackendOverrideLabel = options.presentationBackendOverride?.name?.lowercase()?.replace('_', ' ')
                    ?: "inherit global (framebuffer shared memory)",
                note = options.note ?: "Per-game overrides are reserved for a future milestone.",
            )
        }
    }
}
