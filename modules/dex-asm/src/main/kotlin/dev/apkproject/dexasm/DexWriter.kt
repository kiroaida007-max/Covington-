package dev.apkproject.dexasm

import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.Descriptors
import dev.apkproject.dex.Format
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.Mutf8
import dev.apkproject.dex.OpcodeInfo
import dev.apkproject.dex.Opcodes
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/** Assembles `.dasm` text into a DEX file. Test-kit only: never part of the recovery path. */
object Assembler {
    fun assemble(text: String): ByteArray = DexWriter(DasmParser.parse(text)).write()
}

internal data class ProtoKey(val params: List<String>, val ret: String) {
    val shorty: String get() = (listOf(ret) + params).map { Descriptors.shorty(it) }.joinToString("")
}

internal data class FieldKey(val cls: String, val name: String, val type: String)

internal data class MethodRefKey(val cls: String, val name: String, val proto: ProtoKey)

internal fun splitDescriptors(s: String, line: Int): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < s.length) {
        val start = i
        while (i < s.length && s[i] == '[') i++
        if (i >= s.length) throw AsmException(line, "truncated descriptor in '$s'")
        if (s[i] == 'L') {
            val end = s.indexOf(';', i)
            if (end < 0) throw AsmException(line, "unterminated class descriptor in '$s'")
            i = end + 1
        } else {
            i++
        }
        val d = s.substring(start, i)
        if (!Descriptors.isValidType(d)) throw AsmException(line, "invalid descriptor '$d'")
        out += d
    }
    return out
}

internal fun parseProto(text: String, line: Int): ProtoKey {
    if (!text.startsWith("(")) throw AsmException(line, "prototype must start with '(': $text")
    val close = text.indexOf(')')
    if (close < 0) throw AsmException(line, "prototype is missing ')': $text")
    val params = splitDescriptors(text.substring(1, close), line)
    val ret = splitDescriptors(text.substring(close + 1), line)
    if (ret.size != 1) throw AsmException(line, "prototype needs exactly one return type: $text")
    if (params.any { it == "V" }) throw AsmException(line, "void parameter in $text")
    return ProtoKey(params, ret[0])
}

internal fun parseMethodRef(text: String, line: Int): MethodRefKey {
    val arrow = text.indexOf("->")
    val paren = text.indexOf('(', arrow)
    if (arrow <= 0 || paren < 0) throw AsmException(line, "method reference must be Lcls;->name(params)ret: $text")
    return MethodRefKey(text.substring(0, arrow), text.substring(arrow + 2, paren), parseProto(text.substring(paren), line))
}

internal fun parseFieldRef(text: String, line: Int): FieldKey {
    val arrow = text.indexOf("->")
    val colon = text.indexOf(':', arrow)
    if (arrow <= 0 || colon < 0) throw AsmException(line, "field reference must be Lcls;->name:Type: $text")
    return FieldKey(text.substring(0, arrow), text.substring(arrow + 2, colon), text.substring(colon + 1))
}

internal class DexWriter(private val file: AsmFile) {
    private val strings = sortedSetOf<String>()
    private val types = sortedSetOf<String>()
    private val protos = HashSet<ProtoKey>()
    private val fields = HashSet<FieldKey>()
    private val methods = HashSet<MethodRefKey>()

    private lateinit var stringIdx: Map<String, Int>
    private lateinit var typeIdx: Map<String, Int>
    private lateinit var protoList: List<ProtoKey>
    private lateinit var protoIdx: Map<ProtoKey, Int>
    private lateinit var fieldList: List<FieldKey>
    private lateinit var fieldIdx: Map<FieldKey, Int>
    private lateinit var methodList: List<MethodRefKey>
    private lateinit var methodIdx: Map<MethodRefKey, Int>

    private fun type(t: String, line: Int) {
        if (!Descriptors.isValidType(t)) throw AsmException(line, "invalid type descriptor '$t'")
        types += t; strings += t
    }

    private fun proto(p: ProtoKey, line: Int) {
        protos += p; strings += p.shorty
        type(p.ret, line); p.params.forEach { type(it, line) }
    }

    private fun field(f: FieldKey, line: Int) {
        fields += f; type(f.cls, line); type(f.type, line); strings += f.name
    }

    private fun method(m: MethodRefKey, line: Int) {
        methods += m; type(m.cls, line); strings += m.name; proto(m.proto, line)
    }

    fun write(): ByteArray {
        collect()
        index()
        return layout()
    }

    // ---------------------------------------------------------------- pools

    private fun collect() {
        if (file.version !in setOf(35, 37, 38, 39)) throw AsmException(1, "unsupported .dex version ${file.version}")
        for (c in file.classes) {
            type(c.name, c.line)
            c.superclass?.let { type(it, c.line) }
            c.interfaces.forEach { type(it, c.line) }
            c.sourceFile?.let { strings += it }
            for (f in c.fields) field(FieldKey(c.name, f.name, f.type), f.line)
            for (m in c.methods) {
                method(MethodRefKey(c.name, m.name, parseProto(m.proto, m.line)), m.line)
                m.params.values.forEach { strings += it }
                for (catch in m.catches) catch.type?.let { type(it, catch.line) }
                for (item in m.items) if (item is AsmInsn) collectInsn(item)
            }
        }
    }

