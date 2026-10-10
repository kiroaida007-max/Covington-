package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.ResolvedProfile
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class DecompilerRun(
    val engine: String,
    val version: String,
    val jarSha256: String,
    val argv: List<String>,
    val exitCode: Int?,
    /** COMPLETE (exit 0), PARTIAL (exit 3: finished, some methods failed), FAILED otherwise. */
    val status: String,
    val elapsedMs: Long,
    val reportedErrors: Int?,
)

@Serializable
enum class FileQuality { CLEAN, WARNINGS, FAILED_METHODS }

@Serializable
data class SourceFileQuality(val path: String, val quality: FileQuality, val origin: String, val warnings: Int, val failedMethods: Int)

@Serializable
data class DecompileQuality(
    val javaFiles: Int,
    val clean: Int,
    val withWarnings: Int,
    val withFailedMethods: Int,
    val appFiles: Int,
    val libraryFiles: Int,
    val appPackages: List<String>,
    val warningKinds: Map<String, Int>,
    val files: List<SourceFileQuality>,
)

/**
 * Runs JADX, the decompiler REA uses for APKs, over the whole input and exports an Android Studio
 * Gradle project. JADX is a generator: its output is labelled DECOMPILED (unvalidated) unless the
 * cross-check proves individual methods.
 */
class JadxDecompiler(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    fun available(): Boolean = profile.jadxJar?.let { Files.isRegularFile(it) } == true

    fun export(input: Path, outDir: Path, deobfuscate: Boolean, threads: Int, workDir: Path, mappings: Path? = null, noSource: Boolean = false): DecompilerRun {
        val spec = profile.profile.jadx ?: error("profile ${profile.profile.id} has no jadx section")
        val jar = profile.jadxJar!!
        val argv = buildList {
            add(profile.java.toString())
            add("-Xmx${maxHeapMb()}m")
            add("-cp"); add(jar.toString()); add("jadx.cli.JadxCLI")
            add("--export-gradle")
            addAll(if (noSource) spec.args.filter { it != "--show-bad-code" } else spec.args)
            // Resources and manifest only: for the fast front-end audit, skip Java decompilation.
            if (noSource) add("--no-src")
            if (deobfuscate) add("--deobf")
            if (mappings != null) { add("--mappings-path"); add(mappings.toAbsolutePath().toString()) }
            add("--threads-count"); add(threads.toString())
            add("--log-level"); add("error")
            add("--output-dir"); add(outDir.toString())
            add(input.toAbsolutePath().toString())
        }
        val r = ProcessWorker.run(argv, workDir, spec.timeoutMs, maxLogBytes = 4 shl 20, cancel = cancel)
        if (r.cancelled) throw CancelledException()
        Files.writeString(workDir.resolve("jadx.log"), r.stdout + r.stderr)
        val status = when {
            r.timedOut -> "FAILED"
            r.exitCode == 0 -> "COMPLETE"
            r.exitCode == 3 && Files.isDirectory(outDir.resolve("app/src/main")) -> "PARTIAL"
            else -> "FAILED"
        }
        val errors = Regex("""finished with errors, count: (\d+)""").find(r.stdout + r.stderr)?.groupValues?.get(1)?.toIntOrNull()
        return DecompilerRun("jadx", spec.version, spec.sha256, argv, r.exitCode, status, r.elapsedMs, errors ?: if (r.exitCode == 0) 0 else null)
    }

    private fun maxHeapMb(): Long {
        val total = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: (8L shl 30)
        return (total / (1 shl 20) / 2).coerceIn(1024, 8192)
    }
}

/** Reads JADX's own diagnostics out of the exported sources: they are honest signals of where it struggled. */
object QualityScanner {
    private val WARN = Regex("""/\* JADX WARN(?:ING)?: ([^*]+?)\s*\*/""")
    private val FAILED = listOf("Code decompiled incorrectly", "Method dump skipped", "JADX ERROR")
    private val LIBRARY_PREFIXES = listOf(
        "android/", "androidx/", "kotlin/", "kotlinx/", "com/google/", "okhttp3/", "okio/", "retrofit2/", "io/reactivex/",
        "com/squareup/", "dagger/", "javax/", "org/jetbrains/", "org/intellij/", "com/facebook/", "io/grpc/", "org/apache/",
        "com/bumptech/", "io/ktor/", "org/json/", "com/fasterxml/", "org/slf4j/", "io/sentry/", "com/android/",
    )

