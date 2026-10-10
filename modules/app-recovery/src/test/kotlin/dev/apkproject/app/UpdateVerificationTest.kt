package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateVerificationTest {
    @TempDir lateinit var dir: Path
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val now = 1700000000000L
    private fun record(): ReleaseManifest {
        val file = Files.writeString(dir.resolve("studio.msi"), "signed package fixture")
        return ReleaseManifest(releaseId = 3, version = "0.4.0", createdAt = now - 1000, expiresAt = now + 60000,
            assets = listOf(ReleaseAsset("studio.msi", Hashing.sha256(file), Files.size(file), "windows-x64", "msi")))
    }
    private fun signed(record: ReleaseManifest): Pair<ByteArray, ByteArray> {
        val bytes = Json.encode(record).toByteArray()
        return bytes to Signature.getInstance("Ed25519").run { initSign(keys.private); update(bytes); sign() }
    }
    private fun verify(record: ReleaseManifest) = signed(record).let { (bytes, signature) -> UpdateVerifier.verify(bytes, signature, keys.public.encoded, "0.3.0", 2, now) }

    @Test fun `Python release workflow signature and JSON verify in the shipped Java runtime`() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/release-fixture/$name")!!.use { it.readAllBytes() }
        val manifest = UpdateVerifier.verify(fixture("release.json"), fixture("release.sig"), fixture("public.der"), "0.3.0", 2, now)
        assertEquals("0.4.0", manifest.version)
        val asset = Files.write(dir.resolve("studio.msi"), fixture("studio.msi"))
        assertEquals("studio.msi", UpdateVerifier.verifyAsset(manifest, asset).name)
    }

    @Test fun `checks real signatures and every package byte before accepting an update`() {
        val record = verify(record())
        assertEquals("studio.msi", UpdateVerifier.verifyAsset(record, dir.resolve("studio.msi")).name)
        Files.writeString(dir.resolve("studio.msi"), "corrupted package")
        assertFailsWith<IllegalArgumentException> { UpdateVerifier.verifyAsset(record, dir.resolve("studio.msi")) }
        val (bytes, signature) = signed(record)
        bytes[0] = 32
        assertFailsWith<IllegalArgumentException> { UpdateVerifier.verify(bytes, signature, keys.public.encoded, "0.3.0", 2, now) }
    }

    @Test fun `rejects wrong publisher expired future replayed and downgraded releases`() {
        val record = record()
        val (bytes, signature) = signed(record)
        val wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        assertFailsWith<IllegalArgumentException> { UpdateVerifier.verify(bytes, signature, wrongKey, "0.3.0", now = now) }
        for (bad in listOf(record.copy(expiresAt = now - 1), record.copy(createdAt = now + 400000),
            record.copy(releaseId = 1), record.copy(version = "0.2.9"), record.copy(version = "0.3.0"),
            record.copy(assets = listOf(record.assets.single().copy(name = "../evil.exe"))))) {
            assertFailsWith<IllegalArgumentException> { verify(bad) }
        }
    }
}
