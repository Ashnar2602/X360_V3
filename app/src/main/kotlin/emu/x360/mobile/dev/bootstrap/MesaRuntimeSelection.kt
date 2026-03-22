package emu.x360.mobile.dev.bootstrap

import android.content.Context
import android.os.Build
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import java.io.File
import android.system.Os
import android.system.OsConstants

internal data class DeviceProperties(
    val board: String,
    val hardware: String,
    val socModel: String,
) {
    companion object {
        fun read(): DeviceProperties {
            val socModel = runCatching { Build.SOC_MODEL.orEmpty() }.getOrDefault("")
            return DeviceProperties(
                board = Build.BOARD.orEmpty(),
                hardware = Build.HARDWARE.orEmpty(),
                socModel = socModel,
            )
        }
    }
}

internal data class ResolvedMesaRuntime(
    val branch: MesaRuntimeBranch,
    val reason: String,
)

internal object DeviceMesaRuntimePolicy {
    private val mesa25Boards = setOf("kalama")
    private val mesa25SocModels = setOf("qcs8550")
    private val mesa26Boards = setOf("sun")
    private val mesa26SocModels = setOf("cq8725s")

    fun resolve(
        overrideMode: MesaRuntimeBranch,
        properties: DeviceProperties,
    ): ResolvedMesaRuntime {
        if (overrideMode != MesaRuntimeBranch.AUTO) {
            return ResolvedMesaRuntime(
                branch = overrideMode,
                reason = "manual-override:${overrideMode.name.lowercase()}",
            )
        }

        val board = properties.board.lowercase()
        val hardware = properties.hardware.lowercase()
        val socModel = properties.socModel.lowercase()
        return when {
            board in mesa25Boards || socModel in mesa25SocModels ->
                ResolvedMesaRuntime(MesaRuntimeBranch.MESA25, "auto-adreno740-baseline")
            board in mesa26Boards || socModel in mesa26SocModels ->
                ResolvedMesaRuntime(MesaRuntimeBranch.MESA26, "auto-adreno830-baseline")
            hardware.contains("qcom") ->
                ResolvedMesaRuntime(MesaRuntimeBranch.MESA25, "auto-qualcomm-fallback")
            else ->
                ResolvedMesaRuntime(MesaRuntimeBranch.LAVAPIPE, "auto-non-qualcomm-fallback")
        }
    }
}

internal class MesaRuntimeOverrideStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("x360-runtime-debug", Context.MODE_PRIVATE)

    fun read(): MesaRuntimeBranch {
        val raw = preferences.getString(KEY_OVERRIDE_MODE, MesaRuntimeBranch.AUTO.name).orEmpty()
        return runCatching { MesaRuntimeBranch.valueOf(raw) }.getOrDefault(MesaRuntimeBranch.AUTO)
    }

    fun write(mode: MesaRuntimeBranch) {
        preferences.edit().putString(KEY_OVERRIDE_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_OVERRIDE_MODE = "mesa_override_mode"
    }
}

internal data class KgslAccessStatus(
    val accessible: Boolean,
    val detail: String,
    val devicePath: String = "/dev/kgsl-3d0",
)

internal object KgslAccessInspector {
    fun inspect(): KgslAccessStatus {
        val devicePath = "/dev/kgsl-3d0"
        val file = File(devicePath)
        if (!file.exists()) {
            return KgslAccessStatus(
                accessible = false,
                detail = "device node missing",
                devicePath = devicePath,
            )
        }

        return runCatching {
            val descriptor = Os.open(devicePath, OsConstants.O_RDWR, 0)
            Os.close(descriptor)
            KgslAccessStatus(
                accessible = true,
                detail = "open(O_RDWR) succeeded",
                devicePath = devicePath,
            )
        }.getOrElse { throwable ->
            KgslAccessStatus(
                accessible = false,
                detail = throwable.message ?: throwable::class.simpleName.orEmpty(),
                devicePath = devicePath,
            )
        }
    }
}
