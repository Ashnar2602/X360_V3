package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import kotlin.math.max

internal object GuestPresentationMetricsParser {
    private val issueSwapPattern = Regex("""X360_FRAMEBUFFER_DEBUG issue_swap count=(\d+)""")
    private val captureSuccessPattern = Regex("""X360_FRAMEBUFFER_DEBUG capture_success count=(\d+)""")

    fun parse(
        logContent: String,
        exportedFrameCount: Long,
        elapsedMillis: Long,
        frameSourceStatus: String,
    ): PresentationPerformanceMetrics {
        val safeElapsedMillis = elapsedMillis.coerceAtLeast(1L)
        val elapsedSeconds = safeElapsedMillis / 1000f
        val issueSwapCount = issueSwapPattern.maxCount(logContent)
        val captureSuccessCount = captureSuccessPattern.maxCount(logContent)
        val safeExportCount = exportedFrameCount.coerceAtLeast(0L)
        return PresentationPerformanceMetrics(
            issueSwapCount = issueSwapCount,
            captureSuccessCount = captureSuccessCount,
            exportFrameCount = safeExportCount,
            swapFps = countToFps(issueSwapCount, elapsedSeconds),
            captureFps = countToFps(captureSuccessCount, elapsedSeconds),
            exportFps = countToFps(safeExportCount, elapsedSeconds),
            frameSourceStatus = frameSourceStatus,
        )
    }

    private fun Regex.maxCount(logContent: String): Long {
        return findAll(logContent)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toLongOrNull() }
            .maxOrNull()
            ?: 0L
    }

    private fun countToFps(count: Long, elapsedSeconds: Float): Float {
        if (count <= 0L || elapsedSeconds <= 0f) {
            return 0f
        }
        return count / elapsedSeconds
    }
}

internal class RollingPresentationMetricsTracker(
    private val fpsWindowMillis: Long = 500L,
) {
    private val exportCounter = RollingAbsoluteCountFpsCounter(fpsWindowMillis)
    private val transportChangeCounter = RollingEventFpsCounter(fpsWindowMillis)
    private val decodeCounter = RollingEventFpsCounter(fpsWindowMillis)
    private val presentCounter = RollingEventFpsCounter(fpsWindowMillis)
    private val visibleChangeCounter = RollingEventFpsCounter(fpsWindowMillis)
    private val visibleCounter = RollingAbsoluteCountFpsCounter(fpsWindowMillis)
    private val screenChangeCounter = RollingEventFpsCounter(fpsWindowMillis)
    private var exportFrameCount: Long = 0L
    private var decodedFrameCount: Long = 0L
    private var presentedFrameCount: Long = 0L
    private var lastTransportFrameHash: String = ""
    private var lastTransportPerceptualHash: String = ""
    private var lastVisibleFrameHash: String = ""
    private var lastVisiblePerceptualHash: String = ""
    private var lastScreenFrameHash: String = ""
    private var lastScreenPerceptualHash: String = ""
    private var lastScreenBlackRatio: Float = 0f
    private var lastScreenAverageLuma: Float = 0f
    private var lastPresenterSubmittedAtEpochMillis: Long = 0L
    private var uiLongFrameCount: Long = 0L
    private var uiLongestFrameMillis: Long = 0L
    private var inputEventsPerSecond: Float = 0f
    private var lastLifecycleEvent: String = ""
    private var lastSurfaceEvent: String = ""

    fun onExportObserved(
        frameIndex: Long,
        frameHash: String = "",
        perceptualHash: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (frameIndex < 0L) {
            return
        }
        exportFrameCount = frameIndex
        if (frameHash.isNotBlank()) {
            lastTransportFrameHash = frameHash
        }
        val changeHash = perceptualHash.ifBlank { frameHash }
        if (changeHash.isNotBlank() && changeHash != lastTransportPerceptualHash) {
            lastTransportPerceptualHash = changeHash
            transportChangeCounter.record(nowMillis)
        }
        exportCounter.record(frameIndex, nowMillis)
    }

    fun onDecoded(nowMillis: Long = System.currentTimeMillis()) {
        decodedFrameCount += 1L
        decodeCounter.record(nowMillis)
    }

    fun onPresented(
        frameIndex: Long,
        visibleFrameHash: String = "",
        visiblePerceptualHash: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        presentedFrameCount += 1L
        presentCounter.record(nowMillis)
        if (visibleFrameHash.isNotBlank()) {
            lastVisibleFrameHash = visibleFrameHash
        }
        val changeHash = visiblePerceptualHash.ifBlank { visibleFrameHash }
        if (changeHash.isNotBlank() && changeHash != lastVisiblePerceptualHash) {
            lastVisiblePerceptualHash = changeHash
            visibleChangeCounter.record(nowMillis)
        }
        lastPresenterSubmittedAtEpochMillis = nowMillis
        visibleCounter.record(frameIndex, nowMillis)
    }

    fun onScreenObserved(
        screenFrameHash: String = "",
        screenPerceptualHash: String = "",
        blackRatio: Float = 0f,
        averageLuma: Float = 0f,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (screenFrameHash.isNotBlank()) {
            lastScreenFrameHash = screenFrameHash
        }
        val changeHash = screenPerceptualHash.ifBlank { screenFrameHash }
        if (changeHash.isNotBlank() && changeHash != lastScreenPerceptualHash) {
            lastScreenPerceptualHash = changeHash
            screenChangeCounter.record(nowMillis)
        }
        lastScreenBlackRatio = blackRatio
        lastScreenAverageLuma = averageLuma
    }

    fun onUiLongFrameObserved(frameMillis: Long) {
        if (frameMillis <= 0L) {
            return
        }
        uiLongFrameCount += 1L
        uiLongestFrameMillis = max(uiLongestFrameMillis, frameMillis)
    }

    fun onInputRateObserved(ratePerSecond: Float) {
        inputEventsPerSecond = ratePerSecond.coerceAtLeast(0f)
    }

    fun onLifecycleEvent(event: String) {
        lastLifecycleEvent = event
    }

    fun onSurfaceEvent(event: String) {
        lastSurfaceEvent = event
    }

    fun snapshot(frameSourceStatus: String): PresentationPerformanceMetrics {
        return PresentationPerformanceMetrics(
            exportFrameCount = exportFrameCount,
            decodedFrameCount = decodedFrameCount,
            presentedFrameCount = presentedFrameCount,
            exportFps = exportCounter.fps,
            transportChangeFps = transportChangeCounter.fps,
            decodeFps = decodeCounter.fps,
            presentFps = presentCounter.fps,
            visibleChangeFps = visibleChangeCounter.fps,
            visibleFps = visibleCounter.fps,
            screenChangeFps = screenChangeCounter.fps,
            frameSourceStatus = frameSourceStatus,
            transportFrameHash = lastTransportFrameHash,
            transportFramePerceptualHash = lastTransportPerceptualHash,
            visibleFrameHash = lastVisibleFrameHash,
            visibleFramePerceptualHash = lastVisiblePerceptualHash,
            screenFrameHash = lastScreenFrameHash,
            screenFramePerceptualHash = lastScreenPerceptualHash,
            screenBlackRatio = lastScreenBlackRatio,
            screenAverageLuma = lastScreenAverageLuma,
            presenterSubmittedAtEpochMillis = lastPresenterSubmittedAtEpochMillis,
            uiLongFrameCount = uiLongFrameCount,
            uiLongestFrameMillis = uiLongestFrameMillis,
            inputEventsPerSecond = inputEventsPerSecond,
            lastLifecycleEvent = lastLifecycleEvent,
            lastSurfaceEvent = lastSurfaceEvent,
        )
    }
}

