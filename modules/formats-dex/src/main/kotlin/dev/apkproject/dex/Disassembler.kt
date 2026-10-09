package dev.apkproject.dex

import dev.apkproject.contracts.Outcome

/**
 * Renders DEX as Dalvik assembly text (`.dasm`). The listing is observed evidence: every line
 * is a direct rendering of decoded bytes, with code-unit addresses and optional byte offsets.
 * The same grammar is accepted by the `:dex-asm` assembler, so `assemble(disassemble(x))`
 * reproduces the instructions of `x`.
 */
object Disassembler {
    data class Options(
        val includeDebug: Boolean = true,
        val includeByteOffsets: Boolean = false,
        val includeBanner: Boolean = true,
    )

    fun file(dex: DexFile, options: Options = Options()): String {
        val sb = StringBuilder()
        if (options.includeBanner) {
            sb.append("; Dalvik assembly rendered by apkproject. Observed evidence, not recovered source.\n")
            sb.append("; dex sha256 ").append(dex.sha256).append('\n')
        }
        sb.append(".dex ").append("%03d".format(dex.version)).append("\n")
        for (c in dex.classDefs) {
            sb.append('\n')
            classHeader(dex, c, sb)
            val data = c.classData ?: continue
            for (f in data.staticFields + data.instanceFields) {
                val id = dex.fieldIds[f.fieldIdx]
                sb.append(".field ").append(flags(f.accessFlags, AccessFlags.Kind.FIELD))
                    .append(dex.string(id.nameIdx)).append(':').append(dex.type(id.typeIdx)).append('\n')
            }
            for (m in data.directMethods + data.virtualMethods) {
                sb.append('\n')
                method(DexMethod(dex, c, m), options, sb)
            }
        }
        return sb.toString()
    }

    fun method(m: DexMethod, options: Options = Options()): String = StringBuilder().also { method(m, options, it) }.toString()

    private fun flags(value: Int, kind: AccessFlags.Kind): String =
        AccessFlags.render(value, kind).let { if (it.isEmpty()) "" else "$it " }

    private fun classHeader(dex: DexFile, c: ClassDef, sb: StringBuilder) {
        sb.append(".class ").append(flags(c.accessFlags, AccessFlags.Kind.CLASS)).append(dex.type(c.classIdx)).append('\n')
        if (c.superclassIdx != NO_INDEX) sb.append(".super ").append(dex.type(c.superclassIdx)).append('\n')
        for (i in c.interfaces) sb.append(".implements ").append(dex.type(i)).append('\n')
        if (c.sourceFileIdx != NO_INDEX) sb.append(".source ").append(quote(dex.string(c.sourceFileIdx))).append('\n')
    }

    private fun method(m: DexMethod, options: Options, sb: StringBuilder) {
        sb.append(".method ").append(flags(m.accessFlags, AccessFlags.Kind.METHOD)).append(m.name).append(m.protoDescriptor).append('\n')
        val code = m.code
        if (code != null) {
            sb.append("    .registers ").append(code.registersSize).append('\n')
            sb.append("    .ins ").append(code.insSize).append('\n')
            sb.append("    .outs ").append(code.outsSize).append('\n')
            when (val decoded = InstructionDecoder.decode(m.dex, code)) {
                is Outcome.Ok -> body(m, decoded.value, options, sb)
                is Outcome.Err -> sb.append("    ; decode failed: ").append(decoded.failure).append('\n')
            }
        }
        sb.append(".end method\n")
    }

    private fun body(m: DexMethod, d: DecodedCode, options: Options, sb: StringBuilder) {
        val dex = m.dex
        val debug = if (options.includeDebug) runCatching { m.debugInfo }.getOrNull() else null
        if (debug != null) {
            // Parameter registers are the last ins_size registers; `this` comes first for instance methods.
            var reg = d.code.registersSize - d.code.insSize + if (m.isStatic) 0 else 1
            m.proto.parameters.forEachIndexed { i, t ->
                val name = debug.parameterNames.getOrNull(i)
                if (name != null) sb.append("    .param v").append(reg).append(' ').append(quote(name)).append('\n')
                reg += Descriptors.registerWidth(dex.type(t))
            }
        }
        val lines = debug?.positions?.groupBy { it.address }?.mapValues { e -> e.value.last().line } ?: emptyMap()
        val locals = debug?.locals?.groupBy { it.startAddress } ?: emptyMap()
        val labels = d.labelAddresses()
        val payloadOwner = HashMap<Int, Insn>()
        for (i in d.insns) if (i.op.payloadRef && !payloadOwner.containsKey(i.target)) payloadOwner[i.target] = i
        val items = (d.insns.map { it.address to it } + d.payloads.map { it.key to it.value }).sortedBy { it.first }
        for ((address, item) in items) {
            if (address in labels) sb.append("  ").append(label(address)).append('\n')
            lines[address]?.let { sb.append("    .line ").append(it).append('\n') }
            locals[address]?.forEach { l ->
                sb.append("    ; local v").append(l.register).append(' ').append(l.name ?: "?").append(':').append(l.type ?: "?").append('\n')
            }
            sb.append("    ").append(addr(address)).append(": ")
            when (item) {
                is Insn -> {
                    sb.append(insn(dex, item))
                    if (options.includeByteOffsets) sb.append("    ; @0x").append((d.code.insnsOffset + address * 2).toString(16))
                    sb.append('\n')
                }
                is Payload -> payload(item, payloadOwner[address], sb)
            }
        }
        for (t in d.code.tries) {
            val h = d.code.handlerFor(t)
            val range = "{${label(t.startAddr)} .. ${label(t.startAddr + t.insnCount)}}"
            for ((type, target) in h.typed) sb.append("    .catch ").append(dex.type(type)).append(' ').append(range).append(' ').append(label(target)).append('\n')
            if (h.catchAllAddr >= 0) sb.append("    .catchall ").append(range).append(' ').append(label(h.catchAllAddr)).append('\n')
        }
    }

