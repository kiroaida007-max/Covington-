package dev.apkproject.reconstruct

import dev.apkproject.contracts.ByteRange
import dev.apkproject.contracts.RuleApplication
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
import dev.apkproject.source.JBinOp
import dev.apkproject.source.JExpr
import dev.apkproject.source.JMethod
import dev.apkproject.source.JParam
import dev.apkproject.source.JStmt
import dev.apkproject.source.JUnOp
import dev.apkproject.source.JavaNames

/** Choices that change the produced source but not its meaning; the experiments module varies them. */
data class ReconstructOptions(
    /**
     * Blocks whose `if` keeps the DEX condition and puts the branch target first. By default the
     * condition is negated and the fall-through path comes first, which mirrors javac's layout.
     */
    val invertedIfs: Set<Int> = emptySet(),
    val ternary: Boolean = true,
    val flattenElseAfterReturn: Boolean = true,
    val maxStatements: Int = 2_000,
) {
    val label: String get() = buildString {
        append(if (ternary) "ternary" else "if-else")
        if (invertedIfs.isNotEmpty()) append(",inverted=").append(invertedIfs.sorted().joinToString("+") { "b$it" })
    }
}

data class ReconstructedMethod(
    val ir: MethodIr,
    val method: JMethod,
    val rules: List<RuleApplication>,
    val options: ReconstructOptions,
    val observedParameterNames: Boolean,
    /** Blocks ending in a conditional branch: the polarity choices available to experiments. */
    val branchBlocks: List<Int>,
)

class ReconstructException(val code: String, message: String) : Exception(message)

/**
 * Rule-based IR -> Java reconstruction for acyclic V0 methods (dossier section 15).
 *
 * Structuring uses immediate post-dominators: an `if` at block b is closed at ipdom(b), whose phis
 * become locals assigned on each incoming edge. A block with several predecessors that is not a
 * join is duplicated along each path, with its phis replaced by the value of the edge taken.
 */
object Reconstructor {
    fun reconstruct(ir: MethodIr, javaName: String, modifiers: List<String>, options: ReconstructOptions = ReconstructOptions()): ReconstructedMethod =
        Session(ir, javaName, modifiers, options).run()

    private class Session(val ir: MethodIr, val javaName: String, val modifiers: List<String>, val options: ReconstructOptions) {
        val defs: Map<Value, Op> = ir.definitions()
        val defBlock: Map<Value, Int> = ir.blocks.flatMap { b -> (b.phis + b.ops).map { it.def to b.id } }.toMap()
        val uses = ir.useCounts()
        val preds = ir.predecessors()
        val ipdom = postDominators()
        val localNames = HashMap<Value, String>()
        val declaredJoins = HashSet<Int>()
        val rules = ArrayList<RuleApplication>()
        var statements = 0
        var nextLocal = 0

        val paramNames: List<String>
        val observedNames: Boolean

        init {
            val observed = ir.parameterNames
            val legal = observed.size == ir.signature.params.size &&
                observed.all { it != null && JavaNames.isLegalIdentifier(it) } && observed.toSet().size == observed.size
            observedNames = legal
            paramNames = if (legal) observed.map { it!! } else List(ir.signature.params.size) { "p$it" }
            rules += RuleApplication(if (legal) "names.observed-parameters.v1" else "names.synthetic-parameters.v1", "params")
        }

        fun run(): ReconstructedMethod {
            var body = structure(ir.entry, null, emptyMap(), -1)
            body = Rewrites(options, rules).apply(body)
            val method = JMethod(
                modifiers = modifiers,
                returnType = "int",
                name = javaName,
                params = paramNames.map { JParam("int", it) },
                body = body,
                methodId = ir.key.display(),
            )
            val branches = ir.blocks.filter { it.terminator is If }.map { it.id }
            return ReconstructedMethod(ir, method, rules.distinct(), options, observedNames, branches)
        }

