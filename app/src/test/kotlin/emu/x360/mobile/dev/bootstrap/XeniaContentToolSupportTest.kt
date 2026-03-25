package emu.x360.mobile.dev.bootstrap
import com.google.common.truth.Truth.assertThat
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.TitleContentInstallStatus
import java.nio.file.Path
import org.junit.Test

class XeniaContentToolSupportTest {
    private val directories = RuntimeDirectories(Path.of("/data/user/0/emu.x360.mobile.dev/files"))

    @Test
    fun `content suffix preserves known xcontent extensions`() {
        assertThat(contentPackageSuffix("patch.con")).isEqualTo(".con")
        assertThat(contentPackageSuffix("patch.live")).isEqualTo(".live")
        assertThat(contentPackageSuffix("patch.pirs")).isEqualTo(".pirs")
        assertThat(contentPackageSuffix("patch.bin")).isEqualTo(".pkg")
    }

    @Test
    fun `content tool result maps installed paths under writable content root`() {
        val entry = XeniaContentToolResult(
            displayName = "Dante TU2",
            titleId = "454108CF",
            contentType = "00000002",
            contentTypeLabel = "Marketplace Content",
            packageSignature = "LIVE",
            xuid = "0000000000000000",
            installedDataPath = "0000000000000000/454108CF/00000002/DanteTU2",
            installedHeaderPath = "0000000000000000/454108CF/Headers/00000002/DanteTU2",
            status = "installed",
            summary = "Content package installed",
        ).toTitleContentEntry(
            id = "content-1",
            libraryEntryId = "dante",
            uriString = "content://test/dante-tu2",
            directories = directories,
            fallbackDisplayName = "fallback",
        )

        assertThat(entry.installStatus).isEqualTo(TitleContentInstallStatus.INSTALLED)
        assertThat(entry.installedDataPath)
            .isEqualTo(
                directories.xeniaWritableContentRoot
                    .resolve("0000000000000000/454108CF/00000002/DanteTU2")
                    .normalize()
                    .toString(),
            )
        assertThat(entry.installedHeaderPath)
            .isEqualTo(
                directories.xeniaWritableContentRoot
                    .resolve("0000000000000000/454108CF/Headers/00000002/DanteTU2")
                    .normalize()
                    .toString(),
            )
    }
}
