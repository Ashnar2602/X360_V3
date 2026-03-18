package emu.x360.mobile.dev.runtime

import kotlinx.serialization.Serializable

@Serializable
data class FexBuildMetadata(
    val fexCommit: String,
    val patchSetId: String,
    val abi: String,
    val artifacts: List<FexArtifactMetadata>,
)

@Serializable
data class FexArtifactMetadata(
    val name: String,
    val sha256: String,
    val neededLibraries: List<String>,
)

object FexBuildMetadataCodec {
    fun decode(raw: String): FexBuildMetadata = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(metadata: FexBuildMetadata): String =
        RuntimeManifestCodec.json.encodeToString(FexBuildMetadata.serializer(), metadata)
}
