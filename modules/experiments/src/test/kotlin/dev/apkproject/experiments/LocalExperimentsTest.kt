package dev.apkproject.experiments

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.lift.CodeShape
import dev.apkproject.lift.DexLifter
import dev.apkproject.lift.StructuralMatch
import dev.apkproject.reconstruct.ReconstructOptions
import dev.apkproject.reconstruct.Reconstructor
import dev.apkproject.source.JavaPrinter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class LocalExperimentsTest {
    @TempDir lateinit var dir: Path

    private fun profile(): ResolvedProfile {
        val p = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(p.d8Jar), "toolchain not installed")
        return p
    }

    @Test
    fun `probes recover the branch layout that reproduces the original bytecode`() {
        val p = profile()
        // Hidden "original": compiled by the forward toolchain, then only its DEX is used.
        val original = "public class Orig {\n" +
            "  public static int pick(int a, int b) { if (a > b) { return a - b; } else { return b * 3; } }\n" +
            "  public static int sign(int x) { if (x == 0) return 0; return x < 0 ? -1 : 1; }\n}\n"
        val built = ForwardCompiler(p).compile(mapOf("Orig.java" to original), dir.resolve("orig"), 120_000)
        assertEquals(CheckStatus.PASS, built.status, built.diagnostics)
        val dex = (DexFile.parse(Files.readAllBytes(built.dexFile!!)) as Outcome.Ok).value

        val requests = dex.methods().filter { it.name in setOf("pick", "sign") }.map { m ->
            val ir = assertNotNull(DexLifter.lift(m).ir)
            val shape = CodeShape.of(dex, (InstructionDecoder.decode(dex, m.code!!) as Outcome.Ok).value)
            // Start from a deliberately different layout so the probes have something to find.
            val base = Reconstructor.reconstruct(ir, m.name, listOf("public", "static"))
            val skewed = Reconstructor.reconstruct(ir, m.name, listOf("public", "static"), ReconstructOptions(invertedIfs = base.branchBlocks.toSet(), ternary = false))
            LocalExperiments.Request(ir, skewed, shape, m.name, listOf("public", "static"))
        }
        val results = LocalExperiments(p, Budget(maxProbesPerPattern = 12)).run(requests, dir.resolve("probes"))
        for ((name, r) in results) {
            assertNotEquals("NO_MATCH", r.record.outcome, "$name: ${r.record}")
            assertEquals(StructuralMatch.IDENTICAL, r.bestShape?.match, "$name: ${r.record}")
        }
        val pick = results.entries.single { it.key.contains("pick") }.value
        val text = JavaPrinter.method(pick.best!!.method)
        assertContains(text, "if (p0 > p1)")
    }

    @Test
    fun `mutations change meaning in a visible way`() {
        val m = Reconstructor.reconstruct(
            assertNotNull(
                DexLifter.lift(
                    (
                        DexFile.parse(
                            dev.apkproject.dexasm.Assembler.assemble(
                                ".class public LT;\n.super Ljava/lang/Object;\n.method public static f(I)I\n.registers 4\nadd-int/lit8 v0, v3, #5\nreturn v0\n.end method\n",
                            ),
                        ) as Outcome.Ok
                        ).value.methods().single(),
                ).ir,
            ),
            "f", listOf("static"),
        )
        val mutant = assertNotNull(Mutations.mutate(m.method))
        assertEquals("+ -> -", mutant.description)
        assertContains(JavaPrinter.method(mutant.method), "return p0 - 5;")
    }
}
