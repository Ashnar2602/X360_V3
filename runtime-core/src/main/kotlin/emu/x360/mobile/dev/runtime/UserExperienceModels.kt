package emu.x360.mobile.dev.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val showFpsCounter: Boolean = false,
    val defaultRenderScaleProfile: GuestRenderScaleProfile = GuestRenderScaleProfile.ONE,
    val defaultPresentationBackend: PresentationBackend = PresentationBackend.FRAMEBUFFER_POLLING,
)

object AppSettingsCodec {
    fun decode(raw: String): AppSettings = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(settings: AppSettings): String =
        RuntimeManifestCodec.json.encodeToString(AppSettings.serializer(), settings)
}

@Serializable
data class GameOptionsDatabase(
    val version: Int = 1,
    val entries: List<GameOptionsEntry> = emptyList(),
)

@Serializable
data class GameOptionsEntry(
    val entryId: String,
    val renderScaleOverride: GuestRenderScaleProfile? = null,
    val showFpsCounterOverride: Boolean? = null,
    val dlcEnabledOverride: Boolean? = null,
    val presentationBackendOverride: PresentationBackend? = null,
    val note: String? = null,
)

object GameOptionsDatabaseCodec {
    fun decode(raw: String): GameOptionsDatabase = RuntimeManifestCodec.json.decodeFromString(raw)

    fun encode(database: GameOptionsDatabase): String =
        RuntimeManifestCodec.json.encodeToString(GameOptionsDatabase.serializer(), database)
}
