package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Feature-only local feedback. Raw Java, APK contents, paths and runtime logs are not exported. */
@Serializable
data class RepairLearningExample(val schemaVersion: Int = 1, val featureVersion: Int = 1,
    val inputGroup: String, val querySha256: String, val candidateSha256: String, val profileHash: String,
    val rule: String, val features: List<Double>, val label: Int,
    val labelEvidence: String = "COMPLETE_ROUNDTRIP_NO_REGRESSION", val improvedMethods: Int,
    val comparedMethods: Int, val unitSha256: String? = null,
    val queryCodeSha256: String? = null, val candidateCodeSha256: String? = null)

@Serializable
data class RepairModelEvaluation(val trainingProjects: Int, val validationProjects: Int, val testProjects: Int,
    val testQueries: Int, val baselineMrr: Double, val modelMrr: Double, val baselineHit5: Double, val modelHit5: Double,
    val validationQueries: Int = 0, val validationUnits: Int = 0, val testUnits: Int = 0,
    val validationRankingProjects: Int = 0, val testRankingProjects: Int = 0,
    val baselineUnitMrr: Double = 0.0, val modelUnitMrr: Double = 0.0,
    val baselineFamilyMrr: Double = 0.0, val modelFamilyMrr: Double = 0.0,
    val strongestBaselineMrr: Double = 0.0, val strongestBaselineHit5: Double = 0.0,
    val strongestBaselineUnitMrr: Double = 0.0, val strongestBaselineFamilyMrr: Double = 0.0)

@Serializable
data class RepairPriorityModel(val schemaVersion: Int = 1, val featureVersion: Int = 1,
    val features: List<String>, val weights: List<Double>, val bias: Double, val datasetSha256: String,
    val eligible: Boolean, val evaluation: RepairModelEvaluation)

/** ML ranks proposals only. The round-trip gate still decides whether any edit can be kept. */
object RepairLearning {
    val featureNames = listOf("outer-instance", "drop-cast", "widen-divide", "widen-multiply", "to-float", "to-double", "to-long",
        "before-float", "before-double", "before-long", "before-return", "before-casts", "before-length", "length-change")

    data class CheckedLabel(val label: Int, val improvedMethods: Int, val comparedMethods: Int)

