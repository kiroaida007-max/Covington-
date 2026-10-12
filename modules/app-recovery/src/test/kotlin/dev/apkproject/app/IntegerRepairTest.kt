package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dexasm.Assembler
import org.junit.jupiter.api.Test
import kotlin.test.*

class IntegerRepairTest {
    private fun method(body: String, flags: String = "public static", proto: String = "(I)I"): DexMethod {
        val text = ".class public Lp/C;\n.super Ljava/lang/Object;\n.method $flags work$proto\n.registers 4\n$body\n.end method\n"
        return (DexFile.parse(Assembler.assemble(text)) as Outcome.Ok).value.methods().single()
    }
    private val source = "package p;\npublic class C {\n public static int work(int renamed) { return renamed; }\n public static int untouched() { return 7; }\n}\n"

    @Test fun `integer reconstruction keeps the source declaration and parameter names and records bounded provenance`() {
        val original = method("neg-int v0, v3\nreturn v0")
        val candidate = assertNotNull(IntegerRepair.candidate("p/C.java", source, "Lp/C;->work(I)I", original))
        assertContains(candidate.text, "public static int work(int renamed)")
        assertContains(candidate.text, "return -renamed;")
        assertContains(candidate.text, "untouched() { return 7; }")
        assertEquals("integer-ir", candidate.rule)
        assertEquals("INFERRED_RECONSTRUCTED", candidate.reconstruction?.evidence)
        assertEquals(original.dex.sha256, candidate.reconstruction?.originalDexSha256)
        assertTrue(assertNotNull(candidate.reconstruction).rules.size <= 128)
        assertNull(candidate.origin)
        assertNull(IntegerRepair.candidate("p/C.java", candidate.text, "Lp/C;->work(I)I", original))
    }

    @Test fun `loops calls noninteger and instance methods and ambiguous declarations are never approximated`() {
        for (original in listOf(
            method(":again\nadd-int/lit8 v3, v3, #1\nif-ltz v3, :again\nreturn v3"),
            method("invoke-static {v3}, Lp/Other;->opaque(I)I\nmove-result v0\nreturn v0"),
            method("return v3", flags = "public"),
            method("return v3", flags = "public static synchronized"),
            method("return v3", flags = "public static declared-synchronized"),
            method("const-wide/16 v0, #5\nreturn-wide v0", proto = "(I)J"))) {
            assertNull(IntegerRepair.candidate("p/C.java", source, "Lp/C;->work(I)I", original))
        }
        val original = method("neg-int v0, v3\nreturn v0")
        assertNull(IntegerRepair.candidate("p/C.java", source + "class Other {\n static int work(int x) { return x; }\n}", "Lp/C;->work(I)I", original))
        assertNull(IntegerRepair.candidate("p/C.java", source.replace("int renamed", "int invalid, int invalid"), "Lp/C;->work(I)I", original))
        assertNull(IntegerRepair.candidate("p/C.java", source.replace("static int work", "static synchronized int work"), "Lp/C;->work(I)I", original))
        assertNull(IntegerRepair.candidate("p/C.java", source.replace("static int work", "static synchr\\u006fnized int work"), "Lp/C;->work(I)I", original))
    }
}
