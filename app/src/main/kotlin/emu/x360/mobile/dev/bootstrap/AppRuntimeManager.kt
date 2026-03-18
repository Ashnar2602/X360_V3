package emu.x360.mobile.dev.bootstrap

import android.content.Context
import emu.x360.mobile.dev.nativebridge.NativeBridge
import emu.x360.mobile.dev.runtime.ExitClassification
import emu.x360.mobile.dev.runtime.FexBuildMetadata
import emu.x360.mobile.dev.runtime.FexBuildMetadataCodec
import emu.x360.mobile.dev.runtime.GuestRuntimeMetadata
import emu.x360.mobile.dev.runtime.GuestRuntimeMetadataCodec
import emu.x360.mobile.dev.runtime.GuestLaunchRequest
import emu.x360.mobile.dev.runtime.GuestLaunchResult
import emu.x360.mobile.dev.runtime.GuestLogDestinations
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.MesaRuntimeMetadata
import emu.x360.mobile.dev.runtime.MesaRuntimeMetadataCodec
import emu.x360.mobile.dev.runtime.RuntimeAssetSource
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.RuntimeInstallState
import emu.x360.mobile.dev.runtime.RuntimeInstaller
import emu.x360.mobile.dev.runtime.RuntimeManifest
import emu.x360.mobile.dev.runtime.RuntimeManifestCodec
import emu.x360.mobile.dev.runtime.RuntimePhase
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.outputStream
import kotlin.io.path.readText

