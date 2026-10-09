package dev.apkproject.orchestrator

import dev.apkproject.archive.ApkSnapshot
import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.CompilerResult
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.ArtifactRef
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CapabilityGap
import dev.apkproject.contracts.CheckRecord
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.EvidenceKind
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.ExportManifest
import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.FormatFailure
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.JobSpec
import dev.apkproject.contracts.JobState
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.MethodKey
import dev.apkproject.contracts.MethodOutcome
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.RecoveryPolicy
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.contracts.RuleApplication
import dev.apkproject.contracts.SourceMapEntry
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.Disassembler
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.dex.NO_INDEX
import dev.apkproject.experiments.ExperimentRecord
import dev.apkproject.export.BundleFile
import dev.apkproject.export.RegressionVector
import dev.apkproject.export.SourceBundleManifest
import dev.apkproject.experiments.LocalExperiments
import dev.apkproject.experiments.Mutations
import dev.apkproject.ir.IrEvaluator
import dev.apkproject.ir.LiftResult
import dev.apkproject.lift.CodeShape
import dev.apkproject.lift.DexLifter
import dev.apkproject.lift.StructuralMatch
import dev.apkproject.reconstruct.JavaDeclarations
import dev.apkproject.reconstruct.ReconstructException
import dev.apkproject.reconstruct.ReconstructOptions
import dev.apkproject.reconstruct.Reconstructor
import dev.apkproject.reconstruct.SourceBundle
import dev.apkproject.source.JClass
import dev.apkproject.source.JMethod
import dev.apkproject.source.JavaNames
import dev.apkproject.source.JavaPrinter
import dev.apkproject.store.AtomicFiles
import dev.apkproject.validate.BatchValidator
import dev.apkproject.validate.CandidateUnit
import dev.apkproject.validate.Inputs
import dev.apkproject.validate.UnitValidation
import java.nio.file.Files
import java.nio.file.Path
import dev.apkproject.ir.Outcome as Run

data class JobOptions(
    val policy: RecoveryPolicy = RecoveryPolicy.FULL_SOURCE,
    val budget: Budget = Budget(),
    val experiments: Boolean = true,
    /** Test hook: offer a deliberately wrong candidate first for every method. */
    val seedWrongCandidates: Boolean = false,
)

data class JobResult(val state: JobState, val exitCode: ExitCode, val runDir: Path, val summary: String, val manifest: ExportManifest?)

const val ENGINE_VERSION = "0.1.0"

/**
 * One recovery job (sections 06-07): ingest, decode, lift, reconstruct, compile, validate and
 * refine within budget, run local experiments for fidelity, then re-validate the whole accepted
 * bundle under its real class names before declaring ACCEPTED or PARTIAL.
 */
/** `public Foo() { super(); }` exactly as javac generates it when no constructor is declared. */
object ImplicitConstructor {
    fun matches(m: DexMethod): Boolean {
        if (m.name != "<init>" || m.protoDescriptor != "()V") return false
        val c = m.classDef
        if (c.superclassIdx == NO_INDEX || m.dex.type(c.superclassIdx) != "Ljava/lang/Object;") return false
        val visibility = AccessFlags.PUBLIC or AccessFlags.PRIVATE or AccessFlags.PROTECTED
        if (m.accessFlags and visibility != c.accessFlags and AccessFlags.PUBLIC) return false
        val code = m.code ?: return false
        val d = (InstructionDecoder.decode(m.dex, code) as? Outcome.Ok)?.value ?: return false
        val self = code.registersSize - code.insSize
        return d.insns.size == 2 && d.insns[0].op.mnemonic == "invoke-direct" && d.insns[0].regs.contentEquals(intArrayOf(self)) &&
            m.dex.methodDescriptor(d.insns[0].index) == "Ljava/lang/Object;-><init>()V" && d.insns[1].op.mnemonic == "return-void"
    }
}

