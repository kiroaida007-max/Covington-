package dev.apkproject.archive

import dev.apkproject.contracts.ArtifactRef
import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import java.nio.file.Files
import java.nio.file.Path

/** One extracted entry, stored by content hash, with the archive entry it came from. */
data class ExtractedEntry(val entry: ZipEntryRecord, val ref: ArtifactRef, val bytes: ByteArray)

/** The immutable starting point of every job: input bytes, their hash and the verified inventory. */
class ApkSnapshot private constructor(
    val ref: ArtifactRef,
    val bytes: ByteArray,
    val inventory: ArchiveInventory,
    private val reader: ZipInventoryReader,
) {
    /** Extracts and CRC-checks an entry. */
    fun extract(entry: ZipEntryRecord): Outcome<ByteArray> = reader.extract(bytes, inventory, entry)

    fun dexFiles(): Outcome<List<ExtractedEntry>> = Outcome.of {
        inventory.dexEntries().map { e ->
            val data = extract(e).getOrThrow()
            ExtractedEntry(e, ArtifactRef(Hashing.sha256(data), e.name, data.size.toLong(), ZipInventoryReader.mediaTypeOf(e.name)), data)
        }
    }

    companion object {
        /**
         * Reads the input once, bounded by [limits]. A bare `.dex` file is accepted as a
         * single-entry snapshot so the method laboratory can run without packaging.
         */
        fun take(path: Path, limits: ZipLimits = ZipLimits()): Outcome<ApkSnapshot> = Outcome.of {
            val size = Files.size(path)
            if (size > limits.maxArchiveBytes) {
                throw FormatException(FormatFailure("", 0, "input", "input is $size bytes; limit is ${limits.maxArchiveBytes}"))
            }
            val bytes = Files.readAllBytes(path)
            fromBytes(bytes, path.fileName.toString(), limits).getOrThrow()
        }

        fun fromBytes(bytes: ByteArray, name: String, limits: ZipLimits = ZipLimits()): Outcome<ApkSnapshot> = Outcome.of {
            val sha = Hashing.sha256(bytes)
            val reader = ZipInventoryReader(limits)
            val isDex = bytes.size >= 4 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
                bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()
            if (isDex) {
                val wrapped = wrapDex(bytes)
                val inv = reader.read(wrapped, sha).getOrThrow()
                ApkSnapshot(ArtifactRef(sha, name, bytes.size.toLong(), "application/vnd.android.dex"), wrapped, inv, reader)
            } else {
                val inv = reader.read(bytes, sha).getOrThrow()
                ApkSnapshot(ArtifactRef(sha, name, bytes.size.toLong(), "application/vnd.android.package-archive"), bytes, inv, reader)
            }
        }

        /** Presents a bare DEX as a stored `classes.dex` entry so later stages see one input shape. */
        private fun wrapDex(dex: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { zip ->
                val entry = java.util.zip.ZipEntry("classes.dex")
                entry.method = java.util.zip.ZipEntry.STORED
                entry.size = dex.size.toLong()
                entry.compressedSize = dex.size.toLong()
                entry.crc = java.util.zip.CRC32().also { it.update(dex) }.value
                zip.putNextEntry(entry)
                zip.write(dex)
                zip.closeEntry()
            }
            return out.toByteArray()
        }
    }
}
