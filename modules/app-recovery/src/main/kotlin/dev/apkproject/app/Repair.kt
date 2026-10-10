package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexMethod
import dev.apkproject.lift.RoundTripMatch
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/** One source edit proposed for a method that did not round-trip. */
data class RepairCandidate(val file: String, val rule: String, val line: Int, val before: String, val after: String, val text: String)

@Serializable
data class RepairRecord(
    val file: String,
    val rule: String,
    val line: Int,
    val before: String,
    val after: String,
    /** Methods that became round-trip equivalent with this edit. */
    val fixedMethods: List<String>,
)

@Serializable
data class RepairSummary(val status: String, val note: String, val candidatesTried: Int, val accepted: Int, val methodsFixed: Int, val rounds: Int, val elapsedMs: Long)

/**
 * Edits that undo known decompiler mistakes. They are only proposals: a candidate is kept only when
 * the round trip proves that it makes more methods equivalent to the original bytecode and none worse.
 */
object RepairRules {
    private const val MAX_PER_FILE = 48

    fun candidates(file: String, source: String, methodKeys: List<String>): List<RepairCandidate> {
        val code = JavaMethodLocator.codeMask(source)
        val regions = methodKeys.flatMap { regions(source, code, it) }.distinct().sortedBy { it.first }
        if (regions.isEmpty()) return emptyList()
        val out = ArrayList<RepairCandidate>()
        fun lineOf(pos: Int) = source.substring(0, pos).count { it == '\n' } + 1
        fun lineText(pos: Int) = source.substring(source.lastIndexOf('\n', pos - 1) + 1, source.indexOf('\n', pos).let { if (it < 0) source.length else it }).trim()
        fun add(rule: String, start: Int, end: Int, replacement: String) {
            val text = source.substring(0, start) + replacement + source.substring(end)
            val before = lineText(start)
            val after = text.substring(text.lastIndexOf('\n', start - 1) + 1, text.indexOf('\n', start).let { if (it < 0) text.length else it }).trim()
            out += RepairCandidate(file, rule, lineOf(start), before, after, text)
        }
        // R1. `X.this.new Inner()` where `new Inner()` means the same: javac adds a null check for
        // the qualified form that the original never had.
        for ((rs, re) in regions) {
            Regex("""\b[A-Z][\w.]*\.this\.new\s+""").findAll(source.substring(rs, re)).filter { code[rs + it.range.first] }.forEach { m ->
                add("implicit-outer-instance", rs + m.range.first, rs + m.range.last + 1, "new ")
            }
        }
        // R2. A widening the decompiler dropped: `float r = w / h;` computed as int division.
        for ((rs, re) in regions) {
            val region = source.substring(rs, re)
            for (op in Regex("""(?<![/*=])\s([/*])\s(?![/*=])""").findAll(region)) {
                val at = rs + op.range.first
                if (!code[at + 1]) continue
                val left = leftOperand(source, code, at) ?: continue
                val statement = source.substring(source.lastIndexOf(';', left.first).coerceAtLeast(source.lastIndexOf('{', left.first)) + 1, left.first)
                val declared = Regex("""\b(float|double|long)\s+\w+\s*=\s*[^;]*$""").find(statement)?.groupValues?.get(1)
                val types = declared?.let { listOf(it) } ?: listOf("float", "double", "long")
                val operand = source.substring(left.first, left.second)
                if (operand.startsWith("(") && Regex("""^\((?:float|double|long|int)\)""").containsMatchIn(operand)) continue
                for (t in types) add("widen-before-${if (op.groupValues[1] == "/") "divide" else "multiply"}", left.first, left.second, "(($t) $operand)")
            }
        }
        // R3. A reference cast the original did not have.
        for ((rs, re) in regions) {
            Regex("""\(\s*([A-Z][\w.$]*(?:\[])*)\s*\)\s*(?=[\w(])""").findAll(source.substring(rs, re)).filter { code[rs + it.range.first] }.forEach { m ->
                add("drop-cast", rs + m.range.first, rs + m.range.last + 1, "")
            }
        }
        return out.distinctBy { it.text }.take(MAX_PER_FILE)
    }

