package dev.apkproject.source

import dev.apkproject.contracts.ByteRange

/** One printed line and what it was generated from. */
data class LineOrigin(val line: Int, val methodId: String, val ranges: List<ByteRange>, val rules: List<String>)

data class PrintedUnit(val text: String, val lines: List<LineOrigin>)

/** Deterministic Java pretty-printer with precedence-aware parenthesisation. */
object JavaPrinter {
    fun print(c: JClass): PrintedUnit {
        val out = Out()
        c.header.forEach { out.line("// $it") }
        if (!c.packageName.isNullOrEmpty()) {
            out.line("package ${c.packageName};")
            out.line("")
        }
        out.line((c.modifiers + "class" + c.simpleName).joinToString(" ") + " {")
        c.methods.forEachIndexed { i, m ->
            if (i > 0) out.line("")
            method(m, out, "    ")
        }
        out.line("}")
        return PrintedUnit(out.sb.toString(), out.origins)
    }

    fun method(m: JMethod): String = Out().also { method(m, it, "") }.sb.toString()

    private fun method(m: JMethod, out: Out, indent: String) {
        m.comment?.lines()?.forEach { out.line("$indent// $it") }
        val sig = (m.modifiers + m.returnType).joinToString(" ") + " " + m.name + m.params.joinToString(", ", "(", ")") { "${it.type} ${it.name}" }
        out.line("$indent$sig {")
        block(m.body, out, "$indent    ", m.methodId)
        out.line("$indent}")
    }

    private fun block(stmts: List<JStmt>, out: Out, indent: String, id: String) {
        for (s in stmts) stmt(s, out, indent, id)
    }

    private fun stmt(s: JStmt, out: Out, indent: String, id: String) {
        fun tagged(text: String, extra: List<ByteRange>) = out.line(indent + text, LineOrigin(0, id, (s.origin + extra).distinct(), s.rules))
        when (s) {
            is JStmt.LocalDecl -> tagged("${s.type} ${s.name}" + (s.init?.let { " = ${expr(it)}" } ?: "") + ";", s.init?.allOrigins() ?: emptyList())
            is JStmt.Assign -> tagged("${s.name} = ${expr(s.value)};", s.value.allOrigins())
            is JStmt.Return -> tagged("return ${expr(s.value)};", s.value.allOrigins())
            is JStmt.If -> {
                tagged("if (${expr(s.cond)}) {", s.cond.allOrigins())
                block(s.then, out, "$indent    ", id)
                if (s.otherwise.isEmpty()) {
                    out.line("$indent}")
                } else if (s.otherwise.size == 1 && s.otherwise[0] is JStmt.If) {
                    // else-if chain
                    val nested = s.otherwise[0] as JStmt.If
                    out.line("$indent} else if (${expr(nested.cond)}) {", LineOrigin(0, id, nested.origin + nested.cond.allOrigins(), nested.rules))
                    block(nested.then, out, "$indent    ", id)
                    printElse(nested.otherwise, out, indent, id)
                } else {
                    out.line("$indent} else {")
                    block(s.otherwise, out, "$indent    ", id)
                    out.line("$indent}")
                }
            }
        }
    }

    private fun printElse(otherwise: List<JStmt>, out: Out, indent: String, id: String) {
        if (otherwise.isEmpty()) {
            out.line("$indent}")
            return
        }
        if (otherwise.size == 1 && otherwise[0] is JStmt.If) {
            val nested = otherwise[0] as JStmt.If
            out.line("$indent} else if (${expr(nested.cond)}) {", LineOrigin(0, id, nested.origin + nested.cond.allOrigins(), nested.rules))
            block(nested.then, out, "$indent    ", id)
            printElse(nested.otherwise, out, indent, id)
            return
        }
        out.line("$indent} else {")
        block(otherwise, out, "$indent    ", id)
        out.line("$indent}")
    }

    fun expr(e: JExpr): String = expr(e, 0)

    private const val TERNARY = 2
    private const val UNARY = 13

    private fun precedence(e: JExpr): Int = when (e) {
        is JExpr.IntLit -> if (e.value < 0 && !e.hex) UNARY else 16
        is JExpr.Name -> 16
        is JExpr.Binary -> e.op.precedence
        is JExpr.Unary, is JExpr.Cast -> UNARY
        is JExpr.Ternary -> TERNARY
    }

    private fun expr(e: JExpr, min: Int): String {
        val text = when (e) {
            is JExpr.IntLit -> literal(e)
            is JExpr.Name -> e.id
            is JExpr.Binary -> {
                // Left-associative: the right operand needs strictly higher precedence.
                // Relational operators are not associative in Java's type system either.
                val l = expr(e.left, if (mixed(e.op, e.left)) Int.MAX_VALUE else e.op.precedence + if (e.op.relational) 1 else 0)
                val r = expr(e.right, if (mixed(e.op, e.right)) Int.MAX_VALUE else e.op.precedence + 1)
                "$l ${e.op.symbol} $r"
            }
            is JExpr.Unary -> {
                val inner = expr(e.operand, UNARY)
                // Avoid `--x` / `- -x` being read as decrement.
                if (e.op == JUnOp.NEG && inner.startsWith("-")) "-($inner)" else e.op.symbol + inner
            }
            is JExpr.Cast -> "(${e.type}) " + expr(e.operand, UNARY)
            is JExpr.Ternary -> expr(e.cond, TERNARY + 1) + " ? " + expr(e.ifTrue, TERNARY + 1) + " : " + expr(e.ifFalse, TERNARY)
        }
        return if (precedence(e) < min) "($text)" else text
    }

    private val SHIFTS = setOf(JBinOp.SHL, JBinOp.SHR, JBinOp.USHR)
    private val BITWISE = setOf(JBinOp.AND, JBinOp.OR, JBinOp.XOR)

    /** Parentheses Java does not require but readers expect around mixed shift and bitwise operands. */
    private fun mixed(parent: JBinOp, child: JExpr): Boolean =
        child is JExpr.Binary && child.op != parent && !child.op.relational && (parent in SHIFTS || parent in BITWISE)

    private fun literal(e: JExpr.IntLit): String = when {
        e.hex -> "0x" + Integer.toHexString(e.value)
        else -> e.value.toString()
    }

    private class Out {
        val sb = StringBuilder()
        val origins = ArrayList<LineOrigin>()
        var lineNo = 0

        fun line(text: String, origin: LineOrigin? = null) {
            lineNo++
            sb.append(text).append('\n')
            if (origin != null && (origin.ranges.isNotEmpty() || origin.rules.isNotEmpty())) origins += origin.copy(line = lineNo)
        }
    }
}
