import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class MesaRuntimeSourceKind {
    @SerialName("archive")
    ARCHIVE,

    @SerialName("git")
    GIT,
}

@Serializable
data class MesaRuntimeSourceLock(
    val profile: String,
    val bundles: List<MesaRuntimeSourceBundle>,
)

@Serializable
data class MesaRuntimeSourceBundle(
    val branch: String,
    val mesaVersion: String,
    val sourceKind: MesaRuntimeSourceKind = MesaRuntimeSourceKind.ARCHIVE,
    val sourceUrl: String,
    val sourceSha256: String? = null,
    val sourceRef: String? = null,
    val sourceRevision: String? = null,
    val installProfile: String,
    val patchSetId: String = "none",
)

@Serializable
data class GeneratedMesaRuntimeMetadata(
    val profile: String,
    val bundles: List<GeneratedMesaRuntimeBundleMetadata>,
)

@Serializable
data class GeneratedMesaRuntimeBundleMetadata(
    val branch: String,
    val mesaVersion: String,
    val sourceKind: MesaRuntimeSourceKind,
    val sourceUrl: String,
    val sourceSha256: String? = null,
    val sourceRef: String? = null,
    val sourceRevision: String? = null,
    val installProfile: String,
    val patchSetId: String,
    val appliedPatches: List<String>,
    val libRoot: String,
    val icdPath: String,
    val driverLibraryName: String,
    val bundledDependencies: List<String>,
)

object MesaRuntimeLockCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(raw: String): MesaRuntimeSourceLock = json.decodeFromString(raw)

    fun encode(lock: MesaRuntimeSourceLock): String = json.encodeToString(lock)

    fun encodeMetadata(metadata: GeneratedMesaRuntimeMetadata): String = json.encodeToString(metadata)
}
