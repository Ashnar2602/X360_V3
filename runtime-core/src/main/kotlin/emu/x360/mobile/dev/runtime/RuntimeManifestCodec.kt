package emu.x360.mobile.dev.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RuntimeManifestCodec {
    val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(raw: String): RuntimeManifest = json.decodeFromString(raw)

    fun encode(manifest: RuntimeManifest): String = json.encodeToString(manifest)
}
