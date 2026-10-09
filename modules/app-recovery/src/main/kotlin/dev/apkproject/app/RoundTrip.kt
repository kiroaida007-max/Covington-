package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.CodeItem
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.lift.DataflowShape
import dev.apkproject.lift.MethodIndex
import dev.apkproject.lift.RoundTripComparison
import dev.apkproject.lift.RoundTripMatch
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class FileDiagnostics(val path: String, val errors: Int, val firstErrors: List<String>)

@Serializable
data class EcjResult(
    val status: String,
    val compiler: String,
    val files: Int,
    val filesWithErrors: Int,
    val errors: Int,
    val appFiles: Int,
    val appFilesWithErrors: Int,
    val topErrors: Map<String, Int>,
    val elapsedMs: Long,
    val note: String,
    val classpath: List<String>,
)

/**
 * Compile check with the Eclipse compiler. Unlike javac, which stops type-checking every file once
 * any file has a syntax error, ECJ reports every file's problems in one pass and still emits class
 * files, with erroneous methods replaced by stubs that throw. Those classes feed round-trip
 * verification.
 */
class EcjCompiler(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    fun available() = profile.ecjJar?.let { Files.isRegularFile(it) } == true

    fun compile(javaRoot: Path, classpath: List<Path>, classesOut: Path, workDir: Path, timeoutMs: Long, appPackages: List<String>): Pair<EcjResult, Map<String, FileDiagnostics>> {
        val androidJar = profile.androidJar ?: error("android.jar not configured")
        val files = Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.map { it.toAbsolutePath().toString() }.sorted().toList() }
        Files.createDirectories(workDir)
        Files.createDirectories(classesOut)
        val argsFile = workDir.resolve("ecj-sources.txt")
        Files.write(argsFile, files.map { "\"" + it.replace("\\", "\\\\") + "\"" })
        val log = workDir.resolve("ecj.xml")
        val argv = buildList {
            add(profile.java.toString()); add("-Xmx3g"); add("-jar"); add(profile.ecjJar.toString())
            add("-1.8"); add("-proceedOnError"); add("-nowarn"); add("-encoding"); add("UTF-8"); add("-preserveAllLocals"); add("-g")
            add("-bootclasspath"); add(androidJar.toString())
            if (classpath.isNotEmpty()) { add("-cp"); add(classpath.joinToString(java.io.File.pathSeparator)) }
            add("-d"); add(classesOut.toString()); add("-log"); add(log.toString()); add("@$argsFile")
        }
        val r = ProcessWorker.run(argv, workDir, timeoutMs, maxLogBytes = 1 shl 20, cancel = cancel)
        if (r.cancelled) throw CancelledException()
        val diags = if (Files.isRegularFile(log)) parse(Files.readString(log), javaRoot) else emptyMap()
        val errors = diags.values.sumOf { it.errors }
        val kinds = diags.values.flatMap { it.firstErrors }.map { it.replace(Regex("""\b[A-Z][\w$]*(?:\.[\w$]+)*\b"""), "_").take(80) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).associate { it.key to it.value }
        val withErrors = diags.values.filter { it.errors > 0 }
        val appFiles = files.map { javaRoot.relativize(Path.of(it)).toString().replace('\\', '/') }.count { QualityScanner.origin(it, appPackages) == "app" }
        val appErrors = withErrors.count { QualityScanner.origin(it.path, appPackages) == "app" }
        val status = when {
            r.timedOut -> "UNKNOWN"
            errors == 0 && r.exitCode == 0 -> "PASS"
            else -> "FAIL"
        }
        val note = if (status == "PASS") "every recovered Java file compiles (ECJ, Java 8 level, android-${profile.profile.android?.apiLevel} + verified libraries)"
        else "${withErrors.size} of ${files.size} files have compile errors ($appErrors of $appFiles app files); erroneous methods are excluded from round-trip verification"
        return EcjResult(
            status, "ECJ ${profile.profile.ecj?.version}", files.size, withErrors.size, errors, appFiles, appErrors, kinds, r.elapsedMs, note,
            listOf(androidJar.fileName.toString()) + classpath.map { it.fileName.toString() },
        ) to diags
    }

    companion object {
        fun parse(xml: String, javaRoot: Path): Map<String, FileDiagnostics> {
            val out = HashMap<String, FileDiagnostics>()
            for (block in xml.split("<source ").drop(1)) {
                val path = Regex("""\bpath="([^"]+)"""").find(block)?.groupValues?.get(1) ?: continue
                val errors = Regex("""<problems [^>]*\berrors="(\d+)"""").find(block)?.groupValues?.get(1)?.toInt() ?: 0
                val messages = Regex("""<problem [^>]*severity="ERROR"[^>]*>\s*<message value="([^"]*)"""").findAll(block)
                    .map { unescape(it.groupValues[1]) }.take(5).toList()
                val rel = runCatching { javaRoot.toAbsolutePath().relativize(Path.of(path).toAbsolutePath()).toString().replace('\\', '/') }.getOrDefault(path)
                out[rel] = FileDiagnostics(rel, errors, messages)
            }
            return out
        }

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}

@Serializable
data class MethodRoundTrip(
    val method: String,
    val result: String,
    val file: String?,
    /** D8 mode whose output matched best ("release" or "debug"); the original's build mode is not recorded in the APK. */
    val d8Mode: String? = null,
    val similarity: Double? = null,
)

@Serializable
data class RoundTripSummary(
    val status: String,
    val note: String,
    /** Methods with code in the recovered (non-library) classes of the original DEX. */
    val methods: Int,
    val equivalentDataflow: Int,
    /** Same graph and dataflow once block order, branch polarity and return tails are factored out. */
    val equivalentModuloLayout: Int,
    val similarOperations: Int,
    val different: Int,
    val notCompiled: Int,
    val notFound: Int,
    val appMethods: Int,
    /** App methods that are equivalent, strictly or modulo layout. */
    val appEquivalent: Int,
    val elapsedMs: Long,
    /** Source files recompiled with javac (the compiler Android builds use) and files left with ECJ output. */
    val compiledWithJavac: Int = 0,
    val compiledWithEcjOnly: Int = 0,
    /** Compiler-generated methods (accessors, bridges, desugared lambdas) with no counterpart to compare; not in [methods]. */
    val syntheticNotCompared: Int = 0,
) {
    val equivalent: Int get() = equivalentDataflow + equivalentModuloLayout
}

/**
 * Round-trip verification of every recovered method: the decompiled source is compiled (ECJ), dexed
 * with D8, decoded with our own reader and compared with the original method by dataflow
 * fingerprint. A method that round-trips to the same dataflow is strong evidence that the recovered
 * source is faithful; it is not a semantic proof (see PROVED for that).
 */
class RoundTripVerifier(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    // The APK does not record whether D8 ran in debug or release mode (debug info alone does not
    // tell: release builds can keep it), so both are tried and each method keeps its closer match.
    private val modes = listOf("release", "debug")
    private val shapes = HashMap<DexMethod, DataflowShape?>()
    private var originalIndex: MethodIndex? = null

    /** Renames applied before decompiling to keep obfuscated names valid Java (see [NameMapping]). */
    var names: NameMapping = NameMapping.EMPTY
        set(value) { field = value; classesBack = value.classesBack(); fieldsBack = value.fieldsBack(); methodsBack = value.methodsBack() }
    private var classesBack: Map<String, String> = emptyMap()
    private var fieldsBack: Map<String, String> = emptyMap()
    private var methodsBack: Map<String, String> = emptyMap()

    /** A recompiled method's key in the original's names. */
    private fun backKey(m: DexMethod): String {
        val prefix = "${m.owner}->${m.name}("
        val named = methodsBack[prefix]?.let { it + m.protoDescriptor.removePrefix("(") } ?: "${m.owner}->${m.name}${m.protoDescriptor}"
        return back(named)
    }
    private val DESCRIPTOR = Regex("""L[^;\s(),<>\[]+;""")
    private fun back(s: String) = if (classesBack.isEmpty()) s else DESCRIPTOR.replace(s) { classesBack[it.value] ?: it.value }

    /** The recovered source file that holds an original class. */
    fun fileOf(owner: String) = topLevelFile(names.recovered(owner))

    /** Names JADX changed (see [JadxRenames]); set before comparing. */
    var renames: JadxRenames? = null
        set(value) { field = value; memberMap = value?.memberMap() ?: emptyMap() }
    private var memberMap: Map<String, String> = emptyMap()

    fun shapeOf(m: DexMethod, index: MethodIndex? = null): DataflowShape? = shapes.getOrPut(m) {
        (InstructionDecoder.decode(m.dex, m.code!!) as? Outcome.Ok)?.value?.let { DataflowShape.of(m.dex, it, index) }
    }

    /** A recompiled method's shape in the original's names: JADX member renames, then hygiene renames. */
    private fun recompiledShape(m: DexMethod, index: MethodIndex?): DataflowShape? =
        shapeOf(m, index)?.renamedMembers(memberMap)?.renamedMembers(fieldsBack)?.renamedMembers(methodsBack)?.renamedClasses(classesBack)

    /** Original methods with code of the recovered classes, by class. */
    fun originalByClass(original: List<Pair<String, DexFile>>, recoveredFiles: Set<String>): Map<String, Map<String, DexMethod>> {
        if (originalIndex == null) originalIndex = MethodIndex(original.map { it.second })
        val out = LinkedHashMap<String, LinkedHashMap<String, DexMethod>>()
        for ((_, dex) in original) for (m in dex.methods()) {
            if (m.code == null) continue
            if (fileOf(m.owner) !in recoveredFiles) continue // library replaced by a dependency, or removed artifact
            out.getOrPut(m.owner) { LinkedHashMap() }["${m.name}${m.protoDescriptor}"] = m
        }
        return out
    }

    /** Recompiled methods per D8 mode, plus the classes D8 refused. */
    class Recompiled(val byMode: Map<String, Map<String, DexMethod>>, val rejected: Set<String>, val indexes: Map<String, MethodIndex>) {
        val empty: Boolean get() = byMode.values.all { it.isEmpty() }
    }

    fun recompile(classesDir: Path, classFiles: List<Path>, libraryJars: List<Path>, minApi: Int, workDir: Path, timeoutMs: Long): Recompiled {
        val byMode = HashMap<String, Map<String, DexMethod>>()
        val indexes = HashMap<String, MethodIndex>()
        val rejected = HashSet<String>()
        for (mode in modes) {
            val (dexes, modeRejected) = dexInChunks(classesDir, classFiles, libraryJars, minApi, mode == "debug", workDir.resolve(mode), timeoutMs / modes.size)
            rejected += modeRejected
            val map = HashMap<String, DexMethod>()
            val files = ArrayList<DexFile>()
            for (f in dexes) {
                val d = (DexFile.parse(Files.readAllBytes(f)) as? Outcome.Ok)?.value ?: continue
                files += d
                for (m in d.methods()) if (m.code != null) map[backKey(m)] = m
            }
            byMode[mode] = map
            indexes[mode] = MethodIndex(files)
        }
        return Recompiled(byMode, rejected, indexes)
    }

    /** Compares every original method of [originalByClass] with its recompiled counterpart. */
    fun compare(originalByClass: Map<String, Map<String, DexMethod>>, recompiled: Recompiled, diagnostics: Map<String, FileDiagnostics>): List<MethodRoundTrip> {
        // Anonymous classes are numbered by position in the source file; a decompiler can emit them in
        // another order, so they are matched by structure and dataflow, not by number.
        val ownerMaps = modes.associateWith { mode ->
            val byClass = HashMap<String, HashMap<String, DexMethod>>()
            recompiled.byMode[mode].orEmpty().values.forEach { m -> backKey(m).let { k -> byClass.getOrPut(k.substringBefore("->")) { HashMap() }[k.substringAfter("->")] = m } }
            AnonymousClassMatcher.match(originalByClass, byClass) { a, b -> shapeOf(a, originalIndex)?.let { sa -> recompiledShape(b, recompiled.indexes[mode])?.let { sb -> sa.compare(sb) } } }
        }
        val results = ArrayList<MethodRoundTrip>()
        for ((owner, methods) in originalByClass) for ((sig, m) in methods) {
            val top = fileOf(owner)
            val key = "$owner->$sig"
            val fileErrors = diagnostics[top]?.errors ?: 0
            val candidates = modes.mapNotNull { mode ->
                val recOwner = ownerMaps.getValue(mode)[owner] ?: owner
                val renamed = renames?.recompiledMethodName(names.recovered(recOwner), m.name)?.let { "$recOwner->$it${m.protoDescriptor}" }
                (recompiled.byMode[mode]?.get("$recOwner->$sig") ?: renamed?.let { recompiled.byMode[mode]?.get(it) })?.let { mode to it }
            }
            if (candidates.isEmpty() || candidates.all { stubbed(it.second) }) {
                val result = when {
                    candidates.isEmpty() && isSynthetic(m) -> "SYNTHETIC"
                    candidates.isEmpty() && fileErrors == 0 && owner !in recompiled.rejected -> "NOT_FOUND"
                    else -> "NOT_COMPILED"
                }
                results += MethodRoundTrip(key, result, top)
                continue
            }
            val a = shapeOf(m, originalIndex)
            val best = candidates.filterNot { stubbed(it.second) }.mapNotNull { (mode, c) ->
                val classRenames = ownerMaps.getValue(mode).filter { (o, r) -> o != r && fileOf(o) == top }.entries.associate { (o, r) -> r to o }
                recompiledShape(c, recompiled.indexes[mode])?.renamed(classRenames)?.let { b -> a?.compare(b)?.let { mode to it } }
            }.minWithOrNull(compareBy({ it.second.match.ordinal }, { -it.second.similarity }))
            results += if (best == null) MethodRoundTrip(key, "NOT_FOUND", top)
            else MethodRoundTrip(key, best.second.match.name, top, best.first, if (best.second.match == RoundTripMatch.DIFFERENT) best.second.similarity else null)
        }
        return results
    }

    fun verify(
        original: List<Pair<String, DexFile>>,
        classesDir: Path,
        libraryJars: List<Path>,
        diagnostics: Map<String, FileDiagnostics>,
        recoveredFiles: Set<String>,
        appPackages: List<String>,
        minApi: Int,
        workDir: Path,
        timeoutMs: Long,
    ): Pair<RoundTripSummary, List<MethodRoundTrip>> {
        val started = System.nanoTime()
        val classFiles = Files.walk(classesDir).use { s -> s.filter { it.isRegularFile() && it.extension == "class" }.toList() }
        if (classFiles.isEmpty()) return RoundTripSummary("NOT_RUN", "no class files to verify", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) to emptyList()
        val recompiled = recompile(classesDir, classFiles, libraryJars, minApi, workDir, timeoutMs)
        if (recompiled.empty) {
            return RoundTripSummary("UNKNOWN", "D8 could not dex any recompiled class; see d8.log", 0, 0, 0, 0, 0, 0, 0, 0, 0, (System.nanoTime() - started) / 1_000_000) to emptyList()
        }
        val results = compare(originalByClass(original, recoveredFiles), recompiled, diagnostics)
        val note = "recovered source -> javac/ECJ -> D8 ${profile.profile.d8.version} (release and debug) -> dataflow fingerprint compared with the original method" +
            if (recompiled.rejected.isNotEmpty()) "; D8 rejected ${recompiled.rejected.size} recompiled classes (counted as not compiled)" else ""
        return summarize(results, appPackages, note, (System.nanoTime() - started) / 1_000_000) to results
    }

    companion object {
        fun topLevelFile(owner: String) = owner.removePrefix("L").removeSuffix(";").substringBefore('$') + ".java"

        val EQUIVALENT = setOf(RoundTripMatch.EQUIVALENT_DATAFLOW.name, RoundTripMatch.EQUIVALENT_MODULO_LAYOUT.name)

        fun summarize(results: List<MethodRoundTrip>, appPackages: List<String>, note: String, elapsedMs: Long): RoundTripSummary {
            fun count(r: String) = results.count { it.result == r }
            val compared = results.filter { it.result != "SYNTHETIC" }
            val app = compared.filter { QualityScanner.origin(it.file ?: "", appPackages) == "app" }
            return RoundTripSummary(
                status = "MEASURED",
                note = note,
                methods = compared.size,
                equivalentDataflow = count(RoundTripMatch.EQUIVALENT_DATAFLOW.name),
                equivalentModuloLayout = count(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT.name),
                similarOperations = count(RoundTripMatch.SIMILAR_OPERATIONS.name),
                different = count(RoundTripMatch.DIFFERENT.name),
                notCompiled = count("NOT_COMPILED"),
                notFound = count("NOT_FOUND"),
                appMethods = app.size,
                appEquivalent = app.count { it.result in EQUIVALENT },
                elapsedMs = elapsedMs,
                syntheticNotCompared = count("SYNTHETIC"),
            )
        }
    }

    /**
     * Dexes the recompiled classes in package-ordered chunks. One class D8 refuses (for example a
     * decompiled class with a duplicated annotation) must not cost the verification of every other
     * class, so a failing chunk drops the class D8 names and retries, and an unattributed failure
     * is bisected.
     */
    private fun dexInChunks(classesDir: Path, classFiles: List<Path>, libraryJars: List<Path>, minApi: Int, debug: Boolean, workDir: Path, timeoutMs: Long): Pair<List<Path>, Set<String>> {
        val log = StringBuilder()
        val rejected = HashSet<String>()
        val outputs = ArrayList<Path>()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var chunkNo = 0
        fun descriptor(f: Path) = "L" + classesDir.relativize(f).toString().replace('\\', '/').removeSuffix(".class") + ";"
        fun run(files: List<Path>): Pair<Boolean, String> {
            val out = Files.createDirectories(workDir.resolve("dex/${chunkNo++}"))
            val list = workDir.resolve("classes-$chunkNo.txt")
            Files.write(list, files.map { it.toAbsolutePath().toString() })
            val argv = buildList {
                add(profile.java.toString()); add("-Xmx3g"); add("-cp"); add(profile.d8Jar.toString()); add("com.android.tools.r8.D8")
                add(if (debug) "--debug" else "--release"); add("--min-api"); add(minApi.coerceAtLeast(21).toString())
                add("--lib"); add(profile.androidJar.toString())
                for (j in libraryJars) { add("--classpath"); add(j.toString()) }
                add("--output"); add(out.toString())
                add("@$list")
            }
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(10_000)
            val r = ProcessWorker.run(argv, workDir, remaining, maxLogBytes = 4 shl 20, cancel = cancel)
            if (r.cancelled) throw CancelledException()
            val text = r.stdout + r.stderr
            log.append("## chunk ").append(chunkNo - 1).append(" (").append(files.size).append(" classes): ").append(if (r.ok) "ok" else "failed").append('\n')
            if (!r.ok) log.append(text.lines().filterNot { it.startsWith("Warning") || it.startsWith("\tat ") || it.startsWith("Picked up") }.take(20).joinToString("\n")).append('\n')
            if (r.ok) Files.list(out).use { s -> s.filter { it.fileName.toString().endsWith(".dex") }.sorted().toList() }.let { outputs.addAll(it) }
            return r.ok to text
        }
        fun dex(files: List<Path>, depth: Int) {
            var pending = files
            var attempts = 0
            while (pending.isNotEmpty() && System.nanoTime() < deadline) {
                val (ok, text) = run(pending)
                if (ok) return
                val named = Regex("""Error in (.+?\.class)""").findAll(text).map { Path.of(it.groupValues[1].trim()).toAbsolutePath() }.toSet()
                val drop = pending.filter { it.toAbsolutePath() in named }
                if (drop.isNotEmpty() && attempts++ < 25) {
                    drop.forEach { rejected += descriptor(it) }
                    pending = pending - drop.toSet()
                    continue
                }
                // A failure no class in the batch is blamed for (a bad classpath entry, a crash) is not
                // narrowed down by splitting the batch; nor are batches beyond a few halvings.
                val environmental = text.contains("NoSuchFileException") || text.contains("OutOfMemoryError")
                if (pending.size == 1 || depth >= 4 || environmental) {
                    pending.forEach { rejected += descriptor(it) }
                    return
                }
                dex(pending.subList(0, pending.size / 2), depth + 1)
                dex(pending.subList(pending.size / 2, pending.size), depth + 1)
                return
            }
        }
        val sorted = classFiles.sortedBy { classesDir.relativize(it).toString() }
        for (chunk in sorted.chunked(600)) dex(chunk, 0)
        Files.writeString(workDir.resolve("d8.log"), log)
        return outputs to rejected
    }

    /** Methods a compiler generates rather than a programmer writes; their names and shapes differ between toolchains. */
    private fun isSynthetic(m: DexMethod): Boolean =
        m.accessFlags and (AccessFlags.SYNTHETIC or AccessFlags.BRIDGE) != 0 ||
            m.name.startsWith("-$$") || m.name.startsWith("access$") || m.name.startsWith("lambda$") || m.name == "\$values" ||
            m.owner.contains("$$") || m.owner.contains("-$$")

    private fun stubbed(m: DexMethod): Boolean = m.dex.strings.any { it.startsWith("Unresolved compilation problem") } &&
        (InstructionDecoder.decode(m.dex, m.code!!) as? Outcome.Ok)?.value?.insns?.any { i ->
            i.op.mnemonic.startsWith("const-string") && m.dex.string(i.index).startsWith("Unresolved compilation problem")
        } == true
}

/** Pairs anonymous classes of the original with their recompiled counterparts. */
object AnonymousClassMatcher {
    private val ANON = Regex("""^(L.+)\$(\d+);$""")

    private fun ownSignatures(methods: Map<String, DexMethod>) =
        methods.keys.filterNot { it.startsWith("access$") || it.startsWith("-$$") || it.startsWith("lambda$") }.toSet()

    /**
     * original owner -> recompiled owner for every anonymous class with a structural match: the same
     * enclosing class (itself matched first) and the same declared methods. Among several candidates
     * the one whose methods round-trip best wins; on a tie the same number is kept.
     */
    fun match(
        original: Map<String, Map<String, DexMethod>>,
        recompiled: Map<String, Map<String, DexMethod>>,
        compare: (DexMethod, DexMethod) -> RoundTripComparison?,
    ): Map<String, String> {
        val result = HashMap<String, String>()
        val groups = original.keys.mapNotNull { o -> ANON.find(o)?.let { it.groupValues[1] to o } }.groupBy({ it.first }, { it.second })
        val recByParent = recompiled.keys.mapNotNull { r -> ANON.find(r)?.let { it.groupValues[1] to r } }.groupBy({ it.first }, { it.second })
        for ((parent, members) in groups.entries.sortedBy { it.key.count { c -> c == '$' } }) {
            val recParent = result["$parent;"]?.removeSuffix(";") ?: parent
            val pool = recByParent[recParent].orEmpty().toMutableSet()
            for (o in members.sortedBy { ANON.find(it)!!.groupValues[2].toInt() }) {
                val sigs = ownSignatures(original.getValue(o))
                val number = ANON.find(o)!!.groupValues[2]
                val best = pool.filter { ownSignatures(recompiled.getValue(it)) == sigs }.maxWithOrNull(
                    compareBy<String>({ r ->
                        sigs.sumOf { sig ->
                            val a = original.getValue(o)[sig]; val b = recompiled.getValue(r)[sig]
                            if (a == null || b == null) 0 else compare(a, b)?.let { RoundTripMatch.entries.size - it.match.ordinal } ?: 0
                        }
                    }, { r -> if (ANON.find(r)!!.groupValues[2] == number) 1 else 0 }),
                ) ?: continue
                result[o] = best
                pool -= best
            }
        }
        return result
    }
}

/**
 * Recompiles the files ECJ accepted with javac, the compiler Android builds use, and overlays the
 * result on the ECJ output. ECJ lays out loops and string concatenation differently from javac,
 * so without this the round trip would measure compiler differences instead of decompiler errors.
 * Files javac rejects keep their ECJ classes.
 */
class JavacOverlay(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    data class Result(val javacFiles: List<String>, val rejected: List<String>, val note: String)

    fun apply(javaRoot: Path, files: List<String>, classesDir: Path, libraryJars: List<Path>, workDir: Path, timeoutMs: Long): Result {
        val android = profile.androidJar
        if (files.isEmpty() || android == null || !Files.isExecutable(profile.javac)) return Result(emptyList(), emptyList(), "javac or android.jar unavailable")
        Files.createDirectories(workDir)
        val out = workDir.resolve("javac-classes")
        val root = javaRoot.toAbsolutePath()
        var pending = files.sorted()
        val rejected = ArrayList<String>()
        for (attempt in 0 until 4) {
            if (pending.isEmpty()) break
            AtomicFiles.deleteRecursively(out)
            Files.createDirectories(out)
            val list = workDir.resolve("javac-sources.txt")
            Files.write(list, pending.map { root.resolve(it).toString() })
            val argv = listOf(
                profile.javac.toString(), "-J-Xmx3g", "-source", "8", "-target", "8", "-bootclasspath", android.toString(),
                "-cp", (listOf(classesDir) + libraryJars).joinToString(File.pathSeparator), "-g", "-proc:none", "-nowarn",
                "-encoding", "UTF-8", "-Xmaxerrs", "1000000", "-d", out.toString(), "@$list",
            )
            val r = ProcessWorker.run(argv, workDir, timeoutMs, maxLogBytes = 16 shl 20, cancel = cancel)
            if (r.cancelled) throw CancelledException()
            Files.writeString(workDir.resolve("javac-$attempt.log"), r.stdout + r.stderr)
            if (r.ok) {
                overlay(out, pending, classesDir)
                return Result(pending, rejected.sorted(), "javac ${profile.profile.javac.release} -source 8 -target 8")
            }
            val bad = Regex("""(?m)^(.+?\.java):\d+: error:""").findAll(r.stdout + r.stderr)
                .map { runCatching { root.relativize(Path.of(it.groupValues[1]).toAbsolutePath()).toString().replace('\\', '/') }.getOrDefault(it.groupValues[1]) }
                .filter { it in pending }.toSet()
            if (bad.isEmpty()) return Result(emptyList(), rejected.sorted(), "javac failed without per-file errors (exit ${r.exitCode}${if (r.timedOut) ", timed out" else ""})")
            rejected += bad
            pending = pending.filterNot { it in bad }
        }
        return Result(emptyList(), rejected.sorted(), "javac still rejected files after 4 attempts")
    }

    /** Replaces each recompiled file's ECJ classes (the top-level class and its nested classes) with javac's. */
    private fun overlay(javacOut: Path, files: List<String>, classesDir: Path) {
        for (rel in files) {
            val base = rel.removeSuffix(".java")
            val dir = classesDir.resolve(base).parent ?: classesDir
            val simple = base.substringAfterLast('/')
            if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.filter { f -> f.fileName.toString().let { it == "$simple.class" || it.startsWith("$simple$") } }.toList() }.forEach { Files.delete(it) }
        }
        Files.walk(javacOut).use { s -> s.filter { it.isRegularFile() }.toList() }.forEach { f ->
            val target = classesDir.resolve(javacOut.relativize(f).toString())
            Files.createDirectories(target.parent)
            Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Removes files that are toolchain artifacts rather than application source. */
object ArtifactCleaner {
    /**
     * D8 synthesizes stub classes for platform types newer than the app's minimum API
     * (`synthetic interface X { static { throw new NoClassDefFoundError(); } }`). They duplicate
     * android.jar classes and were never part of anyone's source.
     */
    fun removePlatformStubs(javaRoot: Path): List<String> {
        if (!Files.isDirectory(javaRoot)) return emptyList()
        val removed = ArrayList<String>()
        val stub = Regex("""/\*\s*synthetic\s*\*/\s*(?:abstract\s+|final\s+)*(?:class|interface)\b[\s\S]*?static\s*\{\s*throw\s+new\s+NoClassDefFoundError\(\);\s*}""")
        Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.toList() }.forEach { f ->
            val rel = javaRoot.relativize(f).toString().replace('\\', '/')
            if (!(rel.startsWith("android/") || rel.startsWith("java/") || rel.startsWith("javax/") || rel.startsWith("dalvik/"))) return@forEach
            val text = Files.readString(f)
            if (stub.containsMatchIn(text) && text.length < 4_000) {
                Files.delete(f)
                removed += rel
            }
        }
        return removed.sorted()
    }

    /**
     * D8 desugaring helpers (`com.android.tools.r8.*`, e.g. RecordTag, DesugarVarHandle) are
     * generated by the toolchain. Once nothing left in the project refers to them, they go.
     */
    fun removeUnreferencedToolchainClasses(javaRoot: Path): List<String> {
        val dir = javaRoot.resolve("com/android/tools/r8")
        if (!Files.isDirectory(dir)) return emptyList()
        val referenced = Files.walk(javaRoot).use { s ->
            s.filter { it.isRegularFile() && it.extension == "java" && !it.startsWith(dir) }.anyMatch { Files.readString(it).contains("com.android.tools.r8") }
        }
        if (referenced) return emptyList()
        val files = Files.walk(dir).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.toList() }
        files.forEach { Files.delete(it) }
        return files.map { javaRoot.relativize(it).toString().replace('\\', '/') }.sorted()
    }
}
