package emu.x360.mobile.dev

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.RuntimeInstallState
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeBootstrapInstrumentedTest {
    private lateinit var context: Context
    private lateinit var baseDir: Path

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        baseDir = context.cacheDir.toPath().resolve("runtime-bootstrap-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        baseDir.toFile().deleteRecursively()
    }

    @Test
    fun firstInstallCreatesExpectedLayout() {
        val manager = AppRuntimeManager(context, baseDir)

        val snapshot = manager.install()

        assertThat(snapshot.installState).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(baseDir.resolve("rootfs").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/tmp").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/lib64/ld-linux-x86-64.so.2").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/usr/lib/x86_64-linux-gnu/libvulkan_lvp.so").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/usr/share/vulkan/icd.d/lvp_icd.json").exists()).isTrue()
        assertThat(baseDir.resolve("payload/guest-tests/bin/hello_x86_64").exists()).isTrue()
        assertThat(baseDir.resolve("payload/guest-tests/bin/dyn_hello_x86_64").exists()).isTrue()
        assertThat(baseDir.resolve("payload/guest-tests/bin/vulkan_probe_x86_64").exists()).isTrue()
        assertThat(baseDir.resolve("payload/config/guest-runtime-metadata.json").exists()).isTrue()
        assertThat(baseDir.resolve("payload/config/mesa-runtime-metadata.json").exists()).isTrue()
        assertThat(baseDir.resolve("payload/config/mesa-turnip-source-lock.json").exists()).isTrue()
        val mesaMetadata = JSONObject(baseDir.resolve("payload/config/mesa-runtime-metadata.json").readText())
        val mesaBundles = mesaMetadata.getJSONArray("bundles")
        val mesa26 = (0 until mesaBundles.length())
            .map { mesaBundles.getJSONObject(it) }
            .first { it.getString("branch") == "mesa26" }
        assertThat(mesa26.getString("patchSetId")).isEqualTo("ubwc5-a830-v1")
        assertThat(mesa26.getJSONArray("appliedPatches").getString(0))
            .isEqualTo("0001-tu-kgsl-add-ubwc-5-6-fallback-to-fd-dev-info.patch")
        assertThat(baseDir.resolve("rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/opt/x360-v3/mesa/mesa26/lib/libvulkan_freedreno.so").exists()).isTrue()
        assertThat(baseDir.resolve("rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json").exists()).isTrue()
        assertThat(baseDir.resolve("logs/app").exists()).isTrue()
    }

    @Test
    fun secondInstallDoesNotRewriteInstallMarker() {
        val manager = AppRuntimeManager(context, baseDir)

        manager.install()
        val marker = baseDir.resolve(".runtime-install-state.json")
        val firstModified = marker.getLastModifiedTime()
        Thread.sleep(50)
        manager.install()

        assertThat(marker.getLastModifiedTime()).isEqualTo(firstModified)
    }

    @Test
    fun launchFexHelloCreatesSeparatedLogsAndSentinel() {
        val manager = AppRuntimeManager(context, baseDir)
        manager.install()

        val snapshot = manager.launchFexHello()

        assertThat(snapshot.latestLogs.appLog).contains("backend=fex")
        assertThat(snapshot.latestLogs.fexLog).contains("layer=fex")
        assertThat(snapshot.latestLogs.guestLog).contains("X360_FEX_HELLO_OK")
        assertThat(snapshot.latestLogs.sessionId).isNotNull()
        assertThat(baseDir.resolve("rootfs/tmp/fex_hello_ok.json").exists()).isTrue()
    }

    @Test
    fun launchDynamicHelloCreatesSentinel() {
        val manager = AppRuntimeManager(context, baseDir)
        manager.install()

        val snapshot = manager.launchDynamicHello()

        assertThat(snapshot.latestLogs.appLog).contains("backend=fex")
        assertThat(snapshot.latestLogs.guestLog).contains("X360_DYN_HELLO_OK")
        val sentinel = JSONObject(baseDir.resolve("rootfs/tmp/fex_dyn_hello_ok.json").readText())
        assertThat(sentinel.getString("marker")).isEqualTo("X360_DYN_HELLO_OK")
        assertThat(sentinel.getInt("argc")).isAtLeast(1)
    }

    @Test
    fun launchLavapipeProbeKeepsSoftwareFallbackWorking() {
        val manager = AppRuntimeManager(context, baseDir)
        manager.install()

        val snapshot = manager.launchLavapipeProbe()

        assertThat(snapshot.latestLogs.appLog).contains("backend=fex")
        assertThat(snapshot.latestLogs.guestLog).contains("X360_VK_PROBE_OK")
        val sentinel = JSONObject(baseDir.resolve("rootfs/tmp/fex_vulkan_probe.json").readText())
        assertThat(sentinel.getString("marker")).isEqualTo("X360_VK_PROBE_OK")
        assertThat(sentinel.getString("driver_mode")).isEqualTo("lavapipe")
        assertThat(sentinel.getBoolean("instance_ok")).isTrue()
        assertThat(sentinel.getInt("device_count")).isAtLeast(1)
        assertThat(sentinel.getString("device_name").lowercase()).contains("llvmpipe")
    }

    @Test
    fun launchTurnipProbeUsesHardwarePathAndSupportsRepeatLaunch() {
        val manager = AppRuntimeManager(context, baseDir)
        manager.install()
        manager.setMesaOverride(MesaRuntimeBranch.AUTO)

        val firstSnapshot = manager.launchTurnipProbe()
        val secondSnapshot = manager.launchTurnipProbe()

        assertThat(firstSnapshot.latestLogs.appLog).contains("backend=fex")
        val sentinel = JSONObject(baseDir.resolve("rootfs/tmp/fex_vulkan_probe.json").readText())
        assertThat(sentinel.getString("driver_mode")).isEqualTo("turnip")
        if (isTurnipHardwarePassDevice()) {
            assertThat(secondSnapshot.latestLogs.guestLog).contains("X360_VK_PROBE_OK")
            assertThat(sentinel.getString("marker")).isEqualTo("X360_VK_PROBE_OK")
            assertThat(sentinel.getBoolean("instance_ok")).isTrue()
            assertThat(sentinel.getInt("device_count")).isAtLeast(1)
            assertThat(sentinel.getString("device_name").lowercase()).doesNotContain("llvmpipe")
            assertThat(secondSnapshot.latestLogs.guestLog).contains("physical_device_count=")
            assertThat(secondSnapshot.latestLogs.guestLog).contains("device[0]=")
        } else {
            assertThat(secondSnapshot.fexDiagnostics.selectedMesaBranch).isEqualTo("mesa26")
            assertThat(secondSnapshot.latestLogs.appLog).contains("exitClassification=PROCESS_ERROR")
            assertThat(secondSnapshot.latestLogs.fexLog).contains("vkEnumeratePhysicalDevices(count) failed")
            assertThat(sentinel.getBoolean("instance_ok")).isFalse()
            assertThat(sentinel.getInt("device_count")).isEqualTo(0)
        }
    }

    @Test
    fun launchStubRemainsAvailableAsDebugFallback() {
        val manager = AppRuntimeManager(context, baseDir)
        manager.install()

        val snapshot = manager.launchStub()

        assertThat(snapshot.latestLogs.appLog).contains("backend=stub")
        assertThat(snapshot.latestLogs.guestLog).contains("layer=guest")
    }

    @Test
    fun smokeInstallAndProbeUsingApplicationFilesDir() {
        val manager = AppRuntimeManager(context)

        val installSnapshot = manager.install()
        manager.setMesaOverride(MesaRuntimeBranch.AUTO)
        val probeSnapshot = manager.launchTurnipProbe()

        assertThat(installSnapshot.installState).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(context.filesDir.toPath().resolve("rootfs/lib64/ld-linux-x86-64.so.2").exists()).isTrue()
        assertThat(context.filesDir.toPath().resolve("rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1").exists()).isTrue()
        assertThat(context.filesDir.toPath().resolve("rootfs/usr/share/vulkan/icd.d/lvp_icd.json").exists()).isTrue()
        assertThat(context.filesDir.toPath().resolve("rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json").exists()).isTrue()
        assertThat(context.filesDir.toPath().resolve("rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json").exists()).isTrue()
        val sentinel = JSONObject(context.filesDir.toPath().resolve("rootfs/tmp/fex_vulkan_probe.json").readText())
        if (isTurnipHardwarePassDevice()) {
            assertThat(probeSnapshot.latestLogs.guestLog).contains("X360_VK_PROBE_OK")
            assertThat(sentinel.getBoolean("instance_ok")).isTrue()
        } else {
            assertThat(probeSnapshot.fexDiagnostics.selectedMesaBranch).isEqualTo("mesa26")
            assertThat(sentinel.getString("driver_mode")).isEqualTo("turnip")
        }
    }

    @Test
    fun launchXeniaBringupReachesVulkanInitialized() {
        val manager = AppRuntimeManager(context)

        val installSnapshot = manager.install()
        manager.setMesaOverride(MesaRuntimeBranch.AUTO)
        val launchSnapshot = manager.launchXeniaBringup()

        assertThat(installSnapshot.installState).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(context.filesDir.toPath().resolve("rootfs/opt/x360-v3/xenia/bin/xenia-canary").exists()).isTrue()
        assertThat(context.filesDir.toPath().resolve("payload/config/xenia-build-metadata.json").exists()).isTrue()
        assertThat(launchSnapshot.xeniaDiagnostics.binaryInstalled).isTrue()
        assertThat(launchSnapshot.xeniaDiagnostics.configPresent).isTrue()
        assertWithMessage(
            buildString {
                appendLine("Expected Xenia bring-up to reach Vulkan initialization.")
                appendLine("stage=${launchSnapshot.xeniaDiagnostics.lastStartupStage}")
                appendLine("detail=${launchSnapshot.xeniaDiagnostics.lastStartupDetail}")
                appendLine("lastAction=${launchSnapshot.lastAction}")
                appendLine("guestLog:")
                appendLine(launchSnapshot.latestLogs.guestLog)
                appendLine("fexLog:")
                appendLine(launchSnapshot.latestLogs.fexLog)
                appendLine("appLog:")
                appendLine(launchSnapshot.latestLogs.appLog)
            },
        ).that(launchSnapshot.xeniaDiagnostics.lastStartupStage).isEqualTo("vulkan_initialized")
    }

    private fun isTurnipHardwarePassDevice(): Boolean {
        return Build.BOARD.equals("kalama", ignoreCase = true) ||
            Build.SOC_MODEL.equals("QCS8550", ignoreCase = true) ||
            Build.BOARD.equals("sun", ignoreCase = true) ||
            Build.SOC_MODEL.equals("CQ8725S", ignoreCase = true)
    }
}
