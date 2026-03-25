package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class SharedInputTransportTest {
    @Test
    fun `codec initializes disconnected snapshot`() {
        val buffer = ByteBuffer.allocateDirect(SharedInputTransportCodec.MappedByteCount)

        SharedInputTransportCodec.initialize(buffer)
        val header = SharedInputTransportCodec.decodeHeader(buffer)
        val snapshot = SharedInputTransportCodec.tryReadLatestSnapshot(buffer)

        assertThat(header.sequence).isEqualTo(0L)
        assertThat(header.controllerConnected).isFalse()
        assertThat(snapshot?.controller).isEqualTo(SharedInputControllerState.Disconnected)
    }

    @Test
    fun `codec publishes and re-reads controller state`() {
        val buffer = ByteBuffer.allocateDirect(SharedInputTransportCodec.MappedByteCount)
        SharedInputTransportCodec.initialize(buffer)

        val published = SharedInputTransportCodec.publish(
            buffer = buffer,
            controllerState = SharedInputControllerState(
                connected = true,
                buttons = SharedInputButtons.A or SharedInputButtons.START,
                leftTrigger = 128,
                rightTrigger = 255,
                leftStickX = 1234,
                leftStickY = -2048,
                rightStickX = 8192,
                rightStickY = -8192,
            ),
            timestampMillis = 42L,
        )
        val readBack = SharedInputTransportCodec.tryReadLatestSnapshot(buffer)

        assertThat(readBack).isEqualTo(published)
        assertThat(readBack?.header?.sequence).isEqualTo(2L)
        assertThat(readBack?.controller?.buttons).isEqualTo(SharedInputButtons.A or SharedInputButtons.START)
    }

    @Test
    fun `codec rejects torn in-progress snapshot`() {
        val buffer = ByteBuffer.allocateDirect(SharedInputTransportCodec.MappedByteCount)
        SharedInputTransportCodec.initialize(buffer)

        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).putLong(24, 1L)

        assertThat(SharedInputTransportCodec.tryReadLatestSnapshot(buffer)).isNull()
    }
}
