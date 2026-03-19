package emu.x360.mobile.dev.runtime

import kotlinx.serialization.Serializable

@Serializable
data class XeniaSourceLock(
    val sourceUrl: String,
    val sourceRef: String,
    val sourceRevision: String,
    val buildProfile: String,
    val patchSetId: String = "none",
)

object XeniaSourceLockCodec {
    fun decode(raw: String): XeniaSourceLock = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(lock: XeniaSourceLock): String =
        RuntimeManifestCodec.json.encodeToString(XeniaSourceLock.serializer(), lock)
}
