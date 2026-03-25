package emu.x360.mobile.dev.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressionDiagnosticsModelsTest {
    @Test
    fun `player session diagnostics bundle codec round-trips core fields`() {
        val bundle = PlayerSessionDiagnosticsBundle(
            sessionId = "20260325-101010-123",
            capturedAtEpochMillis = 1_742_891_000_000L,
            startupStage = "title_running_headless",
            startupDetail = "MoviePlayer2 Decode Thread created",
            titleName = "Dante's Inferno",
            titleId = "454108CF",
            moduleHash = "5C20AFE8455FEE7D",
            progressionBucket = ProgressionStallClassification.GUEST_PROGRESS_STALLED,
            progressionReason = "movie decode thread alive but no content transition",
            lastMeaningfulGuestTransition = "ContentManager::OpenContent(...)",
            lastContentCallResult = "X360_CONTENT_MISS=OpenContent ...",
            lastXamCallResult = "XamContentOpenFile root=game path=default.xex",
            lastXliveCallResult = "Unimplemented XLIVEBASE message 0005000E",
            lastXnetCallResult = "XNetLogonGetTitleID offline stub success",
            appLogPath = "/data/user/0/emu.x360.mobile.dev/files/logs/app/session.app.log",
            fexLogPath = "/data/user/0/emu.x360.mobile.dev/files/logs/fex/session.fex.log",
            guestLogPath = "/data/user/0/emu.x360.mobile.dev/files/logs/guest/session.guest.log",
            launchSummary = PlayerSessionLaunchSummary(
                entryId = "dante",
                entryDisplayName = "Dante's Inferno.iso",
                guestTitlePath = "/mnt/library/dante.iso",
                presentationBackend = "framebuffer_shared_memory",
                guestRenderScaleProfile = "one",
                internalDisplayResolution = "1280x720",
                readbackResolveMode = "full",
                apuBackend = "sdl",
                mesaBranch = "mesa26",
                mesaReason = "auto-adreno830-baseline",
                diagnosticProfile = "default",
                args = listOf("--gpu=vulkan", "--headless=true"),
                environmentSummary = mapOf("X360_FRAME_TRANSPORT_PATH" to "/tmp/x360-v3/xenia/presentation/session/frame-transport.bin"),
            ),
            storageRoots = listOf(
                DiagnosticRootAccessState(
                    label = "content-root",
                    hostPath = "/tmp/x360-v3/xenia/content",
                    guestPath = "/tmp/x360-v3/xenia/content",
                    exists = true,
                    readable = true,
                    writable = true,
                    detail = "exists,readable,writable",
                ),
            ),
            installedContent = listOf(
                InstalledTitleContentSummary(
                    id = "tu2",
                    displayName = "Dante TU2",
                    installStatus = "installed",
                    contentType = "Marketplace Content",
                    packageSignature = "LIVE",
                ),
            ),
            patchState = PlayerSessionPatchState(
                databasePresent = true,
                revision = "4814fb128ae2ed060569840708196542823547de",
                fileCount = 100,
                bundledTitleCount = 473,
                loadedTitleCount = 473,
                titleMatchState = "matching-patch-candidate",
                appliedPatches = listOf("Patcher: Applying patch for Dante"),
            ),
            presentation = PlayerSessionPresentationSnapshot(
                framebufferPath = "/tmp/x360-v3/xenia/presentation/session/frame-transport.bin",
                frameStreamStatus = "active",
                frameWidth = 1280,
                frameHeight = 720,
                frameIndex = 4812,
                frameFreshnessSeconds = 0,
                issueSwapCount = 4900,
                captureSuccessCount = 4898,
                exportFrameCount = 4812,
                decodedFrameCount = 4800,
                presentedFrameCount = 4798,
                guestSwapFps = 44.0f,
                captureFps = 43.8f,
                transportPublishFps = 43.7f,
                decodeFps = 43.7f,
                visiblePresentFps = 43.6f,
                visibleFps = 43.6f,
                transportFrameHash = "a1b2c3d4",
                visibleFrameHash = "deadbeef",
            ),
            input = PlayerSessionInputSnapshot(
                controllerConnected = true,
                controllerName = "Xbox Wireless Controller",
                lastInputSequence = 42,
                lastInputAgeMs = 18,
            ),
            host = PlayerSessionHostSnapshot(
                board = "sun",
                hardware = "qcom",
                socModel = "cq8725s",
                androidRelease = "15",
                androidApiLevel = 35,
                availableAppBytes = 123456789L,
                guestProcessAlive = true,
                guestProcessPid = 9876,
                mesaBranch = "mesa26",
                mesaReason = "auto-adreno830-baseline",
                uriPermissionPresent = true,
                fexWarningSummary = listOf("warning: none"),
            ),
        )

        val decoded = PlayerSessionDiagnosticsBundleCodec.decode(
            PlayerSessionDiagnosticsBundleCodec.encode(bundle),
        )

        assertThat(decoded.sessionId).isEqualTo(bundle.sessionId)
        assertThat(decoded.progressionBucket).isEqualTo(ProgressionStallClassification.GUEST_PROGRESS_STALLED)
        assertThat(decoded.patchState.titleMatchState).isEqualTo("matching-patch-candidate")
        assertThat(decoded.presentation.transportFrameHash).isEqualTo("a1b2c3d4")
        assertThat(decoded.input.controllerName).isEqualTo("Xbox Wireless Controller")
    }
}
