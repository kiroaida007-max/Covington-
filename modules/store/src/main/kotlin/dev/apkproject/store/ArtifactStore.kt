package dev.apkproject.store

import dev.apkproject.contracts.ArtifactRef
import dev.apkproject.contracts.Hashing
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Content-addressed artifact store (section 23): immutable bytes live under
 * `artifacts/<first two hex digits>/<sha256>`. Writes go to a temporary file first and are
 * then renamed into place, so a reader never observes a partial artifact.
 */
class ArtifactStore(val root: Path) {
    private val artifacts = root.resolve("artifacts")
    private val tmp = root.resolve("tmp")

    init {
        Files.createDirectories(artifacts)
        Files.createDirectories(tmp)
    }

    fun put(bytes: ByteArray, relativePath: String, mediaType: String): ArtifactRef {
        val sha = Hashing.sha256(bytes)
        val target = pathFor(sha)
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            val staging = tmp.resolve(UUID.randomUUID().toString())
            Files.write(staging, bytes)
            moveAtomically(staging, target)
        }
        return ArtifactRef(sha, relativePath, bytes.size.toLong(), mediaType)
    }

    fun putText(text: String, relativePath: String, mediaType: String = "text/plain"): ArtifactRef =
        put(text.toByteArray(Charsets.UTF_8), relativePath, mediaType)

    fun putFile(file: Path, relativePath: String, mediaType: String): ArtifactRef =
        put(Files.readAllBytes(file), relativePath, mediaType)

    /** Reads an artifact and re-verifies its hash; stored bytes are never trusted blindly. */
    fun read(ref: ArtifactRef): ByteArray = read(ref.sha256)

    fun read(sha256: String): ByteArray {
        val bytes = Files.readAllBytes(pathFor(sha256))
        val actual = Hashing.sha256(bytes)
        check(actual == sha256) { "artifact $sha256 is corrupt (content hash $actual)" }
        return bytes
    }

    fun contains(sha256: String): Boolean = Files.exists(pathFor(sha256))

    fun pathFor(sha256: String): Path {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "not a sha256: $sha256" }
        return artifacts.resolve(sha256.substring(0, 2)).resolve(sha256)
    }

    private fun moveAtomically(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(from) // a concurrent writer stored identical content
        }
    }
}

object AtomicFiles {
    /** Writes text to a sibling temporary file and renames it over the target. */
    fun writeString(target: Path, text: String) {
        Files.createDirectories(target.toAbsolutePath().parent)
        val staging = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(staging, text)
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun appendLine(target: Path, line: String) {
        Files.createDirectories(target.toAbsolutePath().parent)
        Files.writeString(
            target, line + "\n",
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
            java.nio.file.StandardOpenOption.SYNC,
        )
    }

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
