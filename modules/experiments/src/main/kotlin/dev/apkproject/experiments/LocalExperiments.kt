package dev.apkproject.experiments

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.ir.MethodIr
import dev.apkproject.lift.CodeShape
import dev.apkproject.lift.ShapeComparison
import dev.apkproject.lift.StructuralMatch
import dev.apkproject.reconstruct.ReconstructOptions
import dev.apkproject.reconstruct.ReconstructedMethod
import dev.apkproject.reconstruct.Reconstructor
import dev.apkproject.source.JClass
import dev.apkproject.source.JavaPrinter
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ProbeRecord(
    val round: Int,
    val variant: String,
    val compiled: Boolean,
    val shape: String?,
    val distance: Int?,
)

/** Evidence from one local experiment (dossier section 16). */
@Serializable
data class ExperimentRecord(
    val method: String,
    /** The uncertain source features probed: branch polarity, operand order, ternary form. */
    val hypotheses: List<String>,
    val probes: List<ProbeRecord>,
    val chosen: String?,
    /** RESOLVED: identical bytecode; AMBIGUOUS: several variants tie; BUDGET: stopped by the probe budget; NO_MATCH: features exhausted. */
    val outcome: String,
    val profileHash: String,
)

class ExperimentResult(val record: ExperimentRecord, val best: ReconstructedMethod?, val bestShape: ShapeComparison?)

/**
 * Local compiler experiments (section 16): isolate one method, name its uncertain source features,
 * change one feature per probe, compile all probes under the recorded profile in one batch, compare
 * their bytecode with the original, keep the best variant and probe again from it. Stops when the
 * bytecode is identical, no single change improves it, or the probe budget is spent.
 *
 * Every probe is semantically equivalent by construction; acceptance of the chosen variant is still
 * decided by the validator. A structural match is historical-fidelity evidence, never proof of the
 * original source.
 */
