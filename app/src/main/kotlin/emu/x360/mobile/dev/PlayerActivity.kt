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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import emu.x360.mobile.dev.ui.theme.X360RebuildTheme
import java.io.File
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
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
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val sessionKey = state.sessionId ?: state.framebufferPath
    val frameState by produceState(
        initialValue = PlayerFrameState.placeholder(state.detail),
        key1 = sessionKey,
    ) {
        var lastModifiedMillis = Long.MIN_VALUE
        var lastFrameIndex = Long.MIN_VALUE
        var lastBitmap: Bitmap? = null
        while (currentCoroutineContext().isActive) {
            val updated = withContext(Dispatchers.IO) {
                readPlayerFrameState(
                    framebufferPath = state.framebufferPath,
                    fallbackMessage = state.detail,
                    lastModifiedMillis = lastModifiedMillis,
                    lastFrameIndex = lastFrameIndex,
                    lastBitmap = lastBitmap,
                )
            }
            value = updated
            lastModifiedMillis = updated.modifiedAtMillis ?: Long.MIN_VALUE
            lastFrameIndex = updated.frameIndex
            lastBitmap = updated.bitmap
            delay(120)
        }
    }
    val context = LocalContext.current
    var displayFps by remember(sessionKey) { mutableFloatStateOf(0f) }
    var fpsWindowStartFrameIndex by remember(sessionKey) { mutableLongStateOf(-1L) }
    var fpsWindowStartTimeMillis by remember(sessionKey) { mutableLongStateOf(0L) }
    LaunchedEffect(sessionKey, state.showFpsCounter, frameState.frameIndex) {
        if (!state.showFpsCounter) {
            displayFps = 0f
            fpsWindowStartFrameIndex = frameState.frameIndex
            fpsWindowStartTimeMillis = if (frameState.frameIndex >= 0L) System.currentTimeMillis() else 0L
            return@LaunchedEffect
        }

        val currentFrameIndex = frameState.frameIndex
        if (currentFrameIndex < 0L) {
            return@LaunchedEffect
        }

        val nowMillis = System.currentTimeMillis()
        if (fpsWindowStartFrameIndex < 0L || fpsWindowStartTimeMillis <= 0L) {
            fpsWindowStartFrameIndex = currentFrameIndex
            fpsWindowStartTimeMillis = nowMillis
            return@LaunchedEffect
        }

        val deltaFrames = currentFrameIndex - fpsWindowStartFrameIndex
        val deltaMillis = nowMillis - fpsWindowStartTimeMillis
        if (deltaFrames > 0L && deltaMillis >= 500L) {
            displayFps = (deltaFrames * 1000f) / deltaMillis.toFloat()
            fpsWindowStartFrameIndex = currentFrameIndex
            fpsWindowStartTimeMillis = nowMillis
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val bitmap = frameState.bitmap
        val imageView = rememberPlayerImageView(context)
        LaunchedEffect(bitmap) {
            imageView.setImageBitmap(bitmap)
        }
        AndroidView(
            factory = { imageView },
            modifier = Modifier.fillMaxSize(),
            update = {},
        )

        if (bitmap == null) {
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
                        text = frameState.message,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCFD9E1),
                    )
                }
            }
        }

        if (state.showFpsCounter && bitmap != null) {
            Text(
                text = String.format(Locale.US, "%.1f FPS", displayFps),
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
    val bitmap: Bitmap?,
    val frameIndex: Long,
    val modifiedAtMillis: Long?,
    val message: String,
) {
    companion object {
        fun placeholder(message: String): PlayerFrameState {
            return PlayerFrameState(
                bitmap = null,
                frameIndex = -1L,
                modifiedAtMillis = null,
                message = message,
            )
        }
    }
}

private fun readPlayerFrameState(
    framebufferPath: String,
    fallbackMessage: String,
    lastModifiedMillis: Long,
    lastFrameIndex: Long,
    lastBitmap: Bitmap?,
): PlayerFrameState {
    if (framebufferPath.isBlank()) {
        return PlayerFrameState.placeholder(fallbackMessage)
    }

    val path = File(framebufferPath).toPath()
    if (!path.exists()) {
        return PlayerFrameState.placeholder(fallbackMessage)
    }

    val modifiedMillis = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(lastModifiedMillis)
    if (modifiedMillis == lastModifiedMillis && lastBitmap != null) {
        return PlayerFrameState(
            bitmap = lastBitmap,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Frame unchanged",
        )
    }

    val frame = runCatching { XeniaFramebufferCodec.readFrame(path) }.getOrNull()
    if (frame == null) {
        return PlayerFrameState(
            bitmap = lastBitmap,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = fallbackMessage,
        )
    }

    if (lastBitmap != null && !playerFrameHasVisibleContent(frame.rgbaBytes)) {
        return PlayerFrameState(
            bitmap = lastBitmap,
            frameIndex = frame.header.frameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Keeping the last visible frame while the current xenia_fb sample is black.",
        )
    }

    return PlayerFrameState(
        bitmap = playerFrameToBitmap(frame),
        frameIndex = frame.header.frameIndex,
        modifiedAtMillis = modifiedMillis,
        message = "Frame ${frame.header.frameIndex}",
    )
}

private fun playerFrameToBitmap(frame: emu.x360.mobile.dev.runtime.XeniaFramebufferFrame): Bitmap {
    val width = frame.header.width
    val height = frame.header.height
    val stride = frame.header.stride
    val pixels = IntArray(width * height)
    var pixelIndex = 0
    for (y in 0 until height) {
        val rowStart = y * stride
        for (x in 0 until width) {
            val base = rowStart + (x * 4)
            val r = frame.rgbaBytes[base].toInt() and 0xFF
            val g = frame.rgbaBytes[base + 1].toInt() and 0xFF
            val b = frame.rgbaBytes[base + 2].toInt() and 0xFF
            pixels[pixelIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun playerFrameHasVisibleContent(rgbaBytes: ByteArray): Boolean {
    var index = 0
    while (index + 3 < rgbaBytes.size) {
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
