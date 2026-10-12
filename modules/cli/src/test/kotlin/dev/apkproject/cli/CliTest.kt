package dev.apkproject.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliTest {
    @TempDir lateinit var dir: Path

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = run(args.toList(), { out.append(it).append('\n') }, { err.append(it).append('\n') })
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `usage and configuration errors exit with 2`() {
        assertEquals(0, cli("--help").first)
        assertEquals(2, cli().first)
        val (code, _, err) = cli("frobnicate")
        assertEquals(2, code)
        assertContains(err, "unknown command")
        assertEquals(2, cli("inspect", "x.apk").first) // missing --out
        val reference = cli("contract", "x.apk", "--out", dir.resolve("r").toString(), "--reference-types", "reference.json")
        assertEquals(2, reference.first)
        assertContains(reference.third, "must be supplied together")
        val runtime = cli("runtime", "x.apk", "--out", dir.resolve("runtime").toString())
        assertEquals(2, runtime.first)
        assertContains(runtime.third, "missing --adb")
        assertEquals("model.json", Args(listOf("--repair-model", "model.json")).option("--repair-model"))
    }

    @Test
    fun `asm and disasm round trip through the command line`() {
        val src = dir.resolve("t.dasm")
        Files.writeString(src, ".class public LT;\n.super Ljava/lang/Object;\n.method public static f(I)I\n.registers 2\nmul-int/lit8 v0, v1, #3\nreturn v0\n.end method\n")
        val dex = dir.resolve("t.dex")
        assertEquals(0, cli("asm", src.toString(), "--out", dex.toString()).first)
        val (code, out, _) = cli("disasm", dex.toString())
        assertEquals(0, code)
        assertContains(out, "mul-int/lit8 v0, v1, #3")
    }

    @Test
    fun `malformed input exits with 3`() {
        val bad = dir.resolve("bad.apk").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        assertEquals(3, cli("disasm", bad.toString()).first)
        assertEquals(3, cli("inspect", bad.toString(), "--out", dir.resolve("o").toString()).first)
        val asm = dir.resolve("bad.dasm").also { Files.writeString(it, ".class LX;\n.method static f()V\n.registers 1\nbogus\n.end method\n") }
        assertEquals(3, cli("asm", asm.toString(), "--out", dir.resolve("x.dex").toString()).first)
    }
}
