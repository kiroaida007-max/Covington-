package dev.apkproject.desktop

import dev.apkproject.app.ApiContract
import dev.apkproject.app.ContractAudit
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
class Project(selectedRoot: Path) {
    internal val files = ProjectFiles(selectedRoot)
    val root: Path = files.root
    private val reader = ReportReader(root, files = files)
    internal val reportStatuses get() = reader.statuses.values.toList()
    internal val exportedHashes: Map<String, String> = exportedHashes().also { reader.expectedHashes = it }
    val javaRoot: Path = root.resolve("app/src/main/java")
    val report: FullRecoveryReport? = read("recovery/report.json", FullRecoveryReport.serializer())
    val contract: ApiContract? = read("backend/api-contract.json", ApiContract.serializer())
    val embedded: dev.apkproject.app.EmbeddedBackendReport? = read("backend/embedded/report.json", dev.apkproject.app.EmbeddedBackendReport.serializer())
    val contractAudit: ContractAudit? = read("backend/contract-audit.json", ContractAudit.serializer())?.takeIf {
        (contract != null && it.contractSha256 == dev.apkproject.contracts.Hashing.sha256(dev.apkproject.contracts.Json.encodeLine(contract))).also { valid -> if (!valid) reader.status("backend/contract-audit.json", ReportReader.State.STALE, "Audit does not match this contract") }
    }
    val frontend: FrontendReport? = read("audit/ui-audit.json", FrontendReport.serializer()) ?: report?.frontend?.takeIf {
        reader.statuses["audit/ui-audit.json"]?.state == ReportReader.State.MISSING
    }?.also {
        reader.status("audit/ui-audit.json", ReportReader.State.MISSING, "Standalone report absent; UI & Risk uses the frontend section in recovery/report.json")
    }
    val screenMap: ScreenMapReport? = read("audit/screen-map.json", ScreenMapReport.serializer())
    val privacy: PrivacyReport? = read("audit/privacy.json", PrivacyReport.serializer())
    val supplyChain: SupplyChainReport? = read("audit/supply-chain.json", SupplyChainReport.serializer())
    val sbomFile: Path = root.resolve("audit/sbom.cdx.json")
    val names: List<NameOracle.Name> = read("recovery/recovered-names.json", ListSerializer(NameOracle.Name.serializer())).orEmpty()
    val verdicts: Map<String, FileVerdict> = verdicts()
    val reportMarkdown: String? = reader.text("recovery/RECOVERY.md", 4 * 1024 * 1024)
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

    private fun <T> read(relative: String, serializer: KSerializer<T>): T? = reader.read(relative, serializer)

    private fun exportedHashes(): Map<String, String> {
        val path = "recovery/files.sha256"
        val text = reader.text(path) ?: return emptyMap()
        return try {
            val result = LinkedHashMap<String, String>()
            for (line in text.lineSequence().filter { it.isNotEmpty() }) {
                require(result.size < 100_000 && line.length in 67..4162 && line.substring(64, 66) == "  ")
                val hash = line.substring(0, 64)
                val file = line.substring(66)
                val relative = Path.of(file)
                require(hash.matches(Regex("[0-9a-f]{64}")) && !relative.isAbsolute && relative.normalize() == relative && !file.contains('\\'))
                require(!file.startsWith("../") && !result.containsKey(file))
                result[file] = hash
            }
            result
        } catch (error: Exception) {
            reader.status(path, ReportReader.State.INVALID, "Invalid exported-file inventory")
            emptyMap()
        }
    }

    private fun verdicts(): Map<String, FileVerdict> {
        val counts = methodCounts()
        val diagnostics = read("recovery/compile-diagnostics.json", ListSerializer(FileDiagnostics.serializer())).orEmpty().associateBy { it.path }
        val relaxed = reader.text("recovery/javac-relaxed.txt", 4 * 1024 * 1024)?.lineSequence()?.toSet().orEmpty()
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
        // Keep four counters per file, not the JSON string and every method plus grouped lists.
        // A malformed report discards the whole result, so partial input cannot claim verification.
        return runCatching {
            val counts = HashMap<String, IntArray>()
            var pathCharacters = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            JsonReportStream(BoundedReportStream(java.security.DigestInputStream(files.input(file), digest), 256L * 1024 * 1024)).use { input ->
                var records = 0
                for (m in LENIENT.decodeToSequence(input, MethodRoundTrip.serializer())) {
                    if (++records > 2_000_000 || counts.size > 200_000 || (m.file?.length ?: 0) > 4096) throw ReportLimitException("method report entry limit")
                    val path = m.file ?: continue
                    if (m.result == "SYNTHETIC") continue
                    val c = counts.getOrPut(path) {
                        pathCharacters += path.length
                        if (counts.size >= 200_000 || pathCharacters > 8L * 1024 * 1024) throw ReportLimitException("method report file-index limit")
                        IntArray(4)
                    }
                    c[0]++
                    if (m.result in RoundTripVerifier.EQUIVALENT) c[1]++
                    if (m.result == "DIFFERENT" || m.result == "SIMILAR_OPERATIONS") c[2]++
                    if (m.result == "NOT_COMPILED") c[3]++
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                }
                // Include trailing whitespace in the exported-file digest.
                val tail = ByteArray(8192)
                while (input.read(tail) >= 0) { }
            }
            val expected = exportedHashes["recovery/round-trip.json"]
            require(expected == null || digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Method report changed since recovery" }
            reader.status("recovery/round-trip.json", ReportReader.State.LOADED, "Loaded")
            counts
        }.getOrElse { error ->
            if (error is java.io.InterruptedIOException) throw error
            val state = when (error) {
                is ProjectAccessException -> ReportReader.State.BLOCKED
                is java.nio.file.NoSuchFileException -> ReportReader.State.MISSING
                is ReportLimitException -> ReportReader.State.LIMIT_REACHED
                is java.io.IOException -> ReportReader.State.UNREADABLE
                else -> ReportReader.State.INVALID
            }
            reader.status("recovery/round-trip.json", state, "Method checks unavailable (${error.javaClass.simpleName})")
            emptyMap()
        }
    }

    private fun directories(root: Path): List<Path> {
        try { files.directory(root) }
        catch (error: java.nio.file.NoSuchFileException) { return emptyList() }
        catch (error: ProjectAccessException) {
            reader.status(this.root.relativize(root).toString().replace('\\', '/'), ReportReader.State.BLOCKED, error.message.orEmpty())
            return emptyList()
        }
        val result = ArrayList<Path>()
        val pending = java.util.ArrayDeque<Path>().apply { add(root) }
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val directory = pending.removeLast()
            try { files.directory(directory) }
            catch (error: ProjectAccessException) {
                reader.status(this.root.relativize(directory).toString().replace('\\', '/'), ReportReader.State.BLOCKED, error.message.orEmpty())
                continue
            }
            Files.newDirectoryStream(directory).use { entries ->
                for (path in entries) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if (attributes.isDirectory || attributes.isSymbolicLink) {
                        require(result.size < 20_000) { "Project has more than 20,000 folders; inspect exported subfolders separately" }
                        result.add(path)
                        if (attributes.isDirectory) pending.add(path)
                    }
                }
            }
        }
        return result.sorted()
    }

    companion object {
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        fun isProject(dir: Path): Boolean = Files.isRegularFile(dir.resolve("recovery/report.json"))
    }
}