        /** Immediate post-dominators on the acyclic CFG; null means the virtual exit. */
        private fun postDominators(): Map<Int, Int?> {
            val order = ir.reversePostorder().reversed() // successors before predecessors
            val pdom = HashMap<Int, Set<Int>>()
            for (b in order) {
                val succ = ir.block(b).terminator.successors.distinct()
                val common = if (succ.isEmpty()) emptySet() else succ.map { pdom.getValue(it) }.reduce { a, c -> a intersect c }
                pdom[b] = common + b
            }
            return pdom.mapValues { (b, set) -> (set - b).maxByOrNull { pdom.getValue(it).size } }
        }

        private fun budget() {
            if (++statements > options.maxStatements) throw ReconstructException("structure.budget", "structured output exceeds ${options.maxStatements} statements")
        }

        private fun freshLocal(v: Value): String = localNames.getOrPut(v) { "t${nextLocal++}" }

        private fun structure(b: Int, stop: Int?, subst: Map<Value, Value>, from: Int): List<JStmt> {
            val block = ir.block(b)
            var ctx = subst
            if (from >= 0 && block.phis.isNotEmpty() && b !in declaredJoins) {
                // Entered along one specific edge: each phi is just that edge's value.
                ctx = ctx + block.phis.associate { it.def to it.incoming.getValue(from) }
                rules += RuleApplication("cfg.tail-duplication.v1", "b$b")
            }
            val out = ArrayList<JStmt>()
            for (op in block.ops) {
                if (materialize(op, b)) {
                    budget()
                    val reason = if (op is Binary && op.kind.mayThrow) "expr.materialize-throwing.v1" else "expr.materialize-shared.v1"
                    rules += RuleApplication(reason, op.def.toString())
                    out += JStmt.LocalDecl("int", freshLocal(op.def), render(op, ctx), listOfNotNull(op.provenance?.range), listOf(reason))
                }
            }
            when (val t = block.terminator) {
                is Return -> {
                    budget()
                    out += JStmt.Return(expr(t.value, ctx), listOfNotNull(t.provenance?.range))
                }
                is Goto -> out += follow(b, t.target, stop, ctx)
                is If -> {
                    val join = ipdom[b]
                    val innerStop = join ?: stop
                    if (join != null && join != stop && ir.block(join).phis.isNotEmpty()) {
                        declaredJoins += join
                        for (p in ir.block(join).phis) {
                            budget()
                            rules += RuleApplication("cfg.join.phi-local.v1", "b$join:${p.def}")
                            out += JStmt.LocalDecl("int", freshLocal(p.def), null, rules = listOf("cfg.join.phi-local.v1"))
                        }
                    }
                    val inverted = b in options.invertedIfs
                    val cond = condition(if (inverted) t.cond else t.cond.negate(), t, ctx)
                    val (first, second) = if (inverted) t.ifTrue to t.ifFalse else t.ifFalse to t.ifTrue
                    rules += RuleApplication(if (inverted) "cond.inverted-layout.v1" else "cond.javac-layout.v1", "b$b")
                    budget()
                    val thenPart = follow(b, first, innerStop, ctx)
                    val elsePart = follow(b, second, innerStop, ctx)
                    out += JStmt.If(cond, thenPart, elsePart, listOfNotNull(t.provenance?.range), listOf("cfg.structure.if-else.v1"))
                    if (join != null && join != stop) out += structure(join, stop, ctx, -1)
                }
            }
            return out
        }

        private fun follow(from: Int, to: Int, stop: Int?, ctx: Map<Value, Value>): List<JStmt> {
            if (to == stop) {
                // Leaving the region: assign the join's phi locals for this edge.
                return ir.block(to).phis.map { p ->
                    budget()
                    JStmt.Assign(freshLocal(p.def), expr(p.incoming.getValue(from), ctx), rules = listOf("cfg.join.phi-local.v1"))
                }
            }
            return structure(to, stop, ctx, from)
        }

