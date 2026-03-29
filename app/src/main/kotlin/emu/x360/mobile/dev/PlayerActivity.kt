package emu.x360.mobile.dev

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import emu.x360.mobile.dev.bootstrap.DiagnosticLaunchProfile
import emu.x360.mobile.dev.bootstrap.RollingPresentationMetricsTracker
import emu.x360.mobile.dev.bootstrap.SharedFramePresentationReader
import emu.x360.mobile.dev.runtime.PresentationPerformanceMetrics
import emu.x360.mobile.dev.runtime.PresentationBackend
import emu.x360.mobile.dev.runtime.SharedFrameTransportFrame
import emu.x360.mobile.dev.runtime.XeniaFramebufferCodec
import emu.x360.mobile.dev.runtime.XeniaFramebufferHeader
import emu.x360.mobile.dev.ui.theme.X360RebuildTheme
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {
    private val viewModel by viewModels<PlayerViewModel>()
    private val controllerMappingStore by lazy { ControllerMappingStore(applicationContext.filesDir.toPath()) }
    private var controllerInputMapper = AndroidControllerInputMapper()
    private val inputManager by lazy { getSystemService(InputManager::class.java) }
    private val inputEventRateCounter = RollingRateCounter()
    private var controllerInputMuted: Boolean = false
    private var overlayHidden: Boolean = false
    @Volatile
    private var lastLifecycleEvent: String = "activity-created"
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshConnectedControllers()

        override fun onInputDeviceRemoved(deviceId: Int) = refreshConnectedControllers()

        override fun onInputDeviceChanged(deviceId: Int) = refreshConnectedControllers()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastLifecycleEvent = "activity-onCreate"
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val diagnosticProfile = DiagnosticLaunchProfile.fromWireName(
            intent.getStringExtra(EXTRA_DIAGNOSTIC_PROFILE),
        )
        controllerInputMuted = intent.getBooleanExtra(EXTRA_INPUT_MUTED, false)
        overlayHidden = intent.getBooleanExtra(EXTRA_OVERLAY_HIDDEN, false)
        if (entryId.isNullOrBlank()) {
            finish()
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode()
        controllerInputMapper.reloadProfile(controllerMappingStore.load())
        setContent {
            X360RebuildTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(entryId) {
                    viewModel.start(
                        entryId = entryId,
                        diagnosticProfile = diagnosticProfile,
                        inputMuted = controllerInputMuted,
                        overlayHidden = overlayHidden,
                    )
                }
                PlayerScreen(
                    state = state,
                    openPresentationReader = viewModel::openPresentationReader,
                    onMetrics = viewModel::reportVisibleMetrics,
                    onPausePlayer = viewModel::pause,
                    onResumePlayer = viewModel::resume,
                    onSetShowFpsCounter = viewModel::setShowFpsCounter,
                    onOpenControllerMapping = {
                        startActivity(ControllerMappingActivity.intent(this))
                    },
                    overlayHidden = overlayHidden,
                    currentInputEventsPerSecond = { inputEventRateCounter.ratePerSecond },
                    currentLifecycleEvent = { lastLifecycleEvent },
                    onExitHome = {
                        viewModel.stop()
                        finish()
                    },
                    onExitAndroid = {
                        viewModel.stop("exit-android")
                        finishAffinity()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        lastLifecycleEvent = if (hasFocus) "window-focus-gained" else "window-focus-lost"
        if (hasFocus) {
            setImmersiveMode()
        }
    }

    override fun onStart() {
        super.onStart()
        lastLifecycleEvent = "activity-onStart"
        controllerInputMapper.reloadProfile(controllerMappingStore.load())
        inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        refreshConnectedControllers()
    }

    override fun onResume() {
        super.onResume()
        lastLifecycleEvent = "activity-onResume"
    }

    override fun onPause() {
        lastLifecycleEvent = "activity-onPause"
        super.onPause()
    }

    override fun onStop() {
        lastLifecycleEvent = "activity-onStop"
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        super.onStop()
    }

    override fun onDestroy() {
        lastLifecycleEvent = "activity-onDestroy"
        if (isFinishing) {
            viewModel.stop("activity-destroy")
        }
        super.onDestroy()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            if (controllerInputMuted && isControllerInputEvent(event)) {
                return true
            }
            controllerInputMapper.onKeyDown(event)?.let { update ->
                inputEventRateCounter.record()
                viewModel.submitControllerInput(update.copy(inputEventsPerSecond = inputEventRateCounter.ratePerSecond))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            if (controllerInputMuted && isControllerInputEvent(event)) {
                return true
            }
            controllerInputMapper.onKeyUp(event)?.let { update ->
                inputEventRateCounter.record()
                viewModel.submitControllerInput(update.copy(inputEventsPerSecond = inputEventRateCounter.ratePerSecond))
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (controllerInputMuted && isControllerMotionEvent(event)) {
            return true
        }
        controllerInputMapper.onGenericMotionEvent(event)?.let { update ->
            inputEventRateCounter.record()
            viewModel.submitControllerInput(update.copy(inputEventsPerSecond = inputEventRateCounter.ratePerSecond))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun refreshConnectedControllers() {
        val devices = inputManager
            ?.inputDeviceIds
            ?.toList()
            ?.mapNotNull { deviceId -> InputDevice.getDevice(deviceId) }
            ?: emptyList()
        controllerInputMapper.onControllerDevicesChanged(devices)?.let { update ->
            viewModel.submitControllerInput(update)
        }
    }

    private fun setImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_ENTRY_ID = "entry_id"
        private const val EXTRA_DIAGNOSTIC_PROFILE = "diagnostic_profile"
        private const val EXTRA_INPUT_MUTED = "input_muted"
        private const val EXTRA_OVERLAY_HIDDEN = "overlay_hidden"

        internal fun intent(
            context: Context,
            entryId: String,
            diagnosticProfile: DiagnosticLaunchProfile = DiagnosticLaunchProfile.XENIA_REAL_TITLE,
            inputMuted: Boolean = false,
            overlayHidden: Boolean = false,
        ): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_DIAGNOSTIC_PROFILE, diagnosticProfile.wireName)
                .putExtra(EXTRA_INPUT_MUTED, inputMuted)
                .putExtra(EXTRA_OVERLAY_HIDDEN, overlayHidden)
        }
    }
}

@Composable
private fun PlayerScreen(
    state: PlayerSessionUiState,
    openPresentationReader: () -> SharedFramePresentationReader?,
    onMetrics: (PresentationPerformanceMetrics) -> Unit,
    onPausePlayer: () -> Boolean,
    onResumePlayer: () -> Boolean,
    onSetShowFpsCounter: (Boolean) -> Unit,
    onOpenControllerMapping: () -> Unit,
    overlayHidden: Boolean,
    currentInputEventsPerSecond: () -> Float,
    currentLifecycleEvent: () -> String,
    onExitHome: () -> Unit,
    onExitAndroid: () -> Unit,
) {
    val sessionKey = state.sessionId ?: state.framebufferPath
    val context = LocalContext.current
    val sharedMemoryBackend = state.presentationBackend == PresentationBackend.FRAMEBUFFER_SHARED_MEMORY.name.lowercase()
    val imageView = rememberPlayerImageView(context)
    var overlayState by remember(sessionKey) {
        mutableStateOf(PlayerOverlayState.placeholder(state.detail))
    }
    var pauseOverlay by remember(sessionKey) {
        mutableStateOf(PlayerPauseOverlay.NONE)
    }

    fun openPauseMenu() {
        if (!state.isPaused) {
            onPausePlayer()
        }
        pauseOverlay = PlayerPauseOverlay.MENU
    }

    fun resumeAndCloseOverlay() {
        onResumePlayer()
        pauseOverlay = PlayerPauseOverlay.NONE
    }

    BackHandler {
        when (pauseOverlay) {
            PlayerPauseOverlay.NONE -> openPauseMenu()
            PlayerPauseOverlay.OPTIONS -> pauseOverlay = PlayerPauseOverlay.MENU
            PlayerPauseOverlay.MENU -> resumeAndCloseOverlay()
        }
    }

    LaunchedEffect(
        sessionKey,
        sharedMemoryBackend,
        state.playerPollIntervalMs,
        state.keepLastVisibleFrame,
        imageView,
    ) {
        val tracker = RollingPresentationMetricsTracker()
        var lastOverlayReportAtMillis = 0L
        var lastMetricsReportAtMillis = 0L
        var lastUiTickAtMillis = System.currentTimeMillis()
        if (sharedMemoryBackend) {
            val reader = openPresentationReader()
            if (reader == null) {
                overlayState = PlayerOverlayState.placeholder("Shared-memory presentation session is unavailable")
                return@LaunchedEffect
            }
            try {
                tracker.onSurfaceEvent("shared-memory-reader-ready")
                var lastFrameIndex = Long.MIN_VALUE
                var lastBuffer: PlayerBitmapBuffer? = null
                while (currentCoroutineContext().isActive) {
                    val loopStartedAtMillis = System.currentTimeMillis()
                    tracker.onInputRateObserved(currentInputEventsPerSecond())
                    tracker.onLifecycleEvent(currentLifecycleEvent())
                    val uiGapMillis = loopStartedAtMillis - lastUiTickAtMillis
                    if (uiGapMillis >= 700L) {
                        tracker.onUiLongFrameObserved(uiGapMillis)
                    }
                    lastUiTickAtMillis = loopStartedAtMillis
                    val frame = withContext(Dispatchers.IO) {
                        reader.awaitNextFrame(
                            lastFrameIndex = lastFrameIndex,
                            timeoutMillis = state.playerPollIntervalMs.coerceAtLeast(16L),
                        )
                    }
                    if (frame != null) {
                        val updatedFrame = withContext(Dispatchers.Default) {
                            updatePlayerBitmapBufferFromSharedFrame(frame, lastBuffer)
                        }
                        tracker.onExportObserved(
                            frame.header.frameIndex,
                            updatedFrame.transportSummary.rawHash,
                            updatedFrame.transportSummary.perceptualHash,
                        )
                        tracker.onDecoded()
                        lastBuffer = updatedFrame.buffer
                        if (imageView.tag !== updatedFrame.buffer.bitmap) {
                            imageView.setImageBitmap(updatedFrame.buffer.bitmap)
                            imageView.tag = updatedFrame.buffer.bitmap
                        }
                        imageView.postInvalidateOnAnimation()
                        tracker.onSurfaceEvent("shared-memory-frame-submitted")
                        tracker.onPresented(
                            frame.header.frameIndex,
                            updatedFrame.visibleSummary.rawHash,
                            updatedFrame.visibleSummary.perceptualHash,
                        )
                        tracker.onScreenObserved(
                            screenFrameHash = updatedFrame.visibleSummary.rawHash,
                            screenPerceptualHash = updatedFrame.visibleSummary.perceptualHash,
                            blackRatio = updatedFrame.visibleSummary.blackRatio,
                            averageLuma = updatedFrame.visibleSummary.averageLuma,
                        )
                        lastFrameIndex = frame.header.frameIndex
                    }

                    val nowMillis = System.currentTimeMillis()
                    val metrics = tracker.snapshot(
                        frameSourceStatus = if (lastFrameIndex >= 0L) "active" else "idle",
                    )
                    val message = when {
                        lastFrameIndex >= 0L -> "Frame $lastFrameIndex"
                        else -> state.detail
                    }
                    if (overlayState.hasFrame != (lastFrameIndex >= 0L) ||
                        overlayState.message != message ||
                        nowMillis - lastOverlayReportAtMillis >= 250L
                    ) {
                        overlayState = PlayerOverlayState(
                            hasFrame = lastFrameIndex >= 0L,
                            message = message,
                            metrics = metrics,
                        )
                        lastOverlayReportAtMillis = nowMillis
                    }
                    if (metrics.presentedFrameCount > 0L &&
                        nowMillis - lastMetricsReportAtMillis >= 250L
                    ) {
                        onMetrics(metrics)
                        lastMetricsReportAtMillis = nowMillis
                    }
                }
            } finally {
                reader.close()
            }
        } else {
            tracker.onSurfaceEvent("polling-reader-ready")
            var lastModifiedMillis = Long.MIN_VALUE
            var lastFrameIndex = Long.MIN_VALUE
            var lastBuffer: PlayerBitmapBuffer? = null
            while (currentCoroutineContext().isActive) {
                val loopStartedAtMillis = System.currentTimeMillis()
                tracker.onInputRateObserved(currentInputEventsPerSecond())
                tracker.onLifecycleEvent(currentLifecycleEvent())
                val uiGapMillis = loopStartedAtMillis - lastUiTickAtMillis
                if (uiGapMillis >= 700L) {
                    tracker.onUiLongFrameObserved(uiGapMillis)
                }
                lastUiTickAtMillis = loopStartedAtMillis
                val updated = withContext(Dispatchers.IO) {
                    readPlayerFrameState(
                        framebufferPath = state.framebufferPath,
                        fallbackMessage = state.detail,
                        lastModifiedMillis = lastModifiedMillis,
                        lastFrameIndex = lastFrameIndex,
                        lastBuffer = lastBuffer,
                        keepLastVisibleFrame = state.keepLastVisibleFrame,
                        tracker = tracker,
                    )
                }
                val bitmap = updated.buffer?.bitmap
                if (bitmap != null) {
                    if (imageView.tag !== bitmap) {
                        imageView.setImageBitmap(bitmap)
                        imageView.tag = bitmap
                    }
                    imageView.postInvalidateOnAnimation()
                    tracker.onSurfaceEvent("polling-frame-submitted")
                }

                val nowMillis = System.currentTimeMillis()
                if (overlayState.hasFrame != updated.hasFrame ||
                    overlayState.message != updated.message ||
                    nowMillis - lastOverlayReportAtMillis >= 250L
                ) {
                    overlayState = PlayerOverlayState(
                        hasFrame = updated.hasFrame,
                        message = updated.message,
                        metrics = updated.metrics,
                    )
                    lastOverlayReportAtMillis = nowMillis
                }

                if (updated.metrics.presentedFrameCount > 0L &&
                    nowMillis - lastMetricsReportAtMillis >= 250L
                ) {
                    onMetrics(updated.metrics)
                    lastMetricsReportAtMillis = nowMillis
                }

                lastModifiedMillis = updated.modifiedAtMillis ?: Long.MIN_VALUE
                lastFrameIndex = updated.frameIndex
                lastBuffer = updated.buffer
                delay(state.playerPollIntervalMs.coerceAtLeast(1L))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { imageView },
            modifier = Modifier.fillMaxSize(),
            update = { },
        )

        if (!overlayState.hasFrame) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.titleName ?: "Launching title",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFFF4F8FB),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = overlayState.message,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCFD9E1),
                    )
                }
            }
        }

        if (!overlayHidden && state.showFpsCounter && overlayState.hasFrame) {
            Text(
                text = String.format(Locale.US, "Visible %.1f FPS", overlayState.metrics.visibleFps),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color(0x88000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFF4F8FB),
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (!overlayHidden && state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(Color(0xAA2A0D12), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFD7DB),
            )
        }

        if (pauseOverlay != PlayerPauseOverlay.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x8A000000)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE6172634)),
                ) {
                    when (pauseOverlay) {
                        PlayerPauseOverlay.MENU -> PauseMenuCard(
                            title = state.titleName ?: "Game paused",
                            onResume = ::resumeAndCloseOverlay,
                            onOpenOptions = { pauseOverlay = PlayerPauseOverlay.OPTIONS },
                            onExitHome = onExitHome,
                            onExitAndroid = onExitAndroid,
                        )
                        PlayerPauseOverlay.OPTIONS -> PauseOptionsCard(
                            showFpsCounter = state.showFpsCounter,
                            presentationBackend = state.presentationBackend,
                            renderScaleProfile = state.renderScaleProfile,
                            controllerConnected = state.controllerConnected,
                            controllerName = state.controllerName,
                            lastInputAgeMs = state.lastInputAgeMs,
                            titleId = state.titleId,
                            moduleHash = state.moduleHash,
                            patchDatabaseLoadedTitleCount = state.patchDatabaseLoadedTitleCount,
                            progressionBucket = state.progressionBucket,
                            progressionReason = state.progressionReason,
                            lastMeaningfulTransition = state.lastMeaningfulTransition,
                            videoFreezeCause = state.videoFreezeCause,
                            videoFreezeConfidencePercent = state.videoFreezeConfidencePercent,
                            videoFreezeReason = state.videoFreezeReason,
                            frameIndex = state.frameIndex,
                            lastInputSequence = state.lastInputSequence,
                            onSetShowFpsCounter = onSetShowFpsCounter,
                            onOpenControllerMapping = onOpenControllerMapping,
                            onBack = { pauseOverlay = PlayerPauseOverlay.MENU },
                        )
                        PlayerPauseOverlay.NONE -> Unit
                    }
                }
            }
        }
    }
}

