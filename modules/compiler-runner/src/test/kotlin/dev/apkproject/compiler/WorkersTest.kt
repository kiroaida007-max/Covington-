package dev.apkproject.compiler

import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.ResolvedProfile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkersTest {
    @TempDir lateinit var dir: Path

    private fun profile(): ResolvedProfile {
        val root = Paths.get(System.getProperty("apkproject.root"))
        val p = Profiles.load(root.resolve(if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(p.d8Jar) && Files.isExecutable(p.z3) && Files.isExecutable(p.javac), "toolchain not installed; run scripts/setup-toolchain.sh")
        return p
    }

    @Test
    fun `timeouts kill the process tree`() {
        assumeTrue(!dev.apkproject.contracts.isWindows())
        val r = ProcessWorker.run(listOf("sh", "-c", "sleep 30 & sleep 30"), dir, timeoutMs = 300)
        assertTrue(r.timedOut)
        assertTrue(r.elapsedMs < 10_000)
    }

    @Test
    fun `logs are bounded`() {
        assumeTrue(!dev.apkproject.contracts.isWindows())
        val r = ProcessWorker.run(listOf("sh", "-c", "yes x | head -c 100000"), dir, timeoutMs = 10_000, maxLogBytes = 1000)
        assertEquals(1000, r.stdout.length)
        assertTrue(r.truncated)
    }

    @Test
    fun `compiles java to dex and runs it in a separate JVM`() {
        val p = profile()
        val src = "package demo;\npublic class A {\n  public static int twice(int x) { return x + x; }\n  public static int div(int a, int b) { return a / b; }\n}\n"
        val result = ForwardCompiler(p).compile(mapOf("demo/A.java" to src), dir.resolve("c"), 120_000)
        assertEquals(CheckStatus.PASS, result.status, result.diagnostics)
        assertTrue(Files.size(result.dexFile!!) > 0)
        assertEquals(2, result.argvManifest.size)

        val obs = JvmHarness(p).run(
            listOf(dir.resolve("c/classes")),
            listOf(Invocation("demo.A", "twice", intArrayOf(21)), Invocation("demo.A", "div", intArrayOf(1, 0)), Invocation("demo.A", "nope", intArrayOf())),
            dir.resolve("run"), 60_000,
        )
        assertEquals(listOf("ok 42", "throw java.lang.ArithmeticException", "error java.lang.NoSuchMethodException"), obs.map { it.toString() })
    }

    @Test
    fun `reports compile errors as FAIL with diagnostics`() {
        val p = profile()
        val result = ForwardCompiler(p).compile(mapOf("B.java" to "class B { int f() { return x; } }"), dir.resolve("b"), 60_000)
        assertEquals(CheckStatus.FAIL, result.status)
        assertContains(result.diagnostics, "cannot find symbol")
    }

    @Test
    fun `z3 answers sat with a model and unsat`() {
        val p = profile()
        val z3 = Z3Runner(p)
        val sat = z3.check("(declare-const x (_ BitVec 32))\n(assert (= (bvadd x #x00000001) #x00000000))\n(check-sat)\n(get-value (x))\n", dir, 10_000)
        assertEquals(SolverAnswer.Sat(mapOf("x" to "#xffffffff")), sat)
        assertEquals(-1, Z3Runner.bv32("#xffffffff"))
        val unsat = z3.check("(declare-const x (_ BitVec 32))\n(assert (not (= (bvadd x x) (bvmul x #x00000002))))\n(check-sat)\n", dir, 10_000)
        assertEquals(SolverAnswer.Unsat, unsat)
    }
}
