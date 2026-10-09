package dev.apkproject.dex

import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure
import dev.apkproject.contracts.Outcome

/** One decoded instruction. Branch and payload targets are absolute code-unit addresses. */
class Insn(
    val address: Int,
    val op: OpcodeInfo,
    /** Register operands in syntax order (vA, vB, vC / vC..vG / range expanded). */
    val regs: IntArray,
    /** Effective literal value (sign-extended, high16 shifted); 0 when the format has none. */
    val literal: Long,
    val target: Int,
    val index: Int,
    val index2: Int,
) {
    val units: Int get() = op.units
    val next: Int get() = address + units
    override fun toString(): String = "%04x: %s".format(address, op.mnemonic)
}

sealed class Payload {
    abstract val address: Int
    abstract val units: Int
}

class PackedSwitchPayload(override val address: Int, override val units: Int, val firstKey: Int, val relativeTargets: IntArray) : Payload()

class SparseSwitchPayload(override val address: Int, override val units: Int, val keys: IntArray, val relativeTargets: IntArray) : Payload()

class FillArrayDataPayload(override val address: Int, override val units: Int, val elementWidth: Int, val count: Int, val data: ByteArray) : Payload()

class DecodedCode(val code: CodeItem, val insns: List<Insn>, val payloads: Map<Int, Payload>) {
    val byAddress: Map<Int, Insn> = insns.associateBy { it.address }

    /** (key, absolute target) pairs for a packed or sparse switch. */
    fun switchCases(insn: Insn): List<Pair<Int, Int>> = when (val p = payloads[insn.target]) {
        is PackedSwitchPayload -> p.relativeTargets.mapIndexed { i, rel -> (p.firstKey + i) to (insn.address + rel) }
        is SparseSwitchPayload -> p.keys.indices.map { i -> p.keys[i] to (insn.address + p.relativeTargets[i]) }
        else -> emptyList()
    }

    /** Every address that some instruction, switch, try range or handler can transfer control to. */
    fun labelAddresses(): Set<Int> {
        val out = HashSet<Int>()
        for (i in insns) {
            if (i.target >= 0) out += i.target
            if (i.op.flow == FlowKind.SWITCH) switchCases(i).forEach { out += it.second }
        }
        for (t in code.tries) {
            out += t.startAddr
            out += t.startAddr + t.insnCount
            val h = code.handlerFor(t)
            h.typed.forEach { out += it.second }
            if (h.catchAllAddr >= 0) out += h.catchAllAddr
        }
        return out
    }
}

/**
 * Decodes a code item by linear sweep. A unit whose low byte is 0x00 and high byte is 1, 2 or 3
 * can only be a payload (a real `nop` has a zero high byte), so every code unit receives exactly
 * one interpretation. All targets, indexes and registers are bounds-checked afterwards.
 */
object InstructionDecoder {
    fun decode(dex: DexFile, code: CodeItem): Outcome<DecodedCode> = Outcome.of { Sweep(dex, code).run() }

    private class Sweep(val dex: DexFile, val code: CodeItem) {
        val u = code.insns
        val n = u.size

        fun fail(address: Int, reason: String, unsupported: Boolean = false): Nothing = throw FormatException(
            FormatFailure(dex.sha256, (code.insnsOffset + address * 2).toLong(), "code_item@0x${code.offset.toString(16)}", reason, unsupported),
        )

        fun unit(a: Int): Int {
            if (a >= n) fail(a, "instruction runs past the end of the code (${n} units)")
            return u[a]
        }

        fun s16(a: Int): Int = unit(a).toShort().toInt()
        fun i32(a: Int): Int = unit(a) or (unit(a + 1) shl 16)

        fun run(): DecodedCode {
            val insns = ArrayList<Insn>()
            val payloads = HashMap<Int, Payload>()
            var a = 0
            while (a < n) {
                val first = u[a]
                val opcode = first and 0xff
                val hi = first ushr 8
                if (opcode == 0 && hi != 0) {
                    val p = payload(a, first)
                    payloads[a] = p
                    a += p.units
                    continue
                }
                val op = Opcodes[opcode] ?: fail(a, "unassigned opcode 0x%02x".format(opcode))
                if (dex.version < op.minVersion) fail(a, "${op.mnemonic} requires DEX ${op.minVersion}, file is ${dex.version}")
                if (a + op.units > n) fail(a, "${op.mnemonic} runs past the end of the code")
                insns += decodeOne(a, op, hi)
                a += op.units
            }
            val decoded = DecodedCode(code, insns, payloads)
            validate(decoded)
            return decoded
        }

