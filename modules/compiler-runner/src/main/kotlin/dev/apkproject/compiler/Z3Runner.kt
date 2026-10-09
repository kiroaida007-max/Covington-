package dev.apkproject.compiler

import dev.apkproject.contracts.ResolvedProfile
import java.nio.file.Files
import java.nio.file.Path

sealed class SolverAnswer {
    data class Sat(val model: Map<String, String>) : SolverAnswer()
    object Unsat : SolverAnswer()
    data class Unknown(val reason: String) : SolverAnswer()
}

/** Runs Z3 as an isolated process on an SMT-LIB 2 file (sections 04 and 19). */
class Z3Runner(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    fun version(workDir: Path): String {
        val r = ProcessWorker.run(listOf(profile.z3.toString(), "--version"), workDir, 10_000)
        return if (r.ok) r.stdout.trim() else "unavailable"
    }

    fun available(): Boolean = Files.isExecutable(profile.z3)

    /** Runs a multi-command script and returns raw solver output (used for model cross-checks). */
    fun raw(script: String, workDir: Path, timeoutMs: Long): String {
        check(available()) { "z3 not installed at ${profile.z3}" }
        Files.createDirectories(workDir)
        val file = Files.createTempFile(workDir, "script", ".smt2")
        Files.writeString(file, script)
        val r = ProcessWorker.run(listOf(profile.z3.toString(), "-smt2", file.toString()), workDir, timeoutMs, cancel = cancel)
        check(r.ok) { "z3 failed: ${r.stderr}${r.stdout.take(500)}" }
        return r.stdout
    }

    /** [query] must end with `(check-sat)` and, for models, a `(get-value ...)` command. */
    fun check(query: String, workDir: Path, timeoutMs: Long): SolverAnswer {
        if (!available()) return SolverAnswer.Unknown("z3 not installed at ${profile.z3}")
        Files.createDirectories(workDir)
        val file = Files.createTempFile(workDir, "query", ".smt2")
        Files.writeString(file, query)
        val seconds = maxOf(1, (timeoutMs / 1000).toInt())
        val r = ProcessWorker.run(listOf(profile.z3.toString(), "-smt2", "-T:$seconds", file.toString()), workDir, timeoutMs + 5_000, cancel = cancel)
        if (r.cancelled) throw CancelledException()
        if (r.timedOut) return SolverAnswer.Unknown("timeout after ${timeoutMs}ms")
        val lines = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when (lines.firstOrNull()) {
            "unsat" -> SolverAnswer.Unsat
            "sat" -> SolverAnswer.Sat(parseModel(lines.drop(1).joinToString(" ")))
            "unknown" -> SolverAnswer.Unknown("solver returned unknown")
            "timeout" -> SolverAnswer.Unknown("solver timeout")
            else -> SolverAnswer.Unknown("unexpected solver output: ${(r.stdout + r.stderr).take(500)}")
        }
    }

    companion object {
        private val PAIR = Regex("""\(\s*([A-Za-z_][A-Za-z0-9_]*)\s+(#x[0-9a-fA-F]+|#b[01]+|true|false)\s*\)""")

        fun parseModel(text: String): Map<String, String> = PAIR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

        /** `#x80000000` -> Int.MIN_VALUE */
        fun bv32(value: String): Int = when {
            value.startsWith("#x") -> java.lang.Long.parseLong(value.substring(2), 16).toInt()
            value.startsWith("#b") -> java.lang.Long.parseLong(value.substring(2), 2).toInt()
            else -> throw IllegalArgumentException("not a bit-vector literal: $value")
        }
    }
}