private fun isControllerInputEvent(event: KeyEvent): Boolean {
    val source = event.source
    return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
}

private fun isControllerMotionEvent(event: MotionEvent): Boolean {
    val source = event.source
    return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}

private enum class PlayerPauseOverlay {
    NONE,
    MENU,
    OPTIONS,
}

private data class PlayerFrameState(
    val buffer: PlayerBitmapBuffer?,
    val frameIndex: Long,
    val modifiedAtMillis: Long?,
    val message: String,
    val metrics: PresentationPerformanceMetrics,
) {
    val hasFrame: Boolean
        get() = buffer != null

    companion object {
        fun placeholder(message: String): PlayerFrameState {
            return PlayerFrameState(
                buffer = null,
                frameIndex = -1L,
                modifiedAtMillis = null,
                message = message,
                metrics = PresentationPerformanceMetrics.Empty,
            )
        }
    }
}

private data class PlayerOverlayState(
    val hasFrame: Boolean,
    val message: String,
    val metrics: PresentationPerformanceMetrics,
) {
    companion object {
        fun placeholder(message: String): PlayerOverlayState {
            return PlayerOverlayState(
                hasFrame = false,
                message = message,
                metrics = PresentationPerformanceMetrics.Empty,
            )
        }
    }
}

@Composable
private fun PauseMenuCard(
    title: String,
    onResume: () -> Unit,
    onOpenOptions: () -> Unit,
    onExitHome: () -> Unit,
    onExitAndroid: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFF4F8FB),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Il gioco e stato messo in pausa.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCFD9E1),
        )
        Button(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Riprendi")
        }
        OutlinedButton(
            onClick = onOpenOptions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Opzioni")
        }
        OutlinedButton(
            onClick = onExitHome,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Esci alla Home")
        }
        OutlinedButton(
            onClick = onExitAndroid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Esci ad Android")
        }
    }
}

