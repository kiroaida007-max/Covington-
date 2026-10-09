package dev.apkproject.dex

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import java.security.MessageDigest
import java.util.zip.Adler32

data class DexLimits(
    val maxFileBytes: Int = 64 * 1024 * 1024,
    val maxCodeUnitsPerMethod: Int = 1 shl 20,
    val maxHandlersPerList: Int = 65_536,
    val supportedVersions: Set<Int> = setOf(35, 37, 38, 39),
    val verifyChecksums: Boolean = true,
)

/**
 * Structural model of one DEX file (dossier section 11, "DEX structural reader").
 * Construction validates the header, every id table, class data and code items; instruction
 * decoding is a separate, per-method step (see [InstructionDecoder]).
 */
class DexFile private constructor(
    val bytes: ByteArray,
    val sha256: String,
    val header: DexHeader,
    val strings: List<String>,
    /** type_ids as string indexes. */
    val typeIds: IntArray,
    val protos: List<ProtoId>,
    val fieldIds: List<FieldId>,
    val methodIds: List<MethodId>,
    val classDefs: List<ClassDef>,
    val mapItems: List<MapItem>,
    val callSiteCount: Int,
    val methodHandleCount: Int,
    private val codeItems: Map<Int, CodeItem>,
    private val reader: DexBytes,
) {
    val version: Int get() = header.version

    fun string(idx: Int): String = strings[idx]
    fun type(idx: Int): String = strings[typeIds[idx]]

    fun protoDescriptor(idx: Int): String {
        val p = protos[idx]
        return p.parameters.joinToString("", "(", ")") { type(it) } + type(p.returnType)
    }

    fun fieldDescriptor(idx: Int): String {
        val f = fieldIds[idx]
        return "${type(f.classIdx)}->${string(f.nameIdx)}:${type(f.typeIdx)}"
    }

    fun methodDescriptor(idx: Int): String {
        val m = methodIds[idx]
        return "${type(m.classIdx)}->${string(m.nameIdx)}${protoDescriptor(m.protoIdx)}"
    }

    fun codeItem(offset: Int): CodeItem? = if (offset == 0) null else codeItems[offset]

    private val debugCache = HashMap<Int, DebugInfo>()
    private val annotationCache = HashMap<Int, ClassAnnotations>()

    /** Annotations of a class and its members; malformed annotation data fails like any other section. */
    fun annotations(c: ClassDef): ClassAnnotations = synchronized(annotationCache) {
        annotationCache.getOrPut(c.annotationsOff) { AnnotationReader.directory(reader, c.annotationsOff, this) }
    }

    fun debugInfo(offset: Int): DebugInfo = debugCache.getOrPut(offset) { parseDebugInfo(reader, offset, strings, typeIds) }

    fun methods(): List<DexMethod> = classDefs.flatMap { c ->
        val d = c.classData ?: return@flatMap emptyList()
        (d.directMethods + d.virtualMethods).map { DexMethod(this, c, it) }
    }

    fun classDef(descriptor: String): ClassDef? = classDefs.firstOrNull { type(it.classIdx) == descriptor }

    companion object {
        private const val HEADER_SIZE = 0x70
        private const val ENDIAN = 0x12345678L
        private const val REVERSE_ENDIAN = 0x78563412L

        fun parse(bytes: ByteArray, sha256: String = Hashing.sha256(bytes), limits: DexLimits = DexLimits()): Outcome<DexFile> =
            Outcome.of { Parser(bytes, sha256, limits).parse() }
    }

    private class Parser(val bytes: ByteArray, val sha: String, val limits: DexLimits) {
        val r = DexBytes(bytes, sha)

        fun parse(): DexFile {
            if (bytes.size > limits.maxFileBytes) r.fail(0, "DEX is ${bytes.size} bytes; limit is ${limits.maxFileBytes}")
            val header = parseHeader()
            val strings = parseStrings(header.stringIds)
            val types = parseTypes(header.typeIds, strings)
            val protos = parseProtos(header.protoIds, strings, types)
            val fields = parseFields(header.fieldIds, strings, types)
            val methods = parseMethods(header.methodIds, strings, types, protos.size)
            val map = parseMap(header)
            val callSites = map.firstOrNull { it.type == 0x0007 }?.size ?: 0
            val methodHandles = map.firstOrNull { it.type == 0x0008 }?.size ?: 0
            val codeItems = HashMap<Int, CodeItem>()
            val classDefs = parseClassDefs(header, strings, types, fields.size, methods.size, codeItems)
            return DexFile(bytes, sha, header, strings, types, protos, fields, methods, classDefs, map, callSites, methodHandles, codeItems, r)
        }

        private fun parseHeader(): DexHeader {
            r.section = "header"
            if (bytes.size < HEADER_SIZE) r.fail(0, "file is shorter than the 0x70-byte header")
            val magic = bytes.copyOfRange(0, 8)
            if (magic[0] != 'd'.code.toByte() || magic[1] != 'e'.code.toByte() || magic[2] != 'x'.code.toByte() ||
                magic[3] != '\n'.code.toByte() || magic[7] != 0.toByte()
            ) {
                r.fail(0, "bad DEX magic")
            }
            val digits = String(magic, 4, 3, Charsets.US_ASCII)
            if (!digits.all { it in '0'..'9' }) r.fail(4, "bad DEX version '$digits'")
            val version = digits.toInt()
            if (version !in limits.supportedVersions) {
                val known = version in setOf(35, 37, 38, 39, 40, 41)
                r.fail(4, "DEX version $digits is outside the supported profile ${limits.supportedVersions.sorted()}", unsupported = known)
            }
            val endian = r.u4(0x28)
            if (endian == REVERSE_ENDIAN) r.fail(0x28, "byte-swapped DEX files are not supported", unsupported = true)
            if (endian != ENDIAN) r.fail(0x28, "bad endian tag 0x${endian.toString(16)}")
            val fileSize = r.u4Index(0x20, "file_size")
            if (fileSize != bytes.size) r.fail(0x20, "file_size $fileSize does not match actual size ${bytes.size}")
            val headerSize = r.u4Index(0x24, "header_size")
            if (headerSize != HEADER_SIZE) r.fail(0x24, "header_size 0x${headerSize.toString(16)} is not 0x70")
            val checksum = r.u4(0x08)
            val signature = Hashing.hex(bytes.copyOfRange(0x0c, 0x20))
            if (limits.verifyChecksums) {
                val adler = Adler32().also { it.update(bytes, 12, fileSize - 12) }.value
                if (adler != checksum) r.fail(0x08, "adler32 checksum 0x${checksum.toString(16)} does not match content 0x${adler.toString(16)}")
                val sha1 = MessageDigest.getInstance("SHA-1").also { it.update(bytes, 32, fileSize - 32) }.digest()
                if (Hashing.hex(sha1) != signature) r.fail(0x0c, "SHA-1 signature does not match content")
            }
            fun section(off: Int, itemSize: Int, name: String, align: Boolean = true): Section {
                val size = r.u4Index(off, "$name size")
                val offset = r.u4Index(off + 4, "$name offset")
                if (size > 0) {
                    if (offset < HEADER_SIZE) r.fail(off + 4, "$name offset 0x${offset.toString(16)} points into the header")
                    if (align && offset % 4 != 0) r.fail(off + 4, "$name offset is not 4-byte aligned")
                    if (offset.toLong() + size.toLong() * itemSize > fileSize) r.fail(off + 4, "$name table runs past the end of the file")
                }
                return Section(size, offset)
            }
            val h = DexHeader(
                version = version,
                checksum = checksum,
                signature = signature,
                fileSize = fileSize,
                headerSize = headerSize,
                linkSize = r.u4Index(0x2c, "link_size"),
                linkOff = r.u4Index(0x30, "link_off"),
                mapOff = r.u4Index(0x34, "map_off"),
                stringIds = section(0x38, 4, "string_ids"),
                typeIds = section(0x40, 4, "type_ids"),
                protoIds = section(0x48, 12, "proto_ids"),
                fieldIds = section(0x50, 8, "field_ids"),
                methodIds = section(0x58, 8, "method_ids"),
                classDefs = section(0x60, 32, "class_defs"),
                data = section(0x68, 1, "data", align = false),
            )
            if (h.typeIds.size > 65_536) r.fail(0x40, "type_ids size ${h.typeIds.size} exceeds 65536")
            if (h.protoIds.size > 65_536) r.fail(0x48, "proto_ids size ${h.protoIds.size} exceeds 65536")
            if (h.linkSize != 0) r.fail(0x2c, "statically linked DEX files are not supported", unsupported = true)
            return h
        }

        private fun parseStrings(s: Section): List<String> {
            r.section = "string_ids"
            val out = ArrayList<String>(s.size)
            for (i in 0 until s.size) {
                val dataOff = r.u4Index(s.offset + i * 4, "string_data_off")
                r.section = "string_data"
                val c = r.Cursor(dataOff)
                val len = c.ulebIndex("utf16_size")
                out += Mutf8.decode(r, c.pos, len).first
                r.section = "string_ids"
                if (i > 0 && out[i - 1] >= out[i]) r.fail(s.offset + i * 4, "string_ids are not sorted and unique at index $i")
            }
            return out
        }

        private fun parseTypes(s: Section, strings: List<String>): IntArray {
            r.section = "type_ids"
            val out = IntArray(s.size)
            for (i in 0 until s.size) {
                val idx = r.u4Index(s.offset + i * 4, "descriptor_idx")
                if (idx >= strings.size) r.fail(s.offset + i * 4, "type $i descriptor index $idx out of range")
                if (!Descriptors.isValidType(strings[idx])) r.fail(s.offset + i * 4, "type $i has invalid descriptor '${strings[idx]}'")
                if (i > 0 && out[i - 1] >= idx) r.fail(s.offset + i * 4, "type_ids are not sorted and unique at index $i")
                out[i] = idx
            }
            return out
        }

        private fun typeList(off: Int, typeCount: Int): IntArray {
            if (off == 0) return IntArray(0)
            val saved = r.section
            r.section = "type_list"
            if (off % 4 != 0) r.fail(off, "type_list is not 4-byte aligned")
            val n = r.u4Index(off, "type_list size")
            r.need(off + 4, n * 2)
            val out = IntArray(n) { i ->
                val t = r.u2(off + 4 + i * 2)
                if (t >= typeCount) r.fail(off + 4 + i * 2, "type index $t out of range")
                t
            }
            r.section = saved
            return out
        }

        private fun parseProtos(s: Section, strings: List<String>, types: IntArray): List<ProtoId> {
            r.section = "proto_ids"
            val out = ArrayList<ProtoId>(s.size)
            for (i in 0 until s.size) {
                val p = s.offset + i * 12
                val shortyIdx = r.u4Index(p, "shorty_idx")
                val ret = r.u4Index(p + 4, "return_type_idx")
                val params = r.u4Index(p + 8, "parameters_off")
                if (shortyIdx >= strings.size) r.fail(p, "shorty index out of range")
                if (ret >= types.size) r.fail(p + 4, "return type index out of range")
                val proto = ProtoId(strings[shortyIdx], ret, typeList(params, types.size))
                val expectedShorty = (listOf(ret) + proto.parameters.toList()).map { Descriptors.shorty(strings[types[it]]) }.joinToString("")
                if (proto.shorty != expectedShorty) r.fail(p, "shorty '${proto.shorty}' does not match signature '$expectedShorty'")
                if (proto.parameters.any { strings[types[it]] == "V" }) r.fail(p, "void parameter type")
                if (i > 0) {
                    val prev = out[i - 1]
                    val cmp = compareValues(prev.returnType, ret).takeIf { it != 0 } ?: compareTypeLists(prev.parameters, proto.parameters)
                    if (cmp >= 0) r.fail(p, "proto_ids are not sorted and unique at index $i")
                }
                out += proto
            }
            return out
        }

        private fun compareTypeLists(a: IntArray, b: IntArray): Int {
            for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return a.size.compareTo(b.size)
        }

        private fun parseFields(s: Section, strings: List<String>, types: IntArray): List<FieldId> {
            r.section = "field_ids"
            val out = ArrayList<FieldId>(s.size)
            for (i in 0 until s.size) {
                val p = s.offset + i * 8
                val f = FieldId(r.u2(p), r.u2(p + 2), r.u4Index(p + 4, "name_idx"))
                if (f.classIdx >= types.size || f.typeIdx >= types.size || f.nameIdx >= strings.size) r.fail(p, "field $i has an index out of range")
                if (strings[types[f.typeIdx]] == "V") r.fail(p, "field $i has void type")
                if (i > 0) {
                    val q = out[i - 1]
                    val cmp = compareValuesBy(q, f, { it.classIdx }, { it.nameIdx }, { it.typeIdx })
                    if (cmp >= 0) r.fail(p, "field_ids are not sorted and unique at index $i")
                }
                out += f
            }
            return out
        }

        private fun parseMethods(s: Section, strings: List<String>, types: IntArray, protoCount: Int): List<MethodId> {
            r.section = "method_ids"
            val out = ArrayList<MethodId>(s.size)
            for (i in 0 until s.size) {
                val p = s.offset + i * 8
                val m = MethodId(r.u2(p), r.u2(p + 2), r.u4Index(p + 4, "name_idx"))
                if (m.classIdx >= types.size || m.protoIdx >= protoCount || m.nameIdx >= strings.size) r.fail(p, "method $i has an index out of range")
                if (i > 0) {
                    val q = out[i - 1]
                    val cmp = compareValuesBy(q, m, { it.classIdx }, { it.nameIdx }, { it.protoIdx })
                    if (cmp >= 0) r.fail(p, "method_ids are not sorted and unique at index $i")
                }
                out += m
            }
            return out
        }

        private fun parseMap(h: DexHeader): List<MapItem> {
            r.section = "map_list"
            if (h.mapOff == 0) r.fail(0x34, "map_off is zero")
            if (h.mapOff % 4 != 0) r.fail(h.mapOff, "map_list is not 4-byte aligned")
            val n = r.u4Index(h.mapOff, "map size")
            r.need(h.mapOff + 4, n * 12)
            val items = (0 until n).map { i ->
                val p = h.mapOff + 4 + i * 12
                MapItem(r.u2(p), r.u4Index(p + 4, "map item size"), r.u4Index(p + 8, "map item offset"))
            }
            if (items.map { it.type }.toSet().size != items.size) r.fail(h.mapOff, "map_list repeats an item type")
            for (i in 1 until items.size) if (items[i].offset <= items[i - 1].offset) r.fail(h.mapOff, "map_list is not in offset order")
            fun expect(type: Int, s: Section, name: String) {
                val item = items.firstOrNull { it.type == type }
                if (s.size == 0 && item == null) return
                if (item == null || item.size != s.size || item.offset != s.offset) r.fail(h.mapOff, "map_list disagrees with header for $name")
            }
            expect(0x0001, h.stringIds, "string_ids")
            expect(0x0002, h.typeIds, "type_ids")
            expect(0x0003, h.protoIds, "proto_ids")
            expect(0x0004, h.fieldIds, "field_ids")
            expect(0x0005, h.methodIds, "method_ids")
            expect(0x0006, h.classDefs, "class_defs")
            items.firstOrNull { it.type == 0x1000 }?.let { if (it.offset != h.mapOff) r.fail(h.mapOff, "map_list item does not point at itself") }
                ?: r.fail(h.mapOff, "map_list does not list itself")
            return items
        }

        private fun parseClassDefs(
            h: DexHeader, strings: List<String>, types: IntArray, fieldCount: Int, methodCount: Int, codeItems: MutableMap<Int, CodeItem>,
        ): List<ClassDef> {
            val out = ArrayList<ClassDef>(h.classDefs.size)
            val seen = HashSet<Int>()
            for (i in 0 until h.classDefs.size) {
                r.section = "class_defs"
                val p = h.classDefs.offset + i * 32
                val classIdx = r.u4Index(p, "class_idx")
                val access = r.s4(p + 4)
                val superIdx = r.s4(p + 8)
                val interfacesOff = r.u4Index(p + 12, "interfaces_off")
                val sourceIdx = r.s4(p + 16)
                val annotationsOff = r.u4Index(p + 20, "annotations_off")
                val classDataOff = r.u4Index(p + 24, "class_data_off")
                val staticValuesOff = r.u4Index(p + 28, "static_values_off")
                if (classIdx >= types.size) r.fail(p, "class_idx out of range")
                if (!seen.add(classIdx)) r.fail(p, "class ${strings[types[classIdx]]} is defined twice")
                if (!strings[types[classIdx]].startsWith("L")) r.fail(p, "class_def names a non-class type")
                if (superIdx != NO_INDEX && (superIdx < 0 || superIdx >= types.size)) r.fail(p + 8, "superclass index out of range")
                if (sourceIdx != NO_INDEX && (sourceIdx < 0 || sourceIdx >= strings.size)) r.fail(p + 16, "source_file index out of range")
                val interfaces = typeList(interfacesOff, types.size)
                val data = if (classDataOff == 0) null else parseClassData(classDataOff, fieldCount, methodCount, codeItems)
                out += ClassDef(classIdx, access, superIdx, interfaces, sourceIdx, annotationsOff, classDataOff, staticValuesOff, data, p)
            }
            return out
        }

        private fun parseClassData(off: Int, fieldCount: Int, methodCount: Int, codeItems: MutableMap<Int, CodeItem>): ClassData {
            r.section = "class_data"
            val c = r.Cursor(off)
            val sizes = IntArray(4) { c.ulebIndex("class_data count") }
            if (sizes.sumOf { it.toLong() } * 2 > r.limit - c.pos) r.fail(off, "class_data counts exceed the remaining file")
            fun fields(n: Int): List<EncodedField> {
                var idx = 0
                return List(n) { i ->
                    val start = c.pos
                    val diff = c.ulebIndex("field_idx_diff")
                    if (i > 0 && diff == 0) r.fail(start, "field indexes are not strictly increasing")
                    idx += diff
                    if (idx >= fieldCount || idx < 0) r.fail(start, "field index $idx out of range")
                    EncodedField(idx, c.uleb128())
                }
            }
            fun methods(n: Int): List<EncodedMethod> {
                var idx = 0
                return List(n) { i ->
                    val start = c.pos
                    val diff = c.ulebIndex("method_idx_diff")
                    if (i > 0 && diff == 0) r.fail(start, "method indexes are not strictly increasing")
                    idx += diff
                    if (idx >= methodCount || idx < 0) r.fail(start, "method index $idx out of range")
                    val flags = c.uleb128()
                    val codeOff = c.ulebIndex("code_off")
                    val abstractOrNative = flags and (AccessFlags.ABSTRACT or AccessFlags.NATIVE) != 0
                    if (abstractOrNative && codeOff != 0) r.fail(start, "abstract or native method has code")
                    if (codeOff != 0 && !codeItems.containsKey(codeOff)) {
                        val saved = r.section
                        codeItems[codeOff] = parseCodeItem(codeOff)
                        r.section = saved
                    }
                    EncodedMethod(idx, flags, codeOff)
                }
            }
            return ClassData(fields(sizes[0]), fields(sizes[1]), methods(sizes[2]), methods(sizes[3]))
        }

        private fun parseCodeItem(off: Int): CodeItem {
            r.section = "code_item"
            if (off % 4 != 0) r.fail(off, "code_item is not 4-byte aligned")
            val registers = r.u2(off)
            val ins = r.u2(off + 2)
            val outs = r.u2(off + 4)
            val triesSize = r.u2(off + 6)
            val debugOff = r.u4Index(off + 8, "debug_info_off")
            val insnsSize = r.u4Index(off + 12, "insns_size")
            if (ins > registers) r.fail(off, "ins_size $ins exceeds registers_size $registers")
            if (insnsSize > limits.maxCodeUnitsPerMethod) r.fail(off + 12, "method has $insnsSize code units; limit is ${limits.maxCodeUnitsPerMethod}")
            val insnsOff = off + 16
            r.need(insnsOff, insnsSize * 2)
            val insns = IntArray(insnsSize) { i -> r.u2(insnsOff + i * 2) }
            var p = insnsOff + insnsSize * 2
            val tries = ArrayList<TryItem>(triesSize)
            val handlers = HashMap<Int, CatchHandler>()
            if (triesSize > 0) {
                if (insnsSize % 2 == 1) p += 2
                r.need(p, triesSize * 8)
                for (i in 0 until triesSize) {
                    val t = TryItem(r.u4Index(p, "start_addr"), r.u2(p + 4), r.u2(p + 6))
                    if (t.startAddr.toLong() + t.insnCount > insnsSize) r.fail(p, "try block runs past the end of the code")
                    if (tries.isNotEmpty() && tries.last().let { it.startAddr + it.insnCount } > t.startAddr) r.fail(p, "try blocks overlap or are unsorted")
                    tries += t
                    p += 8
                }
                val listOff = p
                val c = r.Cursor(p)
                val count = c.ulebIndex("handler list size")
                if (count > limits.maxHandlersPerList || count.toLong() > r.limit - c.pos) r.fail(listOff, "handler list size $count is not plausible")
                repeat(count) {
                    val rel = c.pos - listOff
                    val size = c.sleb128()
                    val n = kotlin.math.abs(size.toLong())
                    if (n > limits.maxHandlersPerList || n * 2 > r.limit - c.pos) r.fail(c.pos, "handler size $size is not plausible")
                    val typed = List(n.toInt()) {
                        val type = c.ulebIndex("handler type")
                        val addr = c.ulebIndex("handler address")
                        if (addr >= insnsSize) r.fail(c.pos, "handler address $addr is outside the code")
                        type to addr
                    }
                    val catchAll = if (size <= 0) c.ulebIndex("catch-all address") else -1
                    if (catchAll >= insnsSize) r.fail(c.pos, "catch-all address $catchAll is outside the code")
                    handlers[rel] = CatchHandler(typed, catchAll)
                }
                for (t in tries) if (!handlers.containsKey(t.handlerOff)) r.fail(listOff, "try handler_off ${t.handlerOff} does not start a handler")
            }
            return CodeItem(off, registers, ins, outs, debugOff, insns, insnsOff, tries, handlers)
        }
    }
}

