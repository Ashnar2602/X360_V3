package emu.x360.mobile.dev

import emu.x360.mobile.dev.runtime.AppSettings
import emu.x360.mobile.dev.runtime.AppSettingsCodec
import emu.x360.mobile.dev.runtime.GameOptionsDatabase
import emu.x360.mobile.dev.runtime.GameOptionsDatabaseCodec
import emu.x360.mobile.dev.runtime.GameOptionsEntry
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class AppSettingsStore(
    baseDir: Path,
) {
    private val settingsRoot: Path = baseDir.resolve("settings")
    private val settingsFile: Path = settingsRoot.resolve("app-settings.json")

    fun load(): AppSettings {
        if (!settingsFile.exists()) {
            return AppSettings()
        }
        return runCatching { AppSettingsCodec.decode(settingsFile.readText()) }
            .getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        settingsRoot.createDirectories()
        settingsFile.writeText(AppSettingsCodec.encode(settings))
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(load())
        save(updated)
        return updated
    }
}

internal class GameOptionsStore(
    baseDir: Path,
) {
    private val settingsRoot: Path = baseDir.resolve("settings")
    private val optionsFile: Path = settingsRoot.resolve("game-options.json")

    fun load(): GameOptionsDatabase {
        if (!optionsFile.exists()) {
            return GameOptionsDatabase()
        }
        return runCatching { GameOptionsDatabaseCodec.decode(optionsFile.readText()) }
            .getOrDefault(GameOptionsDatabase())
    }

    fun save(database: GameOptionsDatabase) {
        settingsRoot.createDirectories()
        optionsFile.writeText(GameOptionsDatabaseCodec.encode(database))
    }

    fun optionsFor(entryId: String): GameOptionsEntry {
        return load().entries.firstOrNull { it.entryId == entryId } ?: GameOptionsEntry(entryId = entryId)
    }

    fun upsert(entry: GameOptionsEntry): GameOptionsDatabase {
        val current = load()
        val updated = current.entries
            .filterNot { it.entryId == entry.entryId } +
            entry
        return GameOptionsDatabase(version = current.version, entries = updated.sortedBy { it.entryId })
            .also(::save)
    }
}
