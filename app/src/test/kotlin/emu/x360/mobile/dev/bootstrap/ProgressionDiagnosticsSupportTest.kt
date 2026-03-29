package emu.x360.mobile.dev.bootstrap

import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.AndroidVideoFreezeCause
import emu.x360.mobile.dev.runtime.DiagnosticRootAccessState
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.ProgressionStallClassification
import emu.x360.mobile.dev.runtime.VideoFreezeEvidence
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

    @Test
    fun `classifier reports guest stall for movie audio loop before blaming android video`() {
        val evidence = PlayerSessionStallEvidence(
            sessionAlive = true,
            startupAnalysis = XeniaStartupAnalysis(
                stage = XeniaStartupStage.FRAME_STREAM_ACTIVE,
                detail = "frame stream active",
                lastDiscResolveLine = "F> F8000058 DiscImageDevice::ResolvePath()",
                lastEmptyDiscResolveLine = "F> F8000058 DiscImageDevice::ResolvePath()",
                emptyDiscResolveCount = 18,
                movieDecodeThreadCount = 2,
                audioClientRegisterCount = 2,
                movieThreadBurstCount = 2,
                lastMovieDecodeThreadLine = "K> F8000388 XThread::Execute thid 22 (handle=F8000388, 'MoviePlayer2 Decode Thread (F8000388)', native=8B7FF6C0)",
                lastMovieAudioState = "AudioSystem::RegisterClient: client 0 registered successfully",
            ),
            outputPreview = OutputPreviewState(
                framebufferPath = "/tmp/x360-v3/xenia/frame-transport.bin",
                exists = true,
                status = "active",
                width = 1280,
                height = 720,
                stride = 5120,
                frameIndex = 400,
                freshnessSeconds = 0,
                summary = "active",
            ),
            presentationMetrics = PresentationPerformanceMetrics(
                issueSwapCount = 400,
                captureSuccessCount = 400,
                exportFrameCount = 400,
                decodedFrameCount = 400,
                presentedFrameCount = 400,
                swapFps = 41f,
                captureFps = 41f,
                exportFps = 40f,
                presentFps = 40f,
                visibleFps = 40f,
                frameSourceStatus = "active",
            ),
            inputDiagnostics = PlayerInputDiagnostics.Empty,
            storageRoots = listOf(
                DiagnosticRootAccessState(
                    label = "input-transport",
                    hostPath = "/tmp/x360-v3/xenia/input/session/controller-transport.bin",
                    guestPath = "/tmp/x360-v3/xenia/input/session/controller-transport.bin",
                    exists = false,
                    readable = false,
                    writable = false,
                    detail = "missing",
                ),
            ),
        )

        val (classification, reason) = PlayerSessionStallClassifier.classify(evidence)

        assertThat(classification).isEqualTo(ProgressionStallClassification.GUEST_PROGRESS_STALLED)
        assertThat(reason).contains("movie-audio-loop")
    }

    @Test
    fun `video freeze classifier reports android presenter stale only with multi layer agreement`() {
        val report = VideoFreezeClassifier.classify(
            evidence = VideoFreezeEvidence(
                presentationBackend = "framebuffer-polling",
                diagnosticProfile = "xenia-real-title",
                freezeLabSource = "xenia-real-title",
                guestProcessAlive = true,
                guestSwapFps = 42f,
                captureFps = 41f,
                transportPublishFps = 40f,
                transportChangeFps = 39f,
                visiblePresentFps = 38f,
                visibleChangeFps = 0f,
                screenChangeFps = 0f,
                visibleFps = 38f,
                transportFrameHash = "a1",
                visibleFrameHash = "b2",
                screenFrameHash = "deadbeef",
            ),
            progressionBucket = ProgressionStallClassification.UNKNOWN,
        )

        assertThat(report.cause).isEqualTo(AndroidVideoFreezeCause.ANDROID_PRESENTER_STALE)
        assertThat(report.confidencePercent).isAtLeast(90)
        assertThat(report.reason).contains("screen")
        assertThat(report.corroboratingSignals).contains("guest-moving")
        assertThat(report.corroboratingSignals).contains("transport-moving")
        assertThat(report.corroboratingSignals).contains("presenter-moving")
    }

    @Test
    fun `video freeze classifier stays below ninety percent when evidence is weak`() {
        val report = VideoFreezeClassifier.classify(
            evidence = VideoFreezeEvidence(
                presentationBackend = "framebuffer-polling",
                diagnosticProfile = "xenia-real-title",
                freezeLabSource = "xenia-real-title",
                guestProcessAlive = true,
                guestSwapFps = 5f,
                visiblePresentFps = 5f,
                screenChangeFps = 3f,
                lastMeaningfulGuestTransition = "running",
            ),
            progressionBucket = ProgressionStallClassification.UNKNOWN,
        )

        assertThat(report.cause).isEqualTo(AndroidVideoFreezeCause.UNKNOWN)
        assertThat(report.confidencePercent).isLessThan(90)
    }

    @Test
    fun `video freeze classifier reports guest stall from movie audio loop`() {
        val report = VideoFreezeClassifier.classify(
            evidence = VideoFreezeEvidence(
                presentationBackend = "framebuffer-polling",
                diagnosticProfile = "xenia-real-title",
                freezeLabSource = "xenia-real-title",
                guestProcessAlive = true,
                issueSwapCount = 700,
                captureSuccessCount = 700,
                exportFrameCount = 700,
                decodedFrameCount = 700,
                presentedFrameCount = 700,
                guestSwapFps = 40f,
                captureFps = 40f,
                transportPublishFps = 40f,
                transportChangeFps = 0.1f,
                visiblePresentFps = 40f,
                visibleChangeFps = 0f,
                screenChangeFps = 0f,
                visibleFps = 40f,
                emptyDiscResolveCount = 24,
                lastEmptyDiscResolveLine = "F> F8000058 DiscImageDevice::ResolvePath()",
                movieDecodeThreadCount = 2,
                audioClientRegisterCount = 2,
                movieThreadBurstCount = 2,
                lastMovieDecodeThreadLine = "MoviePlayer2 Decode Thread",
                lastMovieAudioState = "AudioSystem::RegisterClient: client 0 registered successfully",
            ),
            progressionBucket = ProgressionStallClassification.GUEST_PROGRESS_STALLED,
        )

        assertThat(report.cause).isEqualTo(AndroidVideoFreezeCause.GUEST_PROGRESS_STALLED_NOT_ANDROID_VIDEO)
        assertThat(report.confidencePercent).isAtLeast(90)
        assertThat(report.reason).contains("movie-audio-loop")
    }

    @Test
    fun `video freeze classifier keeps empty root probe as noise when not near freeze`() {
        val report = VideoFreezeClassifier.classify(
            evidence = VideoFreezeEvidence(
                presentationBackend = "framebuffer-polling",
                diagnosticProfile = "xenia-real-title",
                freezeLabSource = "xenia-real-title",
                guestProcessAlive = true,
                guestSwapFps = 41f,
                captureFps = 41f,
                transportPublishFps = 40f,
                visiblePresentFps = 40f,
                screenChangeFps = 40f,
                emptyDiscResolveCount = 32,
                lastRootProbe = "F> F8000058 DiscImageDevice::ResolvePath()",
                lastMeaningfulGuestTransition = "AudioSystem::RegisterClient: client 0 registered successfully",
            ),
            progressionBucket = ProgressionStallClassification.UNKNOWN,
        )

        assertThat(report.cause).isEqualTo(AndroidVideoFreezeCause.UNKNOWN)
        assertThat(report.confidencePercent).isLessThan(90)
    }
}
