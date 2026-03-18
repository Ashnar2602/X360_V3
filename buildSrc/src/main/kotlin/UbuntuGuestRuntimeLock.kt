import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UbuntuGuestRuntimeLock(
    val distribution: String,
    val release: String,
    val architecture: String,
    val profile: String,
    val activeIcd: String,
    val packages: List<UbuntuGuestRuntimePackage>,
)

@Serializable
data class UbuntuGuestRuntimePackage(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
    val files: List<UbuntuGuestRuntimeFile>,
)

@Serializable
data class UbuntuGuestRuntimeFile(
    val source: String,
    val installPath: String,
)

@Serializable
data class GeneratedGuestRuntimeMetadata(
    val distribution: String,
    val release: String,
    val architecture: String,
    val profile: String,
    val activeIcd: String,
    val packages: List<GeneratedGuestRuntimePackageMetadata>,
)

@Serializable
data class GeneratedGuestRuntimePackageMetadata(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
)

object UbuntuGuestRuntimeLockCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(raw: String): UbuntuGuestRuntimeLock = json.decodeFromString(raw)

    fun encode(lock: UbuntuGuestRuntimeLock): String = json.encodeToString(lock)

    fun encodeMetadata(metadata: GeneratedGuestRuntimeMetadata): String = json.encodeToString(metadata)
}
