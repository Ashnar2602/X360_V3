package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class XeniaFramebufferCodecTest {
    @Test
    fun `decode header rejects wrong magic`() {
        val raw = validFramebufferBytes().apply {
            this[0] = 'B'.code.toByte()
        }

        val failure = runCatching { XeniaFramebufferCodec.decodeHeader(raw) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("magic mismatch")
    }

    @Test
    fun `decode header rejects incomplete payload`() {
        val raw = ByteArray(12)

        val failure = runCatching { XeniaFramebufferCodec.decodeHeader(raw) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("header incomplete")
    }

    @Test
    fun `decode frame rejects payload size mismatch`() {
        val raw = validFramebufferBytes().copyOfRange(0, 32 + 4)

        val failure = runCatching { XeniaFramebufferCodec.decodeFrame(raw) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("payload size mismatch")
    }

    @Test
    fun `decode frame returns valid header and payload`() {
        val raw = validFramebufferBytes()

        val frame = XeniaFramebufferCodec.decodeFrame(raw)

        assertThat(frame.header.width).isEqualTo(2)
        assertThat(frame.header.height).isEqualTo(2)
        assertThat(frame.header.stride).isEqualTo(8)
        assertThat(frame.header.frameIndex).isEqualTo(7L)
        assertThat(frame.header.pixelFormat).isEqualTo(XeniaFramebufferPixelFormat.R8G8B8X8)
        assertThat(frame.rgbaBytes.size).isEqualTo(16)
    }

    private fun validFramebufferBytes(): ByteArray {
        val payload = byteArrayOf(
            0x10, 0x20, 0x30, 0x00,
            0x40, 0x50, 0x60, 0x00,
            0x70, 0x11, 0x22, 0x00,
            0x33, 0x44, 0x55, 0x00,
        )
        val header = ByteBuffer.allocate(32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('X'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte()))
            .putInt(32)
            .putInt(2)
            .putInt(2)
            .putInt(8)
            .putInt(XeniaFramebufferPixelFormat.R8G8B8X8.wireValue)
            .putLong(7L)
            .array()
        return header + payload
    }
}
