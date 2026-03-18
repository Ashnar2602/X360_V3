import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

internal fun deleteRecursively(path: Path) {
    if (!path.exists()) {
        return
    }

    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

internal fun copyTree(sourceDir: Path, destinationDir: Path) {
    if (!sourceDir.exists()) {
        return
    }

    Files.walkFileTree(
        sourceDir,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != sourceDir && dir.fileName?.toString() == ".git") {
                    return FileVisitResult.SKIP_SUBTREE
                }

                val relative = sourceDir.relativize(dir)
                val destination = if (relative.toString().isEmpty()) {
                    destinationDir
                } else {
                    destinationDir.resolve(relative.toString())
                }
                Files.createDirectories(destination)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName?.toString() == ".git") {
                    return FileVisitResult.CONTINUE
                }

                val relative = sourceDir.relativize(file)
                val destination = destinationDir.resolve(relative.toString())
                destination.parent?.createDirectories()
                Files.copy(
                    file,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
                return FileVisitResult.CONTINUE
            }
        },
    )
}

internal fun sha256(path: Path): String {
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

internal fun writeUtf8(path: Path, content: String) {
    path.parent?.createDirectories()
    Files.writeString(path, content, StandardCharsets.UTF_8)
}
