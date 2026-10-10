package dev.apkproject.validate

import dev.apkproject.ir.BinKind
import dev.apkproject.ir.Binary
import dev.apkproject.ir.Cond
import dev.apkproject.ir.ConstBits
import dev.apkproject.ir.Goto
import dev.apkproject.ir.If
import dev.apkproject.ir.MethodIr
import dev.apkproject.ir.Op
import dev.apkproject.ir.Param
import dev.apkproject.ir.Phi
import dev.apkproject.ir.Return
import dev.apkproject.ir.UnKind
import dev.apkproject.ir.Unary
import dev.apkproject.ir.Value

/**
 * Encodes acyclic V0 IR as SMT-LIB 2 bit-vector terms (dossier section 19). This is an
 * independent implementation of the V0 semantics; it shares no code with [dev.apkproject.ir.IrEvaluator].
 *
 * For each block: `in` = reached without an exception, `out` = `in` and no division in the block
 * hit a zero divisor. A method's observable outcome is (threw, result).
 */
object SmtEncoder {
    const val MODEL_BOUNDS = "QF_BV, 32-bit parameters; complete for acyclic V0 IR (no loops, heap, calls or floating point); " +
        "exceptional outcome modelled as ArithmeticException on a zero divisor"

    class Encoded(val definitions: String, val threw: String, val result: String)

    fun params(n: Int): List<String> = List(n) { "p$it" }

    fun encode(ir: MethodIr, prefix: String): Encoded {
        require(ir.signature.params.size == params(ir.signature.params.size).size)
        val sb = StringBuilder()
        val preds = ir.predecessors()
        val order = ir.reversePostorder()
        fun v(x: Value) = "${prefix}v${x.id}"
        fun inB(b: Int) = "${prefix}in_b$b"
        fun outB(b: Int) = "${prefix}out_b$b"
        fun cond(t: If): String {
            val a = v(t.a)
            val b = t.b?.let(::v) ?: "#x00000000"
            return when (t.cond) {
                Cond.EQ -> "(= $a $b)"; Cond.NE -> "(not (= $a $b))"; Cond.LT -> "(bvslt $a $b)"
                Cond.GE -> "(bvsge $a $b)"; Cond.GT -> "(bvsgt $a $b)"; Cond.LE -> "(bvsle $a $b)"
            }
        }
        fun edge(p: Int, b: Int): String = when (val t = ir.block(p).terminator) {
            is Goto -> "true"
            is If -> if (t.ifTrue == t.ifFalse) "true" else if (b == t.ifTrue) cond(t) else "(not ${cond(t)})"
            is Return -> error("return block b$p has no successors")
        }
        fun taken(p: Int, b: Int) = "(and ${outB(p)} ${edge(p, b)})"
        val throws = ArrayList<String>()
        val returns = ArrayList<Pair<String, String>>()
        for (b in order) {
            val block = ir.block(b)
            val live = if (b == ir.entry) "true" else or(preds.getValue(b).distinct().map { taken(it, b) })
            sb.append("(define-fun ${inB(b)} () Bool $live)\n")
            for (phi in block.phis) {
                val incoming = phi.incoming.entries.sortedBy { it.key }
                var term = v(incoming.last().value)
                for ((p, value) in incoming.dropLast(1).reversed()) term = "(ite ${taken(p, b)} ${v(value)} $term)"
                sb.append("(define-fun ${v(phi.def)} () (_ BitVec 32) $term)\n")
            }
            val zeroDivisors = ArrayList<String>()
            for (op in block.ops) {
                sb.append("(define-fun ${v(op.def)} () (_ BitVec 32) ${term(op, ::v)})\n")
                if (op is Binary && op.kind.mayThrow) zeroDivisors += "(= ${v(op.b)} #x00000000)"
            }
            val anyZero = or(zeroDivisors)
            sb.append("(define-fun ${prefix}throw_b$b () Bool (and ${inB(b)} $anyZero))\n")
            sb.append("(define-fun ${outB(b)} () Bool (and ${inB(b)} (not $anyZero)))\n")
            throws += "${prefix}throw_b$b"
            (block.terminator as? Return)?.let { returns += outB(b) to v(it.value) }
        }
        require(returns.isNotEmpty()) { "method has no return" }
        var result = returns.last().second
        for ((guard, value) in returns.dropLast(1).reversed()) result = "(ite $guard $value $result)"
        sb.append("(define-fun ${prefix}threw () Bool ${or(throws)})\n")
        sb.append("(define-fun ${prefix}result () (_ BitVec 32) $result)\n")
        return Encoded(sb.toString(), "${prefix}threw", "${prefix}result")
    }