class AppRuntimeManager(
    private val context: Context,
    private val baseDir: Path = context.filesDir.toPath(),
) {
    private val targetPhase = RuntimePhase.TURNIP_BASELINE
    private val directories = RuntimeDirectories(baseDir)
    private val installer = RuntimeInstaller(directories)
    private val manifestLoader = RuntimeAssetManifestLoader(context)
    private val assetSource = AndroidRuntimeAssetSource(context)
    private val logStore = SessionLogStore(directories)
    private val metadataLoader = FexMetadataLoader(assetSource, directories)
    private val guestRuntimeMetadataLoader = GuestRuntimeMetadataLoader(assetSource, directories)
    private val mesaRuntimeMetadataLoader = MesaRuntimeMetadataLoader(assetSource, directories)
    private val mesaOverrideStore = MesaRuntimeOverrideStore(context)
    private val stubGuestLauncher = StubGuestLauncher(logStore)
    private val fexGuestLauncher = FexGuestLauncher(context, directories, logStore)

    fun snapshot(
        lastAction: String = "Ready",
        installStateOverride: RuntimeInstallState? = null,
    ): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installStateOverride ?: installer.inspect(manifest, targetPhase)
        val metadata = metadataLoader.load()
        val guestRuntimeMetadata = guestRuntimeMetadataLoader.load()
        val mesaRuntimeMetadata = mesaRuntimeMetadataLoader.load()
        val latestLogs = logStore.latestLogs()
        val hostArtifacts = HostArtifactsStatus.from(context)
        val deviceProperties = DeviceProperties.read()
        val overrideMode = mesaOverrideStore.read()
        val resolvedMesaRuntime = DeviceMesaRuntimePolicy.resolve(overrideMode, deviceProperties)
        val kgslAccess = KgslAccessInspector.inspect()
        return RuntimeSnapshot(
            manifest = manifest,
            installState = installState,
            directories = directories,
            nativeHealth = runCatching { NativeBridge.healthCheck() }.getOrElse { "native-bridge:error:${it.message}" },
            surfaceHookReservation = runCatching {
                NativeBridge.describeSurfaceHookPlaceholder(directories.rootfsTmp.toString())
            }.getOrElse { "surface-hook:error:${it.message}" },
            latestLogs = latestLogs,
            outputPreview = OutputPreviewState.from(directories.rootfsTmp.resolve("xenia_fb")),
            fexDiagnostics = buildFexDiagnostics(
                metadata = metadata,
                guestRuntimeMetadata = guestRuntimeMetadata,
                mesaRuntimeMetadata = mesaRuntimeMetadata,
                installState = installState,
                latestLogs = latestLogs,
                hostArtifacts = hostArtifacts,
                resolvedMesaRuntime = resolvedMesaRuntime,
                overrideMode = overrideMode,
                kgslAccess = kgslAccess,
            ),
            lastAction = lastAction,
        )
    }

    fun install(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.install(manifest, assetSource, targetPhase)
        val bootstrapNote = if (installState is RuntimeInstallState.Installed) {
            runCatching { NativeBridge.bootstrapStub(baseDir.toString()) }
                .getOrElse { "native-bootstrap:error:${it.message}" }
        } else {
            "runtime-install:${installState::class.simpleName}"
        }
        return snapshot(
            lastAction = "Install phase ${targetPhase.name.lowercase()} completed: $bootstrapNote",
            installStateOverride = installState,
        )
    }

    fun launchFexHello(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, targetPhase)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: FEX baseline runtime is not installed")
        }

        val request = createRequest(
            sessionId = SessionIdFactory.create(),
            executable = directories.helloFixture.toString(),
            args = listOf("--sentinel=tmp/fex_hello_ok.json"),
            environment = buildHeadlessGuestEnvironment(),
        )
        val result = fexGuestLauncher.launch(request)
        return snapshot(
            lastAction = "FEX hello ${result.exitClassification.name.lowercase()} for session ${result.sessionId}",
        )
    }

    fun launchDynamicHello(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, targetPhase)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: Turnip baseline runtime is not installed")
        }

        val request = createRequest(
            sessionId = SessionIdFactory.create(),
            executable = directories.dynamicHelloFixture.toString(),
            args = listOf("--sentinel=tmp/fex_dyn_hello_ok.json"),
            environment = buildDynamicGuestEnvironment(),
        )
        val result = fexGuestLauncher.launch(request)
        return snapshot(
            lastAction = "Dynamic hello ${result.exitClassification.name.lowercase()} for session ${result.sessionId}",
        )
    }

    fun launchTurnipProbe(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, targetPhase)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: Turnip baseline runtime is not installed")
        }

        val resolvedMesaRuntime = DeviceMesaRuntimePolicy.resolve(
            overrideMode = mesaOverrideStore.read(),
            properties = DeviceProperties.read(),
        )
        if (resolvedMesaRuntime.branch == MesaRuntimeBranch.LAVAPIPE) {
            return snapshot(lastAction = "Turnip probe unavailable: selection resolved to lavapipe (${resolvedMesaRuntime.reason})")
        }

        val kgslAccess = KgslAccessInspector.inspect()
        val request = createRequest(
            sessionId = SessionIdFactory.create(),
            executable = directories.vulkanProbeFixture.toString(),
            args = listOf("--sentinel=tmp/fex_vulkan_probe.json"),
            environment = buildVulkanGuestEnvironment(resolvedMesaRuntime.branch, directories),
        )
        if (!kgslAccess.accessible) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.VALIDATION_ERROR,
                exitCode = null,
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                detail = "KGSL preflight failed for ${kgslAccess.devicePath}: ${kgslAccess.detail}",
            )
            logStore.writeLaunchFailure(request, fexGuestLauncher.backendName, result)
            return snapshot(
                lastAction = "Turnip probe validation_error for session ${request.sessionId}",
            )
        }

        val result = fexGuestLauncher.launch(request)
        return snapshot(
            lastAction = "Turnip probe ${result.exitClassification.name.lowercase()} for session ${result.sessionId}",
        )
    }

    fun launchLavapipeProbe(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, targetPhase)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: Turnip baseline runtime is not installed")
        }

        val request = createRequest(
            sessionId = SessionIdFactory.create(),
            executable = directories.vulkanProbeFixture.toString(),
            args = listOf("--sentinel=tmp/fex_vulkan_probe.json"),
            environment = buildVulkanGuestEnvironment(MesaRuntimeBranch.LAVAPIPE, directories),
        )
        val result = fexGuestLauncher.launch(request)
        return snapshot(
            lastAction = "Lavapipe probe ${result.exitClassification.name.lowercase()} for session ${result.sessionId}",
        )
    }

    fun launchVulkanProbe(): RuntimeSnapshot {
        return launchTurnipProbe()
    }

    fun setMesaOverride(
        overrideMode: MesaRuntimeBranch,
    ): RuntimeSnapshot {
        mesaOverrideStore.write(overrideMode)
        return snapshot(
            lastAction = "Mesa override set to ${overrideMode.name.lowercase()}",
        )
    }

    fun launchStub(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, RuntimePhase.BOOTSTRAP)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: bootstrap runtime is not installed")
        }

        val request = createRequest(
            sessionId = SessionIdFactory.create(),
            executable = directories.payloadBin.resolve("stub_guest.sh").toString(),
            args = listOf("--phase=bootstrap", "--profile=${manifest.profile}", "--mode=stub"),
            environment = buildHeadlessGuestEnvironment(),
        )
        val result = stubGuestLauncher.launch(request)
        return snapshot(
            lastAction = "Stub launch ${result.exitClassification.name.lowercase()} for session ${result.sessionId}",
        )
    }

    private fun createRequest(
        sessionId: String,
        executable: String,
        args: List<String>,
        environment: Map<String, String>,
    ): GuestLaunchRequest {
        val logs = logStore.createSession(sessionId)
        return GuestLaunchRequest(
            sessionId = sessionId,
            executable = executable,
            args = args,
            environment = environment,
            workingDirectory = directories.rootfs.toString(),
            logDestinations = GuestLogDestinations(
                appLog = logs.appLog,
                fexLog = logs.fexLog,
                guestLog = logs.guestLog,
            ),
        )
    }

    private fun buildFexDiagnostics(
        metadata: FexBuildMetadata?,
        guestRuntimeMetadata: GuestRuntimeMetadata?,
        mesaRuntimeMetadata: MesaRuntimeMetadata?,
        installState: RuntimeInstallState,
        latestLogs: LatestSessionLogs,
        hostArtifacts: HostArtifactsStatus,
        resolvedMesaRuntime: ResolvedMesaRuntime,
        overrideMode: MesaRuntimeBranch,
        kgslAccess: KgslAccessStatus,
    ): FexDiagnostics {
        val lastProbeDeviceName = latestLogs.guestLog.lineSequence()
            .firstOrNull { it.startsWith("device[0]=") }
            ?.substringAfter('=')
            ?: "none"
        val lastProbeDriverMode = latestLogs.guestLog.lineSequence()
            .firstOrNull { it.startsWith("driver_mode=") }
            ?.substringAfter('=')
            ?: "none"
        val activeIcd = when (resolvedMesaRuntime.branch) {
            MesaRuntimeBranch.AUTO -> "unresolved"
            MesaRuntimeBranch.LAVAPIPE -> "/usr/share/vulkan/icd.d/lvp_icd.json"
            MesaRuntimeBranch.MESA25 -> "/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json"
            MesaRuntimeBranch.MESA26 -> "/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json"
        }
        val selectedMesaMetadata = when (resolvedMesaRuntime.branch) {
            MesaRuntimeBranch.MESA25 -> mesaRuntimeMetadata?.bundles?.firstOrNull { it.branch == "mesa25" }
            MesaRuntimeBranch.MESA26 -> mesaRuntimeMetadata?.bundles?.firstOrNull { it.branch == "mesa26" }
            else -> null
        }
        return FexDiagnostics(
            commit = metadata?.fexCommit ?: "unavailable",
            patchSetId = metadata?.patchSetId ?: "unavailable",
            hostArtifactsPackaged = hostArtifacts.packaged,
            loaderPath = hostArtifacts.loaderPath,
            corePath = hostArtifacts.corePath,
            rootfsInstalled = installState is RuntimeInstallState.Installed && directories.rootfs.exists(),
            helloFixtureInstalled = directories.helloFixture.exists(),
            dynamicHelloInstalled = directories.dynamicHelloFixture.exists(),
            vulkanProbeInstalled = directories.vulkanProbeFixture.exists(),
            configPresent = directories.fexConfigFile.exists(),
            guestRuntimeProfile = guestRuntimeMetadata?.profile ?: "unavailable",
            guestRuntimeProvenance = guestRuntimeMetadata?.let {
                "${it.distribution} ${it.release} ${it.architecture}"
            } ?: "unavailable",
            mesaRuntimeProfile = mesaRuntimeMetadata?.profile ?: "unavailable",
            mesaPatchSetId = selectedMesaMetadata?.patchSetId ?: "none",
            mesaAppliedPatches = selectedMesaMetadata?.appliedPatches?.joinToString(", ").orEmpty().ifBlank { "none" },
            glibcLoaderPresent = directories.glibcLoader.exists(),
            guestVulkanLoaderPresent = directories.guestVulkanLoader.exists(),
            guestLavapipeDriverPresent = directories.guestLavapipeDriver.exists(),
            activeIcd = activeIcd,
            lvpIcdPresent = directories.guestLavapipeIcd.exists(),
            mesa25Installed = directories.mesa25TurnipDriver.exists() && directories.mesa25TurnipIcd.exists(),
            mesa26Installed = directories.mesa26TurnipDriver.exists() && directories.mesa26TurnipIcd.exists(),
            kgslAccessible = kgslAccess.accessible,
            kgslDetail = "${kgslAccess.devicePath}: ${kgslAccess.detail}",
            selectedMesaBranch = resolvedMesaRuntime.branch.name.lowercase(),
            overrideMode = overrideMode.name.lowercase(),
            selectionReason = resolvedMesaRuntime.reason,
            lastProbeDeviceName = lastProbeDeviceName,
            lastProbeDriverMode = lastProbeDriverMode,
            lastLaunchBackend = latestLogs.backend ?: "none",
            lastLaunchResult = latestLogs.resultSummary ?: "none",
        )
    }
}

