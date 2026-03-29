package emu.x360.mobile.dev

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import emu.x360.mobile.dev.runtime.SharedInputButtons
import emu.x360.mobile.dev.runtime.SharedInputControllerState
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlayerControllerInputUpdate(
    val controllerState: SharedInputControllerState,
    val controllerName: String?,
    val inputEventsPerSecond: Float = 0f,
)

internal class AndroidControllerInputMapper(
    private var mappingProfile: ControllerMappingProfile = ControllerMappingProfile.Default,
) {
    private var state = SharedInputControllerState.Disconnected
    private var controllerName: String? = null
    private var activeControllerDeviceId: Int? = null

    fun onKeyDown(event: KeyEvent): PlayerControllerInputUpdate? = onKeyEvent(event, pressed = true)

    fun onKeyUp(event: KeyEvent): PlayerControllerInputUpdate? = onKeyEvent(event, pressed = false)

    fun onControllerDevicesChanged(
        devices: List<InputDevice>,
    ): PlayerControllerInputUpdate? {
        val primary = selectPrimaryController(devices)
        if (primary == null) {
            activeControllerDeviceId = null
            controllerName = null
            if (!state.connected && state.isNeutral()) {
                return null
            }
            state = SharedInputControllerState.Disconnected
            return PlayerControllerInputUpdate(
                controllerState = state,
                controllerName = controllerName,
            )
        }

        val controllerChanged = activeControllerDeviceId != primary.id || controllerName != primary.name
        activeControllerDeviceId = primary.id
        controllerName = primary.name
        if (state.connected && !controllerChanged) {
            return null
        }
        if (state.connected) {
            return PlayerControllerInputUpdate(
                controllerState = state,
                controllerName = controllerName,
            )
        }
        return updateState(SharedInputControllerState(connected = true))
    }

    fun reloadProfile(
        profile: ControllerMappingProfile,
    ) {
        mappingProfile = profile
    }

    fun onGenericMotionEvent(event: MotionEvent): PlayerControllerInputUpdate? {
        if (!isLikelyControllerMotionEvent(event)) {
            return null
        }
        captureDevice(event.device)
        val leftX = normalizeStick(event.getAxisValue(MotionEvent.AXIS_X))
        val leftY = normalizeStick(-event.getAxisValue(MotionEvent.AXIS_Y))
        val rightX = normalizeStick(
            firstAvailableAxis(
                event,
                MotionEvent.AXIS_RX,
                MotionEvent.AXIS_Z,
            ),
        )
        val rightY = normalizeStick(
            -firstAvailableAxis(
                event,
                MotionEvent.AXIS_RY,
                MotionEvent.AXIS_RZ,
            ),
        )
        val leftTrigger = resolveTrigger(
            event = event,
            primaryAxis = MotionEvent.AXIS_LTRIGGER,
            fallbackAxis = MotionEvent.AXIS_BRAKE,
            currentValue = state.leftTrigger,
        )
        val rightTrigger = resolveTrigger(
            event = event,
            primaryAxis = MotionEvent.AXIS_RTRIGGER,
            fallbackAxis = MotionEvent.AXIS_GAS,
            currentValue = state.rightTrigger,
        )
        val updatedButtons = applyHatButtons(
            baseButtons = state.buttons,
            hatX = firstAvailableAxis(event, MotionEvent.AXIS_HAT_X),
            hatY = firstAvailableAxis(event, MotionEvent.AXIS_HAT_Y),
        )
        val updated = state.copy(
            connected = true,
            buttons = updatedButtons,
            leftStickX = leftX,
            leftStickY = leftY,
            rightStickX = rightX,
            rightStickY = rightY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
        )
        return updateState(updated)
    }

    fun clear(): PlayerControllerInputUpdate? {
        if (!state.connected && state.isNeutral()) {
            return null
        }
        state = SharedInputControllerState.Disconnected
        return PlayerControllerInputUpdate(
            controllerState = state,
            controllerName = controllerName,
        )
    }

    companion object {
        private const val StickDeadzone = 0.18f
        private const val TriggerDeadzone = 0.05f

        internal fun normalizeStick(
            rawValue: Float,
            deadzone: Float = StickDeadzone,
        ): Int {
            val clamped = rawValue.coerceIn(-1f, 1f)
            val magnitude = abs(clamped)
            if (magnitude <= deadzone) {
                return 0
            }
            val scaled = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            return (scaled * Short.MAX_VALUE.toFloat())
                .roundToInt()
                .let { if (clamped < 0f) -it else it }
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }

        internal fun normalizeTrigger(
            rawValue: Float,
            deadzone: Float = TriggerDeadzone,
        ): Int {
            val clamped = rawValue.coerceIn(0f, 1f)
            if (clamped <= deadzone) {
                return 0
            }
            return (((clamped - deadzone) / (1f - deadzone)).coerceIn(0f, 1f) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
        }

        internal fun mapKeyCodeToButton(
            keyCode: Int,
        ): Int? {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> SharedInputButtons.A
                KeyEvent.KEYCODE_BUTTON_1 -> SharedInputButtons.A
                KeyEvent.KEYCODE_BUTTON_B -> SharedInputButtons.B
                KeyEvent.KEYCODE_BUTTON_2 -> SharedInputButtons.B
                KeyEvent.KEYCODE_BUTTON_X -> SharedInputButtons.X
                KeyEvent.KEYCODE_BUTTON_3 -> SharedInputButtons.X
                KeyEvent.KEYCODE_BUTTON_Y -> SharedInputButtons.Y
                KeyEvent.KEYCODE_BUTTON_4 -> SharedInputButtons.Y
                KeyEvent.KEYCODE_DPAD_UP -> SharedInputButtons.DPAD_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> SharedInputButtons.DPAD_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> SharedInputButtons.DPAD_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> SharedInputButtons.DPAD_RIGHT
                KeyEvent.KEYCODE_BUTTON_L1 -> SharedInputButtons.LEFT_SHOULDER
                KeyEvent.KEYCODE_BUTTON_5 -> SharedInputButtons.LEFT_SHOULDER
                KeyEvent.KEYCODE_BUTTON_R1 -> SharedInputButtons.RIGHT_SHOULDER
                KeyEvent.KEYCODE_BUTTON_6 -> SharedInputButtons.RIGHT_SHOULDER
                KeyEvent.KEYCODE_BUTTON_START -> SharedInputButtons.START
                KeyEvent.KEYCODE_BUTTON_8 -> SharedInputButtons.START
                KeyEvent.KEYCODE_MENU -> SharedInputButtons.START
                KeyEvent.KEYCODE_BUTTON_SELECT -> SharedInputButtons.BACK
                KeyEvent.KEYCODE_BUTTON_7 -> SharedInputButtons.BACK
                KeyEvent.KEYCODE_BUTTON_THUMBL -> SharedInputButtons.LEFT_THUMB
                KeyEvent.KEYCODE_BUTTON_9 -> SharedInputButtons.LEFT_THUMB
                KeyEvent.KEYCODE_BUTTON_THUMBR -> SharedInputButtons.RIGHT_THUMB
                KeyEvent.KEYCODE_BUTTON_10 -> SharedInputButtons.RIGHT_THUMB
                else -> null
            }
        }

        internal fun isControllerSource(source: Int): Boolean {
            return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        }

        internal fun isLikelyControllerDevice(
            device: InputDevice?,
        ): Boolean {
            if (device == null) {
                return false
            }
            if (isControllerSource(device.sources)) {
                return true
            }
            val gamepadKeys = device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT,
            )
            if (gamepadKeys.any { it }) {
                return true
            }
            val normalizedName = device.name.orEmpty().lowercase()
            return normalizedName.contains("controller") ||
                normalizedName.contains("gamepad") ||
                normalizedName.contains("joystick")
        }

        private fun hasAxis(
            event: MotionEvent,
            axis: Int,
        ): Boolean {
            return event.device?.getMotionRange(axis, event.source) != null
        }

        private fun firstAvailableAxis(
            event: MotionEvent,
            vararg axes: Int,
        ): Float {
            axes.forEachIndexed { index, axis ->
                if (hasAxis(event, axis)) {
                    val value = event.getAxisValue(axis)
                    if (index == axes.lastIndex || abs(value) > 0.001f) {
                        return value
                    }
                }
            }
            return 0f
        }

        private fun resolveTrigger(
            event: MotionEvent,
            primaryAxis: Int,
            fallbackAxis: Int,
            currentValue: Int,
        ): Int {
            return when {
                hasAxis(event, primaryAxis) -> normalizeTrigger(event.getAxisValue(primaryAxis))
                hasAxis(event, fallbackAxis) -> normalizeTrigger(event.getAxisValue(fallbackAxis))
                else -> currentValue
            }
        }

        private fun applyHatButtons(
            baseButtons: Int,
            hatX: Float,
            hatY: Float,
        ): Int {
            var buttons = baseButtons and (
                SharedInputButtons.DPAD_UP.inv() and
                    SharedInputButtons.DPAD_DOWN.inv() and
                    SharedInputButtons.DPAD_LEFT.inv() and
                    SharedInputButtons.DPAD_RIGHT.inv()
                )
            if (hatX <= -0.5f) {
                buttons = buttons or SharedInputButtons.DPAD_LEFT
            }
            if (hatX >= 0.5f) {
                buttons = buttons or SharedInputButtons.DPAD_RIGHT
            }
            if (hatY <= -0.5f) {
                buttons = buttons or SharedInputButtons.DPAD_UP
            }
            if (hatY >= 0.5f) {
                buttons = buttons or SharedInputButtons.DPAD_DOWN
            }
            return buttons
        }

        private fun selectPrimaryController(
            devices: List<InputDevice>,
        ): InputDevice? {
            return devices
                .filter { isLikelyControllerDevice(it) }
                .sortedWith(
                    compareByDescending<InputDevice> { it.isExternal }
                        .thenBy { it.id },
                )
                .firstOrNull()
        }

        private fun isLikelyControllerMotionEvent(
            event: MotionEvent,
        ): Boolean {
            return isControllerSource(event.source) || isLikelyControllerDevice(event.device)
        }
    }

    private fun onKeyEvent(
        event: KeyEvent,
        pressed: Boolean,
    ): PlayerControllerInputUpdate? {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return null
        }
        val mappedAction = mappingProfile.bindingActionForKeyCode(event.keyCode)
        if (mappedAction == null) {
            return null
        }
        if (!isControllerSource(event.source) && !isLikelyControllerDevice(event.device)) {
            return null
        }
        val controllerNameChanged = captureDevice(event.device)

        return when (mappedAction) {
            ControllerBindingAction.LEFT_TRIGGER -> updateState(
                state.copy(
                    connected = true,
                    leftTrigger = if (pressed) 255 else 0,
                ),
                forceEmit = controllerNameChanged,
            )
            ControllerBindingAction.RIGHT_TRIGGER -> updateState(
                state.copy(
                    connected = true,
                    rightTrigger = if (pressed) 255 else 0,
                ),
                forceEmit = controllerNameChanged,
            )
            else -> {
                val mappedButton = mappedAction.toSharedInputButton() ?: return null
                val updatedButtons = if (pressed) {
                    state.buttons or mappedButton
                } else {
                    state.buttons and mappedButton.inv()
                }
                updateState(
                    state.copy(
                        connected = true,
                        buttons = updatedButtons,
                    ),
                    forceEmit = controllerNameChanged,
                )
            }
        }
    }

    private fun captureDevice(device: InputDevice?): Boolean {
        val previousDeviceId = activeControllerDeviceId
        val previousName = controllerName
        if (device != null) {
            activeControllerDeviceId = device.id
            controllerName = device.name
            return activeControllerDeviceId != previousDeviceId || controllerName != previousName
        }
        if (activeControllerDeviceId != null) {
            controllerName = runCatching { InputDevice.getDevice(activeControllerDeviceId!!)?.name }.getOrNull() ?: controllerName
        }
        return activeControllerDeviceId != previousDeviceId || controllerName != previousName
    }

    private fun updateState(
        updated: SharedInputControllerState,
        forceEmit: Boolean = false,
    ): PlayerControllerInputUpdate? {
        if (!forceEmit && updated == state) {
            return null
        }
        state = updated
        return PlayerControllerInputUpdate(
            controllerState = state,
            controllerName = controllerName,
        )
    }
}