    private fun collectInsn(i: AsmInsn) {
        val op = Opcodes.byMnemonic(i.mnemonic)!!
        if (op.minVersion > file.version) throw AsmException(i.line, "${op.mnemonic} needs .dex ${op.minVersion}")
        for (o in i.operands) {
            when (o) {
                is Operand.Str -> strings += o.value
                is Operand.Proto -> proto(parseProto(o.text, i.line), i.line)
                is Operand.Ref -> when (op.index) {
                    IndexKind.TYPE -> type(o.text, i.line)
                    IndexKind.FIELD -> field(parseFieldRef(o.text, i.line), i.line)
                    IndexKind.METHOD, IndexKind.METHOD_AND_PROTO -> method(parseMethodRef(o.text, i.line), i.line)
                    else -> throw AsmException(i.line, "${op.mnemonic} does not take a reference operand")
                }
                is Operand.Indexed -> throw AsmException(i.line, "${o.kind} operands are not supported by the assembler")
                else -> Unit
            }
        }
    }

    private fun index() {
        stringIdx = strings.withIndex().associate { it.value to it.index }
        typeIdx = types.withIndex().associate { it.value to it.index }
        val t = typeIdx
        protoList = protos.sortedWith { a, b ->
            val r = t.getValue(a.ret).compareTo(t.getValue(b.ret))
            if (r != 0) r else compareLists(a.params.map { t.getValue(it) }, b.params.map { t.getValue(it) })
        }
        protoIdx = protoList.withIndex().associate { it.value to it.index }
        val s = stringIdx
        fieldList = fields.sortedWith(compareBy<FieldKey>({ t.getValue(it.cls) }, { s.getValue(it.name) }, { t.getValue(it.type) }))
        fieldIdx = fieldList.withIndex().associate { it.value to it.index }
        methodList = methods.sortedWith(compareBy<MethodRefKey>({ t.getValue(it.cls) }, { s.getValue(it.name) }, { protoIdx.getValue(it.proto) }))
        methodIdx = methodList.withIndex().associate { it.value to it.index }
        if (types.size > 65_536 || protoList.size > 65_536) throw AsmException(1, "too many types or prototypes for 16-bit indexes")
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }

    // ---------------------------------------------------------------- code

    private class EncodedCode(
        val registers: Int, val ins: Int, val outs: Int, val insns: IntArray,
        val tries: List<Triple<Int, Int, Int>>, val handlerBytes: ByteArray, val debug: ByteArray?,
    ) {
        val size: Int get() {
            var n = 16 + insns.size * 2
            if (tries.isNotEmpty()) {
                if (insns.size % 2 == 1) n += 2
                n += tries.size * 8 + handlerBytes.size
            }
            return n
        }
    }

    private sealed class Placed(val address: Int, val units: Int) {
        class I(address: Int, units: Int, val insn: AsmInsn, val op: OpcodeInfo) : Placed(address, units)
        class P(address: Int, units: Int, val item: AsmItem) : Placed(address, units)
        class Pad(address: Int) : Placed(address, 1)
    }

