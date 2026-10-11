package dev.apkproject.app

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** One signing certificate of an APK. */
@Serializable
data class SignerCertificate(
    /** SHA-256 of the DER certificate, lower-case hex: the identity Android compares on update. */
    val sha256: String,
    val subject: String,
    /** Signature schemes that carry this certificate: v1 (JAR), v2, v3, v3.1. */
    val schemes: List<String>,
)

@Serializable
data class SigningInfo(
    val signers: List<SignerCertificate>,
    /** SHA-256 of every certificate in an APK Signature Scheme v3 rotation lineage (oldest first). */
    val lineage: List<String> = emptyList(),
)

/**
 * Reads who signed an APK, without verifying the signatures (the platform does that on install):
 * the certificates of the APK Signing Block (schemes v2, v3, v3.1) and, for older APKs, of the JAR
 * signature in `META-INF/`. Android installs an update only when its signer matches the installed
 * app's, or when a v3 rotation lineage links the two, so a changed certificate is the first thing
 * to check in an update.
 */
object ApkSigning {
    private const val V2 = 0x7109871a
    private const val V3 = 0xf05368c0.toInt()
    private const val V31 = 0x1b93ad61
    private const val PROOF_OF_ROTATION = 0x3ba06f8c

    fun read(apk: ByteArray, jarSignatures: List<ByteArray> = emptyList()): SigningInfo =
        readPairs(runCatching { signingBlock(apk) }.getOrNull(), jarSignatures)

    /** Reads only the ZIP tail and a bounded signing block, never the whole APK. */
    fun read(apk: java.nio.file.Path, jarSignatures: List<ByteArray> = emptyList()): SigningInfo =
        readPairs(runCatching { signingBlock(apk) }.getOrNull(), jarSignatures)

    private fun readPairs(pairs: List<Pair<Int, ByteBuffer>>?, jarSignatures: List<ByteArray>): SigningInfo {
        val found = LinkedHashMap<String, Pair<X509Certificate, MutableSet<String>>>()
        val lineage = ArrayList<String>()
        fun add(cert: X509Certificate, scheme: String) {
            found.getOrPut(sha256(cert.encoded)) { cert to LinkedHashSet() }.second += scheme
        }
        pairs?.forEach { (id, value) ->
            val scheme = when (id) { V2 -> "v2"; V3 -> "v3"; V31 -> "v3.1"; else -> return@forEach }
            runCatching { blockSigners(value, scheme == "v3" || scheme == "v3.1", lineage) }.getOrDefault(emptyList()).forEach { add(it, scheme) }
        }
        val factory = CertificateFactory.getInstance("X.509")
        for (pkcs7 in jarSignatures) {
            runCatching { factory.generateCertificates(ByteArrayInputStream(pkcs7)) }.getOrNull()
                ?.filterIsInstance<X509Certificate>()?.firstOrNull()?.let { add(it, "v1") }
        }
        return SigningInfo(
            found.map { (digest, v) -> SignerCertificate(digest, v.first.subjectX500Principal.name, v.second.sortedBy { ORDER.indexOf(it) }) },
            lineage.distinct(),
        )
    }

    private val ORDER = listOf("v1", "v2", "v3", "v3.1")

    /** (id, value) pairs of the APK Signing Block, or null when the APK has none. */
    private fun signingBlock(apk: ByteArray): List<Pair<Int, ByteBuffer>>? {
        val b = ByteBuffer.wrap(apk).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (p in apk.size - 22 downTo maxOf(0, apk.size - 22 - 65_535)) if (b.getInt(p) == 0x06054b50) { eocd = p; break }
        if (eocd < 0) return null
        val cd = b.getInt(eocd + 16).toLong() and 0xffffffffL
        if (cd < 32 || cd > eocd) return null
        val magic = String(apk, (cd - 16).toInt(), 16, Charsets.ISO_8859_1)
        if (magic != "APK Sig Block 42") return null
        val size = b.getLong((cd - 24).toInt())
        val start = cd - size - 8
        if (size < 24 || start < 0) return null
        return pairs(b, (start + 8).toInt(), (cd - 24).toInt())
    }

