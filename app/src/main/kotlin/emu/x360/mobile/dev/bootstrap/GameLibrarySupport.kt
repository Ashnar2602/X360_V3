package emu.x360.mobile.dev.bootstrap

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import emu.x360.mobile.dev.nativebridge.NativeBridge
import emu.x360.mobile.dev.runtime.GameLibraryDatabase
import emu.x360.mobile.dev.runtime.GameLibraryDatabaseCodec
import emu.x360.mobile.dev.runtime.GameLibraryEntry
import emu.x360.mobile.dev.runtime.GameLibraryEntryStatus
import emu.x360.mobile.dev.runtime.GameLibrarySourceKind
import emu.x360.mobile.dev.runtime.RuntimeDirectories
import emu.x360.mobile.dev.runtime.TitleContentDatabase
import emu.x360.mobile.dev.runtime.TitleContentDatabaseCodec
import emu.x360.mobile.dev.runtime.TitleContentEntry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class GameLibraryStore(
    private val directories: RuntimeDirectories,
) {
    fun load(): GameLibraryDatabase {
        if (!directories.libraryDatabase.exists()) {
            return GameLibraryDatabase()
        }
        return runCatching { GameLibraryDatabaseCodec.decode(directories.libraryDatabase.readText()) }
            .getOrDefault(GameLibraryDatabase())
            .sorted()
    }

    fun save(database: GameLibraryDatabase) {
        directories.libraryRoot.createDirectories()
        directories.libraryDatabase.writeText(GameLibraryDatabaseCodec.encode(database.sorted()))
    }

    fun upsert(entry: GameLibraryEntry): GameLibraryDatabase {
        val current = load()
        val updated = current.entries
            .filterNot { it.id == entry.id } +
            entry
        return GameLibraryDatabase(version = current.version, entries = updated.sortedByDescending { it.importedAt })
            .also(::save)
    }

    fun remove(entryId: String): GameLibraryDatabase {
        val current = load()
        return GameLibraryDatabase(
            version = current.version,
            entries = current.entries.filterNot { it.id == entryId },
        ).also(::save)
    }

    fun find(entryId: String): GameLibraryEntry? = load().entries.firstOrNull { it.id == entryId }

    fun replaceEntries(entries: List<GameLibraryEntry>): GameLibraryDatabase {
        val current = load()
        return GameLibraryDatabase(
            version = current.version,
            entries = entries.sortedByDescending { it.importedAt },
        ).also(::save)
    }

    private fun GameLibraryDatabase.sorted(): GameLibraryDatabase {
        return copy(entries = entries.sortedByDescending { it.importedAt })
    }
}

internal class TitleContentStore(
    private val directories: RuntimeDirectories,
) {
    fun load(): TitleContentDatabase {
        if (!directories.titleContentDatabase.exists()) {
            return TitleContentDatabase()
        }
        return runCatching { TitleContentDatabaseCodec.decode(directories.titleContentDatabase.readText()) }
            .getOrDefault(TitleContentDatabase())
            .sorted()
    }

    fun save(database: TitleContentDatabase) {
        directories.libraryRoot.createDirectories()
        directories.titleContentDatabase.writeText(TitleContentDatabaseCodec.encode(database.sorted()))
    }

    fun upsert(entry: TitleContentEntry): TitleContentDatabase {
        val current = load()
        val updated = current.entries.filterNot { it.id == entry.id } + entry
        return TitleContentDatabase(
            version = current.version,
            entries = updated.sortedWith(compareBy<TitleContentEntry> { it.libraryEntryId }.thenBy { it.displayName.lowercase() }),
        ).also(::save)
    }

    fun remove(entryId: String): TitleContentDatabase {
        val current = load()
        return TitleContentDatabase(
            version = current.version,
            entries = current.entries.filterNot { it.id == entryId },
        ).also(::save)
    }

    fun find(entryId: String): TitleContentEntry? = load().entries.firstOrNull { it.id == entryId }

    fun forLibraryEntry(libraryEntryId: String): List<TitleContentEntry> =
        load().entries.filter { it.libraryEntryId == libraryEntryId }

    private fun TitleContentDatabase.sorted(): TitleContentDatabase {
        return copy(entries = entries.sortedWith(compareBy<TitleContentEntry> { it.libraryEntryId }.thenBy { it.displayName.lowercase() }))
    }
}

internal sealed interface ResolvedTitleSource {
    data class HostPath(
        val hostPath: Path,
        val origin: String,
    ) : ResolvedTitleSource

    data class FileDescriptorPath(
        val uri: Uri? = null,
        val hostPath: Path? = null,
        val origin: String,
    ) : ResolvedTitleSource

