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
        assertThat(args).contains("--content_root=/opt/x360-v3/xenia/content")
        assertThat(args).contains("--cache_root=/opt/x360-v3/xenia/bin/cache")
        assertThat(args).contains("--storage_root=/opt/x360-v3/xenia/bin")
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
}