    /** [start, end) of the operand that ends just before the operator at [opAt] (identifier chain, call or parenthesised expression). */
    private fun leftOperand(s: String, code: BooleanArray, opAt: Int): Pair<Int, Int>? {
        var end = opAt
        while (end > 0 && s[end - 1].isWhitespace()) end--
        var i = end
        while (true) {
            if (i <= 0) break
            val c = s[i - 1]
            if (c == ')' || c == ']') {
                val open = if (c == ')') '(' else '['
                var depth = 0
                var k = i - 1
                while (k >= 0) {
                    if (code[k]) {
                        if (s[k] == c) depth++
                        if (s[k] == open) {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    k--
                }
                if (k < 0) return null
                i = k
            } else if (c.isLetterOrDigit() || c == '_' || c == '$' || c == '.') {
                i--
            } else {
                break
            }
        }
        if (i == end) return null
        val operand = s.substring(i, end)
        if (operand.first().isDigit() || operand == "this") return null
        return i to end
    }

    /** Body ranges of the declarations that may hold the method named by [key] (`Lpkg/Cls$Inner;->name(proto)ret`). */
    fun regions(source: String, code: BooleanArray, key: String): List<Pair<Int, Int>> {
        val owner = key.substringBefore(";->").removePrefix("L")
        var name = key.substringAfter(";->").substringBefore('(')
        if (name.startsWith("lambda$") || name.startsWith("access$") || name.startsWith("-")) return emptyList()
        if (name == "<clinit>") {
            return Regex("""\bstatic\s*\{""").findAll(source).filter { code[it.range.first] }.mapNotNull { m -> body(source, code, m.range.last) }.toList()
        }
        if (name == "<init>") {
            name = owner.substringAfterLast('/').substringAfterLast('$')
            if (name.all { it.isDigit() }) return emptyList()
        }
        val decl = Regex("""(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:[\w<>\[\],.?]+\s+)*${Regex.escape(name)}\s*\(""")
        return decl.findAll(source).filter { code[it.range.last] && !it.value.trimStart().startsWith("return") && !it.value.contains(" new ") }.mapNotNull { m ->
            val brace = (m.range.last until source.length).firstOrNull { code[it] && (source[it] == '{' || source[it] == ';') } ?: return@mapNotNull null
            if (source[brace] != '{') null else body(source, code, brace)
        }.toList()
    }

    private fun body(source: String, code: BooleanArray, openBrace: Int): Pair<Int, Int>? {
        var depth = 0
        for (k in openBrace until source.length) {
            if (!code[k]) continue
            if (source[k] == '{') depth++
            if (source[k] == '}') {
                depth--
                if (depth == 0) return openBrace to k + 1
            }
        }
        return null
    }
}

/**
 * Verified repair of decompiler errors. Files whose methods did not round-trip get candidate edits
 * ([RepairRules]); each round compiles one candidate per file in a single javac run, dexes the
 * result in both D8 modes and compares again. An edit is kept only if more methods of the file
 * become equivalent and no method gets worse. Every kept edit is recorded with its before/after line.
 */
class SourceRepair(
    private val profile: ResolvedProfile,
    private val verifier: RoundTripVerifier,
    private val cancel: CancellationToken?,
    private val log: (String) -> Unit,
) {
    private val rank = listOf(
        RoundTripMatch.EQUIVALENT_DATAFLOW.name, RoundTripMatch.EQUIVALENT_MODULO_LAYOUT.name, RoundTripMatch.SIMILAR_OPERATIONS.name,
        RoundTripMatch.DIFFERENT.name, "NOT_FOUND", "NOT_COMPILED",
    )

    private fun score(r: String) = if (r in RoundTripVerifier.EQUIVALENT) 0 else rank.indexOf(r).coerceAtLeast(0)

    fun run(
        javaRoot: Path,
        classesDir: Path,
        libraryJars: List<Path>,
        originalByClass: Map<String, Map<String, DexMethod>>,
        results: List<MethodRoundTrip>,
        diagnostics: Map<String, FileDiagnostics>,
        minApi: Int,
        workDir: Path,
        budgetMs: Long,
        maxRounds: Int = 40,
    ): Triple<RepairSummary, List<RepairRecord>, List<MethodRoundTrip>> {
        val started = System.nanoTime()
        val android = profile.androidJar
        if (android == null || !Files.isExecutable(profile.javac)) return Triple(RepairSummary("NOT_RUN", "javac or android.jar unavailable", 0, 0, 0, 0, 0), emptyList(), results)
        val current = LinkedHashMap<String, MethodRoundTrip>().apply { results.forEach { put(it.method, it) } }
        val byFile = results.groupBy { it.file ?: "" }
        val pending = LinkedHashMap<String, ArrayDeque<RepairCandidate>>()
        val seen = HashSet<String>()
        fun refill(file: String) {
            val keys = current.values.filter { it.file == file && it.result in setOf(RoundTripMatch.DIFFERENT.name, RoundTripMatch.SIMILAR_OPERATIONS.name) }.map { it.method }
            if (keys.isEmpty()) { pending.remove(file); return }
            // An edit already tried (at the same line text) is not tried again after another edit lands.
            val fresh = RepairRules.candidates(file, Files.readString(javaRoot.resolve(file)), keys).filter { seen.add("${it.file}|${it.rule}|${it.before}|${it.after}") }
            if (fresh.isEmpty()) pending.remove(file) else pending[file] = ArrayDeque(fresh)
        }
        for ((file, rs) in byFile) {
            if (file.isEmpty() || (diagnostics[file]?.errors ?: 0) > 0) continue
            if (rs.any { it.result == RoundTripMatch.DIFFERENT.name || it.result == RoundTripMatch.SIMILAR_OPERATIONS.name }) refill(file)
        }
        pending.entries.removeIf { it.value.isEmpty() }
        val records = ArrayList<RepairRecord>()
        var tried = 0
        var rounds = 0
        var barren = 0 // consecutive rounds that kept nothing: the remaining differences are not what the rules fix
        while (pending.isNotEmpty() && rounds < maxRounds && barren < 8 && (System.nanoTime() - started) / 1_000_000 < budgetMs) {
            if (cancel?.cancelled == true) throw CancelledException()
            rounds++
            val batch = pending.mapNotNull { (_, q) -> q.removeFirstOrNull() }.toMutableList()
            pending.entries.removeIf { it.value.isEmpty() }
            tried += batch.size
            val round = workDir.resolve("round-$rounds")
            val keptBefore = records.size
            val compiled = compile(batch, classesDir, libraryJars, android, round)
            if (compiled == null) {
                barren++
                continue
            }
            val (out, accepted) = compiled
            val classFiles = Files.walk(out).use { s -> s.filter { it.isRegularFile() && it.toString().endsWith(".class") }.toList() }
            if (classFiles.isEmpty()) continue
            val recompiled = verifier.recompile(out, classFiles, libraryJars + listOf(classesDir), minApi, round.resolve("dex"), 600_000)
            for (c in accepted) {
                val owners = originalByClass.filterKeys { verifier.fileOf(it) == c.file }
                val now = verifier.compare(owners, recompiled, emptyMap()).associateBy { it.method }
                val before = now.keys.associateWith { current[it]?.result ?: "NOT_FOUND" }
                val worse = now.values.any { r -> r.result != "SYNTHETIC" && score(r.result) > score(before.getValue(r.method)) }
                val fixed = now.values.filter { it.result in RoundTripVerifier.EQUIVALENT && before.getValue(it.method) !in RoundTripVerifier.EQUIVALENT }
                if (worse || fixed.isEmpty()) continue
                // Keep it: source, recompiled classes and results.
                Files.writeString(javaRoot.resolve(c.file), c.text)
                replaceClasses(classesDir, out, c.file)
                now.values.forEach { current[it.method] = it }
                records += RepairRecord(c.file, c.rule, c.line, c.before, c.after, fixed.map { it.method })
                log("repair: ${c.rule} in ${c.file}:${c.line} makes ${fixed.size} method(s) round-trip equivalent")
                refill(c.file)
            }
            barren = if (records.size > keptBefore) 0 else barren + 1
        }
        val fixed = records.sumOf { it.fixedMethods.size }
        val note = when {
            pending.isEmpty() -> "all candidates tried"
            barren >= 8 -> "stopped after 8 rounds without a kept edit; ${pending.values.sumOf { it.size }} candidates untried"
            else -> "stopped after $rounds rounds (budget); ${pending.values.sumOf { it.size }} candidates untried"
        }
        return Triple(RepairSummary("MEASURED", note, tried, records.size, fixed, rounds, (System.nanoTime() - started) / 1_000_000), records, current.values.toList())
    }

    /** Compiles each candidate's file; candidates javac rejects are dropped and the rest retried. */
    private fun compile(batch: List<RepairCandidate>, classesDir: Path, libraryJars: List<Path>, android: Path, round: Path): Pair<Path, List<RepairCandidate>>? {
        var remaining = batch
        for (attempt in 0 until 3) {
            if (remaining.isEmpty()) return null
            val src = round.resolve("src-$attempt")
            val out = round.resolve("classes-$attempt")
            AtomicFiles.deleteRecursively(src); AtomicFiles.deleteRecursively(out)
            Files.createDirectories(out)
            val files = remaining.map { c -> src.resolve(c.file).also { Files.createDirectories(it.parent); Files.writeString(it, c.text) } }
            val list = round.resolve("sources-$attempt.txt")
            Files.write(list, files.map { it.toAbsolutePath().toString() })
            // Relaxed like the overlay, so files compiled with checked-exception rules relaxed can be repaired too.
            val argv = JavacCommand.argv(
                profile, round.parent ?: round, "2g", relaxed = true,
                listOf(
                    "-source", "8", "-target", "8", "-bootclasspath", android.toString(),
                    "-cp", (listOf(classesDir) + libraryJars).joinToString(File.pathSeparator), "-g", "-proc:none", "-nowarn",
                    "-encoding", "UTF-8", "-Xmaxerrs", "100000", "-d", out.toString(), "@$list",
                ),
            )
            val r = ProcessWorker.run(argv, round, 600_000, maxLogBytes = 8 shl 20, cancel = cancel)
            if (r.cancelled) throw CancelledException()
            if (r.ok) return out to remaining
            val root = src.toAbsolutePath()
            val bad = Regex("""(?m)^(.+?\.java):\d+: error:""").findAll(r.stdout + r.stderr)
                .mapNotNull { runCatching { root.relativize(Path.of(it.groupValues[1]).toAbsolutePath()).toString().replace('\\', '/') }.getOrNull() }.toSet()
            if (bad.isEmpty()) return null
            remaining = remaining.filterNot { it.file in bad }
        }
        return null
    }

    private fun replaceClasses(classesDir: Path, out: Path, file: String) {
        val base = file.removeSuffix(".java")
        val simple = base.substringAfterLast('/')
        val dir = classesDir.resolve(base).parent ?: classesDir
        if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.filter { f -> f.fileName.toString().let { it == "$simple.class" || it.startsWith("$simple$") } }.toList() }.forEach { Files.delete(it) }
        val from = out.resolve(base).parent ?: out
        if (Files.isDirectory(from)) Files.list(from).use { s -> s.filter { f -> f.fileName.toString().let { it == "$simple.class" || it.startsWith("$simple$") } }.toList() }.forEach { f ->
            Files.createDirectories(dir)
            Files.copy(f, dir.resolve(f.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
