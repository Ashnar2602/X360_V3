package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import java.nio.file.Path
import org.junit.Test

class GuestRuntimeProfilesTest {
    @Test
    fun `dynamic guest environment adds guest library search path`() {
        val environment = buildDynamicGuestEnvironment()

        assertThat(environment["LD_LIBRARY_PATH"]).isEqualTo("/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu")
        assertThat(environment["GDK_BACKEND"]).isEqualTo("offscreen")
        assertThat(environment).doesNotContainKey("VK_DRIVER_FILES")
    }

    @Test
    fun `vulkan guest environment pins lavapipe icd`() {
        val environment = buildVulkanGuestEnvironment(
            branch = MesaRuntimeBranch.LAVAPIPE,
            directories = RuntimeDirectories(Path.of("/data/user/0/emu.x360.mobile.dev/files")),
        )

        assertThat(environment["LD_LIBRARY_PATH"]).isEqualTo("/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu")
        assertThat(environment["VK_DRIVER_FILES"]).isEqualTo("/usr/share/vulkan/icd.d/lvp_icd.json")
        assertThat(environment["VK_LOADER_DEBUG"]).isEqualTo("error,warn")
    }

    @Test
    fun `vulkan guest environment points mesa25 to staged turnip tree`() {
        val directories = RuntimeDirectories(Path.of("/data/user/0/emu.x360.mobile.dev/files"))

        val environment = buildVulkanGuestEnvironment(
            branch = MesaRuntimeBranch.MESA25,
            directories = directories,
        )

        assertThat(environment["LD_LIBRARY_PATH"])
            .isEqualTo("/opt/x360-v3/mesa/mesa25/lib:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu")
        assertThat(environment["VK_DRIVER_FILES"])
            .isEqualTo("/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json")
        assertThat(environment["X360_DRIVER_MODE"]).isEqualTo("turnip")
    }

    @Test
    fun `vulkan guest environment points mesa26 to staged turnip tree`() {
        val directories = RuntimeDirectories(Path.of("/data/user/0/emu.x360.mobile.dev/files"))

        val environment = buildVulkanGuestEnvironment(
            branch = MesaRuntimeBranch.MESA26,
            directories = directories,
        )

        assertThat(environment["LD_LIBRARY_PATH"])
            .isEqualTo("/opt/x360-v3/mesa/mesa26/lib:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu")
        assertThat(environment["VK_DRIVER_FILES"])
            .isEqualTo("/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json")
        assertThat(environment["X360_DRIVER_MODE"]).isEqualTo("turnip")
    }
}
