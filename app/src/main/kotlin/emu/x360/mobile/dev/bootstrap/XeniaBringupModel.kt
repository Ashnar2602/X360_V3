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
    val lastDiscResolveLine: String? = null,
    val lastHostResolveLine: String? = null,
    val lastEmptyDiscResolveLine: String? = null,
    val lastTrueFileMiss: String? = null,
    val lastRootProbe: String? = null,
    val emptyDiscResolveCount: Int = 0,
    val movieDecodeThreadCount: Int = 0,
    val audioClientRegisterCount: Int = 0,
    val movieThreadBurstCount: Int = 0,
    val lastMovieDecodeThreadLine: String? = null,
    val lastMovieAudioState: String? = null,
    val audioClientEvents: List<String> = emptyList(),
    val threadCreationEvents: List<String> = emptyList(),
    val vfsAccessTimeline: List<String> = emptyList(),
    val guestTimelineMarkers: List<String> = emptyList(),
    val lastThreadSnapshotHeader: String? = null,
    val lastThreadSnapshotLines: List<String> = emptyList(),
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
    val muteAudioOutput: Boolean = false,
    val xmaDecoderMode: XeniaXmaDecoderMode? = null,
    val useDedicatedXmaThread: Boolean? = null,
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

internal enum class XeniaXmaDecoderMode(
    val cliValue: String,
) {
    FAKE("fake"),
    OLD("old"),
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
    private val titleIdPattern = Regex("""Title ID:\s*([0-9A-Fa-f]{8})""")
    private val moduleHashPattern = Regex("""Module hash:\s*([0-9A-Fa-f]+)""")
    private val patchDbTitleCountPattern = Regex("""PatchDB:\s+Loaded patches for\s+(\d+)\s+titles""")
    private val emptyDiscResolvePattern = Regex("""DiscImageDevice::ResolvePath\(\s*\)""")
    private val discResolvePattern = Regex("""DiscImageDevice::ResolvePath\(""")
    private val hostResolvePattern = Regex("""HostPathDevice::ResolvePath\(""")
    private val movieDecodeThreadPattern = Regex("""MoviePlayer2 Decode Thread""")
    private val audioClientRegisterPattern =
        Regex("""AudioSystem::RegisterClient:\s+client\s+\d+\s+registered successfully|X360_AUDIO_CLIENT_EVENT action=register""")
    private val audioClientEventPattern = Regex(
        """AudioSystem::RegisterClient|AudioSystem::UnregisterClient|RwAudioCore Dac|AudioMediaPlayer::|Driver initialization failed!|Error sending packet for decoding|Error during decoding|X360_AUDIO_CLIENT_EVENT|X360_AUDIO_SYSTEM_SETUP|X360_MOVIE_AUDIO_STATE""",
    )
    private val threadCreationPattern =
        Regex("""XThread::Execute.*(MoviePlayer2 Decode Thread|RwAudioCore Dac|Audio Media Player)|X360_MOVIE_THREAD_EVENT""")
    private val vfsAccessPattern = Regex(
        """X360_VFS_|X360_IO_TRACE|X360_IO_FILE_MISS|ResolvePath\(|NtOpenFile|NtCreateFile|NtQueryDirectoryFile|NtQueryFullAttributesFile|XamContentOpenFile""",
    )

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
            titleIdPattern.find(line)?.groupValues?.getOrNull(1)?.uppercase()
        }
        val moduleHash = lines.firstNotNullOfOrNull { line ->
            moduleHashPattern.find(line)?.groupValues?.getOrNull(1)?.uppercase()
        }
        val patchDatabaseLoadedTitleCount = lines.firstNotNullOfOrNull { line ->
            patchDbTitleCountPattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val appliedPatches = lines
            .filter { it.contains("Patcher: Applying patch") }
            .map { it.trim() }
        val discResolveLines = lines
            .filter { line ->
                discResolvePattern.containsMatchIn(line) ||
                    line.contains("X360_VFS_RESOLVE", ignoreCase = true) ||
                    line.contains("X360_VFS_DISC_RESOLVE", ignoreCase = true) ||
                    line.contains("X360_VFS_DISC_EMPTY_RESOLVE", ignoreCase = true)
            }
            .map { it.trim() }
        val hostResolveLines = lines
            .filter { line ->
                hostResolvePattern.containsMatchIn(line) ||
                    line.contains("X360_VFS_HOST_RESOLVE", ignoreCase = true)
            }
            .map { it.trim() }
        val emptyDiscResolveLines = lines
            .filter { line ->
                emptyDiscResolvePattern.containsMatchIn(line) ||
                    line.contains("X360_VFS_ROOT_PROBE", ignoreCase = true) ||
                    line.contains("X360_VFS_DISC_EMPTY_RESOLVE", ignoreCase = true)
            }
            .map { it.trim() }
        val movieDecodeThreadLines = lines
            .filter { movieDecodeThreadPattern.containsMatchIn(it) }
            .map { it.trim() }
        val audioClientEventLines = lines
            .filter { audioClientEventPattern.containsMatchIn(it) }
            .map { it.trim() }
        val threadCreationEvents = lines
            .filter { threadCreationPattern.containsMatchIn(it) }
            .map { it.trim() }
        val vfsAccessTimeline = lines
            .filter { vfsAccessPattern.containsMatchIn(it) }
            .map { it.trim() }
            .takeLast(24)
        val lastThreadSnapshotIndex = lines.indexOfLast { it.contains("X360_THREAD_SNAPSHOT") }
        val lastThreadSnapshotHeader = lines.getOrNull(lastThreadSnapshotIndex)?.trim()
        val lastThreadSnapshotLines = if (lastThreadSnapshotIndex >= 0) {
            lines
                .drop(lastThreadSnapshotIndex + 1)
                .takeWhile { it.contains("X360_THREAD ") }
                .map { it.trim() }
        } else {
            emptyList()
        }
        val lastContentMiss = lastMatching { line ->
            line.contains("X360_CONTENT_MISS=") ||
                (line.contains("X360_VFS_", ignoreCase = true) && line.contains("MISS", ignoreCase = true))
        }
        val lastTrueFileMiss = lastMatching { line ->
            (line.contains("X360_CONTENT_MISS=", ignoreCase = true) ||
                line.contains("X360_VFS_", ignoreCase = true) && line.contains("MISS", ignoreCase = true) ||
                line.contains("X360_IO_FILE_MISS", ignoreCase = true)) &&
                !line.contains("EMPTY_RESOLVE", ignoreCase = true)
        }
        val lastContentCallResult = lastMatching { line ->
            line.contains("X360_CONTENT_CALL=", ignoreCase = true) ||
                line.contains("X360_CONTENT_MISS=", ignoreCase = true) ||
                line.contains("X360_XAM_CONTENT_CALL", ignoreCase = true) ||
                line.contains("ContentManager::", ignoreCase = true) ||
                line.contains("XamContentOpenFile", ignoreCase = true) ||
                (line.contains("X360_VFS_", ignoreCase = true) && line.contains("MISS", ignoreCase = true))
        }
        val lastXamCallResult = lastMatching { line ->
            line.contains("X360_XAM_CONTENT_CALL", ignoreCase = true) ||
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
                line.contains("AudioSystem::RegisterClient", ignoreCase = true) ||
                line.contains("RwAudioCore Dac", ignoreCase = true) ||
                line.contains("Audio Media Player", ignoreCase = true) ||
                line.contains("AudioMediaPlayer::", ignoreCase = true) ||
                line.contains("X360_AUDIO_CLIENT_EVENT", ignoreCase = true) ||
                line.contains("X360_MOVIE_AUDIO_STATE", ignoreCase = true) ||
                line.contains("X360_IO_TRACE", ignoreCase = true) ||
                line.contains("X360_IO_FILE_MISS", ignoreCase = true) ||
                line.contains("CreateThread(", ignoreCase = true) ||
                line.contains("X360_CONTENT_CALL=", ignoreCase = true) ||
                line.contains("X360_XAM_CONTENT_CALL", ignoreCase = true) ||
                line.contains("X360_THREAD_SNAPSHOT", ignoreCase = true) ||
                line.contains("ContentManager::", ignoreCase = true) ||
                line.contains("XamContentOpenFile", ignoreCase = true) ||
                line.contains("X360_VFS_", ignoreCase = true) ||
                discResolvePattern.containsMatchIn(line) ||
                hostResolvePattern.containsMatchIn(line) ||
                line.contains("XNetLogon", ignoreCase = true) ||
                line.contains("XLive", ignoreCase = true) ||
                line.contains("XLIVEBASE", ignoreCase = true)
        }
        val firstFrameLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=FIRST_FRAME_CAPTURED") }
        val frameStreamActiveLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=FRAME_STREAM_ACTIVE") }
        val audioClientRegisterCount = lines.count { audioClientRegisterPattern.containsMatchIn(it) }
        val lastMovieAudioState = lastMatching { line ->
            audioClientEventPattern.containsMatchIn(line) ||
                movieDecodeThreadPattern.containsMatchIn(line) ||
                threadCreationPattern.containsMatchIn(line)
        }
        val guestTimelineMarkers = buildList {
            if (lines.any { it.contains("Loading module ", ignoreCase = true) }) {
                add("MODULE_LOADED")
            }
            if (titleName != null || firstFrameLine != null || frameStreamActiveLine != null) {
                add("TITLE_MENU_ACTIVE")
            }
            if (emptyDiscResolveLines.isNotEmpty() ||
                movieDecodeThreadLines.isNotEmpty() ||
                lines.any {
                    it.contains("XamContentCreateEnumeratorResult", ignoreCase = true) &&
                        it.contains("type=00000002", ignoreCase = true)
                }
            ) {
                add("FIRST_MISSION_LOADING_ENTERED")
            }
            if (movieDecodeThreadLines.isNotEmpty()) {
                add("MOVIE_THREAD_CREATED")
            }
            if (audioClientRegisterCount > 0) {
                add("AUDIO_CLIENT_REGISTERED")
            }
            if (movieDecodeThreadLines.size >= 2 && audioClientRegisterCount >= 2) {
                add("MOVIE_AUDIO_LOOP_DETECTED")
            }
        }
        fun buildAnalysis(
            stage: XeniaStartupStage,
            detail: String,
        ) = XeniaStartupAnalysis(
            stage = stage,
            detail = detail,
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
            lastDiscResolveLine = discResolveLines.lastOrNull(),
            lastHostResolveLine = hostResolveLines.lastOrNull(),
            lastEmptyDiscResolveLine = emptyDiscResolveLines.lastOrNull(),
            lastTrueFileMiss = lastTrueFileMiss,
            lastRootProbe = emptyDiscResolveLines.lastOrNull(),
            emptyDiscResolveCount = emptyDiscResolveLines.size,
            movieDecodeThreadCount = movieDecodeThreadLines.size,
            audioClientRegisterCount = audioClientRegisterCount,
            movieThreadBurstCount = movieDecodeThreadLines.size,
            lastMovieDecodeThreadLine = movieDecodeThreadLines.lastOrNull(),
            lastMovieAudioState = lastMovieAudioState,
            audioClientEvents = audioClientEventLines.takeLast(24),
            threadCreationEvents = threadCreationEvents.takeLast(24),
            vfsAccessTimeline = vfsAccessTimeline,
            guestTimelineMarkers = guestTimelineMarkers,
            lastThreadSnapshotHeader = lastThreadSnapshotHeader,
            lastThreadSnapshotLines = lastThreadSnapshotLines,
        )
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
            return buildAnalysis(
                stage = XeniaStartupStage.FAILED,
                detail = failureLine.trim(),
            )
        }

        if (frameStreamActiveLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.FRAME_STREAM_ACTIVE,
                detail = frameStreamActiveLine.trim(),
            )
        }

        if (firstFrameLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.FIRST_FRAME_CAPTURED,
                detail = firstFrameLine.trim(),
            )
        }

        val runningHeadlessLine = lines.firstOrNull { it.contains("X360_XENIA_STAGE=TITLE_RUNNING_HEADLESS") }
        if (runningHeadlessLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.TITLE_RUNNING_HEADLESS,
                detail = runningHeadlessLine.trim(),
            )
        }

        if (titleName != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.TITLE_METADATA_AVAILABLE,
                detail = lines.firstOrNull { it.contains("Title name: ") }?.trim() ?: "Title name discovered",
            )
        }

        val titleModuleLine = lines.firstOrNull { it.contains("Loading module ") }
        if (titleModuleLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.TITLE_MODULE_LOADING,
                detail = titleModuleLine.trim(),
            )
        }

        val discImageLine = lines.firstOrNull { it.contains("Checking for XISO") }
        if (discImageLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.DISC_IMAGE_ACCEPTED,
                detail = discImageLine.trim(),
            )
        }

        val initializedLine = lines.firstOrNull { it.contains("Vulkan device properties and enabled features:") }
        if (initializedLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.VULKAN_INITIALIZED,
                detail = initializedLine.trim(),
            )
        }

        val backendLine = lines.firstOrNull {
            it.contains("Vulkan instance API version") ||
                it.contains("Available Vulkan physical devices")
        }
        if (backendLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.VULKAN_BACKEND_SELECTED,
                detail = backendLine.trim(),
            )
        }

        val configLine = lines.firstOrNull {
            it.contains("Storage root:") ||
                it.contains("Loaded config:")
        }
        if (configLine != null) {
            return buildAnalysis(
                stage = XeniaStartupStage.CONFIG_READY,
                detail = configLine.trim(),
            )
        }

        return buildAnalysis(
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
        if (presentationSettings.muteAudioOutput) {
            add("--mute=true")
        }
        presentationSettings.xmaDecoderMode?.let { add("--xma_decoder=${it.cliValue}") }
        presentationSettings.useDedicatedXmaThread?.let { add("--use_dedicated_xma_thread=$it") }
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
