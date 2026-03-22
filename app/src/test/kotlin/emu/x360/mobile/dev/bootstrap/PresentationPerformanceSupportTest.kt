package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresentationPerformanceSupportTest {
    @Test
    fun `guest metrics parser derives cadences from debug counters and export frame count`() {
        val metrics = GuestPresentationMetricsParser.parse(
            logContent = """
            i> 01000010 X360_FRAMEBUFFER_DEBUG issue_swap count=120
            i> 00000010 X360_FRAMEBUFFER_DEBUG capture_success count=90
            """.trimIndent(),
            exportedFrameCount = 150L,
            elapsedMillis = 3_000L,
            frameSourceStatus = "active",
        )

        assertThat(metrics.issueSwapCount).isEqualTo(120L)
        assertThat(metrics.captureSuccessCount).isEqualTo(90L)
        assertThat(metrics.exportFrameCount).isEqualTo(150L)
        assertThat(metrics.swapFps).isWithin(0.01f).of(40f)
        assertThat(metrics.captureFps).isWithin(0.01f).of(30f)
        assertThat(metrics.exportFps).isWithin(0.01f).of(50f)
        assertThat(metrics.frameSourceStatus).isEqualTo("active")
    }

    @Test
    fun `rolling presentation tracker reports decode present and visible fps`() {
        val tracker = RollingPresentationMetricsTracker(fpsWindowMillis = 500L)

        tracker.onExportObserved(frameIndex = 1L, nowMillis = 1_000L)
        tracker.onDecoded(nowMillis = 1_000L)
        tracker.onPresented(frameIndex = 1L, nowMillis = 1_000L)
        tracker.onExportObserved(frameIndex = 31L, nowMillis = 1_600L)
        tracker.onDecoded(nowMillis = 1_600L)
        tracker.onPresented(frameIndex = 31L, nowMillis = 1_600L)

        val metrics = tracker.snapshot(frameSourceStatus = "active")

        assertThat(metrics.exportFrameCount).isEqualTo(31L)
        assertThat(metrics.decodedFrameCount).isEqualTo(2L)
        assertThat(metrics.presentedFrameCount).isEqualTo(2L)
        assertThat(metrics.exportFps).isWithin(0.01f).of(50f)
        assertThat(metrics.decodeFps).isWithin(0.01f).of(1.6666666f)
        assertThat(metrics.presentFps).isWithin(0.01f).of(1.6666666f)
        assertThat(metrics.visibleFps).isWithin(0.01f).of(50f)
        assertThat(metrics.frameSourceStatus).isEqualTo("active")
    }
}
