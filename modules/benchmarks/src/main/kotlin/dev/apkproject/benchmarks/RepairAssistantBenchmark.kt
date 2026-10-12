package dev.apkproject.benchmarks

import dev.apkproject.app.*
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
private data class AssistantBenchmarkCase(val case: String, val mode: String, val baselineEquivalent: Int, val baselineMethods: Int,
    val finalEquivalent: Int, val accepted: Int, val compilationRestored: Boolean, val elapsedMs: Long,
    val assistant: RepairAssistantReport?)

/** Controlled generated faults, not production accuracy evidence. Same known input DEX in every arm. */
object RepairAssistantBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3 && (args.size == 2 || args[2] == "--extended")) { "RepairAssistantBenchmark ASSISTANT_CONFIG.json NEW_OUTPUT_DIR [--extended]" }
        val config = Path.of(args[0]).toAbsolutePath(); val out = Path.of(args[1]).toAbsolutePath()
        require(!Files.exists(out)); Files.createDirectories(out)
        val profile = Profiles.load(Path.of("profiles/android-full.json"))
        val fixtures = listOf(
            Triple("unsigned-shift", "public static int work(int value, int shift) { return value >>> shift; }", "public static int work(int value, int shift) { return value >> shift; }"),
            Triple("boundary", "public static int work(int value) { return value >= 8 ? 1 : 0; }", "public static int work(int value) { return value > 8 ? 1 : 0; }"),
            Triple("long-literal", "public static long work() { return 9223372036854775807L; }", "public static long work() { return 9223372036854775807; }"),
            // Additional cases declared before running the revised protocol; keep failures in the denominator.
            Triple("bitwise-xor", "public static int work(int value, int other) { return value ^ other; }", "public static int work(int value, int other) { return value | other; }"),
            Triple("negation", "public static int work(int value) { return -value; }", "public static int work(int value) { return value; }")
        ) + if (args.size == 3) listOf(
            Triple("overflow-order", "public static int work(int value) { return (value + 1) * value; }", "public static int work(int value) { return value + 1 * value; }"),
            Triple("char-narrowing", "public static int work(int value) { return (char) value; }", "public static int work(int value) { return (short) value; }"),
            Triple("division-branch", "public static int work(int value, int other) { return value < 0 ? other / value : other % value; }", "public static int work(int value, int other) { return value < 0 ? other % value : other / value; }"),
            Triple("discarded-division-exception", "public static int work(int t0, int t1) { int ignored = t0 / t1; return 0; }", "public static int work(int t0, int t1) { return 0; }"),
            Triple("join-local-collision", "public static int work(int t0, int t1) { int k; if (t0 < 4) k = t1 + 5; else k = t1 - 5; return k * t0; }", "public static int work(int t0, int t1) { int k; if (t0 < 4) k = t1 + 5; else k = t1 + 5; return k * t0; }")
        ) else emptyList()
        val results = arrayListOf<AssistantBenchmarkCase>()
        val snapshots = out.resolve("assistant-snapshot")
        for ((name, good, broken) in fixtures) {
            fun source(method: String) = "package experiment;\npublic class Sample {\n    $method\n    public static int protectedMethod(int value) { return value + 7; }\n}\n"
            val file = "experiment/Sample.java"
            val inputDir = out.resolve(name); Files.createDirectories(inputDir)
            val built = ForwardCompiler(profile).compile(mapOf(file to source(good)), inputDir.resolve("original"), 120_000)
            require(built.status == CheckStatus.PASS) { built.diagnostics }
            val dex = (DexFile.parse(Files.readAllBytes(built.dexFile!!)) as Outcome.Ok).value
            for (mode in listOf("legacy-rules", "integer-engine", "local-no-rag", "local-rag")) {
                val work = inputDir.resolve(mode); Files.createDirectories(work)
                val javaRoot = work.resolve("java"); Files.createDirectories(javaRoot.resolve("experiment"))
                Files.writeString(javaRoot.resolve(file), source(broken))
                val ecj = EcjCompiler(profile)
                val classes = work.resolve("classes")
                val (compilation, diagnostics) = ecj.compile(javaRoot, emptyList(), classes, work.resolve("ecj"), 120_000, listOf("experiment"))
                val verifier = RoundTripVerifier(profile)
                val original = verifier.originalByClass(listOf("original.dex" to dex), setOf(file))
                var (_, baseline) = verifier.verify(listOf("original.dex" to dex), classes, emptyList(), diagnostics, setOf(file), listOf("experiment"), 26, work.resolve("baseline"), 120_000)
                if (baseline.isEmpty()) baseline = original.flatMap { (owner, members) -> members.map { (signature, _) -> MethodRoundTrip("$owner->$signature", "NOT_COMPILED", file) } }
                val cfg = Json.decode<LocalAssistantConfig>(Files.readString(config)).copy(retrieval = mode == "local-rag", maxCalls = 2, totalBudgetMs = 90_000)
                // Keep source-relative artifact paths while varying only the declared retrieval arm.
                val rewritten = config.parent.resolve("benchmark-$mode.json")
                Files.writeString(rewritten, Json.encode(cfg))
                val assistant = if (!mode.startsWith("local-")) null else LocalRepairAssistant.prepare(rewritten, snapshots, log = { println("$name/$mode: $it") }, observeInference = { prompt, reply ->
                    Files.writeString(work.resolve("synthetic-prompt.txt"), prompt); Files.writeString(work.resolve("synthetic-reply.txt"), reply)
                })
                if (mode.startsWith("local-")) require(assistant != null) { "assistant not available; benchmark did not measure model inference" }
                val start = System.nanoTime()
                val (summary, repairs, final) = SourceRepair(profile, verifier, null, { println("$name/$mode: $it") }, assistant = assistant, integerReconstruction = mode != "legacy-rules")
                    .run(javaRoot, classes, emptyList(), original, baseline, diagnostics, 26, work.resolve("repair"), 150_000)
                val report = assistant?.report()
                report?.let { Files.writeString(work.resolve("assistant.json"), Json.encode(it)) }
                Files.deleteIfExists(rewritten)
                val result = AssistantBenchmarkCase(name, mode, baseline.count { it.result in RoundTripVerifier.EQUIVALENT }, baseline.count { it.result != "SYNTHETIC" },
                    final.count { it.result in RoundTripVerifier.EQUIVALENT }, summary.accepted, repairs.any { it.fromCompilationError }, (System.nanoTime() - start) / 1_000_000, report)
                results += result; println(Json.encodeLine(result))
                Files.writeString(out.resolve("results.json"), Json.encode(results))
            }
        }
        Files.writeString(out.resolve("scope.txt"), "Generated small-method faults, selected before inference. Public pretrained weights and fixed retrieved training-family examples. Does not measure independent real-APK recovery accuracy. No generated labels enter the production corpus.\n")
    }
}
