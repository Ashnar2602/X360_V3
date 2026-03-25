package emu.x360.mobile.dev.runtime

import kotlinx.serialization.Serializable

@Serializable
data class XeniaGamePatchesLock(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
)

@Serializable
data class XeniaGamePatchesMetadata(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
    val fileCount: Int,
    val titleCount: Int,
    val files: List<XeniaGamePatchFileMetadata>,
)

@Serializable
data class XeniaGamePatchFileMetadata(
    val relativePath: String,
    val sha256: String,
)

object XeniaGamePatchesLockCodec {
    fun decode(raw: String): XeniaGamePatchesLock = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(lock: XeniaGamePatchesLock): String =
        RuntimeManifestCodec.json.encodeToString(XeniaGamePatchesLock.serializer(), lock)
}

object XeniaGamePatchesMetadataCodec {
    fun decode(raw: String): XeniaGamePatchesMetadata = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(metadata: XeniaGamePatchesMetadata): String =
        RuntimeManifestCodec.json.encodeToString(XeniaGamePatchesMetadata.serializer(), metadata)
}
