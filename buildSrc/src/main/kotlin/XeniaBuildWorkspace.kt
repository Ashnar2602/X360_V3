import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

enum class XeniaBuildMode {
    FULL,
    INCREMENTAL,
    ;

    companion object {
        fun parse(raw: String): XeniaBuildMode = when (raw.lowercase()) {
            "full" -> FULL
            "incremental" -> INCREMENTAL
            else -> error("Unsupported Xenia build mode: $raw")
        }
    }
}

fun computeXeniaWorkspaceKey(
    lock: XeniaSourceBuildLock,
    patchFiles: List<Path>,
    vulkanSdkOverride: String?,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("sourceUrl=${lock.sourceUrl}\n".toByteArray())
    digest.update("sourceRef=${lock.sourceRef}\n".toByteArray())
    digest.update("sourceRevision=${lock.sourceRevision}\n".toByteArray())
    digest.update("buildProfile=${lock.buildProfile}\n".toByteArray())
    digest.update("patchSetId=${lock.patchSetId}\n".toByteArray())
    digest.update("vulkanSdk=${vulkanSdkOverride ?: "system"}\n".toByteArray())
    patchFiles.sortedBy { it.fileName.toString() }.forEach { patchFile ->
        digest.update("patch=${patchFile.fileName}\n".toByteArray())
        digest.update(Files.readAllBytes(patchFile))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

fun computeXeniaSetupSignature(
    lock: XeniaSourceBuildLock,
    vulkanSdkOverride: String?,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("sourceRevision=${lock.sourceRevision}\n".toByteArray())
    digest.update("buildProfile=${lock.buildProfile}\n".toByteArray())
    digest.update("vulkanSdk=${vulkanSdkOverride ?: "system"}\n".toByteArray())
    digest.update("xeniaContentTool=enabled\n".toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }
}
