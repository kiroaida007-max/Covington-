package dev.apkproject.orchestrator

import dev.apkproject.archive.ApkSnapshot
import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.CapabilityGap
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.ExportManifest
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.JobState
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.Disassembler
import dev.apkproject.export.ExportException
import dev.apkproject.export.ProjectExporter
import dev.apkproject.export.SourceBundleManifest
import dev.apkproject.lift.DexLifter
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class MethodCapability(val method: String, val supported: Boolean, val codeUnits: Int, val gaps: List<CapabilityGap>)

@Serializable
data class DexSummary(val entry: String, val sha256: String, val version: Int, val classes: Int, val methods: Int)

/** Output of `apkproject inspect`: inventory, formats and method-level gaps; nothing is executed. */
@Serializable
data class InspectionReport(
    val input: String,
    val inputSha256: String,
    val entries: Int,
    val archiveWarnings: List<String>,
    val dex: List<DexSummary>,
    val methodsTotal: Int,
    val methodsSupported: Int,
    val gapsByCode: Map<String, Int>,
    val methods: List<MethodCapability>,
)

data class OperationResult(val exitCode: ExitCode, val message: String)

object Operations {
    fun inspect(input: Path, outDir: Path): OperationResult {
        val snapshot = when (val s = ApkSnapshot.take(input)) {
            is dev.apkproject.contracts.Outcome.Ok -> s.value
            is dev.apkproject.contracts.Outcome.Err -> return failure(s.failure)
        }
        val entries = when (val d = snapshot.dexFiles()) {
            is dev.apkproject.contracts.Outcome.Ok -> d.value
            is dev.apkproject.contracts.Outcome.Err -> return failure(d.failure)
        }
        Files.createDirectories(outDir)
        val summaries = ArrayList<DexSummary>()
        val methods = ArrayList<MethodCapability>()
        for (e in entries) {
            val dex = when (val p = DexFile.parse(e.bytes, e.ref.sha256)) {
                is dev.apkproject.contracts.Outcome.Ok -> p.value
                is dev.apkproject.contracts.Outcome.Err -> return failure(p.failure)
            }
            summaries += DexSummary(e.entry.name, e.ref.sha256, dex.version, dex.classDefs.size, dex.methods().size)
            AtomicFiles.writeString(outDir.resolve("disassembly/${e.entry.name.removeSuffix(".dex")}.dasm"), Disassembler.file(dex, Disassembler.Options(includeByteOffsets = true)))
            for (m in dex.methods()) {
                if (ImplicitConstructor.matches(m)) {
                    methods += MethodCapability(m.key.display(), true, m.code?.insns?.size ?: 0, emptyList())
                    continue
                }
                val lift = DexLifter.lift(m)
                methods += MethodCapability(m.key.display(), lift.supported, m.code?.insns?.size ?: 0, lift.gaps)
            }
        }
        val report = InspectionReport(
            input = input.fileName.toString(),
            inputSha256 = snapshot.ref.sha256,
            entries = snapshot.inventory.entries.size,
            archiveWarnings = snapshot.inventory.warnings,
            dex = summaries,
            methodsTotal = methods.size,
            methodsSupported = methods.count { it.supported },
            gapsByCode = methods.flatMap { it.gaps }.groupingBy { it.code }.eachCount().toSortedMap(),
            methods = methods,
        )
        AtomicFiles.writeString(outDir.resolve("inspect.json"), Json.encode(report))
        val msg = "${report.methodsSupported}/${report.methodsTotal} methods inside the V0 model; gaps: ${report.gapsByCode.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}"
        return OperationResult(ExitCode.EXPORT_POLICY_SATISFIED, msg)
    }

