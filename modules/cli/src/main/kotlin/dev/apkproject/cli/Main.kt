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
                      [--no-cross-check] [--no-compile-check] [--no-rea] [--no-js] [--no-repair] [--max-checked-methods N]
                      Whole app: UI/resources, decompiled code (machine-checked where possible), web/JS,
                      native boundaries and the backend contract. INPUT: .apk, .apks, .xapk, .apkm, .aab or .dex.
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
        val valued = setOf("--out", "--policy", "--profile", "--suite", "--max-candidates", "--max-probes", "--max-checked-methods", "--threads")
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
    val ok = checks.all { it.status == CheckStatus.PASS }
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
