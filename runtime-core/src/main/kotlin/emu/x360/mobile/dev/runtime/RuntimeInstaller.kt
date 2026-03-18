package emu.x360.mobile.dev.runtime

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable

interface RuntimeAssetSource {
    fun open(assetPath: String): InputStream?
}

class RuntimeInstaller(
    private val directories: RuntimeDirectories,
) {
    fun inspect(
        manifest: RuntimeManifest,
        targetPhase: RuntimePhase,
    ): RuntimeInstallState {
        val marker = readMarker() ?: return RuntimeInstallState.NotInstalled
        if (!marker.installedPhase.includes(targetPhase)) {
            return RuntimeInstallState.NotInstalled
        }

        val filteredManifest = manifest.filteredForPhase(targetPhase)
        for (asset in filteredManifest.assets) {
            val installPath = resolveInstallPath(asset.installPath)
                ?: return RuntimeInstallState.Invalid(RuntimeInstallIssue.InvalidInstallPath(asset.installPath))

            if (!installPath.exists() || !installPath.isRegularFile()) {
                return RuntimeInstallState.Invalid(RuntimeInstallIssue.MissingInstalledFile(asset.installPath))
            }

            val expectedChecksum = asset.checksumSha256 ?: continue
            val actualChecksum = sha256(installPath)
            if (actualChecksum != expectedChecksum) {
                return RuntimeInstallState.Invalid(
                    RuntimeInstallIssue.ChecksumMismatch(
                        installPath = asset.installPath,
                        expectedSha256 = expectedChecksum,
                        actualSha256 = actualChecksum,
                    ),
                )
            }
        }

        return RuntimeInstallState.Installed(
            rootDirectory = directories.baseDir.toString(),
            manifestFingerprint = marker.manifestFingerprint,
            installedAt = Instant.parse(marker.installedAt),
            installedPhase = marker.installedPhase,
        )
    }

    fun install(
        manifest: RuntimeManifest,
        assetSource: RuntimeAssetSource,
        targetPhase: RuntimePhase,
    ): RuntimeInstallState {
        val current = inspect(manifest, targetPhase)
        if (current is RuntimeInstallState.Installed) {
            return current
        }

        directories.requiredDirectories().forEach { it.createDirectories() }

        val filteredManifest = manifest.filteredForPhase(targetPhase)
        for (asset in filteredManifest.assets) {
            val destination = resolveInstallPath(asset.installPath)
                ?: return RuntimeInstallState.Invalid(RuntimeInstallIssue.InvalidInstallPath(asset.installPath))

            val source = assetSource.open(asset.assetPath)
                ?: return RuntimeInstallState.Invalid(RuntimeInstallIssue.MissingAsset(asset.assetPath))

            destination.parent?.createDirectories()
            source.use { input ->
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            }

            if (asset.executable) {
                destination.toFile().setExecutable(true, false)
            }

            val expectedChecksum = asset.checksumSha256 ?: continue
            val actualChecksum = sha256(destination)
            if (actualChecksum != expectedChecksum) {
                return RuntimeInstallState.Invalid(
                    RuntimeInstallIssue.ChecksumMismatch(
                        installPath = asset.installPath,
                        expectedSha256 = expectedChecksum,
                        actualSha256 = actualChecksum,
                    ),
                )
            }
        }

        writeMarker(filteredManifest, targetPhase)
        return inspect(manifest, targetPhase)
    }

    fun runtimeDirectories(): RuntimeDirectories = directories

    private fun readMarker(): RuntimeInstallMarker? {
        if (!directories.installMarker.exists()) {
            return null
        }

        return runCatching {
            RuntimeManifestCodec.json.decodeFromString<RuntimeInstallMarker>(
                directories.installMarker.readText(),
            )
        }.getOrNull()
    }

    private fun writeMarker(
        manifest: RuntimeManifest,
        installedPhase: RuntimePhase,
    ) {
        val marker = RuntimeInstallMarker(
            manifestVersion = manifest.version,
            profile = manifest.profile,
            installedAt = Instant.now().toString(),
            manifestFingerprint = manifestFingerprint(manifest),
            installedPhase = installedPhase,
        )
        directories.installMarker.parent?.createDirectories()
        directories.installMarker.writeText(
            RuntimeManifestCodec.json.encodeToString(RuntimeInstallMarker.serializer(), marker),
        )
    }

    private fun manifestFingerprint(manifest: RuntimeManifest): String {
        val raw = RuntimeManifestCodec.encode(manifest)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(raw.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveInstallPath(relativePath: String): Path? {
        val normalized = directories.baseDir.resolve(relativePath).normalize()
        return normalized.takeIf { it.startsWith(directories.baseDir.normalize()) }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class RuntimeInstallMarker(
    val manifestVersion: Int,
    val profile: String,
    val installedAt: String,
    val manifestFingerprint: String,
    val installedPhase: RuntimePhase,
)
