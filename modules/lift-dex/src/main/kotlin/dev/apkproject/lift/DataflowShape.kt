package dev.apkproject.lift

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.Disassembler
import dev.apkproject.dex.FillArrayDataPayload
import dev.apkproject.dex.FlowKind
import dev.apkproject.dex.Format
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.dex.Insn
import dev.apkproject.dex.PackedSwitchPayload
import dev.apkproject.dex.SparseSwitchPayload
import java.util.Collections
import java.util.WeakHashMap

enum class RoundTripMatch {
    /** Same operations, constants, references and control flow, and every operand comes from the same definition. */
    EQUIVALENT_DATAFLOW,

    /**
     * The same control-flow graph and dataflow once code layout is factored out: block order, branch
     * polarity (`if (a < b) X else Y` vs `if (a >= b) Y else X`) and shared vs duplicated `return`
     * tails. Compilers and decompilers choose these freely; they do not change behaviour.
     */
    EQUIVALENT_MODULO_LAYOUT,

    /** The same operations (as a multiset), in a different order or with different operand flow. */
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
class DataflowShape private constructor(
    val operations: List<String>,
    val dataflow: List<String>,
    /** Blocks in canonical depth-first order with canonical branches and inlined return tails. */
    val canonical: List<String>,
    /** Layout-free operation multiset (no targets, no returns or gotos), sorted. */
    val operationBag: List<String>,
) {
    /** The same shape with class descriptors renamed (e.g. anonymous classes numbered differently by another compiler). */
    fun renamed(map: Map<String, String>): DataflowShape {
        if (map.isEmpty()) return this
        val re = Regex(map.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        fun r(s: String) = re.replace(s) { map.getValue(it.value) }
        return DataflowShape(operations.map(::r), dataflow.map(::r), canonical.map(::r), operationBag.map(::r).sorted())
    }

    fun compare(other: DataflowShape): RoundTripComparison = when {
        dataflow == other.dataflow -> RoundTripComparison(RoundTripMatch.EQUIVALENT_DATAFLOW, 1.0)
        canonical == other.canonical -> RoundTripComparison(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, 1.0)
        operationBag == other.operationBag -> RoundTripComparison(RoundTripMatch.SIMILAR_OPERATIONS, 1.0)
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
            if (insns.isEmpty()) return DataflowShape(emptyList(), emptyList(), emptyList(), emptyList())
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
            // definition's terminal definitions are computed once (moves through loop merges would
            // otherwise be re-walked exponentially); a move cycle contributes nothing. Parameters are
            // negative, an unknown origin is Int.MIN_VALUE.
            val terminal = HashMap<Int, Set<Int>>()
            val inProgress = HashSet<Int>()
            fun terminals(def: Int): Set<Int> {
                if (def < 0) return setOf(def)
                terminal[def]?.let { return it }
                val i = insns[def]
                if (!isMove(i)) return setOf(def).also { terminal[def] = it }
                if (!inProgress.add(def)) return emptySet()
                val at = defsAt[def]
                val result = if (at == null) setOf(Int.MIN_VALUE) else at[i.regs[1]].flatMapTo(HashSet()) { terminals(it) }
                inProgress.remove(def)
                terminal[def] = result
                return result
            }
            fun origins(set: Set<Int>, label: (Int) -> String): String {
                val all = set.flatMapTo(HashSet()) { terminals(it) }.mapTo(sortedSetOf()) { if (it == Int.MIN_VALUE) "?" else if (it < 0) "p${-1 - it}" else label(it) }
                return when (all.size) {
                    0 -> "undef"
                    1 -> all.first()
                    else -> all.joinToString("|", "phi(", ")")
                }
            }
            val strictLabel = { def: Int -> "d${norm[def]}" }
            val ops = ArrayList<String>()
            val flow = ArrayList<String>()
            for ((k, i) in insns.withIndex()) {
                if (isMove(i)) continue
                val op = opToken(dex, d, i, idx, norm)
                ops += op
                val at = defsAt[k]
                val reads = reads(i)
                flow += if (at == null) "$op <unreachable>" else op + reads.joinToString(",", " (", ")") { r -> origins(at[r], strictLabel) }
            }
            for (t in code.tries) {
                val h = code.handlerFor(t)
                val tok = "try ${idx[t.startAddr]?.let { norm.getOrNull(it) }}..${idx[t.startAddr + t.insnCount]?.let { norm.getOrNull(it) } ?: "end"} " +
                    h.typed.joinToString { "${dex.type(it.first)}->${idx[it.second]?.let { a -> norm[a] }}" } + (if (h.catchAllAddr >= 0) " all->${idx[h.catchAllAddr]?.let { norm[it] }}" else "")
                ops += tok; flow += tok
            }
            val canonical = canonicalForm(dex, d, insns, idx, starts, blockOf, outState, defsAt, ::origins) ?: return null
            val bag = insns.filter { !isMove(it) && !isLayoutOnly(it) && !it.op.mnemonic.startsWith("return") && constValue(dex, it) == null }
                .map { i -> if (i.op.flow == FlowKind.IF) canonicalIf(base(i.op.mnemonic)).first else opToken(dex, d, i, idx, norm, withTargets = false) }
                .sorted()
            return DataflowShape(ops, flow, canonical, bag)
        }

        /**
         * Canonical form of the control-flow graph: blocks are numbered in depth-first order from the
         * entry, conditional branches are rewritten to one of `lt`, `le`, `eq` (`ltz`, `lez`, `eqz`)
         * with operands and successors swapped to match, blocks that only shuffle registers are passed
         * through, and a block that only returns is inlined into every edge that reaches it.
         */
        private fun canonicalForm(
            dex: DexFile,
            d: DecodedCode,
            insns: List<Insn>,
            idx: Map<Int, Int>,
            starts: List<Int>,
            blockOf: IntArray,
            outState: Array<Array<Set<Int>>?>,
            defsAt: Array<Array<Set<Int>>?>,
            origins: (Set<Int>, (Int) -> String) -> String,
        ): List<String>? {
            val code = d.code
            val n = starts.size
            fun end(b: Int) = starts.getOrElse(b + 1) { insns.size }
            val fold = ArrayFolding.of(dex, insns, starts, defsAt)
            // Constants are values, not effects: D8 hoists, shares and rematerialises them freely.
            fun body(b: Int) = (starts[b] until end(b)).filter { !isMove(insns[it]) && !isLayoutOnly(insns[it]) && constValue(dex, insns[it]) == null && it !in fold.folded }
            val handlers = Array(n) { ArrayList<Pair<String, Int>>() }
            for (t in code.tries) {
                val first = idx[t.startAddr] ?: continue
                val last = idx[t.startAddr + t.insnCount] ?: insns.size
                val h = code.handlerFor(t)
                val targets = h.typed.map { dex.type(it.first) to it.second } + listOfNotNull(h.catchAllAddr.takeIf { it >= 0 }?.let { "*" to it })
                for (b in 0 until n) if (starts[b] in first until last && body(b).any { mayThrow(insns[it]) }) {
                    targets.forEach { (type, addr) -> idx[addr]?.let { handlers[b] += type to blockOf[it] } }
                }
            }
            // Normal successors in semantic order: [taken, fall-through] after canonicalisation, switch cases by key then default.
            data class Branch(val op: String, val swapOperands: Boolean, val succ: List<Int>)
            val branch = arrayOfNulls<Branch>(n)
            for (b in 0 until n) {
                val lastIdx = end(b) - 1
                val i = insns[lastIdx]
                val next = if (lastIdx + 1 < insns.size) blockOf[lastIdx + 1] else -1
                branch[b] = when (i.op.flow) {
                    FlowKind.IF -> {
                        val (op, swapOperands, swapTargets) = canonicalIf(base(i.op.mnemonic))
                        val taken = idx[i.target]?.let { blockOf[it] } ?: -1
                        Branch(op, swapOperands, if (swapTargets) listOf(next, taken) else listOf(taken, next))
                    }
                    FlowKind.GOTO -> Branch("goto", false, listOfNotNull(idx[i.target]?.let { blockOf[it] }))
                    FlowKind.SWITCH -> Branch("switch", false, d.switchCases(i).sortedBy { it.first }.map { c -> idx[c.second]?.let { blockOf[it] } ?: -1 } + next)
                    FlowKind.RETURN, FlowKind.THROW -> Branch("end", false, emptyList())
                    FlowKind.NONE -> Branch("fall", false, listOf(next).filter { it >= 0 })
                }
            }
            val transparent = BooleanArray(n) { b -> body(b).isEmpty() && branch[b]!!.succ.size == 1 && handlers[b].isEmpty() }
            val returnTail = BooleanArray(n) { b -> body(b).size == 1 && insns[body(b)[0]].op.flow == FlowKind.RETURN && handlers[b].isEmpty() }
            // Follows pass-through blocks; returns (block, its actual predecessor) or null for a pass-through cycle.
            fun resolve(from: Int, start: Int): Pair<Int, Int>? {
                var pred = from
                var b = start
                val seen = HashSet<Int>()
                while (b >= 0 && transparent[b]) {
                    if (!seen.add(b)) return null
                    pred = b
                    b = branch[b]!!.succ[0]
                }
                return b to pred
            }
            val order = IntArray(n) { -1 }
            val visit = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            val entry = resolve(-1, 0)?.first ?: return null
            stack.addLast(entry)
            while (stack.isNotEmpty()) {
                val b = stack.removeLast()
                if (b < 0 || order[b] >= 0) continue
                order[b] = visit.size
                visit += b
                val next = branch[b]!!.succ.mapNotNull { s -> resolve(b, s)?.first }.filter { it < 0 || !returnTail[it] || it == entry } + handlers[b].map { it.second }
                next.asReversed().forEach { if (it >= 0 && order[it] < 0) stack.addLast(it) }
            }
            if (visit.size > 50_000) return null
            val position = HashMap<Int, String>()
            for (b in visit) body(b).forEachIndexed { k, at -> position[at] = "b${order[b]}.$k" }
            val label = { def: Int -> constValue(dex, insns[def]) ?: fold.constAlias[def] ?: position[fold.alias[def] ?: def] ?: "dead" }
            fun operands(at: Int): List<String> = defsAt[at]?.let { state -> reads(insns[at]).map { r -> origins(state[r], label) } } ?: listOf("<unreachable>")
            fun edge(pred: Int, s: Int): String {
                val (b, actual) = resolve(pred, s) ?: return "loop"
                if (b < 0) return "exit"
                if (!returnTail[b] || b == entry) return "#${order[b]}"
                // Inlined return: the returned value is whatever reaches it along this edge.
                val ret = insns[body(b)[0]]
                if (ret.regs.isEmpty()) return "ret[${ret.op.mnemonic}]"
                // The block holds only moves and constants before the return: walk them backwards.
                var reg = ret.regs[0]
                for (k in (starts[b] until body(b)[0]).reversed()) {
                    val m = insns[k]
                    if (m.regs.isEmpty() || m.regs[0] != reg) continue
                    if (isMove(m)) reg = m.regs[1] else constValue(dex, m)?.let { return "ret[${base(ret.op.mnemonic)} $it]" }
                }
                val state = outState[actual] ?: return "ret[${base(ret.op.mnemonic)} ?]"
                return "ret[${base(ret.op.mnemonic)} ${origins(state[reg], label)}]"
            }
            return visit.map { b ->
                val sb = StringBuilder()
                val br = branch[b]!!
                var tail: String? = null
                for (at in body(b)) {
                    val i = insns[at]
                    if (i.op.flow == FlowKind.RETURN) {
                        // Same spelling as an inlined return tail, so placement does not matter.
                        tail = "ret[" + (if (i.regs.isEmpty()) i.op.mnemonic else "${base(i.op.mnemonic)} ${operands(at).single()}") + "]"
                        continue
                    }
                    if (sb.isNotEmpty()) sb.append("; ")
                    val elements = fold.arrays[at]
                    if (elements != null) {
                        sb.append("array ").append(elements.type).append(elements.values.joinToString(",", " (", ")") { (j, r) -> defsAt[j]?.let { origins(it[r], label) } ?: "?" })
                        continue
                    }
                    when (i.op.flow) {
                        FlowKind.IF -> {
                            var ops = operands(at)
                            if (br.swapOperands) ops = ops.reversed()
                            if (br.op == "if-eq") ops = ops.sorted()
                            sb.append(br.op).append(ops.joinToString(",", " (", ")"))
                        }
                        FlowKind.SWITCH -> sb.append("switch ").append(d.switchCases(i).map { it.first }.sorted()).append(operands(at).joinToString(",", " (", ")"))
                        else -> sb.append(opToken(dex, d, i, idx, IntArray(0), withTargets = false)).append(operands(at).joinToString(",", " (", ")"))
                    }
                }
                if (tail != null) sb.append(" -> ").append(tail)
                else if (br.op != "end") sb.append(" -> ").append(br.succ.joinToString(",") { edge(b, it) })
                if (handlers[b].isNotEmpty()) sb.append(" catch ").append(handlers[b].joinToString(",") { "${it.first}:${edge(b, it.second)}" })
                sb.toString()
            }
        }

        /**
         * (canonical op, swap operands, swap successors) for a conditional branch. Every ordered
         * comparison of two registers has exactly one `lt` spelling (`a >= b` is `!(a < b)`, `a > b`
         * is `b < a`, `a <= b` is `!(b < a)`); zero tests reduce to `ltz`, `gtz` or `eqz`.
         */
        private fun canonicalIf(op: String): Triple<String, Boolean, Boolean> = when (op) {
            "if-ge" -> Triple("if-lt", false, true)
            "if-gt" -> Triple("if-lt", true, false)
            "if-le" -> Triple("if-lt", true, true)
            "if-ne" -> Triple("if-eq", false, true)
            "if-gez" -> Triple("if-ltz", false, true)
            "if-lez" -> Triple("if-gtz", false, true)
            "if-nez" -> Triple("if-eqz", false, true)
            else -> Triple(op, false, false)
        }

        private fun isLayoutOnly(i: Insn) = i.op.flow == FlowKind.GOTO || i.op.mnemonic == "nop"

        /** The value of a pure constant load (numbers and strings; class constants can throw and stay operations). */
        private fun constValue(dex: DexFile, i: Insn): String? {
            val m = i.op.mnemonic
            return when {
                m.startsWith("const-string") -> "\"" + dex.string(i.index).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                m.startsWith("const-wide") -> "#${i.literal}L"
                m == "const" || m.startsWith("const/") -> "#${i.literal}"
                else -> null
            }
        }

        private fun mayThrow(i: Insn): Boolean {
            val m = i.op.mnemonic
            return m.startsWith("invoke") || m.startsWith("iget") || m.startsWith("iput") || m.startsWith("sget") || m.startsWith("sput") ||
                m.startsWith("aget") || m.startsWith("aput") || m.startsWith("new-") || m.startsWith("filled-new") || m == "check-cast" ||
                m.startsWith("div-") || m.startsWith("rem-") || m.startsWith("monitor") || m == "throw" || m == "array-length" ||
                m == "fill-array-data" || m.startsWith("const-class") || m.startsWith("const-method")
        }

        private fun isMove(i: Insn) = i.op.mnemonic.startsWith("move") && !i.op.mnemonic.startsWith("move-result") && i.op.mnemonic != "move-exception"

        private fun throws(i: Insn) = i.op.flow == FlowKind.THROW

        private fun base(m: String): String = m
            .removeSuffix("/range").removeSuffix("/jumbo").removeSuffix("/2addr").removeSuffix("/lit8").removeSuffix("/lit16")
            .removeSuffix("/16").removeSuffix("/32").removeSuffix("/4").removeSuffix("/high16").removeSuffix("/from16")
            .let { if (it == "rsub-int") "rsub-int" else it }

        private fun opToken(dex: DexFile, d: DecodedCode, i: Insn, idx: Map<Int, Int>, norm: IntArray, withTargets: Boolean = true): String {
            val m = base(i.op.mnemonic)
            val sb = StringBuilder(m)
            if (i.op.format in setOf(Format.F11n, Format.F21s, Format.F21h, Format.F31i, Format.F51l, Format.F22b, Format.F22s)) sb.append(" #").append(i.literal)
            val accessor = if (i.op.index == IndexKind.METHOD && m.startsWith("invoke-static")) accessorTarget(dex, i.index) else null
            if (accessor != null) return "$m via $accessor"
            if (!withTargets && (m == "invoke-virtual" || m == "invoke-interface")) {
                // java.lang.Object's methods dispatch on the receiver whatever static type names them.
                val member = Disassembler.insn(dex, i).substringAfter("->", "")
                if (member in OBJECT_METHODS) return "invoke-virtual Ljava/lang/Object;->$member /${i.regs.size}"
            }
            if (i.op.index != IndexKind.NONE) sb.append(' ').append(Disassembler.insn(dex, i).substringAfter("}, ", Disassembler.insn(dex, i).substringAfterLast(", ")))
            if (withTargets && i.target >= 0 && !i.op.payloadRef) sb.append(" @").append(idx[i.target]?.let { norm[it] })
            when (val p = d.payloads[i.target]) {
                is PackedSwitchPayload -> if (withTargets) sb.append(" packed ").append(p.firstKey).append(" -> ").append(d.switchCases(i).map { idx[it.second]?.let { a -> norm[a] } })
                is SparseSwitchPayload -> if (withTargets) sb.append(" sparse ").append(d.switchCases(i).map { "${it.first}:${idx[it.second]?.let { a -> norm[a] }}" })
                is FillArrayDataPayload -> sb.append(" data ").append(p.elementWidth).append('x').append(p.count).append(' ').append(p.data.contentHashCode())
                null -> Unit
            }
            // Arity of invokes and filled arrays without register numbers.
            if (i.op.format in setOf(Format.F35c, Format.F3rc, Format.F45cc, Format.F4rcc)) sb.append(" /").append(i.regs.size)
            return sb.toString()
        }

        private val OBJECT_METHODS = setOf("toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z", "getClass()Ljava/lang/Class;")

        private class AccessorIndex(dex: DexFile) {
            /** Synthetic static methods with code, by method id; everything else is never an accessor. */
            val candidates: Map<Int, DexMethod> = dex.methods()
                .filter { it.accessFlags and AccessFlags.SYNTHETIC != 0 && it.isStatic && it.code != null }
                .associateBy { it.encoded.methodIdx }
            val described = HashMap<Int, String>()
        }

        private val accessorCache: MutableMap<DexFile, AccessorIndex> = Collections.synchronizedMap(WeakHashMap())
        private const val NOT_ACCESSOR = ""

        /**
         * What a synthetic static accessor does, if [methodIdx] names one defined in [dex]: a field
         * read or write, or a forwarded call. javac names these `access$000`, D8's nest desugaring
         * `-$$Nest$fgetX`; neither exists in source, so a call through one is compared as the access
         * it performs.
         */
        fun accessorTarget(dex: DexFile, methodIdx: Int): String? {
            val index = synchronized(accessorCache) { accessorCache.getOrPut(dex) { AccessorIndex(dex) } }
            val m = index.candidates[methodIdx] ?: return null
            synchronized(index) { index.described[methodIdx] }?.let { return it.ifEmpty { null } }
            val target = describeAccessor(dex, m) ?: NOT_ACCESSOR
            synchronized(index) { index.described[methodIdx] = target }
            return target.ifEmpty { null }
        }

        private fun describeAccessor(dex: DexFile, m: DexMethod): String? {
            val code = m.code ?: return null
            val insns = (InstructionDecoder.decode(dex, code) as? Outcome.Ok)?.value?.insns?.filterNot { isMove(it) || it.op.mnemonic.startsWith("move-result") } ?: return null
            if (insns.size != 2 || insns[1].op.flow != FlowKind.RETURN) return null
            val core = insns[0]
            val c = core.op.mnemonic
            val forwards = c.startsWith("iget") || c.startsWith("sget") || c.startsWith("iput") || c.startsWith("sput") || (c.startsWith("invoke") && c != "invoke-custom")
            if (!forwards || core.op.index == IndexKind.NONE) return null
            return base(c) + " " + Disassembler.insn(dex, core).substringAfter("}, ", Disassembler.insn(dex, core).substringAfterLast(", "))
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

/**
 * Layout-free view of array literals and no-op casts. `new T[]{a, b}` compiles to
 * `new-array` + one `aput` per element, or (newer D8) to `filled-new-array`; both become one
 * "array" operation over the element values. `(T) null` compiles to a `check-cast` of the null
 * constant, which can never fail and is dropped.
 */
internal class ArrayFolding private constructor(
    /** Instructions absorbed into another one. */
    val folded: Set<Int>,
    /** Representative instruction -> array literal it stands for. */
    val arrays: Map<Int, Elements>,
    /** A definition whose value is defined at another instruction. */
    val alias: Map<Int, Int>,
    /** A definition that is a known constant. */
    val constAlias: Map<Int, String>,
) {
    class Elements(val type: String, val values: List<Pair<Int, Int>>)

    companion object {
        fun of(dex: DexFile, insns: List<Insn>, starts: List<Int>, defsAt: Array<Array<Set<Int>>?>): ArrayFolding {
            val folded = HashSet<Int>()
            val arrays = HashMap<Int, Elements>()
            val alias = HashMap<Int, Int>()
            val constAlias = HashMap<Int, String>()
            fun constAt(at: Int, reg: Int): Long? {
                val defs = defsAt[at]?.get(reg) ?: return null
                val def = defs.singleOrNull()?.takeIf { it >= 0 } ?: return null
                val i = insns[def]
                val m = i.op.mnemonic
                return if (m == "const" || m.startsWith("const/")) i.literal else null
            }
            fun type(i: Insn) = Disassembler.insn(dex, i).substringAfterLast(", ")
            for ((b, start) in starts.withIndex()) {
                val end = starts.getOrElse(b + 1) { insns.size }
                for (k in start until end) {
                    val i = insns[k]
                    val m = i.op.mnemonic
                    when {
                        m == "check-cast" && constAt(k, i.regs[0]) == 0L -> {
                            folded += k; constAlias[k] = "#0"
                        }
                        m.startsWith("filled-new-array") -> {
                            arrays[k] = Elements(type(i), i.regs.map { k to it })
                            val next = k + 1
                            if (next < end && insns[next].op.mnemonic == "move-result-object") {
                                folded += next; alias[next] = k
                            }
                        }
                        m == "new-array" -> {
                            val n = constAt(k, i.regs[1])?.takeIf { it in 1..255 }?.toInt() ?: continue
                            val values = arrayOfNulls<Pair<Int, Int>>(n)
                            val aputs = ArrayList<Int>()
                            var ok = true
                            var j = k + 1
                            while (j < end && aputs.size < n && ok) {
                                val x = insns[j]
                                val state = defsAt[j]
                                val readsArray = state != null && DataflowShape.reads(x).withIndex().any { (pos, r) -> k in state[r] && !(x.op.mnemonic.startsWith("aput") && pos == 1) }
                                if (x.op.mnemonic.startsWith("aput") && state != null && state[x.regs[1]] == setOf(k)) {
                                    val index = constAt(j, x.regs[2])?.toInt()
                                    if (index == null || index !in 0 until n || values[index] != null) ok = false else {
                                        values[index] = j to x.regs[0]; aputs += j
                                    }
                                } else if (readsArray && !(x.op.mnemonic.startsWith("move") && !x.op.mnemonic.startsWith("move-result"))) {
                                    ok = false
                                }
                                j++
                            }
                            if (!ok || aputs.size != n) continue
                            val last = aputs.last()
                            folded += k
                            folded += aputs.dropLast(1)
                            arrays[last] = Elements(type(i), values.map { it!! })
                            alias[k] = last
                        }
                    }
                }
            }
            return ArrayFolding(folded, arrays, alias, constAlias)
        }
    }
}
