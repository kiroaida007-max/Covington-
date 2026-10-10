package dev.apkproject.dex

import dev.apkproject.contracts.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Decodes DEX files produced by Google's D8 (independent encoder) and checks what we recover. */
class D8GroundTruthTest {
    private fun load(name: String): DexFile {
        val bytes = javaClass.getResourceAsStream("/d8/$name")!!.readBytes()
        return when (val o = DexFile.parse(bytes)) {
            is Outcome.Ok -> o.value
            is Outcome.Err -> fail("parse failed: ${o.failure}")
        }
    }

    @Test
    fun `parses tables and decodes every method`() {
        val dex = load("broad.dex")
        assertEquals(39, dex.version)
        val classes = dex.classDefs.map { dex.type(it.classIdx) }.toSet()
        assertTrue("LBroad;" in classes && "LBroad\$Square;" in classes && "LBroad\$Shape;" in classes, classes.toString())
        val methods = dex.methods()
        assertTrue(methods.size >= 18, "found ${methods.size} methods")
        for (m in methods) {
            val code = m.code ?: continue
            val decoded = InstructionDecoder.decode(dex, code)
            if (decoded is Outcome.Err) fail("${m.key.display()}: ${decoded.failure}")
            val d = (decoded as Outcome.Ok).value
            // Every code unit is covered exactly once by an instruction or a payload.
            val covered = d.insns.sumOf { it.units } + d.payloads.values.sumOf { it.units }
            assertEquals(code.insns.size, covered, m.key.display())
        }
    }

    @Test
    fun `disassembly shows the expected constructs`() {
        // D8 lowers small or very sparse switches to if-chains; sparse-switch payloads are
        // covered by the assembler round-trip tests instead.
        val dex = load("broad.dex")
        val text = Disassembler.file(dex)
        for (needle in listOf(
            ".class public LBroad;", ".implements Ljava/lang/Runnable;", ".method static arith(II)I",
            "packed-switch", ".packed-switch 0", "        :L001a",
            "fill-array-data", ".array-data 1", ".array-data 2", ".array-data 8", "1 -2 300000000000", "1 -1 127",
            ".catch Ljava/lang/ArithmeticException;", ".catchall", "monitor-enter", "monitor-exit",
            "invoke-polymorphic", "Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (II)I",
            "const-class", "instance-of", "check-cast", "const-string", "\"value: \"",
            "/range", "div-int", "rem-long", "shl-long", "int-to-byte", "double-to-long", "cmpg-float", "cmpl-double", "const/high16 v0, #0x40200000", "const-wide/high16 v3, #0x401c000000000000",
            "sget-wide", "iput-wide", "invoke-interface", "invoke-super", "new-array",
        )) {
            assertContains(text, needle)
        }
    }

    @Test
    fun `literals are decoded with their effective values`() {
        val dex = load("broad.dex")
        val m = dex.methods().first { it.name == "constants" }
        val text = Disassembler.method(m)
        // D8 folds each constant sub-expression; the folded values must be the Java results.
        assertContains(text, "const-wide v0, #${3L + 70000L + 0x7ff0000000000000L + 0x123456789L}")
        assertContains(text, "#${5 + 200 + 70000 + 0x7f000000 - 1}")
    }

    @Test
    fun `debug build exposes parameter names and lines`() {
        val dex = load("broad-debug.dex")
        val m = dex.methods().first { it.name == "arith" }
        val info = m.debugInfo ?: fail("no debug info")
        assertEquals(listOf("a", "b"), info.parameterNames)
        assertTrue(info.positions.isNotEmpty())
        val text = Disassembler.method(m)
        assertContains(text, ".param v")
        assertContains(text, "\"a\"")
        assertContains(text, ".line ")
    }
}
