package emu.x360.mobile.dev

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class ControllerMappingStoreTest {
    @Test
    fun `default mapping includes xbox start`() {
        val profile = ControllerMappingProfile.Default
        assertThat(profile.bindingActionForKeyCode(KeyEvent.KEYCODE_BUTTON_START)).isEqualTo(ControllerBindingAction.START)
        assertThat(profile.keyCodeFor(ControllerBindingAction.START)).isEqualTo(KeyEvent.KEYCODE_BUTTON_START)
    }

    @Test
    fun `store persists remapped binding`() {
        val tempDir = Files.createTempDirectory("controller-mapping-test")
        val store = ControllerMappingStore(tempDir)

        store.update { current ->
            current.withBinding(ControllerBindingAction.START, KeyEvent.KEYCODE_BUTTON_1)
        }

        val reloaded = store.load()
        assertThat(reloaded.keyCodeFor(ControllerBindingAction.START)).isEqualTo(KeyEvent.KEYCODE_BUTTON_1)
        assertThat(reloaded.bindingActionForKeyCode(KeyEvent.KEYCODE_BUTTON_1)).isEqualTo(ControllerBindingAction.START)
    }
}