    private fun encodeMethod(c: AsmClass, m: AsmMethod): EncodedCode {
        val proto = parseProto(m.proto, m.line)
        val isStatic = m.flags and AccessFlags.STATIC != 0
        val defaultIns = proto.params.sumOf { Descriptors.registerWidth(it) } + if (isStatic) 0 else 1
        val registers = m.registers ?: throw AsmException(m.line, "${m.name}: .registers is required for methods with code")
        val ins = m.ins ?: defaultIns
        if (ins != defaultIns) throw AsmException(m.line, "${m.name}: .ins $ins does not match the prototype ($defaultIns)")
        if (ins > registers) throw AsmException(m.line, "${m.name}: .ins exceeds .registers")

        // Pass 1: addresses and labels.
        val placed = ArrayList<Placed>()
        val labels = HashMap<String, Int>()
        val lines = ArrayList<Pair<Int, Int>>()
        val pendingLabels = ArrayList<AsmLabel>()
        var address = 0
        fun bind(at: Int) {
            for (l in pendingLabels) if (labels.put(l.name, at) != null) throw AsmException(l.line, "duplicate label :${l.name}")
            pendingLabels.clear()
        }
        for (item in m.items) {
            when (item) {
                is AsmLabel -> pendingLabels += item
                is AsmLine -> lines += address to item.number
                is AsmInsn -> {
                    val op = Opcodes.byMnemonic(item.mnemonic)!!
                    bind(address)
                    checkDeclared(item.declaredAddress, address, item.line)
                    placed += Placed.I(address, op.units, item, op)
                    address += op.units
                }
                is AsmPackedSwitch, is AsmSparseSwitch, is AsmArrayData -> {
                    if (address % 2 != 0) {
                        placed += Placed.Pad(address); address += 1
                    }
                    bind(address)
                    val declared = when (item) {
                        is AsmPackedSwitch -> item.declaredAddress
                        is AsmSparseSwitch -> item.declaredAddress
                        is AsmArrayData -> item.declaredAddress
                        else -> null
                    }
                    checkDeclared(declared, address, item.line)
                    val units = payloadUnits(item)
                    placed += Placed.P(address, units, item)
                    address += units
                }
            }
        }
        bind(address)
        val codeSize = address
        fun label(name: String, line: Int): Int = labels[name] ?: throw AsmException(line, "undefined label :$name")

        // Which switch owns each payload (payload targets are relative to the switch).
        val owner = HashMap<Int, Int>()
        for (p in placed) if (p is Placed.I && p.op.payloadRef) {
            val target = (p.insn.operands.getOrNull(1) as? Operand.Label)?.let { label(it.name, p.insn.line) }
                ?: throw AsmException(p.insn.line, "${p.op.mnemonic} needs a payload label")
            owner.putIfAbsent(target, p.address)
        }

        // Pass 2: encoding.
        val units = IntArray(codeSize)
        var maxOuts = 0
        for (p in placed) {
            when (p) {
                is Placed.Pad -> units[p.address] = 0
                is Placed.I -> {
                    val enc = encodeInsn(p.op, p.insn, p.address, ::label, registers)
                    if (p.op.mnemonic.startsWith("invoke-")) maxOuts = maxOf(maxOuts, invokeArgCount(p.insn))
                    System.arraycopy(enc, 0, units, p.address, enc.size)
                }
                is Placed.P -> {
                    val base = owner[p.address]
                    val enc = encodePayload(p.item, base, ::label)
                    if (enc.size != p.units) throw AsmException(p.item.line, "internal payload size mismatch")
                    System.arraycopy(enc, 0, units, p.address, enc.size)
                }
            }
        }
        val outs = m.outs ?: maxOuts
        if (maxOuts > outs) throw AsmException(m.line, "${m.name}: invokes pass $maxOuts registers but .outs is $outs")

        val (tries, handlers) = encodeTries(m, ::label, codeSize)
        val debug = encodeDebug(m, proto, isStatic, registers, ins, lines)
        return EncodedCode(registers, ins, outs, units, tries, handlers, debug)
    }

    private fun checkDeclared(declared: Int?, actual: Int, line: Int) {
        if (declared != null && declared != actual) {
            throw AsmException(line, "declared address %04x but instruction is at %04x".format(declared, actual))
        }
    }

    private fun payloadUnits(item: AsmItem): Int = when (item) {
        is AsmPackedSwitch -> item.targets.size * 2 + 4
        is AsmSparseSwitch -> item.cases.size * 4 + 2
        is AsmArrayData -> ((item.values.size.toLong() * item.width + 1) / 2 + 4).toInt()
        else -> error("not a payload")
    }

    private fun invokeArgCount(i: AsmInsn): Int = when (val o = i.operands.firstOrNull()) {
        is Operand.RegList -> o.regs.size
        is Operand.RegRange -> o.last - o.first + 1
        else -> 0
    }

    private fun encodePayload(item: AsmItem, base: Int?, label: (String, Int) -> Int): IntArray {
        fun rel(o: Operand, line: Int): Int = when (o) {
            is Operand.Relative -> o.offset
            is Operand.Label -> {
                if (base == null) throw AsmException(line, "switch payload has no referencing switch; use +offset targets")
                label(o.name, line) - base
            }
            else -> throw AsmException(line, "bad switch target")
        }
        val out = ArrayList<Int>()
        fun i32(v: Int) {
            out += v and 0xffff; out += (v ushr 16) and 0xffff
        }
        when (item) {
            is AsmPackedSwitch -> {
                out += Opcodes.PACKED_SWITCH_PAYLOAD; out += item.targets.size
                i32(item.firstKey)
                item.targets.forEach { i32(rel(it, item.line)) }
            }
            is AsmSparseSwitch -> {
                val keys = item.cases.map { it.first }
                if (keys.zipWithNext().any { (a, b) -> a >= b }) throw AsmException(item.line, "sparse-switch keys must be strictly ascending")
                out += Opcodes.SPARSE_SWITCH_PAYLOAD; out += item.cases.size
                keys.forEach { i32(it) }
                item.cases.forEach { i32(rel(it.second, item.line)) }
            }
            is AsmArrayData -> {
                out += Opcodes.FILL_ARRAY_DATA_PAYLOAD; out += item.width
                i32(item.values.size)
                val bytes = ByteArray(item.values.size * item.width)
                item.values.forEachIndexed { e, v ->
                    val bits = 8 * item.width
                    if (bits < 64) {
                        val min = -(1L shl (bits - 1))
                        val maxUnsigned = (1L shl bits) - 1
                        if (v < min || v > maxUnsigned) throw AsmException(item.line, "array value $v does not fit ${item.width} bytes")
                    }
                    for (b in 0 until item.width) bytes[e * item.width + b] = (v ushr (8 * b)).toByte()
                }
                var k = 0
                while (k < bytes.size) {
                    val lo = bytes[k].toInt() and 0xff
                    val hi = if (k + 1 < bytes.size) bytes[k + 1].toInt() and 0xff else 0
                    out += lo or (hi shl 8)
                    k += 2
                }
            }
            else -> error("not a payload")
        }
        return out.toIntArray()
    }

