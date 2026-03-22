package emu.x360.mobile.dev.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SharedFrameTransportPixelFormat(
    val wireValue: Int,
) {
    RGBA8888_SRGB(1),
    ;

    companion object {
        fun fromWireValue(wireValue: Int): SharedFrameTransportPixelFormat {
            return entries.firstOrNull { it.wireValue == wireValue }
                ?: throw IllegalArgumentException("Unsupported shared-frame pixel format: $wireValue")
        }
    }
}

enum class SharedFrameTransportColorSpace(
    val wireValue: Int,
) {
    SRGB(1),
    ;

    companion object {
        fun fromWireValue(wireValue: Int): SharedFrameTransportColorSpace {
            return entries.firstOrNull { it.wireValue == wireValue }
                ?: throw IllegalArgumentException("Unsupported shared-frame colorspace: $wireValue")
        }
    }
}

data class SharedFrameTransportConfig(
    val width: Int,
    val height: Int,
    val stride: Int = width * 4,
    val slotCount: Int = 3,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(stride >= width * 4) { "stride must be at least width * 4" }
        require(slotCount >= 2) { "slotCount must be at least 2" }
    }

    val slotSize: Int
        get() = stride * height

    val mappedByteCount: Int
        get() = SharedFrameTransportCodec.HeaderSize + slotSize * slotCount
}

data class SharedFrameTransportHeader(
    val width: Int,
    val height: Int,
    val stride: Int,
    val pixelFormat: SharedFrameTransportPixelFormat,
    val colorSpace: SharedFrameTransportColorSpace,
    val slotCount: Int,
    val slotSize: Int,
    val activeSlot: Int,
    val frameIndex: Long,
    val publishSequence: Long,
) {
    val payloadSize: Int
        get() = stride * height
}

data class SharedFrameTransportFrame(
    val header: SharedFrameTransportHeader,
    val rgbaBytes: ByteArray,
)

object SharedFrameTransportCodec {
    const val HeaderSize = 64
    const val Version = 1

    private const val MagicOffset = 0
    private const val HeaderSizeOffset = 4
    private const val VersionOffset = 8
    private const val WidthOffset = 12
    private const val HeightOffset = 16
    private const val StrideOffset = 20
    private const val PixelFormatOffset = 24
    private const val ColorSpaceOffset = 28
    private const val SlotCountOffset = 32
    private const val SlotSizeOffset = 36
    private const val ActiveSlotOffset = 40
    private const val ReservedOffset = 44
    private const val FrameIndexOffset = 48
    private const val PublishSequenceOffset = 56

    private val Magic = byteArrayOf('X'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())

    fun initialize(buffer: ByteBuffer, config: SharedFrameTransportConfig) {
        require(buffer.capacity() >= config.mappedByteCount) {
            "Shared transport buffer is too small: expected ${config.mappedByteCount}, got ${buffer.capacity()}"
        }
        val mapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        mapped.clear()
        mapped.put(MagicOffset, Magic[0])
        mapped.put(MagicOffset + 1, Magic[1])
        mapped.put(MagicOffset + 2, Magic[2])
        mapped.put(MagicOffset + 3, Magic[3])
        mapped.putInt(HeaderSizeOffset, HeaderSize)
        mapped.putInt(VersionOffset, Version)
        mapped.putInt(WidthOffset, config.width)
        mapped.putInt(HeightOffset, config.height)
        mapped.putInt(StrideOffset, config.stride)
        mapped.putInt(PixelFormatOffset, SharedFrameTransportPixelFormat.RGBA8888_SRGB.wireValue)
        mapped.putInt(ColorSpaceOffset, SharedFrameTransportColorSpace.SRGB.wireValue)
        mapped.putInt(SlotCountOffset, config.slotCount)
        mapped.putInt(SlotSizeOffset, config.slotSize)
        mapped.putInt(ActiveSlotOffset, 0)
        mapped.putInt(ReservedOffset, 0)
        mapped.putLong(FrameIndexOffset, -1L)
        mapped.putLong(PublishSequenceOffset, 0L)
    }

    fun decodeHeader(buffer: ByteBuffer): SharedFrameTransportHeader {
        val mapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(mapped.capacity() >= HeaderSize) {
            "Shared transport header is incomplete: expected $HeaderSize bytes, got ${mapped.capacity()}"
        }
        require(
            mapped.get(MagicOffset) == Magic[0] &&
                mapped.get(MagicOffset + 1) == Magic[1] &&
                mapped.get(MagicOffset + 2) == Magic[2] &&
                mapped.get(MagicOffset + 3) == Magic[3],
        ) {
            "Shared transport magic mismatch"
        }
        val headerSize = mapped.getInt(HeaderSizeOffset)
        require(headerSize == HeaderSize) {
            "Shared transport header size mismatch: expected $HeaderSize, got $headerSize"
        }
        val version = mapped.getInt(VersionOffset)
        require(version == Version) {
            "Shared transport version mismatch: expected $Version, got $version"
        }
        val width = mapped.getInt(WidthOffset)
        val height = mapped.getInt(HeightOffset)
        val stride = mapped.getInt(StrideOffset)
        val pixelFormat = SharedFrameTransportPixelFormat.fromWireValue(mapped.getInt(PixelFormatOffset))
        val colorSpace = SharedFrameTransportColorSpace.fromWireValue(mapped.getInt(ColorSpaceOffset))
        val slotCount = mapped.getInt(SlotCountOffset)
        val slotSize = mapped.getInt(SlotSizeOffset)
        val activeSlot = mapped.getInt(ActiveSlotOffset)
        val frameIndex = mapped.getLong(FrameIndexOffset)
        val publishSequence = mapped.getLong(PublishSequenceOffset)
        require(width > 0) { "Shared transport width must be positive" }
        require(height > 0) { "Shared transport height must be positive" }
        require(stride >= width * 4) { "Shared transport stride too small: stride=$stride width=$width" }
        require(slotCount >= 2) { "Shared transport slot count must be at least 2" }
        require(slotSize >= stride * height) { "Shared transport slot size too small: $slotSize" }
        require(activeSlot in 0 until slotCount || frameIndex < 0L) {
            "Shared transport active slot out of bounds: slot=$activeSlot count=$slotCount"
        }
        return SharedFrameTransportHeader(
            width = width,
            height = height,
            stride = stride,
            pixelFormat = pixelFormat,
            colorSpace = colorSpace,
            slotCount = slotCount,
            slotSize = slotSize,
            activeSlot = activeSlot.coerceAtLeast(0),
            frameIndex = frameIndex,
            publishSequence = publishSequence,
        )
    }

    fun tryCopyLatestFrame(
        buffer: ByteBuffer,
        reuse: ByteArray? = null,
    ): SharedFrameTransportFrame? {
        val mapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val firstSequence = mapped.getLong(PublishSequenceOffset)
        if ((firstSequence and 1L) != 0L) {
            return null
        }
        val header = decodeHeader(mapped)
        if (header.frameIndex < 0L) {
            return null
        }
        val payloadSize = header.payloadSize
        val destination = if (reuse != null && reuse.size == payloadSize) {
            reuse
        } else {
            ByteArray(payloadSize)
        }
        val slotOffset = HeaderSize + header.activeSlot * header.slotSize
        val payloadView = mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        payloadView.position(slotOffset)
        payloadView.get(destination, 0, payloadSize)
        val secondSequence = mapped.getLong(PublishSequenceOffset)
        if (firstSequence != secondSequence || (secondSequence and 1L) != 0L) {
            return null
        }
        return SharedFrameTransportFrame(
            header = header,
            rgbaBytes = destination,
        )
    }
}
