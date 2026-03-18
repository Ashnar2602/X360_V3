import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

abstract class GenerateTurnipMesaAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val mesaRuntimeLockManifest: RegularFileProperty

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDir.get().asFile.toPath()
        deleteRecursively(output)
        output.createDirectories()

        val assembler = MesaTurnipRuntimeAssembler()
        assembler.assertPrerequisites()
        val lock = MesaRuntimeLockCodec.decode(mesaRuntimeLockManifest.get().asFile.toPath().readText())
        val downloadsDir = temporaryDir.toPath().resolve("downloads")
        val workingRoot = temporaryDir.toPath().resolve("build")
        val archives = assembler.prepareSourceArchives(lock, downloadsDir)
        val metadata = assembler.buildAndStageBundles(
            lock = lock,
            archives = archives,
            workingRoot = workingRoot,
            outputRoot = output,
            patchesRoot = patchesDir.get().asFile.toPath(),
        )

        val payloadConfigDir = output.resolve("payload").resolve("config")
        payloadConfigDir.createDirectories()
        mesaRuntimeLockManifest.get().asFile.toPath().copyTo(
            payloadConfigDir.resolve("mesa-turnip-source-lock.json"),
            overwrite = true,
        )
        assembler.writeMetadata(
            metadata = metadata,
            outputPath = payloadConfigDir.resolve("mesa-runtime-metadata.json"),
        )

        writeUtf8(
            output.resolve("rootfs").resolve("usr").resolve("share").resolve("x360-v3").resolve("turnip-baseline.txt"),
            "Phase 3B Turnip bundles generated from pinned Mesa source snapshots\n",
        )
    }
}
