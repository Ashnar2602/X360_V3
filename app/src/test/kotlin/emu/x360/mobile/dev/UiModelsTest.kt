package emu.x360.mobile.dev

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.bootstrap.XeniaPatchDatabaseSummary
import emu.x360.mobile.dev.runtime.GameLibraryEntry
import emu.x360.mobile.dev.runtime.GameLibraryEntryStatus
import emu.x360.mobile.dev.runtime.GameLibrarySourceKind
import emu.x360.mobile.dev.runtime.GameOptionsEntry
import emu.x360.mobile.dev.runtime.TitleContentEntry
import emu.x360.mobile.dev.runtime.TitleContentInstallStatus
import org.junit.Test

class UiModelsTest {
    @Test
    fun `game options ui defaults dlc policy to enabled and counts installed marketplace content`() {
        val ui = GameOptionsUi.from(
            entry = GameLibraryEntry(
                id = "dante",
                sourceKind = GameLibrarySourceKind.ISO,
                uriString = "content://dante.iso",
                displayName = "Dante's Inferno",
                sizeBytes = 0L,
                lastModified = 0L,
                importedAt = 0L,
                lastKnownStatus = GameLibraryEntryStatus.READY,
                lastKnownTitleId = "454108CF",
            ),
            options = GameOptionsEntry(entryId = "dante"),
            patchDatabase = XeniaPatchDatabaseSummary(present = true, revision = "abc123"),
            contentEntries = listOf(
                TitleContentEntry(
                    id = "dlc",
                    libraryEntryId = "dante",
                    uriString = "content://dlc",
                    displayName = "Dark Forest DLC",
                    titleId = "454108CF",
                    contentType = "00000002",
                    contentTypeLabel = "Marketplace Content",
                    packageSignature = "LIVE",
                    xuid = "0000000000000000",
                    installStatus = TitleContentInstallStatus.INSTALLED,
                ),
                TitleContentEntry(
                    id = "tu",
                    libraryEntryId = "dante",
                    uriString = "content://tu",
                    displayName = "Title Update",
                    titleId = "454108CF",
                    contentType = "000B0000",
                    contentTypeLabel = "Title Update",
                    packageSignature = "LIVE",
                    xuid = "0000000000000000",
                    installStatus = TitleContentInstallStatus.INSTALLED,
                ),
            ),
        )

        assertThat(ui.dlcEnabled).isTrue()
        assertThat(ui.dlcPolicyLabel).isEqualTo("enabled")
        assertThat(ui.installedMarketplaceContentCount).isEqualTo(1)
        assertThat(ui.canImportContent).isTrue()
    }

    @Test
    fun `game options ui hides dlc when override disables marketplace content`() {
        val ui = GameOptionsUi.from(
            entry = GameLibraryEntry(
                id = "dante",
                sourceKind = GameLibrarySourceKind.ISO,
                uriString = "content://dante.iso",
                displayName = "Dante's Inferno",
                sizeBytes = 0L,
                lastModified = 0L,
                importedAt = 0L,
                lastKnownStatus = GameLibraryEntryStatus.READY,
            ),
            options = GameOptionsEntry(entryId = "dante", dlcEnabledOverride = false),
            patchDatabase = XeniaPatchDatabaseSummary(),
            contentEntries = emptyList(),
        )

        assertThat(ui.dlcEnabled).isFalse()
        assertThat(ui.dlcPolicyLabel).isEqualTo("disabled")
        assertThat(ui.dlcPolicyDescription).contains("Hide all installed DLC")
        assertThat(ui.contentHint).isEqualTo("Launch once to identify this title.")
    }
}