    fun scan(javaRoot: Path, appPackages: List<String>): DecompileQuality {
        val files = if (Files.isDirectory(javaRoot)) Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.sorted().toList() } else emptyList()
        val kinds = HashMap<String, Int>()
        val out = files.map { f ->
            val rel = javaRoot.relativize(f).toString().replace('\\', '/')
            val text = Files.readString(f)
            val warnings = WARN.findAll(text).map { it.groupValues[1].replace(Regex("""\d+"""), "#").take(80) }.toList()
            warnings.forEach { kinds.merge(it, 1, Int::plus) }
            val failed = FAILED.sumOf { marker -> Regex(Regex.escape(marker)).findAll(text).count() }
            val quality = when {
                failed > 0 -> FileQuality.FAILED_METHODS
                warnings.isNotEmpty() -> FileQuality.WARNINGS
                else -> FileQuality.CLEAN
            }
            SourceFileQuality(rel, quality, origin(rel, appPackages), warnings.size, failed)
        }
        return DecompileQuality(
            javaFiles = out.size,
            clean = out.count { it.quality == FileQuality.CLEAN },
            withWarnings = out.count { it.quality == FileQuality.WARNINGS },
            withFailedMethods = out.count { it.quality == FileQuality.FAILED_METHODS },
            appFiles = out.count { it.origin == "app" },
            libraryFiles = out.count { it.origin == "library" },
            appPackages = appPackages,
            warningKinds = kinds.entries.sortedByDescending { it.value }.take(15).associate { it.key to it.value },
            files = out,
        )
    }

    /** "app" for the application's own packages, "library" for well-known SDKs, otherwise "other". */
    fun origin(relativePath: String, appPackages: List<String>): String = when {
        appPackages.any { relativePath.startsWith(it.replace('.', '/') + "/") } -> "app"
        LIBRARY_PREFIXES.any { relativePath.startsWith(it) } -> "library"
        else -> "other"
    }

    /**
     * Application packages: the manifest package plus the packages of declared components,
     * reduced to their first three segments.
     */
    fun appPackages(manifestXml: String?): List<String> {
        if (manifestXml == null) return emptyList()
        val pkg = Regex("""\bpackage="([^"]+)"""").find(manifestXml)?.groupValues?.get(1)
        val components = Regex("""<(?:activity|activity-alias|service|receiver|provider|application)\b[^>]*?\bandroid:name="([^"]+)"""")
            .findAll(manifestXml).map { it.groupValues[1] }
            .map { if (it.startsWith(".") && pkg != null) pkg + it else it }
            .filter { '.' in it }
            .map { it.substringBeforeLast('.') }
        return (listOfNotNull(pkg) + components)
            .map { it.split('.').take(3).joinToString(".") }
            .filterNot { p -> listOf("android", "androidx", "com.google.android.gms", "com.google.firebase").any { p == it || p.startsWith("$it.") } }
            .distinct().sorted()
    }
}

@Serializable
data class CompileCheck(
    val status: String,
    val library: String,
    val files: Int,
    val filesWithErrors: Int,
    val errors: Int,
    val topErrors: Map<String, Int>,
    val elapsedMs: Long,
    val note: String,
)

/**
 * Compiles every recovered Java file against the Android platform library with javac. This measures
 * how much of the decompiled project is valid Java as-is; it does not run Android Gradle.
 */
object CompileChecker {
    private val ERROR = Regex("""^(.+\.java):(\d+): error: (.+)$""", RegexOption.MULTILINE)

    fun check(profile: ResolvedProfile, javaRoot: Path, workDir: Path, timeoutMs: Long, cancel: CancellationToken?): CompileCheck {
        val androidJar = profile.androidJar
        if (androidJar == null || !Files.isRegularFile(androidJar)) {
            return CompileCheck("NOT_RUN", "-", 0, 0, 0, emptyMap(), 0, "Android platform library not installed (scripts/setup-toolchain.sh --full)")
        }
        val files = Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.map { it.toAbsolutePath().toString() }.sorted().toList() }
        if (files.isEmpty()) return CompileCheck("NOT_RUN", androidJar.fileName.toString(), 0, 0, 0, emptyMap(), 0, "no Java files")
        Files.createDirectories(workDir)
        val argsFile = workDir.resolve("sources.txt")
        Files.write(argsFile, files.map { "\"" + it.replace("\\", "\\\\") + "\"" })
        val out = Files.createDirectories(workDir.resolve("classes"))
        val argv = listOf(
            profile.javac.toString(), "-J-Xmx3g", "-source", "8", "-target", "8", "-bootclasspath", androidJar.toString(),
            "-Xlint:-options", "-nowarn", "-proc:none", "-encoding", "UTF-8", "-Xmaxerrs", "1000000", "-d", out.toString(), "@$argsFile",
        )
        val r = ProcessWorker.run(argv, workDir, timeoutMs, maxLogBytes = 64 shl 20, cancel = cancel)
        if (r.cancelled) throw CancelledException()
        Files.writeString(workDir.resolve("javac.log"), r.stdout + r.stderr)
        if (r.timedOut) return CompileCheck("UNKNOWN", androidJar.fileName.toString(), files.size, 0, 0, emptyMap(), r.elapsedMs, "javac timed out after ${timeoutMs}ms")
        val errors = ERROR.findAll(r.stderr + r.stdout).toList()
        val byFile = errors.map { it.groupValues[1] }.toSet()
        val kinds = errors.map { it.groupValues[3].replace(Regex("""'[^']*'|"[^"]*"|\b[A-Z][\w.$]*\b"""), "_").take(70) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).associate { it.key to it.value }
        val status = if (r.exitCode == 0) "PASS" else if (errors.isEmpty()) "UNKNOWN" else "FAIL"
        val note = if (r.exitCode == 0) "every recovered Java file compiles with javac against android-${profile.profile.android?.apiLevel}" else
            "javac reports errors in ${byFile.size} of ${files.size} files; one decompilation defect can cascade into several errors"
        return CompileCheck(status, androidJar.fileName.toString(), files.size, byFile.size, errors.size, kinds, r.elapsedMs, note)
    }
}
