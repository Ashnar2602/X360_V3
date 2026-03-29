package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.GuestRenderScaleProfile
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import java.nio.file.Path
import org.junit.Test

class XeniaBringupModelTest {
    private val directories = RuntimeDirectories(Path.of("/data/user/0/emu.x360.mobile.dev/files"))

    @Test
    fun `xenia bring-up args pin headless portable vulkan contract`() {
        val args = buildXeniaBringupArgs(directories)

        assertThat(args).contains("--config=/opt/x360-v3/xenia/bin/xenia-canary.config.toml")
        assertThat(args).contains("--headless=true")
        assertThat(args).contains("--portable=true")
        assertThat(args).contains("--gpu=vulkan")
        assertThat(args).contains("--apu=nop")
        assertThat(args).contains("--mount_cache=false")
        assertThat(args).contains("--content_root=/data/user/0/emu.x360.mobile.dev/files/rootfs/tmp/x360-v3/xenia/content")
        assertThat(args).contains("--cache_root=/data/user/0/emu.x360.mobile.dev/files/rootfs/tmp/x360-v3/xenia/cache-host")
        assertThat(args).contains("--storage_root=/data/user/0/emu.x360.mobile.dev/files/rootfs/tmp/x360-v3/xenia/storage")
        assertThat(args).doesNotContain("--internal_display_resolution=17")
        assertThat(args).doesNotContain("--internal_display_resolution_x=1280")
        assertThat(args).doesNotContain("--internal_display_resolution_y=720")
        assertThat(args).doesNotContain("--draw_resolution_scale_x=1")
        assertThat(args).doesNotContain("--draw_resolution_scale_y=1")
        assertThat(args).doesNotContain("--x360_presentation_backend=headless_only")
        assertThat(args).doesNotContain("--x360_framebuffer_path=/tmp/xenia_fb")
        assertThat(args).doesNotContain("--x360_framebuffer_fps=10")
    }

    @Test
    fun `xenia title boot args append guest iso path`() {
        val args = buildXeniaBringupArgs(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
        )

        assertThat(args.last()).isEqualTo("/mnt/library/dante.iso")
        assertThat(args).contains("--apu=sdl")
        assertThat(args).contains("--hid=android_shared_memory")
        assertThat(args).contains("--readback_resolve=fast")
        assertThat(args).contains("--x360_framebuffer_fps=60")
        assertThat(args).contains("--x360_presentation_backend=framebuffer_shared_memory")
        assertThat(args).doesNotContain("--x360_framebuffer_path=/data/user/0/emu.x360.mobile.dev/files/rootfs/tmp/xenia_fb")
    }

    @Test
    fun `xenia performance title boot args use sdl audio fast readback and sixty fps export`() {
        val args = buildXeniaBringupArgs(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
            presentationSettings = XeniaPresentationSettings.FramebufferPollingPerformance,
        )

        assertThat(args).contains("--apu=sdl")
        assertThat(args).contains("--readback_resolve=fast")
        assertThat(args).contains("--x360_framebuffer_fps=60")
    }

    @Test
    fun `xenia no-title config keeps nop audio and guest storage roots`() {
        val config = buildXeniaConfigText(
            directories = directories,
            launchMode = XeniaLaunchMode.NoTitleBringup,
        )

        assertThat(config).contains("apu = \"nop\"")
        assertThat(config).contains("mount_cache = false")
        assertThat(config).contains("content_root = \"/tmp/x360-v3/xenia/content\"")
        assertThat(config).contains("cache_root = \"/tmp/x360-v3/xenia/cache-host\"")
        assertThat(config).contains("storage_root = \"/tmp/x360-v3/xenia/storage\"")
    }

    @Test
    fun `xenia title boot config uses sdl audio and cache mount`() {
        val config = buildXeniaConfigText(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
        )

        assertThat(config).contains("apu = \"sdl\"")
        assertThat(config).contains("hid = \"android_shared_memory\"")
        assertThat(config).contains("mount_cache = true")
        assertThat(config).contains("content_root = \"/tmp/x360-v3/xenia/content\"")
        assertThat(config).contains("cache_root = \"/tmp/x360-v3/xenia/cache-host\"")
        assertThat(config).contains("storage_root = \"/tmp/x360-v3/xenia/storage\"")
    }

