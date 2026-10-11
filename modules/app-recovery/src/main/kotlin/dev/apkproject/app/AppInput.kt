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
import dev.apkproject.store.AtomicFiles

/** One archive inside the input: the input itself, or a split APK inside a bundle. */
class InputPart(val name: String, val inventory: ArchiveInventory, val archivePath: Path? = null, private val readEntry: (ZipEntryRecord) -> ByteArray) {
    fun read(entry: ZipEntryRecord): ByteArray = readEntry(entry)
    fun read(name: String): ByteArray? = inventory.entry(name)?.let(::read)
    val names: List<String> get() = inventory.entries.map { it.name }
}

enum class InputKind { APK, DEX, APK_BUNDLE, APP_BUNDLE_AAB }

/**
 * Normalised view of every supported input shape: a single APK, a bare DEX, a split-APK bundle
 * (.apks, .xapk, .apkm: a ZIP of APKs) or an Android App Bundle (.aab: modules with dex/ folders).
 * Inventories use a fixed-size file window. Entries are streamed and CRC checked on demand;
 * nested APKs are spooled to owned temporary files rather than retained as byte arrays.
 */
class AppInput private constructor(val path: Path, val sha256: String, val kind: InputKind, val parts: List<InputPart>, private val spool: Path? = null) : AutoCloseable {
    override fun close() { spool?.let(AtomicFiles::deleteRecursively) }
    /** Every DEX file with a stable display name, e.g. `base.apk!classes2.dex`. */
    fun dexFiles(): List<Pair<String, DexFile>> = dexSequence().toList()

    /** Callers that do not need cross-DEX indexes can consume one DEX at a time. */
    fun dexSequence(): Sequence<Pair<String, DexFile>> = parts.asSequence().flatMap { part ->
        dexEntries(part).asSequence().map { e ->
            val bytes = part.read(e)
            val name = if (parts.size > 1 || kind == InputKind.APP_BUNDLE_AAB) "${part.name}!${e.name}" else e.name
            name to DexFile.parse(bytes, Hashing.sha256(bytes)).getOrThrow()
        }
    }

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

        fun open(path: Path, spoolParent: Path? = null): Outcome<AppInput> = Outcome.of {
            val size = Files.size(path)
            if (size > limits.maxArchiveBytes) throw FormatException(FormatFailure("", 0, "input", "input is $size bytes; limit is ${limits.maxArchiveBytes}"))
            val magic = Files.newInputStream(path).use { it.readNBytes(4) }
            val sha = Hashing.sha256(path)
            val reader = ZipInventoryReader(limits)
            if (String(magic, Charsets.ISO_8859_1) == "dex\n") {
                if (size > limits.maxEntryUncompressedBytes) throw FormatException(FormatFailure(sha, 0, "input", "bare DEX exceeds entry limit"))
                val crc = java.util.zip.CRC32()
                Files.newInputStream(path).use { stream -> val chunk = ByteArray(8192); while (true) { val n = stream.read(chunk); if (n < 0) break; crc.update(chunk, 0, n) } }
                val entry = ZipEntryRecord("classes.dex", 0, 0, crc.value, size, size, 0, 0, 0, 0)
                val inventory = ArchiveInventory(sha, listOf(entry), emptyList())
                val part = InputPart("classes.dex", inventory, path) { reader.extract(path, inventory, it).getOrThrow() }
                return@of AppInput(path, sha, InputKind.DEX, listOf(part))
            }
            val inventory = reader.read(path, sha).getOrThrow()
            val root = InputPart(path.fileName.toString(), inventory, path) { reader.extract(path, inventory, it).getOrThrow() }
            val hasRootDex = inventory.entries.any { Regex("""classes\d*\.dex""").matches(it.name) }
            val nestedApks = inventory.entries.filter { it.name.endsWith(".apk") && !it.name.contains("..") }
            val aabDex = inventory.entries.any { Regex("""[^/]+/dex/classes\d*\.dex""").matches(it.name) }
            when {
                hasRootDex -> AppInput(path, sha, InputKind.APK, listOf(root))
                aabDex -> AppInput(path, sha, InputKind.APP_BUNDLE_AAB, listOf(root))
                nestedApks.isNotEmpty() -> {
                    val spool = if (spoolParent == null) Files.createTempDirectory("apkproject-input-")
                        else Files.createTempDirectory(Files.createDirectories(spoolParent), "parts-")
                    try {
                        var expanded = 0L
                        var entries = 0
                        val parts = nestedApks.mapIndexed { index, e ->
                            val inner = spool.resolve("$index.apk")
                            reader.extractTo(path, inventory, e, inner).getOrThrow()
                            val inv = reader.read(inner, Hashing.sha256(inner)).getOrThrow()
                            expanded += inv.entries.sumOf { it.uncompressedSize }
                            entries += inv.entries.size
                            if (expanded > limits.maxTotalUncompressedBytes || entries > limits.maxEntries)
                                throw FormatException(FormatFailure(sha, 0, "bundle", "combined split expansion or entry count exceeds limit"))
                            InputPart(e.name, inv, inner) { reader.extract(inner, inv, it).getOrThrow() }
                        }
                        AppInput(path, sha, InputKind.APK_BUNDLE, parts, spool)
                    } catch (e: Throwable) {
                        AtomicFiles.deleteRecursively(spool)
                        throw e
                    }
                }
                else -> throw FormatException(FormatFailure(sha, 0, "archive", "no classes.dex, module dex/ folder or nested APK found", unsupported = true))
            }
        }

    }
}