        private fun payload(a: Int, ident: Int): Payload {
            if (a % 2 != 0) fail(a, "payload at odd address is not 4-byte aligned")
            return when (ident) {
                Opcodes.PACKED_SWITCH_PAYLOAD -> {
                    val size = unit(a + 1)
                    val units = size * 2 + 4
                    if (a + units > n) fail(a, "packed-switch payload runs past the end of the code")
                    PackedSwitchPayload(a, units, i32(a + 2), IntArray(size) { i32(a + 4 + it * 2) })
                }
                Opcodes.SPARSE_SWITCH_PAYLOAD -> {
                    val size = unit(a + 1)
                    val units = size * 4 + 2
                    if (a + units > n) fail(a, "sparse-switch payload runs past the end of the code")
                    val keys = IntArray(size) { i32(a + 2 + it * 2) }
                    for (i in 1 until size) if (keys[i - 1] >= keys[i]) fail(a, "sparse-switch keys are not strictly ascending")
                    SparseSwitchPayload(a, units, keys, IntArray(size) { i32(a + 2 + size * 2 + it * 2) })
                }
                Opcodes.FILL_ARRAY_DATA_PAYLOAD -> {
                    val width = unit(a + 1)
                    if (width !in setOf(1, 2, 4, 8)) fail(a, "fill-array-data element width $width is invalid")
                    val count = (unit(a + 2).toLong() or (unit(a + 3).toLong() shl 16))
                    val bytes = count * width
                    val units = (bytes + 1) / 2 + 4
                    if (a + units > n) fail(a, "fill-array-data payload runs past the end of the code")
                    val data = ByteArray(bytes.toInt()) { i ->
                        val w = u[a + 4 + i / 2]
                        (if (i % 2 == 0) w and 0xff else w ushr 8).toByte()
                    }
                    FillArrayDataPayload(a, units.toInt(), width, count.toInt(), data)
                }
                else -> fail(a, "nop with non-zero high byte 0x%02x is neither nop nor a payload".format(ident ushr 8))
            }
        }

        private fun decodeOne(a: Int, op: OpcodeInfo, hi: Int): Insn {
            var regs = IntArray(0)
            var literal = 0L
            var target = -1
            var index = -1
            var index2 = -1
            val nibA = hi and 0xf
            val nibB = hi ushr 4
            fun zeroHigh() {
                if (hi != 0) fail(a, "${op.mnemonic}: format ${op.format.id} requires a zero high byte")
            }
            when (op.format) {
                Format.F10x -> zeroHigh()
                Format.F12x -> regs = intArrayOf(nibA, nibB)
                Format.F11n -> {
                    regs = intArrayOf(nibA)
                    literal = ((nibB shl 28) shr 28).toLong()
                }
                Format.F11x -> regs = intArrayOf(hi)
                Format.F10t -> target = a + hi.toByte().toInt()
                Format.F20t -> {
                    zeroHigh(); target = a + s16(a + 1)
                }
                Format.F22x -> regs = intArrayOf(hi, unit(a + 1))
                Format.F21t -> {
                    regs = intArrayOf(hi); target = a + s16(a + 1)
                }
                Format.F21s -> {
                    regs = intArrayOf(hi); literal = s16(a + 1).toLong()
                }
                Format.F21h -> {
                    regs = intArrayOf(hi)
                    literal = if (op.wide == "W") s16(a + 1).toLong() shl 48 else (s16(a + 1) shl 16).toLong()
                }
                Format.F21c -> {
                    regs = intArrayOf(hi); index = unit(a + 1)
                }
                Format.F23x -> {
                    val w = unit(a + 1); regs = intArrayOf(hi, w and 0xff, w ushr 8)
                }
                Format.F22b -> {
                    val w = unit(a + 1); regs = intArrayOf(hi, w and 0xff); literal = (w ushr 8).toByte().toLong()
                }
                Format.F22t -> {
                    regs = intArrayOf(nibA, nibB); target = a + s16(a + 1)
                }
                Format.F22s -> {
                    regs = intArrayOf(nibA, nibB); literal = s16(a + 1).toLong()
                }
                Format.F22c -> {
                    regs = intArrayOf(nibA, nibB); index = unit(a + 1)
                }
                Format.F32x -> {
                    zeroHigh(); regs = intArrayOf(unit(a + 1), unit(a + 2))
                }
                Format.F30t -> {
                    zeroHigh(); target = a + i32(a + 1)
                }
                Format.F31t -> {
                    regs = intArrayOf(hi); target = a + i32(a + 1)
                }
                Format.F31i -> {
                    regs = intArrayOf(hi); literal = i32(a + 1).toLong()
                }
                Format.F31c -> {
                    regs = intArrayOf(hi)
                    val v = unit(a + 1).toLong() or (unit(a + 2).toLong() shl 16)
                    if (v > Int.MAX_VALUE) fail(a, "string index $v out of range")
                    index = v.toInt()
                }
                Format.F35c, Format.F45cc -> {
                    val count = nibB
                    if (count > 5) fail(a, "${op.mnemonic} with $count arguments (maximum 5)")
                    index = unit(a + 1)
                    val w = unit(a + 2)
                    regs = intArrayOf(w and 0xf, (w ushr 4) and 0xf, (w ushr 8) and 0xf, w ushr 12, nibA).copyOf(count)
                    if (op.format == Format.F45cc) index2 = unit(a + 3)
                }
                Format.F3rc, Format.F4rcc -> {
                    index = unit(a + 1)
                    val first = unit(a + 2)
                    regs = IntArray(hi) { first + it }
                    if (op.format == Format.F4rcc) index2 = unit(a + 3)
                }
                Format.F51l -> {
                    regs = intArrayOf(hi)
                    literal = unit(a + 1).toLong() or (unit(a + 2).toLong() shl 16) or (unit(a + 3).toLong() shl 32) or (unit(a + 4).toLong() shl 48)
                }
            }
            return Insn(a, op, regs, literal, target, index, index2)
        }

