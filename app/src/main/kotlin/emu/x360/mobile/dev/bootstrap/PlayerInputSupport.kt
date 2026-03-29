package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.SharedInputControllerState
import emu.x360.mobile.dev.runtime.SharedInputTransportCodec
import emu.x360.mobile.dev.runtime.SharedInputTransportSnapshot
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

internal data class PlayerInputDiagnostics(
    val controllerConnected: Boolean = false,
    val controllerName: String? = null,
    val lastInputSequence: Long = 0L,
    val lastInputAgeMs: Long? = null,
    val inputEventsPerSecond: Float = 0f,
) {
    companion object {
        val Empty = PlayerInputDiagnostics()
    }
}

internal class PlayerInputSession private constructor(
    val transportPath: Path,
    private val guestTransportPath: String,
    private val sessionRoot: Path,
    private val file: RandomAccessFile,
) : AutoCloseable {
    private val channel = file.channel
    private val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0L, SharedInputTransportCodec.MappedByteCount.toLong())

    @Volatile
    private var lastSnapshot: SharedInputTransportSnapshot = SharedInputTransportCodec.tryReadLatestSnapshot(mapped)
        ?: SharedInputTransportCodec.publish(mapped, SharedInputControllerState.Disconnected, timestampMillis = 0L)

    @Volatile
    private var lastControllerName: String? = null

    @Volatile
    private var lastInputEventsPerSecond: Float = 0f

    val inheritedFileDescriptors = emptyList<emu.x360.mobile.dev.runtime.InheritedFileDescriptor>()

    val launchEnvironment: Map<String, String> = mapOf(
        TransportPathEnvName to guestTransportPath,
    )

    fun publish(
        controllerState: SharedInputControllerState,
        controllerName: String? = null,
        inputEventsPerSecond: Float = lastInputEventsPerSecond,
    ): PlayerInputDiagnostics {
        val snapshot = synchronized(mapped) {
            SharedInputTransportCodec.publish(mapped, controllerState)
        }
        lastSnapshot = snapshot
        lastInputEventsPerSecond = inputEventsPerSecond
        if (!controllerName.isNullOrBlank()) {
            lastControllerName = controllerName
        }
        return readDiagnostics()
    }

    fun clear(): PlayerInputDiagnostics {
        return publish(SharedInputControllerState.Disconnected, controllerName = lastControllerName)
    }

    fun readDiagnostics(
        nowMillis: Long = System.currentTimeMillis(),
    ): PlayerInputDiagnostics {
        val snapshot = lastSnapshot
        val ageMs = snapshot.header.timestampMillis.takeIf { it > 0L }?.let { timestamp ->
            (nowMillis - timestamp).coerceAtLeast(0L)
        }
        return PlayerInputDiagnostics(
            controllerConnected = snapshot.controller.connected,
            controllerName = lastControllerName,
            lastInputSequence = snapshot.header.sequence,
            lastInputAgeMs = ageMs,
            inputEventsPerSecond = lastInputEventsPerSecond,
        )
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { file.close() }
        runCatching { Files.deleteIfExists(transportPath) }
        runCatching { Files.deleteIfExists(sessionRoot) }
    }

    companion object {
        const val TransportPathEnvName = "X360_INPUT_TRANSPORT_PATH"

        fun create(
            directories: RuntimeDirectories,
            sessionId: String,
        ): PlayerInputSession? {
            val sessionRoot = directories.rootfsTmpXeniaInputRoot
                .resolve("session-$sessionId")
            sessionRoot.createDirectories()
            val transportPath = sessionRoot.resolve("controller-transport.bin").absolute().normalize()
            val file = runCatching { RandomAccessFile(transportPath.toFile(), "rw") }.getOrNull()
                ?: return null
            try {
                file.setLength(SharedInputTransportCodec.MappedByteCount.toLong())
                val mapped = file.channel.map(FileChannel.MapMode.READ_WRITE, 0L, SharedInputTransportCodec.MappedByteCount.toLong())
                SharedInputTransportCodec.initialize(mapped)
                mapped.force()
            } catch (throwable: Throwable) {
                file.close()
                Files.deleteIfExists(transportPath)
                Files.deleteIfExists(sessionRoot)
                return null
            }
            return PlayerInputSession(
                transportPath = transportPath,
                guestTransportPath = transportPath.toGuestPath(directories.rootfs),
                sessionRoot = sessionRoot,
                file = file,
            )
        }
    }
}
