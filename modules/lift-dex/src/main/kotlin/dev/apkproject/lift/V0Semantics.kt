package dev.apkproject.lift

import dev.apkproject.ir.BinKind
import dev.apkproject.ir.Cond
import dev.apkproject.ir.UnKind

/**
 * The V0 semantic manifest (dossier section 11, "V0 semantic opcode families"): the exact opcode
 * variants whose meaning the lifter models. Anything not listed is a capability gap, even when the
 * decoder recognises it. Division and remainder are included because their only exceptional
 * behaviour (ArithmeticException on a zero divisor) is modelled explicitly.
 */
object V0Semantics {
    sealed class Sem {
        object Nop : Sem()
        object Move : Sem()
        object Const : Sem()
        object Return : Sem()
        object Goto : Sem()
        data class IfCmp(val cond: Cond) : Sem()
        data class IfZero(val cond: Cond) : Sem()
        data class Un(val kind: UnKind) : Sem()
        /** 23x: vAA = vBB op vCC */
        data class Bin(val kind: BinKind) : Sem()
        /** 12x: vA = vA op vB */
        data class Bin2Addr(val kind: BinKind) : Sem()
        /** 22s/22b: vA = vB op #lit, or #lit - vB when [reverse] */
        data class BinLit(val kind: BinKind, val reverse: Boolean = false) : Sem()
    }

    val manifest: Map<String, Sem> = buildMap {
        put("nop", Sem.Nop)
        listOf("move", "move/from16", "move/16").forEach { put(it, Sem.Move) }
        listOf("const/4", "const/16", "const", "const/high16").forEach { put(it, Sem.Const) }
        put("return", Sem.Return)
        listOf("goto", "goto/16", "goto/32").forEach { put(it, Sem.Goto) }
        val conds = listOf(Cond.EQ, Cond.NE, Cond.LT, Cond.GE, Cond.GT, Cond.LE)
        val names = listOf("eq", "ne", "lt", "ge", "gt", "le")
        names.forEachIndexed { i, n ->
            put("if-$n", Sem.IfCmp(conds[i])); put("if-${n}z", Sem.IfZero(conds[i]))
        }
        put("neg-int", Sem.Un(UnKind.NEG)); put("not-int", Sem.Un(UnKind.NOT))
        put("int-to-byte", Sem.Un(UnKind.TO_BYTE)); put("int-to-char", Sem.Un(UnKind.TO_CHAR)); put("int-to-short", Sem.Un(UnKind.TO_SHORT))
        val bins = mapOf(
            "add" to BinKind.ADD, "sub" to BinKind.SUB, "mul" to BinKind.MUL, "div" to BinKind.DIV, "rem" to BinKind.REM,
            "and" to BinKind.AND, "or" to BinKind.OR, "xor" to BinKind.XOR, "shl" to BinKind.SHL, "shr" to BinKind.SHR, "ushr" to BinKind.USHR,
        )
        for ((n, k) in bins) {
            put("$n-int", Sem.Bin(k))
            put("$n-int/2addr", Sem.Bin2Addr(k))
            put("$n-int/lit8", Sem.BinLit(k))
            if (k != BinKind.SHL && k != BinKind.SHR && k != BinKind.USHR && k != BinKind.SUB) put("$n-int/lit16", Sem.BinLit(k))
        }
        remove("sub-int/lit8")
        put("rsub-int", Sem.BinLit(BinKind.SUB, reverse = true))
        put("rsub-int/lit8", Sem.BinLit(BinKind.SUB, reverse = true))
    }

    const val MODEL =
        "V0 integer model: I32 two's-complement wrapping arithmetic; shift distances masked to 5 bits; " +
            "div/rem truncate toward zero, MIN_VALUE / -1 == MIN_VALUE, MIN_VALUE % -1 == 0; " +
            "a zero divisor throws java.lang.ArithmeticException; no heap, calls or loops."
}