class LocalExperiments(
    private val profile: ResolvedProfile,
    private val budget: Budget,
    private val cancel: CancellationToken? = null,
) {
    data class Request(val ir: MethodIr, val base: ReconstructedMethod, val originalShape: CodeShape, val javaName: String, val modifiers: List<String>)

    private sealed class Feature {
        data class Polarity(val block: Int) : Feature() {
            override fun toString() = "polarity(b$block)"
        }
        data class Swap(val value: Int) : Feature() {
            override fun toString() = "operand-order(%$value)"
        }
        object Ternary : Feature() {
            override fun toString() = "ternary-form"
        }
    }

    private fun features(base: ReconstructedMethod): List<Feature> =
        base.branchBlocks.map { Feature.Polarity(it) } + base.commutativeOps.map { Feature.Swap(it) } +
            (if (base.branchBlocks.isNotEmpty()) listOf(Feature.Ternary) else emptyList())

    private fun toggle(o: ReconstructOptions, f: Feature): ReconstructOptions = when (f) {
        is Feature.Polarity -> o.copy(invertedIfs = if (f.block in o.invertedIfs) o.invertedIfs - f.block else o.invertedIfs + f.block)
        is Feature.Swap -> o.copy(swappedOperands = if (f.value in o.swappedOperands) o.swappedOperands - f.value else o.swappedOperands + f.value)
        Feature.Ternary -> o.copy(ternary = !o.ternary)
    }

    private inner class Search(val req: Request) {
        val features = features(req.base)
        var current: ReconstructOptions = req.base.options
        var currentShape: ShapeComparison? = null
        val tried = HashSet<ReconstructOptions>()
        val probes = ArrayList<ProbeRecord>()
        var done = false
        var budgetHit = false
        var lastRoundTies = 0

        fun spent() = probes.size
    }

    private data class Probe(val search: Search, val options: ReconstructOptions, val cls: String, val method: String)

    fun run(requests: List<Request>, workDir: Path): Map<String, ExperimentResult> {
        if (requests.isEmpty()) return emptyMap()
        val searches = requests.map { Search(it) }
        var round = 0
        // Round 0 measures the starting point; later rounds flip one feature at a time.
        while (true) {
            if (cancel?.cancelled == true) break
            val probes = ArrayList<Probe>()
            for ((i, s) in searches.withIndex()) {
                if (s.done) continue
                val options = if (round == 0) listOf(s.current) else s.features.map { toggle(s.current, it) }.filter { it !in s.tried }
                val room = budget.maxProbesPerPattern - s.spent()
                if (options.isEmpty()) {
                    s.done = true; continue
                }
                if (room <= 0) {
                    s.done = true; s.budgetHit = true; continue
                }
                options.take(room).forEachIndexed { k, o ->
                    s.tried += o
                    probes += Probe(s, o, "M${i}R$round", "v$k")
                }
                if (options.size > room) s.budgetHit = true
            }
            if (probes.isEmpty()) break
            val shapes = compileAndCompare(probes, workDir.resolve("round$round"))
            for (s in searches.filter { !it.done }) {
                val mine = probes.filter { it.search === s }
                if (mine.isEmpty()) continue
                val scored = mine.map { it to shapes[it] }
                scored.forEach { (p, sh) -> s.probes += ProbeRecord(round, p.options.label, sh != null, sh?.match?.name, sh?.distance) }
                val best = scored.filter { it.second != null }.minWithOrNull(compareBy({ rank(it.second!!) }, { it.second!!.distance }))
                if (round == 0) {
                    s.currentShape = best?.second
                    if (best == null || best.second!!.match == StructuralMatch.IDENTICAL) s.done = true
                    continue
                }
                val cur = s.currentShape
                s.lastRoundTies = scored.count { it.second != null && best != null && rank(it.second!!) == rank(best.second!!) && it.second!!.distance == best.second!!.distance }
                if (best != null && (cur == null || better(best.second!!, cur))) {
                    s.current = best.first.options
                    s.currentShape = best.second
                    if (best.second!!.match == StructuralMatch.IDENTICAL) s.done = true
                } else {
                    s.done = true
                }
            }
            round++
            if (searches.all { it.done }) break
        }
        return searches.associate { s ->
            val shape = s.currentShape
            val outcome = when {
                shape == null -> "NO_MATCH"
                shape.match == StructuralMatch.IDENTICAL && s.lastRoundTies > 1 -> "AMBIGUOUS"
                shape.match == StructuralMatch.IDENTICAL -> "RESOLVED"
                s.budgetHit -> "BUDGET"
                else -> "NO_MATCH"
            }
            val record = ExperimentRecord(
                method = s.req.ir.key.display(),
                hypotheses = s.features.map { it.toString() },
                probes = s.probes,
                chosen = s.current.label,
                outcome = outcome,
                profileHash = profile.profileHash,
            )
            val best = Reconstructor.reconstruct(s.req.ir, s.req.javaName, s.req.modifiers, s.current)
            s.req.ir.key.display() to ExperimentResult(record, best, shape)
        }
    }

    private fun compileAndCompare(probes: List<Probe>, workDir: Path): Map<Probe, ShapeComparison> {
        val byClass = probes.groupBy { it.cls }
        val sources = byClass.mapValues { (cls, ps) ->
            val methods = ps.mapNotNull { p -> runCatching { Reconstructor.reconstruct(p.search.req.ir, p.method, listOf("public", "static"), p.options).method }.getOrNull() }
            JavaPrinter.print(JClass("apkprobe", cls, listOf("public", "final"), methods)).text
        }.mapKeys { "apkprobe/${it.key}.java" }
        val compiled = ForwardCompiler(profile, cancel).compile(sources, workDir, budget.compileTimeoutMs)
        if (compiled.status != CheckStatus.PASS) return emptyMap()
        val dex = (DexFile.parse(Files.readAllBytes(compiled.dexFile!!)) as? Outcome.Ok)?.value ?: return emptyMap()
        val methods = dex.methods().associateBy { it.owner to it.name }
        return probes.mapNotNull { p ->
            val m = methods["Lapkprobe/${p.cls};" to p.method] ?: return@mapNotNull null
            val decoded = (InstructionDecoder.decode(dex, m.code!!) as? Outcome.Ok)?.value ?: return@mapNotNull null
            p to p.search.req.originalShape.compare(CodeShape.of(dex, decoded))
        }.toMap()
    }

    private fun better(a: ShapeComparison, b: ShapeComparison): Boolean = rank(a) < rank(b) || (rank(a) == rank(b) && a.distance < b.distance)

    private fun rank(s: ShapeComparison): Int = when (s.match) {
        StructuralMatch.IDENTICAL -> 0
        StructuralMatch.ISOMORPHIC -> 1
        StructuralMatch.DIFFERENT -> 2
    }
}