        /**
         * Pure values with one use are inlined at the use; shared values become locals. A value that
         * may throw stays at its definition unless its only use is in the same block, so the
         * exception can never move onto or off a path.
         */
        private fun materialize(op: Op, block: Int): Boolean = when (op) {
            is Param, is ConstBits, is Phi -> false
            is Binary, is Unary -> {
                val n = uses[op.def] ?: 0
                val throws = op is Binary && op.kind.mayThrow
                when {
                    throws -> n != 1 || !usedOnlyInBlock(op.def, block)
                    else -> n > 1
                }
            }
        }

        private fun usedOnlyInBlock(v: Value, block: Int): Boolean {
            val b = ir.block(block)
            val inOps = b.ops.count { v in it.uses }
            val inTerm = b.terminator.uses.count { it == v }
            return inOps + inTerm == (uses[v] ?: 0)
        }

        private fun resolve(v: Value, ctx: Map<Value, Value>): Value {
            var cur = v
            var guard = 0
            while (true) {
                val next = ctx[cur] ?: return cur
                cur = next
                if (++guard > 10_000) error("phi substitution cycle")
            }
        }

        fun expr(v: Value, ctx: Map<Value, Value>): JExpr {
            val r = resolve(v, ctx)
            localNames[r]?.let { name ->
                // A phi local is only valid once its join has been declared on this path.
                return JExpr.Name(name)
            }
            val op = defs[r] ?: error("no definition for $r")
            if (op !is Param && op !is ConstBits && op !is Phi) rules += RuleApplication("expr.inline-single-use.v1", r.toString())
            return render(op, ctx)
        }

        private fun render(op: Op, ctx: Map<Value, Value>): JExpr {
            val origin = listOfNotNull(op.provenance?.range)
            return when (op) {
                is Param -> JExpr.Name(paramNames[op.index])
                is ConstBits -> JExpr.IntLit(op.int, origin = origin)
                is Phi -> throw ReconstructException("structure.phi", "phi ${op.def} reached without a local or an incoming edge")
                is Unary -> when (op.kind) {
                    UnKind.NEG -> JExpr.Unary(JUnOp.NEG, expr(op.a, ctx), origin)
                    UnKind.NOT -> JExpr.Unary(JUnOp.BITNOT, expr(op.a, ctx), origin)
                    UnKind.TO_BYTE -> JExpr.Cast("byte", expr(op.a, ctx), origin)
                    UnKind.TO_CHAR -> JExpr.Cast("char", expr(op.a, ctx), origin)
                    UnKind.TO_SHORT -> JExpr.Cast("short", expr(op.a, ctx), origin)
                }
                is Binary -> {
                    val jop = binOp(op.kind)
                    val left = expr(op.a, ctx)
                    val right = expr(op.b, ctx)
                    val bitwise = op.kind in setOf(BinKind.AND, BinKind.OR, BinKind.XOR)
                    fun lit(e: JExpr) = if (bitwise && e is JExpr.IntLit && (e.value > 9 || e.value < 0)) e.copy(hex = true) else e
                    JExpr.Binary(jop, lit(left), lit(right), origin)
                }
            }
        }

        private fun condition(cond: Cond, t: If, ctx: Map<Value, Value>): JExpr {
            val a = expr(t.a, ctx)
            val b = t.b?.let { expr(it, ctx) } ?: JExpr.IntLit(0)
            return JExpr.Binary(relOp(cond), a, b, listOfNotNull(t.provenance?.range))
        }
    }

    fun binOp(k: BinKind): JBinOp = when (k) {
        BinKind.ADD -> JBinOp.ADD; BinKind.SUB -> JBinOp.SUB; BinKind.MUL -> JBinOp.MUL; BinKind.DIV -> JBinOp.DIV
        BinKind.REM -> JBinOp.REM; BinKind.AND -> JBinOp.AND; BinKind.OR -> JBinOp.OR; BinKind.XOR -> JBinOp.XOR
        BinKind.SHL -> JBinOp.SHL; BinKind.SHR -> JBinOp.SHR; BinKind.USHR -> JBinOp.USHR
    }

