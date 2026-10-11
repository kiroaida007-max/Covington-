package dev.apkproject.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.*

class ApkSigningReaderTest {
    @TempDir lateinit var dir: Path
    private fun prefix(bytes: ByteArray) = ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).put(bytes).array()
    private fun writeApk(path: Path, padding: Long): Path {
        // Certificate declarations only: ApkSigning inventories identity, not signature validity.
        val cert = javaClass.getResourceAsStream("/signing/test-cert.pem")!!.use { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it).encoded }
        val signed = prefix(byteArrayOf()) + prefix(prefix(cert))
        val value = prefix(prefix(prefix(signed)))
        val pair = ByteBuffer.allocate(12 + value.size).order(ByteOrder.LITTLE_ENDIAN).putLong(value.size + 4L).putInt(0x7109871a).put(value).array()
        val size = pair.size + 24L
        val block = ByteBuffer.allocate(pair.size + 32).order(ByteOrder.LITTLE_ENDIAN).putLong(size).put(pair).putLong(size).put("APK Sig Block 42".toByteArray()).array()
        val cd = padding + block.size
        val tail = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).putInt(0x06054b50).putShort(0).putShort(0).putShort(0).putShort(0).putInt(0).putInt(cd.toInt()).putShort(0).array()
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { c -> c.position(padding); c.write(ByteBuffer.wrap(block)); c.write(ByteBuffer.wrap(tail)) }
        return path
    }

    @Test fun `file-backed signer inventory agrees with byte input and handles a sparse large APK`() {
        val small = writeApk(dir.resolve("small.apk"), 0)
        val reference = ApkSigning.read(Files.readAllBytes(small))
        assertEquals("CN=APKProject Test Certificate", assertNotNull(reference.signers.singleOrNull()).subject)
        assertEquals(listOf("v2"), reference.signers.single().schemes)
        assertEquals(reference, ApkSigning.read(small))
        val large = writeApk(dir.resolve("large.apk"), 96L * 1024 * 1024)
        assertEquals(reference, ApkSigning.read(large))
    }

    @Test fun `malformed signing lengths cannot manufacture an identity`() {
        val path = writeApk(dir.resolve("bad.apk"), 0)
        FileChannel.open(path, StandardOpenOption.WRITE).use { c -> c.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MAX_VALUE).flip()) }
        assertTrue(ApkSigning.read(path).signers.isEmpty())
    }
}