private fun ControllerBindingAction.toSharedInputButton(): Int? {
    return when (this) {
        ControllerBindingAction.DPAD_UP -> SharedInputButtons.DPAD_UP
        ControllerBindingAction.DPAD_DOWN -> SharedInputButtons.DPAD_DOWN
        ControllerBindingAction.DPAD_LEFT -> SharedInputButtons.DPAD_LEFT
        ControllerBindingAction.DPAD_RIGHT -> SharedInputButtons.DPAD_RIGHT
        ControllerBindingAction.START -> SharedInputButtons.START
        ControllerBindingAction.BACK -> SharedInputButtons.BACK
        ControllerBindingAction.LEFT_THUMB -> SharedInputButtons.LEFT_THUMB
        ControllerBindingAction.RIGHT_THUMB -> SharedInputButtons.RIGHT_THUMB
        ControllerBindingAction.LEFT_SHOULDER -> SharedInputButtons.LEFT_SHOULDER
        ControllerBindingAction.RIGHT_SHOULDER -> SharedInputButtons.RIGHT_SHOULDER
        ControllerBindingAction.A -> SharedInputButtons.A
        ControllerBindingAction.B -> SharedInputButtons.B
        ControllerBindingAction.X -> SharedInputButtons.X
        ControllerBindingAction.Y -> SharedInputButtons.Y
        ControllerBindingAction.LEFT_TRIGGER,
        ControllerBindingAction.RIGHT_TRIGGER,
        -> null
    }
}
