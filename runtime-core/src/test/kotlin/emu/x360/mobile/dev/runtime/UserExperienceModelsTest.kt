package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserExperienceModelsTest {
    @Test
    fun `app settings codec preserves product defaults`() {
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(AppSettings()))

        assertThat(decoded.showFpsCounter).isFalse()
        assertThat(decoded.defaultRenderScaleProfile).isEqualTo(GuestRenderScaleProfile.ONE)
        assertThat(decoded.defaultPresentationBackend).isEqualTo(PresentationBackend.FRAMEBUFFER_POLLING)
    }

    @Test
    fun `game options codec preserves nullable future overrides`() {
        val raw = GameOptionsDatabaseCodec.encode(
            GameOptionsDatabase(
                entries = listOf(
                    GameOptionsEntry(
                        entryId = "dante",
                        renderScaleOverride = GuestRenderScaleProfile.TWO,
                        showFpsCounterOverride = true,
                        dlcEnabledOverride = false,
                        presentationBackendOverride = PresentationBackend.FRAMEBUFFER_POLLING,
                        note = "Future override placeholder",
                    ),
                ),
            ),
        )

        val decoded = GameOptionsDatabaseCodec.decode(raw)

        assertThat(decoded.entries).hasSize(1)
        val entry = decoded.entries.single()
        assertThat(entry.entryId).isEqualTo("dante")
        assertThat(entry.renderScaleOverride).isEqualTo(GuestRenderScaleProfile.TWO)
        assertThat(entry.showFpsCounterOverride).isTrue()
        assertThat(entry.dlcEnabledOverride).isFalse()
        assertThat(entry.presentationBackendOverride).isEqualTo(PresentationBackend.FRAMEBUFFER_POLLING)
        assertThat(entry.note).isEqualTo("Future override placeholder")
    }
}