    fun relOp(c: Cond): JBinOp = when (c) {
        Cond.EQ -> JBinOp.EQ; Cond.NE -> JBinOp.NE; Cond.LT -> JBinOp.LT; Cond.GE -> JBinOp.GE; Cond.GT -> JBinOp.GT; Cond.LE -> JBinOp.LE
    }
}

/** Statement-level rewrites. Each one preserves meaning and is recorded as a rule application. */
internal class Rewrites(private val options: ReconstructOptions, private val rules: MutableList<RuleApplication>) {
    fun apply(body: List<JStmt>): List<JStmt> {
        var out = body.map(::exprRewrite)
        out = block(out)
        out = inlineReturns(out, out)
        return out
    }

    private fun block(stmts: List<JStmt>): List<JStmt> {
        val out = ArrayList<JStmt>()
        var i = 0
        while (i < stmts.size) {
            val s = stmts[i].let { if (it is JStmt.If) it.copy(then = block(it.then), otherwise = block(it.otherwise)) else it }
            val next = stmts.getOrNull(i + 1)?.let { if (it is JStmt.If) it.copy(then = block(it.then), otherwise = block(it.otherwise)) else it }
            // int t; if (c) { t = A; } else { t = B; }  ->  int t = c ? A : B;
            if (options.ternary && s is JStmt.LocalDecl && s.init == null && next is JStmt.If &&
                next.then.singleOrNull().let { it is JStmt.Assign && it.name == s.name } &&
                next.otherwise.singleOrNull().let { it is JStmt.Assign && it.name == s.name }
            ) {
                val a = next.then.single() as JStmt.Assign
                val b = next.otherwise.single() as JStmt.Assign
                rules += RuleApplication("cond.ternary.v1", s.name)
                out += JStmt.LocalDecl(s.type, s.name, JExpr.Ternary(next.cond, a.value, b.value), next.origin, listOf("cond.ternary.v1"))
                i += 2
                continue
            }
            if (s is JStmt.If) {
                var cur = s
                if (cur.then.isEmpty() && cur.otherwise.isNotEmpty()) {
                    rules += RuleApplication("cond.empty-then.v1", "if")
                    cur = cur.copy(cond = negate(cur.cond), then = cur.otherwise, otherwise = emptyList())
                }
                if (options.flattenElseAfterReturn && cur.otherwise.isNotEmpty() && abrupt(cur.then)) {
                    rules += RuleApplication("stmt.else-after-return.v1", "if")
                    out += cur.copy(otherwise = emptyList())
                    out += cur.otherwise
                    i++
                    continue
                }
                out += cur
                i++
                continue
            }
            out += s
            i++
        }
        return out
    }

    /** `int t = E; return t;` -> `return E;` when t is used nowhere else. */
    private fun inlineReturns(stmts: List<JStmt>, whole: List<JStmt>): List<JStmt> {
        val out = ArrayList<JStmt>()
        var i = 0
        while (i < stmts.size) {
            val s = stmts[i]
            val next = stmts.getOrNull(i + 1)
            val init = (s as? JStmt.LocalDecl)?.init
            if (s is JStmt.LocalDecl && init != null && next is JStmt.Return && next.value == JExpr.Name(s.name) && countNames(whole, s.name) == 2) {
                rules += RuleApplication("stmt.inline-return.v1", s.name)
                out += JStmt.Return(init, s.origin + next.origin, s.rules + "stmt.inline-return.v1")
                i += 2
                continue
            }
            out += if (s is JStmt.If) s.copy(then = inlineReturns(s.then, whole), otherwise = inlineReturns(s.otherwise, whole)) else s
            i++
        }
        return out
    }

