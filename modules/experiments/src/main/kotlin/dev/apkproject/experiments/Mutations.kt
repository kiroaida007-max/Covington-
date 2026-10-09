package dev.apkproject.experiments

import dev.apkproject.source.JBinOp
import dev.apkproject.source.JExpr
import dev.apkproject.source.JMethod
import dev.apkproject.source.JStmt

/**
 * Deliberately wrong candidates (dossier sections 27 and 29): used to prove that validation
 * rejects plausible-looking source. A mutant is never eligible for acceptance by construction of
 * the test, and must be rejected by the validator on its own.
 */
object Mutations {
    data class Mutant(val method: JMethod, val description: String)

    fun mutate(m: JMethod): Mutant? {
        var description: String? = null
        fun expr(e: JExpr): JExpr {
            if (description != null) return e
            return when (e) {
                is JExpr.Binary -> {
                    val swapped = when (e.op) {
                        JBinOp.LT -> JBinOp.LE; JBinOp.LE -> JBinOp.LT; JBinOp.GT -> JBinOp.GE; JBinOp.GE -> JBinOp.GT
                        JBinOp.ADD -> JBinOp.SUB; JBinOp.SUB -> JBinOp.ADD; JBinOp.SHR -> JBinOp.USHR; JBinOp.USHR -> JBinOp.SHR
                        else -> null
                    }
                    if (swapped != null) {
                        description = "${e.op.symbol} -> ${swapped.symbol}"
                        e.copy(op = swapped)
                    } else {
                        val l = expr(e.left)
                        e.copy(left = l, right = expr(e.right))
                    }
                }
                is JExpr.IntLit -> {
                    description = "literal ${e.value} -> ${e.value + 1}"
                    e.copy(value = e.value + 1)
                }
                is JExpr.Unary -> e.copy(operand = expr(e.operand))
                is JExpr.Cast -> e.copy(operand = expr(e.operand))
                is JExpr.Ternary -> e.copy(cond = expr(e.cond), ifTrue = expr(e.ifTrue), ifFalse = expr(e.ifFalse))
                is JExpr.Name -> e
            }
        }
        fun stmt(s: JStmt): JStmt = when (s) {
            is JStmt.LocalDecl -> s.copy(init = s.init?.let(::expr))
            is JStmt.Assign -> s.copy(value = expr(s.value))
            is JStmt.Return -> s.copy(value = expr(s.value))
            is JStmt.If -> s.copy(cond = expr(s.cond), then = s.then.map(::stmt), otherwise = s.otherwise.map(::stmt))
        }
        val body = m.body.map(::stmt)
        val d = description ?: return null
        return Mutant(m.copy(body = body), d)
    }
}
