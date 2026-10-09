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
import java.nio.file.Path

@Serializable
data class ProbeRecord(
    val variant: String,
    val compiled: Boolean,
    val shape: String?,
    val distance: Int?,
)

/** Evidence from one local experiment (dossier section 16). */
@Serializable
data class ExperimentRecord(
    val method: String,
    val hypotheses: List<String>,
    val probes: List<ProbeRecord>,
    val chosen: String?,
    /** RESOLVED: one best variant; AMBIGUOUS: several indistinguishable; BUDGET: stopped early; NO_MATCH: none reproduced the original. */
    val outcome: String,
    val profileHash: String,
)

class ExperimentResult(val record: ExperimentRecord, val best: ReconstructedMethod?, val bestShape: ShapeComparison?)

/**
 * Local compiler experiments: when the first reconstruction does not recompile to the original
 * bytecode, enumerate semantically equivalent source variants (branch polarity, ternary vs
 * if/else), compile them all under the recorded profile in one batch, and keep the variant whose
 * bytecode is closest to the original. Semantics are checked separately by the validator; a
 * structural match is historical-fidelity evidence only.
 */
class LocalExperiments(
    private val profile: ResolvedProfile,
    private val budget: Budget,
    private val cancel: CancellationToken? = null,
) {
    data class Request(val ir: MethodIr, val base: ReconstructedMethod, val originalShape: CodeShape, val javaName: String, val modifiers: List<String>)

    /** The hypothesis grammar for one method, closest to the base reconstruction first. */
    fun variants(base: ReconstructedMethod): List<ReconstructOptions> {
        val branches = base.branchBlocks
        val out = ArrayList<ReconstructOptions>()
        val polaritySets = subsetsBySize(branches, budget.maxProbesPerPattern)
        for (ternary in listOf(base.options.ternary, !base.options.ternary)) {
            for (inv in polaritySets) {
                out += base.options.copy(invertedIfs = inv.toSet(), ternary = ternary)
                if (out.size >= budget.maxProbesPerPattern) return out
            }
            if (branches.isEmpty()) break
        }
        return out
    }

    private fun subsetsBySize(items: List<Int>, cap: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (k in 0..items.size) {
            fun rec(start: Int, acc: List<Int>) {
                if (out.size >= cap) return
                if (acc.size == k) {
                    out += acc; return
                }
                for (i in start until items.size) rec(i + 1, acc + items[i])
            }
            rec(0, emptyList())
            if (out.size >= cap) break
        }
        return out
    }

    fun run(requests: List<Request>, workDir: Path): Map<String, ExperimentResult> {
        if (requests.isEmpty()) return emptyMap()
        // Build every probe as a method of a per-request probe class, compiled in one batch.
        data class Probe(val req: Request, val index: Int, val options: ReconstructOptions, val method: ReconstructedMethod, val probeClass: String, val probeMethod: String)
        val probes = ArrayList<Probe>()
        val sources = LinkedHashMap<String, String>()
        requests.forEachIndexed { r, req ->
            val cls = "M$r"
            val methods = variants(req.base).mapIndexedNotNull { i, opts ->
                val rm = runCatching { Reconstructor.reconstruct(req.ir, "v$i", listOf("public", "static"), opts) }.getOrNull() ?: return@mapIndexedNotNull null
                probes += Probe(req, i, opts, rm, "Lapkprobe/$cls;", "v$i")
                rm.method
            }
            sources["apkprobe/$cls.java"] = JavaPrinter.print(JClass("apkprobe", cls, listOf("public", "final"), methods)).text
        }
        val compiled = ForwardCompiler(profile, cancel).compile(sources, workDir, budget.compileTimeoutMs)
        val dex = compiled.dexFile?.takeIf { compiled.status == CheckStatus.PASS }?.let { (DexFile.parse(java.nio.file.Files.readAllBytes(it)) as? Outcome.Ok)?.value }
        val byOwner = dex?.methods()?.associateBy { it.owner to it.name } ?: emptyMap()

        return requests.associate { req ->
            val mine = probes.filter { it.req === req }
            val scored = mine.map { p ->
                val m = byOwner[p.probeClass to p.probeMethod]
                val shape = m?.code?.let { code -> (InstructionDecoder.decode(dex!!, code) as? Outcome.Ok)?.let { req.originalShape.compare(CodeShape.of(dex, it.value)) } }
                Triple(p, shape, m != null)
            }
            val ranked = scored.filter { it.second != null }.sortedWith(compareBy({ rank(it.second!!) }, { it.second!!.distance }, { it.first.index }))
            val best = ranked.firstOrNull()
            val bestRank = best?.second?.let(::rank)
            val ties = ranked.filter { it.second?.let(::rank) == bestRank && it.second!!.distance == best?.second?.distance }
            val outcome = when {
                best == null -> "NO_MATCH"
                best.second!!.match == StructuralMatch.DIFFERENT -> if (mine.size >= budget.maxProbesPerPattern) "BUDGET" else "NO_MATCH"
                ties.size > 1 -> "AMBIGUOUS"
                else -> "RESOLVED"
            }
            val record = ExperimentRecord(
                method = req.ir.key.display(),
                hypotheses = mine.map { it.options.label },
                probes = scored.map { (p, s, ok) -> ProbeRecord(p.options.label, ok, s?.match?.name, s?.distance) },
                chosen = best?.first?.options?.label,
                outcome = outcome,
                profileHash = profile.profileHash,
            )
            val chosen = best?.let { Reconstructor.reconstruct(req.ir, req.javaName, req.modifiers, it.first.options) }
            req.ir.key.display() to ExperimentResult(record, chosen, best?.second)
        }
    }

    private fun rank(s: ShapeComparison): Int = when (s.match) {
        StructuralMatch.IDENTICAL -> 0
        StructuralMatch.ISOMORPHIC -> 1
        StructuralMatch.DIFFERENT -> 2
    }
}
