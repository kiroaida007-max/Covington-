package dev.apkproject.compiler

import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.ResolvedProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Section 18 request record: everything that determines a compilation's output. */
data class CompilerRequest(
    val sourceBundleHash: String,
    val toolchainProfileHash: String,
    val explicitClasspathHashes: List<String>,
    val targetLanguageAndBytecodeLevel: String,
    val sdkAndMinApi: String,
    val timeoutMs: Long,
) {
    /** Cache key: profile labels alone are never enough, so every input hash participates. */
    val cacheKey: String
        get() = Hashing.combine(sourceBundleHash, toolchainProfileHash, explicitClasspathHashes.joinToString(","), targetLanguageAndBytecodeLevel, sdkAndMinApi)
}

data class CompilerResult(
    val request: CompilerRequest,
    val status: CheckStatus,
    val diagnostics: String,
    val classesDir: Path,
    val classFiles: List<Path>,
    val dexFile: Path?,
    val argvManifest: List<List<String>>,
    val environmentHash: String,
    val elapsedMs: Long,
)

/**
 * javac + D8 worker for one source bundle. The candidate is compiled from source alone against
 * the profile's platform library; it never sees the original application DEX.
 */
class ForwardCompiler(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    /** [classpath] is visible to javac only (e.g. android.jar for constants); D8 sees just the candidate classes. */
    fun compile(sources: Map<String, String>, workDir: Path, timeoutMs: Long, withDex: Boolean = true, classpath: List<Path> = emptyList()): CompilerResult {
        val bundleHash = Hashing.combine(*sources.toSortedMap().flatMap { listOf(it.key, it.value) }.toTypedArray())
        val request = CompilerRequest(
            sourceBundleHash = bundleHash,
            toolchainProfileHash = profile.profileHash,
            explicitClasspathHashes = classpath.map { it.toString() },
            targetLanguageAndBytecodeLevel = "java ${profile.profile.javac.release}",
            sdkAndMinApi = "lib=${profile.d8Lib.fileName} minApi=${profile.profile.d8.minApi}",
            timeoutMs = timeoutMs,
        )
        val src = workDir.resolve("src")
        val classes = workDir.resolve("classes")
        val dexOut = workDir.resolve("dex")
        listOf(src, classes, dexOut).forEach { Files.createDirectories(it) }
        val files = sources.map { (rel, text) ->
            require(!rel.contains("..") && !rel.startsWith("/")) { "bad source path $rel" }
            src.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        }
        val javacArgv = listOf(profile.javac.toString(), "--release", profile.profile.javac.release.toString()) +
            profile.profile.javac.flags +
            (if (classpath.isEmpty()) emptyList() else listOf("-cp", classpath.joinToString(java.io.File.pathSeparator))) +
            listOf("-d", classes.toString()) + files.map { it.toString() }
        val javac = ProcessWorker.run(javacArgv, workDir, timeoutMs, cancel = cancel)
        if (javac.cancelled) throw CancelledException()
        val argv = mutableListOf(javacArgv)
        val classFiles = Files.walk(classes).use { s -> s.filter { it.isRegularFile() && it.extension == "class" }.sorted().toList() }
        if (!javac.ok || classFiles.isEmpty()) {
            return CompilerResult(request, CheckStatus.FAIL, "javac failed (exit ${javac.exitCode}, timedOut=${javac.timedOut}):\n${javac.stderr}${javac.stdout}", classes, classFiles, null, argv, javac.environmentHash, javac.elapsedMs)
        }
        if (!withDex) return CompilerResult(request, CheckStatus.PASS, javac.stderr, classes, classFiles, null, argv, javac.environmentHash, javac.elapsedMs)
        val d8Argv = listOf(
            profile.java.toString(), "-cp", profile.d8Jar.toString(), "com.android.tools.r8.D8",
            "--${profile.profile.d8.mode}", "--min-api", profile.profile.d8.minApi.toString(),
            "--lib", profile.d8Lib.toString(), "--output", dexOut.toString(),
        ) + classFiles.map { it.toString() }
        argv += d8Argv
        val d8 = ProcessWorker.run(d8Argv, workDir, timeoutMs, cancel = cancel)
        if (d8.cancelled) throw CancelledException()
        val dex = dexOut.resolve("classes.dex")
        val status = if (d8.ok && Files.isRegularFile(dex)) CheckStatus.PASS else CheckStatus.FAIL
        val diagnostics = if (status == CheckStatus.PASS) javac.stderr + d8.stderr else "D8 failed (exit ${d8.exitCode}, timedOut=${d8.timedOut}):\n${d8.stderr}${d8.stdout}"
        return CompilerResult(
            request, status, diagnostics, classes, classFiles, dex.takeIf { status == CheckStatus.PASS }, argv,
            Hashing.combine(javac.environmentHash, d8.environmentHash), javac.elapsedMs + d8.elapsedMs,
        )
    }
}
