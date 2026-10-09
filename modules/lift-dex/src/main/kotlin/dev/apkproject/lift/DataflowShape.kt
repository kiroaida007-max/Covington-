package dev.apkproject.lift

import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.Disassembler
import dev.apkproject.dex.FillArrayDataPayload
import dev.apkproject.dex.FlowKind
import dev.apkproject.dex.Format
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.Insn
import dev.apkproject.dex.PackedSwitchPayload
import dev.apkproject.dex.SparseSwitchPayload

enum class RoundTripMatch {
    /** Same operations, constants, references and control flow, and every operand comes from the same definition. */
    EQUIVALENT_DATAFLOW,

    /** Same normalised operation sequence, but some operand comes from a different definition. */
    SIMILAR_OPERATIONS,

    /** Different operation sequences. */
    DIFFERENT,
}

data class RoundTripComparison(val match: RoundTripMatch, val similarity: Double) {
    override fun toString() = if (match == RoundTripMatch.DIFFERENT) "$match(${"%.2f".format(similarity)})" else match.name
}

/**
 * A register-allocation-independent fingerprint of a whole method, for every opcode (not only the
 * V0 subset). Moves and register numbers are erased; each operand is replaced by the position of
 * the instruction that defined its value (copy-propagated through moves), a parameter index, or the
 * set of definitions that merge at that point. Two methods with equal fingerprints perform the same
 * operations on the same values in the same order, with the same constants, members and control
 * flow; this is strong round-trip evidence, not a proof.
 */
class DataflowShape private constructor(val operations: List<String>, val dataflow: List<String>) {
    fun compare(other: DataflowShape): RoundTripComparison = when {
        dataflow == other.dataflow -> RoundTripComparison(RoundTripMatch.EQUIVALENT_DATAFLOW, 1.0)
        operations == other.operations -> RoundTripComparison(RoundTripMatch.SIMILAR_OPERATIONS, 1.0)
        else -> {
            val longest = maxOf(operations.size, other.operations.size).coerceAtLeast(1)
            RoundTripComparison(RoundTripMatch.DIFFERENT, 1.0 - CodeShape.editDistance(operations, other.operations).toDouble() / longest)
        }
    }

