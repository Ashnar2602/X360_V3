import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

data class RuntimePayloadAsset(
    val assetPath: String,
    val installPath: String,
    val executable: Boolean,
    val checksumSha256: String,
    val minPhase: String = "bootstrap",
)

data class RuntimePayloadManifest(
    val version: Int,
    val profile: String,
    val generatedBy: String,
    val assets: List<RuntimePayloadAsset>,
)

class RuntimePayloadOverlayPlanner {
    @OptIn(ExperimentalPathApi::class)
    fun stage(
        mockRuntimeDir: Path,
        generatedFexRuntimeDir: Path?,
        generatedVulkanRuntimeDir: Path?,
        generatedTurnipRuntimeDir: Path?,
        localRuntimeDropDir: Path?,
        outputDir: Path,
        includeLocalDrop: Boolean,
    ): RuntimePayloadManifest {
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }

        val assetRoot = outputDir.resolve("runtime-payload")
        val filesRoot = assetRoot.resolve("files")
        filesRoot.createDirectories()

        val phaseByAsset = linkedMapOf<String, String>()
        val layers = buildList {
            add(RuntimePayloadLayer(mockRuntimeDir, RuntimePhaseTag.BOOTSTRAP))
            if (generatedFexRuntimeDir != null && generatedFexRuntimeDir.exists()) {
                add(RuntimePayloadLayer(generatedFexRuntimeDir, RuntimePhaseTag.FEX_BASELINE))
            }
            if (generatedVulkanRuntimeDir != null && generatedVulkanRuntimeDir.exists()) {
                add(RuntimePayloadLayer(generatedVulkanRuntimeDir, RuntimePhaseTag.VULKAN_BASELINE))
            }
            if (generatedTurnipRuntimeDir != null && generatedTurnipRuntimeDir.exists()) {
                add(RuntimePayloadLayer(generatedTurnipRuntimeDir, RuntimePhaseTag.TURNIP_BASELINE))
            }
            if (includeLocalDrop && localRuntimeDropDir != null && localRuntimeDropDir.exists()) {
                add(RuntimePayloadLayer(localRuntimeDropDir, RuntimePhaseTag.BOOTSTRAP, preserveExistingPhase = true))
            }
        }
        layers.forEach { layer ->
            copyTree(layer.sourceDir, filesRoot) { relativePath ->
                if (layer.preserveExistingPhase && phaseByAsset.containsKey(relativePath)) {
                    return@copyTree
                }
                phaseByAsset[relativePath] = layer.minPhase.wireValue
            }
        }

        val manifest = RuntimePayloadManifest(
            version = 1,
            profile = if (generatedVulkanRuntimeDir != null && generatedVulkanRuntimeDir.exists()) {
                if (generatedTurnipRuntimeDir != null && generatedTurnipRuntimeDir.exists()) {
                    "turnip-baseline"
                } else {
                    "vulkan-baseline"
                }
            } else if (generatedTurnipRuntimeDir != null && generatedTurnipRuntimeDir.exists()) {
                "turnip-baseline"
            } else if (generatedFexRuntimeDir != null && generatedFexRuntimeDir.exists()) {
                "fex-baseline"
            } else {
                "bootstrap-mock"
            },
            generatedBy = "buildSrc:RuntimePayloadOverlayPlanner",
            assets = filesRoot
                .walk()
                .filter { it.isRegularFile() }
                .sortedBy { it.relativeTo(filesRoot).invariantSeparatorsPathString }
                .map { file ->
                    val relative = file.relativeTo(filesRoot).invariantSeparatorsPathString
                    RuntimePayloadAsset(
                        assetPath = "runtime-payload/files/$relative",
                        installPath = relative,
                        executable = relative.startsWith("payload/bin/") || relative.startsWith("payload/guest-tests/bin/"),
                        checksumSha256 = sha256(file),
                        minPhase = phaseByAsset[relative] ?: RuntimePhaseTag.BOOTSTRAP.wireValue,
                    )
                }
                .toList(),
        )

        assetRoot.resolve("runtime-manifest.json").writeText(manifest.toJson())
        return manifest
    }

    @OptIn(ExperimentalPathApi::class)
    private fun copyTree(
        sourceDir: Path,
        destinationDir: Path,
        onFileCopied: (String) -> Unit,
    ) {
        if (!sourceDir.exists()) {
            return
        }

        sourceDir.walk()
            .filter { it != sourceDir }
            .forEach { source ->
                val relative = source.relativeTo(sourceDir)
                val destination = destinationDir.resolve(relative)
                if (Files.isDirectory(source)) {
                    destination.createDirectories()
                } else {
                    val relativePath = relative.invariantSeparatorsPathString
                    destination.parent?.createDirectories()
                    source.inputStream().use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    onFileCopied(relativePath)
                }
            }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Path.writeText(content: String) {
        parent?.createDirectories()
        Files.writeString(this, content, StandardCharsets.UTF_8)
    }

    private fun RuntimePayloadManifest.toJson(): String {
        val assetJson = assets.joinToString(",\n") { asset ->
            """
            |    {
            |      "assetPath": "${asset.assetPath.jsonEscape()}",
            |      "installPath": "${asset.installPath.jsonEscape()}",
            |      "executable": ${asset.executable},
            |      "checksumSha256": "${asset.checksumSha256}",
            |      "minPhase": "${asset.minPhase}"
            |    }
            """.trimMargin()
        }

        return """
        |{
        |  "version": $version,
        |  "profile": "${profile.jsonEscape()}",
        |  "generatedBy": "${generatedBy.jsonEscape()}",
        |  "assets": [
        |$assetJson
        |  ]
        |}
        """.trimMargin()
    }

    private fun String.jsonEscape(): String = buildString(length + 8) {
        for (char in this@jsonEscape) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private data class RuntimePayloadLayer(
    val sourceDir: Path,
    val minPhase: RuntimePhaseTag,
    val preserveExistingPhase: Boolean = false,
)

private enum class RuntimePhaseTag(
    val wireValue: String,
) {
    BOOTSTRAP("bootstrap"),
    FEX_BASELINE("fex-baseline"),
    VULKAN_BASELINE("vulkan-baseline"),
    TURNIP_BASELINE("turnip-baseline"),
}