    private fun failure(f: dev.apkproject.contracts.FormatFailure) =
        OperationResult(if (f.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT, f.toString())

    fun export(profile: ResolvedProfile, runDir: Path, outDir: Path, allowPartial: Boolean): OperationResult {
        val dir = JobDirectory(runDir)
        val state = dir.readState() ?: return OperationResult(ExitCode.INVALID_CONFIGURATION, "no job state in $runDir")
        if (state.state !in setOf(JobState.ACCEPTED, JobState.PARTIAL, JobState.EXPORTED)) {
            return OperationResult(ExitCode.INVALID_CONFIGURATION, "job is ${state.state}; run or resume recovery first")
        }
        val bundleRef = state.bundleRef ?: return OperationResult(ExitCode.UNSUPPORTED_OR_PARTIAL, "the job accepted no source; nothing to export (see reports/recovery.json)")
        val bundle = Json.decode<SourceBundleManifest>(String(dir.store.read(bundleRef)))
        val report = Json.decode<ExportManifest>(Files.readString(dir.reports.resolve("recovery.json")))
        val recoveryFiles = LinkedHashMap<String, ByteArray>()
        recoveryFiles["report.json"] = Files.readAllBytes(dir.reports.resolve("recovery.json"))
        for (name in listOf("source-map.json", "experiments.json", "capabilities.json")) {
            dir.reports.resolve(name).takeIf { Files.exists(it) }?.let { recoveryFiles[name] = Files.readAllBytes(it) }
        }
        // Disassembly listings are stored by content; the journal records their names.
        journalRefs(dir).filter { it.second.startsWith("disassembly/") }.forEach { (sha, rel) -> recoveryFiles[rel] = dir.store.read(sha) }
        return try {
            ProjectExporter.export(ProjectExporter.Inputs(dir.store, bundle, report, recoveryFiles, allowPartial), outDir) { src -> compileCheck(profile, src) }
            if (state.state == JobState.ACCEPTED) {
                val journal = Journal(dir, state.jobId).also { it.resumeFrom(state.state) }
                journal.transition(JobState.EXPORTED, "exported to $outDir")
                dir.writeState(state.copy(state = JobState.EXPORTED, message = "exported to $outDir"))
            }
            OperationResult(ExitCode.EXPORT_POLICY_SATISFIED, "exported ${bundle.files.size} source files to $outDir (${report.methodsAccepted}/${report.methodsInScope} methods)")
        } catch (e: ExportException) {
            OperationResult(ExitCode.entries.first { it.code == e.exitCode }, e.message ?: "export failed")
        }
    }

    /** Clean compile of the exported sources with the profile's javac, in a scratch directory. */
    private fun compileCheck(profile: ResolvedProfile, src: Path): String? {
        val files = Files.walk(src).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.map { it.toString() }.toList() }
        if (files.isEmpty()) return "no source files"
        val out = Files.createTempDirectory("apkproject-export-check")
        try {
            val r = ProcessWorker.run(listOf(profile.javac.toString(), "--release", profile.profile.javac.release.toString(), "-Xlint:-options", "-proc:none", "-d", out.toString()) + files, out, 120_000)
            return if (r.ok) null else r.stderr + r.stdout
        } finally {
            AtomicFiles.deleteRecursively(out)
        }
    }

    private fun journalRefs(dir: JobDirectory): List<Pair<String, String>> {
        if (!Files.exists(dir.journalFile)) return emptyList()
        return Files.readAllLines(dir.journalFile).flatMap { line ->
            val e = Json.decode<dev.apkproject.contracts.WorkerEnvelope>(line)
            e.artifactRefs.map { it.sha256 to it.relativePath }
        }.distinct()
    }

    /** Resumes an interrupted job: same input bytes, profile and policy; completed compilations are not trusted blindly. */
    fun resume(profile: ResolvedProfile, runDir: Path, log: (String) -> Unit): JobResult? {
        val dir = JobDirectory(runDir)
        val state = dir.readState() ?: return null
        if (state.state.terminal && state.state != JobState.CANCELLED) {
            log("job ${state.jobId} already finished as ${state.state}: ${state.message}")
            return JobResult(state.state, if (state.state == JobState.FAILED) ExitCode.WORKER_OR_RESOURCE_FAILURE else ExitCode.EXPORT_POLICY_SATISFIED, runDir, state.message ?: "", null)
        }
        val input = Path.of(state.inputPath)
        val expected = state.spec.inputRefs.firstOrNull()?.sha256
        if (expected != null && (!Files.exists(input) || Hashing.sha256(input) != expected)) {
            return JobResult(JobState.FAILED, ExitCode.INVALID_CONFIGURATION, runDir, "input $input changed since the job started", null)
        }
        if (state.spec.profileHash != profile.profileHash) {
            return JobResult(JobState.FAILED, ExitCode.INVALID_CONFIGURATION, runDir, "toolchain profile changed since the job started", null)
        }
        log("resuming ${state.jobId} from ${state.state}")
        return RecoveryJob(profile, JobOptions(policy = state.spec.policy, budget = state.spec.budget), CancellationToken(), log).run(input, runDir)
    }