    private fun or(terms: List<String>): String = when (terms.size) {
        0 -> "false"
        1 -> terms[0]
        else -> terms.joinToString(" ", "(or ", ")")
    }

    fun literal(x: Int): String = "#x%08x".format(x)

    private fun term(op: Op, v: (Value) -> String): String = when (op) {
        is Param -> "p${op.index}"
        is ConstBits -> literal(op.int)
        is Unary -> when (op.kind) {
            UnKind.NEG -> "(bvneg ${v(op.a)})"
            UnKind.NOT -> "(bvnot ${v(op.a)})"
            UnKind.TO_BYTE -> "((_ sign_extend 24) ((_ extract 7 0) ${v(op.a)}))"
            UnKind.TO_CHAR -> "((_ zero_extend 16) ((_ extract 15 0) ${v(op.a)}))"
            UnKind.TO_SHORT -> "((_ sign_extend 16) ((_ extract 15 0) ${v(op.a)}))"
        }
        is Binary -> {
            val a = v(op.a)
            val b = v(op.b)
            when (op.kind) {
                BinKind.ADD -> "(bvadd $a $b)"; BinKind.SUB -> "(bvsub $a $b)"; BinKind.MUL -> "(bvmul $a $b)"
                // SMT-LIB defines bvsdiv/bvsrem for every input, including MIN/-1 (wraps) and a zero
                // divisor (unused: the throw guard dominates the result).
                BinKind.DIV -> "(bvsdiv $a $b)"; BinKind.REM -> "(bvsrem $a $b)"
                BinKind.AND -> "(bvand $a $b)"; BinKind.OR -> "(bvor $a $b)"; BinKind.XOR -> "(bvxor $a $b)"
                BinKind.SHL -> "(bvshl $a (bvand $b #x0000001f))"
                BinKind.SHR -> "(bvashr $a (bvand $b #x0000001f))"
                BinKind.USHR -> "(bvlshr $a (bvand $b #x0000001f))"
            }
        }
        is Phi -> error("phi handled separately")
    }

    private fun header(n: Int): String = buildString {
        append("(set-logic QF_BV)\n")
        params(n).forEach { append("(declare-const $it (_ BitVec 32))\n") }
    }

    /** Is there a supported input on which the two methods' outcomes differ? `unsat` means no. */
    fun equivalenceQuery(original: MethodIr, candidate: MethodIr): String {
        val n = original.signature.params.size
        require(candidate.signature.params.size == n) { "arity differs" }
        val o = encode(original, "o_")
        val c = encode(candidate, "c_")
        return buildString {
            append("; obligation: candidate outcome equals original outcome for every input\n")
            append(header(n))
            append(o.definitions)
            append(c.definitions)
            append("(assert (not (and (= ${o.threw} ${c.threw}) (or ${o.threw} (= ${o.result} ${c.result})))))\n")
            append("(check-sat)\n")
            if (n > 0) append("(get-value (${params(n).joinToString(" ")}))\n")
        }
    }

    /** Evaluates the encoding at concrete inputs; used to cross-check against the IR evaluator. */
    fun evaluationScript(ir: MethodIr, inputs: List<IntArray>): String {
        val n = ir.signature.params.size
        val e = encode(ir, "m_")
        return buildString {
            append(header(n))
            append(e.definitions)
            for (args in inputs) {
                append("(push 1)\n")
                args.forEachIndexed { i, x -> append("(assert (= p$i ${literal(x)}))\n") }
                append("(check-sat)\n(get-value (${e.threw} ${e.result}))\n(pop 1)\n")
            }
        }
    }
}
