package emu.x360.mobile.dev

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.nativebridge.NativeBridge
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KgslPropertiesInstrumentedTest {
    @Test
    fun nativeBridgeExposesCoreKgslProperties() {
        val report = NativeBridge.inspectKgslProperties()
        Log.i("KgslPropertiesTest", report)
        println(report)

        val json = JSONObject(report)
        assertThat(json.getBoolean("open_ok")).isTrue()
        assertThat(json.getJSONObject("device_info").getBoolean("ok")).isTrue()
        assertThat(json.getJSONObject("gpu_model").getBoolean("ok")).isTrue()
        assertThat(json.getJSONObject("gpu_model").getString("value")).isNotEmpty()
        assertThat(json.getJSONObject("ubwc_mode").getBoolean("ok")).isTrue()

        if (Build.BOARD.equals("sun", ignoreCase = true) ||
            Build.SOC_MODEL.equals("CQ8725S", ignoreCase = true)
        ) {
            assertThat(json.getJSONObject("ubwc_mode").getInt("value")).isEqualTo(5)
        }
    }
}