    /** Accepts a run directory or a job id under `./runs`. */
    fun cancel(target: String): OperationResult {
        val direct = Path.of(target)
        val dir = if (Files.isDirectory(direct)) direct else {
            val runs = Path.of("runs")
            if (!Files.isDirectory(runs)) return OperationResult(ExitCode.INVALID_CONFIGURATION, "no run directory or job $target")
            Files.list(runs).use { s -> s.filter { JobDirectory(it).readState()?.jobId == target }.findFirst().orElse(null) }
                ?: return OperationResult(ExitCode.INVALID_CONFIGURATION, "no job $target under ./runs")
        }
        JobDirectory(dir).requestCancel()
        return OperationResult(ExitCode.CANCELLED, "cancellation requested for $dir; running workers are terminated within a second")
    }

    /** Re-runs validation of an accepted bundle, optionally with an extra input suite. */
    fun validate(profile: ResolvedProfile, runDir: Path, suite: Path?, log: (String) -> Unit): OperationResult {
        val dir = JobDirectory(runDir)
        val state = dir.readState() ?: return OperationResult(ExitCode.INVALID_CONFIGURATION, "no job state in $runDir")
        val bundleRef = state.bundleRef ?: return OperationResult(ExitCode.UNSUPPORTED_OR_PARTIAL, "no accepted bundle to validate")
        val bundle = Json.decode<SourceBundleManifest>(String(dir.store.read(bundleRef)))
        val input = Path.of(state.inputPath)
        val snapshot = ApkSnapshot.take(input).getOrThrow()
        val dexes = snapshot.dexFiles().getOrThrow().map { DexFile.parse(it.bytes, it.ref.sha256).getOrThrow() }
        val methods = dexes.flatMap { it.methods() }
        val extra = suite?.let { Json.decode<Map<String, List<List<Int>>>>(Files.readString(it)) } ?: emptyMap()
        val sources = bundle.files.associate { it.path to String(dir.store.read(it.sha256)) }
        val work = dir.work.resolve("revalidate")
        AtomicFiles.deleteRecursively(work)
        val build = ForwardCompiler(profile).compile(sources, work.resolve("build"), state.spec.budget.compileTimeoutMs)
        if (build.status != CheckStatus.PASS) return OperationResult(ExitCode.BUILD_OR_VERIFICATION_FAILURE, "bundle does not build: ${build.diagnostics.take(500)}")
        val report = Json.decode<ExportManifest>(Files.readString(dir.reports.resolve("recovery.json")))
        val accepted = report.methods.filter { it.status == "ACCEPTED" && it.acceptedCandidateHash != null }.map { it.method }
        val units = accepted.mapIndexedNotNull { i, key ->
            val m = methods.firstOrNull { it.key == key } ?: return@mapIndexedNotNull null
            val ir = DexLifter.lift(m).ir ?: return@mapIndexedNotNull null
            dev.apkproject.validate.CandidateUnit("re$i", ir, null, m.owner, m.name, "revalidate")
        }
        val budget = state.spec.budget
        val results = dev.apkproject.validate.BatchValidator(profile, budget).validate(units, build, work.resolve("validate"))
        // Extra suite inputs run through the same comparison path.
        var suiteFailures = 0
        if (extra.isNotEmpty()) {
            val calls = ArrayList<Pair<dev.apkproject.compiler.Invocation, String>>()
            for (u in units) {
                val inputs = extra[u.original.key.display()] ?: continue
                for (args in inputs) {
                    val expected = when (val o = dev.apkproject.ir.IrEvaluator.run(u.original, args.toIntArray())) {
                        is dev.apkproject.ir.Outcome.Returned -> "ok ${o.value}"
                        is dev.apkproject.ir.Outcome.Threw -> "throw ${o.exception}"
                    }
                    calls += dev.apkproject.compiler.Invocation(u.candidateOwnerDescriptor.substring(1, u.candidateOwnerDescriptor.length - 1).replace('/', '.'), u.candidateMethodName, args.toIntArray()) to expected
                }
            }
            val observed = dev.apkproject.compiler.JvmHarness(profile).run(listOf(build.classesDir), calls.map { it.first }, work.resolve("suite"), budget.runtimeTimeoutMs)
            suiteFailures = calls.zip(observed).count { (c, o) -> c.second != o.toString() }
        }
        AtomicFiles.writeString(dir.reports.resolve("validation.json"), Json.encode(results.map { it.report }))
        val failed = results.count { !it.accepted } + suiteFailures
        val msg = "${results.size - results.count { !it.accepted }}/${results.size} methods re-validated" + (if (extra.isNotEmpty()) "; suite mismatches: $suiteFailures" else "")
        log(msg)
        return OperationResult(if (failed == 0) ExitCode.EXPORT_POLICY_SATISFIED else ExitCode.BUILD_OR_VERIFICATION_FAILURE, msg)
    }
}
