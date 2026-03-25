package emu.x360.mobile.dev.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SharedInputButtons {
    const val DPAD_UP: Int = 0x0001
    const val DPAD_DOWN: Int = 0x0002
    const val DPAD_LEFT: Int = 0x0004
    const val DPAD_RIGHT: Int = 0x0008
    const val START: Int = 0x0010
    const val BACK: Int = 0x0020
    const val LEFT_THUMB: Int = 0x0040
    const val RIGHT_THUMB: Int = 0x0080
    const val LEFT_SHOULDER: Int = 0x0100
    const val RIGHT_SHOULDER: Int = 0x0200
    const val GUIDE: Int = 0x0400
    const val A: Int = 0x1000
    const val B: Int = 0x2000
    const val X: Int = 0x4000
    const val Y: Int = 0x8000
}

data class SharedInputControllerState(
    val connected: Boolean = false,
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
) {
    init {
        require(leftTrigger in 0..255) { "leftTrigger must be in 0..255" }
        require(rightTrigger in 0..255) { "rightTrigger must be in 0..255" }
        require(leftStickX in Short.MIN_VALUE..Short.MAX_VALUE) { "leftStickX must fit int16" }
        require(leftStickY in Short.MIN_VALUE..Short.MAX_VALUE) { "leftStickY must fit int16" }
        require(rightStickX in Short.MIN_VALUE..Short.MAX_VALUE) { "rightStickX must fit int16" }
        require(rightStickY in Short.MIN_VALUE..Short.MAX_VALUE) { "rightStickY must fit int16" }
    }

    fun isNeutral(): Boolean {
        return buttons == 0 &&
            leftTrigger == 0 &&
            rightTrigger == 0 &&
            leftStickX == 0 &&
            leftStickY == 0 &&
            rightStickX == 0 &&
            rightStickY == 0
    }

    companion object {
        val Disconnected = SharedInputControllerState()
    }
}

data class SharedInputTransportHeader(
    val sequence: Long,
    val timestampMillis: Long,
    val controllerConnected: Boolean,
)

data class SharedInputTransportSnapshot(
    val header: SharedInputTransportHeader,
    val controller: SharedInputControllerState,
)

object SharedInputTransportCodec {
    const val HeaderSize = 72
    const val Version = 1
    const val MappedByteCount = HeaderSize

    private const val MagicOffset = 0
    private const val HeaderSizeOffset = 4
    private const val VersionOffset = 8
    private const val ConnectedOffset = 12
    private const val ReservedOffset = 16
    private const val Reserved2Offset = 20
    private const val SequenceOffset = 24
    private const val TimestampOffset = 32
    private const val ButtonsOffset = 40
    private const val LeftTriggerOffset = 44
    private const val RightTriggerOffset = 48
    private const val LeftStickXOffset = 52
    private const val LeftStickYOffset = 56
    private const val RightStickXOffset = 60
    private const val RightStickYOffset = 64
    private const val Reserved3Offset = 68

    private val Magic = byteArrayOf('X'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())

    fun initialize(buffer: ByteBuffer) {
        require(buffer.capacity() >= MappedByteCount) {
            "Shared input buffer is too small: expected $MappedByteCount, got ${buffer.capacity()}"
        }
        val mapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        mapped.clear()
        mapped.put(MagicOffset, Magic[0])
        mapped.put(MagicOffset + 1, Magic[1])
        mapped.put(MagicOffset + 2, Magic[2])
        mapped.put(MagicOffset + 3, Magic[3])
        mapped.putInt(HeaderSizeOffset, HeaderSize)
        mapped.putInt(VersionOffset, Version)
        mapped.putInt(ConnectedOffset, 0)
        mapped.putInt(ReservedOffset, 0)
        mapped.putInt(Reserved2Offset, 0)
        mapped.putLong(SequenceOffset, 0L)
        mapped.putLong(TimestampOffset, 0L)
        mapped.putInt(ButtonsOffset, 0)
        mapped.putInt(LeftTriggerOffset, 0)
        mapped.putInt(RightTriggerOffset, 0)
        mapped.putInt(LeftStickXOffset, 0)
        mapped.putInt(LeftStickYOffset, 0)
        mapped.putInt(RightStickXOffset, 0)
        mapped.putInt(RightStickYOffset, 0)
        mapped.putInt(Reserved3Offset, 0)
    }

