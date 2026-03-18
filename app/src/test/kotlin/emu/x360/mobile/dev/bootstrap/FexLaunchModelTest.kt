package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.GuestLaunchRequest
import emu.x360.mobile.dev.runtime.GuestLogDestinations
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import java.nio.file.Path
import org.junit.Test

class FexLaunchModelTest {
    private val directories = RuntimeDirectories(Path.of("/data/user/0/emu.x360.mobile.dev/files"))

    @Test
    fun `build fex config pins rootfs and tso defaults`() {
        val config = buildFexConfig(directories)
        val escapedRootfs = directories.rootfs.toString().replace("\\", "\\\\")

        assertThat(config).contains("\"RootFS\": \"$escapedRootfs\"")
        assertThat(config).contains("\"TSOEnabled\": \"1\"")
        assertThat(config).contains("\"SilentLog\": \"0\"")
    }

    @Test
    fun `build fex launch spec injects host environment contract`() {
        val loaderPath = Path.of("/native/libFEXLoader.so")
        val request = GuestLaunchRequest(
            sessionId = "session-1",
            executable = directories.helloFixture.toString(),
            args = listOf("--sentinel=/tmp/fex_hello_ok.json"),
            environment = mapOf("GDK_BACKEND" to "offscreen", "NO_AT_BRIDGE" to "1"),
            workingDirectory = directories.rootfs.toString(),
            logDestinations = GuestLogDestinations(
                appLog = directories.appLogs.resolve("app.log"),
                fexLog = directories.fexLogs.resolve("fex.log"),
                guestLog = directories.guestLogs.resolve("guest.log"),
            ),
        )

        val spec = buildFexLaunchSpec(loaderPath, directories, request)

        assertThat(spec.command).containsExactly(
            loaderPath.toString(),
            directories.helloFixture.toString(),
            "--sentinel=/tmp/fex_hello_ok.json",
        ).inOrder()
        assertThat(spec.environment["HOME"]).isEqualTo(directories.baseDir.toString())
        assertThat(spec.environment["FEX_ROOTFS"]).isEqualTo(directories.rootfs.toString())
        assertThat(spec.environment["FEX_DISABLESANDBOX"]).isEqualTo("1")
        assertThat(spec.environment["GDK_BACKEND"]).isEqualTo("offscreen")
    }
}
