package dev.apkproject.validate

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.SolverAnswer
import dev.apkproject.compiler.Z3Runner
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dexasm.Assembler
import dev.apkproject.ir.IrEvaluator
import dev.apkproject.ir.MethodIr
import dev.apkproject.lift.DexLifter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.apkproject.ir.Outcome as Run

class ValidateTest {
    @TempDir lateinit var dir: Path

    private fun profile(): ResolvedProfile {
        val p = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve(if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isExecutable(p.z3) && Files.isRegularFile(p.d8Jar), "toolchain not installed; run scripts/setup-toolchain.sh")
        return p
    }

    private fun ir(body: String, proto: String = "(I)I", registers: Int = 4): MethodIr {
        val text = ".class public LT;\n.super Ljava/lang/Object;\n.method public static f$proto\n.registers $registers\n$body\n.end method\n"
        val m = (DexFile.parse(Assembler.assemble(text)) as Outcome.Ok).value.methods().single()
        return assertNotNull(DexLifter.lift(m).ir)
    }

    private fun equivalence(a: MethodIr, b: MethodIr): SolverAnswer = Z3Runner(profile()).check(SmtEncoder.equivalenceQuery(a, b), dir, 10_000)

    private fun assertDiffers(a: MethodIr, b: MethodIr) {
        val answer = equivalence(a, b)
        assertIs<SolverAnswer.Sat>(answer)
        val cex = IntArray(a.signature.params.size) { Z3Runner.bv32(answer.model.getValue("p$it")) }
        // The counterexample must replay in the independent evaluator.
        assertTrue(IrEvaluator.run(a, cex) != IrEvaluator.run(b, cex), "counterexample ${cex.toList()} does not replay")
    }

    private val samples by lazy {
        listOf(
            ir("add-int v0, v3, v3\nreturn v0"),
            ir("if-gez v3, :d\nneg-int v3, v3\n:d\nreturn v3"),
            ir("div-int v0, v2, v3\nrem-int v1, v2, v3\nadd-int/2addr v0, v1\nreturn v0", "(II)I"),
            ir("shl-int v0, v2, v3\nshr-int v1, v2, v3\nxor-int/2addr v0, v1\nushr-int v1, v2, v3\nxor-int/2addr v0, v1\nreturn v0", "(II)I"),
            ir("int-to-byte v0, v3\nint-to-char v1, v3\nadd-int/2addr v0, v1\nint-to-short v1, v3\nmul-int/2addr v0, v1\nreturn v0"),
            ir("if-ge v1, v2, :a\nreturn v2\n:a\nif-le v1, v3, :b\nreturn v3\n:b\ndiv-int/lit8 v0, v1, #-1\nreturn v0", "(III)I"),
            ir("rsub-int v0, v3, #-32768\nmul-int/lit16 v0, v0, #12345\nand-int/lit8 v0, v0, #-16\nreturn v0"),
        )
    }

    @Test
    fun `SMT encoding agrees with the IR evaluator on concrete inputs`() {
        val z3 = Z3Runner(profile())
        for (m in samples) {
            val inputs = Inputs.generate(m, 40)
            val out = z3.raw(SmtEncoder.evaluationScript(m, inputs), dir, 60_000)
            val answers = Regex("""\(\(m_threw (?:true|false)\)\s*\(m_result #x[0-9a-f]+\)\)""").findAll(out).map { it.value }.toList()
            assertEquals(inputs.size, answers.size, out.take(300))
            inputs.forEachIndexed { i, args ->
                val model = Z3Runner.parseModel(answers[i])
                val smt = if (model.getValue("m_threw") == "true") Run.Threw("java.lang.ArithmeticException") else Run.Returned(Z3Runner.bv32(model.getValue("m_result")))
                assertEquals(IrEvaluator.run(m, args), smt, "${m.key.display()} at ${args.toList()}")
            }
        }
    }

