package emu.x360.mobile.dev

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import emu.x360.mobile.dev.bootstrap.RollingPresentationMetricsTracker
import emu.x360.mobile.dev.bootstrap.SharedFramePresentationReader
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.SharedFrameTransportFrame
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import emu.x360.mobile.dev.runtime.XeniaFramebufferHeader
import emu.x360.mobile.dev.ui.theme.X360RebuildTheme
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {
    private val viewModel by viewModels<PlayerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        if (entryId.isNullOrBlank()) {
            finish()
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode()
        setContent {
            X360RebuildTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(entryId) {
                    viewModel.start(entryId)
                }
                PlayerScreen(
                    state = state,
                    openPresentationReader = viewModel::openPresentationReader,
                    onMetrics = viewModel::reportVisibleMetrics,
                    onExit = {
                        viewModel.stop()
                        finish()
                    },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setImmersiveMode()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.stop("activity-destroy")
        }
        super.onDestroy()
    }

    private fun setImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_ENTRY_ID = "entry_id"

        fun intent(context: Context, entryId: String): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
        }
    }
}

@Composable
private fun PlayerScreen(
    state: PlayerSessionUiState,
    openPresentationReader: () -> SharedFramePresentationReader?,
    onMetrics: (PresentationPerformanceMetrics) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val sessionKey = state.sessionId ?: state.framebufferPath
    val context = LocalContext.current
    val sharedMemoryBackend = state.presentationBackend == PresentationBackend.FRAMEBUFFER_SHARED_MEMORY.name.lowercase()
    val imageView = rememberPlayerImageView(context)
    var overlayState by remember(sessionKey) {
        mutableStateOf(PlayerOverlayState.placeholder(state.detail))
    }

    LaunchedEffect(
        sessionKey,
        sharedMemoryBackend,
        state.playerPollIntervalMs,
        state.keepLastVisibleFrame,
        imageView,
    ) {
        val tracker = RollingPresentationMetricsTracker()
        var lastOverlayReportAtMillis = 0L
        var lastMetricsReportAtMillis = 0L
        if (sharedMemoryBackend) {
            val reader = openPresentationReader()
            if (reader == null) {
                overlayState = PlayerOverlayState.placeholder("Shared-memory presentation session is unavailable")
                return@LaunchedEffect
            }
            try {
                var lastFrameIndex = Long.MIN_VALUE
                var lastBuffer: PlayerBitmapBuffer? = null
                while (currentCoroutineContext().isActive) {
                    val frame = withContext(Dispatchers.IO) {
                        reader.awaitNextFrame(
                            lastFrameIndex = lastFrameIndex,
                            timeoutMillis = state.playerPollIntervalMs.coerceAtLeast(16L),
                        )
                    }
                    if (frame != null) {
                        tracker.onExportObserved(frame.header.frameIndex)
                        if (!(state.keepLastVisibleFrame && lastBuffer != null && !playerFrameHasVisibleContent(frame.rgbaBytes, frame.header.payloadSize))) {
                            tracker.onDecoded()
                            val updatedBuffer = updatePlayerBitmapBufferFromSharedFrame(frame, lastBuffer)
                            lastBuffer = updatedBuffer
                            if (imageView.tag !== updatedBuffer.bitmap) {
                                imageView.setImageBitmap(updatedBuffer.bitmap)
                                imageView.tag = updatedBuffer.bitmap
                            }
                            imageView.postInvalidateOnAnimation()
                            tracker.onPresented(frame.header.frameIndex)
                            lastFrameIndex = frame.header.frameIndex
                        }
                    }

                    val nowMillis = System.currentTimeMillis()
                    val metrics = tracker.snapshot(
                        frameSourceStatus = if (lastFrameIndex >= 0L) "active" else "idle",
                    )
                    val message = when {
                        lastFrameIndex >= 0L -> "Frame $lastFrameIndex"
                        else -> state.detail
                    }
                    if (overlayState.hasFrame != (lastFrameIndex >= 0L) ||
                        overlayState.message != message ||
                        nowMillis - lastOverlayReportAtMillis >= 250L
                    ) {
                        overlayState = PlayerOverlayState(
                            hasFrame = lastFrameIndex >= 0L,
                            message = message,
                            metrics = metrics,
                        )
                        lastOverlayReportAtMillis = nowMillis
                    }
                    if (metrics.presentedFrameCount > 0L &&
                        nowMillis - lastMetricsReportAtMillis >= 250L
                    ) {
                        onMetrics(metrics)
                        lastMetricsReportAtMillis = nowMillis
                    }
                }
            } finally {
                reader.close()
            }
        } else {
            var lastModifiedMillis = Long.MIN_VALUE
            var lastFrameIndex = Long.MIN_VALUE
            var lastBuffer: PlayerBitmapBuffer? = null
            while (currentCoroutineContext().isActive) {
                val updated = withContext(Dispatchers.IO) {
                    readPlayerFrameState(
                        framebufferPath = state.framebufferPath,
                        fallbackMessage = state.detail,
                        lastModifiedMillis = lastModifiedMillis,
                        lastFrameIndex = lastFrameIndex,
                        lastBuffer = lastBuffer,
                        keepLastVisibleFrame = state.keepLastVisibleFrame,
                        tracker = tracker,
                    )
                }
                val bitmap = updated.buffer?.bitmap
                if (bitmap != null) {
                    if (imageView.tag !== bitmap) {
                        imageView.setImageBitmap(bitmap)
                        imageView.tag = bitmap
                    }
                    imageView.postInvalidateOnAnimation()
                }

                val nowMillis = System.currentTimeMillis()
                if (overlayState.hasFrame != updated.hasFrame ||
                    overlayState.message != updated.message ||
                    nowMillis - lastOverlayReportAtMillis >= 250L
                ) {
                    overlayState = PlayerOverlayState(
                        hasFrame = updated.hasFrame,
                        message = updated.message,
                        metrics = updated.metrics,
                    )
                    lastOverlayReportAtMillis = nowMillis
                }

                if (updated.metrics.presentedFrameCount > 0L &&
                    nowMillis - lastMetricsReportAtMillis >= 250L
                ) {
                    onMetrics(updated.metrics)
                    lastMetricsReportAtMillis = nowMillis
                }

                lastModifiedMillis = updated.modifiedAtMillis ?: Long.MIN_VALUE
                lastFrameIndex = updated.frameIndex
                lastBuffer = updated.buffer
                delay(state.playerPollIntervalMs.coerceAtLeast(1L))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { imageView },
            modifier = Modifier.fillMaxSize(),
            update = { },
        )

        if (!overlayState.hasFrame) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.titleName ?: "Launching title",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFFF4F8FB),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = overlayState.message,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCFD9E1),
                    )
                }
            }
        }

        if (state.showFpsCounter && overlayState.hasFrame) {
            Text(
                text = String.format(Locale.US, "Visible %.1f FPS", overlayState.metrics.visibleFps),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color(0x88000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFF4F8FB),
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(Color(0xAA2A0D12), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFD7DB),
            )
        }
    }
}

