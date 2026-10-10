package dev.apkproject.validate

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CompilerResult
import dev.apkproject.compiler.Invocation
import dev.apkproject.compiler.JvmHarness
import dev.apkproject.compiler.Observed
import dev.apkproject.compiler.SolverAnswer
import dev.apkproject.compiler.Z3Runner
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CheckRecord
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.EvidenceKind
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.ObligationRecord
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.contracts.ValidationReport
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.ir.IrEvaluator
import dev.apkproject.ir.MethodIr
import dev.apkproject.lift.CodeShape
import dev.apkproject.lift.DexLifter
import dev.apkproject.lift.ShapeComparison
import java.nio.file.Files
import java.nio.file.Path
import dev.apkproject.ir.Outcome as Run

/** One recovered method inside a compiled candidate bundle. */
data class CandidateUnit(
    val id: String,
    val original: MethodIr,
    val originalShape: CodeShape?,
    /** Where the candidate method lives in the compiled bundle. */
    val candidateOwnerDescriptor: String,
    val candidateMethodName: String,
    val sourceHash: String,
)

data class UnitValidation(
    val unit: CandidateUnit,
    val report: ValidationReport,
    val candidateIr: MethodIr?,
    val shape: ShapeComparison?,
    val counterexample: IntArray?,
    val notes: List<String>,
) {
    val accepted: Boolean get() = report.accepted
}

/**
 * Validates compiled candidates against the original method's semantics (sections 18-20).
 * The validator assigns acceptance; the reconstruction engine never does.
 *
 * 1. Re-lift: the candidate's own D8 output is lifted with the same V0 lifter.
 * 2. Formal: Z3 checks that no 32-bit input makes the outcomes differ.
 * 3. Runtime: the candidate runs in a separate JVM on boundary, constant-derived, random and
 *    counterexample inputs; results are compared with the original's lifted semantics.
 * 4. Model consistency: the JVM results must also match our evaluation of the re-lifted
 *    candidate, otherwise the lifter or evaluator mis-models real bytecode.
 */