data class RuntimeSnapshot(
    val manifest: RuntimeManifest,
    val installState: RuntimeInstallState,
    val directories: RuntimeDirectories,
    val nativeHealth: String,
    val surfaceHookReservation: String,
    val latestLogs: LatestSessionLogs,
    val outputPreview: OutputPreviewState,
    val fexDiagnostics: FexDiagnostics,
    val lastAction: String,
)

data class LatestSessionLogs(
    val sessionId: String?,
    val appLog: String,
    val fexLog: String,
    val guestLog: String,
    val backend: String?,
    val resultSummary: String?,
)

data class FexDiagnostics(
    val commit: String,
    val patchSetId: String,
    val hostArtifactsPackaged: Boolean,
    val loaderPath: String,
    val corePath: String,
    val rootfsInstalled: Boolean,
    val helloFixtureInstalled: Boolean,
    val dynamicHelloInstalled: Boolean,
    val vulkanProbeInstalled: Boolean,
    val configPresent: Boolean,
    val guestRuntimeProfile: String,
    val guestRuntimeProvenance: String,
    val mesaRuntimeProfile: String,
    val mesaPatchSetId: String,
    val mesaAppliedPatches: String,
    val glibcLoaderPresent: Boolean,
    val guestVulkanLoaderPresent: Boolean,
    val guestLavapipeDriverPresent: Boolean,
    val activeIcd: String,
    val lvpIcdPresent: Boolean,
    val mesa25Installed: Boolean,
    val mesa26Installed: Boolean,
    val kgslAccessible: Boolean,
    val kgslDetail: String,
    val selectedMesaBranch: String,
    val overrideMode: String,
    val selectionReason: String,
    val lastProbeDeviceName: String,
    val lastProbeDriverMode: String,
    val lastLaunchBackend: String,
    val lastLaunchResult: String,
)

