package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XeniaMetadataCodecTest {
    @Test
    fun `source lock codec decodes pinned bring-up source`() {
        val raw = """
            {
              "sourceUrl": "https://github.com/xenia-canary/xenia-canary.git",
              "sourceRef": "canary_experimental",
              "sourceRevision": "c50b036178108f87cb0acaf3691a7c3caf07820f",
              "buildProfile": "linux-x64-release",
              "patchSetId": "phase5a-framebuffer-polling-v11"
            }
        """.trimIndent()

        val lock = XeniaSourceLockCodec.decode(raw)

        assertThat(lock.sourceUrl).isEqualTo("https://github.com/xenia-canary/xenia-canary.git")
        assertThat(lock.sourceRef).isEqualTo("canary_experimental")
        assertThat(lock.sourceRevision).isEqualTo("c50b036178108f87cb0acaf3691a7c3caf07820f")
        assertThat(lock.buildProfile).isEqualTo("linux-x64-release")
        assertThat(lock.patchSetId).isEqualTo("phase5a-framebuffer-polling-v11")
    }

    @Test
    fun `build metadata codec decodes runtime library closure`() {
        val raw = """
            {
              "sourceUrl": "https://github.com/xenia-canary/xenia-canary.git",
              "sourceRef": "canary_experimental",
              "sourceRevision": "c50b036178108f87cb0acaf3691a7c3caf07820f",
              "buildProfile": "linux-x64-release",
              "patchSetId": "phase4-build-v1",
              "executableName": "xenia-canary",
              "executableSha256": "abc123",
              "runtimeLibraries": [
                {
                  "soname": "libgtk-3.so.0",
                  "sourcePath": "/usr/lib/x86_64-linux-gnu/libgtk-3.so.0",
                  "installPath": "rootfs/usr/lib/x86_64-linux-gnu/libgtk-3.so.0",
                  "packageName": "libgtk-3-0",
                  "packageVersion": "3.24.41-4ubuntu1"
                }
              ],
              "requiredPackages": [
                {
                  "packageName": "libgtk-3-0",
                  "packageVersion": "3.24.41-4ubuntu1",
                  "archiveName": "libgtk-3-0_3.24.41-4ubuntu1_amd64.deb",
                  "archiveSha256": "def456"
                }
              ]
            }
        """.trimIndent()

        val metadata = XeniaBuildMetadataCodec.decode(raw)

        assertThat(metadata.patchSetId).isEqualTo("phase4-build-v1")
        assertThat(metadata.runtimeLibraries).hasSize(1)
        assertThat(metadata.runtimeLibraries.single().soname).isEqualTo("libgtk-3.so.0")
        assertThat(metadata.requiredPackages.single().packageName).isEqualTo("libgtk-3-0")
    }
}
