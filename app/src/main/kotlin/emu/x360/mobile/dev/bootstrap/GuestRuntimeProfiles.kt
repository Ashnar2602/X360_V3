package emu.x360.mobile.dev.bootstrap

import emu.x360.mobile.dev.runtime.GameOptionsEntry
import emu.x360.mobile.dev.runtime.MesaRuntimeBranch
import emu.x360.mobile.dev.runtime.RuntimeDirectories

internal object GuestRuntimeMarkers {
    const val DynamicHello = "X360_DYN_HELLO_OK"
    const val VulkanProbe = "X360_VK_PROBE_OK"
}

internal enum class MarketplaceContentPolicy(
    val wireValue: String,
) {
    ENABLED("enabled"),
    DISABLED("disabled"),
}

internal const val MarketplaceContentPolicyEnvName = "X360_MARKETPLACE_CONTENT_POLICY"

internal fun GameOptionsEntry.marketplaceContentPolicy(): MarketplaceContentPolicy =
    if (dlcEnabledOverride == false) MarketplaceContentPolicy.DISABLED else MarketplaceContentPolicy.ENABLED

internal fun buildMarketplaceContentPolicyEnvironment(
    policy: MarketplaceContentPolicy,
): Map<String, String> = mapOf(MarketplaceContentPolicyEnvName to policy.wireValue)

internal fun buildHeadlessGuestEnvironment(): Map<String, String> {
    return mapOf(
        "GDK_BACKEND" to "offscreen",
        "SDL_VIDEODRIVER" to "dummy",
        "SDL_AUDIODRIVER" to "dummy",
        "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/nonexistent-dbus-socket",
        "NO_AT_BRIDGE" to "1",
    )
}

internal fun buildDynamicGuestEnvironment(): Map<String, String> {
    return buildHeadlessGuestEnvironment() + mapOf(
        "LD_LIBRARY_PATH" to "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu",
    )
}

internal fun buildVulkanGuestEnvironment(
    branch: MesaRuntimeBranch,
    directories: RuntimeDirectories,
): Map<String, String> {
    return when (branch) {
        MesaRuntimeBranch.AUTO -> error("AUTO must be resolved before building the Vulkan guest environment")
        MesaRuntimeBranch.LAVAPIPE -> buildDynamicGuestEnvironment() + mapOf(
            "VK_DRIVER_FILES" to "/usr/share/vulkan/icd.d/lvp_icd.json",
            "VK_LOADER_DEBUG" to "error,warn",
            "X360_DRIVER_MODE" to "lavapipe",
        )
        MesaRuntimeBranch.MESA25 -> buildDynamicGuestEnvironment() + mapOf(
            "LD_LIBRARY_PATH" to "${directories.mesa25LibRoot.toGuestPath(directories.rootfs)}:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu",
            "VK_DRIVER_FILES" to directories.mesa25TurnipIcd.toGuestPath(directories.rootfs),
            "VK_LOADER_DEBUG" to "error,warn",
            "X360_DRIVER_MODE" to "turnip",
        )
        MesaRuntimeBranch.MESA26 -> buildDynamicGuestEnvironment() + mapOf(
            "LD_LIBRARY_PATH" to "${directories.mesa26LibRoot.toGuestPath(directories.rootfs)}:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu",
            "VK_DRIVER_FILES" to directories.mesa26TurnipIcd.toGuestPath(directories.rootfs),
            "VK_LOADER_DEBUG" to "error,warn",
            "X360_DRIVER_MODE" to "turnip",
        )
    }
}

internal fun java.nio.file.Path.toGuestPath(rootfs: java.nio.file.Path): String {
    val normalizedRoot = rootfs.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
    val normalizedPath = toAbsolutePath().normalize().toString().replace('\\', '/')
    return when {
        normalizedPath == normalizedRoot -> "/"
        normalizedPath.startsWith("$normalizedRoot/") -> "/" + normalizedPath.removePrefix("$normalizedRoot/")
        else -> "/" + normalizedPath.trimStart('/')
    }
}
