package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.ResolvedProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ReaPackageFacts(
    val evidenceId: String?,
    val engine: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val permissions: List<String>,
    val classCount: Int?,
    val resourceCount: Int?,
)

@Serializable
data class ReaRun(val command: String, val status: String, val evidenceFile: String?, val detail: String)

@Serializable
data class JsRecovery(val bundle: String, val status: String, val outputDirectory: String?, val modules: Int, val detail: String)

/**
 * REA (Reverse Engineer Anything) adapter. REA wraps pinned engines and returns Evidence records
 * bound to input and engine hashes; those records are stored next to our own report for comparison.
 * Shared underlying engines do not make the observations independent or prove recovery correctness.
 */
class ReaEvidence(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    fun available(): Boolean = profile.reaExecutable?.let { Files.isExecutable(it) } == true && profile.reaEngineJar?.let { Files.isRegularFile(it) } == true

    private fun env(): Map<String, String> = buildMap {
        put("REA_JADX_MCP_JAR", profile.reaEngineJar.toString())
        put("JAVA_HOME", profile.jdkHome.toString())
        put("PATH", listOfNotNull(profile.jdkHome.resolve("bin").toString(), nodeBinDir(), System.getenv("PATH")).joinToString(java.io.File.pathSeparator))
        profile.wakaru?.let { put("REA_WAKARU_COMMAND", it.toString()) }
    }

    private fun nodeBinDir(): String? = System.getenv("PATH")?.split(java.io.File.pathSeparator)?.firstOrNull { Files.isExecutable(Path.of(it, "node")) }

    private fun run(args: List<String>, workDir: Path): dev.apkproject.compiler.ProcessOutcome {
        val spec = profile.profile.rea!!
        val r = ProcessWorker.run(listOf(profile.reaExecutable.toString()) + args + listOf("--format", "json"), workDir, spec.timeoutMs, maxLogBytes = 64 shl 20, extraEnv = env(), cancel = cancel)
        if (r.cancelled) throw CancelledException()
        return r
    }

    /** `rea inspect-android-package`: decoded manifest and declarations, as a REA Evidence record. */
    fun inspectPackage(apk: Path, outDir: Path, workDir: Path): Pair<ReaRun, ReaPackageFacts?> {
        if (!available()) return ReaRun("inspect-android-package", "NOT_RUN", null, "REA or its engine is not installed (scripts/setup-toolchain.sh --full)") to null
        if (Hashing.sha256(profile.reaEngineJar!!) != profile.profile.rea!!.engineSha256) {
            return ReaRun("inspect-android-package", "NOT_RUN", null, "REA engine JAR does not match the pinned SHA-256") to null
        }
        Files.createDirectories(outDir)
        val r = run(listOf("inspect-android-package", apk.toAbsolutePath().toString()), workDir)
        val file = outDir.resolve("inspect-android-package.json")
        Files.writeString(file, r.stdout)
        if (!r.ok) return ReaRun("inspect-android-package", "FAILED", file.fileName.toString(), (r.stderr + r.stdout).take(1000)) to null
        return try {
            val root = Json.parseToJsonElement(r.stdout).jsonObject
            val n = root["normalized_result"]?.jsonObject
            fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content
            val facts = ReaPackageFacts(
                evidenceId = str(root, "evidence_id"),
                engine = n?.get("engine")?.jsonObject?.let { "${str(it, "name")} ${str(it, "version")}" },
                packageName = str(n, "package_name"),
                versionName = str(n, "version_name"),
                versionCode = str(n, "version_code"),
                minSdk = str(n, "min_sdk"),
                targetSdk = str(n, "target_sdk"),
                permissions = (n?.get("permissions") as? JsonArray)?.map { p -> (p as? JsonPrimitive)?.content ?: (p as? JsonObject)?.let { str(it, "name") } ?: p.toString() } ?: emptyList(),
                classCount = str(n, "class_count")?.toIntOrNull(),
                resourceCount = str(n, "resource_count")?.toIntOrNull(),
            )
            ReaRun("inspect-android-package", "PASS", file.fileName.toString(), "evidence ${facts.evidenceId}") to facts
        } catch (e: Exception) {
            ReaRun("inspect-android-package", "FAILED", file.fileName.toString(), "unreadable REA output: ${e.message}") to null
        }
    }

    /** `rea recover-javascript-sources`: Wakaru module recovery for a plain-text JavaScript bundle. */
    fun recoverJavaScript(bundle: Path, label: String, outDir: Path, evidenceDir: Path, workDir: Path): JsRecovery {
        val wakaru = profile.wakaru
        if (!available() || wakaru == null || !Files.isExecutable(wakaru)) {
            return JsRecovery(label, "NOT_RUN", null, 0, "REA with Wakaru is not installed (scripts/setup-toolchain.sh --full)")
        }
        val sha = profile.profile.rea?.wakaruSha256
        if (sha != null && Hashing.sha256(wakaru) != sha) return JsRecovery(label, "NOT_RUN", null, 0, "Wakaru binary does not match the pinned SHA-256")
        Files.createDirectories(evidenceDir)
        Files.createDirectories(outDir.parent)
        val r = run(listOf("recover-javascript-sources", bundle.toAbsolutePath().toString(), outDir.toAbsolutePath().toString()), workDir)
        val evidence = evidenceDir.resolve("recover-javascript-sources-${label.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
        Files.writeString(evidence, r.stdout)
        if (!r.ok) return JsRecovery(label, "FAILED", null, 0, (r.stderr + r.stdout).take(600))
        val modules = runCatching {
            Json.parseToJsonElement(r.stdout).jsonObject["normalized_result"]?.jsonObject?.get("modules")?.jsonArray?.size ?: 0
        }.getOrDefault(0)
        return JsRecovery(label, "PASS", outDir.fileName.toString(), modules, "recovered by Wakaru through REA; runtime equivalence is not established")
    }
}