    private fun encodeInsn(op: OpcodeInfo, i: AsmInsn, address: Int, label: (String, Int) -> Int, registers: Int): IntArray {
        val line = i.line
        val ops = i.operands
        fun err(msg: String): Nothing = throw AsmException(line, "${op.mnemonic}: $msg")
        fun reg(k: Int, max: Int): Int {
            val o = ops.getOrNull(k) as? Operand.Reg ?: err("operand ${k + 1} must be a register")
            if (o.n > max) err("register v${o.n} does not fit the ${op.format.id} format (max v$max)")
            if (o.n >= registers) err("register v${o.n} is outside .registers $registers")
            return o.n
        }
        fun lit(k: Int): Operand.Lit = ops.getOrNull(k) as? Operand.Lit ?: err("operand ${k + 1} must be a #literal")
        fun signed(k: Int, bits: Int): Int {
            val v = lit(k).value
            val min = -(1L shl (bits - 1))
            val max = (1L shl (bits - 1)) - 1
            if (v < min || v > max) err("literal $v does not fit $bits signed bits")
            return v.toInt()
        }
        fun branch(k: Int, bits: Int): Int {
            val o = ops.getOrNull(k) as? Operand.Label ?: err("operand ${k + 1} must be a :label")
            val off = label(o.name, line) - address
            val min = -(1L shl (bits - 1))
            val max = (1L shl (bits - 1)) - 1
            if (off < min || off > max) err("branch offset $off does not fit $bits bits")
            if (off == 0 && op.format != Format.F30t) err("branch offset must not be zero")
            return off
        }
        fun index(k: Int): Int {
            val o = ops.getOrNull(k) ?: err("missing operand ${k + 1}")
            return when (op.index) {
                IndexKind.STRING -> stringIdx.getValue((o as? Operand.Str ?: err("expected a string")).value)
                IndexKind.TYPE -> typeIdx.getValue((o as? Operand.Ref ?: err("expected a type")).text)
                IndexKind.FIELD -> fieldIdx.getValue(parseFieldRef((o as? Operand.Ref ?: err("expected a field")).text, line))
                IndexKind.METHOD, IndexKind.METHOD_AND_PROTO -> methodIdx.getValue(parseMethodRef((o as? Operand.Ref ?: err("expected a method")).text, line))
                IndexKind.PROTO -> protoIdx.getValue(parseProto((o as? Operand.Proto ?: err("expected a prototype")).text, line))
                else -> err("index kind ${op.index} is not supported by the assembler")
            }
        }
        fun count(n: Int) {
            if (ops.size != n) err("expected $n operands, got ${ops.size}")
        }
        fun u(vararg v: Int) = v
        val o = op.opcode
        return when (op.format) {
            Format.F10x -> { count(0); u(o) }
            Format.F12x -> { count(2); u(o or (reg(0, 15) shl 8) or (reg(1, 15) shl 12)) }
            Format.F11n -> { count(2); u(o or (reg(0, 15) shl 8) or ((signed(1, 4) and 0xf) shl 12)) }
            Format.F11x -> { count(1); u(o or (reg(0, 255) shl 8)) }
            Format.F10t -> { count(1); u(o or ((branch(0, 8) and 0xff) shl 8)) }
            Format.F20t -> { count(1); u(o, branch(0, 16) and 0xffff) }
            Format.F22x -> { count(2); u(o or (reg(0, 255) shl 8), reg(1, 65535)) }
            Format.F21t -> { count(2); u(o or (reg(0, 255) shl 8), branch(1, 16) and 0xffff) }
            Format.F21s -> { count(2); u(o or (reg(0, 255) shl 8), signed(1, 16) and 0xffff) }
            Format.F21h -> {
                count(2)
                val v = lit(1).value
                val high = if (op.wide == "W") {
                    if (v and 0xffffffffffffL != 0L) err("const-wide/high16 literal must have its low 48 bits clear")
                    (v ushr 48).toInt()
                } else {
                    if (v < Int.MIN_VALUE || v > 0xffffffffL || (v and 0xffff) != 0L) err("const/high16 literal must be a 32-bit value with its low 16 bits clear")
                    (v.toInt() ushr 16)
                }
                u(o or (reg(0, 255) shl 8), high and 0xffff)
            }
            Format.F21c -> { count(2); u(o or (reg(0, 255) shl 8), index(1).also { if (it > 0xffff) err("index $it needs the jumbo form") }) }
            Format.F23x -> { count(3); u(o or (reg(0, 255) shl 8), reg(1, 255) or (reg(2, 255) shl 8)) }
            Format.F22b -> { count(3); u(o or (reg(0, 255) shl 8), reg(1, 255) or ((signed(2, 8) and 0xff) shl 8)) }
            Format.F22t -> { count(3); u(o or (reg(0, 15) shl 8) or (reg(1, 15) shl 12), branch(2, 16) and 0xffff) }
            Format.F22s -> { count(3); u(o or (reg(0, 15) shl 8) or (reg(1, 15) shl 12), signed(2, 16) and 0xffff) }
            Format.F22c -> { count(3); u(o or (reg(0, 15) shl 8) or (reg(1, 15) shl 12), index(2)) }
            Format.F32x -> { count(2); u(o, reg(0, 65535), reg(1, 65535)) }
            Format.F30t -> { count(1); val off = branch(0, 32); u(o, off and 0xffff, off ushr 16) }
            Format.F31t -> {
                count(2)
                val off = branch(1, 32)
                u(o or (reg(0, 255) shl 8), off and 0xffff, off ushr 16)
            }
            Format.F31i -> {
                count(2)
                val v = lit(1).value
                if (v < Int.MIN_VALUE || v > 0xffffffffL || (op.wide == "W" && v > Int.MAX_VALUE)) err("literal $v does not fit 32 bits")
                val bits = v.toInt()
                u(o or (reg(0, 255) shl 8), bits and 0xffff, bits ushr 16)
            }
            Format.F31c -> { count(2); val idx = index(1); u(o or (reg(0, 255) shl 8), idx and 0xffff, idx ushr 16) }
            Format.F35c, Format.F45cc -> {
                count(if (op.format == Format.F35c) 2 else 3)
                val list = ops[0] as? Operand.RegList ?: err("expected a register list {vC, ...}")
                if (list.regs.size > 5) err("at most 5 registers; use the /range form")
                list.regs.forEach { if (it > 15) err("register v$it does not fit 4 bits; use the /range form"); if (it >= registers) err("register v$it is outside .registers") }
                val r = list.regs + List(5 - list.regs.size) { 0 }
                val first = o or (r[4] shl 8) or (list.regs.size shl 12)
                val words = mutableListOf(first, index(1), r[0] or (r[1] shl 4) or (r[2] shl 8) or (r[3] shl 12))
                if (op.format == Format.F45cc) words += protoIdx.getValue(parseProto((ops[2] as? Operand.Proto ?: err("expected a prototype")).text, line))
                words.toIntArray()
            }
            Format.F3rc, Format.F4rcc -> {
                count(if (op.format == Format.F3rc) 2 else 3)
                val (firstReg, n) = when (val l = ops[0]) {
                    is Operand.RegRange -> l.first to (l.last - l.first + 1)
                    is Operand.RegList -> if (l.regs.isEmpty()) 0 to 0 else err("range form needs {vN .. vM}")
                    else -> err("expected a register range")
                }
                if (n !in 0..255) err("range of $n registers is invalid")
                if (firstReg + n > registers) err("range exceeds .registers")
                val words = mutableListOf(o or (n shl 8), index(1), firstReg)
                if (op.format == Format.F4rcc) words += protoIdx.getValue(parseProto((ops[2] as? Operand.Proto ?: err("expected a prototype")).text, line))
                words.toIntArray()
            }
            Format.F51l -> {
                count(2)
                val v = lit(1).value
                u(o or (reg(0, 255) shl 8), (v and 0xffff).toInt(), ((v ushr 16) and 0xffff).toInt(), ((v ushr 32) and 0xffff).toInt(), ((v ushr 48) and 0xffff).toInt())
            }
        }.also { enc ->
            // Wide operands need their register pair inside the frame.
            ops.forEachIndexed { k, operand ->
                if (operand is Operand.Reg && k < op.wide.length && op.wide[k] == 'W' && operand.n + 1 >= registers) {
                    err("wide register pair v${operand.n}/v${operand.n + 1} exceeds .registers")
                }
            }
        }
    }

