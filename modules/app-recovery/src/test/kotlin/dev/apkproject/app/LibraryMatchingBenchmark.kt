package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

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
        fun dex(p: java.nio.file.Path) = Files.list(p).use { s -> s.filter { it.toString().endsWith(".dex") }.toList() }.map { (DexFile.parse(Files.readAllBytes(it)) as Outcome.Ok).value }
        val apk = LibraryMatcher.profiles(dex(root.resolve("out")))
        val lib = LibraryMatcher.profiles(dex(root.resolve("libdex")))
        // Ground truth: R8's mapping (original -> obfuscated).
        val truth = HashMap<String, String>()
        for (line in Files.readAllLines(root.resolve("mapping.txt"))) {
            val m = Regex("""^(\S+) -> (\S+):$""").find(line) ?: continue
            truth["L" + m.groupValues[2].replace('.', '/') + ";"] = "L" + m.groupValues[1].replace('.', '/') + ";"
        }
        val renamedLib = apk.keys.filter { truth[it] != null && truth[it] != it && lib.containsKey(truth[it]) }
        val known = apk.keys.filter { it in lib }.associateWith { it } // names R8 kept
        val started = System.nanoTime()
        val r = LibraryMatcher.match(apk, lib, known)
        val ms = (System.nanoTime() - started) / 1_000_000
        val correct = r.classes.count { truth[it.obfuscated] == it.original }
        val wrong = r.classes.filter { truth[it.obfuscated] != it.original }
        val sb = StringBuilder()
        sb.append("apk classes ${apk.size}, library classes ${lib.size}, renamed library classes in apk ${renamedLib.size}, kept names ${known.size}\n")
        sb.append("matched ${r.classes.size} in ${r.rounds} rounds, ${ms}ms: correct $correct, wrong ${wrong.size}; precision ${"%.3f".format(correct.toDouble() / r.classes.size.coerceAtLeast(1))}, recall ${"%.3f".format(correct.toDouble() / renamedLib.size.coerceAtLeast(1))}\n")
        wrong.take(20).forEach { sb.append("  WRONG ${it.obfuscated} -> ${it.original} (truth ${truth[it.obfuscated]}) by ${it.by} score ${"%.2f".format(it.score)} margin ${"%.2f".format(it.margin)}\n") }
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
        Files.writeString(root.resolve("eval.txt"), sb)
    }
}
