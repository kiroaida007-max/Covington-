package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.ir.MethodIr
import dev.apkproject.lift.CodeShape
import dev.apkproject.lift.DexLifter
import dev.apkproject.reconstruct.JavaDeclarations
import dev.apkproject.reconstruct.Reconstructor
import dev.apkproject.source.JClass
import dev.apkproject.source.JavaPrinter
import dev.apkproject.validate.BatchValidator
import dev.apkproject.validate.CandidateUnit
import dev.apkproject.validate.UnitValidation
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
enum class MethodCheckStatus {
    /** Z3 proved the decompiled method equals the bytecode, and runtime tests agree. */
    PROVED,

    /**
     * The decompiled text was correct but presented a literal as an unrelated constant field (for
     * example a resource id with the same value); replaced by an equally proved reconstruction.
     */
    PROVED_CLEANED,

    /** The decompiled method was refuted and replaced by a reconstruction that passed validation. */
    REFUTED_REPLACED,

    /** The decompiled method was refuted and no validated replacement was found. */
    REFUTED,

    /** The decompiled text could not be checked (does not compile alone, solver unknown, ...). */
    UNKNOWN,

    /** The method's text could not be located unambiguously in the decompiled source. */
    NOT_LOCATED,
}

@Serializable
data class MethodCheck(
    val method: String,
    val file: String?,
    val status: MethodCheckStatus,
    val detail: String,
    val counterexample: List<Int>? = null,
    val structuralMatch: String? = null,
)

@Serializable
data class CrossCheckSummary(
    val eligible: Int,
    val checked: Int,
    val proved: Int,
    val cleaned: Int,
    val refutedReplaced: Int,
    val refuted: Int,
    val unknown: Int,
    val notLocated: Int,
    val skippedOverBudget: Int,
    val methods: List<MethodCheck>,
)

/**
 * Machine-checks decompiler output (dossier section 06: acceptance is separate from generation).
 * For every method inside the V0 semantic model, the decompiled Java text is compiled on its own,
 * its D8 output is lifted, and Z3 plus runtime execution decide whether it matches the original
 * bytecode. A refuted method is replaced by the engine's own validated reconstruction.
 */
