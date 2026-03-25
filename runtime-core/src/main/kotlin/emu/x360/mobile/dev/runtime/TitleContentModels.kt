package emu.x360.mobile.dev.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TitleContentDatabase(
    val version: Int = 1,
    val entries: List<TitleContentEntry> = emptyList(),
)

@Serializable
data class TitleContentEntry(
    val id: String,
    val libraryEntryId: String,
    val uriString: String,
    val displayName: String,
    val titleId: String,
    val contentType: String,
    val contentTypeLabel: String,
    val packageSignature: String,
    val xuid: String,
    val installStatus: TitleContentInstallStatus,
    val installedDataPath: String? = null,
    val installedHeaderPath: String? = null,
    val lastInstallSummary: String? = null,
)

@Serializable
enum class TitleContentInstallStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("installed")
    INSTALLED,

    @SerialName("missing")
    MISSING,

    @SerialName("error")
    ERROR,
}

object TitleContentDatabaseCodec {
    fun decode(raw: String): TitleContentDatabase = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(database: TitleContentDatabase): String =
        RuntimeManifestCodec.json.encodeToString(TitleContentDatabase.serializer(), database)
}