    /** Token fingerprint for deduplication only; retains literals and operator boundaries. */
    fun sourceFingerprint(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val operators = listOf(">>>=", ">>>", ">>=", "<<=", "...", "++", "--", "&&", "||", "==", "!=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<=", ">=", ">>", "<<", "->", "::")
        var i = 0
        while (i < source.length) {
            if (source[i].isWhitespace()) { i++; continue }
            if (source.startsWith("//", i)) { i = source.indexOf('\n', i + 2).let { if (it < 0) source.length else it }; continue }
            if (source.startsWith("/*", i)) { i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }; continue }
            val start = i
            if (source[i] == '"' || source[i] == '\'') {
                val quote = source[i++]
                while (i < source.length) { val c = source[i++]; if (c == '\\' && i < source.length) i++ else if (c == quote) break }
            } else if (Character.isJavaIdentifierPart(source[i])) {
                while (i < source.length && Character.isJavaIdentifierPart(source[i])) i++
            } else {
                i += operators.firstOrNull { source.startsWith(it, i) }?.length ?: 1
            }
            val token = source.substring(start, i).toByteArray(Charsets.UTF_8)
            for (shift in listOf(24, 16, 8, 0)) digest.update((token.size ushr shift).toByte())
            digest.update(token)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Both sides need all the same methods and known comparisons; missing is not failure data. */
    fun checkedLabel(before: List<MethodRoundTrip>, after: List<MethodRoundTrip>): CheckedLabel? {
        val a = before.filter { it.result != "SYNTHETIC" }.associateBy { it.method }
        val b = after.filter { it.result != "SYNTHETIC" }.associateBy { it.method }
        val order = listOf("EQUIVALENT_DATAFLOW", "EQUIVALENT_MODULO_LAYOUT", "SIMILAR_OPERATIONS", "DIFFERENT")
        if (a.isEmpty() || a.keys != b.keys || a.size != before.count { it.result != "SYNTHETIC" } || b.size != after.count { it.result != "SYNTHETIC" } ||
            (a.values + b.values).any { it.result !in order }) return null
        fun score(result: String) = if (result in RoundTripVerifier.EQUIVALENT) 0 else order.indexOf(result)
        val worse = b.any { (key, value) -> score(value.result) > score(a.getValue(key).result) }
        val improved = b.count { (key, value) -> value.result in RoundTripVerifier.EQUIVALENT && a.getValue(key).result !in RoundTripVerifier.EQUIVALENT }
        return CheckedLabel(if (!worse && improved > 0) 1 else 0, improved, a.size)
    }

    fun features(c: RepairCandidate): List<Double> {
        val before = JavaMethodLocator.codeOnly(c.before.take(4096))
        val after = JavaMethodLocator.codeOnly(c.after.take(4096))
        fun yes(value: Boolean) = if (value) 1.0 else 0.0
        fun word(s: String, w: String) = Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(s)
        return listOf(yes(c.rule == "implicit-outer-instance"), yes(c.rule == "drop-cast"), yes(c.rule == "widen-before-divide"),
            yes(c.rule == "widen-before-multiply"), yes(after.contains("(float)")), yes(after.contains("(double)")), yes(after.contains("(long)")),
            yes(word(before, "float")), yes(word(before, "double")), yes(word(before, "long")), yes(word(before, "return")),
            Regex("\\(\\s*[A-Za-z][\\w.$]*\\s*\\)").findAll(before).take(16).count() / 16.0,
            before.length.coerceAtMost(1024) / 1024.0, (after.length - before.length).coerceIn(-1024, 1024) / 1024.0)
    }

    /** Invalid, oversized, uncalibrated or unpromoted models fall back to deterministic rules. */
    fun load(path: Path?, log: (String) -> Unit = {}): RepairPriorityModel? {
        if (path == null) return null
        return try {
            val bytes = Files.newInputStream(path).use { it.readNBytes(32_769) }
            require(bytes.size <= 32_768) { "model exceeds 32 KiB" }
            val m = Json.decode<RepairPriorityModel>(bytes.toString(Charsets.UTF_8))
            require(m.schemaVersion == 1 && m.featureVersion == 1 && m.features == featureNames && m.weights.size == featureNames.size)
            require(m.weights.all { it.isFinite() && kotlin.math.abs(it) <= 10_000 } && m.bias.isFinite() && kotlin.math.abs(m.bias) <= 10_000)
            require(m.datasetSha256.matches(Regex("[0-9a-f]{64}")))
            val e = m.evaluation
            require(e.trainingProjects >= 3 && e.validationProjects >= 1 && e.testProjects >= 1 && e.testQueries >= 10)
            require(e.validationQueries >= 5 && e.validationUnits >= 5 && e.testUnits >= 10) { "independent held-out source-unit support not established" }
            require(e.validationUnits <= e.validationQueries && e.testUnits <= e.testQueries) { "source-unit support exceeds query support" }
            require(e.validationRankingProjects in 2..e.validationProjects && e.testRankingProjects in 2..e.testProjects) { "multiple informative held-out families not established" }
            require(listOf(e.baselineMrr, e.modelMrr, e.baselineHit5, e.modelHit5,
                e.baselineUnitMrr, e.modelUnitMrr, e.baselineFamilyMrr, e.modelFamilyMrr,
                e.strongestBaselineMrr, e.strongestBaselineHit5, e.strongestBaselineUnitMrr, e.strongestBaselineFamilyMrr).all { it.isFinite() && it in 0.0..1.0 })
            require(m.eligible && e.modelMrr > e.baselineMrr + 0.01 && e.modelHit5 >= e.baselineHit5) { "held-out ranking improvement not established" }
            require(e.strongestBaselineMrr >= e.baselineMrr && e.strongestBaselineHit5 >= e.baselineHit5 &&
                e.strongestBaselineUnitMrr >= e.baselineUnitMrr && e.strongestBaselineFamilyMrr >= e.baselineFamilyMrr)
            require(e.modelMrr > e.strongestBaselineMrr + 0.01 && e.modelHit5 >= e.strongestBaselineHit5 &&
                e.modelUnitMrr > e.strongestBaselineUnitMrr + 0.01 && e.modelFamilyMrr > e.strongestBaselineFamilyMrr + 0.01) { "improvement over simple baselines and balanced held-out units not established" }
            log("repair learning: model ${Hashing.sha256(bytes).take(16)} loaded; suggestions remain subject to complete round-trip verification")
            m
        } catch (e: Exception) {
            log("repair learning: model disabled (${e.message ?: e.javaClass.simpleName}); using deterministic rules")
            null
        }
    }

    fun rank(candidates: List<RepairCandidate>, model: RepairPriorityModel?): List<RepairCandidate> =
        if (model == null) candidates else candidates.sortedByDescending { c ->
            model.bias + features(c).zip(model.weights).sumOf { (x, w) -> x * w }
        }

    fun append(path: Path, example: RepairLearningExample) {
        require(example.features.size == featureNames.size && example.features.all { it.isFinite() })
        val text = Json.encodeLine(example) + "\n"
        require(text.toByteArray(Charsets.UTF_8).size <= 4096)
        Files.createDirectories(path.parent)
        if (!Files.exists(path) || Files.size(path) + text.length <= 16L * 1024 * 1024)
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