data class OutputPreviewState(
    val framebufferPath: String,
    val exists: Boolean,
    val summary: String,
) {
    companion object {
        fun from(framebufferPath: Path): OutputPreviewState {
            return if (framebufferPath.exists()) {
                OutputPreviewState(
                    framebufferPath = framebufferPath.toString(),
                    exists = true,
                    summary = "reserved file detected, size=${Files.size(framebufferPath)} bytes, modified=${framebufferPath.getLastModifiedTime()}",
                )
            } else {
                OutputPreviewState(
                    framebufferPath = framebufferPath.toString(),
                    exists = false,
                    summary = "placeholder only, waiting for future /tmp/xenia_fb polling path",
                )
            }
        }
    }
}

private class RuntimeAssetManifestLoader(
    private val context: Context,
) {
    fun load(): RuntimeManifest {
        val raw = context.assets.open(MANIFEST_ASSET_PATH).bufferedReader().use { it.readText() }
        return RuntimeManifestCodec.decode(raw)
    }

    companion object {
        const val MANIFEST_ASSET_PATH = "runtime-payload/runtime-manifest.json"
    }
}

private class AndroidRuntimeAssetSource(
    private val context: Context,
) : RuntimeAssetSource {
    override fun open(assetPath: String): InputStream? {
        return try {
            context.assets.open(assetPath)
        } catch (_: FileNotFoundException) {
            null
        }
    }
}