    data class Unsupported(
        val reason: String,
    ) : ResolvedTitleSource

    data class ProxyPath(
        val descriptor: String,
    ) : ResolvedTitleSource
}

internal class TitleContentResolver(
    private val context: Context,
    private val directories: RuntimeDirectories,
) {
    fun persistReadPermission(uri: Uri) {
        if (uri.scheme != ContentScheme) {
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun importEntry(uri: Uri): GameLibraryEntry {
        val metadata = probeMetadata(uri)
        val entryId = stableEntryId(uri.toString())
        return GameLibraryEntry(
            id = entryId,
            sourceKind = GameLibrarySourceKind.ISO,
            uriString = uri.toString(),
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            lastModified = metadata.lastModified,
            importedAt = System.currentTimeMillis(),
            lastKnownStatus = metadata.status,
            lastResolvedGuestPath = metadata.guestPortalPath(entryId),
            lastLaunchSummary = metadata.issue,
            lastKnownTitleName = null,
        )
    }

    fun refreshEntry(entry: GameLibraryEntry): GameLibraryEntry {
        val metadata = probeMetadata(Uri.parse(entry.uriString))
        return entry.copy(
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            lastModified = metadata.lastModified,
            lastKnownStatus = metadata.status,
            lastResolvedGuestPath = metadata.guestPortalPath(entry.id),
        )
    }

    fun resolve(entry: GameLibraryEntry): ResolvedTitleSource {
        return resolve(Uri.parse(entry.uriString))
    }

    fun resolve(uri: Uri): ResolvedTitleSource {
        return when (uri.scheme) {
            FileScheme -> resolveFileUri(uri)
            ContentScheme -> resolveContentUri(uri)
            else -> ResolvedTitleSource.Unsupported("Unsupported URI scheme: ${uri.scheme ?: "unknown"}")
        }
    }

    private fun probeMetadata(uri: Uri): TitleMetadataProbe {
        val displayName = queryDisplayName(uri) ?: fallbackDisplayName(uri)
        val sizeBytes = querySize(uri)
        val lastModified = queryLastModified(uri)
        val extensionOk = displayName.lowercase().endsWith(IsoSuffix)
        if (!extensionOk) {
            return TitleMetadataProbe(
                displayName = displayName,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                status = GameLibraryEntryStatus.UNSUPPORTED,
                issue = "Unsupported content type: expected .iso",
            )
        }
        return when (val resolution = resolve(uri)) {
            is ResolvedTitleSource.HostPath -> {
                val fileSize = runCatching { Files.size(resolution.hostPath) }.getOrDefault(sizeBytes)
                val fileLastModified = runCatching { Files.getLastModifiedTime(resolution.hostPath).toMillis() }
                    .getOrDefault(lastModified)
                TitleMetadataProbe(
                    displayName = displayName,
                    sizeBytes = fileSize,
                    lastModified = fileLastModified,
                    status = GameLibraryEntryStatus.READY,
                    issue = resolution.origin,
                )
            }
            is ResolvedTitleSource.FileDescriptorPath -> TitleMetadataProbe(
                displayName = displayName,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                status = GameLibraryEntryStatus.READY,
                issue = resolution.origin,
            )
            is ResolvedTitleSource.Unsupported -> TitleMetadataProbe(
                displayName = displayName,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                status = if (resolution.reason.contains("missing", ignoreCase = true)) {
                    GameLibraryEntryStatus.MISSING
                } else {
                    GameLibraryEntryStatus.UNSUPPORTED
                },
                issue = resolution.reason,
            )
            is ResolvedTitleSource.ProxyPath -> TitleMetadataProbe(
                displayName = displayName,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                status = GameLibraryEntryStatus.UNSUPPORTED,
                issue = "Proxy content paths are reserved for a future milestone",
            )
        }
    }

    private fun resolveFileUri(uri: Uri): ResolvedTitleSource {
        val pathString = uri.path ?: return ResolvedTitleSource.Unsupported("Missing file path")
        val path = File(pathString).toPath().normalize()
        return descriptorBackedHostPathOrIssue(
            path = path,
            descriptorOrigin = "file-fd",
            hostOrigin = "file-uri",
        )
    }

    private fun resolveContentUri(uri: Uri): ResolvedTitleSource {
        val mappedDocumentPath = mapDocumentUriToPath(uri)
        if (mappedDocumentPath != null) {
            val mappedResult = descriptorBackedHostPathOrIssue(
                path = mappedDocumentPath,
                descriptorOrigin = "document-path-fd",
                hostOrigin = "document-uri",
            )
            if (mappedResult !is ResolvedTitleSource.Unsupported) {
                return mappedResult
            }
        }

        val procFdTarget = resolveProcFdTarget(uri)
        if (procFdTarget != null) {
            val procFdResult = descriptorBackedHostPathOrIssue(
                path = procFdTarget,
                descriptorOrigin = "proc-fd-path",
                hostOrigin = "proc-fd",
            )
            if (procFdResult !is ResolvedTitleSource.Unsupported) {
                return procFdResult
            }
        }

        if (canOpenContentUri(uri)) {
            return ResolvedTitleSource.FileDescriptorPath(uri = uri, origin = "content-fd")
        }

        return ResolvedTitleSource.Unsupported("Content URI is not filesystem-backed on this device")
    }

    private fun descriptorBackedHostPathOrIssue(
        path: Path,
        descriptorOrigin: String,
        hostOrigin: String,
    ): ResolvedTitleSource {
        if (canOpenHostPathDescriptor(path)) {
            return ResolvedTitleSource.FileDescriptorPath(
                hostPath = path.toAbsolutePath().normalize(),
                origin = descriptorOrigin,
            )
        }
        return hostPathOrIssue(path, hostOrigin)
    }

    private fun hostPathOrIssue(path: Path, origin: String): ResolvedTitleSource {
        if (!path.exists()) {
            return ResolvedTitleSource.Unsupported("Resolved host path is missing: $path")
        }
        if (!path.isRegularFile()) {
            return ResolvedTitleSource.Unsupported("Resolved host path is not a regular file: $path")
        }
        if (!canOpenHostPath(path)) {
            return ResolvedTitleSource.Unsupported("Resolved host path is not readable: $path")
        }
        return ResolvedTitleSource.HostPath(path.toAbsolutePath().normalize(), origin)
    }

    private fun mapDocumentUriToPath(uri: Uri): Path? {
        if (!DocumentsContract.isDocumentUri(context, uri)) {
            return null
        }
        if (uri.authority != ExternalStorageAuthority) {
            return null
        }

        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.isEmpty()) {
            return null
        }
        val volumeId = parts[0]
        val relativePath = parts.getOrNull(1).orEmpty()
        val basePath = when {
            volumeId.equals("primary", ignoreCase = true) -> File("/storage/emulated/0").toPath()
            volumeId.isBlank() -> return null
            else -> File("/storage").toPath().resolve(volumeId)
        }
        return if (relativePath.isBlank()) {
            basePath
        } else {
            basePath.resolve(relativePath)
        }.normalize()
    }

    private fun resolveProcFdTarget(uri: Uri): Path? {
        val descriptor = openReadableDescriptor(uri)
            ?: return null
        descriptor.use { parcelFileDescriptor ->
            val fdLink = File("/proc/self/fd/${parcelFileDescriptor.fd}").toPath()
            val rawTarget = runCatching { Files.readSymbolicLink(fdLink).toString() }.getOrNull() ?: return null
            val normalizedTarget = rawTarget.substringBefore(" (deleted)").trim()
            if (!normalizedTarget.startsWith("/")) {
                return null
            }
            return File(normalizedTarget).toPath().normalize()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return querySingleValue(uri, OpenableColumns.DISPLAY_NAME) { cursor, index ->
            cursor.getString(index)
        }
    }

    fun openReadableDescriptor(uri: Uri): ParcelFileDescriptor? {
        return runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
    }

    fun adoptDescriptorForExec(
        source: ResolvedTitleSource.FileDescriptorPath,
        minimumFd: Int = DefaultExecFdFloor,
    ): AdoptedExecFd? {
        val parcelFileDescriptor = when {
            source.uri != null -> openReadableDescriptor(source.uri)
            source.hostPath != null -> openReadablePathDescriptor(source.hostPath)
            else -> null
        } ?: return null
        val rawFd = runCatching { parcelFileDescriptor.detachFd() }.getOrElse {
            parcelFileDescriptor.close()
            return null
        }
        val inheritedFd = NativeBridge.adoptFdForExec(rawFd, minimumFd)
        if (inheritedFd < 0) {
            return null
        }
        return AdoptedExecFd(inheritedFd)
    }

    private fun querySize(uri: Uri): Long {
        return querySingleValue(uri, OpenableColumns.SIZE) { cursor, index ->
            cursor.getLong(index)
        } ?: when (val resolved = resolve(uri)) {
            is ResolvedTitleSource.HostPath -> runCatching { Files.size(resolved.hostPath) }.getOrDefault(0L)
            else -> 0L
        }
    }

    private fun queryLastModified(uri: Uri): Long {
        return querySingleValue(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED) { cursor, index ->
            cursor.getLong(index)
        } ?: when (val resolved = resolve(uri)) {
            is ResolvedTitleSource.HostPath -> runCatching { Files.getLastModifiedTime(resolved.hostPath).toMillis() }
                .getOrDefault(0L)
            else -> 0L
        }
    }

    private fun fallbackDisplayName(uri: Uri): String {
        return when (uri.scheme) {
            FileScheme -> uri.path?.substringAfterLast('/', missingDelimiterValue = "untitled.iso").orEmpty()
            else -> uri.lastPathSegment?.substringAfterLast('/', missingDelimiterValue = "untitled.iso").orEmpty()
        }.ifBlank { "untitled.iso" }
    }

    private fun <T> querySingleValue(
        uri: Uri,
        columnName: String,
        extractor: (Cursor, Int) -> T,
    ): T? {
        val projection = arrayOf(columnName)
        val cursor = runCatching { context.contentResolver.query(uri, projection, null, null, null) }.getOrNull()
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            val index = it.getColumnIndex(columnName)
            if (index < 0 || it.isNull(index)) {
                return null
            }
            return extractor(it, index)
        }
    }

    private fun canOpenContentUri(uri: Uri): Boolean {
        return openReadableDescriptor(uri)?.use { true } ?: false
    }

    private fun canOpenHostPathDescriptor(path: Path): Boolean {
        return openReadablePathDescriptor(path)?.use { true } ?: false
    }

    private fun canOpenHostPath(path: Path): Boolean {
        return runCatching {
            Files.newInputStream(path).use { stream ->
                stream.readNBytes(1)
            }
            true
        }.getOrDefault(false)
    }

    private fun openReadablePathDescriptor(path: Path): ParcelFileDescriptor? {
        return runCatching {
            ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    private fun TitleMetadataProbe.guestPortalPath(entryId: String): String? {
        return if (status == GameLibraryEntryStatus.READY) {
            directories.rootfsMntLibrary.resolve("$entryId.iso").toGuestPath(directories.rootfs)
        } else {
            null
        }
    }

    companion object {
        private const val ExternalStorageAuthority = "com.android.externalstorage.documents"
        private const val DefaultExecFdFloor = 192
        private const val FileScheme = "file"
        private const val ContentScheme = "content"
        private const val IsoSuffix = ".iso"
    }
}

internal class GuestContentPortalManager(
    private val directories: RuntimeDirectories,
) {
    fun materializeIsoPortal(entry: GameLibraryEntry, hostPath: Path): Path {
        return materializeHostPathPortal(entry.id, ".iso", hostPath)
    }

    fun materializeHostPathPortal(entryId: String, suffix: String, hostPath: Path): Path {
        directories.rootfsMntLibrary.createDirectories()
        val portalPath = portalPathFor(entryId, suffix)
        Files.deleteIfExists(portalPath)
        Files.createSymbolicLink(portalPath, hostPath.toAbsolutePath().normalize())
        return portalPath
    }

    fun materializeProcFdPortal(entryId: String, inheritedFd: Int): Path {
        return materializeDescriptorPortal(entryId, ".iso", "/proc/self/fd/$inheritedFd")
    }

    fun materializeDescriptorPortal(entryId: String, suffix: String, descriptorPath: String): Path {
        directories.rootfsMntLibrary.createDirectories()
        val portalPath = portalPathFor(entryId, suffix)
        Files.deleteIfExists(portalPath)
        Files.createSymbolicLink(portalPath, File(descriptorPath).toPath())
        return portalPath
    }

    fun materializeFdBackedPortal(entryId: String, suffix: String = ".iso"): Path {
        directories.rootfsMntLibrary.createDirectories()
        val portalPath = portalPathFor(entryId, suffix)
        Files.deleteIfExists(portalPath)
        Files.write(portalPath, byteArrayOf())
        return portalPath
    }

    fun clearPortal(entryId: String, suffix: String = ".iso") {
        Files.deleteIfExists(portalPathFor(entryId, suffix))
    }

    fun portalGuestPath(entryId: String, suffix: String = ".iso"): String {
        return portalPathFor(entryId, suffix).toGuestPath(directories.rootfs)
    }

    private fun portalPathFor(entryId: String, suffix: String): Path = directories.rootfsMntLibrary.resolve("$entryId$suffix")
}

internal fun stableEntryId(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return digest.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private data class TitleMetadataProbe(
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val status: GameLibraryEntryStatus,
    val issue: String?,
)

internal class AdoptedExecFd(
    val fd: Int,
) : AutoCloseable {
    override fun close() {
        NativeBridge.closeFd(fd)
    }
}