    private fun encodeTries(m: AsmMethod, label: (String, Int) -> Int, codeSize: Int): Pair<List<Triple<Int, Int, Int>>, ByteArray> {
        if (m.catches.isEmpty()) return emptyList<Triple<Int, Int, Int>>() to ByteArray(0)
        data class Range(val start: Int, val end: Int)
        val groups = LinkedHashMap<Range, MutableList<AsmCatch>>()
        for (c in m.catches) {
            val r = Range(label(c.start, c.line), label(c.end, c.line))
            if (r.end <= r.start || r.end > codeSize) throw AsmException(c.line, "empty or out-of-range try block")
            groups.getOrPut(r) { ArrayList() } += c
        }
        val sorted = groups.entries.sortedBy { it.key.start }
        for (k in 1 until sorted.size) {
            if (sorted[k - 1].key.end > sorted[k].key.start) throw AsmException(sorted[k].value.first().line, "try blocks overlap")
        }
        val lists = LinkedHashMap<List<Pair<Int, Int>>, Int>() // encoded handler -> offset
        val body = ByteArrayOutputStream()
        val handlerOffsets = ArrayList<Int>()
        val encodedLists = ArrayList<ByteArray>()
        val keyForTry = ArrayList<List<Pair<Int, Int>>>()
        for ((_, catches) in sorted) {
            val typed = catches.filter { it.type != null }.map { typeIdx.getValue(it.type!!) to label(it.handler, it.line) }
            val all = catches.filter { it.type == null }
            if (all.size > 1) throw AsmException(all[1].line, "more than one .catchall for the same range")
            if (catches.indexOfFirst { it.type == null }.let { it >= 0 && it != catches.lastIndex }) {
                throw AsmException(all[0].line, ".catchall must come after the typed handlers of its range")
            }
            val key = typed + (all.firstOrNull()?.let { listOf(-1 to label(it.handler, it.line)) } ?: emptyList())
            keyForTry += key
            if (!lists.containsKey(key)) {
                lists[key] = -1
                val b = ByteArrayOutputStream()
                val n = typed.size
                writeSleb(b, if (all.isEmpty()) n else -n)
                for ((t, a) in typed) {
                    writeUleb(b, t); writeUleb(b, a)
                }
                if (all.isNotEmpty()) writeUleb(b, key.last().second)
                encodedLists += b.toByteArray()
            }
        }
        writeUleb(body, lists.size)
        var i = 0
        for (k in lists.keys) {
            lists[k] = body.size()
            body.write(encodedLists[i++])
        }
        for (k in keyForTry) handlerOffsets += lists.getValue(k)
        val tries = sorted.mapIndexed { idx, e -> Triple(e.key.start, e.key.end - e.key.start, handlerOffsets[idx]) }
        tries.forEach { if (it.second > 0xffff) throw AsmException(m.line, "try block longer than 65535 units") }
        return tries to body.toByteArray()
    }

