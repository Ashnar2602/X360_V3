import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

abstract class GenerateVulkanGuestAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val guestRuntimeLockManifest: RegularFileProperty

    @get:InputFile
    abstract val dynamicHelloFixtureBinary: RegularFileProperty

    @get:InputFile
    abstract val vulkanProbeFixtureBinary: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDir.get().asFile.toPath()
        deleteRecursively(output)
        output.createDirectories()

        val assembler = UbuntuGuestRuntimeAssembler()
        val lock = UbuntuGuestRuntimeLockCodec.decode(guestRuntimeLockManifest.get().asFile.toPath().readText())

        val downloadsDir = temporaryDir.toPath().resolve("downloads")
        val extractionDir = temporaryDir.toPath().resolve("extracted")
        val archives = assembler.preparePackageArchives(lock, downloadsDir)
        val extractedPackages = assembler.extractPackages(archives, extractionDir)
        assembler.copySelectedFiles(lock, extractedPackages, output)

        val guestTestBinDir = output.resolve("payload").resolve("guest-tests").resolve("bin")
        guestTestBinDir.createDirectories()

        val dynamicHelloDestination = guestTestBinDir.resolve("dyn_hello_x86_64")
        dynamicHelloFixtureBinary.get().asFile.toPath().copyTo(dynamicHelloDestination, overwrite = true)
        dynamicHelloDestination.toFile().setExecutable(true, false)

        val vulkanProbeDestination = guestTestBinDir.resolve("vulkan_probe_x86_64")
        vulkanProbeFixtureBinary.get().asFile.toPath().copyTo(vulkanProbeDestination, overwrite = true)
        vulkanProbeDestination.toFile().setExecutable(true, false)

        val payloadConfigDir = output.resolve("payload").resolve("config")
        payloadConfigDir.createDirectories()
        guestRuntimeLockManifest.get().asFile.toPath().copyTo(
            payloadConfigDir.resolve("ubuntu-24.04-amd64-lvp.lock.json"),
            overwrite = true,
        )
        assembler.writeGuestRuntimeMetadata(
            lock = lock,
            outputPath = payloadConfigDir.resolve("guest-runtime-metadata.json"),
        )

        writeUtf8(
            output.resolve("rootfs").resolve("usr").resolve("share").resolve("x360-v3").resolve("vulkan-baseline.txt"),
            "Phase 3A guest runtime generated from pinned Ubuntu 24.04 amd64 packages\n",
        )
    }
}