    fun addr(a: Int): String = "%04x".format(a)
    fun label(a: Int): String = ":L%04x".format(a)

    fun insn(dex: DexFile, i: Insn): String {
        val op = i.op
        val r = i.regs.map { "v$it" }
        fun ref(index: Int = i.index): String = when (op.index) {
            IndexKind.STRING -> quote(dex.string(index))
            IndexKind.TYPE -> dex.type(index)
            IndexKind.FIELD -> dex.fieldDescriptor(index)
            IndexKind.METHOD, IndexKind.METHOD_AND_PROTO -> dex.methodDescriptor(index)
            IndexKind.PROTO -> dex.protoDescriptor(index)
            IndexKind.CALL_SITE -> "call_site@$index"
            IndexKind.METHOD_HANDLE -> "method_handle@$index"
            IndexKind.NONE -> error("no index")
        }
        val operands: String = when (op.format) {
            Format.F10x -> ""
            Format.F12x, Format.F22x, Format.F32x, Format.F11x, Format.F23x -> r.joinToString(", ")
            Format.F11n, Format.F21s, Format.F31i, Format.F51l -> "${r[0]}, #${i.literal}"
            Format.F21h -> "${r[0]}, #0x" + if (op.wide == "W") java.lang.Long.toHexString(i.literal) else Integer.toHexString(i.literal.toInt())
            Format.F22b, Format.F22s -> "${r[0]}, ${r[1]}, #${i.literal}"
            Format.F10t, Format.F20t, Format.F30t -> label(i.target)
            Format.F21t, Format.F31t -> "${r[0]}, ${label(i.target)}"
            Format.F22t -> "${r[0]}, ${r[1]}, ${label(i.target)}"
            Format.F21c, Format.F31c -> "${r[0]}, ${ref()}"
            Format.F22c -> "${r[0]}, ${r[1]}, ${ref()}"
            Format.F35c -> "{${r.joinToString(", ")}}, ${ref()}"
            Format.F3rc -> "${range(i.regs)}, ${ref()}"
            Format.F45cc -> "{${r.joinToString(", ")}}, ${ref()}, ${dex.protoDescriptor(i.index2)}"
            Format.F4rcc -> "${range(i.regs)}, ${ref()}, ${dex.protoDescriptor(i.index2)}"
        }
        return if (operands.isEmpty()) op.mnemonic else "${op.mnemonic} $operands"
    }

    private fun range(regs: IntArray): String = if (regs.isEmpty()) "{}" else "{v${regs.first()} .. v${regs.last()}}"

    private fun payload(p: Payload, owner: Insn?, sb: StringBuilder) {
        fun target(rel: Int): String = if (owner != null) label(owner.address + rel) else "+$rel"
        when (p) {
            is PackedSwitchPayload -> {
                sb.append(".packed-switch ").append(p.firstKey).append('\n')
                for (t in p.relativeTargets) sb.append("        ").append(target(t)).append('\n')
                sb.append("    .end packed-switch\n")
            }
            is SparseSwitchPayload -> {
                sb.append(".sparse-switch\n")
                for (k in p.keys.indices) sb.append("        ").append(p.keys[k]).append(" -> ").append(target(p.relativeTargets[k])).append('\n')
                sb.append("    .end sparse-switch\n")
            }
            is FillArrayDataPayload -> {
                sb.append(".array-data ").append(p.elementWidth).append('\n')
                val values = (0 until p.count).map { e ->
                    var v = 0L
                    for (b in 0 until p.elementWidth) v = v or ((p.data[e * p.elementWidth + b].toLong() and 0xff) shl (8 * b))
                    val shift = 64 - 8 * p.elementWidth
                    (v shl shift) shr shift
                }
                values.chunked(8).forEach { chunk -> sb.append("        ").append(chunk.joinToString(" ")).append('\n') }
                sb.append("    .end array-data\n")
            }
        }
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\t' -> sb.append("\\t")
                ch == '\r' -> sb.append("\\r")
                ch.code in 0x20..0x7e -> sb.append(ch)
                else -> sb.append("\\u%04x".format(ch.code))
            }
        }
        return sb.append('"').toString()
    }
}