    fun decodeHeader(buffer: ByteBuffer): SharedInputTransportHeader {
        val mapped = validate(buffer)
        return SharedInputTransportHeader(
            sequence = mapped.getLong(SequenceOffset),
            timestampMillis = mapped.getLong(TimestampOffset),
            controllerConnected = mapped.getInt(ConnectedOffset) != 0,
        )
    }

    fun publish(
        buffer: ByteBuffer,
        controllerState: SharedInputControllerState,
        timestampMillis: Long = System.currentTimeMillis(),
    ): SharedInputTransportSnapshot {
        val mapped = validate(buffer)
        val currentSequence = mapped.getLong(SequenceOffset)
        val stableSequence = if ((currentSequence and 1L) != 0L) currentSequence + 1L else currentSequence
        mapped.putLong(SequenceOffset, stableSequence + 1L)
        mapped.putInt(ConnectedOffset, if (controllerState.connected) 1 else 0)
        mapped.putLong(TimestampOffset, timestampMillis)
        mapped.putInt(ButtonsOffset, controllerState.buttons)
        mapped.putInt(LeftTriggerOffset, controllerState.leftTrigger)
        mapped.putInt(RightTriggerOffset, controllerState.rightTrigger)
        mapped.putInt(LeftStickXOffset, controllerState.leftStickX)
        mapped.putInt(LeftStickYOffset, controllerState.leftStickY)
        mapped.putInt(RightStickXOffset, controllerState.rightStickX)
        mapped.putInt(RightStickYOffset, controllerState.rightStickY)
        mapped.putLong(SequenceOffset, stableSequence + 2L)
        return SharedInputTransportSnapshot(
            header = SharedInputTransportHeader(
                sequence = stableSequence + 2L,
                timestampMillis = timestampMillis,
                controllerConnected = controllerState.connected,
            ),
            controller = controllerState,
        )
    }

    fun tryReadLatestSnapshot(buffer: ByteBuffer): SharedInputTransportSnapshot? {
        val mapped = validate(buffer)
        val firstSequence = mapped.getLong(SequenceOffset)
        if ((firstSequence and 1L) != 0L) {
            return null
        }
        val timestampMillis = mapped.getLong(TimestampOffset)
        val controller = SharedInputControllerState(
            connected = mapped.getInt(ConnectedOffset) != 0,
            buttons = mapped.getInt(ButtonsOffset),
            leftTrigger = mapped.getInt(LeftTriggerOffset),
            rightTrigger = mapped.getInt(RightTriggerOffset),
            leftStickX = mapped.getInt(LeftStickXOffset),
            leftStickY = mapped.getInt(LeftStickYOffset),
            rightStickX = mapped.getInt(RightStickXOffset),
            rightStickY = mapped.getInt(RightStickYOffset),
        )
        val secondSequence = mapped.getLong(SequenceOffset)
        if (firstSequence != secondSequence || (secondSequence and 1L) != 0L) {
            return null
        }
        return SharedInputTransportSnapshot(
            header = SharedInputTransportHeader(
                sequence = secondSequence,
                timestampMillis = timestampMillis,
                controllerConnected = controller.connected,
            ),
            controller = controller,
        )
    }

    private fun validate(buffer: ByteBuffer): ByteBuffer {
        val mapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(mapped.capacity() >= MappedByteCount) {
            "Shared input buffer is incomplete: expected $MappedByteCount bytes, got ${mapped.capacity()}"
        }
        require(
            mapped.get(MagicOffset) == Magic[0] &&
                mapped.get(MagicOffset + 1) == Magic[1] &&
                mapped.get(MagicOffset + 2) == Magic[2] &&
                mapped.get(MagicOffset + 3) == Magic[3],
        ) {
            "Shared input magic mismatch"
        }
        require(mapped.getInt(HeaderSizeOffset) == HeaderSize) {
            "Shared input header size mismatch"
        }
        require(mapped.getInt(VersionOffset) == Version) {
            "Shared input version mismatch"
        }
        return mapped
    }
}
