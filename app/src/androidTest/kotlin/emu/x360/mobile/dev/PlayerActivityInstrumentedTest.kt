package emu.x360.mobile.dev

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.SharedFrameTransportCodec
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.max
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerActivityInstrumentedTest {
    @Test
    fun playerActivityRendersVisibleFramesWhenFpsCounterIsEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Player smoke is opt-in and requires the Dante ISO on-device",
            arguments.containsKey("dante_uri_mode") || arguments.getString("enable_dante_smoke") == "1",
        )

        val isoPath = findDanteIso()
        assumeTrue("Dante's Inferno ISO not found on this device", isoPath != null)
        val resolvedIsoPath = isoPath!!
        val launchUri = buildDocumentUri(resolvedIsoPath) ?: Uri.fromFile(resolvedIsoPath.toFile())

        val manager = AppRuntimeManager(context)
        manager.install()
        val imported = withShellIdentity {
            manager.importIso(launchUri)
        }
        val entry = imported.gameLibraryEntries.firstOrNull { it.displayName.lowercase().contains("dante") }
            ?: imported.gameLibraryEntries.first()

        val showFpsCounter = arguments.getString("player_show_fps") != "0"
        AppSettingsStore(context.filesDir.toPath()).save(
            emu.x360.mobile.dev.runtime.AppSettings(showFpsCounter = showFpsCounter),
        )

        val scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, entry.id))
        try {
            val diagnostics = waitForVisiblePlayerDiagnostics(
                manager = manager,
                minimumVisibleFps = 1f,
            )
            assertWithMessage(
                buildString {
                    appendLine("Expected PlayerActivity to render non-black content.")
                    appendLine("showFpsCounter=$showFpsCounter")
                    appendLine("isoPath=$resolvedIsoPath")
                    appendLine("entryId=${entry.id}")
                    appendLine("presentationBackend=${diagnostics.presentationBackend}")
                    appendLine("framebufferPath=${diagnostics.framebufferPath}")
                    appendLine("visibleFps=${diagnostics.visibleFps}")
                    appendLine("exportFps=${diagnostics.exportFps}")
                    appendLine("frameStreamStatus=${diagnostics.frameStreamStatus}")
                    appendLine("stage=${diagnostics.lastStartupStage}")
                },
            ).that(diagnostics.visibleFps).isGreaterThan(0f)
            assertThat(diagnostics.frameStreamStatus).isEqualTo("active")
        } finally {
            scenario.onActivity { it.finish() }
            scenario.close()
        }
    }

    @Test
    fun playerActivityUsesDefaultPollingBackendForVisibleFrames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Player smoke is opt-in and requires the Dante ISO on-device",
            arguments.containsKey("dante_uri_mode") || arguments.getString("enable_dante_smoke") == "1",
        )

        val isoPath = findDanteIso()
        assumeTrue("Dante's Inferno ISO not found on this device", isoPath != null)
        val resolvedIsoPath = isoPath!!
        val launchUri = buildDocumentUri(resolvedIsoPath) ?: Uri.fromFile(resolvedIsoPath.toFile())

        val manager = AppRuntimeManager(context)
        manager.install()
        val imported = withShellIdentity {
            manager.importIso(launchUri)
        }
        val entry = imported.gameLibraryEntries.firstOrNull { it.displayName.lowercase().contains("dante") }
            ?: imported.gameLibraryEntries.first()

        AppSettingsStore(context.filesDir.toPath()).save(
            emu.x360.mobile.dev.runtime.AppSettings(showFpsCounter = true),
        )

        val scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, entry.id))
        try {
            val diagnostics = waitForVisiblePlayerDiagnostics(
                manager = manager,
                minimumVisibleFps = 1f,
            )
            assertWithMessage(
                buildString {
                    appendLine("Expected the normal player path to use the framebuffer-polling presentation backend.")
                    appendLine("isoPath=$resolvedIsoPath")
                    appendLine("presentationBackend=${diagnostics.presentationBackend}")
                    appendLine("framebufferPath=${diagnostics.framebufferPath}")
                    appendLine("visibleFps=${diagnostics.visibleFps}")
                    appendLine("exportFps=${diagnostics.exportFps}")
                    appendLine("captureFps=${diagnostics.captureFps}")
                    appendLine("swapFps=${diagnostics.swapFps}")
                    appendLine("stage=${diagnostics.lastStartupStage}")
                    appendLine("frameStreamStatus=${diagnostics.frameStreamStatus}")
                    appendLine("detail=${diagnostics.lastStartupDetail}")
                },
            ).that(diagnostics.presentationBackend)
                .isEqualTo(PresentationBackend.FRAMEBUFFER_POLLING.name.lowercase())
            assertThat(diagnostics.framebufferPath).contains("/rootfs/tmp/xenia_fb")
            assertThat(diagnostics.frameStreamStatus).isEqualTo("active")
            assertThat(diagnostics.visibleFps).isGreaterThan(0f)
        } finally {
            scenario.onActivity { it.finish() }
            scenario.close()
        }
    }

    private fun waitForVisiblePlayerDiagnostics(
        manager: AppRuntimeManager,
        minimumVisibleFps: Float,
    ): emu.x360.mobile.dev.bootstrap.XeniaDiagnostics {
        repeat(45) {
            Thread.sleep(1_000L)
            val diagnostics = manager.snapshot(lastAction = "player-visible-check").xeniaDiagnostics
            val framebufferHasVisibleContent = hasVisibleFrameContent(
                backend = diagnostics.presentationBackend,
                transportPath = diagnostics.framebufferPath,
            )
            if (framebufferHasVisibleContent &&
                diagnostics.frameStreamStatus == "active" &&
                diagnostics.lastFrameWidth > 0 &&
                diagnostics.lastFrameHeight > 0 &&
                diagnostics.visibleFps >= minimumVisibleFps
            ) {
                return diagnostics
            }
        }
        return manager.snapshot(lastAction = "player-visible-check-final").xeniaDiagnostics
    }

    private fun hasVisibleFrameContent(
        backend: String,
        transportPath: String,
    ): Boolean {
        if (transportPath.isBlank()) {
            return false
        }
        return when (backend) {
            PresentationBackend.FRAMEBUFFER_SHARED_MEMORY.name.lowercase() -> runCatching {
                RandomAccessFile(File(transportPath), "r").use { file ->
                    val mapped = file.channel.map(FileChannel.MapMode.READ_ONLY, 0L, file.length())
                    val frame = SharedFrameTransportCodec.tryCopyLatestFrame(mapped, null)
                    frame != null && countNonBlackPixels(frame.rgbaBytes, stepPixels = 24) > 0
                }
            }.getOrDefault(false)

            else -> runCatching {
                val frame = XeniaFramebufferCodec.readFrame(Paths.get(transportPath))
                countNonBlackPixels(frame.rgbaBytes, stepPixels = 24) > 0
            }.getOrDefault(false)
        }
    }

    private fun buildDocumentUri(path: Path): Uri? {
        val normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        val primaryPrefix = "/storage/emulated/0/"
        val (volumeId, relative) = when {
            normalized.startsWith(primaryPrefix) -> "primary" to normalized.removePrefix(primaryPrefix)
            normalized.startsWith("/storage/") -> {
                val withoutStorage = normalized.removePrefix("/storage/")
                val volumeId = withoutStorage.substringBefore('/')
                val relative = withoutStorage.substringAfter('/', missingDelimiterValue = "")
                if (volumeId.isBlank() || volumeId == "emulated" || volumeId == "self") {
                    return null
                }
                volumeId to relative
            }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "$volumeId:$relative",
        )
    }

    private fun <T> withShellIdentity(block: () -> T): T {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        return try {
            block()
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun findDanteIso(): Path? {
        val fileNameCandidates = setOf(
            "DantesInferno.iso",
            "Dantes_Inferno.iso",
            "Dante_s_Inferno.iso",
        )
        val rootCandidates = buildRootCandidates()
        rootCandidates.forEach { root ->
            val found = findByName(root, fileNameCandidates, maxDepth = 6)
            if (found != null) {
                return found.toPath()
            }
        }
        return null
    }

    private fun buildRootCandidates(): List<File> {
        val roots = mutableListOf(
            File("/storage/emulated/0"),
            File("/sdcard"),
        )
        roots += discoverExternalRoots(File("/storage"))
        roots += discoverExternalRoots(File("/mnt/media_rw"))
        return roots
            .map { it.absoluteFile }
            .distinctBy { it.absolutePath }
    }

    private fun discoverExternalRoots(parent: File): List<File> {
        if (!parent.exists() || !parent.isDirectory) {
            return emptyList()
        }
        return parent.listFiles()
            ?.filter { child ->
                child.isDirectory &&
                    child.name != "emulated" &&
                    child.name != "self"
            }
            ?.flatMap { volume ->
                listOf(
                    volume,
                    File(volume, "1-Library"),
                    File(volume, "1-Library/roms"),
                )
            }
            ?: emptyList()
    }

    private fun findByName(
        root: File,
        candidates: Set<String>,
        maxDepth: Int,
    ): File? {
        if (!root.exists()) {
            return null
        }
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (current.isFile && candidates.contains(current.name)) {
                return current
            }
            if (!current.isDirectory || depth >= maxDepth) {
                continue
            }
            current.listFiles()?.forEach { child ->
                queue.add(child to depth + 1)
            }
        }
        return null
    }

    private fun countNonBlackPixels(
        rgbaBytes: ByteArray,
        stepPixels: Int,
    ): Int {
        var nonBlackPixels = 0
        val byteStep = max(1, stepPixels) * 4
        var index = 0
        while (index + 3 < rgbaBytes.size) {
            val red = rgbaBytes[index].toInt() and 0xFF
            val green = rgbaBytes[index + 1].toInt() and 0xFF
            val blue = rgbaBytes[index + 2].toInt() and 0xFF
            if (red != 0 || green != 0 || blue != 0) {
                nonBlackPixels += 1
            }
            index += byteStep
        }
        return nonBlackPixels
    }
}
