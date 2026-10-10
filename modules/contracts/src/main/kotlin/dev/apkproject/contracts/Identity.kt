package dev.apkproject.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Immutable content identity of one stored artifact. */
@Serializable
data class ArtifactRef(
    val sha256: String,
    val relativePath: String,
    @Serializable(with = LongAsStringSerializer::class) val byteSize: Long,
    val mediaType: String,
)

/** Stable identity of a method inside one DEX file. Descriptors use JVM/DEX syntax. */
@Serializable
data class MethodKey(
    val dexSha256: String,
    val ownerDescriptor: String,
    val name: String,
    val prototypeDescriptor: String,
) {
    /** Human-readable form, for example `LFixtures;->twice(I)I`. */
    fun display(): String = "$ownerDescriptor->$name$prototypeDescriptor"
}

/** A byte range inside an immutable artifact; the unit of provenance. */
@Serializable
data class ByteRange(
    val artifactSha256: String,
    @Serializable(with = LongAsStringSerializer::class) val offset: Long,
    @Serializable(with = LongAsStringSerializer::class) val length: Long,
)

object Hashing {
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(path: Path): String = Files.newInputStream(path).use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return hex(digest.digest())
    }

    /** Hash of an ordered list of parts; used for cache keys and environment hashes. */
    fun combine(vararg parts: String): String = sha256(parts.joinToString("\u0000"))

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
