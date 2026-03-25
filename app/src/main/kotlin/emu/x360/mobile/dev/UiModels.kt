package emu.x360.mobile.dev

import emu.x360.mobile.dev.bootstrap.OutputPreviewState
import emu.x360.mobile.dev.bootstrap.XeniaPatchDatabaseSummary
import emu.x360.mobile.dev.runtime.GameLibraryEntry
import emu.x360.mobile.dev.runtime.GameOptionsEntry
import emu.x360.mobile.dev.runtime.TitleContentEntry

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
    val titleId: String,
    val moduleHash: String,
    val patchDatabaseStatus: String,
    val patchDatabaseLoadedCount: String,
    val appliedPatches: List<String>,
    val lastContentMiss: String,
    val renderScaleOverrideLabel: String,
    val fpsCounterOverrideLabel: String,
    val presentationBackendOverrideLabel: String,
    val canImportContent: Boolean,
    val contentHint: String,
    val contentEntries: List<TitleContentEntryUi>,
    val note: String,
) {
    companion object {
        fun from(
            entry: GameLibraryEntry,
            options: GameOptionsEntry,
            patchDatabase: XeniaPatchDatabaseSummary,
            contentEntries: List<TitleContentEntry>,
        ): GameOptionsUi {
            return GameOptionsUi(
                entryId = entry.id,
                titleName = entry.lastKnownTitleName ?: entry.displayName,
                titleId = entry.lastKnownTitleId ?: "unknown",
                moduleHash = entry.lastKnownModuleHash ?: "unknown",
                patchDatabaseStatus = if (patchDatabase.present) {
                    "bundled @ ${patchDatabase.revision.take(12)}"
                } else {
                    "not staged"
                },
                patchDatabaseLoadedCount = patchDatabase.loadedTitleCount.toString(),
                appliedPatches = patchDatabase.appliedPatches,
                lastContentMiss = patchDatabase.lastContentMiss ?: "none",
                renderScaleOverrideLabel = options.renderScaleOverride?.name?.lowercase()?.replace('_', ' ') ?: "inherit global (1.0x)",
                fpsCounterOverrideLabel = when (options.showFpsCounterOverride) {
                    true -> "on"
                    false -> "off"
                    null -> "inherit global"
                },
                presentationBackendOverrideLabel = options.presentationBackendOverride?.name?.lowercase()?.replace('_', ' ')
                    ?: "inherit global (framebuffer shared memory)",
                canImportContent = !entry.lastKnownTitleId.isNullOrBlank(),
                contentHint = if (entry.lastKnownTitleId.isNullOrBlank()) {
                    "Launch once to identify this title."
                } else {
                    "Import XContent packages (CON/LIVE/PIRS) for this title."
                },
                contentEntries = contentEntries.map(TitleContentEntryUi::from),
                note = options.note ?: "Per-game overrides are reserved for a future milestone.",
            )
        }
    }
}

data class TitleContentEntryUi(
    val id: String,
    val displayName: String,
    val contentTypeLabel: String,
    val packageSignature: String,
    val installStatus: String,
    val lastInstallSummary: String,
) {
    companion object {
        fun from(entry: TitleContentEntry): TitleContentEntryUi {
            return TitleContentEntryUi(
                id = entry.id,
                displayName = entry.displayName,
                contentTypeLabel = entry.contentTypeLabel,
                packageSignature = entry.packageSignature,
                installStatus = entry.installStatus.name.lowercase(),
                lastInstallSummary = entry.lastInstallSummary ?: "none",
            )
        }
    }
}