    companion object {
        private const val MAX_INSNS = 30_000

        fun of(dex: DexFile, d: DecodedCode): DataflowShape? {
            val insns = d.insns
            if (insns.size > MAX_INSNS) return null
            val code = d.code
            val regs = code.registersSize
            val idx = insns.withIndex().associate { it.value.address to it.index }

            // Basic blocks: leaders at entry, branch/switch targets, handlers, try boundaries and after control transfers.
            val leaders = sortedSetOf(0)
            for ((k, i) in insns.withIndex()) {
                if (i.target >= 0 && !i.op.payloadRef) idx[i.target]?.let { leaders += it }
                if (i.op.flow == FlowKind.SWITCH) d.switchCases(i).forEach { (_, t) -> idx[t]?.let { leaders += it } }
                if (i.op.flow != FlowKind.NONE || i.op.mnemonic.startsWith("invoke") || throws(i)) if (k + 1 < insns.size) leaders += k + 1
            }
            for (t in code.tries) {
                idx[t.startAddr]?.let { leaders += it }
                idx[t.startAddr + t.insnCount]?.let { leaders += it }
                val h = code.handlerFor(t)
                (h.typed.map { it.second } + listOfNotNull(h.catchAllAddr.takeIf { it >= 0 })).forEach { a -> idx[a]?.let { leaders += it } }
            }
            val starts = leaders.toList()
            val blockOf = IntArray(insns.size)
            for (b in starts.indices) for (k in starts[b] until starts.getOrElse(b + 1) { insns.size }) blockOf[k] = b
            val succ = Array(starts.size) { HashSet<Int>() }
            for (b in starts.indices) {
                val last = starts.getOrElse(b + 1) { insns.size } - 1
                val i = insns[last]
                val fallsThrough = i.op.flow !in setOf(FlowKind.GOTO, FlowKind.RETURN, FlowKind.THROW)
                if (fallsThrough && last + 1 < insns.size) succ[b] += blockOf[last + 1]
                if (i.target >= 0 && !i.op.payloadRef) idx[i.target]?.let { succ[b] += blockOf[it] }
                if (i.op.flow == FlowKind.SWITCH) d.switchCases(i).forEach { (_, t) -> idx[t]?.let { succ[b] += blockOf[it] } }
                // Exceptional edges from every block inside a try range.
                for (t in code.tries) {
                    val first = idx[t.startAddr] ?: continue
                    val end = idx[t.startAddr + t.insnCount] ?: insns.size
                    if (starts[b] in first until end) {
                        val h = code.handlerFor(t)
                        (h.typed.map { it.second } + listOfNotNull(h.catchAllAddr.takeIf { it >= 0 })).forEach { a -> idx[a]?.let { succ[b] += blockOf[it] } }
                    }
                }
            }

            // Reaching definitions per register; a definition is an instruction index, params are -1 - k.
            val firstParam = regs - code.insSize
            fun paramDef(r: Int) = -1 - (r - firstParam)
            if (insns.isEmpty()) return DataflowShape(emptyList(), emptyList())
            val entryDefs = Array<Set<Int>>(regs) { r -> if (r >= firstParam) setOf(paramDef(r)) else emptySet() }
            val inState = arrayOfNulls<Array<Set<Int>>>(starts.size)
            inState[0] = entryDefs
            val work = ArrayDeque(listOf(0))
            val outState = arrayOfNulls<Array<Set<Int>>>(starts.size)
            var iterations = 0
            while (work.isNotEmpty()) {
                if (++iterations > 200_000) return null
                val b = work.removeFirst()
                val state = inState[b]!!.copyOf()
                for (k in starts[b] until starts.getOrElse(b + 1) { insns.size }) for (r in writes(insns[k], regs)) state[r] = setOf(k)
                @Suppress("UNCHECKED_CAST")
                val out = state as Array<Set<Int>>
                if (outState[b] != null && outState[b]!!.contentEquals(out)) continue
                outState[b] = out
                for (s in succ[b]) {
                    val cur = inState[s]
                    val merged = if (cur == null) out.copyOf() else Array(regs) { r -> if (cur[r].containsAll(out[r])) cur[r] else cur[r] + out[r] }
                    if (cur == null || !cur.contentEquals(merged)) {
                        inState[s] = merged
                        work += s
                    }
                }
            }

            // Normalised positions skip moves, which only shuffle registers.
            val norm = IntArray(insns.size) { -1 }
            var n = 0
            for ((k, i) in insns.withIndex()) if (!isMove(i)) norm[k] = n++
            val defsAt = arrayOfNulls<Array<Set<Int>>>(insns.size)
            for (b in starts.indices) {
                val state = (inState[b] ?: continue).copyOf()
                for (k in starts[b] until starts.getOrElse(b + 1) { insns.size }) {
                    @Suppress("UNCHECKED_CAST")
                    defsAt[k] = state.copyOf() as Array<Set<Int>>
                    for (r in writes(insns[k], regs)) state[r] = setOf(k)
                }
            }
            // Value origins of a set of reaching definitions, copy-propagated through moves. Each
            // definition's terminal origins are computed once (moves through loop merges would
            // otherwise be re-walked exponentially); a move cycle contributes nothing.
            val terminal = HashMap<Int, Set<String>>()
            val inProgress = HashSet<Int>()
            fun terminals(def: Int): Set<String> {
                if (def < 0) return setOf("p${-1 - def}")
                terminal[def]?.let { return it }
                val i = insns[def]
                if (!isMove(i)) return setOf("d${norm[def]}").also { terminal[def] = it }
                if (!inProgress.add(def)) return emptySet()
                val at = defsAt[def]
                val result = if (at == null) setOf("?") else at[i.regs[1]].flatMapTo(HashSet()) { terminals(it) }
                inProgress.remove(def)
                terminal[def] = result
                return result
            }
            fun origins(set: Set<Int>): String {
                val all = set.flatMapTo(sortedSetOf()) { terminals(it) }
                return when (all.size) {
                    0 -> "undef"
                    1 -> all.first()
                    else -> all.joinToString("|", "phi(", ")")
                }
            }
            val ops = ArrayList<String>()
            val flow = ArrayList<String>()
            for ((k, i) in insns.withIndex()) {
                if (isMove(i)) continue
                val op = opToken(dex, d, i, idx, norm)
                ops += op
                val at = defsAt[k]
                val reads = reads(i)
                flow += if (at == null) "$op <unreachable>" else op + reads.joinToString(",", " (", ")") { r -> origins(at[r]) }
            }
            for (t in code.tries) {
                val h = code.handlerFor(t)
                val tok = "try ${idx[t.startAddr]?.let { norm.getOrNull(it) }}..${idx[t.startAddr + t.insnCount]?.let { norm.getOrNull(it) } ?: "end"} " +
                    h.typed.joinToString { "${dex.type(it.first)}->${idx[it.second]?.let { a -> norm[a] }}" } + (if (h.catchAllAddr >= 0) " all->${idx[h.catchAllAddr]?.let { norm[it] }}" else "")
                ops += tok; flow += tok
            }
            return DataflowShape(ops, flow)
        }

        private fun isMove(i: Insn) = i.op.mnemonic.startsWith("move") && !i.op.mnemonic.startsWith("move-result") && i.op.mnemonic != "move-exception"

        private fun throws(i: Insn) = i.op.flow == FlowKind.THROW

        private fun base(m: String): String = m
            .removeSuffix("/range").removeSuffix("/jumbo").removeSuffix("/2addr").removeSuffix("/lit8").removeSuffix("/lit16")
            .removeSuffix("/16").removeSuffix("/32").removeSuffix("/4").removeSuffix("/high16").removeSuffix("/from16")
            .let { if (it == "rsub-int") "rsub-int" else it }

        private fun opToken(dex: DexFile, d: DecodedCode, i: Insn, idx: Map<Int, Int>, norm: IntArray): String {
            val m = base(i.op.mnemonic)
            val sb = StringBuilder(m)
            if (i.op.format in setOf(Format.F11n, Format.F21s, Format.F21h, Format.F31i, Format.F51l, Format.F22b, Format.F22s)) sb.append(" #").append(i.literal)
            if (i.op.index != IndexKind.NONE) sb.append(' ').append(Disassembler.insn(dex, i).substringAfter("}, ", Disassembler.insn(dex, i).substringAfterLast(", ")))
            if (i.target >= 0 && !i.op.payloadRef) sb.append(" @").append(idx[i.target]?.let { norm[it] })
            when (val p = d.payloads[i.target]) {
                is PackedSwitchPayload -> sb.append(" packed ").append(p.firstKey).append(" -> ").append(d.switchCases(i).map { idx[it.second]?.let { a -> norm[a] } })
                is SparseSwitchPayload -> sb.append(" sparse ").append(d.switchCases(i).map { "${it.first}:${idx[it.second]?.let { a -> norm[a] }}" })
                is FillArrayDataPayload -> sb.append(" data ").append(p.elementWidth).append('x').append(p.count).append(' ').append(p.data.contentHashCode())
                null -> Unit
            }
            // Arity of invokes and filled arrays without register numbers.
            if (i.op.format in setOf(Format.F35c, Format.F3rc, Format.F45cc, Format.F4rcc)) sb.append(" /").append(i.regs.size)
            return sb.toString()
        }

        /** Registers an instruction writes (both halves of a wide pair). */
        fun writes(i: Insn, regs: Int): List<Int> {
            val m = i.op.mnemonic
            val writesFirst = when {
                i.regs.isEmpty() -> false
                m.startsWith("return") || m.startsWith("if-") || m.startsWith("goto") || m.startsWith("invoke") -> false
                m.startsWith("aput") || m.startsWith("iput") || m.startsWith("sput") -> false
                m in setOf("monitor-enter", "monitor-exit", "throw", "fill-array-data", "packed-switch", "sparse-switch", "filled-new-array", "filled-new-array/range", "nop") -> false
                else -> true
            }
            if (!writesFirst) return emptyList()
            val r = i.regs[0]
            val wide = i.op.wide.firstOrNull() == 'W'
            return if (wide && r + 1 < regs) listOf(r, r + 1) else listOf(r)
        }

        /** Value-carrying registers an instruction reads (the low register of a wide pair). */
        fun reads(i: Insn): List<Int> {
            val m = i.op.mnemonic
            return when {
                i.regs.isEmpty() -> emptyList()
                m.startsWith("move-result") || m == "move-exception" -> emptyList()
                m.startsWith("const") || m == "new-instance" || m.startsWith("sget") || m.startsWith("goto") -> emptyList()
                m.endsWith("/2addr") -> i.regs.toList()
                m.startsWith("return") || m.startsWith("if-") || m.startsWith("invoke") || m.startsWith("filled-new-array") -> i.regs.toList()
                m.startsWith("aput") || m.startsWith("iput") || m.startsWith("sput") -> i.regs.toList()
                m in setOf("monitor-enter", "monitor-exit", "throw", "fill-array-data", "packed-switch", "sparse-switch", "check-cast") -> listOf(i.regs[0])
                else -> i.regs.drop(1)
            }
        }
    }
}
