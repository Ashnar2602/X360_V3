package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.DiagnosticRootAccessState
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.ProgressionStallClassification
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import org.junit.Test

class ProgressionDiagnosticsSupportTest {
    @Test
    fun `classifier reports content blocked when latest content miss is present`() {
        val evidence = PlayerSessionStallEvidence(
            sessionAlive = true,
            startupAnalysis = XeniaStartupAnalysis(
                stage = XeniaStartupStage.TITLE_RUNNING_HEADLESS,
                detail = "running",
                lastContentMiss = "X360_CONTENT_MISS=OpenContent path=/tmp/x360-v3/xenia/content/...",
            ),
            outputPreview = OutputPreviewState(
                framebufferPath = "/tmp/x360-v3/xenia/frame-transport.bin",
                exists = true,
                status = "active",
                width = 1280,
                height = 720,
                stride = 5120,
                frameIndex = 100,
                freshnessSeconds = 0,
                summary = "active",
            ),
            presentationMetrics = PresentationPerformanceMetrics(
                issueSwapCount = 100,
                captureSuccessCount = 100,
                exportFrameCount = 100,
                presentedFrameCount = 100,
                swapFps = 40f,
                captureFps = 40f,
                exportFps = 40f,
                presentFps = 40f,
                visibleFps = 40f,
                frameSourceStatus = "active",
            ),
            inputDiagnostics = PlayerInputDiagnostics.Empty,
            storageRoots = listOf(
                DiagnosticRootAccessState(
                    label = "content-root",
                    hostPath = "/tmp/x360-v3/xenia/content",
                    guestPath = "/tmp/x360-v3/xenia/content",
                    exists = true,
                    readable = true,
                    writable = true,
                    detail = "exists,readable,writable",
                ),
            ),
        )

        val (classification, reason) = PlayerSessionStallClassifier.classify(evidence)

        assertThat(classification).isEqualTo(ProgressionStallClassification.CONTENT_OR_PROFILE_BLOCKED)
        assertThat(reason).contains("X360_CONTENT_MISS")
    }

    @Test
    fun `classifier reports presentation stale when guest swaps continue but presents stop`() {
        val evidence = PlayerSessionStallEvidence(
            sessionAlive = true,
            startupAnalysis = XeniaStartupAnalysis(
                stage = XeniaStartupStage.FRAME_STREAM_ACTIVE,
                detail = "frame stream active",
            ),
            outputPreview = OutputPreviewState(
                framebufferPath = "/tmp/x360-v3/xenia/frame-transport.bin",
                exists = true,
                status = "active",
                width = 1280,
                height = 720,
                stride = 5120,
                frameIndex = 1000,
                freshnessSeconds = 0,
                summary = "active",
            ),
            presentationMetrics = PresentationPerformanceMetrics(
                issueSwapCount = 1000,
                captureSuccessCount = 1000,
                exportFrameCount = 1000,
                decodedFrameCount = 1,
                presentedFrameCount = 1,
                swapFps = 42f,
                captureFps = 42f,
                exportFps = 41f,
                decodeFps = 0.2f,
                presentFps = 0f,
                visibleFps = 0f,
                frameSourceStatus = "active",
            ),
            inputDiagnostics = PlayerInputDiagnostics.Empty,
            storageRoots = listOf(
                DiagnosticRootAccessState(
                    label = "content-root",
                    hostPath = "/tmp/x360-v3/xenia/content",
                    guestPath = "/tmp/x360-v3/xenia/content",
                    exists = true,
                    readable = true,
                    writable = true,
                    detail = "exists,readable,writable",
                ),
            ),
        )

        val (classification, reason) = PlayerSessionStallClassifier.classify(evidence)

        assertThat(classification).isEqualTo(ProgressionStallClassification.PRESENTATION_STALE_BUT_GUEST_RUNNING)
        assertThat(reason).isEqualTo("guest-swaps-active-visible-presents-idle")
    }
}
