package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GameLibraryDatabaseCodecTest {
    @Test
    fun `codec decodes persisted iso library entries`() {
        val raw = """
            {
              "version": 1,
              "entries": [
                {
                  "id": "a1b2c3d4",
                  "sourceKind": "iso",
                  "uriString": "file:///storage/emulated/0/Download/DantesInferno.iso",
                  "displayName": "DantesInferno.iso",
                  "sizeBytes": 8123456789,
                  "lastModified": 1710900000000,
                  "importedAt": 1710901000000,
                  "lastKnownStatus": "ready",
                  "lastResolvedGuestPath": "/mnt/library/a1b2c3d4.iso",
                  "lastLaunchSummary": "Xenia reached title_module_loading",
                  "lastKnownTitleName": "Dante's Inferno"
                }
              ]
            }
        """.trimIndent()

        val database = GameLibraryDatabaseCodec.decode(raw)

        assertThat(database.version).isEqualTo(1)
        assertThat(database.entries).hasSize(1)
        assertThat(database.entries.single().sourceKind).isEqualTo(GameLibrarySourceKind.ISO)
        assertThat(database.entries.single().lastKnownStatus).isEqualTo(GameLibraryEntryStatus.READY)
        assertThat(database.entries.single().lastKnownTitleName).isEqualTo("Dante's Inferno")
    }
}
