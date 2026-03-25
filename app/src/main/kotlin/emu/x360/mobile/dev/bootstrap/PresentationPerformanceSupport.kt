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
    private val decodeCounter = RollingEventFpsCounter(fpsWindowMillis)
    private val presentCounter = RollingEventFpsCounter(fpsWindowMillis)
    private val visibleCounter = RollingAbsoluteCountFpsCounter(fpsWindowMillis)
    private var exportFrameCount: Long = 0L
    private var decodedFrameCount: Long = 0L
    private var presentedFrameCount: Long = 0L
    private var lastTransportFrameHash: String = ""
    private var lastVisibleFrameHash: String = ""

    fun onExportObserved(
        frameIndex: Long,
        frameHash: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (frameIndex < 0L) {
            return
        }
        exportFrameCount = frameIndex
        if (frameHash.isNotBlank()) {
            lastTransportFrameHash = frameHash
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
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        presentedFrameCount += 1L
        presentCounter.record(nowMillis)
        if (visibleFrameHash.isNotBlank()) {
            lastVisibleFrameHash = visibleFrameHash
        }
        visibleCounter.record(frameIndex, nowMillis)
    }

    fun snapshot(frameSourceStatus: String): PresentationPerformanceMetrics {
        return PresentationPerformanceMetrics(
            exportFrameCount = exportFrameCount,
            decodedFrameCount = decodedFrameCount,
            presentedFrameCount = presentedFrameCount,
            exportFps = exportCounter.fps,
            decodeFps = decodeCounter.fps,
            presentFps = presentCounter.fps,
            visibleFps = visibleCounter.fps,
            frameSourceStatus = frameSourceStatus,
            transportFrameHash = lastTransportFrameHash,
            visibleFrameHash = lastVisibleFrameHash,
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
