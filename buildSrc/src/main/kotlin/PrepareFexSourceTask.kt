import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Files
import javax.inject.Inject
import kotlin.io.path.extension

abstract class PrepareFexSourceTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        val source = sourceDir.get().asFile.toPath()
        val patches = patchesDir.get().asFile.toPath()
        val output = outputDir.get().asFile.toPath()

        if (!source.resolve("External").resolve("fmt").toFile().exists()) {
            execOperations.exec {
                workingDir = source.toFile()
                commandLine("git", "submodule", "update", "--init", "--recursive")
            }
        }

        deleteRecursively(output)
        copyTree(source, output)
        execOperations.exec {
            workingDir = output.toFile()
            commandLine("git", "init", ".")
        }

        if (!Files.exists(patches)) {
            return
        }

        Files.list(patches).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "patch" }
                .sorted()
                .forEach { patch ->
                    execOperations.exec {
                        workingDir = output.toFile()
                        commandLine(
                            "git",
                            "apply",
                            "--whitespace=nowarn",
                            patch.toAbsolutePath().toString(),
                        )
                    }
                }
        }
    }
}
