import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class UbuntuGuestRuntimeAssemblerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `decode guest runtime lock manifest`() {
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
                  "sha256": "abcd",
                  "files": [
                    {
                      "source": "./usr/lib/x86_64-linux-gnu/libc.so.6",
                      "installPath": "rootfs/lib/x86_64-linux-gnu/libc.so.6"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val lock = UbuntuGuestRuntimeLockCodec.decode(raw)

        assertEquals("Ubuntu", lock.distribution)
        assertEquals("24.04", lock.release)
        assertEquals("lavapipe", lock.activeIcd)
        assertEquals("rootfs/lib/x86_64-linux-gnu/libc.so.6", lock.packages.single().files.single().installPath)
    }

    @Test
    fun `copy selected files filters package contents to declared slice`() {
        val extractedRoot = tempDir.resolve("pkg").apply { createDirectories() }
        val sourceDir = extractedRoot.resolve("usr/lib/x86_64-linux-gnu").apply { createDirectories() }
        sourceDir.resolve("libc.so.6").writeText("libc\n")
        sourceDir.resolve("libm.so.6").writeText("libm\n")

        val outputRoot = tempDir.resolve("out")
        val lock = UbuntuGuestRuntimeLock(
            distribution = "Ubuntu",
            release = "24.04",
            architecture = "amd64",
            profile = "ubuntu-24.04-lvp-baseline",
            activeIcd = "lavapipe",
            packages = listOf(
                UbuntuGuestRuntimePackage(
                    name = "libc6",
                    version = "2.39-0ubuntu8",
                    url = "http://archive.ubuntu.com/example/libc6.deb",
                    sha256 = "abcd",
                    files = listOf(
                        UbuntuGuestRuntimeFile(
                            source = "./usr/lib/x86_64-linux-gnu/libc.so.6",
                            installPath = "rootfs/lib/x86_64-linux-gnu/libc.so.6",
                        ),
                    ),
                ),
            ),
        )

        UbuntuGuestRuntimeAssembler().copySelectedFiles(
            lock = lock,
            extractedPackages = mapOf("libc6" to extractedRoot),
            outputRoot = outputRoot,
        )

        assertEquals("libc\n", outputRoot.resolve("rootfs/lib/x86_64-linux-gnu/libc.so.6").readText())
        assertTrue(!outputRoot.resolve("rootfs/lib/x86_64-linux-gnu/libm.so.6").toFile().exists())
    }
}
