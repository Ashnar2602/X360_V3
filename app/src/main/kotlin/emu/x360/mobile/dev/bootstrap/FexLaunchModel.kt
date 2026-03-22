package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.GuestLaunchRequest
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import java.nio.file.Path

internal data class FexLaunchSpec(
    val command: List<String>,
    val environment: Map<String, String>,
)

internal fun buildFexLaunchSpec(
    loaderPath: Path,
    directories: RuntimeDirectories,
    request: GuestLaunchRequest,
): FexLaunchSpec {
    val environment = request.environment.toMutableMap()
    request.inheritedFileDescriptors.forEach { descriptor ->
        environment[descriptor.name] = descriptor.fd.toString()
    }
    environment["HOME"] = directories.baseDir.toString()
    environment["FEX_ROOTFS"] = directories.rootfs.toString()
    environment["FEX_DISABLESANDBOX"] = "1"
    return FexLaunchSpec(
        command = listOf(loaderPath.toString(), request.executable) + request.args,
        environment = environment.toMap(),
    )
}

internal fun buildFexConfig(directories: RuntimeDirectories): String {
    return """
        |{
        |  "Config": {
        |    "RootFS": "${directories.rootfs.toString().jsonEscape()}",
        |    "TSOEnabled": "1",
        |    "SilentLog": "0"
        |  }
        |}
    """.trimMargin()
}

private fun String.jsonEscape(): String = buildString(length + 8) {
    for (char in this@jsonEscape) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
