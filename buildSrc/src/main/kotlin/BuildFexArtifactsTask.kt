import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.inject.Inject
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

abstract class BuildFexArtifactsTask : DefaultTask() {
    @get:InputDirectory
    abstract val preparedSourceDir: DirectoryProperty

    @get:Input
    abstract val androidSdkDir: Property<String>

    @get:Input
    abstract val androidApiLevel: Property<Int>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val cmakeVersion: Property<String>

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:Input
    abstract val fexCommit: Property<String>

    @get:Input
    abstract val patchSetId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputFile
    abstract val metadataFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun buildArtifacts() {
        val preparedSource = preparedSourceDir.get().asFile.toPath()
        val sdkDir = Path.of(androidSdkDir.get())
        val ndkDir = sdkDir.resolve("ndk").resolve(ndkVersion.get())
        require(ndkDir.exists()) {
            "Android NDK ${ndkVersion.get()} not found under $sdkDir"
        }

        val cmakeBinDir = sdkDir.resolve("cmake").resolve(cmakeVersion.get()).resolve("bin")
        val cmakeExecutable = resolveTool(cmakeBinDir, "cmake")
        val ninjaExecutable = resolveTool(cmakeBinDir, "ninja")
        val readElfExecutable = resolveTool(
            ndkDir.resolve("toolchains").resolve("llvm").resolve("prebuilt").resolve(hostTag()).resolve("bin"),
            "llvm-readelf",
        )

        val buildDir = temporaryDir.toPath().resolve("android-arm64")
        deleteRecursively(buildDir)
        Files.createDirectories(buildDir)

        runCommand(
            command = listOf(
                cmakeExecutable.toString(),
                "-S",
                preparedSource.toString(),
                "-B",
                buildDir.toString(),
                "-G",
                "Ninja",
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkDir.resolve("build").resolve("cmake").resolve("android.toolchain.cmake")}",
                "-DCMAKE_MAKE_PROGRAM=${ninjaExecutable}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-${androidApiLevel.get()}",
                "-DBUILD_FEXCONFIG=FALSE",
                "-DBUILD_FEX_LINUX_TESTS=FALSE",
                "-DBUILD_TESTING=FALSE",
                "-DBUILD_THUNKS=FALSE",
                "-DENABLE_CCACHE=FALSE",
                "-DENABLE_LTO=FALSE",
                "-DENABLE_JEMALLOC_GLIBC_ALLOC=FALSE",
                "-DTUNE_CPU=generic",
                "-DPython_EXECUTABLE=${pythonExecutable.get()}",
            ),
            workingDirectory = buildDir,
        )
        runCommand(
            command = listOf(cmakeExecutable.toString(), "--build", buildDir.toString(), "--target", "FEXCore_shared", "-j", "8"),
            workingDirectory = buildDir,
        )
        runCommand(
            command = listOf(cmakeExecutable.toString(), "--build", buildDir.toString(), "--target", "FEX", "-j", "8"),
            workingDirectory = buildDir,
        )

        val loader = buildDir.resolve("Bin").resolve("libFEXLoader.so")
        val core = buildDir.resolve("FEXCore").resolve("Source").resolve("libFEXCore.so")
        require(loader.exists()) { "Missing FEX loader artifact at $loader" }
        require(core.exists()) { "Missing FEX core artifact at $core" }

        val abiDir = outputDir.get().asFile.toPath().resolve("arm64-v8a")
        deleteRecursively(outputDir.get().asFile.toPath())
        abiDir.createDirectories()

        val packagedLoader = abiDir.resolve(loader.fileName.toString())
        val packagedCore = abiDir.resolve(core.fileName.toString())
        loader.copyTo(packagedLoader, overwrite = true)
        core.copyTo(packagedCore, overwrite = true)

        val artifacts = listOf(packagedLoader, packagedCore).map { artifact ->
            BuiltArtifact(
                name = artifact.fileName.toString(),
                sha256 = sha256(artifact),
                neededLibraries = readNeededLibraries(readElfExecutable, artifact),
            )
        }
        writeMetadata(artifacts)
    }

    private fun writeMetadata(artifacts: List<BuiltArtifact>) {
        val json = buildString {
            appendLine("{")
            appendLine("  \"fexCommit\": \"${fexCommit.get().jsonEscape()}\",")
            appendLine("  \"patchSetId\": \"${patchSetId.get().jsonEscape()}\",")
            appendLine("  \"abi\": \"arm64-v8a\",")
            appendLine("  \"artifacts\": [")
            append(
                artifacts.joinToString(",\n") { artifact ->
                    buildString {
                        appendLine("    {")
                        appendLine("      \"name\": \"${artifact.name.jsonEscape()}\",")
                        appendLine("      \"sha256\": \"${artifact.sha256}\",")
                        append("      \"neededLibraries\": [")
                        append(artifact.neededLibraries.joinToString(", ") { "\"${it.jsonEscape()}\"" })
                        appendLine("]")
                        append("    }")
                    }
                },
            )
            appendLine()
            appendLine("  ]")
            appendLine("}")
        }
        writeUtf8(metadataFile.get().asFile.toPath(), json)
    }

    private fun readNeededLibraries(readElfExecutable: Path, artifact: Path): List<String> {
        val output = runCommand(
            command = listOf(readElfExecutable.toString(), "--dynamic", artifact.toString()),
            workingDirectory = artifact.parent,
        )
        val regex = Regex("""Shared library: \[(.+)]""")
        return output.lineSequence()
            .mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
            .toList()
    }

    private fun runCommand(
        command: List<String>,
        workingDirectory: Path,
    ): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir = workingDirectory.toFile()
            commandLine(command)
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            error(
                buildString {
                    appendLine("Command failed with exit code ${result.exitValue}:")
                    appendLine(command.joinToString(" "))
                    val output = (stdout.toString() + stderr.toString()).trim()
                    if (output.isNotBlank()) {
                        appendLine(output)
                    }
                },
            )
        }
        return stdout.toString() + stderr.toString()
    }

    private fun resolveTool(directory: Path, basename: String): Path {
        val suffix = if (System.getProperty("os.name").lowercase(Locale.US).contains("win")) ".exe" else ""
        return directory.resolve("$basename$suffix")
    }

    private fun hostTag(): String {
        return if (System.getProperty("os.name").lowercase(Locale.US).contains("win")) {
            "windows-x86_64"
        } else {
            "linux-x86_64"
        }
    }

    private fun String.jsonEscape(): String = buildString(length + 8) {
        for (char in this@jsonEscape) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private data class BuiltArtifact(
    val name: String,
    val sha256: String,
    val neededLibraries: List<String>,
)
