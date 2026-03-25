package emu.x360.mobile.dev.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GameLibraryDatabase(
    val version: Int = 1,
    val entries: List<GameLibraryEntry> = emptyList(),
)

@Serializable
data class GameLibraryEntry(
    val id: String,
    val sourceKind: GameLibrarySourceKind,
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val importedAt: Long,
    val lastKnownStatus: GameLibraryEntryStatus,
    val lastResolvedGuestPath: String? = null,
    val lastLaunchSummary: String? = null,
    val lastKnownTitleName: String? = null,
    val lastKnownTitleId: String? = null,
    val lastKnownModuleHash: String? = null,
)

@Serializable
enum class GameLibrarySourceKind {
    @SerialName("iso")
    ISO,
}

@Serializable
enum class GameLibraryEntryStatus {
    @SerialName("ready")
    READY,

    @SerialName("missing")
    MISSING,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("error")
    ERROR,
}

object GameLibraryDatabaseCodec {
    fun decode(raw: String): GameLibraryDatabase = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(database: GameLibraryDatabase): String =
        RuntimeManifestCodec.json.encodeToString(GameLibraryDatabase.serializer(), database)
}
