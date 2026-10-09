package dev.apkproject.orchestrator

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.compiler.SolverAnswer
import dev.apkproject.compiler.Z3Runner
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.ResolvedProfile
import java.nio.file.Files
import java.nio.file.Path

data class ToolCheck(val name: String, val status: CheckStatus, val detail: String)

/** `apkproject doctor`: binaries, versions, pinned hashes and a smoke compile (section 22). */
object Doctor {
    /** Fast checks used before every job: tools exist and match the pinned hashes. */
    fun verify(profile: ResolvedProfile): List<ToolCheck> {
        val out = ArrayList<ToolCheck>()
        fun file(name: String, path: Path, executable: Boolean, sha: String?) {
            when {
                !Files.exists(path) -> out += ToolCheck(name, CheckStatus.FAIL, "missing: $path")
                executable && !Files.isExecutable(path) -> out += ToolCheck(name, CheckStatus.FAIL, "not executable: $path")
                sha != null && Hashing.sha256(path) != sha -> out += ToolCheck(name, CheckStatus.FAIL, "sha256 differs from profile pin ($path)")
                else -> out += ToolCheck(name, CheckStatus.PASS, path.toString())
            }
        }
        file("javac", profile.javac, true, null)
        file("java", profile.java, true, null)
        file("d8", profile.d8Jar, false, profile.profile.d8.sha256)
        file("z3", profile.z3, true, profile.profile.z3.sha256)
        if (!Files.isDirectory(profile.d8Lib)) out += ToolCheck("d8-lib", CheckStatus.FAIL, "library path missing: ${profile.d8Lib}")
        // Whole-app recovery (`decompile`, profile android-full).
        val p = profile.profile
        profile.jadxJar?.let { file("jadx", it, false, p.jadx?.sha256) }
        profile.ecjJar?.let { file("ecj", it, false, p.ecj?.sha256) }
        profile.androidJar?.let { file("android.jar", it, false, p.android?.sha256) }
        // REA is optional evidence: without Node.js the rest of `decompile` still works.
        profile.reaEngineJar?.let { jar ->
            if (Files.isRegularFile(jar)) file("rea-engine", jar, false, p.rea?.engineSha256)
            else out += ToolCheck("rea-engine", CheckStatus.UNKNOWN, "optional, not installed: $jar")
        }
        profile.reaExecutable?.let { exe ->
            out += if (Files.isExecutable(exe)) ToolCheck("rea", CheckStatus.PASS, exe.toString()) else ToolCheck("rea", CheckStatus.UNKNOWN, "optional, not installed (needs Node.js >= 22): $exe")
        }
        return out
    }

    /** Full doctor: adds version probes and a compile + solve smoke test. */
    fun run(profile: ResolvedProfile, workDir: Path): List<ToolCheck> {
        val out = verify(profile).toMutableList()
        if (out.any { it.status == CheckStatus.FAIL }) return out
        val javacVersion = ProcessWorker.run(listOf(profile.javac.toString(), "-version"), workDir, 30_000)
        val text = (javacVersion.stdout + javacVersion.stderr).trim()
        val major = Regex("""javac (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        out += ToolCheck(
            "jdk-version", if (major == profile.profile.jdk.expectedMajor) CheckStatus.PASS else CheckStatus.FAIL,
            "$text (profile expects ${profile.profile.jdk.expectedMajor})",
        )
        val z3 = Z3Runner(profile)
        val v = z3.version(workDir)
        out += ToolCheck("z3-version", if (v.contains(profile.profile.z3.version)) CheckStatus.PASS else CheckStatus.FAIL, v)
        val smoke = ForwardCompiler(profile).compile(mapOf("Smoke.java" to "public class Smoke { public static int f(int x) { return x + 1; } }"), workDir.resolve("smoke"), 120_000)
        out += ToolCheck("compile-smoke", smoke.status, if (smoke.status == CheckStatus.PASS) "javac + D8 produced ${Files.size(smoke.dexFile!!)} bytes of DEX" else smoke.diagnostics.take(500))
        val ans = z3.check("(declare-const x (_ BitVec 32))\n(assert (not (= (bvadd x x) (bvshl x #x00000001))))\n(check-sat)\n", workDir, 10_000)
        out += ToolCheck("solver-smoke", if (ans == SolverAnswer.Unsat) CheckStatus.PASS else CheckStatus.FAIL, "x+x == x<<1 proved: ${if (ans == SolverAnswer.Unsat) "unsat" else ans.toString()}")
        return out
    }
}
