package dev.apkproject.ir

import dev.apkproject.contracts.ByteRange
import dev.apkproject.contracts.CapabilityGap
import dev.apkproject.contracts.MethodKey

/**
 * Semantic IR (dossier section 13). V0 models 32-bit integers only; every other type is a
 * capability gap until its semantics are implemented.
 */
enum class IrType { I32 }

/** Where an operation came from: DEX code-unit address and file byte range. */
data class Provenance(val address: Int, val range: ByteRange?)

/** An SSA value. Identity is the id; values are defined exactly once. */
data class Value(val id: Int, val type: IrType) {
    override fun toString(): String = "%$id"
}

enum class BinKind(val symbol: String, val mayThrow: Boolean = false) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/", mayThrow = true), REM("%", mayThrow = true),
    AND("&"), OR("|"), XOR("^"), SHL("<<"), SHR(">>"), USHR(">>>"),
}

enum class UnKind { NEG, NOT, TO_BYTE, TO_CHAR, TO_SHORT }

/** Signed 32-bit comparisons used by Dalvik if-* instructions. */
enum class Cond(val symbol: String) {
    EQ("=="), NE("!="), LT("<"), GE(">="), GT(">"), LE("<=");

    fun negate(): Cond = when (this) {
        EQ -> NE; NE -> EQ; LT -> GE; GE -> LT; GT -> LE; LE -> GT
    }

    /** Condition with operands swapped: a < b  <=>  b > a. */
    fun swap(): Cond = when (this) {
        EQ -> EQ; NE -> NE; LT -> GT; GT -> LT; GE -> LE; LE -> GE
    }

    fun test(a: Int, b: Int): Boolean = when (this) {
        EQ -> a == b; NE -> a != b; LT -> a < b; GE -> a >= b; GT -> a > b; LE -> a <= b
    }
}

enum class EffectKind { THROWS_ARITHMETIC_EXCEPTION }

sealed class Op {
    abstract val def: Value
    abstract val uses: List<Value>
    abstract val provenance: Provenance?
}

data class Param(override val def: Value, val index: Int) : Op() {
    override val uses: List<Value> get() = emptyList()
    override val provenance: Provenance? get() = null
}

data class ConstBits(override val def: Value, val bits: Long, override val provenance: Provenance?) : Op() {
    override val uses: List<Value> get() = emptyList()
    val int: Int get() = bits.toInt()
}

data class Binary(override val def: Value, val kind: BinKind, val a: Value, val b: Value, override val provenance: Provenance?) : Op() {
    override val uses: List<Value> get() = listOf(a, b)
}

data class Unary(override val def: Value, val kind: UnKind, val a: Value, override val provenance: Provenance?) : Op() {
    override val uses: List<Value> get() = listOf(a)
}

/** Phi: value depends on the predecessor block control arrived from. */
data class Phi(override val def: Value, val incoming: Map<Int, Value>) : Op() {
    override val uses: List<Value> get() = incoming.values.toList()
    override val provenance: Provenance? get() = null
}

sealed class Terminator {
    abstract val provenance: Provenance?
    abstract val successors: List<Int>
    abstract val uses: List<Value>
}

data class Return(val value: Value, override val provenance: Provenance?) : Terminator() {
    override val successors: List<Int> get() = emptyList()
    override val uses: List<Value> get() = listOf(value)
}

data class Goto(val target: Int, override val provenance: Provenance?) : Terminator() {
    override val successors: List<Int> get() = listOf(target)
    override val uses: List<Value> get() = emptyList()
}

/** `if (a cond b) goto ifTrue else ifFalse`; [b] null means comparison against zero. */
data class If(val cond: Cond, val a: Value, val b: Value?, val ifTrue: Int, val ifFalse: Int, override val provenance: Provenance?) : Terminator() {
    override val successors: List<Int> get() = listOf(ifTrue, ifFalse)
    override val uses: List<Value> get() = listOfNotNull(a, b)
}

data class BasicBlock(
    val id: Int,
    val startAddress: Int,
    val phis: List<Phi>,
    val ops: List<Op>,
    val terminator: Terminator,
)

data class MethodSignature(val params: List<IrType>, val ret: IrType, val isStatic: Boolean)

data class MethodIr(
    val key: MethodKey,
    val signature: MethodSignature,
    val blocks: List<BasicBlock>,
    val entry: Int,
    val effects: Set<EffectKind>,
    val assumptions: List<String>,
    /** Observed parameter names from debug info, when present. */
    val parameterNames: List<String?>,
) {
    val blockById: Map<Int, BasicBlock> = blocks.associateBy { it.id }

    fun block(id: Int): BasicBlock = blockById.getValue(id)

    fun predecessors(): Map<Int, List<Int>> {
        val out = blocks.associate { it.id to ArrayList<Int>() }
        for (b in blocks) for (s in b.terminator.successors.distinct()) out.getValue(s) += b.id
        return out
    }

    /** All definitions, for lookups by value. */
    fun definitions(): Map<Value, Op> = blocks.flatMap { it.phis + it.ops }.associateBy { it.def }

    fun useCounts(): Map<Value, Int> {
        val counts = HashMap<Value, Int>()
        for (b in blocks) {
            for (op in b.phis + b.ops) for (u in op.uses) counts.merge(u, 1, Int::plus)
            for (u in b.terminator.uses) counts.merge(u, 1, Int::plus)
        }
        return counts
    }

    /** Reverse postorder from the entry; blocks unreachable from the entry are omitted. */
    fun reversePostorder(): List<Int> {
        val seen = HashSet<Int>()
        val post = ArrayList<Int>()
        fun dfs(id: Int) {
            if (!seen.add(id)) return
            for (s in block(id).terminator.successors) dfs(s)
            post += id
        }
        dfs(entry)
        return post.reversed()
    }

    fun render(): String = IrPrinter.render(this)
}

data class LiftResult(
    val key: MethodKey,
    val ir: MethodIr?,
    val gaps: List<CapabilityGap>,
) {
    val supported: Boolean get() = ir != null && gaps.isEmpty()
}

object IrPrinter {
    fun render(m: MethodIr): String {
        val sb = StringBuilder()
        sb.append("method ").append(m.key.display()).append('\n')
        for (id in m.reversePostorder()) {
            val b = m.block(id)
            sb.append("  b").append(b.id).append(" @%04x:\n".format(b.startAddress))
            for (p in b.phis) sb.append("    ").append(p.def).append(" = phi ").append(p.incoming.entries.sortedBy { it.key }.joinToString { "b${it.key}: ${it.value}" }).append('\n')
            for (op in b.ops) sb.append("    ").append(op(op)).append('\n')
            sb.append("    ").append(term(b.terminator)).append('\n')
        }
        return sb.toString()
    }

    private fun op(o: Op): String = when (o) {
        is Param -> "${o.def} = param ${o.index}"
        is ConstBits -> "${o.def} = const ${o.int}"
        is Binary -> "${o.def} = ${o.kind.name.lowercase()} ${o.a}, ${o.b}"
        is Unary -> "${o.def} = ${o.kind.name.lowercase()} ${o.a}"
        is Phi -> "${o.def} = phi"
    }

    private fun term(t: Terminator): String = when (t) {
        is Return -> "return ${t.value}"
        is Goto -> "goto b${t.target}"
        is If -> "if ${t.a} ${t.cond.symbol} ${t.b ?: "0"} then b${t.ifTrue} else b${t.ifFalse}"
    }
}
