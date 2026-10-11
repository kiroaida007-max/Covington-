package dev.apkproject.cli

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ProfileException
import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.RecoveryPolicy
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.Disassembler
import dev.apkproject.dexasm.AsmException
import dev.apkproject.dexasm.Assembler
import dev.apkproject.orchestrator.Doctor
import dev.apkproject.orchestrator.JobOptions
import dev.apkproject.orchestrator.Operations
import dev.apkproject.orchestrator.RecoveryJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val USAGE = """apkproject - APK to editable project (V0 method laboratory)

Usage:
  apkproject decompile INPUT --out DIR [--profile profiles/android-full.json] [--deobfuscate]
                      [--no-cross-check] [--no-compile-check] [--no-rea] [--no-js] [--no-repair] [--no-library-names] [--no-osv] [--max-checked-methods N]
                      [--job-dir DIR] [--memory-mb N] [--cpu-threads N] [--max-work-mb N] [--require-os-containment]
                      Whole app: UI/resources, decompiled code (machine-checked where possible), web/JS,
                      native boundaries, backend contract, privacy map and SBOM. INPUT: .apk, .apks, .xapk, .apkm, .aab or .dex.
  apkproject contract INPUT --out DIR [--reference-types MANIFEST --reference-source DIR]
                      Backend contract only, straight from the bytecode (no decompiler): Retrofit endpoints
                      (obfuscated or not), JSON models, URLs; writes api-contract.json and openapi.yaml.
                      Optional reference types add names from hash-checked Kotlin source, with separate provenance.
  apkproject audit INPUT --out DIR [--profile profiles/android-full.json]
                      Front end + security review: UI toolkit and language, screens, static UI-quality
                      and security findings (manifest, WebView, secrets, layouts) with a risk score.
  apkproject screens INPUT --out DIR
                      Screen -> code -> server and database: for every screen, service, receiver and
                      worker, the endpoints, servers, SQL tables and stored settings its code reaches,
                      with the call chain to each.
  apkproject privacy INPUT --out DIR
                      Privacy map: the personal data the code reads (location, device IDs, contacts, ...)
                      and where it can go (endpoints, third-party SDKs, logs, storage, other apps), with
                      the call chain of each flow and a Play Data safety summary.
  apkproject sbom INPUT --out DIR [--offline] [--fail-on critical|high|medium|low] [--cache DIR]
                      Software bill of materials (CycloneDX 1.5): libraries with the version files and
                      version strings that name them, native files with hashes, and their known
                      vulnerabilities from OSV.dev with the fix for the installed version's line.
  apkproject diff OLD NEW --out DIR
                      Update review: what changed between two builds (signer, permissions, exported
                      components, hosts, endpoints, SDKs, capabilities, native code, risk) and which
                      code changed, with classes matched across obfuscator renames.
  apkproject setup [--tools DIR]
                      Downloads and verifies the pinned tools (JADX, ECJ, D8, Z3, android.jar) on Linux, macOS or Windows.
  apkproject names INPUT --out FILE
                      Original names compilers left in the bytecode (Kotlin data classes, Moshi adapters,
                      ViewBinding, Room, enums), with the evidence for each, as JSON.
  apkproject roundtrip INPUT PROJECT_DIR [--out FILE] [--profile profiles/android-full.json]
                      Recompile a recovered (or edited) project and compare every method with INPUT's bytecode.
  apkproject doctor   [--profile FILE]
  apkproject inspect  INPUT --out DIR
  apkproject disasm   INPUT [--out FILE] [--offsets]
  apkproject asm      INPUT.dasm --out FILE.dex
  apkproject recover  INPUT --out DIR [--policy full-source|partial-report] [--profile FILE]
                      [--no-experiments] [--max-candidates N] [--max-probes N] [--seed-wrong-candidates]
  apkproject validate RUN_DIR [--suite FILE] [--profile FILE]
  apkproject export   RUN_DIR --out DIR [--allow-partial] [--profile FILE]
  apkproject resume   RUN_DIR [--profile FILE]
  apkproject resume-full JOB_DIR
                      Resume an interrupted full recovery after checking its input and checkpoints.
  apkproject cancel-full JOB_DIR
  apkproject cancel   JOB_ID|RUN_DIR

INPUT is an .apk or a bare .dex file. Exit codes: 0 policy satisfied, 2 invalid configuration,
3 malformed input, 4 unsupported or partial recovery, 5 build or verification failure,
6 worker or resource failure, 130 cancelled."""

