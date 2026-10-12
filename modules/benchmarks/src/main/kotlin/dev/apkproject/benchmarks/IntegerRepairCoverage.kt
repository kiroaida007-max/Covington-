package dev.apkproject.benchmarks

import dev.apkproject.app.*
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.lift.DexLifter
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
private data class CoverageInput(val family: String, val coordinate: String, val license: String, val sourceUrl: String,
    val sourceArchiveSha256: String, val dex: String, val dexSha256: String, val sources: String, val baseline: String, val baselineSha256: String)
@Serializable
private data class CoverageRequest(val schemaVersion: Int, val selection: String, val captures: List<CoverageInput>)
@Serializable
private data class CoverageResult(val family: String, val coordinate: String, val license: String, val sourceUrl: String,
    val sourceArchiveSha256: String, val inputDexSha256: String, val baselineSha256: String,
    val methodsWithCode: Int, val boundedIntegerSignatures: Int, val supportedV0Methods: Int, val unsupported: Map<String, Int>,
    val baselineUnresolvedMethods: Int, val baselineNotEvaluatedMethods: Int, val boundedUnresolvedMethods: Int,
    val supportedUnresolvedMethods: Int, val locatedRepairProposals: Int,
    val proposalHashes: List<String>,
    val scope: String = "Capability and proposal-location scan of existing captured public-library subsets. No candidate compilation/acceptance or recovery-accuracy gain is measured. No model calls, training labels or holdout changes.")

/** Read-only, hash-bound coverage probe; never use proposal counts as verified repair counts. */
object IntegerRepairCoverage {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "IntegerRepairCoverage FROZEN_INPUT.json NEW_RESULTS.json" }
        val bytes = Files.newInputStream(Path.of(args[0])).use { it.readNBytes(65_537) }
        require(bytes.size <= 65_536)
        val request = Json.decode<CoverageRequest>(bytes.toString(Charsets.UTF_8))
        require(request.schemaVersion == 1 && request.captures.size in 1..8 && request.captures.map { it.family }.distinct().size == request.captures.size)
        val out = Path.of(args[1]); require(!Files.exists(out))
        val results = request.captures.map { q ->
            val dexBytes = Files.newInputStream(Path.of(q.dex)).use { it.readNBytes(64 * 1024 * 1024 + 1) }
            require(dexBytes.size <= 64 * 1024 * 1024 && Hashing.sha256(dexBytes) == q.dexSha256)
            val dex = (DexFile.parse(dexBytes) as? Outcome.Ok)?.value ?: error("invalid captured original DEX")
            val baselineBytes = Files.newInputStream(Path.of(q.baseline)).use { it.readNBytes(2 * 1024 * 1024 + 1) }
            require(baselineBytes.size <= 2 * 1024 * 1024 && Hashing.sha256(baselineBytes) == q.baselineSha256)
            val baselineRows = Json.decode<List<MethodRoundTrip>>(baselineBytes.toString(Charsets.UTF_8))
            require(baselineRows.map { it.method }.distinct().size == baselineRows.size)
            val baseline = baselineRows.associateBy { it.method }
            val root = Path.of(q.sources).toAbsolutePath().normalize()
            val files = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.limit(257).toList() }
            require(files.size in 1..256 && files.sumOf { Files.size(it) } <= 64L * 1024 * 1024)
            val renames = JadxRenames.parse(root)
            val gaps = linkedMapOf<String, Int>(); var supported = 0; var unresolved = 0; var supportedUnresolved = 0
            val proposals = arrayListOf<String>()
            val methods = dex.methods().filter { it.code != null }
            val bounded = methods.filter(IntegerRepair::eligible)
            for (method in bounded) {
                val result = baseline[method.key.display()]?.result
                val bad = result in setOf("DIFFERENT", "SIMILAR_OPERATIONS", "NOT_COMPILED")
                if (bad) unresolved++
                val lifted = DexLifter.lift(method)
                if (lifted.ir == null) {
                    lifted.gaps.map { it.code }.distinct().forEach { gaps[it] = (gaps[it] ?: 0) + 1 }; continue
                }
                supported++
                if (!bad) continue
                supportedUnresolved++
                val file = RoundTripVerifier.topLevelFile(method.owner)
                val path = root.resolve(file).normalize()
                if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.size(path) > 1_048_576) continue
                val text = Files.newInputStream(path).use { it.readNBytes(1_048_577) }
                if (text.size > 1_048_576) continue
                val name = renames.recompiledMethodName(method.owner, method.name) ?: method.name
                val proposal = IntegerRepair.candidate(file, text.toString(Charsets.UTF_8), "${method.owner}->$name${method.protoDescriptor}", method)
                if (proposal != null) proposals += Hashing.sha256(proposal.text)
            }
            CoverageResult(q.family, q.coordinate, q.license, q.sourceUrl, q.sourceArchiveSha256, q.dexSha256, q.baselineSha256,
                methods.size, bounded.size, supported, gaps,
                baseline.values.count { it.result in setOf("DIFFERENT", "SIMILAR_OPERATIONS", "NOT_COMPILED") },
                methods.count { it.key.display() !in baseline }, unresolved, supportedUnresolved, proposals.size, proposals).also { println(Json.encodeLine(it)) }
        }
        Files.createDirectories(out.toAbsolutePath().parent)
        Files.writeString(out, Json.encode(results))
    }
}