    @Test
    fun `equivalent forms are proved`() {
        assertEquals(SolverAnswer.Unsat, equivalence(ir("add-int v0, v3, v3\nreturn v0"), ir("mul-int/lit8 v0, v3, #2\nreturn v0")))
        assertEquals(SolverAnswer.Unsat, equivalence(ir("add-int v0, v3, v3\nreturn v0"), ir("shl-int/lit8 v0, v3, #33\nreturn v0")))
        // abs written with the opposite branch polarity
        assertEquals(SolverAnswer.Unsat, equivalence(samples[1], ir("if-ltz v3, :n\nreturn v3\n:n\nneg-int v0, v3\nreturn v0")))
        // shift distances are masked: x << 32 == x
        assertEquals(SolverAnswer.Unsat, equivalence(ir("shl-int/lit8 v0, v3, #32\nreturn v0"), ir("return v3")))
    }

    @Test
    fun `negative cases from section 19 are caught`() {
        // overflowing arithmetic: x + 1 > x is false at MAX_VALUE
        assertDiffers(ir("add-int/lit8 v0, v3, #1\nif-le v0, v3, :f\nconst/4 v0, #1\nreturn v0\n:f\nconst/4 v0, #0\nreturn v0"), ir("const/4 v0, #1\nreturn v0"))
        // signed versus unsigned shift
        assertDiffers(ir("shr-int/lit8 v0, v3, #1\nreturn v0"), ir("ushr-int/lit8 v0, v3, #1\nreturn v0"))
        // signed comparison direction
        assertDiffers(ir("if-ltz v3, :n\nreturn v3\n:n\nconst/4 v0, #0\nreturn v0"), ir("if-gtz v3, :n\nreturn v3\n:n\nconst/4 v0, #0\nreturn v0"))
        // shift masking: x << 32 is x, not 0
        assertDiffers(ir("shl-int/lit8 v0, v3, #32\nreturn v0"), ir("const/4 v0, #0\nreturn v0"))
        // division by zero must throw, not return a value
        assertDiffers(
            ir("div-int v0, v2, v3\nreturn v0", "(II)I"),
            ir("if-nez v3, :d\nconst/4 v0, #0\nreturn v0\n:d\ndiv-int v0, v2, v3\nreturn v0", "(II)I"),
        )
        // a removed (dead) division still changes the outcome when the divisor is zero
        assertDiffers(ir("div-int v0, v2, v3\nreturn v2", "(II)I"), ir("return v2", "(II)I"))
        // MIN_VALUE / -1 wraps instead of throwing
        assertDiffers(ir("div-int/lit8 v0, v3, #-1\nreturn v0"), ir("neg-int v0, v3\nadd-int/lit8 v0, v0, #1\nreturn v0"))
    }

    @Test
    fun `batch validation accepts a correct candidate and rejects a seeded wrong one`() {
        val p = profile()
        val original = ir("if-gez v3, :d\nneg-int v3, v3\n:d\nreturn v3")
        val src = "package probe;\npublic class C {\n" +
            "  public static int good(int x) { return x < 0 ? -x : x; }\n" +
            "  public static int wrong(int x) { return x <= 0 ? x : -x; }\n" +
            "  public static int looped(int x) { int s = 0; for (int i = 0; i < x; i++) s++; return x < 0 ? -x : x; }\n}\n"
        val compiled = ForwardCompiler(p).compile(mapOf("probe/C.java" to src), dir.resolve("compile"), 120_000)
        assertEquals(CheckStatus.PASS, compiled.status, compiled.diagnostics)
        val units = listOf("good", "wrong", "looped").map { CandidateUnit(it, original, null, "Lprobe/C;", it, "hash-$it") }
        val results = BatchValidator(p, Budget(randomInputsPerMethod = 32)).validate(units, compiled, dir.resolve("validate")).associateBy { it.unit.id }

        val good = results.getValue("good")
        assertTrue(good.accepted, good.report.toString())
        assertEquals(CheckStatus.PASS, good.report.checks.single { it.kind == "model.consistency" }.status)

        val wrong = results.getValue("wrong")
        assertEquals(CheckStatus.FAIL, wrong.report.obligations.single().status)
        assertNotNull(wrong.counterexample)
        assertEquals(CheckStatus.FAIL, wrong.report.checks.single { it.kind == "runtime.agreement" }.status)

        // A candidate that compiles to bytecode outside V0 is never accepted: the proof is UNKNOWN.
        val looped = results.getValue("looped")
        assertEquals(CheckStatus.UNKNOWN, looped.report.obligations.single().status)
        assertTrue(!looped.accepted)
    }
}