@Composable
private fun PauseOptionsCard(
    showFpsCounter: Boolean,
    presentationBackend: String,
    renderScaleProfile: String,
    controllerConnected: Boolean,
    controllerName: String?,
    lastInputAgeMs: Long?,
    titleId: String?,
    moduleHash: String?,
    patchDatabaseLoadedTitleCount: Int,
    progressionBucket: String,
    progressionReason: String,
    lastMeaningfulTransition: String?,
    videoFreezeCause: String,
    videoFreezeConfidencePercent: Int,
    videoFreezeReason: String,
    frameIndex: Long,
    lastInputSequence: Long,
    onSetShowFpsCounter: (Boolean) -> Unit,
    onOpenControllerMapping: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Opzioni rapide",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFF4F8FB),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Il gioco resta in pausa mentre modifichi queste opzioni.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCFD9E1),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "FPS Counter",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFF4F8FB),
                )
                Text(
                    text = "Mostra o nasconde il contatore Visible FPS in alto a sinistra.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAFC0CF),
                    lineHeight = 18.sp,
                )
            }
            Switch(
                checked = showFpsCounter,
                onCheckedChange = onSetShowFpsCounter,
            )
        }
        Text(
            text = "Backend: $presentationBackend",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE6ECF2),
        )
        Text(
            text = "Render scale: $renderScaleProfile",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE6ECF2),
        )
        Text(
            text = buildString {
                append("Controller: ")
                append(if (controllerConnected) "connected" else "not connected")
                controllerName?.takeIf { it.isNotBlank() }?.let {
                    append(" (")
                    append(it)
                    append(')')
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE6ECF2),
        )
        Text(
            text = if (lastInputAgeMs != null) {
                "Last input: ${lastInputAgeMs} ms ago"
            } else {
                "Last input: none yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
        )
        Text(
            text = "Il remap dei tasti digitali apre una schermata dedicata. Gli stick analogici restano su auto-detect in questa fase.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
            lineHeight = 18.sp,
        )
        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFF4F8FB),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Frame index: $frameIndex | Last input seq: $lastInputSequence",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
        )
        Text(
            text = "Title ID: ${titleId ?: "unknown"} | Module hash: ${moduleHash ?: "unknown"}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
        )
        Text(
            text = "Patch DB loaded titles: $patchDatabaseLoadedTitleCount",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
        )
        Text(
            text = "Progression bucket: $progressionBucket",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
        )
        Text(
            text = "Video freeze cause: $videoFreezeCause (${videoFreezeConfidencePercent}%)",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
        )
        Text(
            text = "Reason: $progressionReason",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
            lineHeight = 18.sp,
        )
        Text(
            text = "Freeze reason: $videoFreezeReason",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
            lineHeight = 18.sp,
        )
        Text(
            text = "Last guest transition: ${lastMeaningfulTransition ?: "none"}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAFC0CF),
            lineHeight = 18.sp,
        )
        OutlinedButton(
            onClick = onOpenControllerMapping,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Controller Mapping")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Torna al menu pausa")
        }
    }
}

