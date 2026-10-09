package dev.apkproject.dex

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** Cross-checks Opcodes.kt against an independent transcription of the AOSP opcode table. */
class OpcodeTableTest {
    @Test
    fun `every opcode matches the AOSP table`() {
        val rows = javaClass.getResourceAsStream("/aosp/dalvik-opcodes.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
        assertEquals(256, rows.size)
        val mismatches = ArrayList<String>()
        for ((hex, mnemonic, format) in rows) {
            val code = hex.toInt(16)
            val ours = Opcodes[code]
            if (mnemonic == "(unused)") {
                if (ours != null) mismatches += "$hex: AOSP unused, ours ${ours.mnemonic}"
                continue
            }
            if (ours == null) {
                mismatches += "$hex: missing $mnemonic"; continue
            }
            if (ours.mnemonic != mnemonic) mismatches += "$hex: mnemonic ${ours.mnemonic} != $mnemonic"
            if (ours.format.id != format) mismatches += "$hex: format ${ours.format.id} != $format"
        }
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n"))
        assertEquals(224, Opcodes.all.size)
    }

    @Test
    fun `unassigned opcodes stay unassigned`() {
        for (code in (0x3e..0x43) + 0x73 + (0x79..0x7a) + (0xe3..0xf9)) assertNull(Opcodes[code], "0x%02x".format(code))
    }

    @Test
    fun `wide operand masks match operand counts`() {
        for (op in Opcodes.all) {
            if (op.wide.isEmpty()) continue
            val operands = when (op.format) {
                Format.F12x, Format.F22x, Format.F32x -> 2
                Format.F23x -> 3
                Format.F11x, Format.F21s, Format.F31i, Format.F51l, Format.F21h -> 1
                Format.F22c -> 2
                Format.F21c -> 1
                else -> -1
            }
            assertEquals(operands, op.wide.length, op.mnemonic)
        }
    }
}
