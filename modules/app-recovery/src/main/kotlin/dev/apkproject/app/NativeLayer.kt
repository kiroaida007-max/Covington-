package dev.apkproject.app

import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure
import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable

@Serializable
data class ElfSummary(
    val bits: Int,
    val machine: String,
    val needed: List<String>,
    val exportedFunctions: List<String>,
)

/** Bounded reader for the parts of ELF shared objects needed to map JNI boundaries. */
object ElfReader {
    fun read(bytes: ByteArray, name: String = "lib"): Outcome<ElfSummary> = Outcome.of { parse(bytes, name) }

    private fun parse(b: ByteArray, name: String): ElfSummary {
        fun fail(off: Long, reason: String): Nothing = throw FormatException(FormatFailure(name, off, "elf", reason))
        if (b.size < 52 || b[0] != 0x7f.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) fail(0, "not an ELF file")
        val is64 = when (b[4].toInt()) {
            1 -> false; 2 -> true; else -> fail(4, "bad ELF class")
        }
        if (b[5].toInt() != 1) throw FormatException(FormatFailure(name, 5, "elf", "big-endian ELF is not supported", unsupported = true))
        fun u16(o: Long): Int {
            if (o < 0 || o + 2 > b.size) fail(o, "read out of bounds")
            return (b[o.toInt()].toInt() and 0xff) or ((b[o.toInt() + 1].toInt() and 0xff) shl 8)
        }
        fun u32(o: Long): Long {
            if (o < 0 || o + 4 > b.size) fail(o, "read out of bounds")
            return (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
        }
        fun u64(o: Long): Long = u32(o) or (u32(o + 4) shl 32)
        fun addr(o: Long): Long = if (is64) u64(o) else u32(o)
        val machine = when (u16(18)) {
            0x28 -> "arm"; 0xb7 -> "aarch64"; 0x03 -> "x86"; 0x3e -> "x86_64"; 0xf3 -> "riscv"; else -> "machine-${u16(18)}"
        }
        val shoff = addr(if (is64) 0x28 else 0x20)
        val shentsize = u16(if (is64) 0x3a else 0x2e)
        val shnum = u16(if (is64) 0x3c else 0x30)
        if (shnum == 0 || shoff == 0L) return ElfSummary(if (is64) 64 else 32, machine, emptyList(), emptyList())
        if (shoff + shnum.toLong() * shentsize > b.size || shentsize < (if (is64) 64 else 40)) fail(shoff, "section header table out of bounds")
        data class Sec(val type: Long, val offset: Long, val size: Long, val link: Int, val entsize: Long)
        val secs = (0 until shnum).map { i ->
            val o = shoff + i.toLong() * shentsize
            if (is64) Sec(u32(o + 4), u64(o + 0x18), u64(o + 0x20), u32(o + 0x28).toInt(), u64(o + 0x38))
            else Sec(u32(o + 4), u32(o + 0x10), u32(o + 0x14), u32(o + 0x18).toInt(), u32(o + 0x24))
        }
        fun cstr(table: Sec, idx: Long): String {
            if (idx < 0 || idx >= table.size) return ""
            val start = (table.offset + idx).toInt()
            if (start >= b.size) return ""
            var end = start
            while (end < b.size && end < start + 4096 && b[end] != 0.toByte()) end++
            return String(b, start, end - start, Charsets.UTF_8)
        }
        val exports = ArrayList<String>()
        val needed = ArrayList<String>()
        for (s in secs) {
            if (s.offset + s.size > b.size) continue
            if (s.type == 11L) { // SHT_DYNSYM
                val strtab = secs.getOrNull(s.link) ?: continue
                val ent = if (s.entsize > 0) s.entsize else if (is64) 24L else 16L
                val count = (s.size / ent).coerceAtMost(500_000)
                for (k in 0 until count) {
                    val o = s.offset + k * ent
                    val nameIdx = u32(o)
                    val info: Int
                    val shndx: Int
                    if (is64) {
                        info = b[(o + 4).toInt()].toInt() and 0xff; shndx = u16(o + 6)
                    } else {
                        info = b[(o + 12).toInt()].toInt() and 0xff; shndx = u16(o + 14)
                    }
                    val type = info and 0xf
                    val bind = info ushr 4
                    if (type == 2 && (bind == 1 || bind == 2) && shndx != 0) exports += cstr(strtab, nameIdx)
                }
            } else if (s.type == 6L) { // SHT_DYNAMIC
                val strtab = secs.getOrNull(s.link) ?: continue
                val ent = if (is64) 16L else 8L
                for (k in 0 until (s.size / ent).coerceAtMost(10_000)) {
                    val o = s.offset + k * ent
                    val tag = addr(o)
                    if (tag == 0L) break
                    if (tag == 1L) needed += cstr(strtab, addr(o + if (is64) 8 else 4))
                }
            }
        }
        return ElfSummary(if (is64) 64 else 32, machine, needed.filter { it.isNotEmpty() }, exports.filter { it.isNotEmpty() }.distinct().sorted())
    }
}

@Serializable
data class JniBinding(val javaMethod: String, val library: String, val symbol: String)

@Serializable
data class NativeLibraryReport(val path: String, val abi: String, val machine: String, val bits: Int, val needed: List<String>, val exportedFunctions: Int, val jniFunctions: List<String>, val registersNativesAtLoad: Boolean)

@Serializable
data class NativeLayer(
    val libraries: List<NativeLibraryReport>,
    val javaNativeMethods: List<String>,
    val boundStatically: List<JniBinding>,
    val unbound: List<String>,
    val notes: List<String>,
)

object NativeMapper {
    /** `Java_com_example_Foo_bar__I` -> ("com/example/Foo", "bar"), following the JNI name mangling rules. */
    fun decodeJni(symbol: String): Pair<String, String>? {
        if (!symbol.startsWith("Java_")) return null
        val body = symbol.removePrefix("Java_").substringBefore("__")
        val parts = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '_') {
                val next = body.getOrNull(i + 1)
                when (next) {
                    '1' -> { parts.last().append('_'); i += 2; continue }
                    '2' -> { parts.last().append(';'); i += 2; continue }
                    '3' -> { parts.last().append('['); i += 2; continue }
                    '0' -> {
                        val hex = body.substring(i + 2, minOf(i + 6, body.length))
                        hex.toIntOrNull(16)?.let { parts.last().append(it.toChar()) }
                        i += 6; continue
                    }
                    else -> { parts += StringBuilder(); i++; continue }
                }
            }
            parts.last().append(c)
            i++
        }
        if (parts.size < 2) return null
        return parts.dropLast(1).joinToString("/") to parts.last().toString()
    }

    fun map(input: AppInput, dex: List<Pair<String, DexFile>>): NativeLayer {
        val natives = dex.flatMap { it.second.methods() }.filter { it.accessFlags and AccessFlags.NATIVE != 0 }
            .map { "${it.owner.removePrefix("L").removeSuffix(";")}.${it.name}${it.protoDescriptor}" }.distinct().sorted()
        val libs = ArrayList<NativeLibraryReport>()
        val bindings = ArrayList<JniBinding>()
        val soEntries = input.allEntries().filter { (_, e) -> e.name.matches(Regex("""(?:[^/]+/)?lib/[^/]+/[^/]+\.so""")) && e.uncompressedSize < 512L * 1024 * 1024 }
        for ((part, e) in soEntries) {
            val abi = e.name.substringBeforeLast('/').substringAfterLast('/')
            val summary = (ElfReader.read(part.read(e), e.name) as? Outcome.Ok)?.value ?: continue
            val jni = summary.exportedFunctions.filter { it.startsWith("Java_") }
            libs += NativeLibraryReport(e.name, abi, summary.machine, summary.bits, summary.needed, summary.exportedFunctions.size, jni, "JNI_OnLoad" in summary.exportedFunctions)
            for (sym in jni) {
                val (cls, method) = decodeJni(sym) ?: continue
                natives.firstOrNull { it.startsWith("$cls.$method(") }?.let { bindings += JniBinding(it, e.name, sym) }
            }
        }
        val bound = bindings.map { it.javaMethod }.toSet()
        val notes = buildList {
            add("Statically bound methods are matched by JNI symbol name. Methods registered at runtime through JNI_OnLoad/RegisterNatives cannot be matched without disassembly.")
            add("Native code is machine code: this layer lists boundaries, not source. Pseudocode needs a native disassembler (REA with Ghidra or Hopper).")
        }
        return NativeLayer(libs.sortedBy { it.path }, natives, bindings.distinctBy { it.javaMethod + it.library }, natives.filter { it !in bound }, notes)
    }

    fun readme(n: NativeLayer): String = buildString {
        append("# Native layer\n\n")
        if (n.libraries.isEmpty()) {
            append("The APK contains no native libraries.\n"); return@buildString
        }
        append("| Library | ABI | Exported functions | JNI functions | JNI_OnLoad | Depends on |\n|---|---|---|---|---|---|\n")
        n.libraries.forEach { append("| `${it.path}` | ${it.abi} | ${it.exportedFunctions} | ${it.jniFunctions.size} | ${if (it.registersNativesAtLoad) "yes" else "no"} | ${it.needed.joinToString(", ")} |\n") }
        append("\n${n.javaNativeMethods.size} Java `native` methods; ${n.boundStatically.map { it.javaMethod }.distinct().size} matched to an exported JNI symbol.\n\n")
        n.notes.forEach { append("- ").append(it).append('\n') }
    }
}
