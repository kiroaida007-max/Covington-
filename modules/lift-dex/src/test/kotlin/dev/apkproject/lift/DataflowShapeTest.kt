package dev.apkproject.lift

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.dexasm.Assembler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataflowShapeTest {
    private fun shape(body: String, proto: String = "(II)I", registers: Int = 6): DataflowShape {
        val text = ".class public LT;\n.super Ljava/lang/Object;\n.method public static f$proto\n.registers $registers\n$body\n.end method\n"
        val dex = (DexFile.parse(Assembler.assemble(text)) as Outcome.Ok).value
        val code = dex.methods().single().code!!
        val decoded = (InstructionDecoder.decode(dex, code) as Outcome.Ok).value
        return assertNotNull(DataflowShape.of(dex, decoded))
    }

    @Test
    fun `register allocation and moves do not matter`() {
        // (a - b) * 3 computed with different registers and a redundant copy.
        val a = shape("sub-int v0, v4, v5\nmul-int/lit8 v0, v0, #3\nreturn v0")
        val b = shape("move v3, v5\nsub-int v1, v4, v3\nmul-int/lit8 v2, v1, #3\nreturn v2")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(b).match)
    }

    @Test
    fun `two-address and literal encodings are the same operation`() {
        val a = shape("sub-int/2addr v4, v5\nconst/4 v0, #3\nmul-int/2addr v4, v0\nreturn v4")
        val b = shape("sub-int v0, v4, v5\nconst/16 v1, #3\nmul-int v0, v0, v1\nreturn v0")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(b).match)
    }

    @Test
    fun `swapped operands keep the operations but change the dataflow`() {
        val a = shape("sub-int v0, v4, v5\nreturn v0")
        val b = shape("sub-int v0, v5, v4\nreturn v0")
        assertEquals(RoundTripMatch.SIMILAR_OPERATIONS, a.compare(b).match)
    }

    @Test
    fun `a different constant is a different method`() {
        val a = shape("add-int/lit8 v0, v4, #1\nreturn v0")
        val b = shape("add-int/lit8 v0, v4, #2\nreturn v0")
        val c = a.compare(b)
        assertEquals(RoundTripMatch.DIFFERENT, c.match)
        assertTrue(c.similarity < 1.0)
    }

    @Test
    fun `branch structure and merge values are compared`() {
        // max(a, b) written with the branch in either sense compiles to different control flow.
        val a = shape("if-ge v4, v5, :a\nmove v4, v5\n:a\nreturn v4")
        val b = shape("if-ge v4, v5, :x\nmove v0, v5\nreturn v0\n:x\nmove v0, v4\nreturn v0")
        assertTrue(a.compare(b).match != RoundTripMatch.EQUIVALENT_DATAFLOW)
        val a2 = shape("if-ge v4, v5, :a\nmove v4, v5\n:a\nreturn v4")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(a2).match)
    }

    @Test
    fun `chains of merges through moves stay linear`() {
        // 80 diamonds that each copy one of two registers: naive origin tracing doubles per diamond.
        val body = (0 until 80).joinToString("\n") { k ->
            "if-eqz v4, :a$k\nmove v0, v1\ngoto :m$k\n:a$k\nmove v0, v2\n:m$k\nmove v1, v0\nmove v2, v0"
        } + "\nreturn v0"
        val started = System.nanoTime()
        val a = shape("const/4 v1, #1\nconst/4 v2, #2\n$body", proto = "(I)I", registers = 5)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "took too long")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(a).match)
    }

    @Test
    fun `invoke targets and arity are part of the shape`() {
        val a = shape("invoke-static {v4, v5}, Ljava/lang/Math;->max(II)I\nmove-result v0\nreturn v0")
        val b = shape("invoke-static {v4, v5}, Ljava/lang/Math;->min(II)I\nmove-result v0\nreturn v0")
        val c = shape("invoke-static/range {v4 .. v5}, Ljava/lang/Math;->max(II)I\nmove-result v1\nreturn v1")
        assertEquals(RoundTripMatch.DIFFERENT, a.compare(b).match)
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(c).match)
    }
}