private data class PlayerFrameState(
    val buffer: PlayerBitmapBuffer?,
    val frameIndex: Long,
    val modifiedAtMillis: Long?,
    val message: String,
    val metrics: PresentationPerformanceMetrics,
) {
    val hasFrame: Boolean
        get() = buffer != null

    companion object {
        fun placeholder(message: String): PlayerFrameState {
            return PlayerFrameState(
                buffer = null,
                frameIndex = -1L,
                modifiedAtMillis = null,
                message = message,
                metrics = PresentationPerformanceMetrics.Empty,
            )
        }
    }
}

private data class PlayerOverlayState(
    val hasFrame: Boolean,
    val message: String,
    val metrics: PresentationPerformanceMetrics,
) {
    companion object {
        fun placeholder(message: String): PlayerOverlayState {
            return PlayerOverlayState(
                hasFrame = false,
                message = message,
                metrics = PresentationPerformanceMetrics.Empty,
            )
        }
    }
}

private data class PlayerBitmapBuffer(
    val bitmap: Bitmap,
    val sourceBytes: ByteArray,
    val scratchBytes: ByteArray,
    val scratchBuffer: ByteBuffer,
)

private data class PlayerFramebufferSample(
    val header: XeniaFramebufferHeader,
    val buffer: PlayerBitmapBuffer,
)