private class RollingEventFpsCounter(
    private val windowMillis: Long,
) {
    var fps: Float = 0f
        private set

    private var totalCount: Long = 0L
    private var windowStartCount: Long = 0L
    private var windowStartMillis: Long = 0L

    fun record(nowMillis: Long) {
        totalCount += 1L
        if (windowStartMillis <= 0L) {
            windowStartMillis = nowMillis
            windowStartCount = totalCount
            return
        }

        update(totalCount, nowMillis)
    }

    private fun update(currentCount: Long, nowMillis: Long) {
        val deltaMillis = max(1L, nowMillis - windowStartMillis)
        val deltaCount = currentCount - windowStartCount
        if (deltaCount > 0L && deltaMillis >= windowMillis) {
            fps = (deltaCount * 1000f) / deltaMillis.toFloat()
            windowStartMillis = nowMillis
            windowStartCount = currentCount
        }
    }
}

private class RollingAbsoluteCountFpsCounter(
    private val windowMillis: Long,
) {
    var fps: Float = 0f
        private set

    private var lastCount: Long = -1L
    private var windowStartCount: Long = -1L
    private var windowStartMillis: Long = 0L

    fun record(
        absoluteCount: Long,
        nowMillis: Long,
    ) {
        if (absoluteCount < 0L || absoluteCount == lastCount) {
            return
        }
        lastCount = absoluteCount
        if (windowStartCount < 0L || windowStartMillis <= 0L) {
            windowStartCount = absoluteCount
            windowStartMillis = nowMillis
            return
        }

        val deltaMillis = max(1L, nowMillis - windowStartMillis)
        val deltaCount = absoluteCount - windowStartCount
        if (deltaCount > 0L && deltaMillis >= windowMillis) {
            fps = (deltaCount * 1000f) / deltaMillis.toFloat()
            windowStartCount = absoluteCount
            windowStartMillis = nowMillis
        }
    }
}
