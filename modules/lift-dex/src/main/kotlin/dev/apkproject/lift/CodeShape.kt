package dev.apkproject.lift

import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.Disassembler
import dev.apkproject.dex.IndexKind

enum class StructuralMatch {
    /**
     * Same instructions, registers, literals, branch structure and resolved references: the
     * candidate recompiles to the original bytecode up to constant-pool numbering.
     */
    IDENTICAL,

    /** Same instruction sequence up to a consistent register renaming. */
    ISOMORPHIC,

    /** Different instruction sequences; see the edit distance. */
    DIFFERENT,
}

data class ShapeComparison(val match: StructuralMatch, val distance: Int) {
    override fun toString(): String = if (match == StructuralMatch.DIFFERENT) "$match(distance=$distance)" else match.name
}

/**
 * A register-normalised rendering of decoded code for structural comparison (historical-fidelity
 * evidence). Agreement here supports a source hypothesis; it never establishes semantics.
 */
class CodeShape private constructor(val exact: List<String>, val tokens: List<String>, val mnemonics: List<String>) {
    fun compare(other: CodeShape): ShapeComparison = when {
        exact == other.exact -> ShapeComparison(StructuralMatch.IDENTICAL, 0)
        tokens == other.tokens -> ShapeComparison(StructuralMatch.ISOMORPHIC, 0)
        else -> ShapeComparison(StructuralMatch.DIFFERENT, editDistance(tokens, other.tokens))
    }

    companion object {
        fun of(dex: DexFile, d: DecodedCode): CodeShape {
            val rename = HashMap<Int, Int>()
            val indexOf = d.insns.withIndex().associate { it.value.address to it.index }
            fun token(i: dev.apkproject.dex.Insn, regs: String): String {
                val target = if (i.target >= 0) "@" + (indexOf[i.target]?.toString() ?: "p") else ""
                val ref = if (i.op.index != IndexKind.NONE) " " + Disassembler.insn(dex, i).substringAfterLast(", ") else ""
                val lit = if (i.literal != 0L) " #${i.literal}" else ""
                return "${i.op.mnemonic} $regs$lit$target$ref"
            }
            val exact = listOf("regs=${d.code.registersSize}") + d.insns.map { i -> token(i, i.regs.joinToString(",") { "v$it" }) } +
                d.payloads.values.sortedBy { it.address }.map { payloadToken(it) }
            val tokens = d.insns.map { i -> token(i, i.regs.joinToString(",") { r -> "r" + rename.getOrPut(r) { rename.size } }) } +
                d.payloads.values.sortedBy { it.address }.map { payloadToken(it) }
            return CodeShape(exact, tokens, d.insns.map { it.op.mnemonic })
        }

        private fun payloadToken(p: dev.apkproject.dex.Payload): String = when (p) {
            is dev.apkproject.dex.PackedSwitchPayload -> "packed ${p.firstKey} ${p.relativeTargets.size}"
            is dev.apkproject.dex.SparseSwitchPayload -> "sparse ${p.keys.toList()}"
            is dev.apkproject.dex.FillArrayDataPayload -> "array ${p.elementWidth} ${p.data.contentHashCode()}"
        }

        fun <T> editDistance(a: List<T>, b: List<T>): Int {
            var prev = IntArray(b.size + 1) { it }
            for (i in 1..a.size) {
                val cur = IntArray(b.size + 1)
                cur[0] = i
                for (j in 1..b.size) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.size]
        }
    }
}
