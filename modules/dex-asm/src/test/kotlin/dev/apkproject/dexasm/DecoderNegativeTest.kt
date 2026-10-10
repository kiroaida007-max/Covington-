package dev.apkproject.dexasm

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.zip.Adler32
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Builds valid methods with the assembler, then corrupts one field so each decoder gate
 * from section 11 sees exactly one defect.
 */
class DecoderNegativeTest {
    private fun method(body: String, registers: Int = 4, version: String = "035"): String =
        ".dex $version\n.class public LX;\n.super Ljava/lang/Object;\n.method public static f(I)I\n.registers $registers\n$body\n.end method\n"

    private class Built(val bytes: ByteArray) {
        val dex = (DexFile.parse(bytes) as Outcome.Ok).value
        val code = dex.methods().single().code!!
        /** File offset of code unit [i]. */
        fun unit(i: Int) = code.insnsOffset + i * 2
    }

    private fun build(text: String) = Built(Assembler.assemble(text))

    private fun decodeError(bytes: ByteArray): String {
        fixChecksums(bytes)
        val dex = when (val p = DexFile.parse(bytes)) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> return p.failure.toString()
        }
        return when (val d = InstructionDecoder.decode(dex, dex.methods().single().code!!)) {
            is Outcome.Ok -> fail("expected the decoder to reject the patched code")
            is Outcome.Err -> d.failure.toString()
        }
    }

    @Test
    fun `branch into the middle of an instruction is rejected`() {
        val b = build(method("if-eqz v3, :t\nconst v0, #100000\n:t\nreturn v3"))
        // if-eqz target offset lives in unit 1; aim one unit into the 3-unit const.
        val bytes = b.bytes.clone()
        bytes[b.unit(1)] = 3
        assertContains(decodeError(bytes), "not an instruction boundary")
    }

    @Test
    fun `unassigned opcode is reported, not skipped`() {
        val b = build(method("nop\nreturn v3"))
        val bytes = b.bytes.clone()
        bytes[b.unit(0)] = 0x3e
        assertContains(decodeError(bytes), "unassigned opcode 0x3e")
    }

    @Test
    fun `register outside the frame is rejected`() {
        val b = build(method("add-int v0, v3, v3\nreturn v0"))
        val bytes = b.bytes.clone()
        bytes[b.unit(1)] = 9 // vBB = v9 with registers_size 4
        assertContains(decodeError(bytes), "exceeds registers_size")
    }

    @Test
    fun `switch pointing at a non-payload is rejected`() {
        val b = build(method("packed-switch v3, :p\nreturn v3\n:a\nreturn v3\n:p\n.packed-switch 0\n:a\n.end packed-switch"))
        val bytes = b.bytes.clone()
        bytes[b.unit(1)] = 3 // point at the second return instead of the payload
        assertContains(decodeError(bytes), "is not a payload")
    }

    @Test
    fun `newer opcodes are gated by the file version`() {
        val b = build(method("const-method-type v0, (I)I\nreturn v3", version = "039"))
        val bytes = b.bytes.clone()
        bytes[6] = '5'.code.toByte(); bytes[5] = '3'.code.toByte() // magic 039 -> 035
        assertContains(decodeError(bytes), "requires DEX 39")
    }

    @Test
    fun `non-zero high byte on a 10x instruction is rejected`() {
        val b = build(method("const/4 v0, #0\nreturn-void"))
        val bytes = b.bytes.clone()
        bytes[b.unit(1) + 1] = 5
        assertContains(decodeError(bytes), "zero high byte")
    }

    @Test
    fun `invalid payload identifier is rejected`() {
        val b = build(method("nop\nreturn v3"))
        val bytes = b.bytes.clone()
        bytes[b.unit(0) + 1] = 7 // 0x0700 is not nop and not a payload
        assertContains(decodeError(bytes), "neither nop nor a payload")
    }

    @Test
    fun `try range that splits an instruction is rejected`() {
        val b = build(method(":a\nconst v0, #100000\n:b\nreturn v0\n:h\nreturn v3\n.catchall {:a .. :b} :h"))
        // The try item follows the insns (6 units) at an aligned offset: start_addr u4, insn_count u2.
        val bytes = b.bytes.clone()
        val tryOff = b.code.insnsOffset + b.code.insns.size * 2 + (if (b.code.insns.size % 2 == 1) 2 else 0)
        bytes[tryOff + 4] = 2 // insn_count 3 -> 2 ends inside the const
        assertTrue(decodeError(bytes).contains("try end is not an instruction boundary"))
    }

    companion object {
        fun fixChecksums(b: ByteArray) {
            val sha1 = MessageDigest.getInstance("SHA-1").also { it.update(b, 32, b.size - 32) }.digest()
            System.arraycopy(sha1, 0, b, 12, 20)
            val v = Adler32().also { it.update(b, 12, b.size - 12) }.value
            for (i in 0 until 4) b[8 + i] = (v ushr (8 * i)).toByte()
        }
    }
}