class DecompilerCrossCheck(
    private val profile: ResolvedProfile,
    private val budget: Budget,
    private val cancel: CancellationToken? = null,
    private val log: (String) -> Unit = {},
) {
    private data class Target(val index: Int, val m: DexMethod, val ir: MethodIr, val shape: CodeShape?, val file: Path, val rel: String, val found: JavaMethodLocator.Found, val imports: List<String>)

    /** [names] is the mapping JADX applied; classes and methods are looked up under their recovered names. */
    fun run(dex: List<Pair<String, DexFile>>, javaRoot: Path, workDir: Path, maxMethods: Int, skipClasses: Set<String> = emptySet(), names: NameMapping = NameMapping.EMPTY): CrossCheckSummary {
        fun recoveredName(m: DexMethod) = names.methods[m.owner]?.get(m.name + m.protoDescriptor) ?: m.name
        val results = ArrayList<MethodCheck>()
        val eligible = dex.flatMap { it.second.methods() }
            .filter { it.owner.removePrefix("L").removeSuffix(";") !in skipClasses && it.owner.removePrefix("L").removeSuffix(";").substringBefore('$') !in skipClasses }
            .mapNotNull { m -> DexLifter.lift(m).ir?.let { m to it } }
        val selected = eligible.take(maxMethods)
        val targets = ArrayList<Target>()
        for ((i, pair) in selected.withIndex()) {
            val (m, ir) = pair
            val top = names.recovered(m.owner).removePrefix("L").removeSuffix(";").substringBefore('$')
            val rel = "$top.java"
            val file = javaRoot.resolve(rel)
            if (!Files.isRegularFile(file)) {
                results += MethodCheck(m.key.display(), null, MethodCheckStatus.NOT_LOCATED, "no decompiled file $rel (class merged or not exported)")
                continue
            }
            val source = Files.readString(file)
            when (val found = JavaMethodLocator.find(source, recoveredName(m), m.proto.parameters.size)) {
                is JavaMethodLocator.Result.Found -> {
                    val shape = m.code?.let { code -> (InstructionDecoder.decode(m.dex, code) as? Outcome.Ok)?.let { CodeShape.of(m.dex, it.value) } }
                    // Carry over only the imports the method text actually uses.
                    val code = JavaMethodLocator.codeOnly(found.found.text)
                    val imports = Regex("""(?m)^import\s+(?:static\s+)?([\w.]+)\s*;""").findAll(source)
                        .filter { imp -> Regex("""\b${Regex.escape(imp.groupValues[1].substringAfterLast('.'))}\b""").containsMatchIn(code) }
                        .map { it.value }.toList()
                    targets += Target(i, m, ir, shape, file, rel, found.found, imports)
                }
                is JavaMethodLocator.Result.Missing -> results += MethodCheck(m.key.display(), rel, MethodCheckStatus.NOT_LOCATED, found.reason)
            }
        }
        log("cross-check: ${eligible.size} methods inside the V0 model, ${targets.size} located in decompiled source")
        val validations = validate(targets, workDir.resolve("decompiled"), "J") { t -> renameMethod(t.found.text, recoveredName(t.m), "m") }
        val refuted = ArrayList<Pair<Target, UnitValidation>>()
        val artifacts = ArrayList<Pair<Target, String>>()
        for (t in targets) {
            val v = validations[t.index]
            val field = CONSTANT_FIELD.find(JavaMethodLocator.codeOnly(t.found.text))?.value
            when {
                v == null -> results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.UNKNOWN, "decompiled text does not compile on its own")
                // The bytecode of a V0 method reads no field, so a field name here is a constant the decompiler substituted.
                v.accepted && field != null -> artifacts += t to field
                v.accepted -> results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.PROVED, "Z3: no 32-bit input distinguishes it from the bytecode; runtime inputs agree", structuralMatch = v.shape?.toString())
                v.report.obligations.any { it.status == CheckStatus.FAIL } || v.report.checks.any { it.status == CheckStatus.FAIL } -> refuted += t to v
                else -> results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.UNKNOWN, v.report.obligations.firstOrNull()?.reason ?: v.notes.joinToString("; ").ifEmpty { "not decided" })
            }
        }
        // Refuted or misleading decompiler output: offer the engine's own reconstruction, under the same validation.
        val needsReplacement = refuted.map { it.first } + artifacts.map { it.first }
        val patches = HashMap<Path, MutableList<Pair<JavaMethodLocator.Found, String>>>()
        if (needsReplacement.isNotEmpty()) {
            val replacements = needsReplacement.associate { t ->
                t.index to Reconstructor.reconstruct(t.ir, recoveredName(t.m), JavaDeclarations.methodModifiers(t.m.accessFlags)).method
            }
            val replValidations = validate(needsReplacement, workDir.resolve("replacements"), "R") { t -> JavaPrinter.method(replacements.getValue(t.index).copy(name = "m")) }
            for ((t, field) in artifacts) {
                val rv = replValidations[t.index]
                if (rv != null && rv.accepted) {
                    val indent = t.found.indent
                    val text = "$indent// apkproject: the decompiler wrote the literal as $field (same value, unrelated meaning);\n" +
                        "$indent// replaced by a reconstruction proved equivalent to the bytecode. See recovery/cross-check.json.\n" +
                        JavaPrinter.method(replacements.getValue(t.index)).trimEnd().lines().joinToString("\n") { if (it.isEmpty()) it else indent + it }
                    patches.getOrPut(t.file) { ArrayList() } += t.found to text
                    results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.PROVED_CLEANED, "decompiled text was proved but used constant $field for a literal; replaced", structuralMatch = rv.shape?.toString())
                } else {
                    results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.PROVED, "proved; note: the decompiler wrote a literal as $field", structuralMatch = validations[t.index]?.shape?.toString())
                }
            }
            for ((t, v) in refuted) {
                val cex = v.counterexample?.toList()
                val rv = replValidations[t.index]
                if (rv != null && rv.accepted) {
                    val indent = t.found.indent
                    val text = "$indent// apkproject: the decompiler's version of this method was refuted (counterexample $cex);\n" +
                        "$indent// replaced by a reconstruction proved equivalent to the bytecode. See recovery/report.json.\n" +
                        JavaPrinter.method(replacements.getValue(t.index)).trimEnd().lines().joinToString("\n") { if (it.isEmpty()) it else indent + it }
                    patches.getOrPut(t.file) { ArrayList() } += t.found to text
                    results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.REFUTED_REPLACED, "decompiled method disagrees with the bytecode; replaced", cex, rv.shape?.toString())
                } else {
                    results += MethodCheck(t.m.key.display(), t.rel, MethodCheckStatus.REFUTED, "decompiled method disagrees with the bytecode; ${v.notes.joinToString("; ")}", cex)
                }
            }
        }
        for ((file, edits) in patches) {
            var text = Files.readString(file)
            for ((found, replacement) in edits.sortedByDescending { it.first.start }) {
                text = text.substring(0, found.lineStart) + replacement + text.substring(found.end)
            }
            Files.writeString(file, text)
        }
        return CrossCheckSummary(
            eligible = eligible.size,
            checked = targets.size,
            proved = results.count { it.status == MethodCheckStatus.PROVED || it.status == MethodCheckStatus.PROVED_CLEANED },
            cleaned = results.count { it.status == MethodCheckStatus.PROVED_CLEANED },
            refutedReplaced = results.count { it.status == MethodCheckStatus.REFUTED_REPLACED },
            refuted = results.count { it.status == MethodCheckStatus.REFUTED },
            unknown = results.count { it.status == MethodCheckStatus.UNKNOWN },
            notLocated = results.count { it.status == MethodCheckStatus.NOT_LOCATED },
            skippedOverBudget = eligible.size - selected.size,
            methods = results.sortedBy { it.method },
        )
    }

    /** Compiles one probe class per target (dropping probes that fail to compile) and validates the rest. */
    private fun validate(targets: List<Target>, workDir: Path, prefix: String, body: (Target) -> String): Map<Int, UnitValidation> {
        if (targets.isEmpty()) return emptyMap()
        var pending = targets
        val compiler = ForwardCompiler(profile, cancel)
        repeat(3) { attempt ->
            val sources = pending.associate { t ->
                val imports = if (prefix == "J") t.imports.joinToString("\n", postfix = "\n") else ""
                "apkjadx/$prefix${t.index}.java" to "package apkjadx;\n\n$imports\npublic final class $prefix${t.index} {\n${body(t)}\n}\n"
            }
            val compiled = compiler.compile(sources, workDir.resolve("attempt$attempt"), budget.compileTimeoutMs, classpath = listOfNotNull(profile.androidJar?.takeIf { Files.isRegularFile(it) }))
            if (compiled.status == CheckStatus.PASS) {
                val units = pending.map { t ->
                    CandidateUnit("$prefix${t.index}", t.ir, t.shape, "Lapkjadx/$prefix${t.index};", "m", Hashing.sha256(body(t)))
                }
                val results = BatchValidator(profile, budget, cancel).validate(units, compiled, workDir.resolve("validate$attempt"))
                return pending.zip(results).associate { (t, v) -> t.index to v }
            }
            val bad = Regex("""apkjadx[/\\]$prefix(\d+)\.java""").findAll(compiled.diagnostics).map { it.groupValues[1].toInt() }.toSet()
            if (bad.isEmpty()) return emptyMap()
            pending = pending.filter { it.index !in bad }
            if (pending.isEmpty()) return emptyMap()
        }
        return emptyMap()
    }

    /** `R.attr.x`, `Foo.BAR`: a qualified constant other than Integer.MIN_VALUE / MAX_VALUE. */
    private val CONSTANT_FIELD = Regex("""\b(?!Integer\.(?:MIN_VALUE|MAX_VALUE)\b)(?:[A-Za-z_]\w*\.)+[A-Za-z_]\w*\b(?!\s*\()""")

    private fun renameMethod(text: String, from: String, to: String): String =
        text.replaceFirst(Regex("""\b${Regex.escape(from)}(\s*\()"""), "$to$1")
}

