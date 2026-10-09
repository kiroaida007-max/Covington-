package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Precision and recall of [LibraryMatcher] against R8's own mapping. Runs only when
 * benchmarks/library-matching/run.sh has built the R8 app and set APKPROJECT_LIBMATCH.
 */
class LibraryMatchingBenchmark {
    @Test
    fun `library matching against R8 ground truth`() {
        val dir = System.getenv("APKPROJECT_LIBMATCH")
        assumeTrue(dir != null)
        val root = Paths.get(dir!!)
        val fixtures = if (Files.isDirectory(root.resolve("out"))) listOf(root) else Files.list(root).use { paths ->
            paths.filter { Files.isDirectory(it.resolve("out")) }.sorted().toList()
        }
        assertTrue(fixtures.isNotEmpty(), "no R8 fixtures in $root")
        assertAll(fixtures.map { fixture -> Executable { evaluate(fixture) } })
    }

    private fun evaluate(root: Path) {
        fun dex(p: java.nio.file.Path) = Files.list(p).use { s -> s.filter { it.toString().endsWith(".dex") }.toList() }.map { (DexFile.parse(Files.readAllBytes(it)) as Outcome.Ok).value }
        val apk = LibraryMatcher.profiles(dex(root.resolve("out")))
        val lib = LibraryMatcher.profiles(dex(root.resolve("libdex")))
        // Ground truth: R8's mapping (original -> obfuscated).
        // Several originals can share one R8-merged class. None is a safe single class name.
        val groundTruth = R8GroundTruth(Files.readAllLines(root.resolve("mapping.txt")))
        val origins = groundTruth.origins
        val truth = groundTruth.classes
        val merged = apk.keys.count { origins[it].orEmpty().size > 1 }
        val renamedLib = apk.keys.filter { truth[it] != null && truth[it] != it && lib.containsKey(truth[it]) }
        val known = apk.keys.filter { it in lib }.associateWith { it } // names R8 kept
        val started = System.nanoTime()
        val r = LibraryMatcher.match(apk, lib, known)
        val ms = (System.nanoTime() - started) / 1_000_000
        val correct = r.classes.count { truth[it.obfuscated] == it.original }
        val wrong = r.classes.filter { truth[it.obfuscated] != it.original }
        var methodsCorrect = 0
        var methodsWrong = 0
        var methodsUnverified = 0
        val wrongMethods = mutableListOf<String>()
        for ((owner, names) in r.methods) for ((method, name) in names) {
            val expected = groundTruth.methods[owner]?.get(method)?.singleOrNull()
            when {
                expected == null -> methodsUnverified++
                name == expected -> methodsCorrect++
                else -> { methodsWrong++; wrongMethods += "$owner->$method -> $name (truth $expected)" }
            }
        }
        fun fraction(numerator: Int, denominator: Int) = numerator.toDouble() / denominator.coerceAtLeast(1)
        fun decimal(value: Double) = "%.3f".format(Locale.ROOT, value)
        val sb = StringBuilder()
        sb.append("apk classes ${apk.size}, library classes ${lib.size}, unambiguous renamed library classes in apk ${renamedLib.size}, merged classes $merged, kept names ${known.size}\n")
        sb.append("matched ${r.classes.size} in ${r.rounds} rounds, ${ms}ms: correct $correct, wrong ${wrong.size}; precision ${decimal(fraction(correct, r.classes.size))}, recall ${decimal(fraction(correct, renamedLib.size))}\n")
        wrong.take(20).forEach { sb.append("  WRONG ${it.obfuscated} -> ${it.original} (origins ${origins[it.obfuscated]}) by ${it.by} score ${"%.2f".format(it.score)} margin ${"%.2f".format(it.margin)}\n") }
        for (t in listOf(0.75, 0.8, 0.85, 0.9)) for (mg in listOf(0.15, 0.25, 0.35)) {
            val kept = r.classes.filter { it.by != "features" || (it.score >= t && it.margin >= mg) }
            val c = kept.count { truth[it.obfuscated] == it.original }
            sb.append("  threshold $t margin $mg: kept ${kept.size} correct $c precision ${"%.3f".format(c.toDouble() / kept.size.coerceAtLeast(1))}\n")
        }
        sb.append("  by structure: ${r.classes.count { it.by == "structure" }} (correct ${r.classes.count { it.by == "structure" && truth[it.obfuscated] == it.original }})\n")
        val missed = renamedLib.filter { o -> r.classes.none { it.obfuscated == o } }
        sb.append("missed ${missed.size}, e.g. ${missed.take(15).map { "$it=${truth[it]}" }}\n")
        val final = (known + r.classes.associate { it.obfuscated to it.original })
        val ranks = LibraryMatcher.rank(apk.filterKeys { it in missed.take(12) }, lib, final)
        for (o in missed.take(12)) {
            val p = apk.getValue(o)
            sb.append("  MISSED $o truth=${truth[o]} strings=${p.strings.take(3)} methods=${p.methods.size} fields=${p.fields.size} ranks=${ranks[o]?.take(3)?.map { "${it.first.substringAfterLast('/')}=${"%.2f".format(it.second)}" } ?: "below evidence threshold"}\n")
        }
        val methodsTotal = r.methods.values.sumOf { it.size }
        sb.append("member maps: methods $methodsTotal, fields ${r.fields.values.sumOf { it.size }}\n")
        sb.append("method names: correct $methodsCorrect, wrong $methodsWrong, unevaluable $methodsUnverified; precision among evaluable ${decimal(fraction(methodsCorrect, methodsCorrect + methodsWrong))}\n")
        wrongMethods.take(20).forEach { sb.append("  WRONG METHOD $it\n") }
        Files.writeString(root.resolve("eval.txt"), sb)
        Files.writeString(root.resolve("eval.json"), buildJsonObject {
            put("fixture", root.fileName.toString())
            put("apkClasses", apk.size)
            put("referenceClasses", lib.size)
            put("renamedLibraryClasses", renamedLib.size)
            put("mergedClassHeaders", merged)
            put("classNamesCorrect", correct)
            put("classNamesWrong", wrong.size)
            put("classPrecision", fraction(correct, r.classes.size))
            put("classRecall", fraction(correct, renamedLib.size))
            put("methodNamesCorrect", methodsCorrect)
            put("methodNamesWrong", methodsWrong)
            put("methodNamesUnevaluable", methodsUnverified)
            put("methodPrecisionAmongEvaluable", fraction(methodsCorrect, methodsCorrect + methodsWrong))
            put("rounds", r.rounds)
            put("elapsedMs", ms)
        }.toString() + "\n")
        assertTrue(correct > 0, "the matcher recovered no correct classes; see $root/eval.txt")
        if (System.getenv("APKPROJECT_LIBMATCH_ENFORCE") == "1") {
            assertTrue(fraction(correct, r.classes.size) >= 0.98, "class precision below suite gate; see $root/eval.txt")
            assertTrue(fraction(correct, renamedLib.size) >= 0.5, "class recall below suite gate; see $root/eval.txt")
            assertTrue(fraction(methodsCorrect, methodsCorrect + methodsWrong) >= 0.99, "method precision below suite gate; see $root/eval.txt")
            assertTrue(fraction(methodsCorrect + methodsWrong, methodsTotal) >= 0.8, "too few method maps have ground truth; see $root/eval.txt")
        }
    }
}
