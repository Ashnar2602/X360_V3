package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import org.junit.Test

class RuntimeInstallerTest {
    @Test
    fun `install is idempotent when manifest is unchanged`() {
        val baseDir = Files.createTempDirectory("runtime-installer-idempotent")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = manifestFor(
            installPath = "payload/config/runtime.env",
            assetPath = "runtime-payload/files/payload/config/runtime.env",
            content = "MODE=mock\n",
        )
        val source = FakeRuntimeAssetSource(
            mapOf("runtime-payload/files/payload/config/runtime.env" to "MODE=mock\n".toByteArray()),
        )

        val first = installer.install(manifest, source, RuntimePhase.BOOTSTRAP)
        val markerTime = installer.runtimeDirectories().installMarker.getLastModifiedTime()
        Thread.sleep(20)
        val second = installer.install(manifest, source, RuntimePhase.BOOTSTRAP)

        assertThat(first).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(second).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(installer.runtimeDirectories().installMarker.getLastModifiedTime()).isEqualTo(markerTime)
    }

    @Test
    fun `inspect reports checksum mismatch after installed file is corrupted`() {
        val baseDir = Files.createTempDirectory("runtime-installer-checksum")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = manifestFor(
            installPath = "payload/config/runtime.env",
            assetPath = "runtime-payload/files/payload/config/runtime.env",
            content = "MODE=mock\n",
        )
        val source = FakeRuntimeAssetSource(
            mapOf("runtime-payload/files/payload/config/runtime.env" to "MODE=mock\n".toByteArray()),
        )

        installer.install(manifest, source, RuntimePhase.BOOTSTRAP)
        installer.runtimeDirectories().payloadConfig.resolve("runtime.env").toFile().writeText("MODE=corrupt\n")

        val state = installer.inspect(manifest, RuntimePhase.BOOTSTRAP)

        assertThat(state).isInstanceOf(RuntimeInstallState.Invalid::class.java)
        val invalid = state as RuntimeInstallState.Invalid
        assertThat(invalid.issue).isInstanceOf(RuntimeInstallIssue.ChecksumMismatch::class.java)
    }

    @Test
    fun `inspect reports manifest fingerprint mismatch when manifest changes`() {
        val baseDir = Files.createTempDirectory("runtime-installer-manifest")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val originalManifest = manifestFor(
            installPath = "payload/config/runtime.env",
            assetPath = "runtime-payload/files/payload/config/runtime.env",
            content = "MODE=mock\n",
        )
        val updatedManifest = originalManifest.copy(
            generatedBy = "unit-test-updated",
        )
        val source = FakeRuntimeAssetSource(
            mapOf("runtime-payload/files/payload/config/runtime.env" to "MODE=mock\n".toByteArray()),
        )

        installer.install(originalManifest, source, RuntimePhase.BOOTSTRAP)

        val state = installer.inspect(updatedManifest, RuntimePhase.BOOTSTRAP)

        assertThat(state).isInstanceOf(RuntimeInstallState.Invalid::class.java)
        val invalid = state as RuntimeInstallState.Invalid
        assertThat(invalid.issue).isInstanceOf(RuntimeInstallIssue.ManifestFingerprintMismatch::class.java)
    }

    @Test
    fun `install rewrites marker when manifest fingerprint changes`() {
        val baseDir = Files.createTempDirectory("runtime-installer-manifest-reinstall")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val originalManifest = manifestFor(
            installPath = "payload/config/runtime.env",
            assetPath = "runtime-payload/files/payload/config/runtime.env",
            content = "MODE=mock\n",
        )
        val updatedManifest = originalManifest.copy(
            profile = "bootstrap-mock-v2",
        )
        val source = FakeRuntimeAssetSource(
            mapOf("runtime-payload/files/payload/config/runtime.env" to "MODE=mock\n".toByteArray()),
        )

        installer.install(originalManifest, source, RuntimePhase.BOOTSTRAP)
        val originalMarkerTime = installer.runtimeDirectories().installMarker.getLastModifiedTime()
        Thread.sleep(20)

        val reinstall = installer.install(updatedManifest, source, RuntimePhase.BOOTSTRAP)

        assertThat(reinstall).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(installer.runtimeDirectories().installMarker.getLastModifiedTime()).isGreaterThan(originalMarkerTime)
    }

