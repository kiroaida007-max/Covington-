package dev.apkproject.benchmarks

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.Invocation
import dev.apkproject.compiler.JvmHarness
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExportManifest
import dev.apkproject.contracts.JobState
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.RecoveryPolicy
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.orchestrator.JobOptions
import dev.apkproject.orchestrator.Operations
import dev.apkproject.orchestrator.RecoveryJob
import dev.apkproject.store.AtomicFiles
import dev.apkproject.validate.Inputs
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class FixtureSpec(val name: String, val description: String, val sources: List<String>, val expectedState: String)

@Serializable
data class MethodResult(
    val method: String,
    val status: String,
    val evidence: List<String>,
    val structuralMatch: String?,
    val independentAgreement: String?,
    val gap: String? = null,
)

@Serializable
data class FixtureResult(
    val fixture: String,
    val apkSha256: String,
    val expectedState: String,
    val state: String,
    val exitCode: Int,
    val methodsInScope: Int,
    val methodsAccepted: Int,
    val identicalBytecode: Int,
    val independentChecks: Int,
    val independentMismatches: Int,
    val seededMutants: Int,
    val seededMutantsRejected: Int,
    val exportBuilt: Boolean,
    val elapsedSeconds: Long,
    val methods: List<MethodResult>,
)

/**
 * Ground-truth evaluation (dossier section 27). The hidden source is compiled into an APK, the
 * recovery job sees only that APK, and the recovered project is compared with the hidden
 * original by executing both, independently compiled, on the same inputs.
 */
class Benchmark(private val profile: ResolvedProfile, private val work: Path, private val log: (String) -> Unit) {

