package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.lift.DataflowShape
import dev.apkproject.lift.RoundTripMatch
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
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
data class MethodRoundTrip(val method: String, val result: String, val file: String?)

@Serializable
data class RoundTripSummary(
    val status: String,
    val note: String,
    /** Methods with code in the recovered (non-library) classes of the original DEX. */
    val methods: Int,
    val equivalentDataflow: Int,
    val similarOperations: Int,
    val different: Int,
    val notCompiled: Int,
    val notFound: Int,
    val appMethods: Int,
    val appEquivalentDataflow: Int,
    val elapsedMs: Long,
)

/**
 * Round-trip verification of every recovered method: the decompiled source is compiled (ECJ), dexed
 * with D8, decoded with our own reader and compared with the original method by dataflow
 * fingerprint. A method that round-trips to the same dataflow is strong evidence that the recovered
 * source is faithful; it is not a semantic proof (see PROVED for that).
 */
class RoundTripVerifier(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
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
        if (classFiles.isEmpty()) return RoundTripSummary("NOT_RUN", "no class files to verify", 0, 0, 0, 0, 0, 0, 0, 0, 0) to emptyList()
        val (dexes, rejected) = dexInChunks(classesDir, classFiles, libraryJars, minApi, debug(original), workDir, timeoutMs)
        if (dexes.isEmpty()) {
            return RoundTripSummary("UNKNOWN", "D8 could not dex any recompiled class; see d8.log", 0, 0, 0, 0, 0, 0, 0, 0, (System.nanoTime() - started) / 1_000_000) to emptyList()
        }
        val recompiled = HashMap<String, DexMethod>()
        for (f in dexes) {
            val d = (DexFile.parse(Files.readAllBytes(f)) as? Outcome.Ok)?.value ?: continue
            for (m in d.methods()) if (m.code != null) recompiled["${m.owner}->${m.name}${m.protoDescriptor}"] = m
        }
        val results = ArrayList<MethodRoundTrip>()
        for ((_, dex) in original) for (m in dex.methods()) {
            val code = m.code ?: continue
            val top = m.owner.removePrefix("L").removeSuffix(";").substringBefore('$') + ".java"
            if (top !in recoveredFiles) continue // library replaced by a dependency, or removed artifact
            val key = "${m.owner}->${m.name}${m.protoDescriptor}"
            val fileErrors = diagnostics[top]?.errors ?: 0
            val candidate = recompiled[key]
            val result = when {
                candidate == null -> if (fileErrors > 0 || m.owner in rejected) "NOT_COMPILED" else "NOT_FOUND"
                stubbed(candidate) -> "NOT_COMPILED"
                else -> {
                    val a = (InstructionDecoder.decode(dex, code) as? Outcome.Ok)?.value?.let { DataflowShape.of(dex, it) }
                    val b = (InstructionDecoder.decode(candidate.dex, candidate.code!!) as? Outcome.Ok)?.value?.let { DataflowShape.of(candidate.dex, it) }
                    if (a == null || b == null) "NOT_FOUND" else a.compare(b).match.name
                }
            }
            results += MethodRoundTrip(key, result, top)
        }
        fun count(r: String) = results.count { it.result == r }
        val app = results.filter { QualityScanner.origin(it.file ?: "", appPackages) == "app" }
        val summary = RoundTripSummary(
            status = "MEASURED",
            note = "recovered source -> ECJ -> D8 ${profile.profile.d8.version} -> dataflow fingerprint compared with the original method" +
                if (rejected.isNotEmpty()) "; D8 rejected ${rejected.size} recompiled classes (counted as not compiled)" else "",
            methods = results.size,
            equivalentDataflow = count(RoundTripMatch.EQUIVALENT_DATAFLOW.name),
            similarOperations = count(RoundTripMatch.SIMILAR_OPERATIONS.name),
            different = count(RoundTripMatch.DIFFERENT.name),
            notCompiled = count("NOT_COMPILED"),
            notFound = count("NOT_FOUND"),
            appMethods = app.size,
            appEquivalentDataflow = app.count { it.result == RoundTripMatch.EQUIVALENT_DATAFLOW.name },
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
        return summary to results
    }

    /** ECJ compiles an erroneous method to a body that throws "Unresolved compilation problem". */
    /** The original was built in debug mode when most methods carry debug info; D8 then keeps locals the same way. */
    private fun debug(original: List<Pair<String, DexFile>>): Boolean {
        val codes = original.flatMap { it.second.methods() }.mapNotNull { it.code }
        return codes.isNotEmpty() && codes.count { it.debugInfoOff != 0 } * 2 > codes.size
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
                if (pending.size == 1 || depth >= 6) {
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

    private fun stubbed(m: DexMethod): Boolean = m.dex.strings.any { it.startsWith("Unresolved compilation problem") } &&
        (InstructionDecoder.decode(m.dex, m.code!!) as? Outcome.Ok)?.value?.insns?.any { i ->
            i.op.mnemonic.startsWith("const-string") && m.dex.string(i.index).startsWith("Unresolved compilation problem")
        } == true
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