private class PlayerBitmapBuffer(
    primaryBitmap: Bitmap,
    secondaryBitmap: Bitmap,
    val sourceBytes: ByteArray,
    val scratchBytes: ByteArray,
    val scratchBuffer: ByteBuffer,
) {
    private val bitmaps = arrayOf(primaryBitmap, secondaryBitmap)
    private var nextBitmapIndex = 0

    var bitmap: Bitmap = bitmaps[0]
        private set

    fun commitScratchBufferToBitmap() {
        val targetBitmap = bitmaps[nextBitmapIndex]
        scratchBuffer.rewind()
        targetBitmap.copyPixelsFromBuffer(scratchBuffer)
        scratchBuffer.rewind()
        bitmap = targetBitmap
        nextBitmapIndex = (nextBitmapIndex + 1) % bitmaps.size
    }
}

private data class PlayerFramebufferSample(
    val header: XeniaFramebufferHeader,
    val buffer: PlayerBitmapBuffer,
)

private data class PlayerFrameAnalysis(
    val buffer: PlayerBitmapBuffer,
    val transportSummary: FreezeLabFrameSummary,
    val visibleSummary: FreezeLabFrameSummary,
)

private fun readPlayerFrameState(
    framebufferPath: String,
    fallbackMessage: String,
    lastModifiedMillis: Long,
    lastFrameIndex: Long,
    lastBuffer: PlayerBitmapBuffer?,
    keepLastVisibleFrame: Boolean,
    tracker: RollingPresentationMetricsTracker,
): PlayerFrameState {
    if (framebufferPath.isBlank()) {
        return PlayerFrameState.placeholder(fallbackMessage)
    }

    val path = File(framebufferPath).toPath()
    if (!path.exists()) {
        return PlayerFrameState.placeholder(fallbackMessage)
    }

    val modifiedMillis = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(lastModifiedMillis)
    val header = runCatching { readPlayerFramebufferHeader(path) }.getOrNull()
    if (header == null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = fallbackMessage,
            metrics = tracker.snapshot(frameSourceStatus = "invalid"),
        )
    }

    if (header.frameIndex == lastFrameIndex && lastBuffer != null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = if (modifiedMillis == lastModifiedMillis) {
                "Frame index unchanged"
            } else {
                "Frame header unchanged"
            },
            metrics = tracker.snapshot(frameSourceStatus = "stale"),
        )
    }

    val sample = runCatching { readPlayerFramebufferSample(path, header, lastBuffer) }.getOrNull()
    if (sample == null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = fallbackMessage,
            metrics = tracker.snapshot(frameSourceStatus = "invalid"),
        )
    }

    if (sample.header.frameIndex == lastFrameIndex && lastBuffer != null) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Frame header unchanged",
            metrics = tracker.snapshot(frameSourceStatus = "stale"),
        )
    }

    val analyzedFrame = updatePlayerBitmapBuffer(sample)
    tracker.onExportObserved(
        sample.header.frameIndex,
        analyzedFrame.transportSummary.rawHash,
        analyzedFrame.transportSummary.perceptualHash,
    )

    if (keepLastVisibleFrame &&
        lastBuffer != null &&
        !playerFrameHasVisibleContent(sample.buffer.sourceBytes, sample.header.minimumPayloadSize)
    ) {
        return PlayerFrameState(
            buffer = lastBuffer,
            frameIndex = lastFrameIndex,
            modifiedAtMillis = modifiedMillis,
            message = "Keeping the last visible frame while the current xenia_fb sample is black.",
            metrics = tracker.snapshot(frameSourceStatus = "black-sample"),
        )
    }

    tracker.onDecoded()
    tracker.onPresented(
        sample.header.frameIndex,
        analyzedFrame.visibleSummary.rawHash,
        analyzedFrame.visibleSummary.perceptualHash,
    )
    tracker.onScreenObserved(
        screenFrameHash = analyzedFrame.visibleSummary.rawHash,
        screenPerceptualHash = analyzedFrame.visibleSummary.perceptualHash,
        blackRatio = analyzedFrame.visibleSummary.blackRatio,
        averageLuma = analyzedFrame.visibleSummary.averageLuma,
    )
    return PlayerFrameState(
        buffer = analyzedFrame.buffer,
        frameIndex = sample.header.frameIndex,
        modifiedAtMillis = modifiedMillis,
        message = "Frame ${sample.header.frameIndex}",
        metrics = tracker.snapshot(frameSourceStatus = "active"),
    )
}

