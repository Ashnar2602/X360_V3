package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
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
        assertThat(args).contains("--mount_cache=false")
        assertThat(args).contains("--content_root=/opt/x360-v3/xenia/content")
        assertThat(args).contains("--cache_root=/opt/x360-v3/xenia/cache-host")
        assertThat(args).contains("--storage_root=/opt/x360-v3/xenia/bin")
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
