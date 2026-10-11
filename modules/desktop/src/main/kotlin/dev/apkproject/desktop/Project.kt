package dev.apkproject.desktop

import dev.apkproject.app.ApiContract
import dev.apkproject.app.FileDiagnostics
import dev.apkproject.app.FullRecoveryReport
import dev.apkproject.app.FrontendReport
import dev.apkproject.app.MethodRoundTrip
import dev.apkproject.app.NameOracle
import dev.apkproject.app.PrivacyReport
import dev.apkproject.app.RoundTripVerifier
import dev.apkproject.app.ScreenMapReport
import dev.apkproject.app.SupplyChainReport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.nio.file.Files
import java.nio.file.Path

/** What the round trip says about one source file. */
data class FileVerdict(val methods: Int, val equivalent: Int, val different: Int, val notCompiled: Int, val compileErrors: Int, val firstErrors: List<String>) {
    enum class Kind { VERIFIED, PARTIAL, NOT_COMPILING, UNCHECKED }

    val kind: Kind
        get() = when {
            compileErrors > 0 -> Kind.NOT_COMPILING
            methods == 0 -> Kind.UNCHECKED
            equivalent == methods -> Kind.VERIFIED
            else -> Kind.PARTIAL
        }

    fun describe(): String = when (kind) {
        Kind.NOT_COMPILING -> "Does not compile: $compileErrors error${if (compileErrors == 1) "" else "s"}" + if (methods > 0) "; $equivalent of $methods methods still verified" else ""
        Kind.UNCHECKED -> "No methods compared"
        Kind.VERIFIED -> "All $methods method${if (methods == 1) "" else "s"} match the original bytecode"
        Kind.PARTIAL -> "$equivalent of $methods methods match the original bytecode" + if (different > 0) "; $different differ" else ""
    }
}

/** A recovered project folder, as `decompile` wrote it. Missing parts load as empty. */
class Project(val root: Path) {
    val javaRoot: Path = root.resolve("app/src/main/java")
    val report: FullRecoveryReport? = read("recovery/report.json", FullRecoveryReport.serializer())
    val contract: ApiContract? = read("backend/api-contract.json", ApiContract.serializer())
    val frontend: FrontendReport? = read("audit/ui-audit.json", FrontendReport.serializer()) ?: report?.frontend
    val screenMap: ScreenMapReport? = read("audit/screen-map.json", ScreenMapReport.serializer())
    val privacy: PrivacyReport? = read("audit/privacy.json", PrivacyReport.serializer())
    val supplyChain: SupplyChainReport? = read("audit/supply-chain.json", SupplyChainReport.serializer())
    val sbomFile: Path = root.resolve("audit/sbom.cdx.json")
    val names: List<NameOracle.Name> = read("recovery/recovered-names.json", ListSerializer(NameOracle.Name.serializer())).orEmpty()
    val verdicts: Map<String, FileVerdict> = verdicts()
    val reportMarkdown: String? = root.resolve("recovery/RECOVERY.md").takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
    val skeleton: Path = root.resolve("backend/server-skeleton/BackendSkeleton.java")
    // Gather filesystem metadata while the project is loading, before Swing builds its tree.
    val sourceFolders: List<Path> = directories(javaRoot)
    val resourceFolders: List<Path> = directories(root.resolve("app/src/main/res"))

    val title: String
        get() = report?.manifest?.packageName ?: root.fileName.toString()

    val subtitle: String
        get() = report?.let { r ->
            listOfNotNull(
                r.manifest.versionName?.let { "version $it" },
                r.originalToolchain.firstOrNull()?.toString(),
                "recovered in ${Ui.duration(r.elapsedMs)}",
            ).joinToString(" · ")
        } ?: root.toString()

    private fun <T> read(relative: String, serializer: KSerializer<T>): T? {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val file = root.resolve(relative)
        if (!Files.isRegularFile(file)) return null
        return runCatching { LENIENT.decodeFromString(serializer, Files.readString(file)) }.getOrNull()
    }

    private fun verdicts(): Map<String, FileVerdict> {
        val counts = methodCounts()
        val diagnostics = read("recovery/compile-diagnostics.json", ListSerializer(FileDiagnostics.serializer())).orEmpty().associateBy { it.path }
        val relaxed = root.resolve("recovery/javac-relaxed.txt").takeIf { Files.isRegularFile(it) }?.let { Files.readAllLines(it).toSet() }.orEmpty()
        val out = HashMap<String, FileVerdict>()
        for ((file, ms) in counts) {
            val d = diagnostics[file]?.takeIf { file !in relaxed }
            out[file] = FileVerdict(
                ms[0], ms[1], ms[2], ms[3], d?.errors ?: 0, d?.firstErrors.orEmpty(),
            )
        }
        for ((file, d) in diagnostics) if (file !in out && file !in relaxed) out[file] = FileVerdict(0, 0, 0, 0, d.errors, d.firstErrors)
        return out
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun methodCounts(): Map<String, IntArray> {
        val file = root.resolve("recovery/round-trip.json")
        if (!Files.isRegularFile(file)) return emptyMap()
        // Keep four counters per file, not the JSON string and every method plus grouped lists.
        // A malformed report discards the whole result, so partial input cannot claim verification.
        return runCatching {
            val counts = HashMap<String, IntArray>()
            Files.newInputStream(file).use { input ->
                for (m in LENIENT.decodeToSequence(input, MethodRoundTrip.serializer())) {
                    val path = m.file ?: continue
                    if (m.result == "SYNTHETIC") continue
                    val c = counts.getOrPut(path) { IntArray(4) }
                    c[0]++
                    if (m.result in RoundTripVerifier.EQUIVALENT) c[1]++
                    if (m.result == "DIFFERENT" || m.result == "SIMILAR_OPERATIONS") c[2]++
                    if (m.result == "NOT_COMPILED") c[3]++
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                }
            }
            counts
        }.getOrDefault(emptyMap())
    }

    private fun directories(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.find(root, Int.MAX_VALUE, { path, attributes ->
            path != root && (attributes.isDirectory || attributes.isSymbolicLink && Files.isDirectory(path))
        })
            .use { paths -> paths.peek { if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException() }.sorted().toList() }
    }

    companion object {
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        fun isProject(dir: Path): Boolean = Files.isRegularFile(dir.resolve("recovery/report.json"))
    }
}
