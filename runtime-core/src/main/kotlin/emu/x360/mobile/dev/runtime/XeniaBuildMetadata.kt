package emu.x360.mobile.dev.runtime

import kotlinx.serialization.Serializable

@Serializable
data class XeniaBuildMetadata(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
    val buildProfile: String,
    val patchSetId: String,
    val executableName: String,
    val executableSha256: String,
    val runtimeLibraries: List<XeniaRuntimeLibraryMetadata>,
    val requiredPackages: List<XeniaGuestPackageMetadata>,
)

@Serializable
data class XeniaRuntimeLibraryMetadata(
    val soname: String,
    val sourcePath: String,
    val installPath: String,
    val packageName: String,
    val packageVersion: String,
)

@Serializable
data class XeniaGuestPackageMetadata(
    val packageName: String,
    val packageVersion: String,
    val archiveName: String,
    val archiveSha256: String,
)

object XeniaBuildMetadataCodec {
    fun decode(raw: String): XeniaBuildMetadata = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(metadata: XeniaBuildMetadata): String =
        RuntimeManifestCodec.json.encodeToString(XeniaBuildMetadata.serializer(), metadata)
}
