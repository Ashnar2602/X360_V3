package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class SharedFrameTransportTest {
    @Test
    fun `codec initializes versioned rgba transport header`() {
        val config = SharedFrameTransportConfig(
            width = 4,
            height = 2,
            stride = 16,
            slotCount = 3,
        )
        val buffer = ByteBuffer.allocate(config.mappedByteCount).order(ByteOrder.LITTLE_ENDIAN)

        SharedFrameTransportCodec.initialize(buffer, config)
        val header = SharedFrameTransportCodec.decodeHeader(buffer)

        assertThat(header.width).isEqualTo(4)
        assertThat(header.height).isEqualTo(2)
        assertThat(header.stride).isEqualTo(16)
        assertThat(header.pixelFormat).isEqualTo(SharedFrameTransportPixelFormat.RGBA8888_SRGB)
        assertThat(header.colorSpace).isEqualTo(SharedFrameTransportColorSpace.SRGB)
        assertThat(header.slotCount).isEqualTo(3)
        assertThat(header.frameIndex).isEqualTo(-1L)
        assertThat(header.publishSequence).isEqualTo(0L)
        assertThat(header.payloadSize).isEqualTo(32)
    }

    @Test
    fun `codec copies latest published frame without tearing`() {
        val config = SharedFrameTransportConfig(
            width = 2,
            height = 2,
            stride = 8,
            slotCount = 3,
        )
        val buffer = ByteBuffer.allocate(config.mappedByteCount).order(ByteOrder.LITTLE_ENDIAN)
        SharedFrameTransportCodec.initialize(buffer, config)

        publishFrame(
            buffer = buffer,
            config = config,
            slotIndex = 1,
            frameIndex = 7L,
            publishSequence = 22L,
            payload = byteArrayOf(
                0x10, 0x20, 0x30, 0x40,
                0x50, 0x60, 0x70, 0x7F.toByte(),
                0x01, 0x02, 0x03, 0x04,
                0xA0.toByte(), 0xB0.toByte(), 0xC0.toByte(), 0xFF.toByte(),
            ),
        )

        val frame = SharedFrameTransportCodec.tryCopyLatestFrame(buffer, null)

        assertThat(frame).isNotNull()
        frame!!
        assertThat(frame.header.frameIndex).isEqualTo(7L)
        assertThat(frame.header.activeSlot).isEqualTo(1)
        assertThat(frame.header.publishSequence).isEqualTo(22L)
        assertThat(frame.rgbaBytes.size).isEqualTo(16)
        assertThat(frame.rgbaBytes[0].toInt() and 0xFF).isEqualTo(0x10)
        assertThat(frame.rgbaBytes[14].toInt() and 0xFF).isEqualTo(0xC0)
    }

    @Test
    fun `codec rejects frame while publication is in progress`() {
        val config = SharedFrameTransportConfig(
            width = 2,
            height = 2,
            stride = 8,
            slotCount = 3,
        )
        val buffer = ByteBuffer.allocate(config.mappedByteCount).order(ByteOrder.LITTLE_ENDIAN)
        SharedFrameTransportCodec.initialize(buffer, config)
        publishFrame(
            buffer = buffer,
            config = config,
            slotIndex = 0,
            frameIndex = 3L,
            publishSequence = 11L,
            payload = ByteArray(16) { it.toByte() },
        )

        val frame = SharedFrameTransportCodec.tryCopyLatestFrame(buffer, null)

        assertThat(frame).isNull()
    }

    private fun publishFrame(
        buffer: ByteBuffer,
        config: SharedFrameTransportConfig,
        slotIndex: Int,
        frameIndex: Long,
        publishSequence: Long,
        payload: ByteArray,
    ) {
        val slotOffset = SharedFrameTransportCodec.HeaderSize + (slotIndex * config.slotSize)
        val mapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        mapped.position(slotOffset)
        mapped.put(payload)
        mapped.putInt(40, slotIndex)
        mapped.putLong(48, frameIndex)
        mapped.putLong(56, publishSequence)
    }
}