/** Parses a debug_info_item; malformed debug data fails like any other section. */
internal fun parseDebugInfo(r: DexBytes, off: Int, strings: List<String>, typeIds: IntArray): DebugInfo {
    val saved = r.section
    r.section = "debug_info"
    val c = r.Cursor(off)
    val lineStart = c.uleb128()
    val paramCount = c.ulebIndex("parameters_size")
    if (paramCount > 255) r.fail(off, "debug info declares $paramCount parameters")
    fun str(idx: Int): String? = when {
        idx == -1 -> null
        idx < 0 || idx >= strings.size -> r.fail(c.pos, "debug string index $idx out of range")
        else -> strings[idx]
    }
    val names = List(paramCount) { str(c.uleb128p1()) }
    val positions = ArrayList<DebugPosition>()
    val locals = ArrayList<DebugLocal>()
    val open = HashMap<Int, DebugLocal>()
    var address = 0
    var line = lineStart
    loop@ while (true) {
        when (val op = c.u1()) {
            0x00 -> break@loop
            0x01 -> address += c.ulebIndex("advance_pc")
            0x02 -> line += c.sleb128()
            0x03, 0x04 -> {
                val reg = c.ulebIndex("register")
                val name = str(c.uleb128p1())
                val typeIdx = c.uleb128p1()
                if (typeIdx >= typeIds.size) r.fail(c.pos, "debug type index $typeIdx out of range")
                if (op == 0x04) str(c.uleb128p1())
                open.remove(reg)?.let { locals += it.copy(endAddress = address) }
                open[reg] = DebugLocal(reg, name, typeIdx.takeIf { it >= 0 }?.let { strings[typeIds[it]] }, address, -1)
            }
            0x05 -> c.ulebIndex("register").let { reg -> open.remove(reg)?.let { locals += it.copy(endAddress = address) } }
            0x06 -> c.ulebIndex("register")
            0x07, 0x08 -> Unit
            0x09 -> str(c.uleb128p1())
            else -> {
                val adjusted = op - 0x0a
                line += -4 + adjusted % 15
                address += adjusted / 15
                positions += DebugPosition(address, line)
            }
        }
    }
    locals += open.values.map { it.copy(endAddress = Int.MAX_VALUE) }
    r.section = saved
    return DebugInfo(lineStart, names, positions, locals.sortedBy { it.startAddress })
}
