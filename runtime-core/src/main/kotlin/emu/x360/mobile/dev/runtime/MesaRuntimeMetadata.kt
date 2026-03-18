package emu.x360.mobile.dev.runtime

import kotlinx.serialization.Serializable

@Serializable
data class MesaRuntimeMetadata(
    val profile: String,
    val bundles: List<MesaRuntimeBundleMetadata>,
)

@Serializable
data class MesaRuntimeBundleMetadata(
    val branch: String,
    val mesaVersion: String,
    val sourceKind: String = "archive",
    val sourceUrl: String,
    val sourceSha256: String? = null,
    val sourceRef: String? = null,
    val sourceRevision: String? = null,
    val installProfile: String,
    val patchSetId: String = "none",
    val appliedPatches: List<String> = emptyList(),
    val libRoot: String,
    val icdPath: String,
    val driverLibraryName: String,
    val bundledDependencies: List<String>,
)

object MesaRuntimeMetadataCodec {
    fun decode(raw: String): MesaRuntimeMetadata = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(metadata: MesaRuntimeMetadata): String =
        RuntimeManifestCodec.json.encodeToString(MesaRuntimeMetadata.serializer(), metadata)
}

enum class MesaRuntimeBranch {
    AUTO,
    MESA25,
    MESA26,
    LAVAPIPE,
}
