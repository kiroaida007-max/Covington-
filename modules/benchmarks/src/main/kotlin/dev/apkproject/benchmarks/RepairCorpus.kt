package dev.apkproject.benchmarks

import dev.apkproject.app.JadxRenames
import dev.apkproject.app.MethodRoundTrip
import dev.apkproject.app.RepairCandidate
import dev.apkproject.app.RepairLearning
import dev.apkproject.app.RepairLearningExample
import dev.apkproject.app.RepairRules
import dev.apkproject.app.RoundTripVerifier
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

@Serializable
private data class CorpusRequest(val schemaVersion: Int = 1, val profile: String, val dex: String,
    val sources: String, val libraries: List<String>, val out: String, val maxTrialsPerFile: Int = 12,
    val model: String? = null, val sourceUnits: Map<String, String> = emptyMap(), val maxCompilerPasses: Int = 8)

@Serializable
private data class CorpusSummary(val files: Int, val baselineCompiledFiles: Int, val baselineMethods: Int,
    val baselineEquivalent: Int, val baselineUnresolved: Int, val proposals: Int, val attempted: Int,
    val compileFailures: Int, val incompleteComparisons: Int, val positiveLabels: Int, val negativeLabels: Int,
    val queriesWithPositive: Int, val fixedMethods: Int, val firstSuccessfulTrialSum: Int, val elapsedMs: Long,
    val inputSha256: String, val profileHash: String, val modelSha256: String?,
    val excludedOversizedFiles: Int = 0,
    val scope: String = "Independent trials of bounded deterministic repair proposals on real decompiler output. Labels require complete round-trip comparisons; source-project tests and whole-app equivalence are not established.")