    private fun encodeDebug(m: AsmMethod, proto: ProtoKey, isStatic: Boolean, registers: Int, ins: Int, lines: List<Pair<Int, Int>>): ByteArray? {
        if (m.params.isEmpty() && lines.isEmpty()) return null
        val b = ByteArrayOutputStream()
        writeUleb(b, lines.firstOrNull()?.second ?: 1)
        writeUleb(b, proto.params.size)
        var reg = registers - ins + if (isStatic) 0 else 1
        val byReg = HashMap(m.params)
        for (p in proto.params) {
            val name = byReg.remove(reg)
            writeUleb(b, if (name == null) 0 else stringIdx.getValue(name) + 1)
            reg += Descriptors.registerWidth(p)
        }
        if (byReg.isNotEmpty()) throw AsmException(m.line, ".param names registers ${byReg.keys} that are not parameters")
        var address = 0
        var line = lines.firstOrNull()?.second ?: 1
        for ((a, l) in lines) {
            if (a < address) throw AsmException(m.line, ".line entries must not go backwards")
            if (a > address) {
                b.write(0x01); writeUleb(b, a - address); address = a
            }
            if (l != line) {
                b.write(0x02); writeSleb(b, l - line); line = l
            }
            b.write(0x0a + 4) // special opcode: address += 0, line += 0, emit position
        }
        b.write(0x00)
        return b.toByteArray()
    }

    // ---------------------------------------------------------------- layout

    private fun orderedClasses(): List<AsmClass> {
        val byName = file.classes.associateBy { it.name }
        if (byName.size != file.classes.size) throw AsmException(1, "a class is defined twice")
        val out = ArrayList<AsmClass>()
        val state = HashMap<String, Int>()
        fun visit(c: AsmClass) {
            when (state[c.name]) {
                2 -> return
                1 -> throw AsmException(c.line, "class hierarchy cycle at ${c.name}")
            }
            state[c.name] = 1
            (listOfNotNull(c.superclass) + c.interfaces).mapNotNull { byName[it] }.forEach(::visit)
            state[c.name] = 2
            out += c
        }
        file.classes.forEach(::visit)
        return out
    }

