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
    fun `xenia title boot config switches to sdl audio and cache mount`() {
        val config = buildXeniaConfigText(
            directories = directories,
            launchMode = XeniaLaunchMode.TitleBoot(
                entryId = "dante",
                guestPath = "/mnt/library/dante.iso",
            ),
        )

        assertThat(config).contains("apu = \"sdl\"")
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
