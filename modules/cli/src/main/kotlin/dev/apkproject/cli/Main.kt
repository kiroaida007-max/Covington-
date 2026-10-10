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
                      [--no-cross-check] [--no-compile-check] [--no-rea] [--no-js] [--no-repair] [--no-library-names] [--max-checked-methods N]
                      Whole app: UI/resources, decompiled code (machine-checked where possible), web/JS,
                      native boundaries and the backend contract. INPUT: .apk, .apks, .xapk, .apkm, .aab or .dex.
  apkproject contract INPUT --out DIR
                      Backend contract only, straight from the bytecode (no decompiler): Retrofit endpoints
                      (obfuscated or not), JSON models, URLs; writes api-contract.json and openapi.yaml.
  apkproject audit INPUT --out DIR [--profile profiles/android-full.json]
                      Front end + security review: UI toolkit and language, screens, static UI-quality
                      and security findings (manifest, WebView, secrets, layouts) with a risk score.
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
        val valued = setOf("--out", "--policy", "--profile", "--suite", "--max-candidates", "--max-probes", "--max-checked-methods", "--threads", "--tools")
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
        maxCrossCheckMethods = args.option("--max-checked-methods")?.toIntOrNull() ?: defaults.maxCrossCheckMethods,
        threads = args.option("--threads")?.toIntOrNull() ?: defaults.threads,
    )
    val token = CancellationToken()
    val done = CountDownLatch(1)
    val hook = Thread {
        token.cancel()
        done.await(10, TimeUnit.SECONDS)
    }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        val result = dev.apkproject.app.FullRecovery(profile, options, token) { out(it) }.run(input, outDir)
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
    val dir = Files.createDirectories(Paths.get(args.require("--out")))
    val app = when (val o = dev.apkproject.app.AppInput.open(input)) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> return (if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT).code.also { err(o.failure.toString()) }
    }
    val dex = app.dexFiles()
    val c = dev.apkproject.app.ApiContractExtractor.extract(dir.resolve("none"), null, null, dex, emptyList())
    Files.writeString(dir.resolve("api-contract.json"), dev.apkproject.contracts.Json.encode(c))
    Files.writeString(dir.resolve("openapi.yaml"), dev.apkproject.app.ContractRenderer.openApi(c, input.fileName.toString(), "unknown"))
    // The same runnable mock server `decompile` writes (SYNTHESIZED): java server-skeleton/BackendSkeleton.java
    Files.writeString(Files.createDirectories(dir.resolve("server-skeleton")).resolve("BackendSkeleton.java"), dev.apkproject.app.ContractRenderer.serverSkeleton(c))
    Files.writeString(dir.resolve("README.md"), dev.apkproject.app.ContractRenderer.readme(c))
    out("${c.endpoints.size} endpoints, ${c.models.size} models, ${c.hosts.size} hosts -> $dir")
    return 0
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
    val resources = app.parts.firstNotNullOfOrNull { it.read("resources.arsc") }?.let { dev.apkproject.app.ResourceTable.parse(it) } ?: dev.apkproject.app.ResourceTable.EMPTY
    val r = dev.apkproject.app.NameOracle.recover(app.dexFiles().map { it.second }, resources)
    target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    Files.writeString(target, dev.apkproject.contracts.Json.encode(r.all))
    val byRule = r.all.groupingBy { it.rule }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
    out("${r.classes.size} classes, ${r.fields.values.sumOf { it.size }} fields, ${r.methods.values.sumOf { it.size }} methods named from compiler evidence ($byRule); ${r.conflicts.size} conflicts withheld -> $target")
    return 0
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