    @Test
    fun `xenia title boot config keeps sdl audio in performance mode`() {
        val config = buildXeniaConfigText(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
            presentationSettings = XeniaPresentationSettings.FramebufferPollingPerformance,
        )

        assertThat(config).contains("apu = \"sdl\"")
        assertThat(config).contains("hid = \"android_shared_memory\"")
        assertThat(config).contains("mount_cache = true")
    }

    @Test
    fun `performance settings fall back to full readback on mesa25`() {
        val resolved = XeniaPresentationSettings.FramebufferPollingPerformance
            .resolveForMesaBranch(MesaRuntimeBranch.MESA25)

        assertThat(resolved.readbackResolveMode).isEqualTo(XeniaReadbackResolveMode.FULL)
        assertThat(resolved.apuBackend).isEqualTo(XeniaApuBackend.SDL)
    }

    @Test
    fun `performance settings keep fast readback on mesa26`() {
        val resolved = XeniaPresentationSettings.FramebufferPollingPerformance
            .resolveForMesaBranch(MesaRuntimeBranch.MESA26)

        assertThat(resolved.readbackResolveMode).isEqualTo(XeniaReadbackResolveMode.FAST)
    }

    @Test
    fun `shared memory settings force full readback on mesa26`() {
        val resolved = XeniaPresentationSettings.FramebufferSharedMemory
            .resolveForMesaBranch(MesaRuntimeBranch.MESA26)

        assertThat(resolved.readbackResolveMode).isEqualTo(XeniaReadbackResolveMode.FULL)
    }

