import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.tukaani.xz.XZInputStream

class UbuntuGuestRuntimeAssembler(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    fun preparePackageArchives(
        lock: UbuntuGuestRuntimeLock,
        downloadsDir: Path,
    ): Map<String, Path> {
        downloadsDir.createDirectories()
        return lock.packages.associate { pkg ->
            val filename = pkg.url.substringAfterLast('/')
            val destination = downloadsDir.resolve(filename)
            downloadIfNeeded(pkg, destination)
            pkg.name to destination
        }
    }

    fun extractPackages(
        archives: Map<String, Path>,
        extractionDir: Path,
    ): Map<String, Path> {
        deleteRecursively(extractionDir)
        extractionDir.createDirectories()
        return archives.mapValues { (name, archive) ->
            val destination = extractionDir.resolve(name)
            extractDeb(archive, destination)
            destination
        }
    }

    fun copySelectedFiles(
        lock: UbuntuGuestRuntimeLock,
        extractedPackages: Map<String, Path>,
        outputRoot: Path,
    ) {
        for (pkg in lock.packages) {
            val extractedRoot = extractedPackages[pkg.name]
                ?: error("Missing extracted package root for ${pkg.name}")
            for (file in pkg.files) {
                val source = extractedRoot.resolve(file.source.removePrefix("./")).normalize()
                require(source.startsWith(extractedRoot.normalize())) {
                    "Invalid source path ${file.source} for package ${pkg.name}"
                }
                require(source.exists() && source.isRegularFile()) {
                    "Missing selected file $source from package ${pkg.name}"
                }

                val destination = outputRoot.resolve(file.installPath).normalize()
                require(destination.startsWith(outputRoot.normalize())) {
                    "Invalid install path ${file.installPath}"
                }

                destination.parent?.createDirectories()
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                val executable = source.toFile().canExecute() || file.installPath.endsWith("ld-linux-x86-64.so.2")
                if (executable) {
                    destination.toFile().setExecutable(true, false)
                }
            }
        }
    }

    fun writeGuestRuntimeMetadata(
        lock: UbuntuGuestRuntimeLock,
        outputPath: Path,
    ) {
        val metadata = GeneratedGuestRuntimeMetadata(
            distribution = lock.distribution,
            release = lock.release,
            architecture = lock.architecture,
            profile = lock.profile,
            activeIcd = lock.activeIcd,
            packages = lock.packages.map { pkg ->
                GeneratedGuestRuntimePackageMetadata(
                    name = pkg.name,
                    version = pkg.version,
                    url = pkg.url,
                    sha256 = pkg.sha256,
                )
            },
        )
        outputPath.writeText(UbuntuGuestRuntimeLockCodec.encodeMetadata(metadata))
    }

    private fun downloadIfNeeded(
        pkg: UbuntuGuestRuntimePackage,
        destination: Path,
    ) {
        if (destination.exists() && sha256(destination) == pkg.sha256) {
            return
        }

        destination.parent?.createDirectories()
        Files.deleteIfExists(destination)
        val response = httpClient.send(
            HttpRequest.newBuilder(URI(pkg.url)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(destination),
        )
        require(response.statusCode() in 200..299) {
            "Failed to download ${pkg.url}: HTTP ${response.statusCode()}"
        }
        require(sha256(destination) == pkg.sha256) {
            "SHA-256 mismatch for ${pkg.url}"
        }
    }

    private fun extractDeb(
        archive: Path,
        destination: Path,
    ) {
        deleteRecursively(destination)
        destination.createDirectories()

        Files.newInputStream(archive).use { fileInput ->
            ArArchiveInputStream(BufferedInputStream(fileInput)).use { ar ->
                while (true) {
                    val entry = ar.nextArEntry ?: break
                    if (!entry.name.startsWith("data.tar")) {
                        continue
                    }

                    extractDataArchive(
                        archiveName = entry.name,
                        archiveStream = ar,
                        destination = destination,
                    )
                    return
                }
            }
        }

        error("No data archive found in $archive")
    }

    private fun extractDataArchive(
        archiveName: String,
        archiveStream: InputStream,
        destination: Path,
    ) {
        val decompressedStream: InputStream = when {
            archiveName.endsWith(".gz") -> GzipCompressorInputStream(archiveStream)
            archiveName.endsWith(".xz") -> XZInputStream(archiveStream)
            archiveName.endsWith(".zst") -> ZstdCompressorInputStream(archiveStream)
            archiveName.endsWith(".tar") -> archiveStream
            else -> error("Unsupported Debian data archive format: $archiveName")
        }

        TarArchiveInputStream(BufferedInputStream(decompressedStream)).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val relativePath = entry.name.removePrefix("./")
                if (relativePath.isBlank()) {
                    continue
                }

                val outputPath = destination.resolve(relativePath).normalize()
                require(outputPath.startsWith(destination.normalize())) {
                    "Invalid tar entry path ${entry.name}"
                }

                when {
                    entry.isDirectory -> outputPath.createDirectories()
                    entry.isSymbolicLink -> {
                        // We intentionally skip package symlinks here and copy the selected
                        // regular files to their final SONAME destinations during staging.
                    }
                    entry.isFile -> {
                        outputPath.parent?.createDirectories()
                        Files.copy(tar, outputPath, StandardCopyOption.REPLACE_EXISTING)
                        if ((entry.mode and 73) != 0) {
                            outputPath.toFile().setExecutable(true, false)
                        }
                    }
                }
            }
        }
    }
}
