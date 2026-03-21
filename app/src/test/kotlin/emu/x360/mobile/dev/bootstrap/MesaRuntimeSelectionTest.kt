package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import org.junit.Test

class MesaRuntimeSelectionTest {
    @Test
    fun `auto selects mesa26 for supported odin2 devices`() {
        val resolved = DeviceMesaRuntimePolicy.resolve(
            overrideMode = MesaRuntimeBranch.AUTO,
            properties = DeviceProperties(
                board = "kalama",
                hardware = "qcom",
                socModel = "QCS8550",
            ),
        )

        assertThat(resolved.branch).isEqualTo(MesaRuntimeBranch.MESA26)
        assertThat(resolved.reason).isEqualTo("auto-supported-device-allowlist")
    }

    @Test
    fun `auto falls back to mesa25 for unknown qualcomm hardware`() {
        val resolved = DeviceMesaRuntimePolicy.resolve(
            overrideMode = MesaRuntimeBranch.AUTO,
            properties = DeviceProperties(
                board = "mystery",
                hardware = "qcom",
                socModel = "",
            ),
        )

        assertThat(resolved.branch).isEqualTo(MesaRuntimeBranch.MESA25)
        assertThat(resolved.reason).isEqualTo("auto-qualcomm-fallback")
    }

    @Test
    fun `auto selects mesa26 for supported odin3 devices`() {
        val resolved = DeviceMesaRuntimePolicy.resolve(
            overrideMode = MesaRuntimeBranch.AUTO,
            properties = DeviceProperties(
                board = "sun",
                hardware = "qcom",
                socModel = "CQ8725S",
            ),
        )

        assertThat(resolved.branch).isEqualTo(MesaRuntimeBranch.MESA26)
        assertThat(resolved.reason).isEqualTo("auto-supported-device-allowlist")
    }

    @Test
    fun `manual override wins over auto policy`() {
        val resolved = DeviceMesaRuntimePolicy.resolve(
            overrideMode = MesaRuntimeBranch.MESA26,
            properties = DeviceProperties(
                board = "kalama",
                hardware = "qcom",
                socModel = "QCS8550",
            ),
        )

        assertThat(resolved.branch).isEqualTo(MesaRuntimeBranch.MESA26)
        assertThat(resolved.reason).isEqualTo("manual-override:mesa26")
    }

    @Test
    fun `non qualcomm devices stay on lavapipe in auto mode`() {
        val resolved = DeviceMesaRuntimePolicy.resolve(
            overrideMode = MesaRuntimeBranch.AUTO,
            properties = DeviceProperties(
                board = "generic",
                hardware = "mtk",
                socModel = "",
            ),
        )

        assertThat(resolved.branch).isEqualTo(MesaRuntimeBranch.LAVAPIPE)
        assertThat(resolved.reason).isEqualTo("auto-non-qualcomm-fallback")
    }
}
