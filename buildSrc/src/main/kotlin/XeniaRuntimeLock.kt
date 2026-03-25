import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class XeniaSourceBuildLock(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
    val buildProfile: String,
    val patchSetId: String = "none",
)

@Serializable
data class XeniaGamePatchesLock(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
)

@Serializable
data class GeneratedXeniaBuildMetadata(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
    val buildProfile: String,
    val patchSetId: String,
    val executableName: String,
    val executableSha256: String,
    val runtimeLibraries: List<GeneratedXeniaRuntimeLibrary>,
    val requiredPackages: List<GeneratedXeniaGuestPackage>,
)

@Serializable
data class GeneratedXeniaGamePatchesMetadata(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
    val fileCount: Int,
    val titleCount: Int,
    val files: List<GeneratedXeniaGamePatchFile>,
)

@Serializable
data class GeneratedXeniaRuntimeLibrary(
    val soname: String,
    val sourcePath: String,
    val installPath: String,
    val packageName: String,
    val packageVersion: String,
)

@Serializable
data class GeneratedXeniaGuestPackage(
    val packageName: String,
    val packageVersion: String,
    val archiveName: String,
    val archiveSha256: String,
)

@Serializable
data class GeneratedXeniaGamePatchFile(
    val relativePath: String,
    val sha256: String,
)

object XeniaRuntimeLockCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(raw: String): XeniaSourceBuildLock = json.decodeFromString(raw)

    fun encode(lock: XeniaSourceBuildLock): String = json.encodeToString(lock)

    fun decodeGamePatchesLock(raw: String): XeniaGamePatchesLock = json.decodeFromString(raw)

    fun encodeGamePatchesLock(lock: XeniaGamePatchesLock): String = json.encodeToString(lock)

    fun encodeMetadata(metadata: GeneratedXeniaBuildMetadata): String = json.encodeToString(metadata)

    fun encodeGamePatchesMetadata(metadata: GeneratedXeniaGamePatchesMetadata): String =
        json.encodeToString(metadata)
}