class BatchValidator(
    private val profile: ResolvedProfile,
    private val budget: Budget,
    private val cancel: CancellationToken? = null,
) {
    private val z3 = Z3Runner(profile, cancel)

    fun validate(units: List<CandidateUnit>, compiled: CompilerResult, workDir: Path): List<UnitValidation> {
        Files.createDirectories(workDir)
        val environmentHash = Hashing.combine(compiled.environmentHash, profile.profileHash)
        val compileCheck = CheckRecord(
            "compile", compiled.status, if (compiled.status == CheckStatus.PASS) EvidenceKind.OBSERVED else null,
            if (compiled.status == CheckStatus.PASS) "javac --release ${profile.profile.javac.release} + D8 ${profile.profile.d8.version} succeeded" else compiled.diagnostics.take(4000),
        )
        if (compiled.status != CheckStatus.PASS || compiled.dexFile == null) {
            return units.map { u ->
                val report = ValidationReport(method = u.original.key, candidateHash = u.sourceHash, obligations = emptyList(), checks = listOf(compileCheck), environmentHash = environmentHash)
                UnitValidation(u, report, null, null, null, listOf("candidate did not compile"))
            }
        }
        val dex = when (val p = DexFile.parse(Files.readAllBytes(compiled.dexFile))) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> error("D8 produced an unreadable DEX: ${p.failure}")
        }
        val solverVersion = z3.version(workDir)

        // Re-lift and formally check each unit.
        data class Partial(val u: CandidateUnit, val ir: MethodIr?, val shape: ShapeComparison?, val obligation: ObligationRecord, val cex: IntArray?, val notes: MutableList<String>, val relift: CheckRecord)
        val partials = units.map { u ->
            val notes = ArrayList<String>()
            val m = dex.methods().firstOrNull { it.owner == u.candidateOwnerDescriptor && it.name == u.candidateMethodName }
            if (m == null) {
                val ob = ObligationRecord("equivalence.bv32", "candidate method present", "none", "-", SmtEncoder.MODEL_BOUNDS, "-", CheckStatus.FAIL, "method missing from compiled bundle")
                return@map Partial(u, null, null, ob, null, notes, CheckRecord("relift", CheckStatus.FAIL, null, "candidate method not found in DEX"))
            }
            val lifted = DexLifter.lift(m)
            val shape = u.originalShape?.let { orig ->
                (InstructionDecoder.decode(dex, m.code!!) as? Outcome.Ok)?.let { orig.compare(CodeShape.of(dex, it.value)) }
            }
            val cir = lifted.ir
            if (cir == null) {
                val reason = lifted.gaps.joinToString("; ") { "${it.code}: ${it.detail}" }
                val ob = ObligationRecord("equivalence.bv32", "candidate outcome equals original", "z3", solverVersion, SmtEncoder.MODEL_BOUNDS, "-", CheckStatus.UNKNOWN, "candidate bytecode is outside V0: $reason")
                return@map Partial(u, null, shape, ob, null, notes, CheckRecord("relift", CheckStatus.UNKNOWN, null, reason))
            }
            val query = SmtEncoder.equivalenceQuery(u.original, cir)
            val started = System.nanoTime()
            val answer = z3.check(query, workDir.resolve("smt"), budget.solverTimeoutMs)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            Files.createDirectories(workDir.resolve("smt"))
            Files.writeString(workDir.resolve("smt/${u.id}.smt2"), query)
            var cex: IntArray? = null
            val ob = when (answer) {
                is SolverAnswer.Unsat -> ObligationRecord("equivalence.bv32", "no 32-bit input distinguishes candidate from original", "z3", solverVersion, SmtEncoder.MODEL_BOUNDS, Hashing.sha256(query), CheckStatus.PASS, elapsedMs = elapsed)
                is SolverAnswer.Sat -> {
                    cex = IntArray(u.original.signature.params.size) { i -> Z3Runner.bv32(answer.model["p$i"] ?: "#x00000000") }
                    val o = IrEvaluator.run(u.original, cex)
                    val c = IrEvaluator.run(cir, cex)
                    notes += "counterexample ${cex.toList()}: original $o, candidate $c"
                    if (o == c) notes += "WARNING: SMT counterexample does not replay in the IR evaluator; encoder and evaluator disagree"
                    ObligationRecord(
                        "equivalence.bv32", "no 32-bit input distinguishes candidate from original", "z3", solverVersion, SmtEncoder.MODEL_BOUNDS,
                        Hashing.sha256(query), CheckStatus.FAIL, "counterexample found", cex.withIndex().associate { "p${it.index}" to it.value.toString() }, elapsed,
                    )
                }
                is SolverAnswer.Unknown -> ObligationRecord("equivalence.bv32", "no 32-bit input distinguishes candidate from original", "z3", solverVersion, SmtEncoder.MODEL_BOUNDS, Hashing.sha256(query), CheckStatus.UNKNOWN, answer.reason, elapsedMs = elapsed)
            }
            Partial(u, cir, shape, ob, cex, notes, CheckRecord("relift", CheckStatus.PASS, EvidenceKind.OBSERVED, "candidate DEX lifted inside V0"))
        }

        // One JVM run for every unit's inputs.
        val plan = partials.map { p ->
            val inputs = Inputs.generate(p.u.original, budget.randomInputsPerMethod, extra = listOfNotNull(p.cex))
            p to inputs
        }
        val calls = plan.flatMap { (p, inputs) ->
            val cls = p.u.candidateOwnerDescriptor.substring(1, p.u.candidateOwnerDescriptor.length - 1).replace('/', '.')
            inputs.map { Invocation(cls, p.u.candidateMethodName, it) }
        }
        val observed: List<Observed>? = try {
            JvmHarness(profile, cancel).run(listOf(compiled.classesDir), calls, workDir.resolve("runtime"), budget.runtimeTimeoutMs)
        } catch (e: IllegalStateException) {
            null.also { partials.forEach { p -> p.notes += "runtime harness failed: ${e.message}" } }
        }

        var cursor = 0
        return plan.map { (p, inputs) ->
            val slice = observed?.subList(cursor, cursor + inputs.size)
            cursor += inputs.size
            val checks = mutableListOf(compileCheck, p.relift)
            if (slice == null) {
                checks += CheckRecord("runtime.agreement", CheckStatus.UNKNOWN, null, "runtime harness did not complete")
            } else {
                var mismatch: String? = null
                var modelMismatch: String? = null
                inputs.forEachIndexed { i, args ->
                    val expected = render(IrEvaluator.run(p.u.original, args))
                    val actual = slice[i].toString()
                    if (mismatch == null && expected != actual) mismatch = "input ${args.toList()}: original (lifted) $expected, candidate on JVM $actual"
                    if (p.ir != null && modelMismatch == null) {
                        val modelled = render(IrEvaluator.run(p.ir, args))
                        if (modelled != actual) modelMismatch = "input ${args.toList()}: lifted candidate $modelled, JVM $actual"
                    }
                }
                checks += CheckRecord(
                    "runtime.agreement", if (mismatch == null) CheckStatus.PASS else CheckStatus.FAIL, if (mismatch == null) EvidenceKind.TESTED else null,
                    mismatch ?: "${inputs.size} inputs agree (candidate executed on JVM ${profile.profile.jdk.expectedMajor}; reference is the original's lifted semantics)",
                )
                if (p.ir != null) {
                    checks += CheckRecord(
                        "model.consistency", if (modelMismatch == null) CheckStatus.PASS else CheckStatus.FAIL, if (modelMismatch == null) EvidenceKind.TESTED else null,
                        modelMismatch ?: "re-lifted candidate IR predicts every JVM result",
                    )
                }
            }
            val report = ValidationReport(
                method = p.u.original.key,
                candidateHash = p.u.sourceHash,
                obligations = listOf(p.obligation),
                checks = checks,
                environmentHash = environmentHash,
                counterexamples = listOfNotNull(p.cex?.withIndex()?.associate { "p${it.index}" to it.value.toString() }),
            )
            UnitValidation(p.u, report, p.ir, p.shape, p.cex, p.notes)
        }
    }

    private fun render(o: Run): String = when (o) {
        is Run.Returned -> "ok ${o.value}"
        is Run.Threw -> "throw ${o.exception}"
    }
}