    @Test
    fun `install rejects path traversal`() {
        val baseDir = Files.createTempDirectory("runtime-installer-path")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = RuntimeManifest(
            version = 1,
            profile = "bootstrap-mock",
            generatedBy = "unit-test",
            assets = listOf(
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/config/runtime.env",
                    installPath = "../outside.txt",
                    checksumSha256 = "abcd",
                ),
            ),
        )
        val source = FakeRuntimeAssetSource(
            mapOf("runtime-payload/files/payload/config/runtime.env" to "MODE=mock\n".toByteArray()),
        )

        val state = installer.install(manifest, source, RuntimePhase.BOOTSTRAP)

        assertThat(state).isInstanceOf(RuntimeInstallState.Invalid::class.java)
        val invalid = state as RuntimeInstallState.Invalid
        assertThat(invalid.issue).isEqualTo(RuntimeInstallIssue.InvalidInstallPath("../outside.txt"))
    }

    @Test
    fun `phase filtering allows bootstrap install before fex baseline`() {
        val baseDir = Files.createTempDirectory("runtime-installer-phase")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = RuntimeManifest(
            version = 1,
            profile = "fex-baseline",
            generatedBy = "unit-test",
            assets = listOf(
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/config/runtime.env",
                    installPath = "payload/config/runtime.env",
                    checksumSha256 = sha256("MODE=bootstrap\n".toByteArray()),
                    minPhase = RuntimePhase.BOOTSTRAP,
                ),
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/guest-tests/bin/hello_x86_64",
                    installPath = "payload/guest-tests/bin/hello_x86_64",
                    executable = true,
                    checksumSha256 = sha256("HELLO\n".toByteArray()),
                    minPhase = RuntimePhase.FEX_BASELINE,
                ),
            ),
        )
        val source = FakeRuntimeAssetSource(
            mapOf(
                "runtime-payload/files/payload/config/runtime.env" to "MODE=bootstrap\n".toByteArray(),
                "runtime-payload/files/payload/guest-tests/bin/hello_x86_64" to "HELLO\n".toByteArray(),
            ),
        )

        val bootstrapInstall = installer.install(manifest, source, RuntimePhase.BOOTSTRAP)
        val bootstrapState = installer.inspect(manifest, RuntimePhase.BOOTSTRAP)
        val fexStateBeforeUpgrade = installer.inspect(manifest, RuntimePhase.FEX_BASELINE)

        assertThat(bootstrapInstall).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(bootstrapState).isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(fexStateBeforeUpgrade).isEqualTo(RuntimeInstallState.NotInstalled)
    }

    @Test
    fun `inspect bootstrap stays installed after higher phase install`() {
        val baseDir = Files.createTempDirectory("runtime-installer-higher-phase")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = RuntimeManifest(
            version = 1,
            profile = "fex-baseline",
            generatedBy = "unit-test",
            assets = listOf(
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/config/runtime.env",
                    installPath = "payload/config/runtime.env",
                    checksumSha256 = sha256("MODE=bootstrap\n".toByteArray()),
                    minPhase = RuntimePhase.BOOTSTRAP,
                ),
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/guest-tests/bin/hello_x86_64",
                    installPath = "payload/guest-tests/bin/hello_x86_64",
                    executable = true,
                    checksumSha256 = sha256("HELLO\n".toByteArray()),
                    minPhase = RuntimePhase.FEX_BASELINE,
                ),
            ),
        )
        val source = FakeRuntimeAssetSource(
            mapOf(
                "runtime-payload/files/payload/config/runtime.env" to "MODE=bootstrap\n".toByteArray(),
                "runtime-payload/files/payload/guest-tests/bin/hello_x86_64" to "HELLO\n".toByteArray(),
            ),
        )

        installer.install(manifest, source, RuntimePhase.FEX_BASELINE)

        assertThat(installer.inspect(manifest, RuntimePhase.BOOTSTRAP))
            .isInstanceOf(RuntimeInstallState.Installed::class.java)
    }

    @Test
    fun `phase filtering keeps vulkan baseline gated behind fex baseline`() {
        val baseDir = Files.createTempDirectory("runtime-installer-vulkan-phase")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = RuntimeManifest(
            version = 1,
            profile = "vulkan-baseline",
            generatedBy = "unit-test",
            assets = listOf(
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/guest-tests/bin/hello_x86_64",
                    installPath = "payload/guest-tests/bin/hello_x86_64",
                    executable = true,
                    checksumSha256 = sha256("HELLO\n".toByteArray()),
                    minPhase = RuntimePhase.FEX_BASELINE,
                ),
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/guest-tests/bin/vulkan_probe_x86_64",
                    installPath = "payload/guest-tests/bin/vulkan_probe_x86_64",
                    executable = true,
                    checksumSha256 = sha256("PROBE\n".toByteArray()),
                    minPhase = RuntimePhase.VULKAN_BASELINE,
                ),
            ),
        )
        val source = FakeRuntimeAssetSource(
            mapOf(
                "runtime-payload/files/payload/guest-tests/bin/hello_x86_64" to "HELLO\n".toByteArray(),
                "runtime-payload/files/payload/guest-tests/bin/vulkan_probe_x86_64" to "PROBE\n".toByteArray(),
            ),
        )

        installer.install(manifest, source, RuntimePhase.FEX_BASELINE)

        assertThat(installer.inspect(manifest, RuntimePhase.FEX_BASELINE))
            .isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(installer.inspect(manifest, RuntimePhase.VULKAN_BASELINE))
            .isEqualTo(RuntimeInstallState.NotInstalled)
    }

    @Test
    fun `phase filtering keeps turnip baseline gated behind vulkan baseline`() {
        val baseDir = Files.createTempDirectory("runtime-installer-turnip-phase")
        val installer = RuntimeInstaller(RuntimeDirectories(baseDir))
        val manifest = RuntimeManifest(
            version = 1,
            profile = "turnip-baseline",
            generatedBy = "unit-test",
            assets = listOf(
                RuntimeAsset(
                    assetPath = "runtime-payload/files/payload/guest-tests/bin/vulkan_probe_x86_64",
                    installPath = "payload/guest-tests/bin/vulkan_probe_x86_64",
                    executable = true,
                    checksumSha256 = sha256("PROBE\n".toByteArray()),
                    minPhase = RuntimePhase.VULKAN_BASELINE,
                ),
                RuntimeAsset(
                    assetPath = "runtime-payload/files/rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so",
                    installPath = "rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so",
                    executable = false,
                    checksumSha256 = sha256("TURNIP\n".toByteArray()),
                    minPhase = RuntimePhase.TURNIP_BASELINE,
                ),
            ),
        )
        val source = FakeRuntimeAssetSource(
            mapOf(
                "runtime-payload/files/payload/guest-tests/bin/vulkan_probe_x86_64" to "PROBE\n".toByteArray(),
                "runtime-payload/files/rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so" to "TURNIP\n".toByteArray(),
            ),
        )

        installer.install(manifest, source, RuntimePhase.VULKAN_BASELINE)

        assertThat(installer.inspect(manifest, RuntimePhase.VULKAN_BASELINE))
            .isInstanceOf(RuntimeInstallState.Installed::class.java)
        assertThat(installer.inspect(manifest, RuntimePhase.TURNIP_BASELINE))
            .isEqualTo(RuntimeInstallState.NotInstalled)
    }

    private fun manifestFor(
        installPath: String,
        assetPath: String,
        content: String,
    ): RuntimeManifest {
        val checksum = sha256(content.toByteArray())
        return RuntimeManifest(
            version = 1,
            profile = "bootstrap-mock",
            generatedBy = "unit-test",
            assets = listOf(
                RuntimeAsset(
                    assetPath = assetPath,
                    installPath = installPath,
                    checksumSha256 = checksum,
                    executable = installPath.startsWith("payload/bin/"),
                ),
            ),
        )
    }

    private fun sha256(raw: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(raw)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private class FakeRuntimeAssetSource(
    private val assets: Map<String, ByteArray>,
) : RuntimeAssetSource {
    override fun open(assetPath: String): ByteArrayInputStream? {
        return assets[assetPath]?.let(::ByteArrayInputStream)
    }
}
