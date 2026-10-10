package dev.apkproject.dex

import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure

/**
 * Bounded little-endian reader. Every read checks its range against [limit]; a failure carries
 * the artifact hash, absolute offset and section name.
 */
class DexBytes(val bytes: ByteArray, val sha256: String, val limit: Int = bytes.size) {
    var section: String = "header"

    fun fail(offset: Int, reason: String, unsupported: Boolean = false): Nothing =
        throw FormatException(FormatFailure(sha256, offset.toLong(), section, reason, unsupported))

    fun need(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset.toLong() + length > limit) {
            fail(offset, "read of $length bytes at 0x${offset.toString(16)} is outside the file (size $limit)")
        }
    }

    fun u1(p: Int): Int {
        need(p, 1)
        return bytes[p].toInt() and 0xff
    }

    fun u2(p: Int): Int {
        need(p, 2)
        return (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8)
    }

    fun s4(p: Int): Int {
        need(p, 4)
        return (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or
            ((bytes[p + 2].toInt() and 0xff) shl 16) or ((bytes[p + 3].toInt() and 0xff) shl 24)
    }

    fun u4(p: Int): Long = s4(p).toLong() and 0xffffffffL

    /** u4 used as an offset or count; values that cannot be a position in this file fail. */
    fun u4Index(p: Int, what: String): Int {
        val v = u4(p)
        if (v > Int.MAX_VALUE) fail(p, "$what 0x${v.toString(16)} is too large")
        return v.toInt()
    }

    /** A cursor for variable-length encodings. */
    inner class Cursor(var pos: Int) {
        fun u1(): Int = this@DexBytes.u1(pos).also { pos += 1 }
        fun u2(): Int = this@DexBytes.u2(pos).also { pos += 2 }
        fun s4(): Int = this@DexBytes.s4(pos).also { pos += 4 }

        fun uleb128(): Int {
            val start = pos
            var result = 0L
            var shift = 0
            while (true) {
                val b = this@DexBytes.u1(pos++)
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift >= 35) fail(start, "uleb128 longer than 5 bytes")
            }
            if (result > 0xffffffffL) fail(start, "uleb128 value exceeds 32 bits")
            return result.toInt()
        }

        /** uleb128 interpreted as an unsigned value that must fit a non-negative Int. */
        fun ulebIndex(what: String): Int {
            val start = pos
            val v = uleb128()
            if (v < 0) fail(start, "$what ${v.toLong() and 0xffffffffL} is too large")
            return v
        }

        fun uleb128p1(): Int = uleb128() - 1

        fun sleb128(): Int {
            val start = pos
            var result = 0L
            var shift = 0
            var b: Int
            while (true) {
                b = this@DexBytes.u1(pos++)
                result = result or ((b and 0x7f).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) break
                if (shift >= 35) fail(start, "sleb128 longer than 5 bytes")
            }
            if (shift < 64 && (b and 0x40) != 0) result = result or (-1L shl shift)
            return result.toInt()
        }
    }
}

/** Modified UTF-8 as used by DEX string_data_item. */
object Mutf8 {
    /** Decodes bytes starting at [offset] up to the terminating NUL; returns the string and end offset. */
    fun decode(r: DexBytes, offset: Int, utf16Length: Int): Pair<String, Int> {
        // A UTF-16 unit needs at most 3 bytes; check the bound before allocating.
        if (utf16Length.toLong() * 1 > r.limit - offset) r.fail(offset, "string length $utf16Length exceeds remaining data")
        val sb = StringBuilder(minOf(utf16Length, 1 shl 16))
        var p = offset
        while (true) {
            val a = r.u1(p)
            when {
                a == 0 -> {
                    p++
                    break
                }
                a < 0x80 -> {
                    sb.append(a.toChar()); p += 1
                }
                a and 0xe0 == 0xc0 -> {
                    val b = r.u1(p + 1)
                    if (b and 0xc0 != 0x80) r.fail(p, "bad MUTF-8 continuation byte")
                    val v = ((a and 0x1f) shl 6) or (b and 0x3f)
                    if (v != 0 && v < 0x80) r.fail(p, "overlong MUTF-8 sequence")
                    sb.append(v.toChar()); p += 2
                }
                a and 0xf0 == 0xe0 -> {
                    val b = r.u1(p + 1)
                    val c = r.u1(p + 2)
                    if (b and 0xc0 != 0x80 || c and 0xc0 != 0x80) r.fail(p, "bad MUTF-8 continuation byte")
                    val v = ((a and 0x0f) shl 12) or ((b and 0x3f) shl 6) or (c and 0x3f)
                    if (v < 0x800) r.fail(p, "overlong MUTF-8 sequence")
                    sb.append(v.toChar()); p += 3
                }
                else -> r.fail(p, "invalid MUTF-8 lead byte 0x${a.toString(16)}")
            }
            if (sb.length > utf16Length) r.fail(offset, "string is longer than its declared $utf16Length UTF-16 units")
        }
        if (sb.length != utf16Length) r.fail(offset, "string has ${sb.length} UTF-16 units, declared $utf16Length")
        return sb.toString() to p
    }

    fun encode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length + 1)
        for (ch in s) {
            val c = ch.code
            when {
                c in 1..0x7f -> out.write(c)
                c < 0x800 -> {
                    out.write(0xc0 or (c shr 6)); out.write(0x80 or (c and 0x3f))
                }
                else -> {
                    out.write(0xe0 or (c shr 12)); out.write(0x80 or ((c shr 6) and 0x3f)); out.write(0x80 or (c and 0x3f))
                }
            }
        }
        out.write(0)
        return out.toByteArray()
    }
}

object Descriptors {
    private const val PRIMITIVES = "ZBSCIJFD"

    fun isValidType(d: String, allowVoid: Boolean = true): Boolean {
        var i = 0
        while (i < d.length && d[i] == '[') i++
        if (i > 255) return false
        if (i == d.length) return false
        val rest = d.substring(i)
        return when {
            rest.length == 1 && rest[0] in PRIMITIVES -> true
            rest == "V" -> allowVoid && i == 0
            rest.startsWith("L") && rest.endsWith(";") && rest.length > 2 -> {
                val body = rest.substring(1, rest.length - 1)
                body.split('/').all { it.isNotEmpty() && it.none { c -> c == '.' || c == ';' || c == '[' || c == '(' || c == ')' } }
            }
            else -> false
        }
    }

    /** Shorty character for a type descriptor, as stored in proto_id_item. */
    fun shorty(d: String): Char = if (d.startsWith("L") || d.startsWith("[")) 'L' else d[0]

    /** Number of 16-bit registers a value of this type occupies. */
    fun registerWidth(d: String): Int = if (d == "J" || d == "D") 2 else 1

    /** `Lcom/example/Foo;` -> `com.example.Foo` */
    fun toJavaName(d: String): String {
        var dims = 0
        while (dims < d.length && d[dims] == '[') dims++
        val base = when (val e = d.substring(dims)) {
            "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"; "C" -> "char"; "I" -> "int"
            "J" -> "long"; "F" -> "float"; "D" -> "double"; "V" -> "void"
            else -> e.substring(1, e.length - 1).replace('/', '.')
        }
        return base + "[]".repeat(dims)
    }
}
