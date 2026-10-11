package dev.apkproject.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resource ids to names, read from a compiled `resources.arsc` (only what naming needs: package,
 * type and entry names; values and configurations are skipped). `0x7f0d0042` -> layout/activity_main.
 */
class ResourceTable private constructor(private val names: Map<Int, Pair<String, String>>) {
    val size: Int get() = names.size

    /** `(type, name)` of a resource id, e.g. `("layout", "activity_main")`. */
    operator fun get(id: Int): Pair<String, String>? = names[id]

    fun name(id: Int, type: String): String? = names[id]?.takeIf { it.first == type }?.second

    companion object {
        val EMPTY = ResourceTable(emptyMap())

        private const val STRING_POOL = 0x0001
        private const val TABLE = 0x0002
        private const val PACKAGE = 0x0200
        private const val TYPE = 0x0201
        private const val NO_ENTRY = -1

        fun parse(bytes: ByteArray): ResourceTable = runCatching { ResourceTable(read(bytes)) }.getOrDefault(EMPTY)

        /** A table from known ids (tests, or ids read elsewhere). */
        fun of(names: Map<Int, Pair<String, String>>): ResourceTable = ResourceTable(names)

        private fun read(bytes: ByteArray): Map<Int, Pair<String, String>> {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getShort(0).toInt() and 0xffff != TABLE) return emptyMap()
            val out = HashMap<Int, Pair<String, String>>()
            var at = b.getShort(2).toInt() and 0xffff
            while (at + 8 <= bytes.size) {
                val type = b.getShort(at).toInt() and 0xffff
                val size = b.getInt(at + 4)
                if (size < 8 || at + size > bytes.size) break
                if (type == PACKAGE) readPackage(b, at, size, out)
                at += size
            }
            return out
        }

        private fun readPackage(b: ByteBuffer, start: Int, size: Int, out: MutableMap<Int, Pair<String, String>>) {
            val headerSize = b.getShort(start + 2).toInt() and 0xffff
            val packageId = b.getInt(start + 8)
            val typeStrings = strings(b, start + b.getInt(start + 8 + 4 + 256))
            val keyStrings = strings(b, start + b.getInt(start + 8 + 4 + 256 + 8))
            var at = start + headerSize
            val end = start + size
            while (at + 8 <= end) {
                val type = b.getShort(at).toInt() and 0xffff
                val chunkHeader = b.getShort(at + 2).toInt() and 0xffff
                val chunkSize = b.getInt(at + 4)
                if (chunkSize < 8 || at + chunkSize > end) break
                if (type == TYPE) {
                    val typeId = b.get(at + 8).toInt() and 0xff
                    val flags = b.get(at + 9).toInt() and 0xff
                    val count = b.getInt(at + 12)
                    val entriesStart = at + b.getInt(at + 16)
                    val typeName = typeStrings.getOrNull(typeId - 1) ?: "type$typeId"
                    val offsets = at + chunkHeader
                    for (k in 0 until count) {
                        // FLAG_SPARSE (1): (index, offset / 4) pairs; FLAG_OFFSET16 (2): 16-bit offsets / 4.
                        val (index, offset) = when {
                            flags and 0x01 != 0 -> (b.getShort(offsets + 4 * k).toInt() and 0xffff) to (b.getShort(offsets + 4 * k + 2).toInt() and 0xffff) * 4
                            flags and 0x02 != 0 -> k to (b.getShort(offsets + 2 * k).toInt() and 0xffff).let { if (it == 0xffff) NO_ENTRY else it * 4 }
                            else -> k to b.getInt(offsets + 4 * k)
                        }
                        if (offset == NO_ENTRY) continue
                        val entry = entriesStart + offset
                        if (entry + 8 > at + chunkSize) continue
                        val entryFlags = b.getShort(entry + 2).toInt() and 0xffff
                        // FLAG_COMPACT (8): the key index is in the first 16 bits.
                        val key = if (entryFlags and 0x08 != 0) b.getShort(entry).toInt() and 0xffff else b.getInt(entry + 4)
                        val name = keyStrings.getOrNull(key) ?: continue
                        val id = (packageId shl 24) or (typeId shl 16) or index
                        out.putIfAbsent(id, typeName to name)
                    }
                }
                at += chunkSize
            }
        }

        /** A ResStringPool chunk at [start] (also used by [BinaryXml]). */
        internal fun strings(b: ByteBuffer, start: Int): List<String> {
            if (b.getShort(start).toInt() and 0xffff != STRING_POOL) return emptyList()
            val count = b.getInt(start + 8)
            val utf8 = b.getInt(start + 16) and 0x100 != 0
            val data = start + b.getInt(start + 20)
            val offsets = start + (b.getShort(start + 2).toInt() and 0xffff)
            return List(count) { k ->
                var at = data + b.getInt(offsets + 4 * k)
                if (utf8) {
                    at += if (b.get(at).toInt() and 0x80 != 0) 2 else 1 // UTF-16 length
                    var len = b.get(at).toInt() and 0xff
                    if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (b.get(at + 1).toInt() and 0xff); at += 2 } else at += 1
                    val bytes = ByteArray(len)
                    b.get(at, bytes)
                    String(bytes, Charsets.UTF_8)
                } else {
                    var len = b.getShort(at).toInt() and 0xffff
                    if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (b.getShort(at + 2).toInt() and 0xffff); at += 4 } else at += 2
                    val chars = CharArray(len) { i -> b.getChar(at + 2 * i) }
                    String(chars)
                }
            }
        }
    }
}
