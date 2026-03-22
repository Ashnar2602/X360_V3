package emu.x360.mobile.dev.bootstrap

import android.content.Context
import android.net.Uri
import emu.x360.mobile.dev.nativebridge.NativeBridge
import emu.x360.mobile.dev.runtime.ExitClassification
import emu.x360.mobile.dev.runtime.FexBuildMetadata
import emu.x360.mobile.dev.runtime.FexBuildMetadataCodec
import emu.x360.mobile.dev.runtime.GameLibraryEntry
import emu.x360.mobile.dev.runtime.GameLibraryEntryStatus
import emu.x360.mobile.dev.runtime.GuestRuntimeMetadata
import emu.x360.mobile.dev.runtime.GuestRuntimeMetadataCodec
import emu.x360.mobile.dev.runtime.GuestLaunchRequest
import emu.x360.mobile.dev.runtime.GuestLaunchResult
import emu.x360.mobile.dev.runtime.GuestLogDestinations
import emu.x360.mobile.dev.runtime.InheritedFileDescriptor
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.MesaRuntimeMetadata
import emu.x360.mobile.dev.runtime.MesaRuntimeMetadataCodec
import emu.x360.mobile.dev.runtime.RuntimeAssetSource
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.RuntimeInstallState
import emu.x360.mobile.dev.runtime.RuntimeInstaller
import emu.x360.mobile.dev.runtime.RuntimeManifest
import emu.x360.mobile.dev.runtime.RuntimeManifestCodec
import emu.x360.mobile.dev.runtime.RuntimePhase
import emu.x360.mobile.dev.runtime.SharedFrameTransportFrame
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import emu.x360.mobile.dev.runtime.XeniaBuildMetadata
import emu.x360.mobile.dev.runtime.XeniaBuildMetadataCodec
import emu.x360.mobile.dev.runtime.XeniaSourceLock
import emu.x360.mobile.dev.runtime.XeniaSourceLockCodec
import emu.x360.mobile.dev.runtime.XeniaStartupStage
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    private val targetPhase = RuntimePhase.XENIA_BRINGUP
    private val directories = RuntimeDirectories(baseDir)
    private val installer = RuntimeInstaller(directories)
    private val manifestLoader = RuntimeAssetManifestLoader(context)
    private val assetSource = AndroidRuntimeAssetSource(context)
    private val logStore = SessionLogStore(directories)
    private val metadataLoader = FexMetadataLoader(assetSource, directories)
    private val guestRuntimeMetadataLoader = GuestRuntimeMetadataLoader(assetSource, directories)
    private val mesaRuntimeMetadataLoader = MesaRuntimeMetadataLoader(assetSource, directories)
    private val xeniaSourceLockLoader = XeniaSourceLockLoader(assetSource, directories)
    private val xeniaBuildMetadataLoader = XeniaBuildMetadataLoader(assetSource, directories)
    private val mesaOverrideStore = MesaRuntimeOverrideStore(context)
    private val gameLibraryStore = GameLibraryStore(directories)
    private val titleContentResolver = TitleContentResolver(context, directories)
    private val guestContentPortalManager = GuestContentPortalManager(directories)
    private val stubGuestLauncher = StubGuestLauncher(logStore)
    private val fexGuestLauncher = FexGuestLauncher(context, directories, logStore)
    private val xeniaGuestLauncher = XeniaGuestLauncher(context, directories, logStore)

    fun shellSnapshot(
        lastAction: String = "Ready",
        autoPrepareRuntime: Boolean = true,
    ): ShellSnapshot {
        val manifest = manifestLoader.load()
        val installState = if (autoPrepareRuntime) {
            ensureRuntimeInstalled(manifest)
        } else {
            installer.inspect(manifest, targetPhase)
        }
        return ShellSnapshot(
            installState = installState,
            libraryEntries = refreshLibraryEntries(),
            lastAction = lastAction,
        )
    }

    fun snapshot(
        lastAction: String = "Ready",
        installStateOverride: RuntimeInstallState? = null,
    ): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installStateOverride ?: installer.inspect(manifest, targetPhase)
        val metadata = metadataLoader.load()
        val guestRuntimeMetadata = guestRuntimeMetadataLoader.load()
        val mesaRuntimeMetadata = mesaRuntimeMetadataLoader.load()
        val xeniaSourceLock = xeniaSourceLockLoader.load()
        val xeniaBuildMetadata = xeniaBuildMetadataLoader.load()
        val libraryEntries = refreshLibraryEntries()
        val hostArtifacts = HostArtifactsStatus.from(context)
        val deviceProperties = DeviceProperties.read()
        val overrideMode = mesaOverrideStore.read()
        val resolvedMesaRuntime = DeviceMesaRuntimePolicy.resolve(overrideMode, deviceProperties)
        val kgslAccess = KgslAccessInspector.inspect()
        val latestLogs = logStore.latestLogs()
        val xeniaStartupAnalysis = XeniaStartupStageParser.analyze(latestLogs.guestLog)
        val outputPreview = resolveLatestOutputPreview(
            latestLogs = latestLogs,
            startupAnalysis = xeniaStartupAnalysis,
        )
        return RuntimeSnapshot(
            manifest = manifest,
            installState = installState,
            directories = directories,
            nativeHealth = runCatching { NativeBridge.healthCheck() }.getOrElse { "native-bridge:error:${it.message}" },
            surfaceHookReservation = runCatching {
                NativeBridge.describeSurfaceHookPlaceholder(directories.rootfsTmp.toString())
            }.getOrElse { "surface-hook:error:${it.message}" },
            latestLogs = latestLogs,
            outputPreview = outputPreview,
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
            xeniaDiagnostics = buildXeniaDiagnostics(
                sourceLock = xeniaSourceLock,
                buildMetadata = xeniaBuildMetadata,
                installState = installState,
                latestLogs = latestLogs,
                startupAnalysis = xeniaStartupAnalysis,
                outputPreview = outputPreview,
            ),
            gameLibraryEntries = libraryEntries,
            lastAction = lastAction,
        )
    }

    fun install(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installRuntime(manifest)
        val bootstrapNote = if (installState is RuntimeInstallState.Installed) {
            "native-bootstrap:ready"
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
            return snapshot(lastAction = "Launch blocked: Xenia bring-up runtime is not installed")
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
            return snapshot(lastAction = "Launch blocked: Xenia bring-up runtime is not installed")
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

    fun launchXeniaBringup(): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, targetPhase)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: Xenia bring-up runtime is not installed")
        }
        resetXeniaFramebufferFile()
        val launchMode = XeniaLaunchMode.NoTitleBringup
        val presentationSettings = XeniaPresentationSettings.HeadlessBringup
        val configPrepared = prepareXeniaLaunchConfig(
            launchMode = launchMode,
            presentationSettings = presentationSettings,
        )
        if (configPrepared != null) {
            return snapshot(lastAction = configPrepared)
        }

        val resolvedMesaRuntime = DeviceMesaRuntimePolicy.resolve(
            overrideMode = mesaOverrideStore.read(),
            properties = DeviceProperties.read(),
        )
        val request = createRequest(
            sessionId = SessionIdFactory.create(),
            executable = directories.xeniaBinary.toString(),
            args = buildXeniaBringupArgs(
                directories = directories,
                launchMode = launchMode,
                presentationSettings = presentationSettings,
            ),
            environment = buildVulkanGuestEnvironment(resolvedMesaRuntime.branch, directories),
            workingDirectory = directories.rootfsXeniaBin.toString(),
        )
        val result = xeniaGuestLauncher.launch(
            request,
            XeniaRunGoal.StopAtStage(XeniaStartupStage.VULKAN_INITIALIZED),
        )
        val stage = XeniaStartupStageParser.analyze(logStore.latestLogs().guestLog).stage
        return snapshot(
            lastAction = "Xenia bring-up ${result.exitClassification.name.lowercase()} at ${stage.name.lowercase()} for session ${result.sessionId}",
        )
    }

    fun importIso(uri: Uri): RuntimeSnapshot {
        titleContentResolver.persistReadPermission(uri)
        val importedEntry = titleContentResolver.importEntry(uri)
        gameLibraryStore.upsert(importedEntry)
        return snapshot(
            lastAction = "Imported ISO ${importedEntry.displayName} as ${importedEntry.lastKnownStatus.name.lowercase()}",
        )
    }

    fun refreshLibrary(): RuntimeSnapshot {
        val refreshedEntries = refreshLibraryEntries(forceSave = true)
        return snapshot(
            lastAction = "Library refreshed (${refreshedEntries.size} entr${if (refreshedEntries.size == 1) "y" else "ies"})",
        )
    }

    fun removeLibraryEntry(entryId: String): RuntimeSnapshot {
        guestContentPortalManager.clearPortal(entryId)
        gameLibraryStore.remove(entryId)
        return snapshot(lastAction = "Removed library entry $entryId")
    }

    internal fun startPlayerSession(
        entryId: String,
        presentationSettings: XeniaPresentationSettings = XeniaPresentationSettings.FramebufferSharedMemory,
    ): PlayerSessionStartResult {
        val manifest = manifestLoader.load()
        val installState = ensureRuntimeInstalled(manifest)
        if (installState !is RuntimeInstallState.Installed) {
            return PlayerSessionStartResult(
                session = null,
                detail = "Runtime is not installed",
            )
        }
        resetXeniaFramebufferFile()

        val currentEntry = refreshLibraryEntries().firstOrNull { it.id == entryId }
            ?: return PlayerSessionStartResult(null, "Library entry $entryId was not found")
        if (currentEntry.lastKnownStatus != GameLibraryEntryStatus.READY) {
            return PlayerSessionStartResult(null, "Library entry ${currentEntry.displayName} is ${currentEntry.lastKnownStatus.name.lowercase()}")
        }

        val resolution = titleContentResolver.resolve(currentEntry)
        if (resolution is ResolvedTitleSource.Unsupported || resolution is ResolvedTitleSource.ProxyPath) {
            val detail = when (resolution) {
                is ResolvedTitleSource.Unsupported -> resolution.reason
                is ResolvedTitleSource.ProxyPath -> resolution.descriptor
                else -> "Title source could not be resolved"
            }
            return PlayerSessionStartResult(null, detail)
        }

        val descriptorBackedResolution = descriptorBackedResolution(resolution)
            ?: return PlayerSessionStartResult(null, "Title source could not be inherited for ${currentEntry.displayName}")

        val adoptedExecFd = titleContentResolver.adoptDescriptorForExec(descriptorBackedResolution)
        if (adoptedExecFd == null) {
            return PlayerSessionStartResult(null, "Could not inherit descriptor for ${currentEntry.displayName}")
        }

        val portalPath = runCatching {
            guestContentPortalManager.materializeFdBackedPortal(currentEntry.id)
        }.getOrElse { throwable ->
            adoptedExecFd?.close()
            return PlayerSessionStartResult(null, "Failed to portal ${currentEntry.displayName}: ${throwable.message}")
        }

        val launchMode = XeniaLaunchMode.TitleBoot(
            entryId = currentEntry.id,
            guestPath = portalPath.toGuestPath(directories.rootfs),
        )
        val sessionId = SessionIdFactory.create()
        val resolvedMesaRuntime = DeviceMesaRuntimePolicy.resolve(
            overrideMode = mesaOverrideStore.read(),
            properties = DeviceProperties.read(),
        )
        val effectivePresentationSettings = presentationSettings.resolveForMesaBranch(resolvedMesaRuntime.branch)
        val presentationSession = when (effectivePresentationSettings.presentationBackend) {
            PresentationBackend.FRAMEBUFFER_SHARED_MEMORY -> PlayerPresentationSession.create(
                directories = directories,
                sessionId = sessionId,
                presentationSettings = effectivePresentationSettings,
            )
            else -> null
        }
        if (effectivePresentationSettings.presentationBackend == PresentationBackend.FRAMEBUFFER_SHARED_MEMORY &&
            presentationSession == null
        ) {
            adoptedExecFd.close()
            return PlayerSessionStartResult(null, "Failed to create a shared-memory presentation session")
        }
        prepareXeniaLaunchConfig(
            launchMode = launchMode,
            presentationSettings = effectivePresentationSettings,
        )?.let { detail ->
            adoptedExecFd?.close()
            presentationSession?.close()
            return PlayerSessionStartResult(null, detail)
        }
        val request = createRequest(
            sessionId = sessionId,
            executable = directories.xeniaBinary.toString(),
            args = buildXeniaBringupArgs(
                directories = directories,
                launchMode = launchMode,
                presentationSettings = effectivePresentationSettings,
            ),
            environment = buildVulkanGuestEnvironment(resolvedMesaRuntime.branch, directories) +
                presentationSession?.launchEnvironment.orEmpty() +
                buildMap {
                    if (adoptedExecFd != null) {
                        put("X360_TITLE_SOURCE_FD", "0")
                        put("X360_TITLE_SOURCE_PATH", launchMode.guestPath)
                    }
                },
            workingDirectory = directories.rootfsXeniaBin.toString(),
            stdinRedirectFd = adoptedExecFd?.fd,
            inheritedFileDescriptors = presentationSession?.inheritedFileDescriptors.orEmpty(),
        )
        val handle = xeniaGuestLauncher.startSession(
            request = request,
            entryId = currentEntry.id,
            entryDisplayName = currentEntry.displayName,
            adoptedExecFd = adoptedExecFd,
            presentationSession = presentationSession,
            presentationSettings = effectivePresentationSettings,
        ) ?: run {
            adoptedExecFd?.close()
            presentationSession?.close()
            return PlayerSessionStartResult(null, "Failed to start ${currentEntry.displayName}")
        }
        updateLibraryEntry(
            currentEntry.copy(
                lastKnownStatus = GameLibraryEntryStatus.READY,
                lastResolvedGuestPath = launchMode.guestPath,
                lastLaunchSummary = "Player session starting",
            ),
        )
        return PlayerSessionStartResult(
            session = handle,
            detail = "Player session started for ${currentEntry.displayName}",
        )
    }

    internal fun recordPlayerSessionOutcome(
        entryId: String,
        detail: String,
        stage: XeniaStartupStage,
        titleName: String?,
    ) {
        val entry = gameLibraryStore.find(entryId) ?: return
        updateLibraryEntry(
            entry.copy(
                lastKnownStatus = when (stage) {
                    XeniaStartupStage.FAILED -> GameLibraryEntryStatus.ERROR
                    else -> GameLibraryEntryStatus.READY
                },
                lastLaunchSummary = detail,
                lastKnownTitleName = titleName ?: entry.lastKnownTitleName,
            ),
        )
    }

    internal fun launchImportedTitle(
        entryId: String,
        presentationSettings: XeniaPresentationSettings = XeniaPresentationSettings.FramebufferPolling,
        requiredStage: XeniaStartupStage = XeniaStartupStage.FRAME_STREAM_ACTIVE,
    ): RuntimeSnapshot {
        val manifest = manifestLoader.load()
        val installState = installer.inspect(manifest, targetPhase)
        if (installState !is RuntimeInstallState.Installed) {
            return snapshot(lastAction = "Launch blocked: Xenia bring-up runtime is not installed")
        }
        resetXeniaFramebufferFile()

        val currentEntry = refreshLibraryEntries().firstOrNull { it.id == entryId }
            ?: return snapshot(lastAction = "Launch blocked: library entry $entryId was not found")
        if (currentEntry.lastKnownStatus != GameLibraryEntryStatus.READY) {
            val result = GuestLaunchResult(
                sessionId = SessionIdFactory.create(),
                exitClassification = ExitClassification.VALIDATION_ERROR,
                exitCode = null,
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                detail = "Library entry ${currentEntry.displayName} is ${currentEntry.lastKnownStatus.name.lowercase()}",
            )
            val blockedRequest = createRequest(
                sessionId = result.sessionId,
                executable = directories.xeniaBinary.toString(),
                args = emptyList(),
                environment = emptyMap(),
                workingDirectory = directories.rootfsXeniaBin.toString(),
            )
            logStore.writeLaunchFailure(blockedRequest, xeniaGuestLauncher.backendName, result)
            updateLibraryEntry(
                currentEntry.copy(
                    lastLaunchSummary = result.detail,
                    lastResolvedGuestPath = currentEntry.lastResolvedGuestPath ?: guestContentPortalManager.portalGuestPath(currentEntry.id),
                ),
            )
            return snapshot(
                lastAction = "Imported title validation_error for ${currentEntry.displayName}",
            )
        }

        val resolution = titleContentResolver.resolve(currentEntry)
        if (resolution is ResolvedTitleSource.Unsupported || resolution is ResolvedTitleSource.ProxyPath) {
            val detail = when (resolution) {
                is ResolvedTitleSource.Unsupported -> resolution.reason
                is ResolvedTitleSource.ProxyPath -> resolution.descriptor
                else -> "Title source could not be resolved"
            }
            val result = GuestLaunchResult(
                sessionId = SessionIdFactory.create(),
                exitClassification = ExitClassification.VALIDATION_ERROR,
                exitCode = null,
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                detail = detail,
            )
            val blockedRequest = createRequest(
                sessionId = result.sessionId,
                executable = directories.xeniaBinary.toString(),
                args = emptyList(),
                environment = emptyMap(),
                workingDirectory = directories.rootfsXeniaBin.toString(),
            )
            logStore.writeLaunchFailure(blockedRequest, xeniaGuestLauncher.backendName, result)
            updateLibraryEntry(
                currentEntry.copy(
                    lastKnownStatus = GameLibraryEntryStatus.UNSUPPORTED,
                    lastLaunchSummary = detail,
                    lastResolvedGuestPath = null,
                ),
            )
            return snapshot(lastAction = "Imported title validation_error for ${currentEntry.displayName}")
        }

        val descriptorBackedResolution = descriptorBackedResolution(resolution)
            ?: return snapshot(lastAction = "Launch blocked: title source could not be inherited for ${currentEntry.displayName}")

        val adoptedExecFd = titleContentResolver.adoptDescriptorForExec(descriptorBackedResolution)
        if (adoptedExecFd == null) {
            return snapshot(lastAction = "Launch blocked: could not inherit descriptor for ${currentEntry.displayName}")
        }

        val portalPath = runCatching {
            guestContentPortalManager.materializeFdBackedPortal(currentEntry.id)
        }.getOrElse { throwable ->
            adoptedExecFd?.close()
            return snapshot(lastAction = "Launch blocked: failed to portal ${currentEntry.displayName}: ${throwable.message}")
        }
        val launchMode = XeniaLaunchMode.TitleBoot(
            entryId = currentEntry.id,
            guestPath = portalPath.toGuestPath(directories.rootfs),
        )
        val resolvedMesaRuntime = DeviceMesaRuntimePolicy.resolve(
            overrideMode = mesaOverrideStore.read(),
            properties = DeviceProperties.read(),
        )
        val effectivePresentationSettings = presentationSettings.resolveForMesaBranch(resolvedMesaRuntime.branch)
        val sessionId = SessionIdFactory.create()
        val presentationSession = when (effectivePresentationSettings.presentationBackend) {
            PresentationBackend.FRAMEBUFFER_SHARED_MEMORY -> PlayerPresentationSession.create(
                directories = directories,
                sessionId = sessionId,
                presentationSettings = effectivePresentationSettings,
            )
            else -> null
        }
        if (effectivePresentationSettings.presentationBackend == PresentationBackend.FRAMEBUFFER_SHARED_MEMORY &&
            presentationSession == null
        ) {
            adoptedExecFd.close()
            return snapshot(lastAction = "Launch blocked: failed to create a shared-memory presentation session")
        }
        prepareXeniaLaunchConfig(
            launchMode = launchMode,
            presentationSettings = effectivePresentationSettings,
        )?.let { detail ->
            adoptedExecFd?.close()
            presentationSession?.close()
            return snapshot(lastAction = detail)
        }
        val request = createRequest(
            sessionId = sessionId,
            executable = directories.xeniaBinary.toString(),
            args = buildXeniaBringupArgs(
                directories = directories,
                launchMode = launchMode,
                presentationSettings = effectivePresentationSettings,
            ),
            environment = buildVulkanGuestEnvironment(resolvedMesaRuntime.branch, directories) +
                presentationSession?.launchEnvironment.orEmpty() +
                buildMap {
                    if (adoptedExecFd != null) {
                        put("X360_TITLE_SOURCE_FD", "0")
                        put("X360_TITLE_SOURCE_PATH", launchMode.guestPath)
                    }
                },
            workingDirectory = directories.rootfsXeniaBin.toString(),
            stdinRedirectFd = adoptedExecFd?.fd,
            inheritedFileDescriptors = presentationSession?.inheritedFileDescriptors.orEmpty(),
        )
        val result = try {
            xeniaGuestLauncher.launch(
                request,
                XeniaRunGoal.TitleSteadyState(
                    requiredStage = requiredStage,
                    presentationBackend = effectivePresentationSettings.presentationBackend,
                ),
                presentationSession = presentationSession,
            )
        } finally {
            adoptedExecFd?.close()
            presentationSession?.close()
        }
        val startupAnalysis = XeniaStartupStageParser.analyze(logStore.latestLogs().guestLog)
        updateLibraryEntry(
            currentEntry.copy(
                lastKnownStatus = when (result.exitClassification) {
                    ExitClassification.SUCCESS -> GameLibraryEntryStatus.READY
                    ExitClassification.VALIDATION_ERROR -> GameLibraryEntryStatus.ERROR
                    ExitClassification.PROCESS_ERROR -> GameLibraryEntryStatus.ERROR
                },
                lastResolvedGuestPath = launchMode.guestPath,
                lastLaunchSummary = result.detail,
                lastKnownTitleName = startupAnalysis.titleName ?: currentEntry.lastKnownTitleName,
            ),
        )
        return snapshot(
            lastAction = "Imported title ${result.exitClassification.name.lowercase()} at ${startupAnalysis.stage.name.lowercase()} for session ${result.sessionId}",
        )
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

    private fun refreshLibraryEntries(
        forceSave: Boolean = false,
    ): List<GameLibraryEntry> {
        val currentDatabase = gameLibraryStore.load()
        val refreshedEntries = currentDatabase.entries.map { titleContentResolver.refreshEntry(it) }
        if (forceSave || refreshedEntries != currentDatabase.entries) {
            gameLibraryStore.replaceEntries(refreshedEntries)
        }
        return refreshedEntries
    }

    private fun updateLibraryEntry(entry: GameLibraryEntry) {
        gameLibraryStore.upsert(entry)
    }

    private fun descriptorBackedResolution(
        resolution: ResolvedTitleSource,
    ): ResolvedTitleSource.FileDescriptorPath? {
        return when (resolution) {
            is ResolvedTitleSource.FileDescriptorPath -> resolution
            is ResolvedTitleSource.HostPath -> ResolvedTitleSource.FileDescriptorPath(
                hostPath = resolution.hostPath,
                origin = "${resolution.origin}-fd",
            )
            else -> null
        }
    }

    private fun prepareXeniaLaunchConfig(
        launchMode: XeniaLaunchMode,
        presentationSettings: XeniaPresentationSettings,
    ): String? {
        return try {
            directories.xeniaConfigFile.parent.createDirectories()
            directories.xeniaConfigFile.toFile().writeText(
                buildXeniaConfigText(
                    directories = directories,
                    launchMode = launchMode,
                    presentationSettings = presentationSettings,
                ),
            )
            null
        } catch (throwable: Throwable) {
            "Launch blocked: failed to prepare Xenia config for ${presentationSettings.presentationBackend.name.lowercase()}: ${throwable.message}"
        }
    }

    private fun ensureRuntimeInstalled(
        manifest: RuntimeManifest,
    ): RuntimeInstallState {
        val currentState = installer.inspect(manifest, targetPhase)
        return if (currentState is RuntimeInstallState.Installed) {
            currentState
        } else {
            installRuntime(manifest)
        }
    }

    private fun installRuntime(
        manifest: RuntimeManifest,
    ): RuntimeInstallState {
        val installState = installer.install(manifest, assetSource, targetPhase)
        if (installState is RuntimeInstallState.Installed) {
            runCatching { NativeBridge.bootstrapStub(baseDir.toString()) }
        }
        return installState
    }

    private fun resetXeniaFramebufferFile() {
        runCatching { Files.deleteIfExists(directories.rootfsTmp.resolve("xenia_fb")) }
    }

    private fun createRequest(
        sessionId: String,
        executable: String,
        args: List<String>,
        environment: Map<String, String>,
        workingDirectory: String = directories.rootfs.toString(),
        stdinRedirectPath: String? = null,
        stdinRedirectFd: Int? = null,
        inheritedFileDescriptors: List<InheritedFileDescriptor> = emptyList(),
    ): GuestLaunchRequest {
        val logs = logStore.createSession(sessionId)
        return GuestLaunchRequest(
            sessionId = sessionId,
            executable = executable,
            args = args,
            environment = environment,
            workingDirectory = workingDirectory,
            stdinRedirectPath = stdinRedirectPath,
            stdinRedirectFd = stdinRedirectFd,
            inheritedFileDescriptors = inheritedFileDescriptors,
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

    private fun buildXeniaDiagnostics(
        sourceLock: XeniaSourceLock?,
        buildMetadata: XeniaBuildMetadata?,
        installState: RuntimeInstallState,
        latestLogs: LatestSessionLogs,
        startupAnalysis: XeniaStartupAnalysis,
        outputPreview: OutputPreviewState,
    ): XeniaDiagnostics {
        val appFields = parseKeyValueLines(latestLogs.appLog)
        val presentationMetrics = collectPresentationMetrics(
            guestLog = latestLogs.guestLog,
            outputPreview = outputPreview,
            appFields = appFields,
        )
        val lastLogPath = latestLogs.sessionId
            ?.let { sessionId -> directories.guestLogs.resolve("session-$sessionId.guest.log").toString() }
            ?: "none"
        return XeniaDiagnostics(
            commit = buildMetadata?.sourceRevision ?: sourceLock?.sourceRevision ?: "unavailable",
            patchSetId = buildMetadata?.patchSetId ?: sourceLock?.patchSetId ?: "unavailable",
            buildProfile = buildMetadata?.buildProfile ?: sourceLock?.buildProfile ?: "unavailable",
            binaryInstalled = installState is RuntimeInstallState.Installed && directories.xeniaBinary.exists(),
            configPresent = directories.xeniaConfigFile.exists(),
            portableMarkerPresent = directories.xeniaPortableMarker.exists(),
            contentMode = detectXeniaContentMode(directories),
            lastStartupStage = startupAnalysis.stage.name.lowercase(),
            lastStartupDetail = startupAnalysis.detail,
            aliveAfterModuleLoadSeconds = appFields["xenia.aliveAfterModuleLoadSeconds"]?.toLongOrNull() ?: 0L,
            cacheBackendStatus = appFields["xenia.cacheBackendStatus"] ?: "unknown",
            cacheRootPath = appFields["xenia.cacheRootPath"] ?: directories.xeniaWritableCacheHostRoot.toString(),
            titleMetadataSeen = appFields["xenia.titleMetadataSeen"]?.toBooleanStrictOrNull()
                ?: latestLogs.guestLog.contains("Title name: "),
            presentationBackend = appFields["xenia.presentationBackend"] ?: PresentationBackend.HEADLESS_ONLY.name.lowercase(),
            guestRenderScaleProfile = appFields["xenia.guestRenderScaleProfile"] ?: "one",
            internalDisplayResolution = appFields["xenia.internalDisplayResolution"] ?: "1280x720",
            issueSwapCount = presentationMetrics.issueSwapCount,
            captureSuccessCount = presentationMetrics.captureSuccessCount,
            exportFrameCount = presentationMetrics.exportFrameCount,
            decodedFrameCount = presentationMetrics.decodedFrameCount,
            presentedFrameCount = presentationMetrics.presentedFrameCount,
            swapFps = presentationMetrics.swapFps,
            captureFps = presentationMetrics.captureFps,
            exportFps = presentationMetrics.exportFps,
            decodeFps = presentationMetrics.decodeFps,
            presentFps = presentationMetrics.presentFps,
            visibleFps = presentationMetrics.visibleFps,
            framebufferPath = appFields["xenia.framebufferPath"] ?: outputPreview.framebufferPath,
            frameStreamStatus = appFields["xenia.frameSourceStatus"] ?: appFields["xenia.frameStreamStatus"] ?: outputPreview.status,
            lastFrameWidth = appFields["xenia.lastFrameWidth"]?.toIntOrNull() ?: outputPreview.width ?: 0,
            lastFrameHeight = appFields["xenia.lastFrameHeight"]?.toIntOrNull() ?: outputPreview.height ?: 0,
            lastFrameIndex = appFields["xenia.lastFrameIndex"]?.toLongOrNull() ?: outputPreview.frameIndex,
            frameFreshnessSeconds = outputPreview.freshnessSeconds,
            lastLogPath = lastLogPath,
            executablePath = directories.xeniaBinary.toString(),
        )
    }

    private fun resolveLatestOutputPreview(
        latestLogs: LatestSessionLogs,
        startupAnalysis: XeniaStartupAnalysis,
    ): OutputPreviewState {
        val appFields = parseKeyValueLines(latestLogs.appLog)
        val framebufferPath = appFields["xenia.framebufferPath"]
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).toPath() }
            ?: directories.rootfsTmp.resolve("xenia_fb")
        val backend = appFields["xenia.presentationBackend"]
        return if (backend == PresentationBackend.FRAMEBUFFER_SHARED_MEMORY.name.lowercase()) {
            val sharedFrame = runCatching {
                SharedFramePresentationReader(
                    transportPath = framebufferPath,
                    signalReadPfd = null,
                ).use { reader ->
                    reader.peekLatestFrame()
                }
            }.getOrNull()
            OutputPreviewState.fromSharedFrame(
                transportPath = framebufferPath,
                startupAnalysis = startupAnalysis,
                frame = sharedFrame,
            )
        } else {
            OutputPreviewState.from(
                framebufferPath = framebufferPath,
                startupAnalysis = startupAnalysis,
            )
        }
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
    val xeniaDiagnostics: XeniaDiagnostics,
    val gameLibraryEntries: List<GameLibraryEntry>,
    val lastAction: String,
)

data class ShellSnapshot(
    val installState: RuntimeInstallState,
    val libraryEntries: List<GameLibraryEntry>,
    val lastAction: String,
)

internal data class PlayerSessionStartResult(
    val session: ActivePlayerSessionHandle?,
    val detail: String,
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

data class XeniaDiagnostics(
    val commit: String,
    val patchSetId: String,
    val buildProfile: String,
    val binaryInstalled: Boolean,
    val configPresent: Boolean,
    val portableMarkerPresent: Boolean,
    val contentMode: String,
    val lastStartupStage: String,
    val lastStartupDetail: String,
    val aliveAfterModuleLoadSeconds: Long,
    val cacheBackendStatus: String,
    val cacheRootPath: String,
    val titleMetadataSeen: Boolean,
    val presentationBackend: String,
    val guestRenderScaleProfile: String,
    val internalDisplayResolution: String,
    val issueSwapCount: Long,
    val captureSuccessCount: Long,
    val exportFrameCount: Long,
    val decodedFrameCount: Long,
    val presentedFrameCount: Long,
    val swapFps: Float,
    val captureFps: Float,
    val exportFps: Float,
    val decodeFps: Float,
    val presentFps: Float,
    val visibleFps: Float,
    val framebufferPath: String,
    val frameStreamStatus: String,
    val lastFrameWidth: Int,
    val lastFrameHeight: Int,
    val lastFrameIndex: Long,
    val frameFreshnessSeconds: Long?,
    val lastLogPath: String,
    val executablePath: String,
)

private fun collectPresentationMetrics(
    guestLog: String,
    outputPreview: OutputPreviewState,
    appFields: Map<String, String>,
): PresentationPerformanceMetrics {
    val guestMetrics = GuestPresentationMetricsParser.parse(
        logContent = guestLog,
        exportedFrameCount = outputPreview.frameIndex,
        elapsedMillis = appFields["xenia.sessionElapsedMillis"]?.toLongOrNull() ?: 1L,
        frameSourceStatus = outputPreview.status,
    )
    return guestMetrics.copy(
        exportFrameCount = appFields["xenia.exportFrameCount"]?.toLongOrNull() ?: guestMetrics.exportFrameCount,
        decodedFrameCount = appFields["xenia.decodedFrameCount"]?.toLongOrNull() ?: guestMetrics.decodedFrameCount,
        presentedFrameCount = appFields["xenia.presentedFrameCount"]?.toLongOrNull() ?: guestMetrics.presentedFrameCount,
        exportFps = appFields["xenia.exportFps"]?.toFloatOrNull() ?: guestMetrics.exportFps,
        decodeFps = appFields["xenia.decodeFps"]?.toFloatOrNull() ?: guestMetrics.decodeFps,
        presentFps = appFields["xenia.presentFps"]?.toFloatOrNull() ?: guestMetrics.presentFps,
        visibleFps = appFields["xenia.visibleFps"]?.toFloatOrNull() ?: guestMetrics.visibleFps,
        frameSourceStatus = appFields["xenia.frameSourceStatus"] ?: guestMetrics.frameSourceStatus,
    )
}

data class OutputPreviewState(
    val framebufferPath: String,
    val exists: Boolean,
    val status: String,
    val width: Int?,
    val height: Int?,
    val stride: Int?,
    val frameIndex: Long,
    val freshnessSeconds: Long?,
    val summary: String,
) {
    companion object {
        internal fun from(
            framebufferPath: Path,
            startupAnalysis: XeniaStartupAnalysis,
        ): OutputPreviewState {
            if (!framebufferPath.exists()) {
                return OutputPreviewState(
                    framebufferPath = framebufferPath.toString(),
                    exists = false,
                    status = "idle",
                    width = null,
                    height = null,
                    stride = null,
                    frameIndex = -1L,
                    freshnessSeconds = null,
                    summary = "waiting for /tmp/xenia_fb",
                )
            }

            val modifiedAt = runCatching { framebufferPath.getLastModifiedTime().toInstant() }.getOrNull()
            val freshnessSeconds = modifiedAt?.let { modified -> (Instant.now().epochSecond - modified.epochSecond).coerceAtLeast(0L) }
            val header = runCatching { XeniaFramebufferCodec.readHeader(framebufferPath) }.getOrNull()
            if (header == null) {
                return OutputPreviewState(
                    framebufferPath = framebufferPath.toString(),
                    exists = true,
                    status = "invalid",
                    width = null,
                    height = null,
                    stride = null,
                    frameIndex = -1L,
                    freshnessSeconds = freshnessSeconds,
                    summary = "xenia_fb present but invalid or incomplete",
                )
            }

            val streamStatus = when (startupAnalysis.stage) {
                XeniaStartupStage.FRAME_STREAM_ACTIVE -> "active"
                XeniaStartupStage.FIRST_FRAME_CAPTURED -> "first-frame"
                else -> "first-frame"
            }
            return OutputPreviewState(
                framebufferPath = framebufferPath.toString(),
                exists = true,
                status = streamStatus,
                width = header.width,
                height = header.height,
                stride = header.stride,
                frameIndex = header.frameIndex,
                freshnessSeconds = freshnessSeconds,
                summary = "status=$streamStatus ${header.width}x${header.height} stride=${header.stride} frame=${header.frameIndex} freshness=${freshnessSeconds ?: -1}s",
            )
        }

        internal fun fromSharedFrame(
            transportPath: Path,
            startupAnalysis: XeniaStartupAnalysis,
            frame: SharedFrameTransportFrame?,
        ): OutputPreviewState {
            if (frame == null) {
                return OutputPreviewState(
                    framebufferPath = transportPath.toString(),
                    exists = transportPath.exists(),
                    status = "idle",
                    width = null,
                    height = null,
                    stride = null,
                    frameIndex = -1L,
                    freshnessSeconds = null,
                    summary = "waiting for shared frame transport",
                )
            }
            val streamStatus = when (startupAnalysis.stage) {
                XeniaStartupStage.FRAME_STREAM_ACTIVE -> "active"
                XeniaStartupStage.FIRST_FRAME_CAPTURED -> "first-frame"
                else -> "first-frame"
            }
            return OutputPreviewState(
                framebufferPath = transportPath.toString(),
                exists = true,
                status = streamStatus,
                width = frame.header.width,
                height = frame.header.height,
                stride = frame.header.stride,
                frameIndex = frame.header.frameIndex,
                freshnessSeconds = 0L,
                summary = "status=$streamStatus ${frame.header.width}x${frame.header.height} stride=${frame.header.stride} frame=${frame.header.frameIndex}",
            )
        }
    }

}

internal class ActivePlayerSessionHandle(
    val sessionId: String,
    val entryId: String,
    val entryDisplayName: String,
    val framebufferPath: Path,
    val appLogPath: Path,
    val fexLogPath: Path,
    val guestLogPath: Path,
    private val startedAt: Instant,
    private val request: GuestLaunchRequest,
    private val process: Process,
    private val stdoutPump: Thread,
    private val stderrPump: Thread,
    private val adoptedExecFd: AdoptedExecFd?,
    private val presentationSession: PlayerPresentationSession?,
    private val logStore: SessionLogStore,
    private val backendName: String,
) {
    @Volatile
    private var paused = false

    @Volatile
    private var finalized = false
    private val previewReader = presentationSession?.openReader()

    fun isAlive(): Boolean = process.isAlive

    fun isPaused(): Boolean = paused

    fun readGuestLog(): String = runCatching { guestLogPath.readText() }.getOrDefault("")

    fun readStartupAnalysis(): XeniaStartupAnalysis = XeniaStartupStageParser.analyze(readGuestLog())

    fun openPresentationReader(): SharedFramePresentationReader? = presentationSession?.openReader()

    fun readOutputPreview(): OutputPreviewState {
        val startupAnalysis = readStartupAnalysis()
        val sharedFrame = previewReader?.peekLatestFrame()
        return if (sharedFrame != null || presentationSession != null) {
            OutputPreviewState.fromSharedFrame(
                transportPath = presentationSession?.transportPath ?: framebufferPath,
                startupAnalysis = startupAnalysis,
                frame = sharedFrame,
            )
        } else {
            OutputPreviewState.from(framebufferPath, startupAnalysis)
        }
    }

    fun elapsedMillis(): Long = java.time.Duration.between(startedAt, Instant.now()).toMillis().coerceAtLeast(1L)

    fun reportPlayerMetrics(metrics: PresentationPerformanceMetrics) {
        val outputPreview = readOutputPreview()
        val presentationBackend = request.args
            .firstOrNull { it.startsWith("--x360_presentation_backend=") }
            ?.substringAfter('=')
            ?: PresentationBackend.HEADLESS_ONLY.name.lowercase()
        logStore.appendAppFields(
            appLogPath,
            mapOf(
                "xenia.presentationBackend" to presentationBackend,
                "xenia.framebufferPath" to outputPreview.framebufferPath,
                "xenia.frameStreamStatus" to outputPreview.status,
                "xenia.sessionElapsedMillis" to elapsedMillis().toString(),
                "xenia.exportFrameCount" to metrics.exportFrameCount.toString(),
                "xenia.decodedFrameCount" to metrics.decodedFrameCount.toString(),
                "xenia.presentedFrameCount" to metrics.presentedFrameCount.toString(),
                "xenia.exportFps" to metrics.exportFps.toString(),
                "xenia.decodeFps" to metrics.decodeFps.toString(),
                "xenia.presentFps" to metrics.presentFps.toString(),
                "xenia.visibleFps" to metrics.visibleFps.toString(),
                "xenia.frameSourceStatus" to metrics.frameSourceStatus,
                "xenia.lastFrameWidth" to (outputPreview.width ?: 0).toString(),
                "xenia.lastFrameHeight" to (outputPreview.height ?: 0).toString(),
                "xenia.lastFrameIndex" to outputPreview.frameIndex.toString(),
            ),
        )
    }

    fun pause(): Boolean {
        if (!process.isAlive || paused) {
            return process.isAlive
        }
        val pid = processPid() ?: return false
        val sent = NativeBridge.sendSignal(pid, SignalStop)
        if (sent) {
            paused = true
            logStore.appendAppFields(
                appLogPath,
                mapOf(
                    "xenia.playerPaused" to "true",
                    "xenia.playerPauseReason" to "pause-menu",
                ),
            )
        }
        return sent
    }

    fun resume(): Boolean {
        if (!process.isAlive) {
            return false
        }
        if (!paused) {
            return true
        }
        val pid = processPid() ?: return false
        val sent = NativeBridge.sendSignal(pid, SignalContinue)
        if (sent) {
            paused = false
            logStore.appendAppFields(
                appLogPath,
                mapOf(
                    "xenia.playerPaused" to "false",
                    "xenia.playerPauseReason" to "resume",
                ),
            )
        }
        return sent
    }

    fun stop(reason: String): GuestLaunchResult {
        if (paused && process.isAlive) {
            resume()
        }
        if (process.isAlive) {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        return finalizeSession(
            classificationOverride = ExitClassification.SUCCESS,
            detailOverride = "Player session stopped by $reason",
        )
    }

    fun finalizeIfExited(): GuestLaunchResult? {
        if (process.isAlive) {
            return null
        }
        return finalizeSession()
    }

    private fun finalizeSession(
        classificationOverride: ExitClassification? = null,
        detailOverride: String? = null,
    ): GuestLaunchResult {
        if (finalized) {
            val analysis = readStartupAnalysis()
            return GuestLaunchResult(
                sessionId = sessionId,
                exitClassification = classificationOverride ?: ExitClassification.SUCCESS,
                exitCode = runCatching { process.exitValue() }.getOrNull(),
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                detail = detailOverride ?: "Player session already finalized at ${analysis.stage.name.lowercase()}",
            )
        }
        finalized = true
        stdoutPump.join()
        stderrPump.join()
        adoptedExecFd?.close()

        val startupAnalysis = readStartupAnalysis()
        val outputPreview = readOutputPreview()
        val finishedAt = Instant.now()
        val sessionElapsedMillis = java.time.Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(1L)
        val existingAppFields = parseKeyValueLines(runCatching { appLogPath.readText() }.getOrDefault(""))
        val presentationMetrics = collectPresentationMetrics(
            guestLog = readGuestLog(),
            outputPreview = outputPreview,
            appFields = existingAppFields + mapOf("xenia.sessionElapsedMillis" to sessionElapsedMillis.toString()),
        )
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        val classification = classificationOverride ?: when {
            startupAnalysis.stage == XeniaStartupStage.FAILED -> ExitClassification.PROCESS_ERROR
            exitCode != null && exitCode != 0 -> ExitClassification.PROCESS_ERROR
            else -> ExitClassification.SUCCESS
        }
        val detail = detailOverride ?: when {
            classification == ExitClassification.SUCCESS -> "Player session ended at ${startupAnalysis.stage.name.lowercase()}"
            else -> "Player session failed at ${startupAnalysis.stage.name.lowercase()}: ${startupAnalysis.detail}"
        }
        val result = GuestLaunchResult(
            sessionId = sessionId,
            exitClassification = classification,
            exitCode = exitCode,
            startedAt = startedAt,
            finishedAt = finishedAt,
            detail = detail,
        )
        logStore.writeLaunchResult(
            request,
            backendName,
            result,
            extras = mapOf(
                "xenia.startupStage" to startupAnalysis.stage.name.lowercase(),
                "xenia.cacheBackendStatus" to detectCacheBackendStatusFromLog(readGuestLog()),
                "xenia.presentationBackend" to (request.args.firstOrNull { it.startsWith("--x360_presentation_backend=") }
                    ?.substringAfter('=')
                    ?: PresentationBackend.HEADLESS_ONLY.name.lowercase()),
                "xenia.exportTargetFps" to (request.args.firstOrNull { it.startsWith("--x360_framebuffer_fps=") }
                    ?.substringAfter('=')
                    ?: "0"),
                "xenia.framebufferPath" to outputPreview.framebufferPath,
                "xenia.frameStreamStatus" to outputPreview.status,
                "xenia.sessionElapsedMillis" to sessionElapsedMillis.toString(),
                "xenia.issueSwapCount" to presentationMetrics.issueSwapCount.toString(),
                "xenia.captureSuccessCount" to presentationMetrics.captureSuccessCount.toString(),
                "xenia.exportFrameCount" to presentationMetrics.exportFrameCount.toString(),
                "xenia.decodedFrameCount" to presentationMetrics.decodedFrameCount.toString(),
                "xenia.presentedFrameCount" to presentationMetrics.presentedFrameCount.toString(),
                "xenia.swapFps" to presentationMetrics.swapFps.toString(),
                "xenia.captureFps" to presentationMetrics.captureFps.toString(),
                "xenia.exportFps" to presentationMetrics.exportFps.toString(),
                "xenia.decodeFps" to presentationMetrics.decodeFps.toString(),
                "xenia.presentFps" to presentationMetrics.presentFps.toString(),
                "xenia.visibleFps" to presentationMetrics.visibleFps.toString(),
                "xenia.frameSourceStatus" to presentationMetrics.frameSourceStatus,
                "xenia.lastFrameWidth" to (outputPreview.width ?: 0).toString(),
                "xenia.lastFrameHeight" to (outputPreview.height ?: 0).toString(),
                "xenia.lastFrameIndex" to outputPreview.frameIndex.toString(),
            ),
        )
        runCatching { previewReader?.close() }
        runCatching { presentationSession?.close() }
        return result
    }

    private fun processPid(): Int? {
        val pidByMethod = runCatching {
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as? Long)?.toInt()
        }.getOrNull()
        if (pidByMethod != null && pidByMethod > 0) {
            return pidByMethod
        }

        val pidByField = runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            (field.get(process) as? Int)
        }.getOrNull()
        return pidByField?.takeIf { it > 0 }
    }

    companion object {
        private const val SignalContinue = 18
        private const val SignalStop = 19
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

private class XeniaSourceLockLoader(
    private val assetSource: RuntimeAssetSource,
    private val directories: RuntimeDirectories,
) {
    fun load(): XeniaSourceLock? {
        if (directories.xeniaSourceLock.exists()) {
            return runCatching { XeniaSourceLockCodec.decode(directories.xeniaSourceLock.readText()) }.getOrNull()
        }

        val assetStream = assetSource.open(XENIA_SOURCE_LOCK_ASSET_PATH) ?: return null
        return assetStream.bufferedReader().use { reader ->
            runCatching { XeniaSourceLockCodec.decode(reader.readText()) }.getOrNull()
        }
    }

    companion object {
        const val XENIA_SOURCE_LOCK_ASSET_PATH = "runtime-payload/files/payload/config/xenia-source-lock.json"
    }
}

private class XeniaBuildMetadataLoader(
    private val assetSource: RuntimeAssetSource,
    private val directories: RuntimeDirectories,
) {
    fun load(): XeniaBuildMetadata? {
        if (directories.xeniaBuildMetadata.exists()) {
            return runCatching { XeniaBuildMetadataCodec.decode(directories.xeniaBuildMetadata.readText()) }.getOrNull()
        }

        val assetStream = assetSource.open(XENIA_BUILD_METADATA_ASSET_PATH) ?: return null
        return assetStream.bufferedReader().use { reader ->
            runCatching { XeniaBuildMetadataCodec.decode(reader.readText()) }.getOrNull()
        }
    }

    companion object {
        const val XENIA_BUILD_METADATA_ASSET_PATH = "runtime-payload/files/payload/config/xenia-build-metadata.json"
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

private fun ProcessBuilder.startWithConfiguredStdin(request: GuestLaunchRequest): Process {
    val stdinRedirectFd = request.stdinRedirectFd ?: return start()
    val savedStdinFd = NativeBridge.remapFdToStdinForExec(stdinRedirectFd)
    if (savedStdinFd < 0) {
        throw IOException("Failed to remap inherited stdin fd $stdinRedirectFd: errno ${-savedStdinFd}")
    }
    val process = try {
        start()
    } catch (throwable: Throwable) {
        if (!NativeBridge.restoreStdinAfterExec(savedStdinFd)) {
            throwable.addSuppressed(
                IOException("Failed to restore stdin after failed launch for session ${request.sessionId}"),
            )
        }
        throw throwable
    }
    if (!NativeBridge.restoreStdinAfterExec(savedStdinFd)) {
        process.destroy()
        process.waitFor(3, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
        }
        throw IOException("Failed to restore stdin after launching session ${request.sessionId}")
    }
    return process
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
            .redirectInput(
                when {
                    request.stdinRedirectFd != null -> ProcessBuilder.Redirect.INHERIT
                    request.stdinRedirectPath != null ->
                        ProcessBuilder.Redirect.from(File(requireNotNull(request.stdinRedirectPath)))
                    else -> ProcessBuilder.Redirect.PIPE
                },
            )

        val environment = processBuilder.environment()
        launchSpec.environment.forEach { (key, value) -> environment[key] = value }

        val process = try {
            processBuilder.startWithConfiguredStdin(request)
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

private class XeniaGuestLauncher(
    private val context: Context,
    private val directories: RuntimeDirectories,
    private val logStore: SessionLogStore,
) : GuestLauncher {
    override val backendName: String = "fex-xenia"

    override fun launch(request: GuestLaunchRequest): GuestLaunchResult {
        return launch(
            request,
            XeniaRunGoal.StopAtStage(XeniaStartupStage.VULKAN_INITIALIZED),
            presentationSession = null,
        )
    }

    fun startSession(
        request: GuestLaunchRequest,
        entryId: String,
        entryDisplayName: String,
        adoptedExecFd: AdoptedExecFd?,
        presentationSession: PlayerPresentationSession?,
        presentationSettings: XeniaPresentationSettings,
    ): ActivePlayerSessionHandle? {
        val startedAt = Instant.now()
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir).toPath()
        val loaderPath = nativeLibraryDir.resolve("libFEXLoader.so")
        val corePath = nativeLibraryDir.resolve("libFEXCore.so")
        val guestExecutable = File(request.executable).toPath()

        val missingArtifact = when {
            !loaderPath.exists() -> loaderPath
            !corePath.exists() -> corePath
            !guestExecutable.exists() -> guestExecutable
            !directories.xeniaConfigFile.exists() -> directories.xeniaConfigFile
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
            return null
        }

        directories.fexEmuHome.createDirectories()
        directories.fexConfigFile.parent?.createDirectories()
        directories.rootfsTmp.createDirectories()
        directories.fexConfigFile.toFile().writeText(buildFexConfig(directories))

        logStore.writeFexPreamble(
            request = request,
            backend = backendName,
            loaderPath = loaderPath,
            configPath = directories.fexConfigFile,
            homePath = directories.baseDir,
        )
        logStore.writeGuestPreamble(request, backendName)
        logStore.writeLaunchRunning(
            request = request,
            backend = backendName,
            extras = mapOf(
                "xenia.presentationBackend" to presentationSettings.presentationBackend.name.lowercase(),
                "xenia.guestRenderScaleProfile" to presentationSettings.guestRenderScaleProfile.name.lowercase(),
                "xenia.internalDisplayResolution" to "${presentationSettings.internalDisplayResolution.width}x${presentationSettings.internalDisplayResolution.height}",
                "xenia.exportTargetFps" to presentationSettings.exportTargetFps.toString(),
                "xenia.playerPollIntervalMs" to presentationSettings.playerPollIntervalMs.toString(),
                "xenia.keepLastVisibleFrame" to presentationSettings.keepLastVisibleFrame.toString(),
                "xenia.apuBackend" to presentationSettings.apuBackend.cliValue,
                "xenia.readbackResolveMode" to (presentationSettings.readbackResolveMode?.cliValue ?: "none"),
            ),
        )

        val launchSpec = buildFexLaunchSpec(loaderPath, directories, request)
        val processBuilder = ProcessBuilder(launchSpec.command)
            .directory(File(request.workingDirectory))
            .redirectInput(
                when {
                    request.stdinRedirectFd != null -> ProcessBuilder.Redirect.INHERIT
                    request.stdinRedirectPath != null ->
                        ProcessBuilder.Redirect.from(File(requireNotNull(request.stdinRedirectPath)))
                    else -> ProcessBuilder.Redirect.PIPE
                },
            )

        val environment = processBuilder.environment()
        launchSpec.environment.forEach { (key, value) -> environment[key] = value }

        val process = try {
            processBuilder.startWithConfiguredStdin(request)
        } catch (throwable: Throwable) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.PROCESS_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "Failed to start Xenia through FEX: ${throwable.message}",
            )
            logStore.writeLaunchFailure(request, backendName, result)
            return null
        }

        val stdoutPump = logStore.pump(process.inputStream, request.logDestinations.guestLog)
        val stderrPump = logStore.pump(process.errorStream, request.logDestinations.fexLog)
        return ActivePlayerSessionHandle(
            sessionId = request.sessionId,
            entryId = entryId,
            entryDisplayName = entryDisplayName,
            framebufferPath = directories.rootfsTmp.resolve("xenia_fb"),
            appLogPath = request.logDestinations.appLog,
            fexLogPath = request.logDestinations.fexLog,
            guestLogPath = request.logDestinations.guestLog,
            startedAt = startedAt,
            request = request,
            process = process,
            stdoutPump = stdoutPump,
            stderrPump = stderrPump,
            adoptedExecFd = adoptedExecFd,
            presentationSession = presentationSession,
            logStore = logStore,
            backendName = backendName,
        )
    }

    fun launch(
        request: GuestLaunchRequest,
        goal: XeniaRunGoal,
        presentationSession: PlayerPresentationSession? = null,
    ): GuestLaunchResult {
        val startedAt = Instant.now()
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir).toPath()
        val loaderPath = nativeLibraryDir.resolve("libFEXLoader.so")
        val corePath = nativeLibraryDir.resolve("libFEXCore.so")
        val guestExecutable = File(request.executable).toPath()

        val missingArtifact = when {
            !loaderPath.exists() -> loaderPath
            !corePath.exists() -> corePath
            !guestExecutable.exists() -> guestExecutable
            !directories.xeniaConfigFile.exists() -> directories.xeniaConfigFile
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
            .redirectInput(
                when {
                    request.stdinRedirectFd != null -> ProcessBuilder.Redirect.INHERIT
                    request.stdinRedirectPath != null ->
                        ProcessBuilder.Redirect.from(File(requireNotNull(request.stdinRedirectPath)))
                    else -> ProcessBuilder.Redirect.PIPE
                },
            )

        val environment = processBuilder.environment()
        launchSpec.environment.forEach { (key, value) -> environment[key] = value }

        val process = try {
            processBuilder.startWithConfiguredStdin(request)
        } catch (throwable: Throwable) {
            val result = GuestLaunchResult(
                sessionId = request.sessionId,
                exitClassification = ExitClassification.PROCESS_ERROR,
                exitCode = null,
                startedAt = startedAt,
                finishedAt = Instant.now(),
                detail = "Failed to start Xenia through FEX: ${throwable.message}",
            )
            logStore.writeLaunchFailure(request, backendName, result)
            return result
        }

        val stdoutPump = logStore.pump(process.inputStream, request.logDestinations.guestLog)
        val stderrPump = logStore.pump(process.errorStream, request.logDestinations.fexLog)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(goal.timeoutSeconds)
        var startupAnalysis = XeniaStartupAnalysis(
            stage = XeniaStartupStage.PROCESS_STARTED,
            detail = "xenia process started",
        )
        var stageObservedAt: Long? = null
        var moduleLoadObservedAt: Long? = null
        var steadyStateReached = false
        var timedOut = false
        while (process.isAlive && System.nanoTime() < deadline) {
            val logContent = runCatching { request.logDestinations.guestLog.readText() }.getOrDefault("")
            startupAnalysis = XeniaStartupStageParser.analyze(logContent)
            if (startupAnalysis.stage == XeniaStartupStage.FAILED) {
                process.destroy()
                process.waitFor(3, TimeUnit.SECONDS)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                break
            }

            when (goal) {
                is XeniaRunGoal.StopAtStage -> {
                    if (startupAnalysis.stage.reaches(goal.stage)) {
                        val observedAt = stageObservedAt ?: System.nanoTime().also { stageObservedAt = it }
                        if (System.nanoTime() - observedAt >= TimeUnit.MILLISECONDS.toNanos(goal.lingerMillis) ||
                            startupAnalysis.stage == XeniaStartupStage.TITLE_METADATA_AVAILABLE
                        ) {
                            process.destroy()
                            process.waitFor(3, TimeUnit.SECONDS)
                            if (process.isAlive) {
                                process.destroyForcibly()
                            }
                            break
                        }
                    } else {
                        stageObservedAt = null
                    }
                }

                is XeniaRunGoal.TitleSteadyState -> {
                    if (startupAnalysis.stage.reaches(XeniaStartupStage.TITLE_MODULE_LOADING)) {
                        val observedAt = moduleLoadObservedAt ?: System.nanoTime().also { moduleLoadObservedAt = it }
                        val aliveNanos = System.nanoTime() - observedAt
                        if (aliveNanos >= TimeUnit.SECONDS.toNanos(goal.observationWindowSeconds)) {
                            if (!steadyStateReached) {
                                steadyStateReached = true
                                logStore.appendGuestLine(
                                    request.logDestinations.guestLog,
                                    "X360_XENIA_STAGE=TITLE_RUNNING_HEADLESS observation_seconds=${goal.observationWindowSeconds}",
                                )
                            }
                            if (goal.requiredStage == XeniaStartupStage.TITLE_RUNNING_HEADLESS ||
                                startupAnalysis.stage.reaches(goal.requiredStage)
                            ) {
                                process.destroy()
                                process.waitFor(3, TimeUnit.SECONDS)
                                if (process.isAlive) {
                                    process.destroyForcibly()
                                }
                                break
                            }
                        }
                    }
                }
            }
            Thread.sleep(250)
        }

        if (process.isAlive) {
            timedOut = true
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }

        stdoutPump.join()
        stderrPump.join()

        val finalGuestLog = runCatching { request.logDestinations.guestLog.readText() }.getOrDefault("")
        startupAnalysis = XeniaStartupStageParser.analyze(finalGuestLog)
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        val finishedAt = Instant.now()
        val finishedAtNanos = System.nanoTime()
        val outputPreview = if (presentationSession != null) {
            val sharedFrame = presentationSession.openReader().use { reader ->
                reader?.peekLatestFrame()
            }
            OutputPreviewState.fromSharedFrame(
                transportPath = presentationSession.transportPath,
                startupAnalysis = startupAnalysis,
                frame = sharedFrame,
            )
        } else {
            OutputPreviewState.from(
                framebufferPath = directories.rootfsTmp.resolve("xenia_fb"),
                startupAnalysis = startupAnalysis,
            )
        }
        val sessionElapsedMillis = java.time.Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(1L)
        val presentationMetrics = collectPresentationMetrics(
            guestLog = finalGuestLog,
            outputPreview = outputPreview,
            appFields = mapOf("xenia.sessionElapsedMillis" to sessionElapsedMillis.toString()),
        )
        val aliveAfterModuleLoadSeconds = moduleLoadObservedAt?.let { observedAt ->
            val finishedNanos = if (goal is XeniaRunGoal.TitleSteadyState &&
                steadyStateReached &&
                goal.requiredStage == XeniaStartupStage.TITLE_RUNNING_HEADLESS
            ) {
                TimeUnit.SECONDS.toNanos(goal.observationWindowSeconds)
            } else {
                (finishedAtNanos - observedAt).coerceAtLeast(0L)
            }
            TimeUnit.NANOSECONDS.toSeconds(finishedNanos)
        } ?: 0L
        val cacheBackendStatus = detectCacheBackendStatusFromLog(finalGuestLog)
        val titleMetadataSeen = finalGuestLog.contains("Title name: ")
        val presentationBackend = request.args
            .firstOrNull { it.startsWith("--x360_presentation_backend=") }
            ?.substringAfter('=')
            ?: PresentationBackend.HEADLESS_ONLY.name.lowercase()
        val guestRenderScaleProfile = request.args
            .firstOrNull { it.startsWith("--draw_resolution_scale_x=") }
            ?.substringAfter('=')
            ?.let { scale -> if (scale == "1") "one" else "unsupported:$scale" }
            ?: "one"
        val internalDisplayResolution = buildString {
            val width = request.args
                .firstOrNull { it.startsWith("--internal_display_resolution_x=") }
                ?.substringAfter('=')
                ?: "1280"
            val height = request.args
                .firstOrNull { it.startsWith("--internal_display_resolution_y=") }
                ?.substringAfter('=')
                ?: "720"
            append(width)
            append('x')
            append(height)
        }
        val classification = when {
            goal.isSatisfiedBy(startupAnalysis.stage) -> ExitClassification.SUCCESS
            startupAnalysis.stage == XeniaStartupStage.FAILED -> ExitClassification.PROCESS_ERROR
            exitCode != null && exitCode != 0 -> ExitClassification.PROCESS_ERROR
            timedOut -> ExitClassification.PROCESS_ERROR
            else -> ExitClassification.VALIDATION_ERROR
        }
        val detail = when {
            goal.isSatisfiedBy(startupAnalysis.stage) && goal is XeniaRunGoal.TitleSteadyState ->
                buildString {
                    append("Xenia stayed alive for ")
                    append(aliveAfterModuleLoadSeconds)
                    append("s after title_module_loading")
                    if (goal.requiredStage != XeniaStartupStage.TITLE_RUNNING_HEADLESS) {
                        append(" and reached ")
                        append(goal.requiredStage.name.lowercase())
                    }
                }
            goal.isSatisfiedBy(startupAnalysis.stage) ->
                "Xenia reached ${startupAnalysis.stage.name.lowercase()} via ${startupAnalysis.detail}"
            timedOut && goal is XeniaRunGoal.TitleSteadyState && steadyStateReached ->
                "Xenia reached title_running_headless but timed out before ${goal.requiredStage.name.lowercase()}"
            timedOut -> "Xenia bring-up timed out at ${startupAnalysis.stage.name.lowercase()}"
            else -> "Xenia stopped at ${startupAnalysis.stage.name.lowercase()}: ${startupAnalysis.detail}"
        }
        val result = GuestLaunchResult(
            sessionId = request.sessionId,
            exitClassification = classification,
            exitCode = exitCode,
            startedAt = startedAt,
            finishedAt = finishedAt,
            detail = detail,
        )
        logStore.writeLaunchResult(
            request,
            backendName,
            result,
            extras = mapOf(
                "xenia.startupStage" to startupAnalysis.stage.name.lowercase(),
                "xenia.aliveAfterModuleLoadSeconds" to aliveAfterModuleLoadSeconds.toString(),
                "xenia.cacheBackendStatus" to cacheBackendStatus,
                "xenia.cacheRootPath" to directories.xeniaWritableCacheHostRoot.toString(),
                "xenia.titleMetadataSeen" to titleMetadataSeen.toString(),
                "xenia.presentationBackend" to presentationBackend,
                "xenia.guestRenderScaleProfile" to guestRenderScaleProfile,
                "xenia.internalDisplayResolution" to internalDisplayResolution,
                "xenia.exportTargetFps" to (request.args.firstOrNull { it.startsWith("--x360_framebuffer_fps=") }
                    ?.substringAfter('=')
                    ?: "0"),
                "xenia.framebufferPath" to outputPreview.framebufferPath,
                "xenia.frameStreamStatus" to outputPreview.status,
                "xenia.sessionElapsedMillis" to sessionElapsedMillis.toString(),
                "xenia.issueSwapCount" to presentationMetrics.issueSwapCount.toString(),
                "xenia.captureSuccessCount" to presentationMetrics.captureSuccessCount.toString(),
                "xenia.exportFrameCount" to presentationMetrics.exportFrameCount.toString(),
                "xenia.swapFps" to presentationMetrics.swapFps.toString(),
                "xenia.captureFps" to presentationMetrics.captureFps.toString(),
                "xenia.exportFps" to presentationMetrics.exportFps.toString(),
                "xenia.frameSourceStatus" to presentationMetrics.frameSourceStatus,
                "xenia.lastFrameWidth" to (outputPreview.width ?: 0).toString(),
                "xenia.lastFrameHeight" to (outputPreview.height ?: 0).toString(),
                "xenia.lastFrameIndex" to outputPreview.frameIndex.toString(),
            ),
        )
        return result
    }

}

private sealed interface XeniaRunGoal {
    val timeoutSeconds: Long

    data class StopAtStage(
        val stage: XeniaStartupStage,
        val lingerMillis: Long = 1200L,
    ) : XeniaRunGoal {
        override val timeoutSeconds: Long =
            if (stage.reaches(XeniaStartupStage.TITLE_MODULE_LOADING)) 120L else 30L
    }

    data class TitleSteadyState(
        val observationWindowSeconds: Long = 30L,
        val startupTimeoutSeconds: Long = 150L,
        val requiredStage: XeniaStartupStage = XeniaStartupStage.TITLE_RUNNING_HEADLESS,
        val presentationBackend: PresentationBackend = PresentationBackend.HEADLESS_ONLY,
    ) : XeniaRunGoal {
        override val timeoutSeconds: Long = startupTimeoutSeconds + observationWindowSeconds
    }

    fun isSatisfiedBy(stage: XeniaStartupStage): Boolean {
        return when (this) {
            is StopAtStage -> stage.reaches(this.stage)
            is TitleSteadyState -> stage.reaches(this.requiredStage)
        }
    }
}

private fun detectCacheBackendStatusFromLog(guestLog: String): String {
    val disabledBackends = buildList {
        if (guestLog.contains("Module info cache disabled:")) {
            add("module-info")
        }
        if (guestLog.contains("persistent shader storage will be disabled")) {
            add("shader-storage")
        }
        if (guestLog.contains("VkPipelineCache disk backing disabled")) {
            add("pipeline-cache")
        }
    }
    return if (disabledBackends.isEmpty()) {
        "ready"
    } else {
        "degraded:${disabledBackends.joinToString(",")}"
    }
}

internal class SessionLogStore(
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

    fun writeLaunchResult(
        request: GuestLaunchRequest,
        backend: String,
        result: GuestLaunchResult,
        extras: Map<String, String> = emptyMap(),
    ) {
        writeAppSummary(request, backend, result, extras)
    }

    fun writeLaunchFailure(
        request: GuestLaunchRequest,
        backend: String,
        result: GuestLaunchResult,
        extras: Map<String, String> = emptyMap(),
    ) {
        writeAppSummary(request, backend, result, extras)
        append(request.logDestinations.fexLog, "error=${result.detail}\n")
        append(request.logDestinations.guestLog, "error=${result.detail}\n")
    }

    fun writeLaunchRunning(
        request: GuestLaunchRequest,
        backend: String,
        extras: Map<String, String> = emptyMap(),
    ) {
        overwrite(
            request.logDestinations.appLog,
            buildList {
                add("session=${request.sessionId}")
                add("layer=app")
                add("backend=$backend")
                add("workingDir=${request.workingDirectory}")
                add("exitClassification=RUNNING")
                add("exitCode=none")
                add("detail=Player session running")
                extras.forEach { (key, value) -> add("$key=$value") }
            },
        )
    }

    fun appendGuestLine(path: Path, line: String) {
        append(path, "$line\n")
    }

    fun appendAppFields(
        path: Path,
        fields: Map<String, String>,
    ) {
        append(
            path,
            fields.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" },
        )
    }

    fun pump(input: InputStream, destination: Path): Thread {
        return thread(start = true, isDaemon = true) {
            destination.parent?.createDirectories()
            try {
                input.use { source ->
                    destination.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { sink ->
                        source.copyTo(sink)
                    }
                }
            } catch (_: IOException) {
                // Process teardown may close stdout/stderr while the pump is still reading.
                // Logging is best-effort here, so treat stream closure as a normal shutdown path.
            }
        }
    }

    private fun writeAppSummary(
        request: GuestLaunchRequest,
        backend: String,
        result: GuestLaunchResult,
        extras: Map<String, String> = emptyMap(),
    ) {
        overwrite(
            request.logDestinations.appLog,
            buildList {
                add("session=${request.sessionId}")
                add("layer=app")
                add("backend=$backend")
                add("workingDir=${request.workingDirectory}")
                add("exitClassification=${result.exitClassification}")
                add("exitCode=${result.exitCode ?: "none"}")
                add("detail=${result.detail}")
                extras.forEach { (key, value) -> add("$key=$value") }
            },
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

}

internal data class SessionLogs(
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

private object SessionIdFactory {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    fun create(): String = formatter.format(Instant.now())
}
