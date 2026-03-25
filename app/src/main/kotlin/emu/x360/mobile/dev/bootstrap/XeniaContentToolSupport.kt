package emu.x360.mobile.dev.bootstrap
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.TitleContentEntry
import emu.x360.mobile.dev.runtime.TitleContentInstallStatus
import java.nio.file.Path
import org.json.JSONObject

internal data class XeniaContentToolResult(
    val displayName: String = "content package",
    val titleId: String? = null,
    val contentType: String = "unknown",
    val contentTypeLabel: String = "Unknown content package",
    val packageSignature: String = "UNKNOWN",
    val xuid: String = "0000000000000000",
    val installedDataPath: String? = null,
    val installedHeaderPath: String? = null,
    val status: String = "error",
    val summary: String = "",
    val profileXuid: Long = 0L,
)

internal object XeniaContentToolResultCodec {
    fun decode(raw: String): XeniaContentToolResult {
        val json = JSONObject(raw)
        return XeniaContentToolResult(
            displayName = json.optString("displayName", "content package"),
            titleId = json.optString("titleId").ifBlank { null },
            contentType = json.optString("contentType", "unknown"),
            contentTypeLabel = json.optString("contentTypeLabel", "Unknown content package"),
            packageSignature = json.optString("packageSignature", "UNKNOWN"),
            xuid = json.optString("xuid", "0000000000000000"),
            installedDataPath = json.optString("installedDataPath").ifBlank { null },
            installedHeaderPath = json.optString("installedHeaderPath").ifBlank { null },
            status = json.optString("status", "error"),
            summary = json.optString("summary", ""),
            profileXuid = json.optLong("profileXuid", 0L),
        )
    }
}

internal enum class XeniaContentToolCommand(
    val cliValue: String,
) {
    INSPECT("inspect"),
    INSTALL("install"),
}

internal fun contentPackageSuffix(displayName: String): String {
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "con", "live", "pirs" -> ".$extension"
        else -> ".pkg"
    }
}

internal fun XeniaContentToolResult.toTitleContentEntry(
    id: String,
    libraryEntryId: String,
    uriString: String,
    directories: RuntimeDirectories,
    fallbackDisplayName: String,
): TitleContentEntry {
    val absoluteDataPath = installedDataPath
        ?.takeIf { it.isNotBlank() }
        ?.let { directories.xeniaWritableContentRoot.resolve(it).normalize().toString() }
    val absoluteHeaderPath = installedHeaderPath
        ?.takeIf { it.isNotBlank() }
        ?.let { directories.xeniaWritableContentRoot.resolve(it).normalize().toString() }
    val installStatus = when (status.lowercase()) {
        "installed" -> TitleContentInstallStatus.INSTALLED
        "inspected", "pending" -> TitleContentInstallStatus.PENDING
        else -> TitleContentInstallStatus.ERROR
    }
    return TitleContentEntry(
        id = id,
        libraryEntryId = libraryEntryId,
        uriString = uriString,
        displayName = displayName.ifBlank { fallbackDisplayName },
        titleId = titleId?.uppercase() ?: "UNKNOWN",
        contentType = contentType,
        contentTypeLabel = contentTypeLabel,
        packageSignature = packageSignature,
        xuid = xuid,
        installStatus = installStatus,
        installedDataPath = absoluteDataPath,
        installedHeaderPath = absoluteHeaderPath,
        lastInstallSummary = summary.ifBlank { null },
    )
}

internal data class XeniaContentToolLaunchOutcome(
    val launchResult: emu.x360.mobile.dev.runtime.GuestLaunchResult,
    val toolResult: XeniaContentToolResult?,
)

internal fun Path.deleteIfExistsRecursively() {
    val file = toFile()
    if (!file.exists()) {
        return
    }
    if (file.isDirectory) {
        file.listFiles()?.forEach { child -> child.toPath().deleteIfExistsRecursively() }
    }
    file.delete()
}
