import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

abstract class GenerateXeniaBringupAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val xeniaSourceLockManifest: RegularFileProperty

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:Input
    abstract val buildMode: Property<String>

    @get:LocalState
    abstract val sourceCacheDir: DirectoryProperty

    @get:LocalState
    abstract val workspaceCacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDir.get().asFile.toPath()
        deleteRecursively(output)
        output.createDirectories()

        val assembler = XeniaRuntimeAssembler()
        assembler.assertPrerequisites()
        val lock = XeniaRuntimeLockCodec.decode(xeniaSourceLockManifest.get().asFile.toPath().readText())
        val resolvedBuildMode = XeniaBuildMode.parse(buildMode.get())
        val workingRootBase = when (resolvedBuildMode) {
            XeniaBuildMode.FULL -> temporaryDir.toPath().resolve("workspaces")
            XeniaBuildMode.INCREMENTAL -> workspaceCacheDir.get().asFile.toPath()
        }
        val metadata = assembler.buildAndStage(
            lock = lock,
            sourceCacheDir = sourceCacheDir.get().asFile.toPath(),
            workingRootBase = workingRootBase,
            outputRoot = output,
            patchesRoot = patchesDir.get().asFile.toPath(),
            buildMode = resolvedBuildMode,
        )

        val payloadConfigDir = output.resolve("payload").resolve("config")
        payloadConfigDir.createDirectories()
        xeniaSourceLockManifest.get().asFile.toPath().copyTo(
            payloadConfigDir.resolve("xenia-source-lock.json"),
            overwrite = true,
        )
        assembler.writeMetadata(
            metadata = metadata,
            outputPath = payloadConfigDir.resolve("xenia-build-metadata.json"),
        )
    }
}
