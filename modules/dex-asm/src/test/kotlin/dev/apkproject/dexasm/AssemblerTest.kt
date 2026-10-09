package dev.apkproject.dexasm

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.Disassembler
import dev.apkproject.dex.InstructionDecoder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.fail

class AssemblerTest {
    private val noBanner = Disassembler.Options(includeBanner = false)

    private fun parse(bytes: ByteArray): DexFile = when (val o = DexFile.parse(bytes)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> fail("assembled DEX does not parse: ${o.failure}")
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream(name)?.readBytes() ?: fail("missing $name")

    /** Debug-local comments are not reproduced by the assembler. */
    private fun stripLocals(text: String) = text.lines().filterNot { it.trimStart().startsWith("; local ") }.joinToString("\n")

    @Test
    fun `D8 output round-trips through disassembly and assembly`() {
        for (name in listOf("broad.dex", "broad-debug.dex")) {
            val original = parse(resource("/d8/$name"))
            val text1 = stripLocals(Disassembler.file(original, noBanner))
            val rebuilt = parse(Assembler.assemble(text1))
            val text2 = stripLocals(Disassembler.file(rebuilt, noBanner))
            assertEquals(text1, text2, "round trip of $name")
        }
    }

    @Test
    fun `every format assembles, decodes and reaches a fixpoint`() {
        val source = String(resource("/all-formats.dasm"))
        val dex = parse(Assembler.assemble(source))
        for (m in dex.methods()) m.code?.let { code ->
            val d = InstructionDecoder.decode(dex, code)
            if (d is Outcome.Err) fail("${m.name}: ${d.failure}")
        }
        val text1 = Disassembler.file(dex, noBanner)
        val text2 = Disassembler.file(parse(Assembler.assemble(text1)), noBanner)
        assertEquals(text1, text2)
        for (needle in listOf(
            "move/16 v299, v297", "move-wide/16 v280, v298", "const/4 v0, #-8", "const/16 v2, #-32768",
            "const/high16 v4, #0x7f010000", "const-wide v10, #-9223372036854775808", "const-wide/high16 v12, #0x4000000000000000",
            "\"tab\\there \\\"quoted\\\" \\u00e9 ; not a comment\"", "const-string/jumbo v15, \"jumbo\"",
            "filled-new-array {v0, v1, v2, v3, v4}, [I", "filled-new-array/range {v20 .. v25}, [I",
            "invoke-static/range {}, LOther;->none()V", "invoke-polymorphic {v9, v0}", ", (I)V",
            "const-method-type v0, (IJ)Ljava/lang/String;", "rsub-int v0, v1, #-32768", "mul-int/lit8 v0, v1, #-128",
            "goto/32 :L", ".packed-switch -2", "-100 -> :L", "2000000000 -> :L", ".array-data 2", "-1 0 32767 -32768 7",
            ".catch Ljava/lang/RuntimeException;", ".catchall", ".param v297 \"x\"", ".param v298 \"big\"", ".line 11",
            ".method public native ignored()V", ".field private static counter:I",
        )) assertContains(text1, needle)
    }

    @Test
    fun `assembler reports precise errors`() {
        fun asmError(body: String, registers: Int = 4): String = assertThrows<AsmException> {
            Assembler.assemble(".class public LX;\n.super Ljava/lang/Object;\n.method public static f(I)I\n.registers $registers\n$body\n.end method\n")
        }.message!!
        assertContains(asmError("goto :nowhere"), "undefined label")
        assertContains(asmError("add-int/2addr v16, v1\nreturn v0", registers = 20), "does not fit")
        assertContains(asmError("const/4 v0, #8\nreturn v0"), "does not fit 4 signed bits")
        assertContains(asmError(":l\nif-eqz v0, :l\nreturn v0"), "must not be zero")
        assertContains(asmError("move v9, v0\nreturn v0"), "outside .registers")
        assertContains(asmError("const/high16 v0, #0x10001\nreturn v0"), "low 16 bits")
        assertContains(asmError("0003: return v0"), "declared address")
        assertContains(asmError("frobnicate v0"), "unknown instruction")
        assertContains(asmError("return-wide v3"), "exceeds .registers")
    }

    @Test
    fun `D8 independently accepts assembled DEX`(@TempDir dir: Path) {
        val d8 = d8Jar()
        assumeTrue(d8 != null, "D8 jar not installed; run scripts/setup-toolchain.sh")
        val original = parse(resource("/d8/broad.dex"))
        val rebuilt = Assembler.assemble(Disassembler.file(original, noBanner))
        val input = dir.resolve("rebuilt.dex")
        Files.write(input, rebuilt)
        val out = Files.createDirectories(dir.resolve("out"))
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val p = ProcessBuilder(java, "-cp", d8.toString(), "com.android.tools.r8.D8", "--min-api", "28", "--output", out.toString(), input.toString())
            .redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        assertEquals(true, p.waitFor(120, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), "D8 rejected assembled DEX:\n$log")
        parse(Files.readAllBytes(out.resolve("classes.dex")))
    }

    companion object {
        fun d8Jar(): Path? {
            val tools = System.getenv("APKPROJECT_TOOLS") ?: return null
            return Paths.get(tools, "r8-8.7.18.jar").takeIf { Files.isRegularFile(it) }
        }
    }
}
