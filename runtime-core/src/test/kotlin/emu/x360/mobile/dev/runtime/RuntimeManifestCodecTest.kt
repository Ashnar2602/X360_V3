package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuntimeManifestCodecTest {
    @Test
    fun `decode runtime manifest with bootstrap phase`() {
        val raw = """
            {
              "version": 1,
              "profile": "bootstrap-mock",
              "generatedBy": "unit-test",
              "assets": [
                {
                  "assetPath": "runtime-payload/files/payload/config/runtime.env",
                  "installPath": "payload/config/runtime.env",
                  "executable": false,
                  "checksumSha256": "abcd",
                  "minPhase": "bootstrap"
                }
              ]
            }
        """.trimIndent()

        val manifest = RuntimeManifestCodec.decode(raw)

        assertThat(manifest.version).isEqualTo(1)
        assertThat(manifest.profile).isEqualTo("bootstrap-mock")
        assertThat(manifest.assets.single().minPhase).isEqualTo(RuntimePhase.BOOTSTRAP)
    }

    @Test
    fun `decode fex build metadata`() {
        val raw = """
            {
              "fexCommit": "49a37c7",
              "patchSetId": "android-baseline-v1",
              "abi": "arm64-v8a",
              "artifacts": [
                {
                  "name": "libFEXLoader.so",
                  "sha256": "abcd",
                  "neededLibraries": ["libc.so"]
                }
              ]
            }
        """.trimIndent()

        val metadata = FexBuildMetadataCodec.decode(raw)

        assertThat(metadata.fexCommit).isEqualTo("49a37c7")
        assertThat(metadata.patchSetId).isEqualTo("android-baseline-v1")
        assertThat(metadata.artifacts.single().name).isEqualTo("libFEXLoader.so")
    }

    @Test
    fun `decode runtime manifest with vulkan baseline phase`() {
        val raw = """
            {
              "version": 1,
              "profile": "vulkan-baseline",
              "generatedBy": "unit-test",
              "assets": [
                {
                  "assetPath": "runtime-payload/files/payload/guest-tests/bin/vulkan_probe_x86_64",
                  "installPath": "payload/guest-tests/bin/vulkan_probe_x86_64",
                  "executable": true,
                  "checksumSha256": "abcd",
                  "minPhase": "vulkan-baseline"
                }
              ]
            }
        """.trimIndent()

        val manifest = RuntimeManifestCodec.decode(raw)

        assertThat(manifest.profile).isEqualTo("vulkan-baseline")
        assertThat(manifest.assets.single().minPhase).isEqualTo(RuntimePhase.VULKAN_BASELINE)
    }

    @Test
    fun `decode guest runtime metadata`() {
        val raw = """
            {
              "distribution": "Ubuntu",
              "release": "24.04",
              "architecture": "amd64",
              "profile": "ubuntu-24.04-lvp-baseline",
              "activeIcd": "lavapipe",
              "packages": [
                {
                  "name": "libc6",
                  "version": "2.39-0ubuntu8",
                  "url": "http://archive.ubuntu.com/example/libc6.deb",
                  "sha256": "abcd"
                }
              ]
            }
        """.trimIndent()

        val metadata = GuestRuntimeMetadataCodec.decode(raw)

        assertThat(metadata.release).isEqualTo("24.04")
        assertThat(metadata.activeIcd).isEqualTo("lavapipe")
        assertThat(metadata.packages.single().name).isEqualTo("libc6")
    }
}