/** Offline laboratory only: original source never enters candidate compilation. */
object RepairCorpus {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size == 2 && args[0] == "--fingerprint-files") {
            val request = Files.newInputStream(Path.of(args[1])).use { it.readNBytes(65_537) }
            require(request.size <= 65_536)
            val paths = Json.decode<List<String>>(request.toString(Charsets.UTF_8))
            require(paths.size in 1..128)
            println(Json.encode(paths.associateWith { p ->
                val source = Files.newInputStream(Path.of(p)).use { it.readNBytes(1_048_577) }
                require(source.size <= 1_048_576)
                RepairLearning.sourceFingerprint(source.toString(Charsets.UTF_8))
            }))
            return
        }
        require(args.size == 1) { "Usage: RepairCorpus REQUEST.json" }
        val bytes = Files.newInputStream(Path.of(args[0])).use { it.readNBytes(65_537) }
        require(bytes.size <= 65_536)
        val q = Json.decode<CorpusRequest>(bytes.toString(Charsets.UTF_8))
        require(q.schemaVersion == 1 && q.maxTrialsPerFile in 1..48 && q.libraries.size in 1..32)
        require(q.maxCompilerPasses in 1..16)
        require(q.sourceUnits.size <= 256 && q.sourceUnits.values.all { it.matches(Regex("[a-f0-9]{64}")) })
        val profile = Profiles.load(Path.of(q.profile))
        val sourceRoot = Path.of(q.sources).toAbsolutePath().normalize()
        val out = Path.of(q.out).toAbsolutePath().normalize()
        require(!Files.exists(out)) { "Corpus output must be new" }
        Files.createDirectories(out)
        val started = System.nanoTime()
        val libraries = q.libraries.map { Path.of(it).toAbsolutePath() }
        require(libraries.all { Files.isRegularFile(it) })
        val dexBytes = Files.newInputStream(Path.of(q.dex)).use { it.readNBytes(64 * 1024 * 1024 + 1) }
        require(dexBytes.size <= 64 * 1024 * 1024)
        val dex = (DexFile.parse(dexBytes) as? Outcome.Ok)?.value ?: error("Invalid original DEX")
        val allFiles = Files.walk(sourceRoot).use { s -> s.filter { it.isRegularFile() && it.toString().endsWith(".java") }.sorted().limit(257).toList() }
        require(allFiles.size in 1..256 && allFiles.sumOf { Files.size(it) } <= 64L * 1024 * 1024) { "source corpus exceeds file/total-byte bounds" }
        val files = allFiles.filter { Files.size(it) <= 1_048_576 }
        require(files.isNotEmpty()) { "no source files within the 1 MiB per-file bound" }
        val relative = files.map { sourceRoot.relativize(it).toString().replace('\\', '/') }
        val verifier = RoundTripVerifier(profile)
        verifier.renames = JadxRenames.parse(sourceRoot)
        val original = verifier.originalByClass(listOf("original.dex" to dex), relative.toSet())
        fun compile(candidates: List<RepairCandidate>, dir: Path): Pair<Path, List<RepairCandidate>> {
            var remaining = candidates
            repeat(q.maxCompilerPasses) { attempt ->
                val src = dir.resolve("src-$attempt"); val classes = dir.resolve("classes-$attempt")
                Files.createDirectories(classes)
                val sources = remaining.map { c -> src.resolve(c.file).also { Files.createDirectories(it.parent); Files.writeString(it, c.text) } }
                if (sources.isEmpty()) return classes to emptyList()
                val list = dir.resolve("javac-$attempt.txt")
                Files.write(list, sources.map { "\"${it.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"")}\"" })
                val command = listOf(profile.javac.toString(), "-J-Xmx1g", "-source", "8", "-target", "8",
                    "-bootclasspath", profile.androidJar.toString(), "-cp", libraries.joinToString(File.pathSeparator),
                    "-g", "-proc:none", "-nowarn", "-encoding", "UTF-8", "-Xmaxerrs", "10000", "-d", classes.toString(), "@$list")
                val result = ProcessWorker.run(command, dir, 120_000, maxLogBytes = 1 shl 20)
                Files.writeString(dir.resolve("javac-$attempt.log"), result.stdout + result.stderr)
                if (result.ok && !result.truncated) return classes to remaining
                if (result.timedOut || result.truncated) return classes to emptyList()
                val bad = Regex("(?m)^(.+?\\.java):[0-9]+: error:").findAll(result.stdout + result.stderr)
                    .mapNotNull { runCatching { src.toAbsolutePath().relativize(Path.of(it.groupValues[1]).toAbsolutePath()).toString().replace('\\', '/') }.getOrNull() }.toSet()
                if (bad.isEmpty()) return classes to emptyList()
                val kept = remaining.filterNot { it.file in bad }
                if (kept.size == remaining.size) return classes to emptyList()
                remaining = kept
            }
            return dir to emptyList()
        }
        fun compare(classes: Path, candidates: List<RepairCandidate>, dir: Path): List<MethodRoundTrip> {
            if (candidates.isEmpty()) return emptyList()
            val classFiles = Files.walk(classes).use { s -> s.filter { it.isRegularFile() && it.toString().endsWith(".class") }.toList() }
            if (classFiles.isEmpty()) return emptyList()
            val selected = candidates.map { it.file }.toSet()
            return verifier.compare(original.filterKeys { verifier.fileOf(it) in selected },
                verifier.recompile(classes, classFiles, libraries, 26, dir, 120_000), emptyMap())
        }
        val baselineCandidates = relative.map { file -> RepairCandidate(file, "baseline", 0, "", "", Files.readString(sourceRoot.resolve(file))) }
        val (baselineClasses, compiled) = compile(baselineCandidates, out.resolve("baseline"))
        val baseline = compare(baselineClasses, compiled, out.resolve("baseline/dex"))
        Files.writeString(out.resolve("baseline.json"), Json.encode(baseline))
        val byFile = baseline.groupBy { it.file }
        val model = RepairLearning.load(q.model?.let(Path::of)) { println(it) }
        val proposals = compiled.associate { c ->
            val keys = byFile[c.file].orEmpty().filter { it.result == "DIFFERENT" || it.result == "SIMILAR_OPERATIONS" }.map { it.method }
            c.file to RepairLearning.rank(RepairRules.candidates(c.file, c.text, keys).take(q.maxTrialsPerFile), model)
        }
        var attempted = 0; var compileFailures = 0; var incomplete = 0; var positives = 0; var negatives = 0
        val fixedByFile = linkedMapOf<String, Set<String>>(); val firstByFile = linkedMapOf<String, Int>()
        val trials = arrayListOf<Map<String, String>>()
        val inputGroup = Hashing.combine(dex.sha256)
        repeat(proposals.values.maxOfOrNull { it.size } ?: 0) { index ->
            val batch = proposals.values.mapNotNull { it.getOrNull(index) }
            attempted += batch.size
            val dir = out.resolve("trial-${index + 1}")
            val (classes, accepted) = compile(batch, dir)
            compileFailures += batch.size - accepted.size
            val comparisons = compare(classes, accepted, dir.resolve("dex")).groupBy { it.file }
            for (c in accepted) {
                val previous = byFile[c.file].orEmpty().filter { it.result != "SYNTHETIC" }.associateBy { it.method }
                val now = comparisons[c.file].orEmpty().filter { it.result != "SYNTHETIC" }.associateBy { it.method }
                val checked = RepairLearning.checkedLabel(previous.values.toList(), now.values.toList())
                if (checked == null) { incomplete++; continue }
                val fixed = now.filter { (key, value) -> value.result in RoundTripVerifier.EQUIVALENT && previous.getValue(key).result !in RoundTripVerifier.EQUIVALENT }.keys
                val label = checked.label
                if (label == 1) {
                    positives++; fixedByFile[c.file] = fixedByFile[c.file].orEmpty() + fixed
                    firstByFile.putIfAbsent(c.file, index + 1)
                } else negatives++
                val example = RepairLearningExample(inputGroup = inputGroup, querySha256 = Hashing.sha256(Files.readString(sourceRoot.resolve(c.file))),
                    candidateSha256 = Hashing.sha256(c.text), profileHash = profile.profileHash, rule = c.rule, features = RepairLearning.features(c),
                    label = label, improvedMethods = fixed.size, comparedMethods = previous.size, unitSha256 = q.sourceUnits[c.file],
                    queryCodeSha256 = RepairLearning.sourceFingerprint(Files.readString(sourceRoot.resolve(c.file))),
                    candidateCodeSha256 = RepairLearning.sourceFingerprint(c.text))
                RepairLearning.append(out.resolve("feedback.jsonl"), example)
                trials += mapOf("query" to example.querySha256, "candidate" to example.candidateSha256, "rule" to c.rule, "label" to label.toString(), "order" to (index + 1).toString())
            }
            println("trial ${index + 1}: $positives positive, $negatives negative, $incomplete incomplete")
        }
        // No invented rows when all files compile cleanly or all comparisons are incomplete.
        if (!Files.exists(out.resolve("feedback.jsonl"))) Files.createFile(out.resolve("feedback.jsonl"))
        Files.writeString(out.resolve("trials.json"), Json.encode(trials))
        val summary = CorpusSummary(relative.size, compiled.size, baseline.size, baseline.count { it.result in RoundTripVerifier.EQUIVALENT },
            baseline.count { it.result == "NOT_FOUND" || it.result == "NOT_COMPILED" }, proposals.values.sumOf { it.size }, attempted,
            compileFailures, incomplete, positives, negatives, firstByFile.size, fixedByFile.values.sumOf { it.size }, firstByFile.values.sum(),
            (System.nanoTime() - started) / 1_000_000, dex.sha256, profile.profileHash, q.model?.let { Hashing.sha256(Path.of(it)) },
            excludedOversizedFiles = allFiles.size - files.size)
        Files.writeString(out.resolve("summary.json"), Json.encode(summary))
        println(Json.encode(summary))
    }
}
