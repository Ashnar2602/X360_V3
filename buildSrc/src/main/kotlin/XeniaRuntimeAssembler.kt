import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class XeniaRuntimeAssembler(
    private val guestPackageAssembler: UbuntuGuestRuntimeAssembler = UbuntuGuestRuntimeAssembler(),
) {
    private val baseRuntimeLibraries = setOf(
        "ld-linux-x86-64.so.2",
        "libLLVM-17.so.1",
        "libX11-xcb.so.1",
        "libXau.so.6",
        "libXdmcp.so.6",
        "libbsd.so.0",
        "libc.so.6",
        "libdrm.so.2",
        "libedit.so.2",
        "libexpat.so.1",
        "libffi.so.8",
        "libgcc_s.so.1",
        "libicudata.so.74",
        "libicuuc.so.74",
        "liblzma.so.5",
        "libm.so.6",
        "libmd.so.0",
        "libstdc++.so.6",
        "libtinfo.so.6",
        "libvulkan.so.1",
        "libvulkan_freedreno.so",
        "libvulkan_lvp.so",
        "libwayland-client.so.0",
        "libxshmfence.so.1",
        "libxml2.so.2",
        "libxcb-dri3.so.0",
        "libxcb-present.so.0",
        "libxcb-randr.so.0",
        "libxcb-shm.so.0",
        "libxcb-sync.so.1",
        "libxcb-xfixes.so.0",
        "libxcb.so.1",
        "libz.so.1",
        "libzstd.so.1",
    )
    private val allowedRuntimePrefixes = listOf("/lib/", "/usr/lib/", "/usr/libexec/")

    fun assertPrerequisites() {
        require(System.getProperty("os.name").lowercase().contains("win")) {
            "Phase 4A Xenia builds currently require a Windows host with WSL enabled"
        }
        require(runProcess(listOf("git", "--version"), null, failOnError = false).exitCode == 0) {
            "Missing host prerequisite for Phase 4A Xenia builds: git"
        }

        val missingTools = listOf(
            "bash",
            "git",
            "python3",
            "clang",
            "clang++",
            "cmake",
            "ninja",
            "pkg-config",
            "wget",
            "tar",
            "apt",
            "dpkg-query",
        ).filterNot { tool ->
            runProcess(
                command = listOf("wsl", "bash", "-lc", "command -v $tool >/dev/null 2>&1"),
                workingDirectory = null,
                failOnError = false,
            ).exitCode == 0
        }
        val missingPkgConfigModules = listOf(
            "gtk+-3.0",
            "sdl2",
            "vulkan",
            "x11-xcb",
            "liblz4",
        ).filterNot { module ->
            runProcess(
                command = listOf("wsl", "bash", "-lc", "pkg-config --exists ${shellQuote(module)}"),
                workingDirectory = null,
                failOnError = false,
            ).exitCode == 0
        }
        if (missingTools.isNotEmpty() || missingPkgConfigModules.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Missing WSL prerequisites for Phase 4A Xenia builds.")
                    if (missingTools.isNotEmpty()) {
                        appendLine("Missing tools: ${missingTools.joinToString(" ")}")
                    }
                    if (missingPkgConfigModules.isNotEmpty()) {
                        appendLine("Missing pkg-config modules: ${missingPkgConfigModules.joinToString(" ")}")
                    }
                    append(
                        "Suggested apt install: sudo apt-get install build-essential mesa-vulkan-drivers valgrind libc++-dev libc++abi-dev libgtk-3-dev liblz4-dev libsdl2-dev libvulkan-dev libx11-xcb-dev clang llvm ninja-build pkg-config wget tar",
                    )
                },
            )
        }
    }

    fun buildAndStage(
        lock: XeniaSourceBuildLock,
        sourceCacheDir: Path,
        workingRoot: Path,
        outputRoot: Path,
        patchesRoot: Path,
    ): GeneratedXeniaBuildMetadata {
        deleteRecursively(outputRoot)
        workingRoot.createDirectories()
        outputRoot.createDirectories()

        val checkoutDir = workingRoot.resolve("checkout")
        prepareSourceCheckout(lock, sourceCacheDir, checkoutDir)
        val patchFiles = resolvePatchFiles(lock, patchesRoot)
        val vulkanSdkOverride = ensureModernVulkanSdk(sourceCacheDir.parent.resolve("vulkan-sdk-cache"))
        val executable = buildXenia(lock, checkoutDir, patchFiles, vulkanSdkOverride)
        val dependencyResolution = resolveGuestDependencies(executable, workingRoot.resolve("downloads"))

        stageRuntimeTree(
            executable = executable,
            dependencyResolution = dependencyResolution,
            outputRoot = outputRoot,
            lock = lock,
        )

        return GeneratedXeniaBuildMetadata(
            sourceUrl = lock.sourceUrl,
            sourceRef = lock.sourceRef,
            sourceRevision = lock.sourceRevision,
            buildProfile = lock.buildProfile,
            patchSetId = lock.patchSetId,
            executableName = "xenia-canary",
            executableSha256 = sha256(outputRoot.resolve("rootfs/opt/x360-v3/xenia/bin/xenia-canary")),
            runtimeLibraries = dependencyResolution.libraries.map { library ->
                GeneratedXeniaRuntimeLibrary(
                    soname = library.soname,
                    sourcePath = library.sourcePath,
                    installPath = library.installPath,
                    packageName = library.packageName,
                    packageVersion = library.packageVersion,
                )
            },
            requiredPackages = dependencyResolution.packages.map { pkg ->
                GeneratedXeniaGuestPackage(
                    packageName = pkg.packageName,
                    packageVersion = pkg.packageVersion,
                    archiveName = pkg.archivePath.fileName.toString(),
                    archiveSha256 = sha256(pkg.archivePath),
                )
            },
        )
    }

    fun writeMetadata(
        metadata: GeneratedXeniaBuildMetadata,
        outputPath: Path,
    ) {
        writeUtf8(outputPath, XeniaRuntimeLockCodec.encodeMetadata(metadata))
    }

    private fun prepareSourceCheckout(
        lock: XeniaSourceBuildLock,
        sourceCacheDir: Path,
        checkoutDir: Path,
    ) {
        ensureSourceCache(lock, sourceCacheDir)
        deleteRecursively(checkoutDir)
        runProcess(
            command = listOf(
                "git",
                "clone",
                "--local",
                "--no-hardlinks",
                sourceCacheDir.toString(),
                checkoutDir.toString(),
            ),
            workingDirectory = null,
        )
        runProcess(
            command = listOf(
                "git",
                "-C",
                checkoutDir.toString(),
                "checkout",
                "--force",
                "--detach",
                lock.sourceRevision,
            ),
            workingDirectory = null,
        )
    }

    private fun ensureSourceCache(
        lock: XeniaSourceBuildLock,
        sourceCacheDir: Path,
    ) {
        val gitDir = sourceCacheDir.resolve(".git")
        if (!gitDir.exists()) {
            deleteRecursively(sourceCacheDir)
            runProcess(
                command = listOf(
                    "git",
                    "clone",
                    "--branch",
                    lock.sourceRef,
                    "--single-branch",
                    "--filter=blob:none",
                    lock.sourceUrl,
                    sourceCacheDir.toString(),
                ),
                workingDirectory = null,
            )
        }
        runProcess(
            command = listOf("git", "-C", sourceCacheDir.toString(), "remote", "set-url", "origin", lock.sourceUrl),
            workingDirectory = null,
            failOnError = false,
        )
        runProcess(
            command = listOf(
                "git",
                "-C",
                sourceCacheDir.toString(),
                "fetch",
                "--filter=blob:none",
                "origin",
                lock.sourceRef,
            ),
            workingDirectory = null,
        )
        runProcess(
            command = listOf(
                "git",
                "-C",
                sourceCacheDir.toString(),
                "fetch",
                "--filter=blob:none",
                "origin",
                lock.sourceRevision,
            ),
            workingDirectory = null,
        )
        runProcess(
            command = listOf(
                "git",
                "-C",
                sourceCacheDir.toString(),
                "checkout",
                "--force",
                "--detach",
                lock.sourceRevision,
            ),
            workingDirectory = null,
        )
    }

    private fun resolvePatchFiles(
        lock: XeniaSourceBuildLock,
        patchesRoot: Path,
    ): List<Path> {
        val patchDir = patchesRoot.resolve("phase4")
        val patchFiles = if (patchDir.exists()) {
            Files.list(patchDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".patch") }
                    .sorted()
                    .toList()
            }
        } else {
            emptyList()
        }
        if (lock.patchSetId == "none" && patchFiles.isNotEmpty()) {
            error("Unexpected Xenia patches found for patchSetId=none")
        }
        if (lock.patchSetId != "none" && patchFiles.isEmpty()) {
            error("Missing Xenia patches for patchSetId=${lock.patchSetId}")
        }
        return patchFiles
    }

    private fun buildXenia(
        lock: XeniaSourceBuildLock,
        checkoutDir: Path,
        patchFiles: List<Path>,
        vulkanSdkOverride: String?,
    ): Path {
        val buildConfig = resolveBuildConfig(lock.buildProfile)
        val resultPath = checkoutDir.resolve("xenia-build-result.txt")
        val sourceDirWsl = toWslPath(checkoutDir)
        val resultPathWsl = toWslPath(resultPath)
        val vulkanSdkExports = if (vulkanSdkOverride.isNullOrBlank()) {
            ""
        } else {
            """
            export VULKAN_SDK=${shellQuote(vulkanSdkOverride)}
            export PATH="${'$'}VULKAN_SDK/bin:${'$'}PATH"
            """.trimIndent()
        }
        val patchCommands = if (patchFiles.isEmpty()) {
            "true"
        } else {
            patchFiles.joinToString("\n") { patchFile ->
                "git -C ${shellQuote(sourceDirWsl)} apply --whitespace=nowarn ${shellQuote(toWslPath(patchFile))}"
            }
        }
        val script = """
            set -euo pipefail
            cd ${shellQuote(sourceDirWsl)}
            find . -type f \( -name "*.py" -o -name "*.sh" -o -name "*.lua" \) -exec sed -i 's/\r$//' {} +
            git config core.autocrlf false
            git config core.eol lf
            $patchCommands
            export CC=clang
            export CXX=clang++
            $vulkanSdkExports
            python3 xenia-build.py setup
            python3 xenia-build.py build --cc clang --config ${buildConfig.cliName} --target xenia-app
            BIN_PATH=${'$'}(find "${'$'}PWD/build/bin/Linux/${buildConfig.outputDirectoryName}" -maxdepth 1 -type f \( -name xenia_canary -o -name xenia-app \) -perm -111 | head -n 1)
            if [ -z "${'$'}BIN_PATH" ]; then
                echo "Unable to locate built xenia_canary binary" >&2
              exit 3
            fi
            BIN_PATH=${'$'}(readlink -f "${'$'}BIN_PATH")
            printf '%s\n' "${'$'}BIN_PATH" > ${shellQuote(resultPathWsl)}
        """.trimIndent()
        val scriptPath = checkoutDir.resolve("build-xenia.sh")
        writeUtf8(scriptPath, "$script\n")
        runProcess(
            command = listOf("wsl", "bash", toWslPath(scriptPath)),
            workingDirectory = null,
        )
        val builtBinaryWsl = resultPath.readText().trim()
        require(builtBinaryWsl.isNotBlank()) { "Xenia build did not report an output binary" }
        return fromWslPath(builtBinaryWsl)
    }

    private fun resolveGuestDependencies(
        executable: Path,
        downloadsDir: Path,
    ): XeniaDependencyResolution {
        deleteRecursively(downloadsDir)
        downloadsDir.createDirectories()

        val packages = linkedMapOf<String, XeniaResolvedPackage>()
        val libraries = linkedMapOf<String, XeniaResolvedLibrary>()
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.addLast(toWslLinuxPath(executable))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            val dependencies = readLddDependencies(current)
            dependencies.forEach { dependency ->
                if (dependency.soname in baseRuntimeLibraries) {
                    return@forEach
                }
                if (allowedRuntimePrefixes.none { dependency.installPath.startsWith(it) }) {
                    return@forEach
                }
                val key = dependency.installPath
                if (libraries.containsKey(key)) {
                    queue.addLast(dependency.sourcePath)
                    return@forEach
                }
                val owner = resolveOwningPackage(dependency.sourcePath)
                val downloadedPackage = packages.getOrPut(owner.packageName) {
                    val archivePath = downloadPackage(
                        packageName = owner.packageName,
                        packageVersion = owner.packageVersion,
                        downloadsDir = downloadsDir,
                    )
                    XeniaResolvedPackage(
                        packageName = owner.packageName,
                        packageVersion = owner.packageVersion,
                        archivePath = archivePath,
                    )
                }
                libraries[key] = XeniaResolvedLibrary(
                    soname = dependency.soname,
                    sourcePath = dependency.sourcePath,
                    installPath = toGuestInstallPath(dependency.installPath),
                    packageName = downloadedPackage.packageName,
                    packageVersion = downloadedPackage.packageVersion,
                )
                queue.addLast(dependency.sourcePath)
            }
        }

        return XeniaDependencyResolution(
            libraries = libraries.values.sortedBy { it.installPath },
            packages = packages.values.sortedBy { it.packageName },
        )
    }

    private fun ensureModernVulkanSdk(
        sdkCacheDir: Path,
    ): String? {
        val hasRequiredSpirvOpt = runProcess(
            command = listOf(
                "wsl",
                "bash",
                "-lc",
                "spirv-opt --help 2>/dev/null | grep -F -- --canonicalize-ids >/dev/null",
            ),
            workingDirectory = null,
            failOnError = false,
        ).exitCode == 0
        if (hasRequiredSpirvOpt) {
            return null
        }

        val sdkCacheDirWsl = toWslPath(sdkCacheDir)
        val provisionScript = """
            set -euo pipefail
            mkdir -p ${shellQuote(sdkCacheDirWsl)}
            cd ${shellQuote(sdkCacheDirWsl)}
            if [ ! -f .x360-v3-vulkan-sdk-path ]; then
              rm -rf sdk-stage vulkan-sdk.tar.xz
              mkdir -p sdk-stage
              wget -qO vulkan-sdk.tar.xz https://sdk.lunarg.com/sdk/download/latest/linux/vulkan-sdk.tar.xz
              tar -xf vulkan-sdk.tar.xz -C sdk-stage
              rm -f vulkan-sdk.tar.xz
              SDK_PATH=${'$'}(find "${'$'}PWD/sdk-stage" -mindepth 2 -maxdepth 2 -type d -name x86_64 | head -n 1)
              if [ -z "${'$'}SDK_PATH" ]; then
                echo "Unable to locate extracted Vulkan SDK x86_64 root" >&2
                exit 2
              fi
              SDK_PATH=${'$'}(readlink -f "${'$'}SDK_PATH")
              printf '%s\n' "${'$'}SDK_PATH" > .x360-v3-vulkan-sdk-path
            fi
            cat .x360-v3-vulkan-sdk-path
        """.trimIndent()
        val provisionScriptPath = sdkCacheDir.resolve("provision-vulkan-sdk.sh")
        writeUtf8(provisionScriptPath, "$provisionScript\n")
        val resolvedSdkPath = runProcess(
            command = listOf("wsl", "bash", toWslPath(provisionScriptPath)),
            workingDirectory = null,
        ).output.lineSequence().lastOrNull()?.trim().orEmpty()
        require(resolvedSdkPath.isNotBlank()) {
            "Unable to provision a modern Vulkan SDK for Xenia shader compilation"
        }
        return resolvedSdkPath
    }

    private fun stageRuntimeTree(
        executable: Path,
        dependencyResolution: XeniaDependencyResolution,
        outputRoot: Path,
        lock: XeniaSourceBuildLock,
    ) {
        val rootfsOutput = outputRoot.resolve("rootfs")
        val xeniaBinDir = rootfsOutput.resolve("opt").resolve("x360-v3").resolve("xenia").resolve("bin")
        val xeniaContentDir = rootfsOutput.resolve("opt").resolve("x360-v3").resolve("xenia").resolve("content")
        val xeniaLogsDir = xeniaBinDir.resolve("logs")
        val xeniaCacheDir = xeniaBinDir.resolve("cache")
        xeniaBinDir.createDirectories()
        xeniaContentDir.createDirectories()
        xeniaLogsDir.createDirectories()
        xeniaCacheDir.createDirectories()

        Files.copy(executable, xeniaBinDir.resolve("xenia-canary"))
        xeniaBinDir.resolve("xenia-canary").toFile().setExecutable(true, false)
        writeUtf8(xeniaBinDir.resolve("portable.txt"), "portable-mode\n")
        writeUtf8(
            xeniaBinDir.resolve("xenia-canary.config.toml"),
            buildXeniaConfig(),
        )

        val archives = dependencyResolution.packages.associate { pkg -> pkg.packageName to pkg.archivePath }
        val extractedPackages = guestPackageAssembler.extractPackages(
            archives = archives,
            extractionDir = outputRoot.resolve("xenia-package-extraction"),
        )
        dependencyResolution.libraries.forEach { library ->
            val packageRoot = extractedPackages[library.packageName]
                ?: error("Missing extracted package for ${library.packageName}")
            val source = packageRoot.resolve(library.sourcePath.removePrefix("/")).normalize()
            require(source.exists()) {
                "Missing extracted Xenia runtime library ${library.sourcePath} from ${library.packageName}"
            }
            val destination = outputRoot.resolve(library.installPath).normalize()
            destination.parent?.createDirectories()
            Files.copy(source, destination)
            destination.toFile().setExecutable(true, false)
        }

        writeUtf8(
            rootfsOutput.resolve("usr").resolve("share").resolve("x360-v3").resolve("xenia-bringup.txt"),
            "Phase 4A Xenia bring-up runtime staged from a pinned source build\n",
        )
    }

    private fun buildXeniaConfig(): String {
        return """
            [General]
            discord = false
            portable = true
            
            [GPU]
            gpu = "vulkan"
            
            [APU]
            apu = "nop"
            
            [HID]
            hid = "nop"
            
            [Kernel]
            headless = true
            
            [Storage]
            mount_cache = true
            mount_memory_unit = false
            mount_scratch = false
            content_root = "../content"
            cache_root = "./cache"
        """.trimIndent() + "\n"
    }

    private fun readLddDependencies(
        binaryPathWsl: String,
    ): List<WslLibraryDependency> {
        val output = runProcess(
            command = listOf("wsl", "bash", "-lc", "ldd ${shellQuote(binaryPathWsl)}"),
            workingDirectory = null,
        ).output
        return output.lineSequence()
            .mapNotNull { line -> parseLddDependency(line) }
            .map { dependency ->
                dependency.copy(sourcePath = canonicalizeWslPath(dependency.sourcePath))
            }
            .distinctBy { it.installPath }
            .toList()
    }

    private fun parseLddDependency(line: String): WslLibraryDependency? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("linux-vdso")) {
            return null
        }
        val directRegex = Regex("""^(\S+)\s+=>\s+(\S+)\s+\(0x""")
        val loaderRegex = Regex("""^(/[^ ]+/([^/\s]+))\s+\(0x""")
        directRegex.find(trimmed)?.let { match ->
            val soname = match.groupValues[1]
            val resolved = match.groupValues[2]
            if (resolved == "not") {
                error("Unresolved Xenia runtime dependency in ldd output: $line")
            }
            return WslLibraryDependency(
                soname = soname,
                sourcePath = resolved,
                installPath = resolved,
            )
        }
        loaderRegex.find(trimmed)?.let { match ->
            return WslLibraryDependency(
                soname = match.groupValues[2],
                sourcePath = match.groupValues[1],
                installPath = match.groupValues[1],
            )
        }
        if (trimmed.contains("not found")) {
            error("Unresolved Xenia runtime dependency in ldd output: $line")
        }
        return null
    }

    private fun canonicalizeWslPath(path: String): String {
        return runProcess(
            command = listOf("wsl", "bash", "-lc", "readlink -f ${shellQuote(path)}"),
            workingDirectory = null,
        ).output.trim()
    }

    private fun resolveOwningPackage(
        sourcePathWsl: String,
    ): PackageOwner {
        val ownerRaw = runProcess(
            command = listOf("wsl", "bash", "-lc", "dpkg-query -S ${shellQuote(sourcePathWsl)} | head -n 1"),
            workingDirectory = null,
        ).output.trim()
        val ownerMatch = Regex("""^(.+?):\s+/""").find(ownerRaw)
            ?: error("Unable to resolve owning package for $sourcePathWsl: $ownerRaw")
        val packageName = ownerMatch.groupValues[1]
        val packageVersion = runProcess(
            command = listOf(
                "wsl",
                "bash",
                "-lc",
                "dpkg-query -W ${shellQuote(packageName)} | cut -f2",
            ),
            workingDirectory = null,
        ).output.trim()
        require(packageVersion.isNotBlank()) {
            "Unable to resolve package version for $packageName"
        }
        return PackageOwner(
            packageName = packageName,
            packageVersion = packageVersion,
        )
    }

    private fun downloadPackage(
        packageName: String,
        packageVersion: String,
        downloadsDir: Path,
    ): Path {
        val packageDownloadDir = downloadsDir.resolve(sanitizePackageDirectoryName(packageName, packageVersion))
        deleteRecursively(packageDownloadDir)
        packageDownloadDir.createDirectories()
        val downloadsDirWsl = toWslPath(packageDownloadDir)
        val downloadCommand =
            "cd ${shellQuote(downloadsDirWsl)} && " +
                "apt download ${shellQuote("$packageName=$packageVersion")} >/dev/null && " +
                "find . -maxdepth 1 -type f -name '*.deb' -printf '%f\\n' | sort | sed '/^$/d'"
        val downloadedFiles = runProcess(
            command = listOf("wsl", "bash", "-lc", downloadCommand),
            workingDirectory = null,
        ).output.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".deb") }
            .toList()
        require(downloadedFiles.size == 1) {
            buildString {
                append("Expected exactly one downloaded archive for $packageName=$packageVersion")
                if (downloadedFiles.isNotEmpty()) {
                    append(", found: ${downloadedFiles.joinToString()}")
                }
            }
        }
        val downloadedName = downloadedFiles.single()
        val archivePath = packageDownloadDir.resolve(downloadedName)
        require(archivePath.exists()) {
            "Expected downloaded package missing: $archivePath"
        }
        return archivePath
    }

    private fun sanitizePackageDirectoryName(
        packageName: String,
        packageVersion: String,
    ): String {
        fun sanitize(value: String): String = value.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return "${sanitize(packageName)}__${sanitize(packageVersion)}"
    }

    private fun toGuestInstallPath(systemPath: String): String {
        return "rootfs/${systemPath.removePrefix("/")}"
    }

    private fun toWslLinuxPath(path: Path): String = toWslPath(path)

    private fun resolveBuildConfig(buildProfile: String): XeniaBuildConfig {
        return when (buildProfile) {
            "linux-x64-release" -> XeniaBuildConfig(
                cliName = "release",
                outputDirectoryName = "Release",
            )
            "linux-x64-relwithdebinfo" -> XeniaBuildConfig(
                cliName = "release",
                outputDirectoryName = "Release",
            )
            "linux-x64-debug" -> XeniaBuildConfig(
                cliName = "debug",
                outputDirectoryName = "Debug",
            )
            "linux-x64-checked" -> XeniaBuildConfig(
                cliName = "checked",
                outputDirectoryName = "Checked",
            )
            else -> error("Unsupported Xenia build profile: $buildProfile")
        }
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

    private fun fromWslPath(path: String): Path {
        val windowsPath = runProcess(
            command = listOf("wsl", "wslpath", "-a", "-w", path),
            workingDirectory = null,
        ).output.trim()
        return Path.of(windowsPath)
    }

    private fun runProcess(
        command: List<String>,
        workingDirectory: Path?,
        failOnError: Boolean = true,
    ): XeniaProcessResult {
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
        return XeniaProcessResult(exitCode, combined)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}

private data class WslLibraryDependency(
    val soname: String,
    val sourcePath: String,
    val installPath: String,
)

private data class PackageOwner(
    val packageName: String,
    val packageVersion: String,
)

private data class XeniaResolvedPackage(
    val packageName: String,
    val packageVersion: String,
    val archivePath: Path,
)

private data class XeniaResolvedLibrary(
    val soname: String,
    val sourcePath: String,
    val installPath: String,
    val packageName: String,
    val packageVersion: String,
)

private data class XeniaDependencyResolution(
    val libraries: List<XeniaResolvedLibrary>,
    val packages: List<XeniaResolvedPackage>,
)

private data class XeniaProcessResult(
    val exitCode: Int,
    val output: String,
)

private data class XeniaBuildConfig(
    val cliName: String,
    val outputDirectoryName: String,
)