class Args(raw: List<String>) {
    val positional = ArrayList<String>()
    private val options = HashMap<String, String>()
    private val flags = HashSet<String>()

    init {
        var i = 0
        val valued = setOf("--out", "--policy", "--profile", "--suite", "--max-candidates", "--max-probes", "--max-checked-methods", "--threads", "--tools", "--job-dir", "--memory-mb", "--cpu-threads", "--max-work-mb", "--reference-types", "--reference-source", "--cache", "--osv-url", "--fail-on")
        while (i < raw.size) {
            val a = raw[i]
            when {
                a in valued -> {
                    if (i + 1 >= raw.size) throw UsageError("$a needs a value")
                    options[a] = raw[i + 1]; i += 2
                }
                a.startsWith("--") -> {
                    flags += a; i++
                }
                else -> {
                    positional += a; i++
                }
            }
        }
    }

    fun option(name: String): String? = options[name]
    fun flag(name: String): Boolean = name in flags
    fun require(name: String): String = options[name] ?: throw UsageError("missing $name")
    fun path(index: Int, what: String): Path = Paths.get(positional.getOrNull(index) ?: throw UsageError("missing $what"))
}

class UsageError(message: String) : Exception(message)

fun main(argv: Array<String>) {
    exitProcess(run(argv.toList()))
}

fun run(argv: List<String>, out: (String) -> Unit = ::println, err: (String) -> Unit = { System.err.println(it) }): Int {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) {
        out(USAGE)
        return if (argv.isEmpty()) ExitCode.INVALID_CONFIGURATION.code else 0
    }
    val command = argv[0]
    return try {
        val args = Args(argv.drop(1))
        when (command) {
            "doctor" -> doctor(args, out)
            "decompile" -> decompile(args, out, err)
            "roundtrip" -> roundtrip(args, out)
            "contract" -> contract(args, out, err)
            "audit" -> audit(args, out, err)
            "diff" -> diff(args, out, err)
            "screens" -> screens(args, out, err)
            "sbom" -> sbom(args, out, err)
            "privacy" -> privacy(args, out, err)
            "names" -> names(args, out, err)
            "setup" -> setup(args, out, err)
            "inspect" -> report(Operations.inspect(args.path(0, "INPUT"), Paths.get(args.require("--out"))).let { it.exitCode to it.message }, out, err)
            "disasm" -> disasm(args, out, err)
            "asm" -> asm(args, out, err)
            "recover" -> recover(args, out)
            "validate" -> report(Operations.validate(profile(args), args.path(0, "RUN_DIR"), args.option("--suite")?.let(Paths::get), out).let { it.exitCode to it.message }, out, err)
            "export" -> report(Operations.export(profile(args), args.path(0, "RUN_DIR"), Paths.get(args.require("--out")), args.flag("--allow-partial")).let { it.exitCode to it.message }, out, err)
            "resume" -> {
                val r = Operations.resume(profile(args), args.path(0, "RUN_DIR"), out) ?: return ExitCode.INVALID_CONFIGURATION.code.also { err("no job state in ${args.positional[0]}") }
                out("${r.state}: ${r.summary}")
                r.exitCode.code
            }
            "resume-full" -> dev.apkproject.app.FullRecoverySupervisor.resume(args.path(0, "JOB_DIR"), log = out).let { report(it.exitCode to it.message, out, err) }
            "cancel-full" -> {
                val files = dev.apkproject.app.FullJobFiles(args.path(0, "JOB_DIR"))
                require(Files.isRegularFile(files.request)) { "no full recovery request in ${files.dir}" }
                dev.apkproject.store.AtomicFiles.writeString(files.cancel, "cancel\n")
                out("cancellation requested")
                0
            }
            "cancel" -> report(Operations.cancel(args.positional.getOrNull(0) ?: throw UsageError("missing JOB_ID")).let { it.exitCode to it.message }, out, err)
            else -> throw UsageError("unknown command '$command'")
        }
    } catch (e: UsageError) {
        err("error: ${e.message}\n\n$USAGE")
        ExitCode.INVALID_CONFIGURATION.code
    } catch (e: ProfileException) {
        err("profile error: ${e.message}")
        ExitCode.INVALID_CONFIGURATION.code
    } catch (e: java.nio.file.NoSuchFileException) {
        err("file not found: ${e.file}")
        ExitCode.INVALID_CONFIGURATION.code
    } catch (e: IllegalArgumentException) {
        err(e.message ?: "invalid configuration")
        ExitCode.INVALID_CONFIGURATION.code
    } catch (e: dev.apkproject.contracts.FormatException) {
        err(e.failure.toString())
        if (e.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL.code else ExitCode.MALFORMED_INPUT.code
    } catch (e: ArithmeticException) {
        err("configuration number exceeds its supported range")
        ExitCode.INVALID_CONFIGURATION.code
    }
}