        private fun validate(d: DecodedCode) {
            val starts = d.byAddress.keys
            for (i in d.insns) {
                checkRegisters(i)
                checkIndex(i)
                when {
                    i.op.payloadRef -> {
                        val p = d.payloads[i.target] ?: fail(i.address, "${i.op.mnemonic} target 0x%x is not a payload".format(i.target))
                        val ok = when (i.op.mnemonic) {
                            "packed-switch" -> p is PackedSwitchPayload
                            "sparse-switch" -> p is SparseSwitchPayload
                            else -> p is FillArrayDataPayload
                        }
                        if (!ok) fail(i.address, "${i.op.mnemonic} points at the wrong payload kind")
                        for ((_, t) in d.switchCases(i)) if (t !in starts) fail(i.address, "switch case target 0x%x is not an instruction".format(t))
                    }
                    i.target >= 0 || i.op.flow == FlowKind.GOTO || i.op.flow == FlowKind.IF -> {
                        if (i.target == i.address && i.op.format != Format.F30t) fail(i.address, "${i.op.mnemonic} has a zero branch offset")
                        if (i.target !in starts) fail(i.address, "${i.op.mnemonic} target 0x%x is not an instruction boundary".format(i.target))
                    }
                }
            }
            for (t in code.tries) {
                if (t.startAddr !in starts) fail(t.startAddr, "try start is not an instruction boundary")
                val end = t.startAddr + t.insnCount
                if (end != n && end !in starts && end !in d.payloads) fail(end, "try end is not an instruction boundary")
                val h = code.handlerFor(t)
                for ((type, addr) in h.typed) {
                    if (type >= dex.typeIds.size) fail(t.startAddr, "catch type index $type out of range")
                    if (addr !in starts) fail(addr, "handler address is not an instruction boundary")
                }
                if (h.catchAllAddr >= 0 && h.catchAllAddr !in starts) fail(h.catchAllAddr, "catch-all address is not an instruction boundary")
            }
        }

        private fun checkRegisters(i: Insn) {
            val limit = code.registersSize
            i.regs.forEachIndexed { k, r ->
                val width = if (k < i.op.wide.length && i.op.wide[k] == 'W') 2 else 1
                if (r + width > limit) fail(i.address, "${i.op.mnemonic}: register v$r${if (width == 2) " (pair)" else ""} exceeds registers_size $limit")
            }
            val isInvoke = i.op.mnemonic.startsWith("invoke-")
            if (isInvoke && i.regs.size > code.outsSize) fail(i.address, "${i.op.mnemonic} passes ${i.regs.size} registers but outs_size is ${code.outsSize}")
        }

        private fun checkIndex(i: Insn) {
            fun check(idx: Int, size: Int, what: String) {
                if (idx < 0 || idx >= size) fail(i.address, "${i.op.mnemonic}: $what index $idx out of range (size $size)")
            }
            when (i.op.index) {
                IndexKind.NONE -> Unit
                IndexKind.STRING -> check(i.index, dex.strings.size, "string")
                IndexKind.TYPE -> check(i.index, dex.typeIds.size, "type")
                IndexKind.FIELD -> check(i.index, dex.fieldIds.size, "field")
                IndexKind.METHOD -> check(i.index, dex.methodIds.size, "method")
                IndexKind.PROTO -> check(i.index, dex.protos.size, "proto")
                IndexKind.CALL_SITE -> check(i.index, dex.callSiteCount, "call site")
                IndexKind.METHOD_HANDLE -> check(i.index, dex.methodHandleCount, "method handle")
                IndexKind.METHOD_AND_PROTO -> {
                    check(i.index, dex.methodIds.size, "method"); check(i.index2, dex.protos.size, "proto")
                }
            }
        }
    }
}
