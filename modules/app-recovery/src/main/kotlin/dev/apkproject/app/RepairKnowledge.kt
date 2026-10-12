package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln

@Serializable
data class RepairKnowledgeEntry(val id: String, val rule: String, val tags: List<String>,
    val before: String, val after: String, val explanation: String, val sourceUrl: String,
    val license: String, val unitSha256: String? = null, val queryCodeSha256: String? = null, val candidateCodeSha256: String? = null,
    val evidence: String = "COMPLETE_ROUNDTRIP_NO_REGRESSION")

@Serializable
data class RepairKnowledge(val schemaVersion: Int = 1, val entries: List<RepairKnowledgeEntry>) {
    fun checked(): RepairKnowledge {
        require(schemaVersion == 1 && entries.size <= 256)
        require(entries.map { it.id }.distinct().size == entries.size)
        entries.forEach { e ->
            require(e.id.matches(Regex("[a-zA-Z0-9._-]{1,80}")) && e.rule.length in 1..80 && e.tags.size <= 24)
            require(e.tags.all { it.length in 1..64 } && e.before.length in 1..512 && e.after.length in 1..512)
            require(e.explanation.length in 1..1024 && e.license.length in 1..256 && e.sourceUrl.length in 1..2048)
            require(e.sourceUrl.startsWith("https://") || e.sourceUrl.startsWith("urn:apkproject:checked:"))
            require(e.before != e.after && e.evidence in setOf("COMPLETE_ROUNDTRIP_NO_REGRESSION", "ORIGINAL_ALL_METHODS_EQUIVALENT", "REFERENCE_GUIDANCE"))
            val hashes = listOf(e.unitSha256, e.queryCodeSha256, e.candidateCodeSha256)
            if (e.evidence != "REFERENCE_GUIDANCE") require(hashes.all { it?.matches(Regex("[0-9a-f]{64}")) == true })
            else require(hashes.all { it == null }) // Reference advice is not checked repair data.
        }
        return this
    }

    companion object {
        private const val LIMIT = 512 * 1024
        fun decode(bytes: ByteArray): RepairKnowledge {
            require(bytes.size <= LIMIT) { "repair knowledge exceeds 512 KiB" }
            return Json.decode<RepairKnowledge>(bytes.toString(Charsets.UTF_8)).checked()
        }
        fun load(path: Path): RepairKnowledge = decode(Files.newInputStream(path).use { it.readNBytes(LIMIT + 1) })
        fun builtInBytes(): ByteArray = RepairKnowledge::class.java.getResourceAsStream("/dev/apkproject/app/repair-knowledge.json")
            ?.use { it.readNBytes(LIMIT + 1) } ?: error("built-in repair knowledge missing")
    }
}

/** Local BM25 retrieval: no embedding service, network calls or vector database. */
class RepairRetriever(private val knowledge: RepairKnowledge) {
    private val bags = knowledge.checked().entries.map { e -> tokens(e.rule + " " + e.tags.joinToString(" ") + " " + e.before + " " + e.explanation).groupingBy { it }.eachCount() }
    private val lengths = bags.map { it.values.sum().coerceAtLeast(1) }
    private val average = lengths.average().takeIf { it.isFinite() && it > 0 } ?: 1.0
    private val frequency = bags.flatMap { it.keys }.groupingBy { it }.eachCount()

    fun retrieve(query: String, excludedUnits: Set<String> = emptySet(), limit: Int = 3): List<RepairKnowledgeEntry> {
        require(limit in 0..3)
        val words = tokens(query.take(16_384)).distinct().take(128)
        return knowledge.entries.indices.filter { knowledge.entries[it].unitSha256 !in excludedUnits }.map { index ->
            val score = words.sumOf { token ->
                val tf = bags[index][token] ?: 0
                if (tf == 0) 0.0 else {
                    val df = frequency[token] ?: 0
                    val idf = ln(1 + (bags.size - df + 0.5) / (df + 0.5))
                    idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * lengths[index] / average))
                }
            }
            index to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { knowledge.entries[it.first].id })
            .take(limit).map { knowledge.entries[it.first] }
    }

    companion object {
        fun tokens(text: String): List<String> = Regex("[A-Za-z_$][A-Za-z0-9_$-]{1,63}")
            .findAll(text).take(4096).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()
        fun identity(knowledge: RepairKnowledge): String = Hashing.sha256(Json.encode(knowledge).toByteArray(Charsets.UTF_8))
    }
}