private fun report(result: Pair<ExitCode, String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    if (result.first == ExitCode.EXPORT_POLICY_SATISFIED) out(result.second) else err(result.second)
    return result.first.code
}

private fun profile(args: Args): ResolvedProfile {
    val path = args.option("--profile") ?: System.getenv("APKPROJECT_PROFILE") ?: "profiles/v0-jvm-pure.json"
    return Profiles.load(Paths.get(path))
}

private fun doctor(args: Args, out: (String) -> Unit): Int {
    val p = profile(args)
    val work = Files.createTempDirectory("apkproject-doctor")
    val checks = Doctor.run(p, work)
    out("profile ${p.profile.id} (sha256 ${p.profileHash.take(12)})")
    for (c in checks) out("  %-14s %-5s %s".format(c.name, c.status, c.detail))
    val ok = checks.none { it.status == CheckStatus.FAIL }
    out(if (ok) "toolchain ready" else "toolchain not ready; run scripts/setup-toolchain.sh")
    return if (ok) 0 else ExitCode.INVALID_CONFIGURATION.code
}

private fun decompile(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val outDir = Paths.get(args.require("--out"))
    val profilePath = args.option("--profile") ?: System.getenv("APKPROJECT_PROFILE") ?: "profiles/android-full.json"
    val profile = Profiles.load(Paths.get(profilePath))
    val defaults = dev.apkproject.app.FullRecoveryOptions()
    val options = defaults.copy(
        deobfuscate = args.flag("--deobfuscate"),
        crossCheck = !args.flag("--no-cross-check"),
        compileCheck = !args.flag("--no-compile-check"),
        rea = !args.flag("--no-rea"),
        javaScript = !args.flag("--no-js"),
        repair = !args.flag("--no-repair"),
        nameLibraries = !args.flag("--no-library-names"),
        vulnerabilities = !args.flag("--no-osv"),
        maxCrossCheckMethods = args.option("--max-checked-methods")?.toIntOrNull() ?: defaults.maxCrossCheckMethods,
        threads = args.option("--threads")?.toInt() ?: defaults.threads,
        resources = defaults.resources.copy(requireOsContainment = args.flag("--require-os-containment"), memoryMb = args.option("--memory-mb")?.toInt() ?: 0,
            cpuThreads = args.option("--cpu-threads")?.toInt() ?: 0,
            maxWorkBytes = args.option("--max-work-mb")?.toLong()?.let { Math.multiplyExact(it, 1024L * 1024) } ?: defaults.resources.maxWorkBytes),
    )
    val token = CancellationToken()
    val done = CountDownLatch(1)
    val hook = Thread {
        token.cancel()
        done.await(10, TimeUnit.SECONDS)
    }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        val jobDir = args.option("--job-dir")?.let(Paths::get) ?: outDir.toAbsolutePath().resolveSibling(".${outDir.fileName}.job")
        out("job: $jobDir (resume with apkproject resume-full)")
        val result = dev.apkproject.app.FullRecoverySupervisor.run(profile, options, input, outDir, jobDir, token, out)
        if (result.outDir != null) {
            out(result.message)
            out("project: ${result.outDir}  (start with README.md and recovery/RECOVERY.md)")
        } else {
            err(result.message)
        }
        return result.exitCode.code
    } finally {
        done.countDown()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private fun contract(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val references = args.option("--reference-types")
    val referenceSource = args.option("--reference-source")
    require((references == null) == (referenceSource == null)) { "--reference-types and --reference-source must be supplied together" }
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    app.use {
        val dex = app.dexFiles()
        val extracted = dev.apkproject.app.ApiContractExtractor.extract(dir.resolve("none"), null, null, dex, emptyList())
        val c = if (references == null) extracted else dev.apkproject.app.ReferenceTypes.apply(extracted, app.sha256, Paths.get(references), Paths.get(referenceSource!!))
        Files.writeString(dir.resolve("api-contract.json"), dev.apkproject.contracts.Json.encode(c))
        val audit = dev.apkproject.app.ContractAuditor.audit(c)
        Files.writeString(dir.resolve("contract-audit.json"), dev.apkproject.contracts.Json.encode(audit))
        Files.writeString(dir.resolve("openapi.yaml"), dev.apkproject.app.ContractRenderer.openApi(c, input.fileName.toString(), "unknown"))
        // The same runnable mock server `decompile` writes (SYNTHESIZED): java server-skeleton/BackendSkeleton.java
        Files.writeString(Files.createDirectories(dir.resolve("server-skeleton")).resolve("BackendSkeleton.java"), dev.apkproject.app.ContractRenderer.serverSkeleton(c))
        Files.writeString(dir.resolve("README.md"), dev.apkproject.app.ContractRenderer.readme(c))
        out("${c.endpoints.size} endpoints, ${c.models.size} models, ${c.hosts.size} hosts -> $dir")
        out("${audit.checkedExamples} structurally checked examples, ${audit.checkedClientSelections} checked client selections, ${audit.withheldExamples} withheld by default, ${audit.checkedResponseVariants} selectable checked variants, ${audit.issues.size} contract issues; see contract-audit.json")
        val attributed = c.models.sumOf { m -> m.fields.count { it.referenceType != null } }
        if (attributed > 0) out("$attributed type names attributed to reference source; APK type identities remain separate")
        val embedded = dev.apkproject.app.EmbeddedBackend.scan(dex, app.sha256)
        dev.apkproject.app.EmbeddedBackend.write(dir.resolve("embedded"), embedded)
        out("${embedded.handlers.size} embedded handler declarations; use decompile to export/check their implementation (embedded/report.json)")
        return 0
    }
}

/** Static front-end + security review: UI toolkit, screens, findings and a risk score. */
private fun audit(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val profile = profile(args)
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    app.use {
        val dex = app.dexFiles()
        val anatomy = dev.apkproject.app.AnatomyDetector.detect(app, dex)
        // Decode resources and the manifest only (no Java): fast, and all the UI/manifest checks need.
        val decoded = Files.createDirectories(dir.resolve("decoded"))
        val run = dev.apkproject.app.JadxDecompiler(profile, dev.apkproject.compiler.CancellationToken()).export(input, decoded, false, 1, dir, noSource = true)
        val main = decoded.resolve("app/src/main")
        val manifestXml = main.resolve("AndroidManifest.xml").takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        if (manifestXml == null) err("warning: resource decoding was ${run.status.lowercase()}; manifest checks skipped")
        val report = dev.apkproject.app.FrontendAudit.analyze(main, manifestXml, dex, anatomy, app.allEntries().map { it.second.name })
        Files.writeString(dir.resolve("ui-audit.json"), dev.apkproject.contracts.Json.encode(report))
        Files.writeString(dir.resolve("README.md"), dev.apkproject.app.FrontendMarkdown.render(report))
        dev.apkproject.store.AtomicFiles.deleteRecursively(decoded)
        val r = report.risk
        out("${report.toolkit.name}; ${report.screens.count { it.kind.startsWith("ACTIVITY") }} activities; risk ${r.score}/100 (grade ${r.grade}): ${r.critical} critical, ${r.high} high, ${r.medium} medium, ${r.low} low -> $dir")
        return 0
    }
}

/** Screen -> code -> server and database map, from the bytecode and the manifest (no decompiler). */
private fun screens(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    app.use {
        val dex = app.dexFiles()
        val manifest = app.decodedManifest()
        if (manifest == null) err("warning: no binary AndroidManifest.xml; only workers are found as entry points")
        val r = dev.apkproject.app.ScreenMap.analyze(dex, manifest)
        Files.writeString(dir.resolve("screen-map.json"), dev.apkproject.contracts.Json.encode(r))
        Files.writeString(dir.resolve("README.md"), dev.apkproject.app.ScreenMapMarkdown.render(r))
        out("${r.screens.size} screens and components; ${r.endpoints.count { it.screens.isNotEmpty() }}/${r.endpoints.size} endpoints and ${r.tables.count { it.screens.isNotEmpty() }}/${r.tables.size} tables reached -> $dir")
        return 0
    }
}

/** Privacy map: personal data sources to sinks, from the bytecode and the manifest (no decompiler). */
private fun privacy(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    app.use {
        val manifest = app.decodedManifest()
        if (manifest == null) err("warning: no binary AndroidManifest.xml; permissions are unknown")
        val r = dev.apkproject.app.PrivacyMap.analyze(app.dexFiles(), manifest)
        Files.writeString(dir.resolve("privacy.json"), dev.apkproject.contracts.Json.encode(r))
        Files.writeString(dir.resolve("README.md"), dev.apkproject.app.PrivacyMarkdown.render(r))
        val byAttention = listOf("HIGH", "MEDIUM", "LOW").map { a -> a to r.flows.count { it.attention == a } }.filter { it.second > 0 }
        out("${dev.apkproject.app.PrivacyMap.kinds(r.sources.map { it.data }.distinct().size)} read; ${r.flows.size} ${if (r.flows.size == 1) "flow" else "flows"}" +
            (if (byAttention.isEmpty()) "" else " (" + byAttention.joinToString { "${it.second} ${it.first.lowercase()}" } + ")") +
            "; ${r.stats.methods} methods in ${r.stats.elapsedMs} ms${if (r.stats.truncated) " (stopped at the time budget)" else ""} -> $dir")
        return if (r.stats.truncated) ExitCode.UNSUPPORTED_OR_PARTIAL.code else 0
    }
}

/** SBOM and known vulnerabilities, from the package itself (no decompiler). */
private fun sbom(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val failOn = args.option("--fail-on")?.uppercase()?.let { if (it == "MODERATE") "MEDIUM" else it }
    if (failOn != null && failOn !in dev.apkproject.app.SupplyChain.SEVERITY_ORDER.dropLast(1)) throw UsageError("--fail-on takes critical, high, medium or low")
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    app.use {
        val osv = if (args.flag("--offline")) null else dev.apkproject.app.OsvClient(
            args.option("--osv-url") ?: "https://api.osv.dev",
            args.option("--cache")?.let(Paths::get) ?: dev.apkproject.app.OsvClient.defaultCache(),
        )
        val r = dev.apkproject.app.SupplyChain.analyze(app, app.dexFiles(), app.decodedManifest(), osv) { out(it) }
        Files.writeString(dir.resolve("sbom.cdx.json"), dev.apkproject.app.SupplyChain.cycloneDx(r))
        Files.writeString(dir.resolve("supply-chain.json"), dev.apkproject.contracts.Json.encode(r))
        Files.writeString(dir.resolve("README.md"), dev.apkproject.app.SupplyChainMarkdown.render(r))
        val libs = r.components.count { it.type == "library" }
        val files = r.components.count { it.type == "file" }
        val counts = dev.apkproject.app.SupplyChain.SEVERITY_ORDER.map { s -> s to r.advisories.count { it.severity == s } }.filter { it.second > 0 }
        out("$libs libraries, $files native files; ${if (osv == null) "vulnerabilities not looked up (offline)" else dev.apkproject.app.SupplyChain.vulnerabilities(r.advisories.size, r.risks.size) + (if (counts.isEmpty()) "" else " (" + counts.joinToString { "${it.second} ${it.first.lowercase()}" } + ")")} -> $dir")
        if (!r.lookupComplete && osv != null) {
            err("vulnerability lookup incomplete: ${r.lookup}")
            return ExitCode.UNSUPPORTED_OR_PARTIAL.code
        }
        if (failOn != null) {
            val limit = dev.apkproject.app.SupplyChain.SEVERITY_ORDER.indexOf(failOn)
            val hit = r.advisories.filter { dev.apkproject.app.SupplyChain.SEVERITY_ORDER.indexOf(it.severity) <= limit }
            if (hit.isNotEmpty()) {
                err("${hit.size} advisories at or above ${failOn.lowercase()}")
                return ExitCode.BUILD_OR_VERIFICATION_FAILURE.code
            }
        }
        return 0
    }
}

/** Update review of two builds: manifest, network, SDKs, capabilities, risk and code matched across renames. */
private fun diff(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val old = args.path(0, "OLD")
    val new = args.path(1, "NEW")
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val result = try {
        dev.apkproject.app.AppDiff.compare(old, new) { out(it) }
    } catch (e: java.nio.file.NoSuchFileException) {
        throw e
    } catch (e: Exception) {
        // Unreadable input (not an archive, a malformed DEX): say which, without a stack trace.
        err("cannot compare: ${e.message ?: e}")
        return ExitCode.MALFORMED_INPUT.code
    }
    val r = result.report
    Files.writeString(dir.resolve("diff.json"), dev.apkproject.contracts.Json.encode(r))
    Files.writeString(dir.resolve("README.md"), dev.apkproject.app.DiffMarkdown.render(r))
    Files.writeString(dir.resolve("code-map.tsv"), result.classMap)
    r.headline.forEach { out(it.replace("→", "->")) }
    out("report: ${dir.resolve("README.md")}")
    return 0
}

private fun setup(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val tools = Paths.get(args.option("--tools") ?: System.getenv("APKPROJECT_TOOLS") ?: ".tools").toAbsolutePath()
    val installer = dev.apkproject.orchestrator.ToolchainInstaller(tools)
    var last = ""
    val results = installer.install({ _, message, done, total ->
        val line = if (total != null && total > 1) "$message: ${done * 100 / total}%" else message
        if (line != last && (total == null || done == total || done % (8L shl 20) < (1 shl 20))) out(line)
        last = line
    })
    if (dev.apkproject.contracts.isWindows()) installer.completeWindowsZ3(Paths.get(System.getProperty("java.home")))
    for (r in results) (if (r.state == dev.apkproject.orchestrator.ToolchainInstaller.Status.State.INSTALLED) out else err)("${r.spec.title}: ${r.state.name.lowercase()} ${if (r.state.name == "INSTALLED") "" else r.detail}".trim())
    out("tools in $tools (profile profiles/${dev.apkproject.orchestrator.ToolchainInstaller.profileName()})")
    return if (results.any { it.state != dev.apkproject.orchestrator.ToolchainInstaller.Status.State.INSTALLED && !it.spec.optional }) ExitCode.WORKER_OR_RESOURCE_FAILURE.code else 0
}

private fun names(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val target = Paths.get(args.require("--out"))
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    app.use {
        val resources = app.parts.firstNotNullOfOrNull { it.read("resources.arsc") }?.let { dev.apkproject.app.ResourceTable.parse(it) } ?: dev.apkproject.app.ResourceTable.EMPTY
        val r = dev.apkproject.app.NameOracle.recover(app.dexFiles().map { it.second }, resources)
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(target, dev.apkproject.contracts.Json.encode(r.all))
        val byRule = r.all.groupingBy { it.rule }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
        out("${r.classes.size} classes, ${r.fields.values.sumOf { it.size }} fields, ${r.methods.values.sumOf { it.size }} methods named from compiler evidence ($byRule); ${r.conflicts.size} conflicts withheld -> $target")
        return 0
    }
}

private fun roundtrip(args: Args, out: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val project = args.path(1, "PROJECT_DIR")
    val profile = Profiles.load(Paths.get(args.option("--profile") ?: System.getenv("APKPROJECT_PROFILE") ?: "profiles/android-full.json"))
    val target = Paths.get(args.option("--out") ?: project.resolve("recovery/round-trip-recheck.json").toString())
    val s = dev.apkproject.app.ProjectVerifier(profile, CancellationToken()) { out(it) }.run(input, project, target)
    out("round trip: ${s.equivalent}/${s.methods} methods equivalent (${s.equivalentDataflow} identical dataflow, ${s.equivalentModuloLayout} modulo layout; app ${s.appEquivalent}/${s.appMethods}), ${s.similarOperations} same operations reordered, ${s.different} different, ${s.notCompiled} not compiled")
    out("per-method results: $target")
    return if (s.status == "MEASURED") 0 else ExitCode.UNSUPPORTED_OR_PARTIAL.code
}

private fun disasm(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val snapshot = dev.apkproject.archive.ApkSnapshot.take(input)
    val entries = when (snapshot) {
        is Outcome.Ok -> snapshot.value.dexFiles()
        is Outcome.Err -> return ExitCode.MALFORMED_INPUT.code.also { err(snapshot.failure.toString()) }
    }
    val dexes = when (entries) {
        is Outcome.Ok -> entries.value
        is Outcome.Err -> return ExitCode.MALFORMED_INPUT.code.also { err(entries.failure.toString()) }
    }
    val text = StringBuilder()
    for (e in dexes) {
        when (val d = DexFile.parse(e.bytes, e.ref.sha256)) {
            is Outcome.Ok -> text.append("; entry ").append(e.entry.name).append('\n').append(Disassembler.file(d.value, Disassembler.Options(includeByteOffsets = args.flag("--offsets"))))
            is Outcome.Err -> return (if (d.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(d.failure.toString()) }
        }
    }
    val target = args.option("--out")
    if (target != null) Files.writeString(Paths.get(target), text) else out(text.toString())
    return 0
}

private fun asm(args: Args, out: (String) -> Unit, err: (String) -> Unit): Int {
    val input = args.path(0, "INPUT.dasm")
    return try {
        val bytes = Assembler.assemble(Files.readString(input))
        Files.write(Paths.get(args.require("--out")), bytes)
        out("assembled ${bytes.size} bytes")
        0
    } catch (e: AsmException) {
        err("asm: ${e.message}")
        ExitCode.MALFORMED_INPUT.code
    }
}

private fun recover(args: Args, out: (String) -> Unit): Int {
    val input = args.path(0, "INPUT")
    val runDir = Paths.get(args.require("--out"))
    val policy = when (args.option("--policy") ?: "full-source") {
        "full-source" -> RecoveryPolicy.FULL_SOURCE
        "partial-report" -> RecoveryPolicy.PARTIAL_REPORT
        else -> throw UsageError("--policy must be full-source or partial-report")
    }
    val defaults = Budget()
    val budget = defaults.copy(
        maxCandidatesPerMethod = args.option("--max-candidates")?.toIntOrNull() ?: defaults.maxCandidatesPerMethod,
        maxProbesPerPattern = args.option("--max-probes")?.toIntOrNull() ?: defaults.maxProbesPerPattern,
    )
    val options = JobOptions(policy, budget, experiments = !args.flag("--no-experiments"), seedWrongCandidates = args.flag("--seed-wrong-candidates"))
    val token = CancellationToken()
    val done = CountDownLatch(1)
    val hook = Thread {
        token.cancel()
        done.await(10, TimeUnit.SECONDS)
    }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        val result = RecoveryJob(profile(args), options, token) { out(it) }.run(input, runDir)
        out("${result.state}: ${result.summary}")
        out("reports: ${runDir.resolve("reports")}")
        return result.exitCode.code
    } finally {
        done.countDown()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}
