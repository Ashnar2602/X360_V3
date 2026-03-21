package emu.x360.mobile.dev

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import java.io.File
import java.nio.file.Path
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

        val framebufferPath = context.filesDir.toPath().resolve("rootfs/tmp/xenia_fb")
        val scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, entry.id))
        try {
            val passed = waitForVisiblePlayerFrame(framebufferPath)
            assertWithMessage(
                buildString {
                    appendLine("Expected PlayerActivity to render non-black content.")
                    appendLine("showFpsCounter=$showFpsCounter")
                    appendLine("isoPath=$resolvedIsoPath")
                    appendLine("entryId=${entry.id}")
                    appendLine("framebufferExists=${framebufferPath.toFile().exists()}")
                    appendLine("framebufferSize=${framebufferPath.toFile().takeIf { it.exists() }?.length() ?: -1}")
                },
            ).that(passed).isTrue()
        } finally {
            scenario.onActivity { it.finish() }
            scenario.close()
        }
    }

    private fun waitForVisiblePlayerFrame(framebufferPath: Path): Boolean {
        repeat(20) {
            Thread.sleep(2_000L)
            val framebufferHasVisibleContent = runCatching {
                val frame = XeniaFramebufferCodec.readFrame(framebufferPath)
                countNonBlackPixels(frame.rgbaBytes, stepPixels = 24) > 0
            }.getOrDefault(false)
            if (!framebufferHasVisibleContent) {
                return@repeat
            }

            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot != null) {
                screenshot.useBitmap { bitmap ->
                    if (countNonBlackPixelsInCentralViewport(bitmap, stepPixels = 24) > 0) {
                        return true
                    }
                }
            }
        }
        return false
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

    private fun countNonBlackPixelsInCentralViewport(
        bitmap: Bitmap,
        stepPixels: Int,
    ): Int {
        val startX = bitmap.width / 5
        val endX = bitmap.width - startX
        val startY = bitmap.height / 4
        val endY = bitmap.height - (bitmap.height / 5)
        var nonBlackPixels = 0
        val step = max(1, stepPixels)
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val pixel = bitmap.getPixel(x, y)
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                if (red != 0 || green != 0 || blue != 0) {
                    nonBlackPixels += 1
                }
                x += step
            }
            y += step
        }
        return nonBlackPixels
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            recycle()
        }
    }
}
