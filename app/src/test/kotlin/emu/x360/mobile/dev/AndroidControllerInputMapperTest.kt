package emu.x360.mobile.dev

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.SharedInputButtons
import org.junit.Test

class AndroidControllerInputMapperTest {
    @Test
    fun `android back stays reserved for pause menu`() {
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BACK)).isNull()
    }

    @Test
    fun `controller source detection accepts dpad-backed devices too`() {
        assertThat(AndroidControllerInputMapper.isControllerSource(android.view.InputDevice.SOURCE_GAMEPAD)).isTrue()
        assertThat(AndroidControllerInputMapper.isControllerSource(android.view.InputDevice.SOURCE_JOYSTICK)).isTrue()
        assertThat(AndroidControllerInputMapper.isControllerSource(android.view.InputDevice.SOURCE_DPAD)).isTrue()
    }

    @Test
    fun `normalizeStick applies deadzone and scales remaining range`() {
        assertThat(AndroidControllerInputMapper.normalizeStick(0.1f)).isEqualTo(0)
        assertThat(AndroidControllerInputMapper.normalizeStick(1.0f)).isEqualTo(Short.MAX_VALUE.toInt())
        assertThat(AndroidControllerInputMapper.normalizeStick(-1.0f)).isEqualTo(Short.MIN_VALUE.toInt() + 1)
    }

    @Test
    fun `normalizeTrigger applies deadzone and scales remaining range`() {
        assertThat(AndroidControllerInputMapper.normalizeTrigger(0.01f)).isEqualTo(0)
        assertThat(AndroidControllerInputMapper.normalizeTrigger(1.0f)).isEqualTo(255)
        assertThat(AndroidControllerInputMapper.normalizeTrigger(0.5f)).isGreaterThan(0)
    }

    @Test
    fun `button mapper covers required xbox controls`() {
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_A)).isEqualTo(SharedInputButtons.A)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_1)).isEqualTo(SharedInputButtons.A)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_B)).isEqualTo(SharedInputButtons.B)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_2)).isEqualTo(SharedInputButtons.B)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_X)).isEqualTo(SharedInputButtons.X)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_Y)).isEqualTo(SharedInputButtons.Y)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_START)).isEqualTo(SharedInputButtons.START)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_8)).isEqualTo(SharedInputButtons.START)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_MENU)).isEqualTo(SharedInputButtons.START)
        assertThat(AndroidControllerInputMapper.mapKeyCodeToButton(KeyEvent.KEYCODE_BUTTON_SELECT)).isEqualTo(SharedInputButtons.BACK)
    }
}
