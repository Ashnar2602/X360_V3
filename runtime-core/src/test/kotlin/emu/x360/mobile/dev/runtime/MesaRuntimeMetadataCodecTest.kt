package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MesaRuntimeMetadataCodecTest {
    @Test
    fun `codec decodes dual mesa metadata`() {
        val raw = """
            {
              "profile": "turnip-baseline",
              "bundles": [
                {
                  "branch": "mesa25",
                  "mesaVersion": "25.3-branch@7f1ccad77883",
                  "sourceKind": "git",
                  "sourceUrl": "https://gitlab.freedesktop.org/mesa/mesa.git",
                  "sourceSha256": null,
                  "sourceRef": "25.3",
                  "sourceRevision": "7f1ccad77883be68e7750ab30b99b16df02e679d",
                  "installProfile": "turnip-kgsl-minimal",
                  "patchSetId": "none",
                  "appliedPatches": [],
                  "libRoot": "/opt/x360-v3/mesa/mesa25/lib",
                  "icdPath": "/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json",
                  "driverLibraryName": "libvulkan_freedreno.so",
                  "bundledDependencies": ["libzstd.so.1", "libexpat.so.1"]
                },
                {
                  "branch": "mesa26",
                  "mesaVersion": "26.x-main@44669146808b",
                  "sourceKind": "git",
                  "sourceUrl": "https://gitlab.freedesktop.org/mesa/mesa.git",
                  "sourceSha256": null,
                  "sourceRef": "main",
                  "sourceRevision": "44669146808b74024b9befeb59266db18ae5e165",
                  "installProfile": "turnip-kgsl-minimal",
                  "patchSetId": "ubwc5-a830-v1",
                  "appliedPatches": ["0001-tu-kgsl-add-ubwc-5-6-fallback-to-fd-dev-info.patch"],
                  "libRoot": "/opt/x360-v3/mesa/mesa26/lib",
                  "icdPath": "/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json",
                  "driverLibraryName": "libvulkan_freedreno.so",
                  "bundledDependencies": ["libz.so.1"]
                }
              ]
            }
        """.trimIndent()

        val metadata = MesaRuntimeMetadataCodec.decode(raw)

        assertThat(metadata.profile).isEqualTo("turnip-baseline")
        assertThat(metadata.bundles).hasSize(2)
        assertThat(metadata.bundles[0].branch).isEqualTo("mesa25")
        assertThat(metadata.bundles[1].mesaVersion).isEqualTo("26.x-main@44669146808b")
        assertThat(metadata.bundles[1].sourceKind).isEqualTo("git")
        assertThat(metadata.bundles[1].patchSetId).isEqualTo("ubwc5-a830-v1")
        assertThat(metadata.bundles[1].appliedPatches)
            .containsExactly("0001-tu-kgsl-add-ubwc-5-6-fallback-to-fd-dev-info.patch")
        assertThat(metadata.bundles[1].icdPath).isEqualTo("/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json")
    }
}
