package dev.apkproject.app

import dev.apkproject.contracts.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepairLearningTest {
    @TempDir lateinit var dir: Path
    private fun model() = RepairPriorityModel(features = RepairLearning.featureNames, weights = List(14) { if (it == 4) 5.0 else 0.0 },
        bias = 0.0, datasetSha256 = "a".repeat(64), eligible = true,
        evaluation = RepairModelEvaluation(3, 1, 1, 20, 0.3, 0.8, 0.8, 0.9))

    @Test fun `only promoted bounded finite models can change proposal order`() {
        val path = dir.resolve("model.json")
        Files.writeString(path, Json.encode(model()))
        val loaded = assertNotNull(RepairLearning.load(path))
        val base = RepairCandidate("private.java", "widen-before-divide", 3, "return a / b;", "return ((long) a) / b;", "private source")
        val float = base.copy(after = "return ((float) a) / b;")
        assertEquals(listOf(float, base), RepairLearning.rank(listOf(base, float), loaded))
        assertEquals(listOf(base, float), RepairLearning.rank(listOf(base, float), null))
        for (invalid in listOf(model().copy(eligible = false), model().copy(weights = listOf(1.0)),
            model().copy(evaluation = model().evaluation.copy(modelMrr = 0.3)), model().copy(featureVersion = 99))) {
            Files.writeString(path, Json.encode(invalid)); assertNull(RepairLearning.load(path))
        }
        Files.writeString(path, " ".repeat(32_769)); assertNull(RepairLearning.load(path))
        assertNull(RepairLearning.load(dir.resolve("missing.json")))
    }

    @Test fun `local feedback contains checked features and hashes without raw source or paths`() {
        val candidate = RepairCandidate("/private/customer.java", "widen-before-divide", 3, "return secretCustomerAmount / b;", "return ((float) secretCustomerAmount) / b;", "full confidential Java")
        val example = RepairLearningExample(inputGroup = "a".repeat(64), querySha256 = "b".repeat(64), candidateSha256 = "c".repeat(64),
            profileHash = "d".repeat(64), rule = candidate.rule, features = RepairLearning.features(candidate), label = 1, improvedMethods = 1, comparedMethods = 2)
        val target = dir.resolve("feedback.jsonl")
        RepairLearning.append(target, example)
        val text = Files.readString(target)
        assertFalse("confidential" in text || "secretCustomer" in text || "customer.java" in text)
        assertEquals(example, Json.decode<RepairLearningExample>(text))
    }
}
