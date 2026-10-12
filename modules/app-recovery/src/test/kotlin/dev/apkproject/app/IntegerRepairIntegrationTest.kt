package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class IntegerRepairIntegrationTest {
    @TempDir lateinit var dir: Path
    private fun profile() = Profiles.load(Path.of(System.getProperty("apkproject.root")).resolve(
        if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/android-full.json")).also { p ->
        assumeTrue(listOfNotNull(p.androidJar, p.ecjJar, p.d8Jar).size == 3 &&
            listOfNotNull(p.androidJar, p.ecjJar, p.d8Jar).all(Files::isRegularFile), "full toolchain required")
    }

    @Test fun `bounded reconstructions restore boundaries negation exception effects and join locals before model use`() {
        val p = profile()
        val bodies = linkedMapOf(
            "boundary" to "return t0 >= 8 ? 1 : 0;",
            "negation" to "return -t0;",
            "unusedDivision" to "int ignored = t0 / t1; return 0;",
            "join" to "int k; if (t0 < 4) k = t1 + 5; else k = t1 - 5; return k * t0;",
        )
        val file = "p/C.java"
        fun source(replace: String? = null) = "package p;\npublic class C {\n" + bodies.entries.joinToString("\n") { (name, body) ->
            " public static int $name(int t0, int t1) { ${if (name == replace) "return missing;" else body} }"
        } + "\n public static int untouched() { return 7; }\n}\n"
        val built = ForwardCompiler(p).compile(mapOf(file to source()), dir.resolve("original"), 120_000)
        assertEquals(CheckStatus.PASS, built.status, built.diagnostics)
        val dex = (DexFile.parse(Files.readAllBytes(built.dexFile!!)) as Outcome.Ok).value
        val java = dir.resolve("java"); Files.createDirectories(java.resolve("p"))
        for (name in bodies.keys) {
            Files.writeString(java.resolve(file), source(name))
            val work = dir.resolve(name)
            val classes = work.resolve("classes")
            val (_, diagnostics) = EcjCompiler(p).compile(java, emptyList(), classes, work.resolve("ecj"), 120_000, listOf("p"))
            val verifier = RoundTripVerifier(p)
            val original = verifier.originalByClass(listOf("original.dex" to dex), setOf(file))
            val baseline = original.flatMap { (owner, methods) -> methods.map { (signature, _) -> MethodRoundTrip("$owner->$signature", "NOT_COMPILED", file) } }
            val (_, repairs, final) = SourceRepair(p, verifier, null, {}).run(java, classes, emptyList(), original,
                baseline, diagnostics, 26, work.resolve("repair"), 120_000)
            assertEquals(1, repairs.size, name)
            assertTrue(repairs.single().fromCompilationError, name)
            assertNotNull(repairs.single().reconstruction, name)
            assertNull(repairs.single().origin, name)
            assertEquals(6, final.size)
            assertTrue(final.all { it.result in RoundTripVerifier.EQUIVALENT }, "$name: $final")
            assertContains(Files.readString(java.resolve(file)), "untouched() { return 7; }")
            assertFalse(Files.exists(work.resolve("repair/learning-repairs.jsonl")), name)
        }
    }
}