private fun readPlayerFramebufferHeader(
    path: java.nio.file.Path,
): XeniaFramebufferHeader {
    val headerBytes = ByteArray(PlayerFramebufferHeaderSize)
    path.inputStream().use { input ->
        readExactly(input, headerBytes, PlayerFramebufferHeaderSize)
    }
    return XeniaFramebufferCodec.decodeHeader(headerBytes)
}

private fun readPlayerFramebufferSample(
    path: java.nio.file.Path,
    header: XeniaFramebufferHeader,
    existing: PlayerBitmapBuffer?,
): PlayerFramebufferSample {
    val headerBytes = ByteArray(PlayerFramebufferHeaderSize)
    path.inputStream().use { input ->
        readExactly(input, headerBytes, PlayerFramebufferHeaderSize)
        val buffer = if (existing == null ||
            existing.bitmap.width != header.width ||
            existing.bitmap.height != header.height ||
            existing.sourceBytes.size != header.minimumPayloadSize
        ) {
            createPlayerBitmapBuffer(
                width = header.width,
                height = header.height,
                payloadSize = header.minimumPayloadSize,
            )
        } else {
            existing
        }
        readExactly(input, buffer.sourceBytes, header.minimumPayloadSize)
        return PlayerFramebufferSample(
            header = header,
            buffer = buffer,
        )
    }
}

