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
              "sourceRevision": "553aedebb59340d3106cd979ca7d09cc8e3bd98e",
              "buildProfile": "linux-x64-release",
              "patchSetId": "phase11a-upstreamfirst-v1"
            }
        """.trimIndent()

        val lock = XeniaSourceLockCodec.decode(raw)

        assertThat(lock.sourceUrl).isEqualTo("https://github.com/xenia-canary/xenia-canary.git")
        assertThat(lock.sourceRef).isEqualTo("canary_experimental")
        assertThat(lock.sourceRevision).isEqualTo("553aedebb59340d3106cd979ca7d09cc8e3bd98e")
        assertThat(lock.buildProfile).isEqualTo("linux-x64-release")
        assertThat(lock.patchSetId).isEqualTo("phase11a-upstreamfirst-v1")
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

    @Test
    fun `game patches lock codec decodes official bundled database`() {
        val raw = """
            {
              "sourceUrl": "https://github.com/xenia-canary/game-patches.git",
              "sourceRef": "main",
              "sourceRevision": "4814fb128ae2ed060569840708196542823547de"
            }
        """.trimIndent()

        val lock = XeniaGamePatchesLockCodec.decode(raw)

        assertThat(lock.sourceUrl).isEqualTo("https://github.com/xenia-canary/game-patches.git")
        assertThat(lock.sourceRef).isEqualTo("main")
        assertThat(lock.sourceRevision).isEqualTo("4814fb128ae2ed060569840708196542823547de")
    }

    @Test
    fun `title content database codec decodes installed package`() {
        val raw = """
            {
              "version": 1,
              "entries": [
                {
                  "id": "content-1",
                  "libraryEntryId": "dante",
                  "uriString": "content://test/dante-tu2",
                  "displayName": "Dante TU2",
                  "titleId": "454108CF",
                  "contentType": "00000002",
                  "contentTypeLabel": "Marketplace Content",
                  "packageSignature": "LIVE",
                  "xuid": "0000000000000000",
                  "installStatus": "installed",
                  "installedDataPath": "/tmp/x360-v3/xenia/content/0000000000000000/454108CF/00000002/DanteTU2",
                  "installedHeaderPath": "/tmp/x360-v3/xenia/content/0000000000000000/454108CF/Headers/00000002/DanteTU2",
                  "lastInstallSummary": "Content package installed"
                }
              ]
            }
        """.trimIndent()

        val database = TitleContentDatabaseCodec.decode(raw)

        assertThat(database.entries).hasSize(1)
        assertThat(database.entries.single().titleId).isEqualTo("454108CF")
        assertThat(database.entries.single().installStatus).isEqualTo(TitleContentInstallStatus.INSTALLED)
    }
}
