package emu.x360.mobile.dev.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

enum class XeniaFramebufferPixelFormat(
    val wireValue: Int,
) {
    R8G8B8X8(1),
    ;

    companion object {
        fun fromWireValue(wireValue: Int): XeniaFramebufferPixelFormat {
            return entries.firstOrNull { it.wireValue == wireValue }
                ?: throw IllegalArgumentException("Unsupported xenia_fb pixel format: $wireValue")
        }
    }
}

data class XeniaFramebufferHeader(
    val width: Int,
    val height: Int,
    val stride: Int,
    val pixelFormat: XeniaFramebufferPixelFormat,
    val frameIndex: Long,
) {
    val minimumPayloadSize: Int
        get() = when {
            width <= 0 || height <= 0 || stride < width * 4 -> 0
            height == 1 -> width * 4
            else -> stride * (height - 1) + (width * 4)
        }
}

data class XeniaFramebufferFrame(
    val header: XeniaFramebufferHeader,
    val rgbaBytes: ByteArray,
)

object XeniaFramebufferCodec {
    private const val HeaderSize = 32
    private val Magic = byteArrayOf('X'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    fun readHeader(path: Path): XeniaFramebufferHeader {
        require(Files.exists(path)) { "xenia_fb does not exist: $path" }
        val headerBytes = path.inputStream().use { input -> input.readNBytes(HeaderSize) }
        return decodeHeader(headerBytes)
    }

    fun readFrame(path: Path): XeniaFramebufferFrame {
        require(Files.exists(path)) { "xenia_fb does not exist: $path" }
        return decodeFrame(Files.readAllBytes(path))
    }

    fun decodeHeader(raw: ByteArray): XeniaFramebufferHeader {
        require(raw.size >= HeaderSize) {
            "xenia_fb header incomplete: expected at least $HeaderSize bytes, got ${raw.size}"
        }
        require(raw[0] == Magic[0] && raw[1] == Magic[1] && raw[2] == Magic[2] && raw[3] == Magic[3]) {
            "xenia_fb magic mismatch"
        }

        val buffer = ByteBuffer.wrap(raw, 0, HeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buffer.get(magic)
        val headerSize = buffer.int
        require(headerSize == HeaderSize) {
            "xenia_fb header size mismatch: expected $HeaderSize, got $headerSize"
        }

        val width = buffer.int
        val height = buffer.int
        val stride = buffer.int
        val pixelFormat = XeniaFramebufferPixelFormat.fromWireValue(buffer.int)
        val frameIndex = buffer.long
        require(width > 0) { "xenia_fb width must be positive" }
        require(height > 0) { "xenia_fb height must be positive" }
        require(stride >= width * 4) {
            "xenia_fb stride too small: stride=$stride width=$width"
        }
        return XeniaFramebufferHeader(
            width = width,
            height = height,
            stride = stride,
            pixelFormat = pixelFormat,
            frameIndex = frameIndex,
        )
    }

    fun decodeFrame(raw: ByteArray): XeniaFramebufferFrame {
        val header = decodeHeader(raw)
        val payload = raw.copyOfRange(HeaderSize, raw.size)
        require(payload.size == header.minimumPayloadSize) {
            "xenia_fb payload size mismatch: expected ${header.minimumPayloadSize}, got ${payload.size}"
        }
        return XeniaFramebufferFrame(
            header = header,
            rgbaBytes = payload,
        )
    }
}
