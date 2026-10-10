package dev.apkproject.app

import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.FlowKind
import dev.apkproject.dex.IndexKind
import dev.apkproject.lift.DataflowShape

/**
 * Upper bounds on every object returned by a method. This deliberately stays within basic blocks:
 * joins and exception handlers forget registers, and an unknown or null return makes the entire
 * result unknown. Incompatible bounds can veto a proposed identity, but cannot establish one.
 */
internal object ReturnTypeEvidence {
    fun collect(dex: DexFile, code: DecodedCode): Set<String>? {
        val boundaries = code.labelAddresses()
        val registers = HashMap<Int, String>()
        val returns = HashSet<String>()
        var pendingResult: String? = null
        for (i in code.insns) {
            if (i.address in boundaries) { registers.clear(); pendingResult = null }
            val mnemonic = i.op.mnemonic
            if (mnemonic == "return-object") returns += registers[i.regs[0]] ?: return null
            val assigned = when {
                mnemonic.startsWith("move-object") -> registers[i.regs[1]]
                mnemonic == "move-result-object" -> pendingResult
                mnemonic.startsWith("const-string") -> "Ljava/lang/String;"
                mnemonic == "const-class" -> "Ljava/lang/Class;"
                mnemonic in setOf("new-instance", "new-array", "check-cast") -> dex.type(i.index)
                mnemonic in setOf("iget-object", "sget-object") -> dex.type(dex.fieldIds[i.index].typeIdx)
                else -> null
            }
            DataflowShape.writes(i, code.code.registersSize).forEach { registers.remove(it) }
            if (assigned != null) registers[i.regs[0]] = assigned
            pendingResult = when {
                mnemonic.startsWith("invoke-") && i.op.index == IndexKind.METHOD -> dex.methodDescriptor(i.index).substringAfter(')')
                mnemonic.startsWith("filled-new-array") -> dex.type(i.index)
                else -> null
            }
            if (i.op.flow != FlowKind.NONE) { registers.clear(); pendingResult = null }
        }
        return returns.takeIf { it.isNotEmpty() }
    }
}
