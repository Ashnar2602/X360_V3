import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class XeniaBuildWorkspaceTest {
    @Test
    fun `workspace key is stable for same inputs`() {
        val tempDir = Files.createTempDirectory("xenia-workspace-key")
        val patch = tempDir.resolve("0001-test.patch")
        Files.writeString(patch, "diff --git a/a b/a\n")
        val lock = XeniaSourceBuildLock(
            sourceUrl = "https://github.com/xenia-canary/xenia-canary.git",
            sourceRef = "canary_experimental",
            sourceRevision = "abc123",
            buildProfile = "linux-x64-release",
            patchSetId = "phase4",
        )

        val first = computeXeniaWorkspaceKey(lock, listOf(patch), null)
        val second = computeXeniaWorkspaceKey(lock, listOf(patch), null)

        assertEquals(first, second)
    }

    @Test
    fun `workspace key changes when patch contents change`() {
        val tempDir = Files.createTempDirectory("xenia-workspace-key-patch")
        val patch = tempDir.resolve("0001-test.patch")
        val lock = XeniaSourceBuildLock(
            sourceUrl = "https://github.com/xenia-canary/xenia-canary.git",
            sourceRef = "canary_experimental",
            sourceRevision = "abc123",
            buildProfile = "linux-x64-release",
            patchSetId = "phase4",
        )
        Files.writeString(patch, "diff --git a/a b/a\n-old\n+new\n")
        val before = computeXeniaWorkspaceKey(lock, listOf(patch), "/sdk/1")

        Files.writeString(patch, "diff --git a/a b/a\n-old\n+newer\n")
        val after = computeXeniaWorkspaceKey(lock, listOf(patch), "/sdk/1")

        assertNotEquals(before, after)
    }

    @Test
    fun `setup signature changes with build profile or sdk`() {
        val baseLock = XeniaSourceBuildLock(
            sourceUrl = "https://github.com/xenia-canary/xenia-canary.git",
            sourceRef = "canary_experimental",
            sourceRevision = "abc123",
            buildProfile = "linux-x64-release",
            patchSetId = "phase4",
        )

        val release = computeXeniaSetupSignature(baseLock, null)
        val debug = computeXeniaSetupSignature(baseLock.copy(buildProfile = "linux-x64-debug"), null)
        val customSdk = computeXeniaSetupSignature(baseLock, "/opt/vulkan-sdk")

        assertNotEquals(release, debug)
        assertNotEquals(release, customSdk)
    }
}
