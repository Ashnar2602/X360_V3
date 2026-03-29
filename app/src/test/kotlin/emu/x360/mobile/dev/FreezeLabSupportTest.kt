package emu.x360.mobile.dev

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FreezeLabSupportTest {
    @Test
    fun `ignored overlay rect keeps hashes stable when overlay pixels change`() {
        val width = 64
        val height = 64
        val stride = width * 4
        val base = ByteArray(stride * height) { 0x20.toByte() }
        val overlayChanged = base.copyOf()

        for (y in 0 until 12) {
            for (x in 0 until 16) {
                val index = y * stride + x * 4
                overlayChanged[index] = 0x7F.toByte()
                overlayChanged[index + 1] = 0x10.toByte()
                overlayChanged[index + 2] = 0x55.toByte()
                overlayChanged[index + 3] = 0x7F.toByte()
            }
        }

        val ignored = listOf(
            FreezeLabIgnoredRect(
                left = 0,
                top = 0,
                rightExclusive = 16,
                bottomExclusive = 12,
            ),
        )

        val baseSummary = FreezeLabHashAnalyzer.analyzeRgba(base, width, height, stride, ignored)
        val changedSummary = FreezeLabHashAnalyzer.analyzeRgba(overlayChanged, width, height, stride, ignored)

        assertThat(changedSummary.rawHash).isEqualTo(baseSummary.rawHash)
        assertThat(changedSummary.perceptualHash).isEqualTo(baseSummary.perceptualHash)
    }

    @Test
    fun `perceptual hash remains stable for uniformly brighter equivalent frame`() {
        val width = 64
        val height = 64
        val stride = width * 4
        val darker = ByteArray(stride * height)
        val brighter = ByteArray(stride * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * stride + x * 4
                val base = if (x < width / 2) 0x20 else 0xD0
                val lifted = if (x < width / 2) 0x30 else 0xE0
                darker[index] = base.toByte()
                darker[index + 1] = base.toByte()
                darker[index + 2] = base.toByte()
                darker[index + 3] = 0x7F.toByte()
                brighter[index] = lifted.toByte()
                brighter[index + 1] = lifted.toByte()
                brighter[index + 2] = lifted.toByte()
                brighter[index + 3] = 0x7F.toByte()
            }
        }

        val darkerSummary = FreezeLabHashAnalyzer.analyzeRgba(darker, width, height, stride)
        val brighterSummary = FreezeLabHashAnalyzer.analyzeRgba(brighter, width, height, stride)

        assertThat(darkerSummary.perceptualHash).isEqualTo(brighterSummary.perceptualHash)
        assertThat(darkerSummary.rawHash).isNotEqualTo(brighterSummary.rawHash)
    }
}