    private fun layout(): ByteArray {
        val classes = orderedClasses()
        val headerSize = 0x70
        var off = headerSize
        val stringIdsOff = off; off += strings.size * 4
        val typeIdsOff = off; off += types.size * 4
        val protoIdsOff = off; off += protoList.size * 12
        val fieldIdsOff = off; off += fieldList.size * 8
        val methodIdsOff = off; off += methodList.size * 8
        val classDefsOff = off; off += classes.size * 32
        val dataOff = off

        // Code items.
        data class CodeEntry(val cls: AsmClass, val method: AsmMethod, val code: EncodedCode, var offset: Int = 0, var debugOff: Int = 0)
        val codes = ArrayList<CodeEntry>()
        for (c in classes) for (m in c.methods) {
            val hasCode = m.registers != null || m.items.isNotEmpty()
            val abstractOrNative = m.flags and (AccessFlags.ABSTRACT or AccessFlags.NATIVE) != 0
            if (hasCode && abstractOrNative) throw AsmException(m.line, "${m.name}: abstract or native methods cannot have code")
            if (!hasCode && !abstractOrNative) throw AsmException(m.line, "${m.name}: method needs code or the abstract/native flag")
            if (hasCode) codes += CodeEntry(c, m, encodeMethod(c, m))
        }
        for (e in codes) {
            off = align4(off); e.offset = off; off += e.code.size
        }
        val codeSectionOff = codes.firstOrNull()?.offset ?: 0

        // Type lists (proto parameters and interfaces), deduplicated.
        val typeLists = LinkedHashMap<List<String>, Int>()
        protoList.forEach { if (it.params.isNotEmpty()) typeLists.putIfAbsent(it.params, 0) }
        classes.forEach { if (it.interfaces.isNotEmpty()) typeLists.putIfAbsent(it.interfaces, 0) }
        var typeListSectionOff = 0
        for (k in typeLists.keys.toList()) {
            off = align4(off)
            if (typeListSectionOff == 0) typeListSectionOff = off
            typeLists[k] = off
            off += 4 + k.size * 2
        }

        // String data.
        val stringData = strings.map { s -> ByteArrayOutputStream().also { writeUleb(it, s.length); it.write(Mutf8.encode(s)) }.toByteArray() }
        val stringDataOff = off
        val stringOffsets = IntArray(strings.size)
        stringData.forEachIndexed { i, b -> stringOffsets[i] = off; off += b.size }

        // Debug info.
        var debugSectionOff = 0
        var debugCount = 0
        for (e in codes) e.code.debug?.let {
            if (debugSectionOff == 0) debugSectionOff = off
            e.debugOff = off; off += it.size; debugCount++
        }

        // Class data.
        val codeOffsetOf = codes.associate { (it.cls.name to it.method) to it.offset }
        val classData = classes.map { c -> encodeClassData(c) { m -> codeOffsetOf[c.name to m] ?: 0 } }
        var classDataSectionOff = 0
        val classDataOffsets = IntArray(classes.size)
        classData.forEachIndexed { i, b ->
            if (b != null) {
                if (classDataSectionOff == 0) classDataSectionOff = off
                classDataOffsets[i] = off; off += b.size
            }
        }

        // Map list.
        off = align4(off)
        val mapOff = off
        val map = ArrayList<Triple<Int, Int, Int>>()
        map += Triple(0x0000, 1, 0)
        if (strings.isNotEmpty()) map += Triple(0x0001, strings.size, stringIdsOff)
        if (types.isNotEmpty()) map += Triple(0x0002, types.size, typeIdsOff)
        if (protoList.isNotEmpty()) map += Triple(0x0003, protoList.size, protoIdsOff)
        if (fieldList.isNotEmpty()) map += Triple(0x0004, fieldList.size, fieldIdsOff)
        if (methodList.isNotEmpty()) map += Triple(0x0005, methodList.size, methodIdsOff)
        if (classes.isNotEmpty()) map += Triple(0x0006, classes.size, classDefsOff)
        if (codes.isNotEmpty()) map += Triple(0x2001, codes.size, codeSectionOff)
        if (typeLists.isNotEmpty()) map += Triple(0x1001, typeLists.size, typeListSectionOff)
        if (strings.isNotEmpty()) map += Triple(0x2002, strings.size, stringDataOff)
        if (debugCount > 0) map += Triple(0x2003, debugCount, debugSectionOff)
        val classDataCount = classData.count { it != null }
        if (classDataCount > 0) map += Triple(0x2000, classDataCount, classDataSectionOff)
        map += Triple(0x1000, 1, mapOff)
        off += 4 + map.size * 12
        val fileSize = off

        val out = Buf(fileSize)
        // Header.
        out.bytes("dex\n0${file.version}\u0000".toByteArray(Charsets.US_ASCII), 0)
        out.u4(0x20, fileSize); out.u4(0x24, headerSize); out.u4(0x28, 0x12345678)
        out.u4(0x2c, 0); out.u4(0x30, 0); out.u4(0x34, mapOff)
        fun sec(p: Int, size: Int, o: Int) {
            out.u4(p, size); out.u4(p + 4, if (size == 0) 0 else o)
        }
        sec(0x38, strings.size, stringIdsOff); sec(0x40, types.size, typeIdsOff); sec(0x48, protoList.size, protoIdsOff)
        sec(0x50, fieldList.size, fieldIdsOff); sec(0x58, methodList.size, methodIdsOff); sec(0x60, classes.size, classDefsOff)
        out.u4(0x68, fileSize - dataOff); out.u4(0x6c, dataOff)
        // Id tables.
        stringOffsets.forEachIndexed { i, o -> out.u4(stringIdsOff + i * 4, o) }
        types.forEachIndexed { i, t -> out.u4(typeIdsOff + i * 4, stringIdx.getValue(t)) }
        protoList.forEachIndexed { i, p ->
            val b = protoIdsOff + i * 12
            out.u4(b, stringIdx.getValue(p.shorty)); out.u4(b + 4, typeIdx.getValue(p.ret))
            out.u4(b + 8, if (p.params.isEmpty()) 0 else typeLists.getValue(p.params))
        }
        fieldList.forEachIndexed { i, f ->
            val b = fieldIdsOff + i * 8
            out.u2(b, typeIdx.getValue(f.cls)); out.u2(b + 2, typeIdx.getValue(f.type)); out.u4(b + 4, stringIdx.getValue(f.name))
        }
        methodList.forEachIndexed { i, m ->
            val b = methodIdsOff + i * 8
            out.u2(b, typeIdx.getValue(m.cls)); out.u2(b + 2, protoIdx.getValue(m.proto)); out.u4(b + 4, stringIdx.getValue(m.name))
        }
        classes.forEachIndexed { i, c ->
            val b = classDefsOff + i * 32
            out.u4(b, typeIdx.getValue(c.name)); out.u4(b + 4, c.flags)
            out.u4(b + 8, c.superclass?.let { typeIdx.getValue(it) } ?: -1)
            out.u4(b + 12, if (c.interfaces.isEmpty()) 0 else typeLists.getValue(c.interfaces))
            out.u4(b + 16, c.sourceFile?.let { stringIdx.getValue(it) } ?: -1)
            out.u4(b + 20, 0); out.u4(b + 24, classDataOffsets[i]); out.u4(b + 28, 0)
        }
        // Code items.
        for (e in codes) {
            val c = e.code
            var p = e.offset
            out.u2(p, c.registers); out.u2(p + 2, c.ins); out.u2(p + 4, c.outs); out.u2(p + 6, c.tries.size)
            out.u4(p + 8, e.debugOff); out.u4(p + 12, c.insns.size)
            p += 16
            for (w in c.insns) {
                out.u2(p, w); p += 2
            }
            if (c.tries.isNotEmpty()) {
                if (c.insns.size % 2 == 1) p += 2
                for ((start, count, handler) in c.tries) {
                    out.u4(p, start); out.u2(p + 4, count); out.u2(p + 6, handler); p += 8
                }
                out.bytes(c.handlerBytes, p)
            }
            c.debug?.let { out.bytes(it, e.debugOff) }
        }
        for ((list, o) in typeLists) {
            out.u4(o, list.size)
            list.forEachIndexed { i, t -> out.u2(o + 4 + i * 2, typeIdx.getValue(t)) }
        }
        stringData.forEachIndexed { i, b -> out.bytes(b, stringOffsets[i]) }
        classData.forEachIndexed { i, b -> if (b != null) out.bytes(b, classDataOffsets[i]) }
        out.u4(mapOff, map.size)
        map.forEachIndexed { i, (type, size, o) ->
            val b = mapOff + 4 + i * 12
            out.u2(b, type); out.u2(b + 2, 0); out.u4(b + 4, size); out.u4(b + 8, o)
        }
        // Checksums.
        val bytes = out.data
        val sha1 = MessageDigest.getInstance("SHA-1").also { it.update(bytes, 32, bytes.size - 32) }.digest()
        System.arraycopy(sha1, 0, bytes, 12, 20)
        out.u4(8, Adler32().also { it.update(bytes, 12, bytes.size - 12) }.value.toInt())
        return bytes
    }

