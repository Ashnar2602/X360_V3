package emu.x360.mobile.dev.runtime

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeManifest(
    val version: Int,
    val profile: String,
    val generatedBy: String,
    val assets: List<RuntimeAsset>,
)

@Serializable
data class RuntimeAsset(
    val assetPath: String,
    val installPath: String,
    val executable: Boolean = false,
    val checksumSha256: String? = null,
    val minPhase: RuntimePhase = RuntimePhase.BOOTSTRAP,
)

@Serializable
enum class RuntimePhase {
    @SerialName("bootstrap")
    BOOTSTRAP,

    @SerialName("fex-baseline")
    FEX_BASELINE,

    @SerialName("vulkan-baseline")
    VULKAN_BASELINE,

    @SerialName("turnip-baseline")
    TURNIP_BASELINE,

    @SerialName("xenia-bringup")
    XENIA_BRINGUP,

    ;

    fun includes(requiredPhase: RuntimePhase): Boolean = ordinal >= requiredPhase.ordinal
}

sealed interface RuntimeInstallState {
    data object NotInstalled : RuntimeInstallState

    data class Installed(
        val rootDirectory: String,
        val manifestFingerprint: String,
        val installedAt: Instant,
        val installedPhase: RuntimePhase,
    ) : RuntimeInstallState

    data class Invalid(
        val issue: RuntimeInstallIssue,
    ) : RuntimeInstallState
}

sealed interface RuntimeInstallIssue {
    data class MissingAsset(val assetPath: String) : RuntimeInstallIssue

    data class MissingInstalledFile(val installPath: String) : RuntimeInstallIssue

    data class InvalidInstallPath(val installPath: String) : RuntimeInstallIssue

