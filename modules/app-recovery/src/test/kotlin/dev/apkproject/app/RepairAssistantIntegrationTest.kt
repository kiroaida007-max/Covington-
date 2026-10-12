package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepairAssistantIntegrationTest {
    @TempDir lateinit var dir: Path
    private val file = "p/C.java"
    private fun source(body: String, type: String = "int", params: String = "int value, int shift") =
        "package p;\npublic class C {\n public static $type work($params) $body\n public static int protectedMethod() { return 7; }\n}\n"
    private fun profile() = Profiles.load(Path.of(System.getProperty("apkproject.root")).resolve(
        if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/android-full.json")).also { p ->
        assumeTrue(listOfNotNull(p.androidJar, p.ecjJar, p.d8Jar).size == 3 &&
            listOfNotNull(p.androidJar, p.ecjJar, p.d8Jar).all(Files::isRegularFile), "full toolchain required")
    }

    private class Proposal(private val reply: String, private val regressOther: Boolean = false) : RepairAssistant {
        override val identity = "test-only"
        override val available get() = !called
        private var called = false
        var kept: Boolean? = null
        var compiled: Boolean? = null
        var label: RepairLearning.CheckedLabel? = null
        var originalRecovery = false
        override fun propose(file: String, source: String, methodKey: String, original: DexMethod, timeoutMs: Long,
            excludedUnits: Set<String>, diagnostics: String): RepairCandidate? {
            if (original.name != "work") return null
            val c = AssistantPatch.candidate(file, source, methodKey, reply) ?: return null
            called = true
            return c.copy(text = if (regressOther) c.text.replace("return 7;", "return 9;") else c.text,
                origin = RepairProposalOrigin(modelId = "test-only", modelSha256 = "0".repeat(64), runtimeSha256 = "0".repeat(64),
                    knowledgeSha256 = "0".repeat(64), promptSha256 = "0".repeat(64), references = emptyList(), inferenceMs = 0, peakResidentBytes = 0))
        }
        override fun checked(candidate: RepairCandidate, label: RepairLearning.CheckedLabel?, compiled: Boolean, accepted: Boolean, originalRecovery: Boolean) {
            this.kept = accepted; this.compiled = compiled; this.label = label; this.originalRecovery = originalRecovery
        }
        override fun report(): RepairAssistantReport = error("unused")
        override fun memory() = RepairKnowledge(entries = emptyList())
    }

    @Test fun `compiler and all-method comparison reject invalid incorrect and regressing generated proposals`() {
        val p = profile()
        val good = source("{ return value >>> shift; }")
        val broken = source("{ return value >> shift; }")
        val built = ForwardCompiler(p).compile(mapOf(file to good), dir.resolve("original"), 120_000)
        assertEquals(CheckStatus.PASS, built.status, built.diagnostics)
        val dex = (DexFile.parse(Files.readAllBytes(built.dexFile!!)) as Outcome.Ok).value
        val hints = AssistantIntegerHints.cases(dex.methods().single { it.name == "work" })
        assertTrue(hints.size <= 10 && hints.any { it.arguments == listOf(-1, 1) && it.returned == Int.MAX_VALUE })
        assertTrue(hints.all { it.evidence == "V0_IR_MODELED_EXECUTION" })
        val cases = listOf(
            Triple("compile failure", "{ return missing; }", false),
            Triple("wrong behavior", "{ return 0; }", false),
            Triple("other method regression", "{ return value >>> shift; }", true),
            Triple("correct", "{ return value >>> shift; }", false),
        )
        for ((name, body, regression) in cases) {
            val work = dir.resolve("work with spaces/$name"); Files.createDirectories(work)
            val java = work.resolve("java"); Files.createDirectories(java.resolve("p")); Files.writeString(java.resolve(file), broken)
            val classes = work.resolve("classes")
            val (_, diagnostics) = EcjCompiler(p).compile(java, emptyList(), classes, work.resolve("ecj"), 120_000, listOf("p"))
            val verifier = RoundTripVerifier(p)
            val original = verifier.originalByClass(listOf("original.dex" to dex), setOf(file))
            val (_, baseline) = verifier.verify(listOf("original.dex" to dex), classes, emptyList(), diagnostics, setOf(file), listOf("p"), 26, work.resolve("baseline"), 120_000)
            val assistant = Proposal(dev.apkproject.contracts.Json.encode(mapOf("body" to body)), regression)
            val (_, repairs, final) = SourceRepair(p, verifier, null, {}, assistant = assistant).run(java, classes, emptyList(), original,
                baseline, diagnostics, 26, work.resolve("repair"), 120_000)
            if (name == "correct") {
                assertTrue(assistant.kept == true, name)
                assertEquals(good, Files.readString(java.resolve(file)))
                assertEquals(1, repairs.size)
                assertTrue(final.all { it.result in RoundTripVerifier.EQUIVALENT })
            } else {
                assertTrue(assistant.kept == false, name)
                assertEquals(broken, Files.readString(java.resolve(file)), name)
                assertTrue(repairs.isEmpty(), name)
                if (name == "compile failure") assertFalse(assistant.compiled!!)
                else assertEquals(0, assistant.label?.label, name)
            }
        }
    }

    @Test fun `uncompiled source is restored only after every original method matches without manufacturing training labels`() {
        val p = profile()
        val good = source("{ return 9223372036854775807L; }", "long", "")
        val broken = good.replace("5807L", "5807")
        val built = ForwardCompiler(p).compile(mapOf(file to good), dir.resolve("original"), 120_000)
        assertEquals(CheckStatus.PASS, built.status, built.diagnostics)
        val dex = (DexFile.parse(Files.readAllBytes(built.dexFile!!)) as Outcome.Ok).value
        assertTrue(AssistantIntegerHints.cases(dex.methods().single { it.name == "work" }).isEmpty())
        val java = dir.resolve("java"); Files.createDirectories(java.resolve("p")); Files.writeString(java.resolve(file), broken)
        val classes = dir.resolve("classes")
        val (_, diagnostics) = EcjCompiler(p).compile(java, emptyList(), classes, dir.resolve("ecj"), 120_000, listOf("p"))
        assertTrue(diagnostics.getValue(file).errors > 0)
        val verifier = RoundTripVerifier(p)
        val original = verifier.originalByClass(listOf("original.dex" to dex), setOf(file))
        val baseline = original.flatMap { (owner, methods) -> methods.map { (signature, _) -> MethodRoundTrip("$owner->$signature", "NOT_COMPILED", file) } }
        val assistant = Proposal("""{"body":"{ return 9223372036854775807L; }"}""")
        val work = dir.resolve("repair")
        val (_, repairs, final) = SourceRepair(p, verifier, null, {}, assistant = assistant).run(java, classes, emptyList(), original,
            baseline, diagnostics, 26, work, 120_000)
        assertTrue(assistant.kept == true && assistant.originalRecovery)
        assertNull(assistant.label)
        assertEquals(good, Files.readString(java.resolve(file)))
        assertTrue(repairs.single().fromCompilationError)
        assertTrue(final.all { it.result in RoundTripVerifier.EQUIVALENT })
        assertFalse(Files.exists(work.resolve("learning-repairs.jsonl")))
    }
}