/** Finds one method declaration in Java source text, skipping comments and string literals. */
object JavaMethodLocator {
    data class Found(val lineStart: Int, val start: Int, val end: Int, val indent: String, val text: String)

    sealed class Result {
        data class Found(val found: JavaMethodLocator.Found) : Result()
        data class Missing(val reason: String) : Result()
    }

    fun find(source: String, name: String, arity: Int): Result {
        val code = codeMask(source)
        val decl = Regex("""\bint\s+${Regex.escape(name)}\s*\(""")
        val candidates = decl.findAll(source).filter { code[it.range.first] }.mapNotNull { m ->
            val lineStart = source.lastIndexOf('\n', m.range.first) + 1
            val prefix = source.substring(lineStart, m.range.first)
            if (!Regex("""\bstatic\b""").containsMatchIn(prefix) || prefix.contains('=') || prefix.contains(';')) return@mapNotNull null
            val open = m.range.last
            val close = matching(source, code, open, '(', ')') ?: return@mapNotNull null
            val params = source.substring(open + 1, close).trim()
            val count = if (params.isEmpty()) 0 else params.split(',').size
            if (count != arity || (count > 0 && !params.split(',').all { Regex("""^\s*(?:final\s+)?int\s+\w+\s*$""").matches(it) })) return@mapNotNull null
            // The body starts at the first '{' after the parameters; a ';' first means no body.
            val brace = (close + 1 until source.length).firstOrNull { code[it] && (source[it] == '{' || source[it] == ';') }
                ?: return@mapNotNull null
            if (source[brace] != '{') return@mapNotNull null
            val end = matching(source, code, brace, '{', '}') ?: return@mapNotNull null
            val indent = prefix.takeWhile { it == ' ' || it == '\t' }
            Found(lineStart, m.range.first, end + 1, indent, source.substring(lineStart, end + 1))
        }.toList()
        return when (candidates.size) {
            0 -> Result.Missing("no static int $name(${List(arity) { "int" }.joinToString(", ")}) declaration found (renamed or inlined)")
            1 -> Result.Found(candidates.single())
            else -> Result.Missing("${candidates.size} declarations of $name with $arity int parameters (nested classes); ambiguous")
        }
    }

    private fun matching(s: String, code: BooleanArray, open: Int, o: Char, c: Char): Int? {
        var depth = 0
        for (i in open until s.length) {
            if (!code[i]) continue
            if (s[i] == o) depth++
            if (s[i] == c) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    /** The text with comments and literals blanked out, so scans see only code. */
    fun codeOnly(s: String): String {
        val mask = codeMask(s)
        return String(CharArray(s.length) { if (mask[it]) s[it] else ' ' })
    }

    /** true where the character is code, false inside comments, string and char literals. */
    fun codeMask(s: String): BooleanArray {
        val mask = BooleanArray(s.length) { true }
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("//", i) -> {
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    for (k in i until end) mask[k] = false
                    i = end
                }
                s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    for (k in i until end) mask[k] = false
                    i = end
                }
                s[i] == '"' || s[i] == '\'' -> {
                    val q = s[i]
                    var k = i + 1
                    while (k < s.length && s[k] != q) {
                        if (s[k] == '\\') k++
                        k++
                    }
                    for (j in i..minOf(k, s.length - 1)) mask[j] = false
                    i = k + 1
                }
                else -> i++
            }
        }
        return mask
    }
}
