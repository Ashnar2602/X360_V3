import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        gamePatchesLock: XeniaGamePatchesLock,
        sourceCacheDir: Path,
        gamePatchesCacheDir: Path,
        workingRootBase: Path,
        outputRoot: Path,
        patchesRoot: Path,
        buildMode: XeniaBuildMode,
    ): XeniaRuntimeStageResult {
        deleteRecursively(outputRoot)
        outputRoot.createDirectories()

        val patchFiles = resolvePatchFiles(lock, patchesRoot)
        val vulkanSdkOverride = ensureModernVulkanSdk(sourceCacheDir.parent.resolve("vulkan-sdk-cache"))
        val workspaceKey = computeXeniaWorkspaceKey(lock, patchFiles, vulkanSdkOverride)
        val workingRoot = when (buildMode) {
            XeniaBuildMode.FULL -> workingRootBase.resolve("full-$workspaceKey-${System.nanoTime()}")
            XeniaBuildMode.INCREMENTAL -> workingRootBase.resolve(workspaceKey)
        }
        workingRoot.createDirectories()
        val checkoutDir = workingRoot.resolve("checkout")
        prepareSourceCheckout(lock, sourceCacheDir, checkoutDir, patchFiles, buildMode, workspaceKey)
        val builtArtifacts = buildXenia(lock, checkoutDir, vulkanSdkOverride, buildMode)
        val dependencyResolution = resolveGuestDependencies(builtArtifacts.executable, workingRoot.resolve("downloads"))

        stageRuntimeTree(
            builtArtifacts = builtArtifacts,
            dependencyResolution = dependencyResolution,
            outputRoot = outputRoot,
            extractionRoot = workingRoot.resolve("xenia-package-extraction"),
            lock = lock,
        )

        val gamePatchesMetadata = stageOfficialGamePatches(
            lock = gamePatchesLock,
            sourceCacheDir = gamePatchesCacheDir,
            destinationRoot = outputRoot.resolve("rootfs/tmp/x360-v3/xenia/storage/patches"),
        )

        return XeniaRuntimeStageResult(
            buildMetadata = GeneratedXeniaBuildMetadata(
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
            ),
            gamePatchesMetadata = gamePatchesMetadata,
        )
    }

    fun writeMetadata(
        metadata: GeneratedXeniaBuildMetadata,
        outputPath: Path,
    ) {
        writeUtf8(outputPath, XeniaRuntimeLockCodec.encodeMetadata(metadata))
    }

    fun writeGamePatchesMetadata(
        metadata: GeneratedXeniaGamePatchesMetadata,
        outputPath: Path,
    ) {
        writeUtf8(outputPath, XeniaRuntimeLockCodec.encodeGamePatchesMetadata(metadata))
    }

    private fun prepareSourceCheckout(
        lock: XeniaSourceBuildLock,
        sourceCacheDir: Path,
        checkoutDir: Path,
        patchFiles: List<Path>,
        buildMode: XeniaBuildMode,
        workspaceKey: String,
    ) {
        val preparedStamp = checkoutDir.parent.resolve(".x360-v3-workspace-key")
        ensureSourceCache(lock, sourceCacheDir)
        val checkoutReady = buildMode == XeniaBuildMode.INCREMENTAL &&
            checkoutDir.resolve(".git").exists() &&
            preparedStamp.exists() &&
            preparedStamp.readText() == workspaceKey
        if (checkoutReady) {
            return
        }

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
        patchFiles.forEach { patchFile ->
            runProcess(
                command = listOf(
                    "git",
                    "-C",
                    checkoutDir.toString(),
                    "apply",
                    "--whitespace=nowarn",
                    patchFile.toString(),
                ),
                workingDirectory = null,
            )
        }
        writeUtf8(preparedStamp, workspaceKey)
    }

    private fun ensureSourceCache(
        lock: XeniaSourceBuildLock,
        sourceCacheDir: Path,
    ) {
        ensureGitSourceCache(
            sourceUrl = lock.sourceUrl,
            sourceRef = lock.sourceRef,
            sourceRevision = lock.sourceRevision,
            sourceCacheDir = sourceCacheDir,
        )
    }

    private fun ensureGitSourceCache(
        sourceUrl: String,
        sourceRef: String,
        sourceRevision: String,
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
                    sourceRef,
                    "--single-branch",
                    "--filter=blob:none",
                    sourceUrl,
                    sourceCacheDir.toString(),
                ),
                workingDirectory = null,
            )
        }
        runProcess(
            command = listOf("git", "-C", sourceCacheDir.toString(), "remote", "set-url", "origin", sourceUrl),
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
                sourceRef,
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
                sourceRevision,
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
                sourceRevision,
            ),
            workingDirectory = null,
        )
    }

    private fun resolvePatchFiles(
        lock: XeniaSourceBuildLock,
        patchesRoot: Path,
    ): List<Path> {
        val patchDir = when (lock.patchSetId) {
            "none" -> patchesRoot.resolve("__none__")
            else -> patchesRoot.resolve(lock.patchSetId)
        }
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
        vulkanSdkOverride: String?,
        buildMode: XeniaBuildMode,
    ): XeniaBuiltArtifacts {
        val buildConfig = resolveBuildConfig(lock.buildProfile)
        val resultPath = checkoutDir.resolve("xenia-build-result.txt")
        val sourceDirWsl = toWslPath(checkoutDir)
        val resultPathWsl = toWslPath(resultPath)
        val setupSignature = computeXeniaSetupSignature(lock, vulkanSdkOverride)
        val setupSignatureFile = checkoutDir.resolve("build/.x360-v3-setup-signature")
        val setupSignatureFileWsl = toWslPath(setupSignatureFile)
        val vulkanSdkExports = if (vulkanSdkOverride.isNullOrBlank()) {
            ""
        } else {
            """
            export VULKAN_SDK=${shellQuote(vulkanSdkOverride)}
            export PATH="${'$'}VULKAN_SDK/bin:${'$'}PATH"
            """.trimIndent()
        }
        val configureAndBuildCommand =
            "python3 xenia-build.py build --cc clang --config ${buildConfig.cliName} " +
                "--cmake-define XENIA_BUILD_MISC=ON --target xenia-app --target xenia-content-tool"
        val buildCommand = when (buildMode) {
            XeniaBuildMode.FULL -> configureAndBuildCommand
            XeniaBuildMode.INCREMENTAL ->
                "cmake --build build --config ${buildConfig.outputDirectoryName} --target xenia-app --target xenia-content-tool"
        }
        val script = """
            set -euo pipefail
            cd ${shellQuote(sourceDirWsl)}
            find . -type f \( -name "*.py" -o -name "*.sh" -o -name "*.lua" \) -exec sed -i 's/\r$//' {} +
            git config core.autocrlf false
            git config core.eol lf
            git submodule sync --recursive
            git submodule update --init --recursive --jobs ${'$'}{X360_XENIA_SUBMODULE_JOBS:-4}
            export CC=clang
            export CXX=clang++
            export CMAKE_BUILD_PARALLEL_LEVEL=${'$'}{X360_XENIA_BUILD_JOBS:-2}
            $vulkanSdkExports
            SETUP_REQUIRED=1
            if [ -f ${shellQuote(setupSignatureFileWsl)} ] && [ ${shellQuote(setupSignature)} = "${'$'}(cat ${shellQuote(setupSignatureFileWsl)})" ]; then
              SETUP_REQUIRED=0
            fi
            if [ "${buildMode.name}" = "FULL" ]; then
              SETUP_REQUIRED=1
            fi
            if [ "${'$'}SETUP_REQUIRED" = "1" ]; then
              rm -rf build
              $configureAndBuildCommand
              printf '%s\n' ${shellQuote(setupSignature)} > ${shellQuote(setupSignatureFileWsl)}
            else
              $buildCommand
            fi
            BIN_PATH=${'$'}(find "${'$'}PWD/build/bin/Linux/${buildConfig.outputDirectoryName}" -maxdepth 1 -type f \( -name xenia_canary -o -name xenia-app \) -perm -111 | head -n 1)
            TOOL_PATH=${'$'}(find "${'$'}PWD/build/bin/Linux/${buildConfig.outputDirectoryName}" -maxdepth 1 -type f -name xenia-content-tool -perm -111 | head -n 1)
            if [ -z "${'$'}BIN_PATH" ] || [ -z "${'$'}TOOL_PATH" ]; then
                echo "Unable to locate built Xenia runtime binaries" >&2
              exit 3
            fi
            BIN_PATH=${'$'}(readlink -f "${'$'}BIN_PATH")
            TOOL_PATH=${'$'}(readlink -f "${'$'}TOOL_PATH")
            printf 'main=%s\ntool=%s\n' "${'$'}BIN_PATH" "${'$'}TOOL_PATH" > ${shellQuote(resultPathWsl)}
        """.trimIndent()
        val scriptPath = checkoutDir.resolve("build-xenia.sh")
        writeUtf8(scriptPath, "$script\n")
        runProcess(
            command = listOf("wsl", "bash", toWslPath(scriptPath)),
            workingDirectory = null,
        )
        val resultLines = resultPath.readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('=') }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        val builtBinaryWsl = resultLines["main"].orEmpty()
        val contentToolWsl = resultLines["tool"].orEmpty()
        require(builtBinaryWsl.isNotBlank()) { "Xenia build did not report an output binary" }
        require(contentToolWsl.isNotBlank()) { "Xenia build did not report the xenia-content-tool binary" }
        return XeniaBuiltArtifacts(
            executable = fromWslPath(builtBinaryWsl),
            contentTool = fromWslPath(contentToolWsl),
        )
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
                val packagedSourcePath = resolvePackagedSourcePath(dependency.sourcePath)
                val owner = resolveOwningPackage(packagedSourcePath)
                val downloadedPackage = packages.getOrPut(owner.packageName) {
                    val archivePath = downloadPackage(
                        packageName = owner.packageName,
                        preferredVersion = owner.packageVersion,
                        downloadsDir = downloadsDir,
                    )
                    XeniaResolvedPackage(
                        packageName = owner.packageName,
                        packageVersion = readArchivePackageVersion(archivePath),
                        archivePath = archivePath,
                    )
                }
                libraries[key] = XeniaResolvedLibrary(
                    soname = dependency.soname,
                    sourcePath = packagedSourcePath,
                    installPath = toGuestInstallPath(resolveGuestLibraryInstallPath(dependency)),
                    packageName = downloadedPackage.packageName,
                    packageVersion = downloadedPackage.packageVersion,
                )
                queue.addLast(packagedSourcePath)
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
        builtArtifacts: XeniaBuiltArtifacts,
        dependencyResolution: XeniaDependencyResolution,
        outputRoot: Path,
        extractionRoot: Path,
        lock: XeniaSourceBuildLock,
    ) {
        val rootfsOutput = outputRoot.resolve("rootfs")
        val xeniaBinDir = rootfsOutput.resolve("opt").resolve("x360-v3").resolve("xenia").resolve("bin")
        val xeniaContentDir = rootfsOutput.resolve("opt").resolve("x360-v3").resolve("xenia").resolve("content")
        val xeniaCacheHostDir = rootfsOutput.resolve("opt").resolve("x360-v3").resolve("xenia").resolve("cache-host")
        val xeniaCacheModulesDir = xeniaCacheHostDir.resolve("modules")
        val xeniaCacheShadersShareableDir = xeniaCacheHostDir.resolve("shaders").resolve("shareable")
        val xeniaLogsDir = xeniaBinDir.resolve("logs")
        val xeniaCacheDir = xeniaBinDir.resolve("cache")
        val xeniaCacheSlot0Dir = xeniaBinDir.resolve("cache0")
        val xeniaCacheSlot1Dir = xeniaBinDir.resolve("cache1")
        val xeniaScratchDir = xeniaBinDir.resolve("scratch")
        val xeniaWritableRoot = rootfsOutput.resolve("tmp").resolve("x360-v3").resolve("xenia")
        val xeniaWritableContentDir = xeniaWritableRoot.resolve("content")
        val xeniaWritableCacheHostDir = xeniaWritableRoot.resolve("cache-host")
        val xeniaWritableCacheModulesDir = xeniaWritableCacheHostDir.resolve("modules")
        val xeniaWritableCacheShadersShareableDir = xeniaWritableCacheHostDir.resolve("shaders").resolve("shareable")
        val xeniaWritableStorageDir = xeniaWritableRoot.resolve("storage")
        xeniaBinDir.createDirectories()
        xeniaContentDir.createDirectories()
        xeniaCacheHostDir.createDirectories()
        xeniaCacheModulesDir.createDirectories()
        xeniaCacheShadersShareableDir.createDirectories()
        xeniaLogsDir.createDirectories()
        xeniaCacheDir.createDirectories()
        xeniaCacheSlot0Dir.createDirectories()
        xeniaCacheSlot1Dir.createDirectories()
        xeniaScratchDir.createDirectories()
        xeniaWritableRoot.createDirectories()
        xeniaWritableContentDir.createDirectories()
        xeniaWritableCacheHostDir.createDirectories()
        xeniaWritableCacheModulesDir.createDirectories()
        xeniaWritableCacheShadersShareableDir.createDirectories()
        xeniaWritableStorageDir.createDirectories()

        Files.copy(builtArtifacts.executable, xeniaBinDir.resolve("xenia-canary"), StandardCopyOption.REPLACE_EXISTING)
        xeniaBinDir.resolve("xenia-canary").toFile().setExecutable(true, false)
        Files.copy(builtArtifacts.contentTool, xeniaBinDir.resolve("xenia-content-tool"), StandardCopyOption.REPLACE_EXISTING)
        xeniaBinDir.resolve("xenia-content-tool").toFile().setExecutable(true, false)
        writeUtf8(xeniaBinDir.resolve("portable.txt"), "portable-mode\n")
        writeUtf8(
            xeniaBinDir.resolve("xenia-canary.config.toml"),
            buildXeniaConfig(),
        )

        val archives = dependencyResolution.packages.associate { pkg -> pkg.packageName to pkg.archivePath }
        val extractedPackages = guestPackageAssembler.extractPackages(
            archives = archives,
            extractionDir = extractionRoot,
        )
        dependencyResolution.libraries.forEach { library ->
            val packageRoot = extractedPackages[library.packageName]
                ?: error("Missing extracted package for ${library.packageName}")
            val source = resolveExtractedLibrarySource(packageRoot, library)
            val destination = outputRoot.resolve(library.installPath).normalize()
            destination.parent?.createDirectories()
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            destination.toFile().setExecutable(true, false)
        }

        writeUtf8(
            rootfsOutput.resolve("usr").resolve("share").resolve("x360-v3").resolve("xenia-bringup.txt"),
            "Phase 4A Xenia bring-up runtime staged from a pinned source build\n",
        )
    }

    private fun stageOfficialGamePatches(
        lock: XeniaGamePatchesLock,
        sourceCacheDir: Path,
        destinationRoot: Path,
    ): GeneratedXeniaGamePatchesMetadata {
        ensureGitSourceCache(
            sourceUrl = lock.sourceUrl,
            sourceRef = lock.sourceRef,
            sourceRevision = lock.sourceRevision,
            sourceCacheDir = sourceCacheDir,
        )
        val sourcePatchesRoot = sourceCacheDir.resolve("patches")
        deleteRecursively(destinationRoot)
        copyTree(sourcePatchesRoot, destinationRoot)
        val files = if (destinationRoot.exists()) {
            Files.walk(destinationRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .sorted()
                    .map { file ->
                        GeneratedXeniaGamePatchFile(
                            relativePath = destinationRoot.relativize(file).toString().replace('\\', '/'),
                            sha256 = sha256(file),
                        )
                    }
                    .toList()
            }
        } else {
            emptyList()
        }
        val titleCount = files
            .mapNotNull { file ->
                val raw = runCatching { destinationRoot.resolve(file.relativePath).readText() }.getOrNull() ?: return@mapNotNull null
                Regex("title_id\\s*=\\s*\"([0-9A-Fa-f]{8})\"")
                    .find(raw)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.uppercase()
            }
            .distinct()
            .size
        return GeneratedXeniaGamePatchesMetadata(
            sourceUrl = lock.sourceUrl,
            sourceRef = lock.sourceRef,
            sourceRevision = lock.sourceRevision,
            fileCount = files.size,
            titleCount = titleCount,
            files = files,
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
            mount_cache = false
            mount_memory_unit = false
            mount_scratch = false
            content_root = "/tmp/x360-v3/xenia/content"
            cache_root = "/tmp/x360-v3/xenia/cache-host"
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
        val ownerRaw = queryOwningPackage(sourcePathWsl)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
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

    private fun resolvePackagedSourcePath(
        sourcePathWsl: String,
    ): String {
        val candidates = linkedSetOf(sourcePathWsl)
        if (sourcePathWsl.startsWith("/usr/lib/")) {
            candidates += "/lib/${sourcePathWsl.removePrefix("/usr/lib/")}"
        }
        if (sourcePathWsl.startsWith("/usr/lib64/")) {
            candidates += "/lib64/${sourcePathWsl.removePrefix("/usr/lib64/")}"
        }
        if (sourcePathWsl.startsWith("/usr/libexec/")) {
            candidates += "/libexec/${sourcePathWsl.removePrefix("/usr/libexec/")}"
        }
        if (sourcePathWsl.startsWith("/lib/")) {
            candidates += "/usr${sourcePathWsl}"
        }
        if (sourcePathWsl.startsWith("/lib64/")) {
            candidates += "/usr${sourcePathWsl}"
        }
        return candidates.firstOrNull { queryOwningPackage(it, failOnError = false).isNotBlank() }
            ?: sourcePathWsl
    }

    private fun queryOwningPackage(
        sourcePathWsl: String,
        failOnError: Boolean = true,
    ): String {
        val result = runProcess(
            command = listOf("wsl", "bash", "-lc", "dpkg-query -S ${shellQuote(sourcePathWsl)} 2>/dev/null"),
            workingDirectory = null,
            failOnError = failOnError,
        )
        if (!failOnError && result.exitCode != 0) {
            return ""
        }
        return result.output.trim()
    }

    private fun downloadPackage(
        packageName: String,
        preferredVersion: String,
        downloadsDir: Path,
    ): Path {
        val packageDownloadDir = downloadsDir.resolve(sanitizePackageDirectoryName(packageName, preferredVersion))
        val specs = listOf("$packageName=$preferredVersion", packageName).distinct()
        var lastFailure: String? = null

        for (spec in specs) {
            deleteRecursively(packageDownloadDir)
            packageDownloadDir.createDirectories()
            val downloadsDirWsl = toWslPath(packageDownloadDir)
            val downloadCommand =
                "cd ${shellQuote(downloadsDirWsl)} && " +
                    "apt download ${shellQuote(spec)} >/dev/null && " +
                    "find . -maxdepth 1 -type f -name '*.deb' -printf '%f\\n' | sort | sed '/^$/d'"
            val result = runProcess(
                command = listOf("wsl", "bash", "-lc", downloadCommand),
                workingDirectory = null,
                failOnError = false,
            )
            val downloadedFiles = result.output.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".deb") }
                .toList()
            if (result.exitCode == 0 && downloadedFiles.size == 1) {
                val archivePath = packageDownloadDir.resolve(downloadedFiles.single())
                require(archivePath.exists()) {
                    "Expected downloaded package missing: $archivePath"
                }
                return archivePath
            }
            lastFailure = buildString {
                append("spec=$spec exit=${result.exitCode}")
                if (downloadedFiles.isNotEmpty()) {
                    append(" files=${downloadedFiles.joinToString()}")
                }
                val trimmedOutput = result.output.trim()
                if (trimmedOutput.isNotBlank()) {
                    append(" output=$trimmedOutput")
                }
            }
        }
        error("Unable to download guest package $packageName. Last failure: $lastFailure")
    }

    private fun readArchivePackageVersion(
        archivePath: Path,
    ): String {
        val packageVersion = runProcess(
            command = listOf(
                "wsl",
                "bash",
                "-lc",
                "dpkg-deb -f ${shellQuote(toWslPath(archivePath))} Version",
            ),
            workingDirectory = null,
        ).output.trim()
        require(packageVersion.isNotBlank()) {
            "Unable to read package version from ${archivePath.fileName}"
        }
        return packageVersion
    }

    private fun sanitizePackageDirectoryName(
        packageName: String,
        packageVersion: String = "candidate",
    ): String {
        fun sanitize(value: String): String = value.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return "${sanitize(packageName)}__${sanitize(packageVersion)}"
    }

    private fun resolveGuestLibraryInstallPath(
        dependency: WslLibraryDependency,
    ): String {
        val directory = dependency.installPath.substringBeforeLast('/', "")
        return if (directory.isBlank()) {
            "/${dependency.soname}"
        } else {
            "$directory/${dependency.soname}"
        }
    }

    private fun resolveExtractedLibrarySource(
        packageRoot: Path,
        library: XeniaResolvedLibrary,
    ): Path {
        val exactSource = packageRoot.resolve(library.sourcePath.removePrefix("/")).normalize()
        if (exactSource.exists()) {
            return exactSource
        }

        val sourceRelativePath = library.sourcePath.removePrefix("/")
        val sourceDirectory = sourceRelativePath.substringBeforeLast('/', "")
        val candidateDirectories = linkedSetOf(sourceDirectory).apply {
            if (sourceDirectory.startsWith("lib/")) {
                add("usr/$sourceDirectory")
            }
            if (sourceDirectory.startsWith("lib64/")) {
                add("usr/$sourceDirectory")
            }
            if (sourceDirectory.startsWith("usr/lib/")) {
                add(sourceDirectory.removePrefix("usr/"))
            }
            if (sourceDirectory.startsWith("usr/lib64/")) {
                add(sourceDirectory.removePrefix("usr/"))
            }
        }
        candidateDirectories.forEach { relativeDirectory ->
            val candidateDirectory = if (relativeDirectory.isBlank()) {
                packageRoot
            } else {
                packageRoot.resolve(relativeDirectory).normalize()
            }
            if (!candidateDirectory.exists()) {
                return@forEach
            }
            val sonamePath = candidateDirectory.resolve(library.soname)
            if (sonamePath.exists()) {
                return sonamePath
            }
            val fallbackMatch = Files.list(candidateDirectory).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().startsWith("${library.soname}.") }
                    .sorted()
                    .findFirst()
                    .orElse(null)
            }
            if (fallbackMatch != null) {
                return fallbackMatch
            }
        }

        error("Missing extracted Xenia runtime library ${library.sourcePath} from ${library.packageName}")
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

private data class XeniaBuiltArtifacts(
    val executable: Path,
    val contentTool: Path,
)

data class XeniaRuntimeStageResult(
    val buildMetadata: GeneratedXeniaBuildMetadata,
    val gamePatchesMetadata: GeneratedXeniaGamePatchesMetadata,
)
