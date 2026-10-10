package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@Serializable
data class ReleaseAsset(val name: String, val sha256: String, val bytes: Long, val platform: String, val kind: String)

@Serializable
data class ReleaseManifest(val schemaVersion: Int = 1, val releaseId: Long, val version: String,
    val createdAt: Long, val expiresAt: Long, val assets: List<ReleaseAsset>)

/** Trust comes from a key pinned in the installed app, never a key supplied by an update. */
object UpdateVerifier {
    fun verify(manifest: ByteArray, signature: ByteArray, trustedKeyDer: ByteArray,
        currentVersion: String, minimumReleaseId: Long = 0, now: Long = System.currentTimeMillis()): ReleaseManifest {
        require(manifest.size in 1..65536 && signature.size == 64 && trustedKeyDer.size in 32..256) { "invalid update envelope size" }
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(trustedKeyDer))
        require(Signature.getInstance("Ed25519").run { initVerify(key); update(manifest); verify(signature) }) { "update signature is invalid" }
        val record = Json.decode<ReleaseManifest>(String(manifest, Charsets.UTF_8))
        require(record.schemaVersion == 1 && record.releaseId > 0 && record.releaseId >= minimumReleaseId) { "unsupported or replayed update" }
        require(compareVersions(record.version, currentVersion) > 0) { "update version must be newer than the installed version" }
        require(record.createdAt > 0 && record.createdAt <= now + 300000 && record.expiresAt > now &&
            record.expiresAt > record.createdAt && record.expiresAt - record.createdAt <= 90L * 24 * 60 * 60 * 1000) { "expired or invalid update lifetime" }
        require(record.assets.size in 1..32 && record.assets.map { it.name }.distinct().size == record.assets.size) { "invalid update asset list" }
        record.assets.forEach { asset ->
            require(asset.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) &&
                asset.sha256.matches(Regex("[a-f0-9]{64}")) && asset.bytes in 1..(2L * 1024 * 1024 * 1024)) { "invalid update asset" }
        }
        return record
    }

    fun verifyAsset(manifest: ReleaseManifest, file: Path): ReleaseAsset {
        val asset = manifest.assets.singleOrNull { it.name == file.fileName.toString() } ?: error("package is not listed in the signed manifest")
        require(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(file) == asset.bytes && Hashing.sha256(file) == asset.sha256) { "update package hash or size mismatch" }
        return asset
    }

    fun compareVersions(a: String, b: String): Int {
        fun parts(version: String): List<Int> {
            require(version.matches(Regex("(?:0|[1-9][0-9]{0,3})\\.(?:0|[1-9][0-9]{0,3})\\.(?:0|[1-9][0-9]{0,3})"))) { "invalid release version" }
            return version.split('.').map(String::toInt)
        }
        return parts(a).zip(parts(b)).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }
}
