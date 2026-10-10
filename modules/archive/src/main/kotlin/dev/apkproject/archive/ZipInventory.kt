package dev.apkproject.archive

import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure
import dev.apkproject.contracts.Outcome
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Bounds applied before any allocation driven by archive contents (sections 11 and 26). */
data class ZipLimits(
    val maxArchiveBytes: Long = 256L * 1024 * 1024,
    val maxEntries: Int = 65_535,
    val maxEntryUncompressedBytes: Long = 128L * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 1024L * 1024 * 1024,
    /** Deflate cannot exceed ~1032:1; anything above this is a forged size field. */
    val maxCompressionRatio: Long = 1_100,
    val maxNameBytes: Int = 4_096,
)

data class ZipEntryRecord(
    val name: String,
    val method: Int,
    val flags: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val dataOffset: Long,
    val versionMadeBy: Int,
    val externalAttributes: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

data class ArchiveInventory(
    val artifactSha256: String,
    val entries: List<ZipEntryRecord>,
    /** Observed oddities that do not make the archive ambiguous, e.g. case-only name collisions. */
    val warnings: List<String>,
) {
    fun entry(name: String): ZipEntryRecord? = entries.firstOrNull { it.name == name }

    /** Primary and secondary DEX files in runtime load order: classes.dex, classes2.dex, ... */
    fun dexEntries(): List<ZipEntryRecord> {
        val byName = entries.associateBy { it.name }
        val out = ArrayList<ZipEntryRecord>()
        byName["classes.dex"]?.let(out::add)
        var i = 2
        while (true) {
            val e = byName["classes$i.dex"] ?: break
            out += e
            i++
        }
        return out
    }
}

/**
 * Our own ZIP central-directory reader. We do not use java.util.zip.ZipFile for inventory because
 * it silently tolerates duplicate names, mismatched local headers and overlapping entries, all of
 * which make "which bytes does the runtime load" ambiguous.
 */
class ZipInventoryReader(private val limits: ZipLimits = ZipLimits()) {

    fun read(bytes: ByteArray, sha256: String): Outcome<ArchiveInventory> = Outcome.of { readOrThrow(bytes, sha256) }

    private fun fail(sha: String, offset: Long, section: String, reason: String, unsupported: Boolean = false): Nothing =
        throw FormatException(FormatFailure(sha, offset, section, reason, unsupported))

    private fun readOrThrow(bytes: ByteArray, sha: String): ArchiveInventory {
        if (bytes.size.toLong() > limits.maxArchiveBytes) {
            fail(sha, 0, "archive", "archive is ${bytes.size} bytes; limit is ${limits.maxArchiveBytes}")
        }
        val r = LeBytes(bytes, sha)
        val eocd = findEocd(r)
        val diskNumber = r.u2(eocd + 4)
        val cdDisk = r.u2(eocd + 6)
        val entriesOnDisk = r.u2(eocd + 8)
        val totalEntries = r.u2(eocd + 10)
        val cdSize = r.u4(eocd + 12)
        val cdOffset = r.u4(eocd + 16)
        if (diskNumber != 0 || cdDisk != 0 || entriesOnDisk != totalEntries) {
            fail(sha, eocd.toLong(), "eocd", "multi-disk archives are not supported", unsupported = true)
        }
        if (totalEntries == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
            fail(sha, eocd.toLong(), "eocd", "ZIP64 archives are not supported in this profile", unsupported = true)
        }
        if (totalEntries > limits.maxEntries) fail(sha, eocd.toLong(), "eocd", "$totalEntries entries exceed limit ${limits.maxEntries}")
        if (cdOffset + cdSize > eocd) fail(sha, eocd.toLong(), "eocd", "central directory [$cdOffset, +$cdSize) overlaps the end record")

        val entries = ArrayList<ZipEntryRecord>(totalEntries)
        var p = cdOffset.toInt()
        val cdEnd = (cdOffset + cdSize).toInt()
        repeat(totalEntries) { index ->
            if (p + 46 > cdEnd) fail(sha, p.toLong(), "central-directory", "entry $index runs past the central directory")
            if (r.u4(p) != 0x02014b50L) fail(sha, p.toLong(), "central-directory", "bad central header signature")
            val versionMadeBy = r.u2(p + 4)
            val flags = r.u2(p + 8)
            val method = r.u2(p + 10)
            val crc = r.u4(p + 16)
            val csize = r.u4(p + 20)
            val usize = r.u4(p + 24)
            val nameLen = r.u2(p + 28)
            val extraLen = r.u2(p + 30)
            val commentLen = r.u2(p + 32)
            val diskStart = r.u2(p + 34)
            val external = r.u4(p + 38)
            val localOffset = r.u4(p + 42)
            if (nameLen > limits.maxNameBytes) fail(sha, p.toLong(), "central-directory", "name length $nameLen exceeds limit")
            val next = p + 46 + nameLen + extraLen + commentLen
            if (next > cdEnd) fail(sha, p.toLong(), "central-directory", "entry $index variable fields run past the central directory")
            val nameBytes = r.slice(p + 46, nameLen)
            val name = decodeName(nameBytes) ?: fail(sha, (p + 46).toLong(), "central-directory", "entry name is not valid UTF-8")
            checkName(sha, p.toLong(), name)
            if (diskStart != 0) fail(sha, p.toLong(), "central-directory", "$name: multi-disk entry", unsupported = true)
            if (flags and 0x1 != 0) fail(sha, p.toLong(), "central-directory", "$name: encrypted entries are not supported", unsupported = true)
            if (method != STORED && method != DEFLATED) fail(sha, p.toLong(), "central-directory", "$name: compression method $method", unsupported = true)
            if (csize == 0xffffffffL || usize == 0xffffffffL || localOffset == 0xffffffffL) {
                fail(sha, p.toLong(), "central-directory", "$name: ZIP64 sizes are not supported", unsupported = true)
            }
            if ((versionMadeBy ushr 8) == UNIX_HOST && ((external ushr 16) and 0xF000L) == 0xA000L) {
                fail(sha, p.toLong(), "central-directory", "$name: symbolic link entries are rejected")
            }
            if (method == STORED && csize != usize) fail(sha, p.toLong(), "central-directory", "$name: stored entry with differing sizes")
            if (usize > limits.maxEntryUncompressedBytes) fail(sha, p.toLong(), "central-directory", "$name: $usize bytes exceeds entry limit")
            if (csize > 0 && usize / csize > limits.maxCompressionRatio) {
                fail(sha, p.toLong(), "central-directory", "$name: compression ratio ${usize / csize} is not achievable by deflate")
            }
            if (csize == 0L && usize != 0L) fail(sha, p.toLong(), "central-directory", "$name: empty data claims $usize bytes")
            val dataOffset = checkLocalHeader(r, sha, localOffset, nameBytes, name, method, cdOffset)
            if (dataOffset + csize > cdOffset) fail(sha, localOffset, "local-header", "$name: data runs into the central directory")
            entries += ZipEntryRecord(name, method, flags, crc, csize, usize, localOffset, dataOffset, versionMadeBy, external)
            p = next
        }
        if (p != cdEnd) fail(sha, p.toLong(), "central-directory", "declared size leaves ${cdEnd - p} unparsed bytes")

        // Duplicate names make the loaded bytes depend on which reader is used.
        val seen = HashSet<String>()
        for (e in entries) if (!seen.add(e.name)) fail(sha, e.localHeaderOffset, "central-directory", "duplicate entry name ${e.name}")
        val total = entries.sumOf { it.uncompressedSize }
        if (total > limits.maxTotalUncompressedBytes) fail(sha, 0, "archive", "total expansion $total exceeds ${limits.maxTotalUncompressedBytes}")

        // Overlapping data ranges are a classic way to multiply expansion or hide content.
        val byOffset = entries.sortedBy { it.localHeaderOffset }
        for (i in 0 until byOffset.size - 1) {
            val a = byOffset[i]
            val b = byOffset[i + 1]
            if (a.dataOffset + a.compressedSize > b.localHeaderOffset) {
                fail(sha, b.localHeaderOffset, "local-header", "entries ${a.name} and ${b.name} overlap")
            }
        }

        val warnings = entries.groupBy { it.name.lowercase() }.filter { it.value.size > 1 }
            .map { (_, group) -> "case-only name collision: ${group.joinToString { it.name }}" }
        return ArchiveInventory(sha, entries, warnings)
    }

    private fun findEocd(r: LeBytes): Int {
        val size = r.size
        if (size < 22) r.fail(0, "eocd", "file is too small to be a ZIP archive")
        val lowest = maxOf(0, size - 22 - 0xffff)
        var p = size - 22
        while (p >= lowest) {
            if (r.u4(p) == 0x06054b50L && p + 22 + r.u2(p + 20) == size) return p
            p--
        }
        r.fail((size - 22).toLong(), "eocd", "end of central directory record not found")
    }

    private fun checkLocalHeader(r: LeBytes, sha: String, offset: Long, centralName: ByteArray, name: String, method: Int, cdOffset: Long): Long {
        val p = offset.toInt()
        if (offset + 30 > cdOffset) fail(sha, offset, "local-header", "$name: local header outside entry area")
        if (r.u4(p) != 0x04034b50L) fail(sha, offset, "local-header", "$name: bad local header signature")
        val localMethod = r.u2(p + 8)
        val nameLen = r.u2(p + 26)
        val extraLen = r.u2(p + 28)
        if (localMethod != method) fail(sha, offset, "local-header", "$name: local method $localMethod differs from central $method")
        if (!r.slice(p + 30, nameLen).contentEquals(centralName)) fail(sha, offset, "local-header", "$name: local name differs from central name")
        return offset + 30 + nameLen + extraLen
    }

    private fun checkName(sha: String, offset: Long, name: String) {
        val reason = when {
            name.isEmpty() -> "empty entry name"
            name.startsWith("/") -> "absolute path"
            name.contains('\\') -> "backslash in entry name"
            name.contains('\u0000') -> "NUL in entry name"
            DRIVE.containsMatchIn(name) -> "drive-qualified path"
            name.split('/').dropLast(if (name.endsWith("/")) 1 else 0).any { it == ".." || it == "." || it.isEmpty() } -> "path traversal or empty segment"
            else -> null
        }
        if (reason != null) fail(sha, offset, "central-directory", "$reason: '$name'")
    }

    private fun decodeName(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /**
     * Inflates one entry with a hard output bound and verifies size and CRC while streaming.
     * Expansion beyond the declared size is an error, never a truncation.
     */
    fun extract(bytes: ByteArray, inventory: ArchiveInventory, entry: ZipEntryRecord): Outcome<ByteArray> = Outcome.of {
        val sha = inventory.artifactSha256
        val start = entry.dataOffset.toInt()
        val csize = entry.compressedSize.toInt()
        if (entry.dataOffset + entry.compressedSize > bytes.size) fail(sha, entry.dataOffset, "entry-data", "${entry.name}: data past end of file")
        val out = ByteArray(entry.uncompressedSize.toInt())
        val crc = CRC32()
        when (entry.method) {
            STORED -> {
                System.arraycopy(bytes, start, out, 0, csize)
                crc.update(out)
            }
            DEFLATED -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(bytes, start, csize)
                    var written = 0
                    val probe = ByteArray(1)
                    while (!inflater.finished()) {
                        val n = if (written < out.size) {
                            inflater.inflate(out, written, out.size - written)
                        } else {
                            // Output is full: any further byte means the declared size was a lie.
                            val extra = inflater.inflate(probe)
                            if (extra > 0) fail(sha, entry.dataOffset, "entry-data", "${entry.name}: expands beyond declared ${entry.uncompressedSize} bytes")
                            extra
                        }
                        if (n > 0) {
                            crc.update(out, written, n)
                            written += n
                        } else if (!inflater.finished()) {
                            fail(sha, entry.dataOffset, "entry-data", "${entry.name}: truncated deflate stream")
                        }
                    }
                    if (written != out.size) fail(sha, entry.dataOffset, "entry-data", "${entry.name}: inflated $written of ${out.size} declared bytes")
                    if (inflater.remaining > 0) fail(sha, entry.dataOffset, "entry-data", "${entry.name}: ${inflater.remaining} trailing compressed bytes")
                } catch (e: DataFormatException) {
                    fail(sha, entry.dataOffset, "entry-data", "${entry.name}: invalid deflate data (${e.message})")
                } finally {
                    inflater.end()
                }
            }
        }
        if (crc.value != entry.crc32) {
            fail(sha, entry.dataOffset, "entry-data", "${entry.name}: CRC ${crc.value.toString(16)} != declared ${entry.crc32.toString(16)}")
        }
        out
    }

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        private const val UNIX_HOST = 3
        private val DRIVE = Regex("^[A-Za-z]:")

        fun mediaTypeOf(name: String): String = when {
            DEX_NAME.matches(name) -> "application/vnd.android.dex"
            name == "AndroidManifest.xml" -> "application/vnd.android.binary-xml"
            name == "resources.arsc" -> "application/vnd.android.arsc"
            name.startsWith("lib/") && name.endsWith(".so") -> "application/x-elf"
            else -> "application/octet-stream"
        }

        private val DEX_NAME = Regex("classes\\d*\\.dex")
    }
}

/** Little-endian bounded reads over the whole archive. */
internal class LeBytes(private val b: ByteArray, private val sha: String) {
    val size: Int get() = b.size

    fun fail(offset: Long, section: String, reason: String): Nothing =
        throw FormatException(FormatFailure(sha, offset, section, reason))

    private fun need(p: Int, n: Int) {
        if (p < 0 || n < 0 || p.toLong() + n > b.size) fail(p.toLong(), "archive", "read of $n bytes at $p is out of bounds")
    }

    fun u2(p: Int): Int {
        need(p, 2)
        return (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    }

    fun u4(p: Int): Long {
        need(p, 4)
        return (u2(p).toLong()) or (u2(p + 2).toLong() shl 16)
    }

    fun slice(p: Int, n: Int): ByteArray {
        need(p, n)
        return b.copyOfRange(p, p + n)
    }
}
