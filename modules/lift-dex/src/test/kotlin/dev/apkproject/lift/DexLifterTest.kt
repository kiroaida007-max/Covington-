package dev.apkproject.lift

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.Opcodes
import dev.apkproject.dexasm.Assembler
import dev.apkproject.ir.BinKind
import dev.apkproject.ir.Binary
import dev.apkproject.ir.EffectKind
import dev.apkproject.ir.IrEvaluator
import dev.apkproject.ir.MethodIr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.apkproject.ir.Outcome as Run

class DexLifterTest {
    private fun methods(body: String, proto: String = "(I)I", registers: Int = 4, flags: String = "public static"): List<DexMethod> {
        val text = ".class public LT;\n.super Ljava/lang/Object;\n.method $flags f$proto\n.registers $registers\n$body\n.end method\n"
        return (DexFile.parse(Assembler.assemble(text)) as Outcome.Ok).value.methods()
    }

    private fun lift(body: String, proto: String = "(I)I", registers: Int = 4) = DexLifter.lift(methods(body, proto, registers).single())

    private fun ir(body: String, proto: String = "(I)I", registers: Int = 4): MethodIr =
        lift(body, proto, registers).let { assertNotNull(it.ir, "gaps: ${it.gaps}") }

    @Test
    fun `manifest only names real opcodes`() {
        for (name in V0Semantics.manifest.keys) assertNotNull(Opcodes.byMnemonic(name), name)
    }

    @Test
    fun `x plus x lifts to one add with provenance`() {
        val m = ir("add-int v0, v3, v3\nreturn v0")
        val add = m.blocks.single().ops.filterIsInstance<Binary>().single()
        assertEquals(BinKind.ADD, add.kind)
        assertEquals(add.a, add.b)
        assertEquals(0, add.provenance!!.address)
        assertEquals(4L, add.provenance!!.range!!.length)
        assertEquals(Run.Returned(14), IrEvaluator.run(m, intArrayOf(7)))
    }

    @Test
    fun `conditional merge creates a phi`() {
        // abs(x): if (x >= 0) skip negation
        val m = ir("if-gez v3, :done\nneg-int v3, v3\n:done\nreturn v3")
        assertEquals(1, m.blocks.sumOf { it.phis.size })
        assertEquals(Run.Returned(5), IrEvaluator.run(m, intArrayOf(-5)))
        assertEquals(Run.Returned(5), IrEvaluator.run(m, intArrayOf(5)))
        assertEquals(Run.Returned(Int.MIN_VALUE), IrEvaluator.run(m, intArrayOf(Int.MIN_VALUE)))
    }

    @Test
    fun `literal forms, reverse subtract and shifts`() {
        val m = ir("rsub-int/lit8 v0, v3, #10\nshl-int/lit8 v1, v0, #33\nmul-int/lit16 v2, v1, #-300\nreturn v2")
        val x = 3
        assertEquals(Run.Returned(((10 - x) shl (33 and 31)) * -300), IrEvaluator.run(m, intArrayOf(x)))
    }

    @Test
    fun `division records its exceptional effect`() {
        val m = ir("div-int v0, v2, v3\nreturn v0", proto = "(II)I")
        assertTrue(EffectKind.THROWS_ARITHMETIC_EXCEPTION in m.effects)
        assertEquals(Run.Threw("java.lang.ArithmeticException"), IrEvaluator.run(m, intArrayOf(1, 0)))
        assertEquals(Run.Returned(Int.MIN_VALUE), IrEvaluator.run(m, intArrayOf(Int.MIN_VALUE, -1)))
    }

    @Test
    fun `loops, calls, wide values and instance methods are explicit gaps`() {
        val loop = lift(":top\nadd-int/lit8 v3, v3, #-1\nif-gtz v3, :top\nreturn v3")
        assertNull(loop.ir)
        assertEquals("cfg.loop", loop.gaps.single().code)

        val call = lift("invoke-static {v3}, LT;->g(I)I\nmove-result v0\nreturn v0")
        assertEquals(setOf("opcode.unsupported"), call.gaps.map { it.code }.toSet())
        assertNotNull(call.gaps.first().byteRange)

        val wide = lift("const-wide/16 v0, #1\nreturn v3")
        assertEquals("opcode.unsupported", wide.gaps.single().code)

        val sig = DexLifter.lift(methods("return-wide v2", proto = "(J)J").single())
        assertTrue(sig.gaps.any { it.code == "signature.non-i32" })

        val inst = DexLifter.lift(methods("return v3", flags = "public").single())
        assertTrue(inst.gaps.any { it.code == "method.instance" })
    }

    @Test
    fun `register defined on only one path is rejected`() {
        val r = lift("if-eqz v3, :skip\nconst/4 v0, #1\n:skip\nreturn v0")
        assertEquals("register.undefined", r.gaps.single().code)
    }

    @Test
    fun `lifted D8 arithmetic agrees with an independent reimplementation`() {
        val dex = (DexFile.parse(javaClass.getResourceAsStream("/d8/broad.dex")!!.readBytes()) as Outcome.Ok).value
        val m = DexLifter.lift(dex.methods().first { it.name == "arith" })
        val ir = assertNotNull(m.ir, "gaps: ${m.gaps}")
        // Hand transcription of Broad.arith (Java semantics), independent of the lifter.
        fun arith(a: Int, b: Int): Run {
            var x = a + b; x -= a * 3; x *= b
            val d = b or 1
            x /= d; x %= 7
            x = x and 0xff; x = x or a; x = x xor b; x = x shl 2; x = x shr 1; x = x ushr 3
            return Run.Returned(-x + a.inv() + (a + 1000) + (b * 100000))
        }
        val edge = intArrayOf(0, 1, -1, 2, -2, 7, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE + 1, 31, 32, 33)
        val rnd = java.util.Random(14)
        fun gen() = if (rnd.nextBoolean()) edge[rnd.nextInt(edge.size)] else rnd.nextInt()
        repeat(2_000) {
            val a = gen()
            val b = gen()
            assertEquals(arith(a, b), IrEvaluator.run(ir, intArrayOf(a, b)), "arith($a, $b)")
        }
    }
}
