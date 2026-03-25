package emu.x360.mobile.dev

import android.view.KeyEvent
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

internal enum class ControllerBindingAction(
    val defaultKeyCode: Int,
    val label: String,
) {
    START(KeyEvent.KEYCODE_BUTTON_START, "Start"),
    BACK(KeyEvent.KEYCODE_BUTTON_SELECT, "Back"),
    A(KeyEvent.KEYCODE_BUTTON_A, "A"),
    B(KeyEvent.KEYCODE_BUTTON_B, "B"),
    X(KeyEvent.KEYCODE_BUTTON_X, "X"),
    Y(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
    DPAD_UP(KeyEvent.KEYCODE_DPAD_UP, "D-Pad Up"),
    DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN, "D-Pad Down"),
    DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT, "D-Pad Left"),
    DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, "D-Pad Right"),
    LEFT_SHOULDER(KeyEvent.KEYCODE_BUTTON_L1, "LB"),
    RIGHT_SHOULDER(KeyEvent.KEYCODE_BUTTON_R1, "RB"),
    LEFT_TRIGGER(KeyEvent.KEYCODE_BUTTON_L2, "LT"),
    RIGHT_TRIGGER(KeyEvent.KEYCODE_BUTTON_R2, "RT"),
    LEFT_THUMB(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3"),
    RIGHT_THUMB(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3"),
    ;
}

internal data class ControllerButtonBinding(
    val action: ControllerBindingAction,
    val keyCode: Int,
)

internal data class ControllerMappingProfile(
    val version: Int = 1,
    val bindings: List<ControllerButtonBinding> = ControllerBindingAction.entries.map { action ->
        ControllerButtonBinding(action = action, keyCode = action.defaultKeyCode)
    },
) {
    fun bindingActionForKeyCode(keyCode: Int): ControllerBindingAction? {
        return bindings.lastOrNull { it.keyCode == keyCode }?.action
    }

    fun keyCodeFor(action: ControllerBindingAction): Int {
        return bindings.firstOrNull { it.action == action }?.keyCode ?: action.defaultKeyCode
    }

    fun withBinding(
        action: ControllerBindingAction,
        keyCode: Int,
    ): ControllerMappingProfile {
        val updated = bindings
            .filterNot { it.action == action || it.keyCode == keyCode } +
            ControllerButtonBinding(action = action, keyCode = keyCode)
        return copy(bindings = updated.sortedBy { it.action.ordinal })
    }

    companion object {
        val Default = ControllerMappingProfile()
    }
}

internal class ControllerMappingStore(
    baseDir: Path,
) {
    private val settingsRoot: Path = baseDir.resolve("settings")
    private val mappingFile: Path = settingsRoot.resolve("controller-mapping.json")

    fun load(): ControllerMappingProfile {
        if (!mappingFile.exists()) {
            return ControllerMappingProfile.Default
        }
        return runCatching {
            val properties = Properties()
            mappingFile.inputStream().use { input ->
                properties.load(input)
            }
            val version = properties.getProperty("version")?.toIntOrNull() ?: 1
            val bindings = ControllerBindingAction.entries.map { action ->
                ControllerButtonBinding(
                    action = action,
                    keyCode = properties.getProperty(action.name)?.toIntOrNull() ?: action.defaultKeyCode,
                )
            }
            ControllerMappingProfile(version = version, bindings = bindings)
        }.getOrDefault(ControllerMappingProfile.Default)
    }

    fun save(profile: ControllerMappingProfile) {
        settingsRoot.createDirectories()
        val properties = Properties()
        properties["version"] = profile.version.toString()
        profile.bindings.forEach { binding ->
            properties[binding.action.name] = binding.keyCode.toString()
        }
        mappingFile.outputStream().use { output ->
            properties.store(output, "X360 Mobile controller mapping")
        }
    }

    fun update(transform: (ControllerMappingProfile) -> ControllerMappingProfile): ControllerMappingProfile {
        val updated = transform(load())
        save(updated)
        return updated
    }

    fun reset(): ControllerMappingProfile {
        return ControllerMappingProfile.Default.also(::save)
    }
}

internal fun controllerKeyCodeLabel(
    keyCode: Int,
): String {
    return KeyEvent.keyCodeToString(keyCode)
        .removePrefix("KEYCODE_")
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { it.uppercase() }
}
