package dev.apkproject.lift

import dev.apkproject.contracts.CapabilityGap
import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.FlowKind
import dev.apkproject.dex.Insn
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.ir.BasicBlock
import dev.apkproject.ir.BinKind
import dev.apkproject.ir.Binary
import dev.apkproject.ir.ConstBits
import dev.apkproject.ir.EffectKind
import dev.apkproject.ir.Goto
import dev.apkproject.ir.If
import dev.apkproject.ir.IrType
import dev.apkproject.ir.LiftResult
import dev.apkproject.ir.MethodIr
import dev.apkproject.ir.MethodSignature
import dev.apkproject.ir.Op
import dev.apkproject.ir.Param
import dev.apkproject.ir.Phi
import dev.apkproject.ir.Provenance
import dev.apkproject.ir.Return
import dev.apkproject.ir.Terminator
import dev.apkproject.ir.Unary
import dev.apkproject.ir.Value
import dev.apkproject.lift.V0Semantics.Sem

/**
 * Lifts DEX bytecode to typed SSA IR (dossier section 14): CFG construction, reaching
 * definitions with phi merges, I32 typing and explicit capability gaps. Anything outside the
 * V0 manifest is reported, never approximated.
 */
object DexLifter {
    private class Gap(val code: String, detail: String) : Exception(detail)

    fun lift(m: DexMethod): LiftResult {
        val key = m.key
        val gaps = ArrayList<CapabilityGap>()
        fun gap(code: String, detail: String, insn: Insn? = null) {
            gaps += CapabilityGap(key.display(), code, detail, insn?.let { m.byteRangeOf(it.address, it.units) })
        }
        val code = m.code
        if (code == null) {
            gap("method.no-code", "abstract or native method has no bytecode to recover")
            return LiftResult(key, null, gaps)
        }
        if (!m.isStatic) gap("method.instance", "V0 lifts static methods only")
        val proto = m.proto
        if (proto.parameters.any { m.dex.type(it) != "I" } || m.dex.type(proto.returnType) != "I") {
            gap("signature.non-i32", "V0 supports (I...)I signatures; found ${m.protoDescriptor}")
        }
        val decoded = when (val d = InstructionDecoder.decode(m.dex, code)) {
            is Outcome.Ok -> d.value
            is Outcome.Err -> {
                gap("decode.failed", d.failure.toString())
                return LiftResult(key, null, gaps)
            }
        }
        if (code.tries.isNotEmpty()) gap("exceptions.try-catch", "exception handlers are outside V0")
        if (decoded.payloads.isNotEmpty()) gap("payload.unsupported", "switch and array payloads are outside V0")
        for (i in decoded.insns) {
            if (i.op.mnemonic !in V0Semantics.manifest) gap("opcode.unsupported", "${i.op.mnemonic} at %04x".format(i.address), i)
        }
        if (gaps.isNotEmpty()) return LiftResult(key, null, gaps)
        return try {
            LiftResult(key, Builder(m, decoded).build(), emptyList())
        } catch (g: Gap) {
            LiftResult(key, null, listOf(CapabilityGap(key.display(), g.code, g.message ?: "")))
        }
    }

    private class Builder(val m: DexMethod, val d: DecodedCode) {
        val code = d.code
        var nextValue = 0
        fun newValue() = Value(nextValue++, IrType.I32)
        fun prov(i: Insn) = Provenance(i.address, m.byteRangeOf(i.address, i.units))

