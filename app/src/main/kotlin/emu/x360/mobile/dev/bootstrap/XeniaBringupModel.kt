package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.GuestRenderScaleProfile
import emu.x360.mobile.dev.runtime.PresentationBackend
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

internal data class InternalDisplayResolution(
    val width: Int,
    val height: Int,
)

internal data class XeniaPresentationSettings(
    val presentationBackend: PresentationBackend,
    val guestRenderScaleProfile: GuestRenderScaleProfile,
    val internalDisplayResolution: InternalDisplayResolution,
) {
    companion object {
        val HeadlessBringup = XeniaPresentationSettings(
            presentationBackend = PresentationBackend.HEADLESS_ONLY,
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
        )

        val FramebufferPolling = XeniaPresentationSettings(
            presentationBackend = PresentationBackend.FRAMEBUFFER_POLLING,
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
        )
    }
}

private enum class XeniaApuBackend(
    val cliValue: String,
) {
    NOP("nop"),
    SDL("sdl"),
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

        val frameStreamActiveLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=FRAME_STREAM_ACTIVE") }
        if (frameStreamActiveLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.FRAME_STREAM_ACTIVE,
                detail = frameStreamActiveLine.trim(),
                titleName = lines.firstOrNull { it.contains("Title name: ") }
                    ?.substringAfter("Title name: ")
                    ?.trim()
                    ?.ifBlank { null },
            )
        }

        val firstFrameLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=FIRST_FRAME_CAPTURED") }
        if (firstFrameLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.FIRST_FRAME_CAPTURED,
                detail = firstFrameLine.trim(),
                titleName = lines.firstOrNull { it.contains("Title name: ") }
                    ?.substringAfter("Title name: ")
                    ?.trim()
                    ?.ifBlank { null },
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
    presentationSettings: XeniaPresentationSettings = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> XeniaPresentationSettings.HeadlessBringup
        is XeniaLaunchMode.TitleBoot -> XeniaPresentationSettings.FramebufferPolling
    },
): List<String> {
    require(presentationSettings.guestRenderScaleProfile == GuestRenderScaleProfile.ONE) {
        "Phase 5A only supports GuestRenderScaleProfile.ONE as a true guest render scale"
    }
    val xeniaContentRoot = directories.xeniaWritableContentRoot.hostAbsolutePathString()
    val xeniaCacheRoot = directories.xeniaWritableCacheHostRoot.hostAbsolutePathString()
    val xeniaStorageRoot = directories.xeniaWritableStorageRoot.hostAbsolutePathString()
    val xeniaFramebufferPath = directories.rootfsTmp.resolve("xenia_fb").hostAbsolutePathString()
    val mountCache = launchMode is XeniaLaunchMode.TitleBoot
    val apuBackend = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> XeniaApuBackend.NOP
        is XeniaLaunchMode.TitleBoot -> XeniaApuBackend.SDL
    }
    val readbackResolveMode = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> null
        is XeniaLaunchMode.TitleBoot -> "full"
    }
    val baseArgs = buildList {
        addAll(
            listOf(
                "--config=/opt/x360-v3/xenia/bin/xenia-canary.config.toml",
                "--headless=true",
                "--portable=true",
                "--gpu=vulkan",
                "--apu=${apuBackend.cliValue}",
                "--hid=nop",
                "--discord=false",
                "--log_file=stdout",
                "--mount_cache=$mountCache",
                "--mount_scratch=false",
                "--mount_memory_unit=false",
                "--content_root=$xeniaContentRoot",
                "--cache_root=$xeniaCacheRoot",
                "--storage_root=$xeniaStorageRoot",
            ),
        )
        readbackResolveMode?.let { add("--readback_resolve=$it") }
        if (presentationSettings.presentationBackend != PresentationBackend.HEADLESS_ONLY) {
            add("--internal_display_resolution=17")
            add("--internal_display_resolution_x=${presentationSettings.internalDisplayResolution.width}")
            add("--internal_display_resolution_y=${presentationSettings.internalDisplayResolution.height}")
            add("--draw_resolution_scale_x=1")
            add("--draw_resolution_scale_y=1")
            add("--x360_presentation_backend=${presentationSettings.presentationBackend.name.lowercase()}")
            add("--x360_framebuffer_path=$xeniaFramebufferPath")
            add("--x360_framebuffer_fps=10")
        }
    }
    return when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> baseArgs
        is XeniaLaunchMode.TitleBoot -> baseArgs + launchMode.guestPath
    }
}

internal fun buildXeniaConfigText(
    directories: RuntimeDirectories,
    launchMode: XeniaLaunchMode = XeniaLaunchMode.NoTitleBringup,
): String {
    val apuBackend = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> XeniaApuBackend.NOP
        is XeniaLaunchMode.TitleBoot -> XeniaApuBackend.SDL
    }
    val mountCache = launchMode is XeniaLaunchMode.TitleBoot
    val contentRoot = directories.xeniaWritableContentRoot.toGuestPath(directories.rootfs)
    val cacheRoot = directories.xeniaWritableCacheHostRoot.toGuestPath(directories.rootfs)
    val storageRoot = directories.xeniaWritableStorageRoot.toGuestPath(directories.rootfs)
    return """
        [General]
        discord = false
        portable = true
        
        [GPU]
        gpu = "vulkan"
        
        [APU]
        apu = "${apuBackend.cliValue}"
        
        [HID]
        hid = "nop"
        
        [Kernel]
        headless = true
        
        [Storage]
        mount_cache = $mountCache
        mount_memory_unit = false
        mount_scratch = false
        content_root = "$contentRoot"
        cache_root = "$cacheRoot"
        storage_root = "$storageRoot"
    """.trimIndent() + "\n"
}

private fun java.nio.file.Path.hostAbsolutePathString(): String =
    toString()
        .replace('\\', '/')
        .let { raw ->
            if (raw.startsWith("/")) {
                raw
            } else {
                toAbsolutePath().normalize().toString().replace('\\', '/')
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
        !directories.xeniaWritableContentRoot.toFile().listFiles().isNullOrEmpty() -> "local-smoke"
        else -> "none"
    }
}
