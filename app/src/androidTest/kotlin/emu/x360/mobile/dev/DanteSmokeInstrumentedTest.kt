package emu.x360.mobile.dev

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.bootstrap.XeniaPresentationSettings
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import java.io.File
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DanteSmokeInstrumentedTest {
    @Test
    fun danteInfernoIsoLaunchReachesTitleBootStages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Dante smoke is opt-in and requires external-storage instrumentation args",
            arguments.containsKey("dante_uri_mode") || arguments.getString("enable_dante_smoke") == "1",
        )
        val isoPath = findDanteIso()
        assumeTrue("Dante's Inferno ISO not found on this device", isoPath != null)
        val resolvedIsoPath = isoPath!!
        val preferFileUri = arguments.getString("dante_uri_mode") == "file"
        val launchUri = if (preferFileUri) {
            Uri.fromFile(resolvedIsoPath.toFile())
        } else {
            buildDocumentUri(resolvedIsoPath) ?: Uri.fromFile(resolvedIsoPath.toFile())
        }

        val manager = AppRuntimeManager(context)
        manager.install()
        val mesaOverride = arguments.getString("dante_mesa_override")
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> MesaRuntimeBranch.valueOf(raw.uppercase()) }
            ?: MesaRuntimeBranch.AUTO
        manager.setMesaOverride(mesaOverride)
        val imported = withShellIdentity {
            manager.importIso(launchUri)
        }
        val entry = imported.gameLibraryEntries.firstOrNull { it.displayName.lowercase().contains("dante") }
            ?: imported.gameLibraryEntries.first()

        val useHeadlessOnly = arguments.getString("dante_presentation_backend") == "headless_only"
        val presentationSettings = if (useHeadlessOnly) {
            XeniaPresentationSettings.HeadlessBringup
        } else {
            XeniaPresentationSettings.FramebufferPolling
        }
        val requiredStage = if (useHeadlessOnly) {
            XeniaStartupStage.TITLE_RUNNING_HEADLESS
        } else {
            XeniaStartupStage.FRAME_STREAM_ACTIVE
        }
        val launched = withShellIdentity {
            manager.launchImportedTitle(
                entryId = entry.id,
                presentationSettings = presentationSettings,
                requiredStage = requiredStage,
            )
        }
        val stage = launched.xeniaDiagnostics.lastStartupStage

        val framebufferPath = context.filesDir.toPath().resolve("rootfs/tmp/xenia_fb")

        assertWithMessage(
            buildString {
                appendLine(
                    if (useHeadlessOnly) {
                        "Expected Dante's Inferno to stay alive headless after module load."
                    } else {
                        "Expected Dante's Inferno to reach an active visible framebuffer stream."
                    },
                )
                appendLine("path=$resolvedIsoPath")
                appendLine("stage=$stage")
                appendLine("detail=${launched.xeniaDiagnostics.lastStartupDetail}")
                appendLine("aliveAfterModuleLoadSeconds=${launched.xeniaDiagnostics.aliveAfterModuleLoadSeconds}")
                appendLine("cacheBackendStatus=${launched.xeniaDiagnostics.cacheBackendStatus}")
                appendLine("frameStreamStatus=${launched.xeniaDiagnostics.frameStreamStatus}")
                appendLine("framebufferPath=$framebufferPath")
                appendLine("lastAction=${launched.lastAction}")
                appendLine("guestLog:")
                appendLine(launched.latestLogs.guestLog)
                appendLine("fexLog:")
                appendLine(launched.latestLogs.fexLog)
                appendLine("appLog:")
                appendLine(launched.latestLogs.appLog)
            },
        ).that(stage).isEqualTo(requiredStage.name.lowercase())

        if (!useHeadlessOnly) {
            assertThat(framebufferPath.toFile().exists()).isTrue()
            val header = XeniaFramebufferCodec.readHeader(framebufferPath)
            assertThat(header.width).isGreaterThan(0)
            assertThat(header.height).isGreaterThan(0)
            assertThat(header.frameIndex).isAtLeast(2L)
            val frame = XeniaFramebufferCodec.readFrame(framebufferPath)
            assertWithMessage(
                buildString {
                    appendLine("Expected visible Dante framebuffer content, but the exported frame was fully black.")
                    appendLine("path=$resolvedIsoPath")
                    appendLine("stage=$stage")
                    appendLine("frameIndex=${header.frameIndex}")
                    appendLine("size=${header.width}x${header.height}")
                    appendLine("detail=${launched.xeniaDiagnostics.lastStartupDetail}")
                },
            ).that(countNonBlackPixels(frame.rgbaBytes)).isGreaterThan(0)
        }

        if (launched.xeniaDiagnostics.titleMetadataSeen) {
            val refreshed = manager.refreshLibrary()
            val launchedEntry = refreshed.gameLibraryEntries.first { it.id == entry.id }
            assertThat(launchedEntry.lastKnownTitleName).isNotNull()
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
        val documentId = "$volumeId:$relative"
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
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

    private fun countNonBlackPixels(rgbaBytes: ByteArray): Int {
        var nonBlackPixels = 0
        var index = 0
        while (index + 3 < rgbaBytes.size) {
            val red = rgbaBytes[index].toInt() and 0xFF
            val green = rgbaBytes[index + 1].toInt() and 0xFF
            val blue = rgbaBytes[index + 2].toInt() and 0xFF
            if (red != 0 || green != 0 || blue != 0) {
                nonBlackPixels += 1
            }
            index += 4
        }
        return nonBlackPixels
    }
}