    @Test
    fun `startup parser reports vulkan initialized from device creation log`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Storage root: /opt/x360-v3/xenia/bin
            Vulkan instance API version 1.3.0
            Vulkan device properties and enabled features:
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.VULKAN_INITIALIZED)
        assertThat(analysis.detail).contains("Vulkan device properties")
    }

    @Test
    fun `startup parser reports failure from fatal vulkan error`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Storage root: /opt/x360-v3/xenia/bin
            Failed to create a Vulkan instance
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.FAILED)
        assertThat(analysis.detail).contains("Failed to create a Vulkan instance")
    }

    @Test
    fun `startup parser reports title metadata when disc boot progresses`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Vulkan device properties and enabled features:
            Checking for XISO
            Loading module game:\default.xex
            Title name: Dante's Inferno
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.TITLE_METADATA_AVAILABLE)
        assertThat(analysis.titleName).isEqualTo("Dante's Inferno")
    }

    @Test
    fun `startup parser reports steady headless state from launcher marker`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Checking for XISO
            Loading module game:\default.xex
            Title name: Dante's Inferno
            X360_XENIA_STAGE=TITLE_RUNNING_HEADLESS observation_seconds=30
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.TITLE_RUNNING_HEADLESS)
        assertThat(analysis.titleName).isEqualTo("Dante's Inferno")
    }

    @Test
    fun `startup parser reports first visible frame from launcher marker`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Loading module game:\default.xex
            X360_XENIA_STAGE=FIRST_FRAME_CAPTURED frame_index=1 width=1280 height=720
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.FIRST_FRAME_CAPTURED)
        assertThat(analysis.detail).contains("frame_index=1")
    }

    @Test
    fun `startup parser reports active frame stream from launcher marker`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Loading module game:\default.xex
            X360_XENIA_STAGE=FRAME_STREAM_ACTIVE frame_index=3 width=1280 height=720
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.FRAME_STREAM_ACTIVE)
        assertThat(analysis.detail).contains("frame_index=3")
    }

    @Test
    fun `startup parser captures patch db title count module hash and content miss`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            PatchDB: Loaded patches for 482 titles
            Title ID: 454108CF
            Module hash: 8b5ce7c2f1ab44de
            Patcher: Applying patch for: Dante's Inferno(454108CF) - Disable broken post FX
            X360_XAM_CONTENT_CALL name=XamContentResolve tid=00000001 request=[device=00000002 type=00000002 title=454108CF xuid=0000000000000000 file=TU0001] path=GAME:\Content\0000000000000000\454108CF\00000002\TU0001
            X360_CONTENT_CALL=ResolvePackagePath tid=00000001 request=[device=00000002 title=454108CF type=00000002 xuid=0000000000000000 file=TU0001] caller_xuid=0000000000000000 path=/tmp/x360-v3/xenia/content/0000000000000000/454108CF/00000002/TU0001 disc=4294967295 exists=0
            X360_CONTENT_MISS=OpenContent request=[device=00000001 title=454108CF type=00000002 xuid=0000000000000000 file=TU0001] caller_xuid=0000000000000000 path=/tmp/x360-v3/xenia/content/0000000000000000/454108CF/00000002/TU0001
            X360_THREAD_SNAPSHOT reason=OpenContent trigger_tid=00000001 count=2
            X360_THREAD tid=00000001 handle=F8000004 state=running suspend=0 main=1 guest=1 cpu=1 start=82000000 lr=82123456 r1=7FFFF000 wait_result=00000102 wait_obj=00000000 alertable=0 terminated=0 last_error=00000000 name=Main Thread (F8000004)
            X360_THREAD tid=00000002 handle=F8000008 state=waiting suspend=0 main=0 guest=1 cpu=2 start=82001000 lr=82111111 r1=7FFFE000 wait_result=00000103 wait_obj=81234567 alertable=1 terminated=0 last_error=00000000 name=MoviePlayer2 Decode Thread (F8000008)
            XNetLogonGetTitleID offline stub success title_id=454108CF
            XLIVEBASE message 0005000E offline response size=0x28
            X360_VFS_DISC_MISS path=content\\0000000000000000\\454108CF\\00000002 image=/storage/roms/Dante.iso
            F> F8000058 DiscImageDevice::ResolvePath()
            F> F8000058 DiscImageDevice::ResolvePath()
            AudioSystem::RegisterClient: client 0 registered successfully
            K> F8000388 XThread::Execute thid 22 (handle=F8000388, 'MoviePlayer2 Decode Thread (F8000388)', native=8B7FF6C0)
            Loading module game:\default.xex
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.TITLE_MODULE_LOADING)
        assertThat(analysis.titleId).isEqualTo("454108CF")
        assertThat(analysis.moduleHash).isEqualTo("8B5CE7C2F1AB44DE")
        assertThat(analysis.patchDatabaseLoadedTitleCount).isEqualTo(482)
        assertThat(analysis.appliedPatches).hasSize(1)
        assertThat(analysis.lastContentMiss).contains("X360_VFS_DISC_MISS")
        assertThat(analysis.lastTrueFileMiss).contains("X360_VFS_DISC_MISS")
        assertThat(analysis.lastContentCallResult).contains("X360_VFS_DISC_MISS")
        assertThat(analysis.lastXamCallResult).contains("X360_XAM_CONTENT_CALL")
        assertThat(analysis.lastXliveCallResult).contains("XLIVEBASE")
        assertThat(analysis.lastXnetCallResult).contains("XNetLogonGetTitleID")
        assertThat(analysis.lastEmptyDiscResolveLine).contains("DiscImageDevice::ResolvePath()")
        assertThat(analysis.lastRootProbe).contains("DiscImageDevice::ResolvePath()")
        assertThat(analysis.emptyDiscResolveCount).isEqualTo(2)
        assertThat(analysis.movieDecodeThreadCount).isEqualTo(2)
        assertThat(analysis.audioClientRegisterCount).isEqualTo(1)
        assertThat(analysis.movieThreadBurstCount).isEqualTo(2)
        assertThat(analysis.lastMovieDecodeThreadLine).contains("MoviePlayer2 Decode Thread")
        assertThat(analysis.lastMovieAudioState).contains("MoviePlayer2 Decode Thread")
        assertThat(analysis.guestTimelineMarkers).contains("FIRST_MISSION_LOADING_ENTERED")
        assertThat(analysis.guestTimelineMarkers).contains("MOVIE_THREAD_CREATED")
        assertThat(analysis.guestTimelineMarkers).contains("AUDIO_CLIENT_REGISTERED")
        assertThat(analysis.lastThreadSnapshotHeader).contains("X360_THREAD_SNAPSHOT")
        assertThat(analysis.lastThreadSnapshotLines).hasSize(2)
        assertThat(analysis.lastThreadSnapshotLines.last()).contains("MoviePlayer2 Decode Thread")
        assertThat(analysis.lastMeaningfulTransition).contains("Loading module")
    }

    @Test
    fun `startup parser keeps empty disc resolve separate from content miss`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Title name: Dante's Inferno
            F> F8000058 DiscImageDevice::ResolvePath()
            F> F8000058 DiscImageDevice::ResolvePath()
            K> F8000388 XThread::Execute thid 21 (handle=F8000388, 'MoviePlayer2 Decode Thread (F8000388)', native=8B7FF6C0)
            """.trimIndent(),
        )

        assertThat(analysis.lastContentMiss).isNull()
        assertThat(analysis.lastDiscResolveLine).contains("DiscImageDevice::ResolvePath()")
        assertThat(analysis.lastEmptyDiscResolveLine).contains("DiscImageDevice::ResolvePath()")
        assertThat(analysis.lastRootProbe).contains("DiscImageDevice::ResolvePath()")
        assertThat(analysis.emptyDiscResolveCount).isEqualTo(2)
        assertThat(analysis.movieDecodeThreadCount).isEqualTo(1)
    }

    @Test
    fun `title boot args include audio diagnostic flags when requested`() {
        val args = buildXeniaBringupArgs(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
            presentationSettings = XeniaPresentationSettings.FramebufferPollingPerformance.copy(
                muteAudioOutput = true,
                xmaDecoderMode = XeniaXmaDecoderMode.FAKE,
                useDedicatedXmaThread = false,
            ),
        )

        assertThat(args).contains("--mute=true")
        assertThat(args).contains("--xma_decoder=fake")
        assertThat(args).contains("--use_dedicated_xma_thread=false")
    }

    @Test
    fun `title boot defaults to shared memory at one x render scale`() {
        val args = buildXeniaBringupArgs(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
        )

        assertThat(args).contains("--x360_presentation_backend=framebuffer_shared_memory")
        assertThat(args).contains("--internal_display_resolution_x=1280")
        assertThat(args).contains("--internal_display_resolution_y=720")
        assertThat(args).contains("--draw_resolution_scale_x=1")
        assertThat(args).contains("--draw_resolution_scale_y=1")
        assertThat(args).doesNotContain("--x360_framebuffer_path=/data/user/0/emu.x360.mobile.dev/files/rootfs/tmp/xenia_fb")
    }

    @Test
    fun `polling backend keeps framebuffer path for debug and regression`() {
        val args = buildXeniaBringupArgs(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
            presentationSettings = XeniaPresentationSettings(
                presentationBackend = PresentationBackend.FRAMEBUFFER_POLLING,
                guestRenderScaleProfile = GuestRenderScaleProfile.ONE,
                internalDisplayResolution = InternalDisplayResolution(1280, 720),
            ),
        )

        assertThat(args).contains("--x360_presentation_backend=framebuffer_polling")
        assertThat(args).contains("--x360_framebuffer_path=/data/user/0/emu.x360.mobile.dev/files/rootfs/tmp/xenia_fb")
    }

    @Test
    fun `startup parser treats filesystem exception as fatal failure`() {
        val analysis = XeniaStartupStageParser.analyze(
            """
            Loading module game:\default.xex
            terminate called after throwing an instance of 'std::filesystem::__cxx11::filesystem_error'
            what(): filesystem error: cannot create directories
            """.trimIndent(),
        )

        assertThat(analysis.stage).isEqualTo(XeniaStartupStage.FAILED)
        assertThat(analysis.detail).contains("filesystem_error")
    }
}
