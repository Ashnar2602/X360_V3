package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.GuestRenderScaleProfile
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

internal data class XeniaStartupAnalysis(
    val stage: XeniaStartupStage,
    val detail: String,
    val titleName: String? = null,
    val titleId: String? = null,
    val moduleHash: String? = null,
    val patchDatabaseLoadedTitleCount: Int? = null,
    val appliedPatches: List<String> = emptyList(),
    val lastContentMiss: String? = null,
    val lastMeaningfulTransition: String? = null,
    val lastContentCallResult: String? = null,
    val lastXamCallResult: String? = null,
    val lastXliveCallResult: String? = null,
    val lastXnetCallResult: String? = null,
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
    val exportTargetFps: Int = 10,
    val playerPollIntervalMs: Long = 120L,
    val keepLastVisibleFrame: Boolean = true,
    val apuBackend: XeniaApuBackend = XeniaApuBackend.SDL,
    val readbackResolveMode: XeniaReadbackResolveMode? = XeniaReadbackResolveMode.FULL,
) {
    companion object {
        val HeadlessBringup = XeniaPresentationSettings(
            presentationBackend = PresentationBackend.HEADLESS_ONLY,
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
            apuBackend = XeniaApuBackend.NOP,
            readbackResolveMode = null,
        )

        val FramebufferPollingDebug = XeniaPresentationSettings(
            presentationBackend = PresentationBackend.FRAMEBUFFER_POLLING,
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
            exportTargetFps = 10,
            playerPollIntervalMs = 120L,
            keepLastVisibleFrame = true,
            apuBackend = XeniaApuBackend.SDL,
            readbackResolveMode = XeniaReadbackResolveMode.FULL,
        )

        val FramebufferSharedMemory = XeniaPresentationSettings(
            presentationBackend = PresentationBackend.FRAMEBUFFER_SHARED_MEMORY,
            guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
            internalDisplayResolution = InternalDisplayResolution(1280, 720),
            exportTargetFps = 60,
            playerPollIntervalMs = 16L,
            keepLastVisibleFrame = true,
            apuBackend = XeniaApuBackend.SDL,
            readbackResolveMode = XeniaReadbackResolveMode.FAST,
        )

        val FramebufferPollingPerformance = FramebufferSharedMemory.copy(
            presentationBackend = PresentationBackend.FRAMEBUFFER_POLLING,
        )

        val FramebufferPolling: XeniaPresentationSettings = FramebufferPollingDebug
    }
}

internal enum class XeniaApuBackend(
    val cliValue: String,
) {
    NOP("nop"),
    SDL("sdl"),
}

internal enum class XeniaReadbackResolveMode(
    val cliValue: String,
) {
    FAST("fast"),
    FULL("full"),
    NONE("none"),
}

internal enum class XeniaHidBackend(
    val cliValue: String,
) {
    NOP("nop"),
    ANDROID_SHARED_MEMORY("android_shared_memory"),
}

internal fun XeniaPresentationSettings.resolveForMesaBranch(
    mesaRuntimeBranch: MesaRuntimeBranch,
): XeniaPresentationSettings {
    if (presentationBackend == PresentationBackend.HEADLESS_ONLY) {
        return this
    }

    val effectiveReadbackResolveMode = when {
        presentationBackend == PresentationBackend.FRAMEBUFFER_SHARED_MEMORY &&
            readbackResolveMode == XeniaReadbackResolveMode.FAST -> XeniaReadbackResolveMode.FULL
        mesaRuntimeBranch == MesaRuntimeBranch.MESA25 &&
            readbackResolveMode == XeniaReadbackResolveMode.FAST -> XeniaReadbackResolveMode.FULL
        else -> readbackResolveMode
    }

    return if (effectiveReadbackResolveMode == readbackResolveMode) {
        this
    } else {
        copy(readbackResolveMode = effectiveReadbackResolveMode)
    }
}

