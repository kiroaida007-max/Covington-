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
        evaluation = RepairModelEvaluation(3, 1, 1, 20, 0.3, 0.8, 0.8, 0.9, validationQueries = 10, validationUnits = 10, testUnits = 20))

    @Test fun `unknown missing duplicate or regressing method evidence cannot produce a positive label`() {
        val a = MethodRoundTrip("Lsample;->a()I", "DIFFERENT", "sample.java")
        val b = MethodRoundTrip("Lsample;->b()I", "EQUIVALENT_DATAFLOW", "sample.java")
        val fixed = a.copy(result = "EQUIVALENT_MODULO_LAYOUT")
        assertEquals(RepairLearning.CheckedLabel(1, 1, 2), RepairLearning.checkedLabel(listOf(a, b), listOf(fixed, b)))
        assertEquals(RepairLearning.CheckedLabel(0, 1, 2), RepairLearning.checkedLabel(listOf(a, b), listOf(fixed, b.copy(result = "DIFFERENT"))))
        assertNull(RepairLearning.checkedLabel(listOf(a.copy(result = "NOT_FOUND"), b), listOf(fixed, b)))
        assertNull(RepairLearning.checkedLabel(listOf(a, b), listOf(fixed)))
        assertNull(RepairLearning.checkedLabel(listOf(a, b), listOf(fixed, b.copy(result = "UNKNOWN"))))
        assertNull(RepairLearning.checkedLabel(listOf(a, a), listOf(fixed)))
    }

    @Test fun `fingerprints ignore tool comments and spacing while preserving literals and operators`() {
        val a = "class A { /* JADX INFO: loaded from: /private/one.dex */ String s = \"http://host/*text*/\"; int f(int x) { return x + ++x; } }"
        val b = a.replace("/private/one.dex", "/other/two.dex").replace("class A", "class  A")
        assertEquals(RepairLearning.sourceFingerprint(a), RepairLearning.sourceFingerprint(b))
        assertNotEquals(RepairLearning.sourceFingerprint(a), RepairLearning.sourceFingerprint(a.replace("host", "other")))
        assertNotEquals(RepairLearning.sourceFingerprint(a), RepairLearning.sourceFingerprint(a.replace("x + ++x", "x++ + x")))
        assertNotEquals(RepairLearning.sourceFingerprint("int a;"), RepairLearning.sourceFingerprint("inta;"))
    }

    @Test fun `only promoted bounded finite models can change proposal order`() {
        val path = dir.resolve("model.json")
        Files.writeString(path, Json.encode(model()))
        val loaded = assertNotNull(RepairLearning.load(path))
        val base = RepairCandidate("private.java", "widen-before-divide", 3, "return a / b;", "return ((long) a) / b;", "private source")
        val float = base.copy(after = "return ((float) a) / b;")
        assertEquals(listOf(float, base), RepairLearning.rank(listOf(base, float), loaded))
        assertEquals(listOf(base, float), RepairLearning.rank(listOf(base, float), null))
        for (invalid in listOf(model().copy(eligible = false), model().copy(weights = listOf(1.0)),
            model().copy(evaluation = model().evaluation.copy(modelMrr = 0.3)), model().copy(evaluation = model().evaluation.copy(testUnits = 2)), model().copy(featureVersion = 99))) {
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
