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
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
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
        manager.setMesaOverride(MesaRuntimeBranch.AUTO)
        val imported = withShellIdentity {
            manager.importIso(launchUri)
        }
        val entry = imported.gameLibraryEntries.firstOrNull { it.displayName.lowercase().contains("dante") }
            ?: imported.gameLibraryEntries.first()

        val launched = withShellIdentity {
            manager.launchImportedTitle(entry.id)
        }
        val stage = launched.xeniaDiagnostics.lastStartupStage

        assertWithMessage(
            buildString {
                appendLine("Expected Dante's Inferno to reach steady-state headless execution.")
                appendLine("path=$resolvedIsoPath")
                appendLine("stage=$stage")
                appendLine("detail=${launched.xeniaDiagnostics.lastStartupDetail}")
                appendLine("aliveAfterModuleLoadSeconds=${launched.xeniaDiagnostics.aliveAfterModuleLoadSeconds}")
                appendLine("cacheBackendStatus=${launched.xeniaDiagnostics.cacheBackendStatus}")
                appendLine("lastAction=${launched.lastAction}")
                appendLine("guestLog:")
                appendLine(launched.latestLogs.guestLog)
                appendLine("fexLog:")
                appendLine(launched.latestLogs.fexLog)
                appendLine("appLog:")
                appendLine(launched.latestLogs.appLog)
            },
        ).that(stage).isEqualTo("title_running_headless")

        if (launched.xeniaDiagnostics.titleMetadataSeen) {
            val refreshed = manager.refreshLibrary()
            val launchedEntry = refreshed.gameLibraryEntries.first { it.id == entry.id }
            assertThat(launchedEntry.lastKnownTitleName).isNotNull()
        }
    }

    private fun buildDocumentUri(path: Path): Uri? {
        val normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        val primaryPrefix = "/storage/emulated/0/"
        if (!normalized.startsWith(primaryPrefix)) {
            return null
        }
        val relative = normalized.removePrefix(primaryPrefix)
        val documentId = "primary:$relative"
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
}