private fun readPlayerFrameState(
    framebufferPath: String,
    fallbackMessage: String,
    lastModifiedMillis: Long,
    lastFrameIndex: Long,
    lastBuffer: PlayerBitmapBuffer?,
    keepLastVisibleFrame: Boolean,
    tracker: RollingPresentationMetricsTracker,
): PlayerFrameState {
    if (framebufferPath.isBlank()) {
        return PlayerFrameState.placeholder(fallbackMessage)
    }

    val path = File(framebufferPath).toPath()
    if (!path.exists()) {
        return PlayerFrameState.placeholder(fallbackMessage)
    }

    val modifiedMillis = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(lastModifiedMillis)
    val header = runCatching { readPlayerFramebufferHeader(path) }.getOrNull()
    if (header == null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = fallbackMessage,
            metrics = tracker.snapshot(frameSourceStatus = "invalid"),
        )
    }

    if (header.frameIndex == lastFrameIndex && lastBuffer != null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = if (modifiedMillis == lastModifiedMillis) {
                "Frame index unchanged"
            } else {
                "Frame header unchanged"
            },
            metrics = tracker.snapshot(frameSourceStatus = "stale"),
        )
    }

    val sample = runCatching { readPlayerFramebufferSample(path, header, lastBuffer) }.getOrNull()
    if (sample == null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = fallbackMessage,
            metrics = tracker.snapshot(frameSourceStatus = "invalid"),
        )
    }

    if (sample.header.frameIndex == lastFrameIndex && lastBuffer != null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Frame header unchanged",
            metrics = tracker.snapshot(frameSourceStatus = "stale"),
        )
    }

    tracker.onExportObserved(sample.header.frameIndex)

    if (keepLastVisibleFrame &&
        lastBuffer != null &&
        !playerFrameHasVisibleContent(sample.buffer.sourceBytes, sample.header.minimumPayloadSize)
    ) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Keeping the last visible frame while the current xenia_fb sample is black.",
            metrics = tracker.snapshot(frameSourceStatus = "black-sample"),
        )
    }

    tracker.onDecoded()
    val updatedBuffer = updatePlayerBitmapBuffer(sample)
    tracker.onPresented(sample.header.frameIndex)
    return PlayerFrameState(
        buffer = updatedBuffer,
        frameIndex = sample.header.frameIndex,
        modifiedAtMillis = modifiedMillis,
        message = "Frame ${sample.header.frameIndex}",
        metrics = tracker.snapshot(frameSourceStatus = "active"),
    )
}

private fun readPlayerFramebufferHeader(
    path: java.nio.file.Path,
): XeniaFramebufferHeader {
    val headerBytes = ByteArray(PlayerFramebufferHeaderSize)
    path.inputStream().use { input ->
        readExactly(input, headerBytes, PlayerFramebufferHeaderSize)
    }
    return XeniaFramebufferCodec.decodeHeader(headerBytes)
}

private fun readPlayerFramebufferSample(
    path: java.nio.file.Path,
    header: XeniaFramebufferHeader,
    existing: PlayerBitmapBuffer?,
): PlayerFramebufferSample {
    val headerBytes = ByteArray(PlayerFramebufferHeaderSize)
    path.inputStream().use { input ->
        readExactly(input, headerBytes, PlayerFramebufferHeaderSize)
        val buffer = if (existing == null ||
            existing.bitmap.width != header.width ||
            existing.bitmap.height != header.height ||
            existing.sourceBytes.size != header.minimumPayloadSize
        ) {
            createPlayerBitmapBuffer(
                width = header.width,
                height = header.height,
                payloadSize = header.minimumPayloadSize,
            )
        } else {
            existing
        }
        readExactly(input, buffer.sourceBytes, header.minimumPayloadSize)
        return PlayerFramebufferSample(
            header = header,
            buffer = buffer,
        )
    }
}

