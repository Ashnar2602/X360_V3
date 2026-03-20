package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

internal data class XeniaStartupAnalysis(
    val stage: XeniaStartupStage,
    val detail: String,
    val titleName: String? = null,
)

internal sealed interface XeniaLaunchMode {
    data object NoTitleBringup : XeniaLaunchMode

    data class TitleBoot(
        val entryId: String,
        val guestPath: String,
    ) : XeniaLaunchMode
}

internal object XeniaStartupStageParser {
    fun analyze(logContent: String): XeniaStartupAnalysis {
        val lines = logContent.lineSequence().toList()
        val failureLine = lines.firstOrNull { line ->
            line.contains("Failed to create a Vulkan instance") ||
                line.contains("No Vulkan physical devices available") ||
                line.contains("Couldn't choose a compatible Vulkan physical device") ||
                line.contains("Failed to setup emulator") ||
                line.contains("Unable to launch disc image") ||
                line.contains("terminate called after throwing an instance of") ||
                line.contains("std::filesystem::filesystem_error") ||
                line.contains("filesystem error:") ||
                line.contains("Aborted (core dumped)") ||
                line.contains("uncaught exception", ignoreCase = true)
        }
        if (failureLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.FAILED,
                detail = failureLine.trim(),
            )
        }

        val runningHeadlessLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=TITLE_RUNNING_HEADLESS") }
        if (runningHeadlessLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_RUNNING_HEADLESS,
                detail = runningHeadlessLine.trim(),
                titleName = lines.firstOrNull { it.contains("Title name: ") }
                    ?.substringAfter("Title name: ")
                    ?.trim()
                    ?.ifBlank { null },
            )
        }

        val titleNameLine = lines.firstOrNull { it.contains("Title name: ") }
        if (titleNameLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_METADATA_AVAILABLE,
                detail = titleNameLine.trim(),
                titleName = titleNameLine.substringAfter("Title name: ").trim().ifBlank { null },
            )
        }

        val titleModuleLine = lines.firstOrNull { it.contains("Loading module ") }
        if (titleModuleLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_MODULE_LOADING,
                detail = titleModuleLine.trim(),
            )
        }

        val discImageLine = lines.firstOrNull { it.contains("Checking for XISO") }
        if (discImageLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.DISC_IMAGE_ACCEPTED,
                detail = discImageLine.trim(),
            )
        }

        val initializedLine = lines.firstOrNull { it.contains("Vulkan device properties and enabled features:") }
        if (initializedLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.VULKAN_INITIALIZED,
                detail = initializedLine.trim(),
            )
        }

        val backendLine = lines.firstOrNull {
            it.contains("Vulkan instance API version") ||
                it.contains("Available Vulkan physical devices")
        }
        if (backendLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.VULKAN_BACKEND_SELECTED,
                detail = backendLine.trim(),
            )
        }

        val configLine = lines.firstOrNull {
            it.contains("Storage root:") ||
                it.contains("Loaded config:")
        }
        if (configLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.CONFIG_READY,
                detail = configLine.trim(),
            )
        }

        return XeniaStartupAnalysis(
            stage = XeniaStartupStage.PROCESS_STARTED,
            detail = if (logContent.isBlank()) {
                "xenia process started"
            } else {
                lines.lastOrNull().orEmpty().ifBlank { "xenia process started" }
            },
        )
    }
}

internal fun buildXeniaBringupArgs(
    directories: RuntimeDirectories,
    launchMode: XeniaLaunchMode = XeniaLaunchMode.NoTitleBringup,
): List<String> {
    val baseArgs = listOf(
        "--config=/opt/x360-v3/xenia/bin/xenia-canary.config.toml",
        "--headless=true",
        "--portable=true",
        "--gpu=vulkan",
        "--apu=nop",
        "--hid=nop",
        "--discord=false",
        "--log_file=stdout",
        "--mount_cache=false",
        "--mount_scratch=false",
        "--mount_memory_unit=false",
        "--content_root=/opt/x360-v3/xenia/content",
        "--cache_root=/opt/x360-v3/xenia/cache-host",
        "--storage_root=/opt/x360-v3/xenia/bin",
    )
    return when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> baseArgs
        is XeniaLaunchMode.TitleBoot -> baseArgs + launchMode.guestPath
    }
}

internal fun detectXeniaContentMode(
    directories: RuntimeDirectories,
): String {
    val libraryEntries = if (directories.rootfsMntLibrary.exists()) {
        runCatching { directories.rootfsMntLibrary.listDirectoryEntries("*.iso") }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    return when {
        libraryEntries.isNotEmpty() -> "library"
        !directories.rootfsXeniaContent.toFile().listFiles().isNullOrEmpty() -> "local-smoke"
        else -> "none"
    }
}
