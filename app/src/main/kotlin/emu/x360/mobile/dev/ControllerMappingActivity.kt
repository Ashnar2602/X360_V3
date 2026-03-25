package emu.x360.mobile.dev

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import emu.x360.mobile.dev.ui.theme.X360RebuildTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ControllerMappingActivity : ComponentActivity() {
    private val viewModel by viewModels<ControllerMappingViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            X360RebuildTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    ControllerMappingScreen(
                        state = state,
                        onBack = ::finish,
                        onBeginCapture = viewModel::beginCapture,
                        onResetDefaults = viewModel::resetDefaults,
                        onCancelCapture = viewModel::cancelCapture,
                    )
                }
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (viewModel.isCapturing()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                viewModel.cancelCapture()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return super.onKeyDown(keyCode, event)
            }
            viewModel.captureBinding(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, ControllerMappingActivity::class.java)
        }
    }
}

internal class ControllerMappingViewModel(
    application: android.app.Application,
) : AndroidViewModel(application) {
    private val store = ControllerMappingStore(application.applicationContext.filesDir.toPath())
    private val mutableState = MutableStateFlow(ControllerMappingUiState.from(store.load()))
    val uiState: StateFlow<ControllerMappingUiState> = mutableState.asStateFlow()

    fun beginCapture(action: ControllerBindingAction) {
        mutableState.update { current ->
            current.copy(
                capturingAction = action,
                statusMessage = "Press a controller button for ${action.label}",
            )
        }
    }

    fun cancelCapture() {
        mutableState.update { current ->
            current.copy(
                capturingAction = null,
                statusMessage = "Capture cancelled",
            )
        }
    }

    fun captureBinding(keyCode: Int) {
        val action = mutableState.value.capturingAction ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = store.update { current -> current.withBinding(action, keyCode) }
            mutableState.value = ControllerMappingUiState.from(
                profile = updated,
                statusMessage = "${action.label} bound to ${controllerKeyCodeLabel(keyCode)}",
            )
        }
    }

    fun resetDefaults() {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = store.reset()
            mutableState.value = ControllerMappingUiState.from(
                profile = updated,
                statusMessage = "Controller mapping reset to defaults",
            )
        }
    }

    fun isCapturing(): Boolean = mutableState.value.capturingAction != null
}

internal data class ControllerMappingUiState(
    val bindings: List<ControllerMappingBindingUi> = emptyList(),
    val capturingAction: ControllerBindingAction? = null,
    val statusMessage: String = "Use Change, then press a controller button.",
) {
    companion object {
        fun from(
            profile: ControllerMappingProfile,
            statusMessage: String = "Use Change, then press a controller button.",
        ): ControllerMappingUiState {
            return ControllerMappingUiState(
                bindings = ControllerBindingAction.entries.map { action ->
                    ControllerMappingBindingUi(
                        action = action,
                        label = action.label,
                        keyLabel = controllerKeyCodeLabel(profile.keyCodeFor(action)),
                    )
                },
                statusMessage = statusMessage,
            )
        }
    }
}

internal data class ControllerMappingBindingUi(
    val action: ControllerBindingAction,
    val label: String,
    val keyLabel: String,
)

@Composable
private fun ControllerMappingScreen(
    state: ControllerMappingUiState,
    onBack: () -> Unit,
    onBeginCapture: (ControllerBindingAction) -> Unit,
    onResetDefaults: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF07131D), Color(0xFF0E2230), Color(0xFF163547)),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Text(
                text = "Controller Mapping",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFF5F7FA),
                fontWeight = FontWeight.Bold,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC11212E)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Digital controller buttons are remappable here. Analog sticks and trigger axes still use automatic detection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE6ECF2),
                )
                Text(
                    text = state.capturingAction?.let { "Listening for ${it.label}..." } ?: state.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAFC0CF),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onResetDefaults) {
                        Text("Reset Defaults")
                    }
                    if (state.capturingAction != null) {
                        OutlinedButton(onClick = onCancelCapture) {
                            Text("Cancel Capture")
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.bindings, key = { it.action.name }) { binding ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC122231)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = binding.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFF5F7FA),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = binding.keyLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAFC0CF),
                            )
                        }
                        Button(onClick = { onBeginCapture(binding.action) }) {
                            Text("Change")
                        }
                    }
                }
            }
        }
    }
}
