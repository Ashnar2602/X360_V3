package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.XeniaStartupStage

internal data class XeniaStartupAnalysis(
    val stage: XeniaStartupStage,
    val detail: String,
)

internal object XeniaStartupStageParser {
    fun analyze(logContent: String): XeniaStartupAnalysis {
        val lines = logContent.lineSequence().toList()
        val failureLine = lines.firstOrNull { line ->
            line.contains("Failed to create a Vulkan instance") ||
                line.contains("No Vulkan physical devices available") ||
                line.contains("Couldn't choose a compatible Vulkan physical device") ||
                line.contains("Failed to setup emulator")
        }
        if (failureLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.FAILED,
                detail = failureLine.trim(),
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
): List<String> {
    return listOf(
        "--config=/opt/x360-v3/xenia/bin/xenia-canary.config.toml",
        "--headless=true",
        "--portable=true",
        "--gpu=vulkan",
        "--apu=nop",
        "--hid=nop",
        "--discord=false",
        "--log_file=stdout",
        "--mount_scratch=false",
        "--mount_memory_unit=false",
        "--content_root=/opt/x360-v3/xenia/content",
        "--cache_root=/opt/x360-v3/xenia/bin/cache",
        "--storage_root=/opt/x360-v3/xenia/bin",
    )
}

internal fun detectXeniaContentMode(
    directories: RuntimeDirectories,
): String {
    return if (directories.rootfsXeniaContent.toFile().listFiles().isNullOrEmpty()) {
        "none"
    } else {
        "local-smoke"
    }
}
