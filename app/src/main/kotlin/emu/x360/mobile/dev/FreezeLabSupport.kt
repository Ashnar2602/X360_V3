package emu.x360.mobile.dev

import kotlin.math.max

internal data class FreezeLabIgnoredRect(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    fun contains(x: Int, y: Int): Boolean =
        x in left until rightExclusive && y in top until bottomExclusive
}

internal data class FreezeLabFrameSummary(
    val rawHash: String,
    val perceptualHash: String,
    val blackRatio: Float,
    val averageLuma: Float,
)

internal object FreezeLabHashAnalyzer {
    fun analyzeRgba(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        ignoredRects: List<FreezeLabIgnoredRect> = emptyList(),
    ): FreezeLabFrameSummary {
        if (width <= 0 || height <= 0 || stride < width * 4 || rgbaBytes.isEmpty()) {
            return FreezeLabFrameSummary(
                rawHash = "",
                perceptualHash = "",
                blackRatio = 1f,
                averageLuma = 0f,
            )
        }

        val stepX = max(1, width / 128)
        val stepY = max(1, height / 72)
        val blocks = IntArray(64)
        val blockCounts = IntArray(64)
        var sampledPixelCount = 0
        var blackPixelCount = 0
        var lumaTotal = 0L
        var hash = 0x811C9DC5.toInt()

        fun isIgnored(x: Int, y: Int): Boolean = ignoredRects.any { it.contains(x, y) }

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (!isIgnored(x, y)) {
                    val pixelOffset = y * stride + x * 4
                    if (pixelOffset + 2 < rgbaBytes.size) {
                        val red = rgbaBytes[pixelOffset].toInt() and 0xFF
                        val green = rgbaBytes[pixelOffset + 1].toInt() and 0xFF
                        val blue = rgbaBytes[pixelOffset + 2].toInt() and 0xFF
                        val luma = ((red * 299) + (green * 587) + (blue * 114)) / 1000
                        lumaTotal += luma.toLong()
                        sampledPixelCount += 1
                        if (luma <= 8) {
                            blackPixelCount += 1
                        }
                        hash = hash xor red
                        hash *= 0x01000193
                        hash = hash xor green
                        hash *= 0x01000193
                        hash = hash xor blue
                        hash *= 0x01000193

                        val blockX = (x * 8) / width
                        val blockY = (y * 8) / height
                        val blockIndex = blockY.coerceIn(0, 7) * 8 + blockX.coerceIn(0, 7)
                        blocks[blockIndex] += luma
                        blockCounts[blockIndex] += 1
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (sampledPixelCount == 0) {
            return FreezeLabFrameSummary(
                rawHash = "",
                perceptualHash = "",
                blackRatio = 1f,
                averageLuma = 0f,
            )
        }

        val averageLuma = lumaTotal.toFloat() / sampledPixelCount.toFloat()
        val normalizedBlocks = IntArray(64) { index ->
            val count = blockCounts[index]
            if (count <= 0) {
                0
            } else {
                blocks[index] / count
            }
        }
        val blockAverage = normalizedBlocks.average().toFloat()
        var perceptualHash = 0L
        normalizedBlocks.forEachIndexed { index, value ->
            if (value >= blockAverage) {
                perceptualHash = perceptualHash or (1L shl index)
            }
        }

        return FreezeLabFrameSummary(
            rawHash = Integer.toHexString(hash),
            perceptualHash = java.lang.Long.toHexString(perceptualHash),
            blackRatio = blackPixelCount.toFloat() / sampledPixelCount.toFloat(),
            averageLuma = averageLuma,
        )
    }
}

internal class RollingRateCounter(
    private val windowMillis: Long = 750L,
) {
    private var totalCount = 0L
    private var windowStartCount = 0L
    private var windowStartMillis = 0L

    var ratePerSecond: Float = 0f
        private set

    fun record(nowMillis: Long = System.currentTimeMillis()) {
        totalCount += 1L
        if (windowStartMillis <= 0L) {
            windowStartMillis = nowMillis
            windowStartCount = totalCount
            return
        }
        val deltaMillis = max(1L, nowMillis - windowStartMillis)
        val deltaCount = totalCount - windowStartCount
        if (deltaCount > 0L && deltaMillis >= windowMillis) {
            ratePerSecond = (deltaCount * 1000f) / deltaMillis.toFloat()
            windowStartMillis = nowMillis
            windowStartCount = totalCount
        }
    }
}

internal class RollingHashChangeTracker(
    private val windowMillis: Long = 750L,
) {
    private val rateCounter = RollingRateCounter(windowMillis)
    private var lastHash: String = ""

    val changesPerSecond: Float
        get() = rateCounter.ratePerSecond

    fun observe(
        hash: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (hash.isBlank() || hash == lastHash) {
            return
        }
        lastHash = hash
        rateCounter.record(nowMillis)
    }
}
