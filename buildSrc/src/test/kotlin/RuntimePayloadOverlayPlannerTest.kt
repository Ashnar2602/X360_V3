import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RuntimePayloadOverlayPlannerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local runtime drop overrides mock payload when enabled`() {
        val mockDir = tempDir.resolve("mock").apply { createDirectories() }
        val localDir = tempDir.resolve("local").apply { createDirectories() }
        val outputDir = tempDir.resolve("out")

        mockDir.resolve("payload/config/runtime.env").apply {
            parent.createDirectories()
            writeText("MODE=mock\n")
        }
        localDir.resolve("payload/config/runtime.env").apply {
            parent.createDirectories()
            writeText("MODE=local\n")
        }

        val manifest = RuntimePayloadOverlayPlanner().stage(
            mockRuntimeDir = mockDir,
            generatedFexRuntimeDir = null,
            generatedVulkanRuntimeDir = null,
            generatedTurnipRuntimeDir = null,
            localRuntimeDropDir = localDir,
            outputDir = outputDir,
            includeLocalDrop = true,
        )

        val mergedContent = outputDir.resolve("runtime-payload/files/payload/config/runtime.env").readText()
        assertEquals("MODE=local\n", mergedContent)
        assertTrue(manifest.assets.any { it.installPath == "payload/config/runtime.env" })
    }

    @Test
    fun `mock payload stays active when local runtime drop is disabled`() {
        val mockDir = tempDir.resolve("mock").apply { createDirectories() }
        val localDir = tempDir.resolve("local").apply { createDirectories() }
        val outputDir = tempDir.resolve("out")

        mockDir.resolve("payload/bin/stub_guest.sh").apply {
            parent.createDirectories()
            writeText("#!/bin/sh\necho mock\n")
        }
        localDir.resolve("payload/bin/stub_guest.sh").apply {
            parent.createDirectories()
            writeText("#!/bin/sh\necho local\n")
        }

        RuntimePayloadOverlayPlanner().stage(
            mockRuntimeDir = mockDir,
            generatedFexRuntimeDir = null,
            generatedVulkanRuntimeDir = null,
            generatedTurnipRuntimeDir = null,
            localRuntimeDropDir = localDir,
            outputDir = outputDir,
            includeLocalDrop = false,
        )

        val mergedContent = outputDir.resolve("runtime-payload/files/payload/bin/stub_guest.sh").readText()
        assertEquals("#!/bin/sh\necho mock\n", mergedContent)
    }

    @Test
    fun `manifest contains checksum and executable metadata`() {
        val mockDir = tempDir.resolve("mock").apply { createDirectories() }
        val outputDir = tempDir.resolve("out")

        mockDir.resolve("payload/bin/stub_guest.sh").apply {
            parent.createDirectories()
            writeText("#!/bin/sh\necho mock\n")
        }
        mockDir.resolve("rootfs/usr/share/x360-v3/mock-runtime.txt").apply {
            parent.createDirectories()
            writeText("mock runtime rootfs marker\n")
        }

        val manifest = RuntimePayloadOverlayPlanner().stage(
            mockRuntimeDir = mockDir,
            generatedFexRuntimeDir = null,
            generatedVulkanRuntimeDir = null,
            generatedTurnipRuntimeDir = null,
            localRuntimeDropDir = null,
            outputDir = outputDir,
            includeLocalDrop = false,
        )
        val manifestJson = outputDir.resolve("runtime-payload/runtime-manifest.json").readText()

        val binEntry = manifest.assets.first { it.installPath == "payload/bin/stub_guest.sh" }
        val rootfsEntry = manifest.assets.first { it.installPath == "rootfs/usr/share/x360-v3/mock-runtime.txt" }

        assertTrue(binEntry.executable)
        assertFalse(rootfsEntry.executable)
        assertTrue(binEntry.checksumSha256.isNotBlank())
        assertTrue(manifestJson.contains("\"checksumSha256\""))
    }

    @Test
    fun `generated fex layer marks guest assets as fex baseline`() {
        val mockDir = tempDir.resolve("mock").apply { createDirectories() }
        val fexDir = tempDir.resolve("fex").apply { createDirectories() }
        val outputDir = tempDir.resolve("out")

        mockDir.resolve("payload/config/runtime.env").apply {
            parent.createDirectories()
            writeText("MODE=bootstrap\n")
        }
        fexDir.resolve("payload/guest-tests/bin/hello_x86_64").apply {
            parent.createDirectories()
            writeText("hello\n")
        }

        val manifest = RuntimePayloadOverlayPlanner().stage(
            mockRuntimeDir = mockDir,
            generatedFexRuntimeDir = fexDir,
            generatedVulkanRuntimeDir = null,
            generatedTurnipRuntimeDir = null,
            localRuntimeDropDir = null,
            outputDir = outputDir,
            includeLocalDrop = false,
        )

        val helloEntry = manifest.assets.first { it.installPath == "payload/guest-tests/bin/hello_x86_64" }
        assertEquals("fex-baseline", helloEntry.minPhase)
        assertTrue(helloEntry.executable)
    }

    @Test
    fun `generated vulkan layer marks runtime assets as vulkan baseline`() {
        val mockDir = tempDir.resolve("mock").apply { createDirectories() }
        val fexDir = tempDir.resolve("fex").apply { createDirectories() }
        val vulkanDir = tempDir.resolve("vulkan").apply { createDirectories() }
        val outputDir = tempDir.resolve("out")

        mockDir.resolve("payload/config/runtime.env").apply {
            parent.createDirectories()
            writeText("MODE=bootstrap\n")
        }
        fexDir.resolve("payload/guest-tests/bin/hello_x86_64").apply {
            parent.createDirectories()
            writeText("hello\n")
        }
        vulkanDir.resolve("payload/guest-tests/bin/vulkan_probe_x86_64").apply {
            parent.createDirectories()
            writeText("probe\n")
        }

        val manifest = RuntimePayloadOverlayPlanner().stage(
            mockRuntimeDir = mockDir,
            generatedFexRuntimeDir = fexDir,
            generatedVulkanRuntimeDir = vulkanDir,
            generatedTurnipRuntimeDir = null,
            localRuntimeDropDir = null,
            outputDir = outputDir,
            includeLocalDrop = false,
        )

        val probeEntry = manifest.assets.first { it.installPath == "payload/guest-tests/bin/vulkan_probe_x86_64" }
        assertEquals("vulkan-baseline", probeEntry.minPhase)
        assertEquals("vulkan-baseline", manifest.profile)
    }
}