    fun run(fixtureDir: Path): FixtureResult {
        val spec = Json.decode<FixtureSpec>(Files.readString(fixtureDir.resolve("fixture.json")))
        val dir = work.resolve(spec.name)
        AtomicFiles.deleteRecursively(dir)
        Files.createDirectories(dir)
        val started = System.nanoTime()

        // 1. Build the APK from hidden source (the only artifact recovery may read).
        val hiddenSources = spec.sources.associate { rel -> rel.removePrefix("hidden/") to Files.readString(fixtureDir.resolve(rel)) }
        val original = ForwardCompiler(profile).compile(hiddenSources, dir.resolve("original-build"), 120_000)
        check(original.status == CheckStatus.PASS) { "fixture ${spec.name} does not build: ${original.diagnostics}" }
        val apk = dir.resolve("${spec.name}.apk")
        Files.write(apk, zip(mapOf("classes.dex" to Files.readAllBytes(original.dexFile!!))))
        val apkSha = dev.apkproject.contracts.Hashing.sha256(apk)
        log("[${spec.name}] built ${apk.fileName} (${Files.size(apk)} bytes, sha256 $apkSha)")

        // 2. Recover from the APK alone.
        val run = dir.resolve("run")
        val result = RecoveryJob(profile, JobOptions(policy = RecoveryPolicy.FULL_SOURCE), log = { log("[${spec.name}] $it") }).run(apk, run)
        val report = Json.decode<ExportManifest>(Files.readString(run.resolve("reports/recovery.json")))

        // 3. Export (partial allowed so mixed fixtures still produce their accepted subset).
        val exported = dir.resolve("recovered-project")
        val exportResult = Operations.export(profile, run, exported, allowPartial = true)
        val exportBuilt = exportResult.exitCode.code == 0
        log("[${spec.name}] export: ${exportResult.message}")

        // 4. Independent agreement: hidden original vs exported source, both compiled from source.
        val agreement = HashMap<String, String>()
        var checks = 0
        var mismatches = 0
        if (exportBuilt) {
            val recoveredSources = Files.walk(exported.resolve("src/main/java")).use { s ->
                s.filter { it.isRegularFile() && it.extension == "java" }.toList()
            }.associate { exported.resolve("src/main/java").relativize(it).toString() to Files.readString(it) }
            val recovered = ForwardCompiler(profile).compile(recoveredSources, dir.resolve("recovered-build"), 120_000, withDex = false)
            check(recovered.status == CheckStatus.PASS) { "exported project does not compile: ${recovered.diagnostics}" }
            val accepted = report.methods.filter { it.status == "ACCEPTED" && it.method.name != "<init>" }
            val calls = ArrayList<Pair<String, Invocation>>()
            for (m in accepted) {
                val arity = m.method.prototypeDescriptor.substringAfter('(').substringBefore(')').length
                val cls = m.method.ownerDescriptor.substring(1, m.method.ownerDescriptor.length - 1).replace('/', '.')
                for (args in independentInputs(arity)) calls += m.method.display() to Invocation(cls, m.method.name, args)
            }
            val hiddenRuns = JvmHarness(profile).run(listOf(original.classesDir), calls.map { it.second }, dir.resolve("run-original"), 120_000)
            val recoveredRuns = JvmHarness(profile).run(listOf(recovered.classesDir), calls.map { it.second }, dir.resolve("run-recovered"), 120_000)
            for (i in calls.indices) {
                checks++
                val key = calls[i].first
                if (hiddenRuns[i] != recoveredRuns[i]) {
                    mismatches++
                    agreement.putIfAbsent(key, "MISMATCH at ${calls[i].second.args.toList()}: original ${hiddenRuns[i]}, recovered ${recoveredRuns[i]}")
                }
            }
            for (m in accepted) agreement.putIfAbsent(m.method.display(), "AGREE")
        }

        // 5. Seeded wrong candidates must all be rejected (M2 exit gate).
        val seededRun = dir.resolve("seeded-run")
        RecoveryJob(profile, JobOptions(policy = RecoveryPolicy.FULL_SOURCE, experiments = false, seedWrongCandidates = true)).run(apk, seededRun)
        val seededReport = Json.decode<ExportManifest>(Files.readString(seededRun.resolve("reports/recovery.json")))
        val journal = Files.readAllLines(seededRun.resolve("journal.ndjson"))
        val mutants = journal.count { it.contains("seeded-mutant") && it.contains("candidate.rejected") }
        val gaps = seededReport.methods.flatMap { it.gaps }.count { it.detail.contains("VALIDATION GAP") }
        val seededAccepted = seededReport.methodsAccepted
        log("[${spec.name}] seeded run: $mutants mutants rejected, $gaps accepted mutants, $seededAccepted methods accepted after refinement")

        val methods = report.methods.map { m ->
            MethodResult(
                method = m.method.display(),
                status = m.status,
                evidence = m.evidence.map { it.name },
                structuralMatch = m.structuralMatch,
                independentAgreement = agreement[m.method.display()],
                gap = m.gaps.firstOrNull { it.code != "note" }?.let { "${it.code}: ${it.detail}" },
            )
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000_000
        return FixtureResult(
            fixture = spec.name,
            apkSha256 = apkSha,
            expectedState = spec.expectedState,
            state = result.state.name,
            exitCode = result.exitCode.code,
            methodsInScope = report.methodsInScope,
            methodsAccepted = report.methodsAccepted,
            identicalBytecode = report.methods.count { it.structuralMatch == "IDENTICAL" },
            independentChecks = checks,
            independentMismatches = mismatches,
            seededMutants = mutants + gaps,
            seededMutantsRejected = mutants,
            exportBuilt = exportBuilt,
            elapsedSeconds = elapsed,
            methods = methods,
        ).also { check(it.state == spec.expectedState || it.state == JobState.EXPORTED.name) { "fixture ${spec.name}: expected ${spec.expectedState}, got ${it.state}" } }
    }

    /** Inputs chosen without looking at any IR: boundaries plus seeded random values. */
    private fun independentInputs(arity: Int): List<IntArray> {
        if (arity == 0) return listOf(IntArray(0))
        val edges = Inputs.EDGES
        val rnd = Random(7L + arity)
        val out = ArrayList<IntArray>()
        when (arity) {
            1 -> edges.forEach { out += intArrayOf(it) }
            2 -> for (a in edges.take(16)) for (b in edges.take(16)) out += intArrayOf(a, b)
            else -> repeat(200) { out += IntArray(arity) { edges[rnd.nextInt(edges.size)] } }
        }
        repeat(200) { out += IntArray(arity) { rnd.nextInt() } }
        return out
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                // Fixed timestamp: fixture APKs are byte-for-byte reproducible.
                z.putNextEntry(ZipEntry(name).apply { timeLocal = java.time.LocalDateTime.of(1980, 1, 1, 0, 0) })
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