private class FexMetadataLoader(
    private val assetSource: RuntimeAssetSource,
    private val directories: RuntimeDirectories,
) {
    fun load(): FexBuildMetadata? {
        val installedPath = directories.payloadConfig.resolve("fex-build-metadata.json")
        if (installedPath.exists()) {
            return runCatching { FexBuildMetadataCodec.decode(installedPath.readText()) }.getOrNull()
        }

        val assetStream = assetSource.open(FEX_METADATA_ASSET_PATH) ?: return null
        return assetStream.bufferedReader().use { reader ->
            runCatching { FexBuildMetadataCodec.decode(reader.readText()) }.getOrNull()
        }
    }

    companion object {
        const val FEX_METADATA_ASSET_PATH = "runtime-payload/files/payload/config/fex-build-metadata.json"
    }
}

private class GuestRuntimeMetadataLoader(
    private val assetSource: RuntimeAssetSource,
    private val directories: RuntimeDirectories,
) {
    fun load(): GuestRuntimeMetadata? {
        if (directories.guestRuntimeMetadata.exists()) {
            return runCatching { GuestRuntimeMetadataCodec.decode(directories.guestRuntimeMetadata.readText()) }.getOrNull()
        }

        val assetStream = assetSource.open(GUEST_RUNTIME_METADATA_ASSET_PATH) ?: return null
        return assetStream.bufferedReader().use { reader ->
            runCatching { GuestRuntimeMetadataCodec.decode(reader.readText()) }.getOrNull()
        }
    }

    companion object {
        const val GUEST_RUNTIME_METADATA_ASSET_PATH = "runtime-payload/files/payload/config/guest-runtime-metadata.json"
    }
}

private class MesaRuntimeMetadataLoader(
    private val assetSource: RuntimeAssetSource,
    private val directories: RuntimeDirectories,
) {
    fun load(): MesaRuntimeMetadata? {
        if (directories.mesaRuntimeMetadata.exists()) {
            return runCatching { MesaRuntimeMetadataCodec.decode(directories.mesaRuntimeMetadata.readText()) }.getOrNull()
        }

        val assetStream = assetSource.open(MESA_RUNTIME_METADATA_ASSET_PATH) ?: return null
        return assetStream.bufferedReader().use { reader ->
            runCatching { MesaRuntimeMetadataCodec.decode(reader.readText()) }.getOrNull()
        }
    }

    companion object {
        const val MESA_RUNTIME_METADATA_ASSET_PATH = "runtime-payload/files/payload/config/mesa-runtime-metadata.json"
    }
}

private interface GuestLauncher {
    val backendName: String

    fun launch(request: GuestLaunchRequest): GuestLaunchResult
}

private class StubGuestLauncher(
    private val logStore: SessionLogStore,
) : GuestLauncher {
    override val backendName: String = "stub"

    override fun launch(request: GuestLaunchRequest): GuestLaunchResult {
        val startedAt = Instant.now()
        val workingDirectory = File(request.workingDirectory).toPath()
        val executable = File(request.executable).toPath()

        if (!Files.exists(workingDirectory) || !Files.isDirectory(workingDirectory)) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.VALIDATION_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "Working directory missing: ${request.workingDirectory}",
            )
            logStore.writeStubFailure(request, backendName, result)
            return result
        }

        if (!Files.exists(executable)) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.VALIDATION_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "Executable missing: ${request.executable}",
            )
            logStore.writeStubFailure(request, backendName, result)
            return result
        }

        val result = GuestLaunchResult(
            sessionId = request.sessionId,
            exitClassification = ExitClassification.SUCCESS,
            exitCode = 0,
            startedAt = startedAt,
            finishedAt = Instant.now(),
            detail = "Stub guest completed successfully",
        )
        logStore.writeStubSuccess(request, backendName, result)
        return result
    }
}