private fun createPlayerBitmapBuffer(
    width: Int,
    height: Int,
    payloadSize: Int,
): PlayerBitmapBuffer {
    val scratchBytes = ByteArray(width * height * 4)
    return PlayerBitmapBuffer(
        primaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        secondaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        sourceBytes = ByteArray(payloadSize),
        scratchBytes = scratchBytes,
        scratchBuffer = ByteBuffer.wrap(scratchBytes),
    )
}

private fun updatePlayerBitmapBuffer(
    sample: PlayerFramebufferSample,
): PlayerFrameAnalysis {
    val header = sample.header
    val buffer = sample.buffer
    val width = header.width
    val height = header.height
    val stride = header.stride
    val sourceBytes = buffer.sourceBytes
    val scratchBytes = buffer.scratchBytes
    val rowByteCount = width * 4
    if (stride == rowByteCount) {
        System.arraycopy(sourceBytes, 0, scratchBytes, 0, rowByteCount * height)
    } else {
        var destinationIndex = 0
        for (y in 0 until height) {
            val rowStart = y * stride
            System.arraycopy(sourceBytes, rowStart, scratchBytes, destinationIndex, rowByteCount)
            destinationIndex += rowByteCount
        }
    }
    buffer.commitScratchBufferToBitmap()
    return PlayerFrameAnalysis(
        buffer = buffer,
        transportSummary = FreezeLabHashAnalyzer.analyzeRgba(
            rgbaBytes = buffer.sourceBytes,
            width = width,
            height = height,
            stride = stride,
        ),
        visibleSummary = FreezeLabHashAnalyzer.analyzeRgba(
            rgbaBytes = buffer.scratchBytes,
            width = width,
            height = height,
            stride = width * 4,
        ),
    )
}

