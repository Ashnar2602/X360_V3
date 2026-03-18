package emu.x360.mobile.dev.runtime

import kotlinx.serialization.Serializable

@Serializable
data class GuestRuntimeMetadata(
    val distribution: String,
    val release: String,
    val architecture: String,
    val profile: String,
    val activeIcd: String,
    val packages: List<GuestRuntimePackageMetadata>,
)

@Serializable
data class GuestRuntimePackageMetadata(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
)

object GuestRuntimeMetadataCodec {
    fun decode(raw: String): GuestRuntimeMetadata = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(metadata: GuestRuntimeMetadata): String =
        RuntimeManifestCodec.json.encodeToString(GuestRuntimeMetadata.serializer(), metadata)
}
