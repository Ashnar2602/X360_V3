package emu.x360.mobile.dev.bootstrap

import android.os.ParcelFileDescriptor
import emu.x360.mobile.dev.nativebridge.NativeBridge
import emu.x360.mobile.dev.runtime.InheritedFileDescriptor
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.SharedFrameTransportCodec
import emu.x360.mobile.dev.runtime.SharedFrameTransportConfig
import emu.x360.mobile.dev.runtime.SharedFrameTransportFrame
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

internal class PlayerPresentationSession private constructor(
    val transportPath: Path,
    private val guestTransportPath: String,
    private val signalReadPfd: ParcelFileDescriptor?,
    private val sessionRoot: Path,
) : AutoCloseable {
    val inheritedFileDescriptors: List<InheritedFileDescriptor> = emptyList()

    val launchEnvironment: Map<String, String> = buildMap {
        put(TransportPathEnvName, guestTransportPath)
    }

    fun openReader(): SharedFramePresentationReader? {
        if (!transportPath.exists()) {
            return null
        }
        val duplicatedSignalPfd = signalReadPfd?.let { original ->
            runCatching { ParcelFileDescriptor.dup(original.fileDescriptor) }.getOrNull()
        }
        return SharedFramePresentationReader(
            transportPath = transportPath,
            signalReadPfd = duplicatedSignalPfd,
        )
    }

    override fun close() {
        runCatching { signalReadPfd?.close() }
        runCatching { Files.deleteIfExists(transportPath) }
        runCatching { Files.deleteIfExists(sessionRoot) }
    }

    companion object {
        private const val ShmExecFdFloor = 224
        private const val SignalExecFdFloor = 228
        const val TransportPathEnvName = "X360_FRAME_TRANSPORT_PATH"
        const val ShmFdEnvName = "X360_FRAME_TRANSPORT_SHM_FD"
        const val SignalFdEnvName = "X360_FRAME_TRANSPORT_SIGNAL_FD"

        fun create(
            directories: RuntimeDirectories,
            sessionId: String,
            presentationSettings: XeniaPresentationSettings,
        ): PlayerPresentationSession? {
            val sessionRoot = directories.rootfsTmpXeniaRoot
                .resolve("presentation")
                .resolve("session-$sessionId")
            sessionRoot.createDirectories()
            val transportPath = sessionRoot.resolve("frame-transport.bin").absolute().normalize()
            val config = SharedFrameTransportConfig(
                width = presentationSettings.internalDisplayResolution.width,
                height = presentationSettings.internalDisplayResolution.height,
            )

            val randomAccessFile = runCatching { RandomAccessFile(transportPath.toFile(), "rw") }.getOrNull()
                ?: return null
            randomAccessFile.use { file ->
                file.setLength(config.mappedByteCount.toLong())
                val mapped = file.channel.map(FileChannel.MapMode.READ_WRITE, 0L, config.mappedByteCount.toLong())
                SharedFrameTransportCodec.initialize(mapped, config)
                mapped.force()
            }

            return PlayerPresentationSession(
                transportPath = transportPath,
                guestTransportPath = transportPath.toGuestPath(directories.rootfs),
                signalReadPfd = null,
                sessionRoot = sessionRoot,
            )
        }
    }
}

internal class SharedFramePresentationReader(
    transportPath: Path,
    private val signalReadPfd: ParcelFileDescriptor?,
) : AutoCloseable {
    private val file = RandomAccessFile(transportPath.toFile(), "r")
    private val channel = file.channel
    private val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
    private val signalStream = signalReadPfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    private var reusableFrameBytes: ByteArray? = null

    fun awaitNextFrame(
        lastFrameIndex: Long,
        timeoutMillis: Long,
    ): SharedFrameTransportFrame? {
        if (signalReadPfd != null) {
            val waitResult = NativeBridge.pollFdReadable(
                signalReadPfd.fd,
                timeoutMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            )
            if (waitResult > 0) {
                drainSignalPipe()
            }
        } else if (timeoutMillis > 0L) {
            Thread.sleep(timeoutMillis.coerceAtMost(250L))
        }
        val frame = SharedFrameTransportCodec.tryCopyLatestFrame(mapped, reusableFrameBytes)
        if (frame != null) {
            reusableFrameBytes = frame.rgbaBytes
        }
        return frame?.takeIf { it.header.frameIndex > lastFrameIndex }
    }

    fun peekLatestFrame(): SharedFrameTransportFrame? {
        val frame = SharedFrameTransportCodec.tryCopyLatestFrame(mapped, reusableFrameBytes)
        if (frame != null) {
            reusableFrameBytes = frame.rgbaBytes
        }
        return frame
    }

    override fun close() {
        runCatching { signalStream?.close() }
        runCatching { signalReadPfd?.close() }
        runCatching { channel.close() }
        runCatching { file.close() }
    }

    private fun drainSignalPipe() {
        val stream = signalStream ?: return
        val sink = ByteArray(64)
        while (stream.available() > 0) {
            val read = stream.read(sink)
            if (read <= 0) {
                break
            }
        }
    }
}