private fun updatePlayerBitmapBufferFromSharedFrame(
    frame: SharedFrameTransportFrame,
    existing: PlayerBitmapBuffer?,
): PlayerFrameAnalysis {
    val header = frame.header
    val payloadSize = header.payloadSize
    val buffer = if (existing == null ||
        existing.bitmap.width != header.width ||
        existing.bitmap.height != header.height ||
        existing.sourceBytes.size != payloadSize
    ) {
        createPlayerBitmapBuffer(
            width = header.width,
            height = header.height,
            payloadSize = payloadSize,
        )
    } else {
        existing
    }

    System.arraycopy(frame.rgbaBytes, 0, buffer.sourceBytes, 0, payloadSize)
    copySharedFrameRowsToBitmapBuffer(
        rgbaBytes = buffer.sourceBytes,
        destination = buffer.scratchBytes,
        width = header.width,
        height = header.height,
        stride = header.stride,
    )
    buffer.commitScratchBufferToBitmap()
    return PlayerFrameAnalysis(
        buffer = buffer,
        transportSummary = FreezeLabHashAnalyzer.analyzeRgba(
            rgbaBytes = buffer.sourceBytes,
            width = header.width,
            height = header.height,
            stride = header.stride,
        ),
        visibleSummary = FreezeLabHashAnalyzer.analyzeRgba(
            rgbaBytes = buffer.scratchBytes,
            width = header.width,
            height = header.height,
            stride = header.width * 4,
        ),
    )
}

