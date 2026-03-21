package emu.x360.mobile.dev

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import java.io.File
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun OutputPreviewCard(
    preview: OutputPreviewUiState,
    latestSessionId: String,
) {
    val renderState by produceState(
        initialValue = FramebufferPreviewRenderState.placeholder(preview.status),
        key1 = preview.framebufferPath,
    ) {
        var lastModifiedMillis = Long.MIN_VALUE
        var lastFrameIndex = Long.MIN_VALUE
        var lastBitmap: Bitmap? = null
        while (currentCoroutineContext().isActive) {
            val updated = withContext(Dispatchers.IO) {
                readFramebufferPreview(
                    framebufferPath = preview.framebufferPath,
                    fallbackStatus = preview.status,
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xB3122230)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Output Preview",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF47C0FF),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Session: ${latestSessionId.ifBlank { "none" }} | Stream: ${preview.status} | Frame: ${preview.frameIndex.takeIf { it >= 0 } ?: "none"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Text(
                text = "Path: ${preview.framebufferPath.ifBlank { "unavailable" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Text(
                text = "Details: ${preview.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC7D2DC),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(renderState.aspectRatio)
                    .background(Color.Black),
            ) {
                val bitmap = renderState.bitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Xenia framebuffer preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = renderState.message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC7D2DC),
                    )
                }
            }
        }
    }
}

private data class FramebufferPreviewRenderState(
    val bitmap: Bitmap?,
    val aspectRatio: Float,
    val frameIndex: Long,
    val modifiedAtMillis: Long?,
    val message: String,
) {
    companion object {
        fun placeholder(status: String): FramebufferPreviewRenderState {
            return FramebufferPreviewRenderState(
                bitmap = null,
                aspectRatio = 16f / 9f,
                frameIndex = -1L,
                modifiedAtMillis = null,
                message = when (status) {
                    "active" -> "Waiting for the first readable framebuffer."
                    "first-frame" -> "First framebuffer detected, preview is warming up."
                    "invalid" -> "The latest framebuffer file is invalid or incomplete."
                    else -> "Launch a visible title to populate /tmp/xenia_fb."
                },
            )
        }
    }
}

private fun readFramebufferPreview(
    framebufferPath: String,
    fallbackStatus: String,
    lastModifiedMillis: Long,
    lastFrameIndex: Long,
    lastBitmap: Bitmap?,
): FramebufferPreviewRenderState {
    if (framebufferPath.isBlank()) {
        return FramebufferPreviewRenderState.placeholder(fallbackStatus)
    }

    val path = File(framebufferPath).toPath()
    if (!path.exists()) {
        return FramebufferPreviewRenderState.placeholder(fallbackStatus)
    }

    val modifiedMillis = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(lastModifiedMillis)
    if (modifiedMillis == lastModifiedMillis && lastBitmap != null) {
        return FramebufferPreviewRenderState(
            bitmap = lastBitmap,
            aspectRatio = lastBitmap.width.toFloat() / lastBitmap.height.toFloat(),
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Live framebuffer unchanged since last poll.",
        )
    }

    val frame = runCatching { XeniaFramebufferCodec.readFrame(path) }.getOrNull()
    if (frame == null) {
        return FramebufferPreviewRenderState(
            bitmap = lastBitmap,
            aspectRatio = lastBitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: (16f / 9f),
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Keeping the last valid frame while the current xenia_fb file settles.",
        )
    }

    val bitmap = rgbaFrameToBitmap(frame)
    return FramebufferPreviewRenderState(
        bitmap = bitmap,
        aspectRatio = frame.header.width.toFloat() / frame.header.height.toFloat(),
        frameIndex = frame.header.frameIndex,
        modifiedAtMillis = modifiedMillis,
        message = "Frame ${frame.header.frameIndex} ${frame.header.width}x${frame.header.height}",
    )
}

private fun rgbaFrameToBitmap(frame: emu.x360.mobile.dev.runtime.XeniaFramebufferFrame): Bitmap {
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