    private fun countNames(stmts: List<JStmt>, name: String): Int = stmts.sumOf { s ->
        when (s) {
            is JStmt.LocalDecl -> (if (s.name == name) 1 else 0) + (s.init?.let { countIn(it, name) } ?: 0)
            is JStmt.Assign -> (if (s.name == name) 1 else 0) + countIn(s.value, name)
            is JStmt.Return -> countIn(s.value, name)
            is JStmt.If -> countIn(s.cond, name) + countNames(s.then, name) + countNames(s.otherwise, name)
        }
    }

    private fun countIn(e: JExpr, name: String): Int = when (e) {
        is JExpr.Name -> if (e.id == name) 1 else 0
        is JExpr.IntLit -> 0
        is JExpr.Binary -> countIn(e.left, name) + countIn(e.right, name)
        is JExpr.Unary -> countIn(e.operand, name)
        is JExpr.Cast -> countIn(e.operand, name)
        is JExpr.Ternary -> countIn(e.cond, name) + countIn(e.ifTrue, name) + countIn(e.ifFalse, name)
    }

    private fun abrupt(stmts: List<JStmt>): Boolean = when (val last = stmts.lastOrNull()) {
        is JStmt.Return -> true
        is JStmt.If -> last.otherwise.isNotEmpty() && abrupt(last.then) && abrupt(last.otherwise)
        else -> false
    }

    private fun negate(e: JExpr): JExpr {
        if (e is JExpr.Binary && e.op.relational) {
            val op = when (e.op) {
                JBinOp.LT -> JBinOp.GE; JBinOp.GE -> JBinOp.LT; JBinOp.GT -> JBinOp.LE; JBinOp.LE -> JBinOp.GT
                JBinOp.EQ -> JBinOp.NE; JBinOp.NE -> JBinOp.EQ; else -> error("not relational")
            }
            return e.copy(op = op)
        }
        error("cannot negate $e")
    }

    /** `x + -5` -> `x - 5` (identical in two's complement; MIN_VALUE is left alone). */
    private fun exprRewrite(s: JStmt): JStmt = when (s) {
        is JStmt.LocalDecl -> s.copy(init = s.init?.let(::rewrite))
        is JStmt.Assign -> s.copy(value = rewrite(s.value))
        is JStmt.Return -> s.copy(value = rewrite(s.value))
        is JStmt.If -> s.copy(cond = rewrite(s.cond), then = s.then.map(::exprRewrite), otherwise = s.otherwise.map(::exprRewrite))
    }

    private fun rewrite(e: JExpr): JExpr = when (e) {
        is JExpr.Binary -> {
            val l = rewrite(e.left)
            val r = rewrite(e.right)
            if (e.op == JBinOp.ADD && r is JExpr.IntLit && !r.hex && r.value < 0 && r.value != Int.MIN_VALUE) {
                rules += RuleApplication("expr.add-negative-literal.v1", r.value.toString())
                JExpr.Binary(JBinOp.SUB, l, r.copy(value = -r.value), e.origin)
            } else {
                e.copy(left = l, right = r)
            }
        }
        is JExpr.Unary -> e.copy(operand = rewrite(e.operand))
        is JExpr.Cast -> e.copy(operand = rewrite(e.operand))
        is JExpr.Ternary -> e.copy(cond = rewrite(e.cond), ifTrue = rewrite(e.ifTrue), ifFalse = rewrite(e.ifFalse))
        else -> e
    }
}

/** Range helper used by callers that build source maps. */
fun List<JStmt>.allRanges(): List<ByteRange> = flatMap { s ->
    s.origin + when (s) {
        is JStmt.LocalDecl -> s.init?.allOrigins() ?: emptyList()
        is JStmt.Assign -> s.value.allOrigins()
        is JStmt.Return -> s.value.allOrigins()
        is JStmt.If -> s.cond.allOrigins() + s.then.allRanges() + s.otherwise.allRanges()
    }
}
