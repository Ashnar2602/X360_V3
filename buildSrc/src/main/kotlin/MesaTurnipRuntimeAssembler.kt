import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class MesaTurnipRuntimeAssembler(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val baseRuntimeLibraries = setOf(
        "libc.so.6",
        "libm.so.6",
        "libgcc_s.so.1",
        "libstdc++.so.6",
        "libz.so.1",
        "libzstd.so.1",
        "libexpat.so.1",
        "ld-linux-x86-64.so.2",
    )

    fun assertPrerequisites() {
        require(System.getProperty("os.name").lowercase().contains("win")) {
            "Phase 3B Mesa build currently requires a Windows host with WSL enabled"
        }

        val missingTools = listOf(
            "bash",
            "meson",
            "ninja",
            "clang",
            "clang++",
            "python3",
            "bison",
            "flex",
            "pkg-config",
            "cmake",
            "curl",
            "glslangValidator",
        ).filterNot { tool ->
            runProcess(
                command = listOf("wsl", "bash", "-lc", "command -v $tool >/dev/null 2>&1"),
                workingDirectory = null,
                failOnError = false,
            ).exitCode == 0
        }
        val missingPkgConfigModules = listOf(
            "zlib",
            "libzstd",
            "expat",
            "libudev",
            "libelf",
            "libxml-2.0",
        ).filterNot { module ->
            runProcess(
                command = listOf("wsl", "bash", "-lc", "pkg-config --exists $module"),
                workingDirectory = null,
                failOnError = false,
            ).exitCode == 0
        }
        val hostGitAvailable = runProcess(
            command = listOf("git", "--version"),
            workingDirectory = null,
            failOnError = false,
        ).exitCode == 0
        if (missingTools.isNotEmpty() || missingPkgConfigModules.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Missing WSL prerequisites for Phase 3B Mesa builds.")
                    if (missingTools.isNotEmpty()) {
                        appendLine("Missing tools: ${missingTools.joinToString(" ")}")
                    }
                    if (missingPkgConfigModules.isNotEmpty()) {
                        appendLine("Missing pkg-config modules: ${missingPkgConfigModules.joinToString(" ")}")
                    }
                    append("Suggested apt install: sudo apt install build-essential clang meson ninja-build pkg-config python3 bison flex cmake curl glslang-tools zlib1g-dev libzstd-dev libexpat1-dev libudev-dev libelf-dev libxml2-dev")
                },
            )
        }
        require(hostGitAvailable) {
            "Missing host prerequisite for Phase 3B Mesa builds: git"
        }
    }

    fun prepareSourceArchives(
        lock: MesaRuntimeSourceLock,
        downloadsDir: Path,
    ): Map<String, Path> {
        downloadsDir.createDirectories()
        val archiveDir = downloadsDir.resolve("archives")
        val gitCacheDir = downloadsDir.resolve("git-cache")
        return lock.bundles.associate { bundle ->
            val preparedArchive = when (bundle.sourceKind) {
                MesaRuntimeSourceKind.ARCHIVE -> {
                    val filename = bundle.sourceUrl.substringAfterLast('/')
                    val destination = archiveDir.resolve(filename)
                    downloadIfNeeded(bundle, destination)
                    destination
                }
                MesaRuntimeSourceKind.GIT -> prepareGitArchive(
                    bundle = bundle,
                    gitCacheDir = gitCacheDir.resolve(bundle.branch),
                    archiveDir = archiveDir,
                )
            }
            bundle.branch to preparedArchive
        }
    }

    fun buildAndStageBundles(
        lock: MesaRuntimeSourceLock,
        archives: Map<String, Path>,
        workingRoot: Path,
        outputRoot: Path,
        patchesRoot: Path,
    ): GeneratedMesaRuntimeMetadata {
        val rootfsOutput = outputRoot.resolve("rootfs")
        val bundles = lock.bundles.map { bundle ->
            val archive = archives[bundle.branch]
                ?: error("Missing source archive for ${bundle.branch}")
            val patchFiles = resolveBundlePatches(bundle, patchesRoot)
            val prefixDir = buildBundle(
                bundle = bundle,
                sourceArchive = archive,
                workingRoot = workingRoot.resolve("${bundle.branch}-${System.nanoTime()}"),
                patchFiles = patchFiles,
            )
            stageBundle(bundle, prefixDir, rootfsOutput, patchFiles)
        }
        return GeneratedMesaRuntimeMetadata(
            profile = lock.profile,
            bundles = bundles,
        )
    }

    fun writeMetadata(
        metadata: GeneratedMesaRuntimeMetadata,
        outputPath: Path,
    ) {
        writeUtf8(outputPath, MesaRuntimeLockCodec.encodeMetadata(metadata))
    }

    private fun buildBundle(
        bundle: MesaRuntimeSourceBundle,
        sourceArchive: Path,
        workingRoot: Path,
        patchFiles: List<Path>,
    ): Path {
        deleteRecursively(workingRoot)
        workingRoot.createDirectories()

        val sourceArchiveWsl = toWslPath(sourceArchive)
        val prefixDir = workingRoot.resolve("prefix")
        val prefixDirWsl = toWslPath(prefixDir)
        val patchCommands = if (patchFiles.isEmpty()) {
            "            :"
        } else {
            patchFiles.joinToString(separator = "\n") { patchFile ->
                val patchWslPath = toWslPath(patchFile)
                "            git -C \"\${SOURCE_DIR}\" apply --whitespace=nowarn ${shellQuote(patchWslPath)}"
            }
        }
        val script = """
            set -euo pipefail
            BUILD_ROOT=${'$'}(mktemp -d /tmp/x360-v3-${bundle.branch}-XXXXXX)
            cleanup() {
              rm -rf "${'$'}BUILD_ROOT"
            }
            trap cleanup EXIT
            mkdir -p "${'$'}BUILD_ROOT/source" "$prefixDirWsl"
            tar -xf "$sourceArchiveWsl" -C "${'$'}BUILD_ROOT/source"
            SOURCE_DIR=${'$'}(find "${'$'}BUILD_ROOT/source" -mindepth 1 -maxdepth 1 -type d | head -n 1)
            if [ -z "${'$'}SOURCE_DIR" ]; then
              echo "Unable to locate extracted Mesa source directory"
              exit 3
            fi
            find "${'$'}SOURCE_DIR" -type f \( -name "*.py" -o -name "*.sh" \) -exec sed -i 's/\r$//' {} +
            git -C "${'$'}SOURCE_DIR" init -q
            git -C "${'$'}SOURCE_DIR" config core.autocrlf false
            git -C "${'$'}SOURCE_DIR" config core.eol lf
$patchCommands
            export CC=clang
            export CXX=clang++
            meson setup "${'$'}BUILD_ROOT/build" "${'$'}SOURCE_DIR" \
              --prefix="${'$'}BUILD_ROOT/prefix" \
              -Dbuildtype=release \
              -Dplatforms= \
              -Dgallium-drivers= \
              -Dvulkan-drivers=freedreno \
              -Dfreedreno-kmds=kgsl \
              -Degl=disabled \
              -Dglx=disabled \
              -Dgbm=disabled \
              -Dllvm=disabled \
              -Dshared-glapi=disabled
            ninja -C "${'$'}BUILD_ROOT/build" -j8 install
            cp -a "${'$'}BUILD_ROOT/prefix/." "$prefixDirWsl/"
        """.trimIndent()
        val scriptPath = workingRoot.resolve("build-mesa-${bundle.branch}.sh")
        writeUtf8(scriptPath, "$script\n")
        runProcess(listOf("wsl", "bash", toWslPath(scriptPath)), null)
        require(prefixDir.exists()) {
            "Mesa install prefix missing for ${bundle.branch}: $prefixDir"
        }
        return prefixDir
    }

    private fun stageBundle(
        bundle: MesaRuntimeSourceBundle,
        prefixDir: Path,
        rootfsOutput: Path,
        patchFiles: List<Path>,
    ): GeneratedMesaRuntimeBundleMetadata {
        val driverSource = prefixDir.resolve("lib").resolve("x86_64-linux-gnu").resolve("libvulkan_freedreno.so")
        val icdSource = prefixDir.resolve("share").resolve("vulkan").resolve("icd.d").resolve("freedreno_icd.x86_64.json")
        require(driverSource.exists()) { "Missing Turnip driver for ${bundle.branch}: $driverSource" }
        require(icdSource.exists()) { "Missing Turnip ICD JSON for ${bundle.branch}: $icdSource" }

        val branchRoot = rootfsOutput
            .resolve("opt")
            .resolve("x360-v3")
            .resolve("mesa")
            .resolve(bundle.branch)
        val libRoot = branchRoot.resolve("lib")
        val icdRoot = branchRoot.resolve("icd")
        libRoot.createDirectories()
        icdRoot.createDirectories()

        val bundledDependencies = mutableListOf<String>()
        driverSource.copyTo(libRoot.resolve("libvulkan_freedreno.so"), overwrite = true)

        readDependencies(driverSource).forEach { dependency ->
            val dependencyPath = dependency.path ?: return@forEach
            if (dependency.name in baseRuntimeLibraries) {
                return@forEach
            }
            val destination = libRoot.resolve(dependency.name)
            dependencyPath.copyTo(destination, overwrite = true)
            bundledDependencies += dependency.name
        }

        val guestDriverPath = "/opt/x360-v3/mesa/${bundle.branch}/lib/libvulkan_freedreno.so"
        val icdOutput = icdRoot.resolve("turnip_icd.json")
        writeUtf8(
            icdOutput,
            rewriteIcdJson(icdSource.readText(), guestDriverPath),
        )

        return GeneratedMesaRuntimeBundleMetadata(
            branch = bundle.branch,
            mesaVersion = bundle.mesaVersion,
            sourceKind = bundle.sourceKind,
            sourceUrl = bundle.sourceUrl,
            sourceSha256 = bundle.sourceSha256,
            sourceRef = bundle.sourceRef,
            sourceRevision = bundle.sourceRevision,
            installProfile = bundle.installProfile,
            patchSetId = bundle.patchSetId,
            appliedPatches = patchFiles.map { it.fileName.toString() },
            libRoot = "/opt/x360-v3/mesa/${bundle.branch}/lib",
            icdPath = "/opt/x360-v3/mesa/${bundle.branch}/icd/turnip_icd.json",
            driverLibraryName = "libvulkan_freedreno.so",
            bundledDependencies = bundledDependencies.sorted(),
        )
    }

    private fun resolveBundlePatches(
        bundle: MesaRuntimeSourceBundle,
        patchesRoot: Path,
    ): List<Path> {
        val bundlePatchDir = patchesRoot.resolve(bundle.branch)
        val patchFiles = if (bundlePatchDir.exists()) {
            Files.list(bundlePatchDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".patch") }
                    .sorted()
                    .toList()
            }
        } else {
            emptyList()
        }

        if (bundle.patchSetId == "none" && patchFiles.isNotEmpty()) {
            error("Unexpected Mesa patches for ${bundle.branch}: patchSetId is 'none' but found ${patchFiles.size} patch(es)")
        }
        if (bundle.patchSetId != "none" && patchFiles.isEmpty()) {
            error("Missing Mesa patches for ${bundle.branch}: patchSetId=${bundle.patchSetId}")
        }

        return patchFiles
    }

    private fun readDependencies(driverLibrary: Path): List<MesaDependency> {
        val output = runProcess(
            listOf("wsl", "bash", "-lc", "ldd ${shellQuote(toWslPath(driverLibrary))}"),
            null,
        ).output
        return output.lineSequence()
            .mapNotNull { parseDependency(it) }
            .toList()
    }

    private fun parseDependency(line: String): MesaDependency? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val directRegex = Regex("""^(\S+)\s+=>\s+(\S+)\s+\(0x""")
        val loaderRegex = Regex("""^(/[^ ]+/([^/\s]+))\s+\(0x""")
        directRegex.find(trimmed)?.let { match ->
            val name = match.groupValues[1]
            val resolved = match.groupValues[2]
            if (resolved == "not") {
                error("Unresolved dependency in ldd output: $line")
            }
            return MesaDependency(name, Path.of(resolved))
        }
        loaderRegex.find(trimmed)?.let { match ->
            return MesaDependency(match.groupValues[2], Path.of(match.groupValues[1]))
        }
        if (trimmed.contains("not found")) {
            error("Unresolved dependency in ldd output: $line")
        }
        return null
    }

    private fun rewriteIcdJson(
        rawJson: String,
        guestLibraryPath: String,
    ): String {
        val libraryPathRegex = Regex(""""library_path"\s*:\s*"[^"]+"""")
        require(libraryPathRegex.containsMatchIn(rawJson)) {
            "Unable to rewrite Mesa ICD JSON library_path"
        }
        return libraryPathRegex.replace(rawJson) {
            "\"library_path\": \"$guestLibraryPath\""
        }
    }

    private fun downloadIfNeeded(
        bundle: MesaRuntimeSourceBundle,
        destination: Path,
    ) {
        val sourceSha256 = requireNotNull(bundle.sourceSha256) {
            "Missing sourceSha256 for archive source ${bundle.branch}"
        }
        if (destination.exists() && sha256(destination) == sourceSha256) {
            return
        }

        destination.parent?.createDirectories()
        Files.deleteIfExists(destination)
        val response = httpClient.send(
            HttpRequest.newBuilder(URI(bundle.sourceUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(destination),
        )
        require(response.statusCode() in 200..299) {
            "Failed to download ${bundle.sourceUrl}: HTTP ${response.statusCode()}"
        }
        require(sha256(destination) == sourceSha256) {
            "SHA-256 mismatch for ${bundle.sourceUrl}"
        }
    }

    private fun prepareGitArchive(
        bundle: MesaRuntimeSourceBundle,
        gitCacheDir: Path,
        archiveDir: Path,
    ): Path {
        val sourceRevision = requireNotNull(bundle.sourceRevision) {
            "Missing sourceRevision for git source ${bundle.branch}"
        }
        archiveDir.createDirectories()
        val archivePath = archiveDir.resolve("mesa-${bundle.branch}-${sourceRevision.take(12)}.tar")
        if (archivePath.exists()) {
            return archivePath
        }

        ensureGitCache(bundle, gitCacheDir)
        runProcess(
            command = listOf("git", "-C", gitCacheDir.toString(), "fetch", "--depth", "1", "origin", sourceRevision),
            workingDirectory = null,
        )
        val fetchedRevision = runProcess(
            command = listOf("git", "-C", gitCacheDir.toString(), "rev-parse", "FETCH_HEAD"),
            workingDirectory = null,
        ).output.trim()
        require(fetchedRevision == sourceRevision) {
            "Fetched Mesa revision mismatch for ${bundle.branch}: expected $sourceRevision but got $fetchedRevision"
        }

        Files.deleteIfExists(archivePath)
        runProcess(
            command = listOf(
                "git",
                "-C",
                gitCacheDir.toString(),
                "archive",
                "--format=tar",
                "--prefix=mesa-$sourceRevision/",
                sourceRevision,
                "-o",
                archivePath.toString(),
            ),
            workingDirectory = null,
        )
        require(archivePath.exists()) {
            "Failed to create Mesa source archive for ${bundle.branch}: $archivePath"
        }
        return archivePath
    }

    private fun ensureGitCache(
        bundle: MesaRuntimeSourceBundle,
        gitCacheDir: Path,
    ) {
        val gitDir = gitCacheDir.resolve(".git")
        if (!gitDir.exists()) {
            deleteRecursively(gitCacheDir)
            gitCacheDir.parent?.createDirectories()
            runProcess(
                command = listOf("git", "init", gitCacheDir.toString()),
                workingDirectory = null,
            )
            runProcess(
                command = listOf("git", "-C", gitCacheDir.toString(), "config", "core.autocrlf", "false"),
                workingDirectory = null,
            )
            runProcess(
                command = listOf("git", "-C", gitCacheDir.toString(), "config", "core.eol", "lf"),
                workingDirectory = null,
            )
            runProcess(
                command = listOf("git", "-C", gitCacheDir.toString(), "remote", "add", "origin", bundle.sourceUrl),
                workingDirectory = null,
            )
            return
        }

        val remoteUrl = runProcess(
            command = listOf("git", "-C", gitCacheDir.toString(), "remote", "get-url", "origin"),
            workingDirectory = null,
            failOnError = false,
        ).output.trim()
        if (remoteUrl != bundle.sourceUrl) {
            deleteRecursively(gitCacheDir)
            ensureGitCache(
                bundle = bundle,
                gitCacheDir = gitCacheDir,
            )
            return
        }
        runProcess(
            command = listOf("git", "-C", gitCacheDir.toString(), "config", "core.autocrlf", "false"),
            workingDirectory = null,
        )
        runProcess(
            command = listOf("git", "-C", gitCacheDir.toString(), "config", "core.eol", "lf"),
            workingDirectory = null,
        )
    }

    private fun toWslPath(path: Path): String {
        val normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        val drivePathMatch = Regex("^([A-Za-z]):/(.*)$").matchEntire(normalized)
        if (drivePathMatch != null) {
            val driveLetter = drivePathMatch.groupValues[1].lowercase()
            val remainingPath = drivePathMatch.groupValues[2]
            return "/mnt/$driveLetter/$remainingPath"
        }
        return runProcess(
            listOf("wsl", "wslpath", "-a", "-u", normalized),
            null,
        ).output.trim()
    }

    private fun runProcess(
        command: List<String>,
        workingDirectory: Path?,
        failOnError: Boolean = true,
    ): ProcessResult {
        val stdout = ByteArrayOutputStream()
        val process = ProcessBuilder(command)
            .apply {
                if (workingDirectory != null) {
                    directory(workingDirectory.toFile())
                }
                redirectErrorStream(true)
            }
            .start()
        process.inputStream.copyTo(stdout)
        val exitCode = process.waitFor()
        val combined = stdout.toString().trim()
        if (failOnError && exitCode != 0) {
            error(
                buildString {
                    appendLine("Command failed with exit code $exitCode:")
                    appendLine(command.joinToString(" "))
                    if (combined.isNotBlank()) {
                        appendLine(combined)
                    }
                },
            )
        }
        return ProcessResult(
            exitCode = exitCode,
            output = combined,
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}

private data class MesaDependency(
    val name: String,
    val path: Path?,
)

private data class ProcessResult(
    val exitCode: Int,
    val output: String,
)