private class FexGuestLauncher(
    private val context: Context,
    private val directories: RuntimeDirectories,
    private val logStore: SessionLogStore,
) : GuestLauncher {
    override val backendName: String = "fex"

    override fun launch(request: GuestLaunchRequest): GuestLaunchResult {
        val startedAt = Instant.now()
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir).toPath()
        val loaderPath = nativeLibraryDir.resolve("libFEXLoader.so")
        val corePath = nativeLibraryDir.resolve("libFEXCore.so")
        val guestExecutable = File(request.executable).toPath()
        val expectedSentinel = expectedSentinelPath(request)

        val missingArtifact = when {
            !loaderPath.exists() -> loaderPath
            !corePath.exists() -> corePath
            !guestExecutable.exists() -> guestExecutable
            else -> null
        }
        if (missingArtifact != null) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.VALIDATION_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "Missing required artifact: $missingArtifact",
            )
            logStore.writeLaunchFailure(request, backendName, result)
            return result
        }

        directories.fexEmuHome.createDirectories()
        directories.fexConfigFile.parent?.createDirectories()
        directories.rootfsTmp.createDirectories()
        Files.deleteIfExists(expectedSentinel)
        directories.fexConfigFile.toFile().writeText(buildFexConfig(directories))

        logStore.writeFexPreamble(
            request = request,
            backend = backendName,
            loaderPath = loaderPath,
            configPath = directories.fexConfigFile,
            homePath = directories.baseDir,
        )
        logStore.writeGuestPreamble(request, backendName)

        val launchSpec = buildFexLaunchSpec(loaderPath, directories, request)
        val processBuilder = ProcessBuilder(launchSpec.command)
            .directory(File(request.workingDirectory))
            .redirectInput(ProcessBuilder.Redirect.PIPE)

        val environment = processBuilder.environment()
        launchSpec.environment.forEach { (key, value) -> environment[key] = value }

        val process = try {
            processBuilder.start()
        } catch (throwable: Throwable) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.PROCESS_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "Failed to start FEX loader: ${throwable.message}",
            )
            logStore.writeLaunchFailure(request, backendName, result)
            return result
        }

        val stdoutPump = logStore.pump(process.inputStream, request.logDestinations.guestLog)
        val stderrPump = logStore.pump(process.errorStream, request.logDestinations.fexLog)
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutPump.join()
            stderrPump.join()
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.PROCESS_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "FEX guest timed out after 20 seconds",
            )
            logStore.writeLaunchFailure(request, backendName, result)
            return result
        }

        stdoutPump.join()
        stderrPump.join()

        val exitCode = process.exitValue()
        val detail = when {
            exitCode != 0 -> "FEX guest exited with code $exitCode"
            !expectedSentinel.exists() -> "FEX guest exited successfully but did not create $expectedSentinel"
            else -> "FEX guest completed successfully"
        }
        val classification = when {
            exitCode == 0 && expectedSentinel.exists() -> ExitClassification.SUCCESS
            exitCode == 0 -> ExitClassification.VALIDATION_ERROR
            else -> ExitClassification.PROCESS_ERROR
        }
        val result = GuestLaunchResult(
            sessionId = request.sessionId,
            exitClassification = classification,
            exitCode = exitCode,
            startedAt = startedAt,
            finishedAt = Instant.now(),
            detail = detail,
        )
        logStore.writeLaunchResult(request, backendName, result)
        return result
    }

    private fun expectedSentinelPath(request: GuestLaunchRequest): Path {
        val sentinelArg = request.args.firstOrNull { it.startsWith("--sentinel=") }
            ?.substringAfter('=')
            ?: return directories.fexHelloSentinel
        return when {
            sentinelArg.startsWith("/") -> directories.rootfs.resolve(sentinelArg.removePrefix("/")).normalize()
            else -> directories.rootfs.resolve(sentinelArg).normalize()
        }
    }
}

