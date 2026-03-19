import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class StageRuntimePayloadTask : DefaultTask() {
    @get:InputDirectory
    abstract val mockRuntimeDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val generatedFexRuntimeDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val generatedVulkanRuntimeDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val generatedTurnipRuntimeDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val generatedXeniaRuntimeDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val localRuntimeDropDir: DirectoryProperty

    @get:Input
    abstract val includeLocalDrop: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stagePayload() {
        RuntimePayloadOverlayPlanner().stage(
            mockRuntimeDir = mockRuntimeDir.get().asFile.toPath(),
            generatedFexRuntimeDir = generatedFexRuntimeDir.orNull?.asFile?.toPath(),
            generatedVulkanRuntimeDir = generatedVulkanRuntimeDir.orNull?.asFile?.toPath(),
            generatedTurnipRuntimeDir = generatedTurnipRuntimeDir.orNull?.asFile?.toPath(),
            generatedXeniaRuntimeDir = generatedXeniaRuntimeDir.orNull?.asFile?.toPath(),
            localRuntimeDropDir = localRuntimeDropDir.orNull?.asFile?.toPath(),
            outputDir = outputDir.get().asFile.toPath(),
            includeLocalDrop = includeLocalDrop.getOrElse(false),
        )
    }
}