class RecoveryJob(
    private val profile: ResolvedProfile,
    private val options: JobOptions = JobOptions(),
    private val cancel: CancellationToken = CancellationToken(),
    private val log: (String) -> Unit = {},
) {
    private class Candidate(val label: String, val method: JMethod, val rules: List<RuleApplication>, val seeded: Boolean, val options: ReconstructOptions?)

    private inner class Work(val index: Int, val m: DexMethod, val lift: LiftResult) {
        val key: MethodKey = m.key
        val gaps = ArrayList(lift.gaps)
        val queue = ArrayDeque<Candidate>()
        var accepted: Pair<Candidate, UnitValidation>? = null
        var last: UnitValidation? = null
        var tried = 0
        var experiment: ExperimentRecord? = null
        val notes = ArrayList<String>()
        val originalShape: CodeShape? by lazy {
            m.code?.let { code -> (InstructionDecoder.decode(m.dex, code) as? Outcome.Ok)?.let { CodeShape.of(m.dex, it.value) } }
        }
        /** Implicit default constructor recognised by pattern (rule class.implicit-default-constructor.v1). */
        var implicitConstructor = false
        var constructorCheck: CheckRecord? = null
        var constructorShape: String? = null
        val inScope: Boolean get() = true
    }

    fun run(input: Path, runDir: Path): JobResult {
        val dir = JobDirectory(runDir)
        Files.createDirectories(runDir)
        Files.deleteIfExists(dir.cancelMarker)
        val watcher = dir.watchCancel(cancel)
        val inputSha = Hashing.sha256(input)
        val jobId = "job-" + Hashing.combine(inputSha, profile.profileHash, options.policy.name).take(12)
        val spec = JobSpec(
            jobId = jobId, inputRefs = emptyList(), profileId = profile.profile.id, profileHash = profile.profileHash,
            policy = options.policy, budget = options.budget, engineVersion = ENGINE_VERSION,
        )
        val journal = Journal(dir, jobId)
        var stateFile = JobStateFile(jobId = jobId, state = JobState.CREATED, spec = spec, inputPath = input.toAbsolutePath().toString())
        fun save(state: JobState, message: String? = null) {
            stateFile = stateFile.copy(state = state, message = message)
            dir.writeState(stateFile)
        }
        fun finish(state: JobState, code: ExitCode, summary: String, manifest: ExportManifest? = null): JobResult {
            if (journal.state != state) journal.transition(state, summary)
            save(state, summary)
            log(summary)
            return JobResult(state, code, runDir, summary, manifest)
        }
        save(JobState.CREATED)
        journal.event("job.created", CheckStatus.PASS, message = "input sha256 $inputSha, profile ${profile.profile.id}")
        try {
            // CREATED: configuration and toolchain identity.
            val problems = Doctor.verify(profile).filter { it.status != CheckStatus.PASS }
            if (problems.isNotEmpty()) {
                return finish(JobState.FAILED, ExitCode.INVALID_CONFIGURATION, "toolchain does not match profile: " + problems.joinToString("; ") { "${it.name}: ${it.detail}" })
            }

            // INGESTED: snapshot and inventory.
            val snapshot = ApkSnapshot.take(input).getOrThrow()
            val inputRef = dir.store.put(Files.readAllBytes(input), input.fileName.toString(), snapshot.ref.mediaType)
            stateFile = stateFile.copy(spec = spec.copy(inputRefs = listOf(inputRef)))
            val inventoryRef = dir.store.putText(Json.encode(snapshot.inventory.entries.map { "${it.name} ${it.uncompressedSize} crc=${it.crc32.toString(16)}" }), "inventory.json", "application/json")
            journal.transition(JobState.INGESTED)
            journal.event("archive.inventory", CheckStatus.PASS, listOf(inputRef, inventoryRef), "${snapshot.inventory.entries.size} entries")
            save(JobState.INGESTED)
            checkCancel()

            // DECODED: DEX structure, disassembly and lifting.
            val dexEntries = snapshot.dexFiles().getOrThrow()
            if (dexEntries.isEmpty()) throw FormatException(FormatFailure(inputSha, 0, "archive", "no classes.dex entry", unsupported = true))
            val dexFiles = dexEntries.map { e ->
                val dex = DexFile.parse(e.bytes, e.ref.sha256).getOrThrow()
                val listing = dir.store.putText(Disassembler.file(dex, Disassembler.Options(includeByteOffsets = true)), "disassembly/${e.entry.name.removeSuffix(".dex")}.dasm")
                journal.event("dex.decoded", CheckStatus.PASS, listOf(e.ref, listing), "${e.entry.name}: DEX ${dex.version}, ${dex.classDefs.size} classes, ${dex.methods().size} methods")
                dex
            }
            val works = dexFiles.flatMap { it.methods() }.mapIndexed { i, m -> Work(i, m, DexLifter.lift(m)) }
            for (w in works) recognizeConstructor(w)
            journal.transition(JobState.DECODED)
            save(JobState.DECODED)
            checkCancel()

            // MODELED: names, then candidate queues.
            for (w in works.filter { it.lift.supported }) {
                val ownerOk = JavaDeclarations.splitClass(w.m.owner) != null
                if (!ownerOk || !JavaNames.isLegalIdentifier(w.m.name)) {
                    w.gaps += CapabilityGap(w.key.display(), "names.illegal-identifier", "class or method name is not a legal Java identifier")
                    continue
                }
                try {
                    val base = Reconstructor.reconstruct(w.lift.ir!!, w.m.name, JavaDeclarations.methodModifiers(w.m.accessFlags), ReconstructOptions(maxStatements = options.budget.maxStructuredStatements))
                    if (options.seedWrongCandidates) Mutations.mutate(base.method)?.let { w.queue += Candidate("seeded-mutant (${it.description})", it.method, emptyList(), true, null) }
                    w.queue += Candidate(base.options.label, base.method, base.rules, false, base.options)
                } catch (e: ReconstructException) {
                    w.gaps += CapabilityGap(w.key.display(), e.code, e.message ?: "")
                }
            }
            val modelled = works.filter { it.queue.isNotEmpty() }
            log("decoded ${works.size} methods; ${modelled.size} inside the V0 model")
            if (modelled.isEmpty()) {
                val manifest = writeReports(dir, journal, jobId, inputRef, works, null)
                return finish(JobState.PARTIAL, ExitCode.UNSUPPORTED_OR_PARTIAL, "no method is inside the supported model", manifest)
            }
            journal.transition(JobState.MODELED)
            save(JobState.MODELED)

            // CANDIDATE -> COMPILED -> REFINING loop.
            var round = 0
            while (true) {
                checkCancel()
                val pending = modelled.filter { it.accepted == null && it.queue.isNotEmpty() && it.tried < options.budget.maxCandidatesPerMethod }
                if (pending.isEmpty()) break
                round++
                journal.transition(JobState.CANDIDATE, "round $round: ${pending.size} candidates")
                save(JobState.CANDIDATE)
                val picked = pending.associateWith { it.queue.removeFirst().also { _ -> it.tried++ } }
                val validations = compileAndValidate(dir, "round$round", picked.map { (w, c) -> w to c.method })
                journal.transition(if (validations.values.any { it.report.checks.any { c -> c.kind == "compile" && c.status == CheckStatus.PASS } }) JobState.COMPILED else JobState.REFINING)
                var rejected = 0
                for ((w, c) in picked) {
                    val v = validations.getValue(w)
                    w.last = v
                    if (v.accepted && !c.seeded) {
                        w.accepted = c to v
                    } else {
                        rejected++
                        if (c.seeded && v.accepted) w.notes += "VALIDATION GAP: seeded wrong candidate '${c.label}' was accepted"
                        val why = v.report.obligations.firstOrNull()?.let { "${it.status}${it.counterexample?.let { cx -> " counterexample $cx" } ?: ""}" } ?: "not compiled"
                        w.notes += "candidate '${c.label}' rejected: $why"
                        journal.event("candidate.rejected", CheckStatus.FAIL, message = "${w.key.display()}: ${c.label}: $why")
                    }
                }
                journal.event("round.validated", CheckStatus.PASS, message = "round $round: ${picked.size - rejected} accepted, $rejected rejected")
                if (rejected > 0 && modelled.any { it.accepted == null && it.queue.isNotEmpty() && it.tried < options.budget.maxCandidatesPerMethod }) {
                    journal.transition(JobState.REFINING)
                    save(JobState.REFINING)
                } else {
                    break
                }
            }

            // Local compiler experiments for historical fidelity.
            if (options.experiments) runExperiments(dir, journal, modelled)
            checkCancel()

            // Final gate: the whole bundle under real class names.
            val bundle = finalGate(dir, journal, jobId, works)
            val manifest = writeReports(dir, journal, jobId, inputRef, works, bundle)
            stateFile = stateFile.copy(bundleRef = bundle?.second, reportRef = dir.store.putText(Json.encode(manifest), "recovery/report.json", "application/json"))
            val complete = manifest.methodsAccepted == manifest.methodsInScope
            val summary = "${manifest.methodsAccepted}/${manifest.methodsInScope} methods accepted as source" +
                if (complete) "" else "; ${manifest.methodsUnresolved} unresolved (see reports/recovery.json)"
            return if (complete) {
                journal.transition(JobState.ACCEPTED, summary)
                finish(JobState.ACCEPTED, ExitCode.EXPORT_POLICY_SATISFIED, summary, manifest)
            } else {
                val code = if (options.policy == RecoveryPolicy.PARTIAL_REPORT && bundle != null) ExitCode.EXPORT_POLICY_SATISFIED else ExitCode.UNSUPPORTED_OR_PARTIAL
                finish(JobState.PARTIAL, code, summary, manifest)
            }
        } catch (e: CancelledException) {
            return finish(JobState.CANCELLED, ExitCode.CANCELLED, "cancelled; rerun `apkproject resume ${runDir}` to continue")
        } catch (e: FormatException) {
            journal.event("input.rejected", CheckStatus.FAIL, message = e.failure.toString())
            return finish(JobState.FAILED, if (e.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT, e.failure.toString())
        } catch (e: Exception) {
            journal.event("worker.failed", CheckStatus.FAIL, message = "${e::class.simpleName}: ${e.message}")
            return finish(JobState.FAILED, ExitCode.WORKER_OR_RESOURCE_FAILURE, "worker or resource failure: ${e::class.simpleName}: ${e.message}")
        } finally {
            watcher.interrupt()
        }
    }

    private fun checkCancel() {
        if (cancel.cancelled) throw CancelledException()
    }

    private fun recognizeConstructor(w: Work) {
        if (ImplicitConstructor.matches(w.m)) {
            w.implicitConstructor = true
            w.gaps.clear()
        }
    }

    private fun compileBatch(dir: JobDirectory, label: String, sources: Map<String, String>): CompilerResult {
        val key = Hashing.combine(profile.profileHash, *sources.toSortedMap().flatMap { listOf(it.key, it.value) }.toTypedArray()).take(24)
        val work = dir.work.resolve("compile").resolve("$label-$key")
        AtomicFiles.deleteRecursively(work)
        return ForwardCompiler(profile, cancel).compile(sources, work, options.budget.compileTimeoutMs)
    }

    /** Compiles each method candidate in its own probe class (one batch), then validates all of them. */
    private fun compileAndValidate(dir: JobDirectory, label: String, items: List<Pair<Work, JMethod>>): Map<Work, UnitValidation> {
        fun unit(w: Work, m: JMethod, cls: String) = CandidateUnit(
            id = "m${w.index}", original = w.lift.ir!!, originalShape = w.originalShape,
            candidateOwnerDescriptor = "Lapkcand/$cls;", candidateMethodName = m.name,
            sourceHash = Hashing.sha256(JavaPrinter.method(m)),
        )
        val sources = items.associate { (w, m) -> "apkcand/C${w.index}.java" to JavaPrinter.print(JClass("apkcand", "C${w.index}", listOf("public", "final"), listOf(m))).text }
        val validator = BatchValidator(profile, options.budget, cancel)
        val batch = compileBatch(dir, label, sources)
        if (batch.status == CheckStatus.PASS) {
            val units = items.map { (w, m) -> unit(w, m, "C${w.index}") }
            val results = validator.validate(units, batch, dir.work.resolve("validate").resolve(label))
            return items.zip(results).associate { (item, v) -> item.first to v }
        }
        // Isolate the failing candidates.
        return items.associate { (w, m) ->
            val single = compileBatch(dir, "$label-m${w.index}", mapOf("apkcand/C${w.index}.java" to sources.getValue("apkcand/C${w.index}.java")))
            w to validator.validate(listOf(unit(w, m, "C${w.index}")), single, dir.work.resolve("validate").resolve("$label-m${w.index}")).single()
        }
    }

    private fun runExperiments(dir: JobDirectory, journal: Journal, works: List<Work>) {
        val targets = works.filter { w -> w.accepted?.second?.shape?.match.let { it != null && it != StructuralMatch.IDENTICAL } && w.originalShape != null }
        if (targets.isEmpty()) return
        val requests = targets.map { w ->
            val base = Reconstructor.reconstruct(w.lift.ir!!, w.m.name, JavaDeclarations.methodModifiers(w.m.accessFlags), w.accepted!!.first.options ?: ReconstructOptions())
            LocalExperiments.Request(w.lift.ir!!, base, w.originalShape!!, w.m.name, JavaDeclarations.methodModifiers(w.m.accessFlags))
        }
        val results = LocalExperiments(profile, options.budget, cancel).run(requests, dir.work.resolve("experiments"))
        val improved = ArrayList<Pair<Work, Candidate>>()
        for (w in targets) {
            val r = results[w.key.display()] ?: continue
            w.experiment = r.record
            journal.event("experiment.${r.record.outcome.lowercase()}", CheckStatus.PASS, message = "${w.key.display()}: chose ${r.record.chosen} from ${r.record.probes.size} probes")
            val best = r.best ?: continue
            val current = w.accepted!!.second.shape!!
            val found = r.bestShape
            val better = found != null && (rank(found.match) < rank(current.match) || (found.match == current.match && found.distance < current.distance))
            if (better) improved += w to Candidate(best.options.label, best.method, best.rules + RuleApplication("experiment.layout-probe.v1", r.record.chosen ?: ""), false, best.options)
        }
        if (improved.isEmpty()) return
        // A variant replaces the accepted candidate only after passing the same validation.
        val validations = compileAndValidate(dir, "experiments", improved.map { it.first to it.second.method })
        for ((w, c) in improved) {
            val v = validations.getValue(w)
            if (v.accepted) w.accepted = c to v else w.notes += "experiment variant '${c.label}' failed validation and was discarded"
        }
    }

    private fun rank(m: StructuralMatch) = when (m) {
        StructuralMatch.IDENTICAL -> 0; StructuralMatch.ISOMORPHIC -> 1; StructuralMatch.DIFFERENT -> 2
    }

    /**
     * Compiles the accepted methods as the real classes and validates every one again from that
     * build. Returns the printed units and the stored bundle manifest, or null if nothing was accepted.
     */
    private fun finalGate(dir: JobDirectory, journal: Journal, jobId: String, works: List<Work>): Pair<List<Pair<JClass, dev.apkproject.source.PrintedUnit>>, ArtifactRef>? {
        val byClass = works.filter { it.accepted != null || it.implicitConstructor }.groupBy { it.m.owner }
        if (byClass.isEmpty()) return null
        val header = listOf(
            "Recovered by apkproject $ENGINE_VERSION (profile ${profile.profile.id}).",
            "Every method below passed formal and runtime validation; see recovery/report.json.",
            "Original comments, local names and formatting are not present in the APK and were not invented.",
        )
        val classes = byClass.mapNotNull { (owner, ws) ->
            val methods = ws.mapNotNull { it.accepted?.first?.method?.copy(comment = null) }
            if (methods.isEmpty() && ws.none { it.implicitConstructor }) return@mapNotNull null
            SourceBundle.classFor(owner, ws.first().m.classDef.accessFlags, methods, header)
        }
        val printed = classes.map { it to JavaPrinter.print(it) }
        val sources = printed.associate { (c, p) -> c.relativePath to p.text }
        val build = compileBatch(dir, "final", sources)
        val buildCheck = CheckRecord("bundle.build", build.status, if (build.status == CheckStatus.PASS) EvidenceKind.OBSERVED else null, if (build.status == CheckStatus.PASS) "javac + D8 built ${sources.size} classes from the recovered source alone" else build.diagnostics.take(2000))
        journal.event("bundle.compiled", build.status, message = buildCheck.detail)
        val acceptedWorks = works.filter { it.accepted != null }
        if (build.status == CheckStatus.PASS) {
            val units = acceptedWorks.map { w ->
                CandidateUnit("final-m${w.index}", w.lift.ir!!, w.originalShape, w.m.owner, w.m.name, w.accepted!!.second.unit.sourceHash)
            }
            val results = BatchValidator(profile, options.budget, cancel).validate(units, build, dir.work.resolve("validate/final"))
            for ((w, v) in acceptedWorks.zip(results)) {
                if (v.accepted) {
                    w.accepted = w.accepted!!.first to v
                } else {
                    w.notes += "final bundle validation failed: ${v.report.checks.filter { it.status != CheckStatus.PASS }.joinToString { it.detail }}"
                    w.last = v
                    w.accepted = null
                }
            }
            // Implicit constructors must recompile to the same shape.
            val dex = (DexFile.parse(Files.readAllBytes(build.dexFile!!)) as Outcome.Ok).value
            for (w in works.filter { it.implicitConstructor }) {
                val m = dex.methods().firstOrNull { it.owner == w.m.owner && it.name == "<init>" && it.protoDescriptor == "()V" }
                val shape = m?.code?.let { code -> (InstructionDecoder.decode(dex, code) as? Outcome.Ok)?.let { w.originalShape?.compare(CodeShape.of(dex, it.value)) } }
                val ok = shape != null && shape.match != StructuralMatch.DIFFERENT
                w.constructorShape = shape?.toString()
                w.constructorCheck = CheckRecord("structural.recompile", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) EvidenceKind.OBSERVED else null, "implicit default constructor recompiles as ${shape ?: "missing"}")
                if (!ok) w.implicitConstructor = false
            }
        } else {
            acceptedWorks.forEach { w ->
                w.notes += "final bundle did not compile"
                w.accepted = null
            }
        }
        val vectors = works.filter { it.accepted != null }.flatMap { w ->
            val ir = w.lift.ir!!
            val cls = JavaDeclarations.binaryName(w.m.owner)
            Inputs.generate(ir, 4).take(24).map { args ->
                val expected = when (val o = IrEvaluator.run(ir, args)) {
                    is Run.Returned -> "ok ${o.value}"
                    is Run.Threw -> "throw ${o.exception}"
                }
                RegressionVector(cls, w.m.name, args.toList(), expected)
            }
        }
        val files = sources.map { (path, text) -> BundleFile(path, dir.store.putText(text, "src/main/java/$path", "text/x-java").sha256) }
        val callable = works.filter { it.accepted != null && it.m.accessFlags and AccessFlags.PRIVATE == 0 }.map { "${JavaDeclarations.binaryName(it.m.owner)}.${it.m.name}" }
        val manifest = SourceBundleManifest(jobId, files, vectors, buildCheck, profile.profile.javac.release, callable)
        val ref = dir.store.putText(Json.encode(manifest), "bundle.json", "application/json")
        // Source map from the printed line origins.
        val map = printed.flatMap { (c, p) -> p.lines.map { SourceMapEntry("src/main/java/${c.relativePath}", it.line, it.methodId, it.ranges, it.rules) } }
        AtomicFiles.writeString(dir.reports.resolve("source-map.json"), Json.encode(map))
        return printed to ref
    }

    private fun writeReports(dir: JobDirectory, journal: Journal, jobId: String, inputRef: ArtifactRef, works: List<Work>, bundle: Pair<List<Pair<JClass, dev.apkproject.source.PrintedUnit>>, ArtifactRef>?): ExportManifest {
        val outcomes = works.map { w -> outcome(w) }
        val accepted = outcomes.count { it.status == "ACCEPTED" }
        val buildCheck = bundle?.let { Json.decode<SourceBundleManifest>(String(dir.store.read(it.second))).buildCheck }
            ?: CheckRecord("bundle.build", CheckStatus.NOT_RUN, null, "no accepted source to build")
        val manifest = ExportManifest(
            jobId = jobId,
            policy = options.policy,
            profileId = profile.profile.id,
            profileHash = profile.profileHash,
            environmentHash = Hashing.combine(profile.profileHash, ENGINE_VERSION),
            inputs = listOf(inputRef),
            methodsInScope = works.size,
            methodsAccepted = accepted,
            methodsUnresolved = works.size - accepted,
            methods = outcomes,
            sourceFiles = bundle?.first?.map { "src/main/java/${it.first.relativePath}" } ?: emptyList(),
            buildCheck = buildCheck,
            policySatisfied = when (options.policy) {
                RecoveryPolicy.FULL_SOURCE -> accepted == works.size && buildCheck.status == CheckStatus.PASS
                RecoveryPolicy.PARTIAL_REPORT -> buildCheck.status != CheckStatus.FAIL
            },
            notes = listOf(
                "Denominator: every method with a definition in the input DEX, including unsupported ones.",
                "PROVED = Z3 found no 32-bit input on which candidate and original outcomes differ (model: ${dev.apkproject.lift.V0Semantics.MODEL})",
                "TESTED = candidate executed in a separate JVM agreed with the original's lifted semantics on every generated input. Independent execution of the original DEX requires an Android runtime (V1).",
                "Structural match compares the candidate's recompiled bytecode with the original; it is fidelity evidence, not proof of historical source.",
                "No method body was stubbed. Methods that are not ACCEPTED are absent from the exported source and listed here.",
            ),
        )
        AtomicFiles.writeString(dir.reports.resolve("recovery.json"), Json.encode(manifest))
        AtomicFiles.writeString(dir.reports.resolve("experiments.json"), Json.encode(works.mapNotNull { it.experiment }))
        AtomicFiles.writeString(dir.reports.resolve("capabilities.json"), Json.encode(works.flatMap { it.gaps }))
        journal.event("report.written", CheckStatus.PASS, message = "$accepted/${works.size} accepted")
        return manifest
    }

    private fun outcome(w: Work): MethodOutcome {
        if (w.implicitConstructor) {
            return MethodOutcome(
                method = w.key, status = "ACCEPTED", evidence = listOf(EvidenceKind.OBSERVED), structuralMatch = w.constructorShape,
                checks = listOfNotNull(w.constructorCheck), ruleIds = listOf("class.implicit-default-constructor.v1"),
            )
        }
        val acc = w.accepted
        if (acc != null) {
            val v = acc.second
            val evidence = buildList {
                add(EvidenceKind.OBSERVED)
                if (v.report.obligations.all { it.status == CheckStatus.PASS }) add(EvidenceKind.PROVED)
                if (v.report.checks.any { it.kind == "runtime.agreement" && it.status == CheckStatus.PASS }) add(EvidenceKind.TESTED)
            }
            return MethodOutcome(
                method = w.key, status = "ACCEPTED", evidence = evidence, acceptedCandidateHash = v.unit.sourceHash,
                candidatesTried = w.tried, structuralMatch = v.shape?.toString(), obligations = v.report.obligations, checks = v.report.checks,
                ruleIds = acc.first.rules.map { it.ruleId }.distinct().sorted(),
            )
        }
        val status = when {
            w.gaps.isNotEmpty() -> "UNSUPPORTED"
            w.last?.report?.obligations?.any { it.status == CheckStatus.UNKNOWN } == true -> "UNKNOWN"
            else -> "REJECTED"
        }
        val gaps = w.gaps + w.notes.map { CapabilityGap(w.key.display(), "note", it) }
        return MethodOutcome(
            method = w.key, status = status, evidence = listOf(EvidenceKind.OBSERVED), candidatesTried = w.tried,
            gaps = gaps, obligations = w.last?.report?.obligations ?: emptyList(), checks = w.last?.report?.checks ?: emptyList(),
        )
    }
}