private class SessionLogStore(
    private val directories: RuntimeDirectories,
) {
    fun createSession(sessionId: String): SessionLogs {
        directories.requiredDirectories().forEach { Files.createDirectories(it) }
        return SessionLogs(
            sessionId = sessionId,
            appLog = directories.appLogs.resolve("session-$sessionId.app.log"),
            fexLog = directories.fexLogs.resolve("session-$sessionId.fex.log"),
            guestLog = directories.guestLogs.resolve("session-$sessionId.guest.log"),
        )
    }

    fun latestLogs(): LatestSessionLogs {
        val latestApp = latestLogFile(directories.appLogs)
        val latestFex = latestLogFile(directories.fexLogs)
        val latestGuest = latestLogFile(directories.guestLogs)
        val appLog = latestApp?.readText().orEmpty()
        val appFields = parseKeyValueLines(appLog)
        return LatestSessionLogs(
            sessionId = latestApp?.fileName?.toString()?.substringAfter("session-")?.substringBefore("."),
            appLog = appLog,
            fexLog = latestFex?.readText().orEmpty(),
            guestLog = latestGuest?.readText().orEmpty(),
            backend = appFields["backend"],
            resultSummary = appFields["detail"]?.let { detail ->
                buildString {
                    append(appFields["exitClassification"] ?: "unknown")
                    append(": ")
                    append(detail)
                }
            },
        )
    }

    fun writeStubSuccess(request: GuestLaunchRequest, backend: String, result: GuestLaunchResult) {
        writeAppSummary(request, backend, result)
        overwrite(
            request.logDestinations.fexLog,
            buildList {
                add("session=${request.sessionId}")
                add("layer=fex")
                add("backend=$backend")
                add("message=FEX not linked for this path; translated guest flow is simulated")
                request.environment.forEach { (key, value) -> add("env.$key=$value") }
            },
        )
        overwrite(
            request.logDestinations.guestLog,
            listOf(
                "session=${request.sessionId}",
                "layer=guest",
                "backend=$backend",
                "executable=${request.executable}",
                "args=${request.args.joinToString(" ")}",
                "result=${result.detail}",
                "exitCode=${result.exitCode}",
            ),
        )
    }

    fun writeStubFailure(request: GuestLaunchRequest, backend: String, result: GuestLaunchResult) {
        writeLaunchFailure(request, backend, result)
    }

    fun writeFexPreamble(
        request: GuestLaunchRequest,
        backend: String,
        loaderPath: Path,
        configPath: Path,
        homePath: Path,
    ) {
        overwrite(
            request.logDestinations.fexLog,
            buildList {
                add("session=${request.sessionId}")
                add("layer=fex")
                add("backend=$backend")
                add("loader=$loaderPath")
                add("config=$configPath")
                add("env.HOME=$homePath")
                add("env.FEX_ROOTFS=${directories.rootfs}")
                add("env.FEX_DISABLESANDBOX=1")
                request.environment.forEach { (key, value) -> add("env.$key=$value") }
                add("")
            },
        )
    }

    fun writeGuestPreamble(request: GuestLaunchRequest, backend: String) {
        overwrite(
            request.logDestinations.guestLog,
            listOf(
                "session=${request.sessionId}",
                "layer=guest",
                "backend=$backend",
                "executable=${request.executable}",
                "args=${request.args.joinToString(" ")}",
                "",
            ),
        )
    }

    fun writeLaunchResult(request: GuestLaunchRequest, backend: String, result: GuestLaunchResult) {
        writeAppSummary(request, backend, result)
    }

    fun writeLaunchFailure(request: GuestLaunchRequest, backend: String, result: GuestLaunchResult) {
        writeAppSummary(request, backend, result)
        append(request.logDestinations.fexLog, "error=${result.detail}\n")
        append(request.logDestinations.guestLog, "error=${result.detail}\n")
    }

    fun pump(input: InputStream, destination: Path): Thread {
        return thread(start = true, isDaemon = true) {
            destination.parent?.createDirectories()
            input.use { source ->
                destination.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { sink ->
                    source.copyTo(sink)
                }
            }
        }
    }

    private fun writeAppSummary(request: GuestLaunchRequest, backend: String, result: GuestLaunchResult) {
        overwrite(
            request.logDestinations.appLog,
            listOf(
                "session=${request.sessionId}",
                "layer=app",
                "backend=$backend",
                "workingDir=${request.workingDirectory}",
                "exitClassification=${result.exitClassification}",
                "exitCode=${result.exitCode ?: "none"}",
                "detail=${result.detail}",
            ),
        )
    }

    private fun latestLogFile(directory: Path): Path? {
        if (!directory.exists()) {
            return null
        }

        return Files.list(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .max { left, right ->
                    Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right))
                }
                .orElse(null)
        }
    }

    private fun overwrite(path: Path, lines: List<String>) {
        path.parent?.let(Files::createDirectories)
        path.toFile().writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun append(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        path.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { output ->
            output.write(content.toByteArray())
        }
    }

    private fun parseKeyValueLines(content: String): Map<String, String> {
        return content.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }
            .toMap()
    }
}

private data class SessionLogs(
    val sessionId: String,
    val appLog: Path,
    val fexLog: Path,
    val guestLog: Path,
)

private data class HostArtifactsStatus(
    val packaged: Boolean,
    val loaderPath: String,
    val corePath: String,
) {
        companion object {
        fun from(context: Context): HostArtifactsStatus {
            val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir).toPath()
            val loaderPath = nativeLibraryDir.resolve("libFEXLoader.so")
            val corePath = nativeLibraryDir.resolve("libFEXCore.so")
            return HostArtifactsStatus(
                packaged = loaderPath.exists() && corePath.exists(),
                loaderPath = loaderPath.toString(),
                corePath = corePath.toString(),
            )
        }
    }
}

private object SessionIdFactory {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    fun create(): String = formatter.format(Instant.now())
}