internal object XeniaStartupStageParser {
    fun analyze(logContent: String): XeniaStartupAnalysis {
        val lines = logContent.lineSequence().toList()
        fun lastMatching(pattern: (String) -> Boolean): String? =
            lines.lastOrNull(pattern)?.trim()
        val titleName = lines.firstNotNullOfOrNull { line ->
            if (!line.contains("Title name: ")) {
                null
            } else {
                line.substringAfter("Title name: ").trim().ifBlank { null }
            }
        }
        val titleId = lines.firstNotNullOfOrNull { line ->
            Regex("""Title ID:\s*([0-9A-Fa-f]{8})""").find(line)?.groupValues?.getOrNull(1)?.uppercase()
        }
        val moduleHash = lines.firstNotNullOfOrNull { line ->
            Regex("""Module hash:\s*([0-9A-Fa-f]+)""").find(line)?.groupValues?.getOrNull(1)?.uppercase()
        }
        val patchDatabaseLoadedTitleCount = lines.firstNotNullOfOrNull { line ->
            Regex("""PatchDB:\s+Loaded patches for\s+(\d+)\s+titles""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val appliedPatches = lines
            .filter { it.contains("Patcher: Applying patch") }
            .map { it.trim() }
        val lastContentMiss = lastMatching { line ->
            line.contains("X360_CONTENT_MISS=") ||
                line.contains("""\content\""", ignoreCase = true) ||
                line.contains("ResolvePath(", ignoreCase = true) ||
                line.contains("XamContentOpenFile", ignoreCase = true)
        }
        val lastContentCallResult = lastMatching { line ->
            line.contains("ContentManager::", ignoreCase = true) ||
                line.contains("XamContentOpenFile", ignoreCase = true) ||
                line.contains("X360_VFS_", ignoreCase = true)
        }
        val lastXamCallResult = lastMatching { line ->
            line.contains("Xam", ignoreCase = true)
        }
        val lastXliveCallResult = lastMatching { line ->
            line.contains("XLIVEBASE", ignoreCase = true) ||
                line.contains("XLive", ignoreCase = true)
        }
        val lastXnetCallResult = lastMatching { line ->
            line.contains("XNetLogon", ignoreCase = true) ||
                line.contains("XNet", ignoreCase = true)
        }
        val lastMeaningfulTransition = lastMatching { line ->
            line.contains("CompleteLaunch:", ignoreCase = true) ||
                line.contains("Loading module ", ignoreCase = true) ||
                line.contains("Title name: ", ignoreCase = true) ||
                line.contains("MoviePlayer", ignoreCase = true) ||
                line.contains("CreateThread(", ignoreCase = true) ||
                line.contains("ContentManager::", ignoreCase = true) ||
                line.contains("XamContentOpenFile", ignoreCase = true) ||
                line.contains("X360_VFS_", ignoreCase = true) ||
                line.contains("XNetLogon", ignoreCase = true) ||
                line.contains("XLive", ignoreCase = true) ||
                line.contains("XLIVEBASE", ignoreCase = true)
        }
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
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        val frameStreamActiveLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=FRAME_STREAM_ACTIVE") }
        if (frameStreamActiveLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.FRAME_STREAM_ACTIVE,
                detail = frameStreamActiveLine.trim(),
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        val firstFrameLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=FIRST_FRAME_CAPTURED") }
        if (firstFrameLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.FIRST_FRAME_CAPTURED,
                detail = firstFrameLine.trim(),
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        val runningHeadlessLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=TITLE_RUNNING_HEADLESS") }
        if (runningHeadlessLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_RUNNING_HEADLESS,
                detail = runningHeadlessLine.trim(),
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        if (titleName != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_METADATA_AVAILABLE,
                detail = lines.firstOrNull { it.contains("Title name: ") }?.trim() ?: "Title name discovered",
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        val titleModuleLine = lines.firstOrNull { it.contains("Loading module ") }
        if (titleModuleLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_MODULE_LOADING,
                detail = titleModuleLine.trim(),
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        val discImageLine = lines.firstOrNull { it.contains("Checking for XISO") }
        if (discImageLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.DISC_IMAGE_ACCEPTED,
                detail = discImageLine.trim(),
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        val initializedLine = lines.firstOrNull { it.contains("Vulkan device properties and enabled features:") }
        if (initializedLine != null) {
            return XeniaStartupAnalysis(
                stage = XeniaStartupStage.VULKAN_INITIALIZED,
                detail = initializedLine.trim(),
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
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
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
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
                titleName = titleName,
                titleId = titleId,
                moduleHash = moduleHash,
                patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
                appliedPatches = appliedPatches,
                lastContentMiss = lastContentMiss,
                lastMeaningfulTransition = lastMeaningfulTransition,
                lastContentCallResult = lastContentCallResult,
                lastXamCallResult = lastXamCallResult,
                lastXliveCallResult = lastXliveCallResult,
                lastXnetCallResult = lastXnetCallResult,
            )
        }

        return XeniaStartupAnalysis(
            stage = XeniaStartupStage.PROCESS_STARTED,
            detail = if (logContent.isBlank()) {
                "xenia process started"
            } else {
                lines.lastOrNull().orEmpty().ifBlank { "xenia process started" }
            },
            titleName = titleName,
            titleId = titleId,
            moduleHash = moduleHash,
            patchDatabaseLoadedTitleCount = patchDatabaseLoadedTitleCount,
            appliedPatches = appliedPatches,
            lastContentMiss = lastContentMiss,
            lastMeaningfulTransition = lastMeaningfulTransition,
            lastContentCallResult = lastContentCallResult,
            lastXamCallResult = lastXamCallResult,
            lastXliveCallResult = lastXliveCallResult,
            lastXnetCallResult = lastXnetCallResult,
        )
    }
}

internal fun buildXeniaBringupArgs(
    directories: RuntimeDirectories,
    launchMode: XeniaLaunchMode = XeniaLaunchMode.NoTitleBringup,
    presentationSettings: XeniaPresentationSettings = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> XeniaPresentationSettings.HeadlessBringup
        is XeniaLaunchMode.TitleBoot -> XeniaPresentationSettings.FramebufferSharedMemory
    },
): List<String> {
    require(presentationSettings.guestRenderScaleProfile == GuestRenderScaleProfile.ONE) {
        "Phase 5C only supports GuestRenderScaleProfile.ONE as a true guest render scale"
    }
    val xeniaContentRoot = directories.xeniaWritableContentRoot.hostAbsolutePathString()
    val xeniaCacheRoot = directories.xeniaWritableCacheHostRoot.hostAbsolutePathString()
    val xeniaStorageRoot = directories.xeniaWritableStorageRoot.hostAbsolutePathString()
    val xeniaFramebufferPath = directories.rootfsTmp.resolve("xenia_fb").hostAbsolutePathString()
    val mountCache = launchMode is XeniaLaunchMode.TitleBoot
    val readbackResolveMode = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> null
        is XeniaLaunchMode.TitleBoot -> presentationSettings.readbackResolveMode?.cliValue
    }
    val hidBackend = resolveXeniaHidBackend(launchMode)
    val baseArgs = buildList {
        addAll(
            listOf(
                "--config=/opt/x360-v3/xenia/bin/xenia-canary.config.toml",
                "--headless=true",
                "--portable=true",
                "--gpu=vulkan",
                "--apu=${presentationSettings.apuBackend.cliValue}",
                "--hid=${hidBackend.cliValue}",
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
            add("--x360_framebuffer_fps=${presentationSettings.exportTargetFps}")
            if (presentationSettings.presentationBackend == PresentationBackend.FRAMEBUFFER_POLLING) {
                add("--x360_framebuffer_path=$xeniaFramebufferPath")
            }
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
    presentationSettings: XeniaPresentationSettings = when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> XeniaPresentationSettings.HeadlessBringup
        is XeniaLaunchMode.TitleBoot -> XeniaPresentationSettings.FramebufferSharedMemory
    },
): String {
    val mountCache = launchMode is XeniaLaunchMode.TitleBoot
    val hidBackend = resolveXeniaHidBackend(launchMode)
    val contentRoot = directories.xeniaWritableContentRoot.toXeniaGuestPath(directories.rootfs)
    val cacheRoot = directories.xeniaWritableCacheHostRoot.toXeniaGuestPath(directories.rootfs)
    val storageRoot = directories.xeniaWritableStorageRoot.toXeniaGuestPath(directories.rootfs)
    return """
        [General]
        discord = false
        portable = true
        
        [GPU]
        gpu = "vulkan"
        
        [APU]
        apu = "${presentationSettings.apuBackend.cliValue}"
        
        [HID]
        hid = "${hidBackend.cliValue}"
        
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

private fun java.nio.file.Path.toXeniaGuestPath(rootfs: java.nio.file.Path): String {
    val normalizedRoot = rootfs.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
    val normalizedPath = toAbsolutePath().normalize().toString().replace('\\', '/')
    return when {
        normalizedPath == normalizedRoot -> "/"
        normalizedPath.startsWith("$normalizedRoot/") -> "/" + normalizedPath.removePrefix("$normalizedRoot/")
        else -> "/" + normalizedPath.trimStart('/')
    }
}

private fun resolveXeniaHidBackend(
    launchMode: XeniaLaunchMode,
): XeniaHidBackend {
    return when (launchMode) {
        XeniaLaunchMode.NoTitleBringup -> XeniaHidBackend.NOP
        is XeniaLaunchMode.TitleBoot -> XeniaHidBackend.ANDROID_SHARED_MEMORY
    }
}