    private const val MAX_BLOCK_BYTES = 16 * 1024 * 1024

    private fun signingBlock(path: java.nio.file.Path): List<Pair<Int, ByteBuffer>>? =
        java.nio.channels.FileChannel.open(path).use { channel ->
            val length = channel.size()
            if (length < 22) return null
            fun region(offset: Long, size: Int): ByteBuffer {
                require(offset >= 0 && size >= 0 && offset <= length - size)
                val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                var position = offset
                while (b.hasRemaining()) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n = channel.read(b, position)
                    require(n > 0) { "truncated signing metadata" }
                    position += n
                }
                b.flip()
                return b
            }
            val tailSize = minOf(length, 22L + 65_535).toInt()
            val tailOffset = length - tailSize
            val tail = region(tailOffset, tailSize)
            val eocd = (tailSize - 22 downTo 0).firstOrNull {
                tail.getInt(it) == 0x06054b50 && it + 22 + (tail.getShort(it + 20).toInt() and 0xffff) == tailSize
            } ?: return null
            val cd = tail.getInt(eocd + 16).toLong() and 0xffffffffL
            if (cd < 32 || cd > tailOffset + eocd) return null
            val footer = region(cd - 24, 24)
            val magic = ByteArray(16).also { footer.position(8); footer.get(it) }
            if (!magic.contentEquals("APK Sig Block 42".toByteArray(Charsets.ISO_8859_1))) return null
            val size = footer.getLong(0)
            require(size in 24..(MAX_BLOCK_BYTES - 8).toLong() && size <= cd - 8) { "signing block exceeds bounds" }
            val block = region(cd - size - 8, (size + 8).toInt())
            require(block.getLong(0) == size) { "signing block length mismatch" }
            pairs(block, 8, block.limit() - 24)
        }

    private fun pairs(b: ByteBuffer, start: Int, end: Int): List<Pair<Int, ByteBuffer>> {
        val out = ArrayList<Pair<Int, ByteBuffer>>()
        var at = start
        while (at < end) {
            require(end - at >= 12) { "truncated signing pair" }
            val len = b.getLong(at)
            require(len >= 4 && len <= end - at - 8L) { "bad signing pair length" }
            out += b.getInt(at + 8) to slice(b, at + 12, (len - 4).toInt())
            at += (8 + len).toInt()
        }
        return out
    }

    private fun blockSigners(value: ByteBuffer, v3: Boolean, lineage: MutableList<String>): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = ArrayList<X509Certificate>()
        val signers = prefixed(value)
        while (signers.remaining() >= 4) {
            val signer = prefixed(signers)
            val signedData = prefixed(signer)
            prefixed(signedData) // digests
            val certificates = prefixed(signedData)
            var first = true
            while (certificates.remaining() >= 4) {
                val der = bytes(prefixed(certificates))
                if (first) certs += factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                first = false
            }
            if (v3) {
                signedData.getInt(); signedData.getInt() // minSdk, maxSdk
                val attributes = prefixed(signedData)
                while (attributes.remaining() >= 4) {
                    val attribute = prefixed(attributes)
                    if (attribute.getInt() == PROOF_OF_ROTATION) runCatching { rotation(attribute, lineage) }
                }
            }
        }
        return certs
    }

    /** Certificates of a v3 proof-of-rotation attribute: version, then one signed node per key. */
    private fun rotation(attribute: ByteBuffer, lineage: MutableList<String>) {
        attribute.getInt() // version
        while (attribute.remaining() >= 4) {
            val node = prefixed(attribute)
            val signedData = prefixed(node)
            lineage += sha256(bytes(prefixed(signedData)))
        }
    }

    private fun prefixed(b: ByteBuffer): ByteBuffer {
        val len = b.getInt()
        require(len >= 0 && len <= b.remaining()) { "bad length $len" }
        val s = slice(b, b.position(), len)
        b.position(b.position() + len)
        return s
    }

    private fun slice(b: ByteBuffer, at: Int, len: Int): ByteBuffer =
        b.duplicate().also { it.position(at); it.limit(at + len) }.slice().order(ByteOrder.LITTLE_ENDIAN)

    private fun bytes(b: ByteBuffer) = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
