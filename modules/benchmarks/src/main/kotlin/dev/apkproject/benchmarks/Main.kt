package dev.apkproject.benchmarks

import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Profiles
import dev.apkproject.store.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Usage: benchmarks [REPO_ROOT] [--out RESULTS_DIR]
 * Runs every fixture under fixtures/ and writes JSON and Markdown results.
 */
fun main(args: Array<String>) {
    val root = Paths.get(args.firstOrNull { !it.startsWith("--") } ?: ".").toAbsolutePath().normalize()
    val outIndex = args.indexOf("--out")
    val results = if (outIndex >= 0) Paths.get(args[outIndex + 1]) else root.resolve("benchmarks/results")
    val profile = Profiles.load(root.resolve("profiles/v0-jvm-pure.json"))
    val bench = Benchmark(profile, root.resolve("benchmarks/work")) { println(it) }
    val fixtures = Files.list(root.resolve("fixtures")).use { s -> s.filter { Files.isRegularFile(it.resolve("fixture.json")) }.sorted().toList() }
    val all = fixtures.map { bench.run(it) }
    Files.createDirectories(results)
    AtomicFiles.writeString(results.resolve("v0-results.json"), Json.encode(all))
    AtomicFiles.writeString(results.resolve("v0-results.md"), markdown(all, profile.profile.id))
    println(markdown(all, profile.profile.id))
    val ok = all.all { it.independentMismatches == 0 && it.seededMutants == it.seededMutantsRejected }
    exitProcess(if (ok) 0 else 1)
}

fun markdown(all: List<FixtureResult>, profileId: String): String = buildString {
    append("# V0 method laboratory: measured results\n\n")
    append("Generated ${LocalDate.now()} with profile `$profileId`. These numbers come from the fixtures in `fixtures/` only;\n")
    append("they are not a claim about arbitrary APKs.\n\n")
    append("| Fixture | Job state | Accepted / in scope | Recompiles to identical bytecode | Independent checks (mismatches) | Seeded wrong candidates rejected |\n")
    append("|---|---|---|---|---|---|\n")
    for (r in all) {
        append("| ${r.fixture} | ${r.state} (exit ${r.exitCode}) | ${r.methodsAccepted} / ${r.methodsInScope} | ${r.identicalBytecode} | ${r.independentChecks} (${r.independentMismatches}) | ${r.seededMutantsRejected} / ${r.seededMutants} |\n")
    }
    for (r in all) {
        append("\n## ${r.fixture}\n\nInput APK sha256 `${r.apkSha256}` (rebuilt from hidden source; byte-reproducible).\n\n| Method | Status | Evidence | Bytecode match | Independent run | Gap |\n|---|---|---|---|---|---|\n")
        for (m in r.methods) {
            append("| `${m.method.substringAfter("->")}` | ${m.status} | ${m.evidence.joinToString(", ")} | ${m.structuralMatch ?: "-"} | ${m.independentAgreement ?: "-"} | ${m.gap ?: ""} |\n")
        }
    }
}
