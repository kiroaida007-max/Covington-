package dev.apkproject.reconstruct

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dexasm.Assembler
import dev.apkproject.ir.MethodIr
import dev.apkproject.lift.DexLifter
import dev.apkproject.source.JavaPrinter
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconstructorTest {
    private fun ir(body: String, proto: String = "(I)I", registers: Int = 4, params: String = ""): MethodIr {
        val text = ".class public LT;\n.super Ljava/lang/Object;\n.method public static f$proto\n.registers $registers\n$params\n$body\n.end method\n"
        val m = (DexFile.parse(Assembler.assemble(text)) as Outcome.Ok).value.methods().single()
        val r = DexLifter.lift(m)
        return assertNotNull(r.ir, "gaps ${r.gaps}")
    }

    private fun source(ir: MethodIr, options: ReconstructOptions = ReconstructOptions()): String =
        JavaPrinter.method(Reconstructor.reconstruct(ir, "f", listOf("public", "static"), options).method)

    @Test
    fun `straight-line arithmetic inlines into the return`() {
        val s = source(ir("add-int v0, v3, v3\nreturn v0"))
        assertEquals("public static int f(int p0) {\n    return p0 + p0;\n}\n", s)
    }

    @Test
    fun `observed parameter names are kept`() {
        val s = source(ir("mul-int/lit8 v0, v3, #2\nreturn v0", params = ".param v3 \"x\""))
        assertContains(s, "f(int x)")
        assertContains(s, "return x * 2;")
    }

    @Test
    fun `abs becomes a ternary in javac layout`() {
        val s = source(ir("if-gez v3, :done\nneg-int v3, v3\n:done\nreturn v3"))
        assertContains(s, "return p0 < 0 ? -p0 : p0;")
    }

    @Test
    fun `inverting a branch flips condition and arms`() {
        val m = ir("if-gez v3, :done\nneg-int v3, v3\n:done\nreturn v3")
        val first = Reconstructor.reconstruct(m, "f", listOf("static"))
        val s = source(m, ReconstructOptions(invertedIfs = first.branchBlocks.toSet()))
        assertContains(s, "return p0 >= 0 ? p0 : -p0;")
    }

    @Test
    fun `guard clauses flatten into early returns`() {
        // clamp(x, lo, hi)
        val s = source(
            ir(
                "if-ge v1, v2, :a\nreturn v2\n:a\nif-le v1, v3, :b\nreturn v3\n:b\nreturn v1",
                proto = "(III)I",
            ),
        )
        assertEquals(
            "public static int f(int p0, int p1, int p2) {\n    if (p0 < p1) {\n        return p1;\n    }\n" +
                "    if (p0 > p2) {\n        return p2;\n    }\n    return p0;\n}\n",
            s,
        )
    }

    @Test
    fun `shared values become locals and throwing values stay in place`() {
        val s = source(
            ir(
                "div-int v0, v2, v3\nif-eqz v2, :z\nadd-int v1, v0, v0\nreturn v1\n:z\nreturn v3",
                proto = "(II)I",
            ),
        )
        // The division happens before the branch on every path, so it must not be inlined into one arm.
        assertTrue(s.indexOf("int t0 = p0 / p1;") < s.indexOf("if ("), s)
        assertContains(s, "return t0 + t0;")
    }

    @Test
    fun `a join with a computed value uses a phi local`() {
        val s = source(
            ir(
                "if-lez v2, :neg\nmul-int/lit8 v0, v2, #3\ngoto :join\n:neg\nadd-int/lit8 v0, v3, #-7\n:join\nadd-int v1, v0, v2\nreturn v1",
                proto = "(II)I",
            ),
        )
        assertContains(s, "int t0 = p0 > 0 ? p0 * 3 : p1 - 7;")
        assertContains(s, "return t0 + p0;")
    }

    @Test
    fun `byte, char and short conversions print as casts`() {
        val s = source(ir("int-to-byte v0, v3\nint-to-char v1, v3\nadd-int/2addr v0, v1\nint-to-short v1, v3\nadd-int/2addr v0, v1\nreturn v0"))
        assertContains(s, "(byte) p0 + (char) p0 + (short) p0")
    }
}
