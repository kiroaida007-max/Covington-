package dev.apkproject.app

import dev.apkproject.archive.ArchiveInventory
import dev.apkproject.archive.ZipEntryRecord
import dev.apkproject.archive.ZipInventoryReader
import dev.apkproject.archive.ZipLimits
import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import java.nio.file.Files
import java.nio.file.Path

/** One archive inside the input: the input itself, or a split APK inside a bundle. */
class InputPart(val name: String, val bytes: ByteArray, val inventory: ArchiveInventory, private val reader: ZipInventoryReader) {
    /** Extracts the uncompressed bytes of a known entry. */
    fun read(entry: ZipEntryRecord): ByteArray = reader.extract(bytes, inventory, entry).getOrThrow()
    /** Extracts the uncompressed bytes of an entry by name, or null if it is not present. */
    fun read(name: String): ByteArray? = inventory.entry(name)?.let(::read)
    val names: List<String> get() = inventory.entries.map { it.name }
}

enum class InputKind { APK, DEX, APK_BUNDLE, APP_BUNDLE_AAB }

/**
 * Normalised view of every supported input shape: a single APK, a bare DEX, a split-APK bundle
 * (.apks, .xapk, .apkm: a ZIP of APKs) or an Android App Bundle (.aab: modules with dex/ folders).
 * All archives go through the bounded ZIP reader; nothing is extracted to disk.
 */
class AppInput private constructor(val path: Path, val sha256: String, val kind: InputKind, val parts: List<InputPart>) {
    /** Every DEX file with a stable display name, e.g. `base.apk!classes2.dex`. */
    fun dexFiles(): List<Pair<String, DexFile>> = parts.flatMap { part ->
        dexEntries(part).map { e ->
            val bytes = part.read(e)
            val name = if (parts.size > 1 || kind == InputKind.APP_BUNDLE_AAB) "${part.name}!${e.name}" else e.name
            name to DexFile.parse(bytes, Hashing.sha256(bytes)).getOrThrow()
        }
    }

    /** This part's DEX entries, ordered by module then classesN index. */
    private fun dexEntries(part: InputPart): List<ZipEntryRecord> {
        val dex = Regex("""(?:.*/)?classes\d*\.dex""")
        return part.inventory.entries.filter { dex.matches(it.name) && (kind == InputKind.APP_BUNDLE_AAB || !it.name.contains('/')) }
            .sortedWith(compareBy({ it.name.substringBeforeLast('/', "") }, { it.name.removeSuffix(".dex").substringAfterLast("classes").toIntOrNull() ?: 1 }))
    }

    /**
     * The base part's AndroidManifest.xml decoded from binary XML (resource references named through
     * its resources.arsc when present), or null when there is none.
     */
    fun decodedManifest(): String? = runCatching {
        val base = parts.firstOrNull { it.name == "base.apk" } ?: parts.firstOrNull() ?: return null
        val resources = parts.firstNotNullOfOrNull { it.read("resources.arsc") }?.let { ResourceTable.parse(it) } ?: ResourceTable.EMPTY
        base.read("AndroidManifest.xml")?.let { BinaryXml.decode(it, resources) }
    }.getOrNull()

    /** All entry names across parts, prefixed with the part for bundles. */
    fun allEntries(): List<Pair<InputPart, ZipEntryRecord>> = parts.flatMap { p -> p.inventory.entries.map { p to it } }

    companion object {
        private val limits = ZipLimits(maxArchiveBytes = 1024L * 1024 * 1024, maxTotalUncompressedBytes = 4L * 1024 * 1024 * 1024)

        /** Opens a file at [path], detecting whether it is a bare DEX, APK, split-APK bundle or App Bundle. */
        fun open(path: Path): Outcome<AppInput> = Outcome.of {
            val size = Files.size(path)
            if (size > limits.maxArchiveBytes) throw FormatException(FormatFailure("", 0, "input", "input is $size bytes; limit is ${limits.maxArchiveBytes}"))
            val bytes = Files.readAllBytes(path)
            val sha = Hashing.sha256(bytes)
            val reader = ZipInventoryReader(limits)
            if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "dex\n") {
                // A bare DEX: wrap it so later stages see one shape.
                val wrapped = wrappedBytes(bytes)
                val part = InputPart("classes.dex", wrapped, reader.read(wrapped, sha).getOrThrow(), reader)
                return@of AppInput(path, sha, InputKind.DEX, listOf(part))
            }
            val inventory = reader.read(bytes, sha).getOrThrow()
            val root = InputPart(path.fileName.toString(), bytes, inventory, reader)
            val hasRootDex = inventory.entries.any { Regex("""classes\d*\.dex""").matches(it.name) }
            val nestedApks = inventory.entries.filter { it.name.endsWith(".apk") && !it.name.contains("..") }
            val aabDex = inventory.entries.any { Regex("""[^/]+/dex/classes\d*\.dex""").matches(it.name) }
            when {
                hasRootDex -> AppInput(path, sha, InputKind.APK, listOf(root))
                aabDex -> AppInput(path, sha, InputKind.APP_BUNDLE_AAB, listOf(root))
                nestedApks.isNotEmpty() -> {
                    val parts = nestedApks.map { e ->
                        val inner = reader.extract(bytes, inventory, e).getOrThrow()
                        val innerSha = Hashing.sha256(inner)
                        InputPart(e.name, inner, reader.read(inner, innerSha).getOrThrow(), reader)
                    }
                    AppInput(path, sha, InputKind.APK_BUNDLE, parts)
                }
                else -> throw FormatException(FormatFailure(sha, 0, "archive", "no classes.dex, module dex/ folder or nested APK found", unsupported = true))
            }
        }

        /** Wraps a bare DEX in a minimal, uncompressed ZIP so it can be read like any other archive. */
        private fun wrappedBytes(dex: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { zip ->
                val entry = java.util.zip.ZipEntry("classes.dex")
                entry.timeLocal = java.time.LocalDateTime.of(1980, 1, 1, 0, 0)
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