private fun copySharedFrameRowsToBitmapBuffer(
    rgbaBytes: ByteArray,
    destination: ByteArray,
    width: Int,
    height: Int,
    stride: Int,
) {
    val rowByteCount = width * 4
    if (stride == rowByteCount) {
        System.arraycopy(rgbaBytes, 0, destination, 0, rowByteCount * height)
        return
    }

    var destinationIndex = 0
    for (y in 0 until height) {
        val rowStart = y * stride
        System.arraycopy(rgbaBytes, rowStart, destination, destinationIndex, rowByteCount)
        destinationIndex += rowByteCount
    }
}

private fun playerFrameHasVisibleContent(
    rgbaBytes: ByteArray,
    payloadSize: Int,
): Boolean {
    var index = 0
    while (index + 3 < payloadSize) {
        val red = rgbaBytes[index].toInt() and 0xFF
        val green = rgbaBytes[index + 1].toInt() and 0xFF
        val blue = rgbaBytes[index + 2].toInt() and 0xFF
        if (red != 0 || green != 0 || blue != 0) {
            return true
        }
        index += 96
    }
    return false
}

private fun computePlayerFrameHash(
    bytes: ByteArray,
    byteCount: Int,
): String {
    if (byteCount <= 0 || bytes.isEmpty()) {
        return ""
    }
    var hash = 0x811C9DC5.toInt()
    val safeCount = byteCount.coerceAtMost(bytes.size)
    val step = (safeCount / 1024).coerceAtLeast(1)
    var index = 0
    while (index < safeCount) {
        hash = hash xor (bytes[index].toInt() and 0xFF)
        hash *= 0x01000193
        index += step
    }
    return Integer.toHexString(hash)
}

private fun readExactly(
    input: InputStream,
    destination: ByteArray,
    byteCount: Int,
) {
    var offset = 0
    while (offset < byteCount) {
        val read = input.read(destination, offset, byteCount - offset)
        require(read >= 0) {
            "xenia_fb read truncated: expected $byteCount bytes, got $offset"
        }
        offset += read
    }
}

@Composable
private fun rememberPlayerImageView(context: Context): ImageView {
    return remember(context) {
        ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(android.graphics.Color.BLACK)
            // Keep the player presenter on a software layer so mutable frame bitmaps
            // don't depend on device-specific HWUI texture refresh behavior.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }
}

private const val PlayerFramebufferHeaderSize = 32