        fun build(): MethodIr {
            // 1. Basic blocks.
            val leaders = sortedSetOf(0)
            for (i in d.insns) {
                when (i.op.flow) {
                    FlowKind.IF -> {
                        leaders += i.target; leaders += i.next
                    }
                    FlowKind.GOTO -> {
                        leaders += i.target; leaders += i.next
                    }
                    FlowKind.RETURN -> leaders += i.next
                    else -> Unit
                }
            }
            leaders.removeIf { it >= code.insns.size }
            val starts = leaders.toList()
            val blockOf = starts.withIndex().associate { it.value to it.index }
            val blockInsns = starts.mapIndexed { idx, s ->
                val end = starts.getOrElse(idx + 1) { code.insns.size }
                d.insns.filter { it.address in s until end }
            }
            fun target(address: Int): Int = blockOf[address] ?: throw Gap("cfg.bad-target", "branch to %04x is not a block start".format(address))
            val successors = blockInsns.mapIndexed { idx, insns ->
                val last = insns.last()
                when (last.op.flow) {
                    FlowKind.RETURN -> emptyList()
                    FlowKind.GOTO -> listOf(target(last.target))
                    FlowKind.IF -> {
                        if (last.next >= code.insns.size) throw Gap("cfg.falls-off-end", "if at %04x falls off the end of the code".format(last.address))
                        listOf(target(last.target), target(last.next))
                    }
                    else -> {
                        if (last.next >= code.insns.size) throw Gap("cfg.falls-off-end", "control falls off the end after %04x".format(last.address))
                        listOf(target(last.next))
                    }
                }
            }

            // 2. Reachability, loop detection and reverse postorder.
            val color = IntArray(starts.size)
            val post = ArrayList<Int>()
            fun dfs(b: Int) {
                color[b] = 1
                for (s in successors[b]) {
                    if (color[s] == 1) throw Gap("cfg.loop", "back edge b$b -> b$s; V0 excludes loops that need invariants")
                    if (color[s] == 0) dfs(s)
                }
                color[b] = 2
                post += b
            }
            dfs(0)
            val rpo = post.reversed()
            val preds = HashMap<Int, MutableList<Int>>()
            for (b in rpo) for (s in successors[b].distinct()) preds.getOrPut(s) { ArrayList() } += b

            // 3. SSA construction. Acyclic, so every predecessor is complete before its successor.
            val registers = code.registersSize
            val firstParam = registers - code.insSize
            val params = m.proto.parameters.indices.map { Param(newValue(), it) }
            val outState = HashMap<Int, Array<Value?>>()
            val built = HashMap<Int, BasicBlock>()
            val conflict = Value(-1, IrType.I32) // register defined on some paths only
            for (b in rpo) {
                val state = arrayOfNulls<Value>(registers)
                val phis = ArrayList<Phi>()
                val ops = ArrayList<Op>()
                if (b == 0) {
                    params.forEachIndexed { i, p -> state[firstParam + i] = p.def }
                    ops += params
                } else {
                    val ps = preds.getValue(b)
                    for (r in 0 until registers) {
                        val incoming = ps.map { outState.getValue(it)[r] }
                        state[r] = when {
                            incoming.all { it == incoming[0] } -> incoming[0]
                            incoming.any { it == null || it == conflict } -> conflict
                            else -> Phi(newValue(), ps.zip(incoming).associate { (p, v) -> p to v!! }).also { phis += it }.def
                        }
                    }
                }
                fun read(r: Int, i: Insn): Value {
                    val v = state[r]
                    if (v == null || v == conflict) throw Gap("register.undefined", "v$r is not defined on every path to %04x".format(i.address))
                    return v
                }
                var terminator: Terminator? = null
                for (i in blockInsns[b]) {
                    val p = prov(i)
                    when (val sem = V0Semantics.manifest.getValue(i.op.mnemonic)) {
                        Sem.Nop -> Unit
                        Sem.Move -> state[i.regs[0]] = read(i.regs[1], i)
                        Sem.Const -> ConstBits(newValue(), i.literal.toInt().toLong(), p).also { ops += it; state[i.regs[0]] = it.def }
                        Sem.Return -> terminator = Return(read(i.regs[0], i), p)
                        Sem.Goto -> terminator = Goto(target(i.target), p)
                        is Sem.IfCmp -> terminator = If(sem.cond, read(i.regs[0], i), read(i.regs[1], i), target(i.target), target(i.next), p)
                        is Sem.IfZero -> terminator = If(sem.cond, read(i.regs[0], i), null, target(i.target), target(i.next), p)
                        is Sem.Un -> Unary(newValue(), sem.kind, read(i.regs[1], i), p).also { ops += it; state[i.regs[0]] = it.def }
                        is Sem.Bin -> Binary(newValue(), sem.kind, read(i.regs[1], i), read(i.regs[2], i), p).also { ops += it; state[i.regs[0]] = it.def }
                        is Sem.Bin2Addr -> Binary(newValue(), sem.kind, read(i.regs[0], i), read(i.regs[1], i), p).also { ops += it; state[i.regs[0]] = it.def }
                        is Sem.BinLit -> {
                            val lit = ConstBits(newValue(), i.literal.toInt().toLong(), p).also { ops += it }
                            val src = read(i.regs[1], i)
                            val bin = if (sem.reverse) Binary(newValue(), BinKind.SUB, lit.def, src, p) else Binary(newValue(), sem.kind, src, lit.def, p)
                            ops += bin
                            state[i.regs[0]] = bin.def
                        }
                    }
                }
                val term = terminator ?: Goto(successors[b].single(), null)
                outState[b] = state
                built[b] = BasicBlock(b, starts[b], phis, ops, term)
            }
            val blocks = pruneDeadPhis(rpo.map { built.getValue(it) })
            val effects = if (blocks.any { b -> b.ops.any { it is Binary && it.kind.mayThrow } }) setOf(EffectKind.THROWS_ARITHMETIC_EXCEPTION) else emptySet()
            val names = runCatching { m.debugInfo?.parameterNames }.getOrNull() ?: emptyList()
            return MethodIr(
                key = m.key,
                signature = MethodSignature(List(m.proto.parameters.size) { IrType.I32 }, IrType.I32, isStatic = true),
                blocks = blocks,
                entry = 0,
                effects = effects,
                assumptions = listOf(V0Semantics.MODEL),
                parameterNames = names,
            )
        }

        private fun pruneDeadPhis(blocks: List<BasicBlock>): List<BasicBlock> {
            var current = blocks
            while (true) {
                val used = HashSet<Value>()
                for (b in current) {
                    for (op in b.phis + b.ops) used += op.uses
                    used += b.terminator.uses
                }
                val next = current.map { b -> b.copy(phis = b.phis.filter { it.def in used }) }
                if (next.sumOf { it.phis.size } == current.sumOf { it.phis.size }) return next
                current = next
            }
        }
    }
}