    private fun encodeClassData(c: AsmClass, codeOff: (AsmMethod) -> Int): ByteArray? {
        if (c.fields.isEmpty() && c.methods.isEmpty()) return null
        val staticFields = c.fields.filter { it.flags and AccessFlags.STATIC != 0 }
        val instanceFields = c.fields.filter { it.flags and AccessFlags.STATIC == 0 }
        fun isDirect(m: AsmMethod) = m.flags and (AccessFlags.STATIC or AccessFlags.PRIVATE or AccessFlags.CONSTRUCTOR) != 0 || m.name == "<init>" || m.name == "<clinit>"
        val direct = c.methods.filter(::isDirect)
        val virtual = c.methods.filterNot(::isDirect)
        val b = ByteArrayOutputStream()
        writeUleb(b, staticFields.size); writeUleb(b, instanceFields.size); writeUleb(b, direct.size); writeUleb(b, virtual.size)
        fun fieldsOut(list: List<AsmField>) {
            var prev = 0
            list.map { fieldIdx.getValue(FieldKey(c.name, it.name, it.type)) to it }.sortedBy { it.first }.forEach { (idx, f) ->
                writeUleb(b, idx - prev); writeUleb(b, f.flags); prev = idx
            }
        }
        fun methodsOut(list: List<AsmMethod>) {
            var prev = 0
            val seen = HashSet<Int>()
            list.map { methodIdx.getValue(MethodRefKey(c.name, it.name, parseProto(it.proto, it.line))) to it }.sortedBy { it.first }.forEach { (idx, m) ->
                if (!seen.add(idx)) throw AsmException(m.line, "method ${m.name}${m.proto} is defined twice")
                writeUleb(b, idx - prev); writeUleb(b, m.flags); writeUleb(b, codeOff(m)); prev = idx
            }
        }
        fieldsOut(staticFields); fieldsOut(instanceFields); methodsOut(direct); methodsOut(virtual)
        return b.toByteArray()
    }

    private fun align4(v: Int) = (v + 3) and 3.inv()

    private class Buf(size: Int) {
        val data = ByteArray(size)
        fun u2(p: Int, v: Int) {
            data[p] = v.toByte(); data[p + 1] = (v ushr 8).toByte()
        }
        fun u4(p: Int, v: Int) {
            u2(p, v and 0xffff); u2(p + 2, v ushr 16)
        }
        fun bytes(b: ByteArray, p: Int) = System.arraycopy(b, 0, data, p, b.size)
    }

    companion object {
        fun writeUleb(out: ByteArrayOutputStream, value: Int) {
            var v = value
            do {
                var b = v and 0x7f
                v = v ushr 7
                if (v != 0) b = b or 0x80
                out.write(b)
            } while (v != 0)
        }

        fun writeSleb(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while (true) {
                val b = v and 0x7f
                v = v shr 7
                val done = (v == 0 && b and 0x40 == 0) || (v == -1 && b and 0x40 != 0)
                out.write(if (done) b else b or 0x80)
                if (done) return
            }
        }
    }
}