    data class ChecksumMismatch(
        val installPath: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : RuntimeInstallIssue
}

data class RuntimeDirectories(
    val baseDir: Path,
) {
    val rootfs: Path = baseDir.resolve("rootfs")
    val rootfsLib: Path = rootfs.resolve("lib")
    val rootfsLib64: Path = rootfs.resolve("lib64")
    val rootfsLibX86_64LinuxGnu: Path = rootfsLib.resolve("x86_64-linux-gnu")
    val rootfsTmp: Path = rootfs.resolve("tmp")
    val rootfsProc: Path = rootfs.resolve("proc")
    val rootfsDev: Path = rootfs.resolve("dev")
    val rootfsEtc: Path = rootfs.resolve("etc")
    val rootfsUsr: Path = rootfs.resolve("usr")
    val rootfsUsrLib: Path = rootfsUsr.resolve("lib")
    val rootfsUsrLibX86_64LinuxGnu: Path = rootfsUsrLib.resolve("x86_64-linux-gnu")
    val rootfsUsrShare: Path = rootfsUsr.resolve("share")
    val rootfsUsrShareVulkan: Path = rootfsUsrShare.resolve("vulkan")
    val rootfsUsrShareVulkanIcdD: Path = rootfsUsrShareVulkan.resolve("icd.d")
    val rootfsUsrShareX360V3: Path = rootfsUsrShare.resolve("x360-v3")
    val rootfsOpt: Path = rootfs.resolve("opt")
    val rootfsOptX360V3: Path = rootfsOpt.resolve("x360-v3")
    val rootfsMesaRoot: Path = rootfsOptX360V3.resolve("mesa")
    val mesa25Root: Path = rootfsMesaRoot.resolve("mesa25")
    val mesa25LibRoot: Path = mesa25Root.resolve("lib")
    val mesa25IcdRoot: Path = mesa25Root.resolve("icd")
    val mesa25TurnipDriver: Path = mesa25LibRoot.resolve("libvulkan_freedreno.so")
    val mesa25TurnipIcd: Path = mesa25IcdRoot.resolve("turnip_icd.json")
    val mesa26Root: Path = rootfsMesaRoot.resolve("mesa26")
    val mesa26LibRoot: Path = mesa26Root.resolve("lib")
    val mesa26IcdRoot: Path = mesa26Root.resolve("icd")
    val mesa26TurnipDriver: Path = mesa26LibRoot.resolve("libvulkan_freedreno.so")
    val mesa26TurnipIcd: Path = mesa26IcdRoot.resolve("turnip_icd.json")
    val fexEmuHome: Path = baseDir.resolve(".fex-emu")
    val fexConfigFile: Path = fexEmuHome.resolve("Config.json")
    val payload: Path = baseDir.resolve("payload")
    val payloadBin: Path = payload.resolve("bin")
    val payloadConfig: Path = payload.resolve("config")
    val payloadGuestTests: Path = payload.resolve("guest-tests")
    val payloadGuestTestsBin: Path = payloadGuestTests.resolve("bin")
    val helloFixture: Path = payloadGuestTestsBin.resolve("hello_x86_64")
    val dynamicHelloFixture: Path = payloadGuestTestsBin.resolve("dyn_hello_x86_64")
    val vulkanProbeFixture: Path = payloadGuestTestsBin.resolve("vulkan_probe_x86_64")
    val guestRuntimeMetadata: Path = payloadConfig.resolve("guest-runtime-metadata.json")
    val guestRuntimeLockManifest: Path = payloadConfig.resolve("ubuntu-24.04-amd64-lvp.lock.json")
    val mesaRuntimeMetadata: Path = payloadConfig.resolve("mesa-runtime-metadata.json")
    val mesaRuntimeLockManifest: Path = payloadConfig.resolve("mesa-turnip-source-lock.json")
    val glibcLoader: Path = rootfsLib64.resolve("ld-linux-x86-64.so.2")
    val guestVulkanLoader: Path = rootfsUsrLibX86_64LinuxGnu.resolve("libvulkan.so.1")
    val guestLavapipeDriver: Path = rootfsUsrLibX86_64LinuxGnu.resolve("libvulkan_lvp.so")
    val guestLavapipeIcd: Path = rootfsUsrShareVulkanIcdD.resolve("lvp_icd.json")
    val logs: Path = baseDir.resolve("logs")
    val appLogs: Path = logs.resolve("app")
    val fexLogs: Path = logs.resolve("fex")
    val guestLogs: Path = logs.resolve("guest")
    val fexHelloSentinel: Path = rootfsTmp.resolve("fex_hello_ok.json")
    val dynamicHelloSentinel: Path = rootfsTmp.resolve("fex_dyn_hello_ok.json")
    val vulkanProbeSentinel: Path = rootfsTmp.resolve("fex_vulkan_probe.json")
    val installMarker: Path = baseDir.resolve(".runtime-install-state.json")

    fun requiredDirectories(): List<Path> = listOf(
        baseDir,
        rootfs,
        rootfsLib,
        rootfsLib64,
        rootfsLibX86_64LinuxGnu,
        rootfsTmp,
        rootfsProc,
        rootfsDev,
        rootfsEtc,
        rootfsUsr,
        rootfsUsrLib,
        rootfsUsrLibX86_64LinuxGnu,
        rootfsUsrShare,
        rootfsUsrShareVulkan,
        rootfsUsrShareVulkanIcdD,
        rootfsUsrShareX360V3,
        rootfsOpt,
        rootfsOptX360V3,
        rootfsMesaRoot,
        mesa25Root,
        mesa25LibRoot,
        mesa25IcdRoot,
        mesa26Root,
        mesa26LibRoot,
        mesa26IcdRoot,
        fexEmuHome,
        payload,
        payloadBin,
        payloadConfig,
        payloadGuestTests,
        payloadGuestTestsBin,
        logs,
        appLogs,
        fexLogs,
        guestLogs,
    )
}

data class GuestLogDestinations(
    val appLog: Path,
    val fexLog: Path,
    val guestLog: Path,
)

data class GuestLaunchRequest(
    val sessionId: String,
    val executable: String,
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
    val logDestinations: GuestLogDestinations,
)

data class GuestLaunchResult(
    val sessionId: String,
    val exitClassification: ExitClassification,
    val exitCode: Int?,
    val startedAt: Instant,
    val finishedAt: Instant,
    val detail: String,
)

enum class ExitClassification {
    SUCCESS,
    VALIDATION_ERROR,
    PROCESS_ERROR,
}

fun RuntimeManifest.filteredForPhase(targetPhase: RuntimePhase): RuntimeManifest {
    return copy(
        assets = assets.filter { targetPhase.includes(it.minPhase) },
    )
}
