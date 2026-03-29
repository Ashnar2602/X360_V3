package emu.x360.mobile.dev.bootstrap

import android.net.Uri
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.TitleContentEntry
import emu.x360.mobile.dev.runtime.TitleContentInstallStatus

data class XeniaPatchDatabaseSummary(
    val present: Boolean = false,
    val revision: String = "unavailable",
    val bundledTitleCount: Int = 0,
    val loadedTitleCount: Int = 0,
    val appliedPatches: List<String> = emptyList(),
    val lastContentMiss: String? = null,
)

internal fun TitleContentEntry.refreshInstallStatus(
    directories: RuntimeDirectories,
): TitleContentEntry {
    val dataExists = installedDataPath?.let(::pathExistsOnDisk) ?: false
    val headerExists = installedHeaderPath?.let(::pathExistsOnDisk) ?: false
    val nextStatus = when {
        installStatus == TitleContentInstallStatus.ERROR -> TitleContentInstallStatus.ERROR
        installedDataPath == null && installedHeaderPath == null -> installStatus
        dataExists || headerExists -> TitleContentInstallStatus.INSTALLED
        else -> TitleContentInstallStatus.MISSING
    }
    return if (nextStatus == installStatus) {
        this
    } else {
        copy(
            installStatus = nextStatus,
            lastInstallSummary = when (nextStatus) {
                TitleContentInstallStatus.MISSING -> "Installed content is no longer present under ${directories.xeniaWritableContentRoot}"
                else -> lastInstallSummary
            },
        )
    }
}

internal fun stableContentEntryId(libraryEntryId: String, uri: Uri): String =
    stableEntryId("$libraryEntryId|${uri}")

private fun pathExistsOnDisk(rawPath: String): Boolean =
    java.io.File(rawPath).exists()