private fun createPlayerBitmapBuffer(
    width: Int,
    height: Int,
    payloadSize: Int,
): PlayerBitmapBuffer {
    val scratchBytes = ByteArray(width * height * 4)
    return PlayerBitmapBuffer(
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        sourceBytes = ByteArray(payloadSize),
        scratchBytes = scratchBytes,
        scratchBuffer = ByteBuffer.wrap(scratchBytes),
    )
}

private fun updatePlayerBitmapBuffer(
    sample: PlayerFramebufferSample,
): PlayerBitmapBuffer {
    val header = sample.header
    val buffer = sample.buffer
    val width = header.width
    val height = header.height
    val stride = header.stride
    val sourceBytes = buffer.sourceBytes
    val scratchBytes = buffer.scratchBytes
    val rowByteCount = width * 4
    if (stride == rowByteCount) {
        System.arraycopy(sourceBytes, 0, scratchBytes, 0, rowByteCount * height)
    } else {
        var destinationIndex = 0
        for (y in 0 until height) {
            val rowStart = y * stride
            System.arraycopy(sourceBytes, rowStart, scratchBytes, destinationIndex, rowByteCount)
            destinationIndex += rowByteCount
        }
    }
    buffer.scratchBuffer.rewind()
    buffer.bitmap.copyPixelsFromBuffer(buffer.scratchBuffer)
    buffer.scratchBuffer.rewind()
    return buffer
}

private fun updatePlayerBitmapBufferFromSharedFrame(
    frame: SharedFrameTransportFrame,
    existing: PlayerBitmapBuffer?,
): PlayerBitmapBuffer {
    val header = frame.header
    val payloadSize = header.payloadSize
    val buffer = if (existing == null ||
        existing.bitmap.width != header.width ||
        existing.bitmap.height != header.height ||
        existing.sourceBytes.size != payloadSize
    ) {
        createPlayerBitmapBuffer(
            width = header.width,
            height = header.height,
            payloadSize = payloadSize,
        )
    } else {
        existing
    }

    System.arraycopy(frame.rgbaBytes, 0, buffer.sourceBytes, 0, payloadSize)
    copySharedFrameRowsToBitmapBuffer(
        rgbaBytes = buffer.sourceBytes,
        destination = buffer.scratchBytes,
        width = header.width,
        height = header.height,
        stride = header.stride,
    )
    buffer.scratchBuffer.rewind()
    buffer.bitmap.copyPixelsFromBuffer(buffer.scratchBuffer)
    buffer.scratchBuffer.rewind()
    return buffer
}

private fun copySharedFrameRowsToBitmapBuffer(
    rgbaBytes: ByteArray,
    destination: ByteArray,
    width: Int,
    height: Int,
    stride: Int,
) {
    val rowByteCount = width * 4
    if (stride == rowByteCount) {
        System.arraycopy(rgbaBytes, 0, destination, 0, rowByteCount * height)
        return
    }

    var destinationIndex = 0
    for (y in 0 until height) {
        val rowStart = y * stride
        System.arraycopy(rgbaBytes, rowStart, destination, destinationIndex, rowByteCount)
        destinationIndex += rowByteCount
    }
}

private fun playerFrameHasVisibleContent(
    rgbaBytes: ByteArray,
    payloadSize: Int,
): Boolean {
    var index = 0
    while (index + 3 < payloadSize) {
        val red = rgbaBytes[index].toInt() and 0xFF
        val green = rgbaBytes[index + 1].toInt() and 0xFF
        val blue = rgbaBytes[index + 2].toInt() and 0xFF
        if (red != 0 || green != 0 || blue != 0) {
            return true
        }
        index += 96
    }
    return false
}

private fun readExactly(
    input: InputStream,
    destination: ByteArray,
    byteCount: Int,
) {
    var offset = 0
    while (offset < byteCount) {
        val read = input.read(destination, offset, byteCount - offset)
        require(read >= 0) {
            "xenia_fb read truncated: expected $byteCount bytes, got $offset"
        }
        offset += read
    }
}

@Composable
private fun rememberPlayerImageView(context: Context): ImageView {
    return remember(context) {
        ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
}

private const val PlayerFramebufferHeaderSize = 32
