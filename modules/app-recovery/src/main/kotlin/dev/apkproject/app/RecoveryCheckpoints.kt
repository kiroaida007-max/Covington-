package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class CheckpointFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class RecoveryCheckpoint(val schemaVersion: Int = 1, val identity: String, val payload: String, val payloadSha256: String, val files: List<CheckpointFile>)

/** Immutable snapshots. Validate every byte and the complete file set before reusing a stage. */
class RecoveryCheckpoints(private val root: Path, private val log: (String) -> Unit = {}) {
    fun restore(stage: String, identity: String, target: Path?): String? {
        val dir = stageDir(stage)
        if (!Files.isRegularFile(dir.resolve("checkpoint.json"))) return null
        val record = try {
            require(Files.size(dir.resolve("checkpoint.json")) <= 64L * 1024 * 1024)
            val record = Json.decode<RecoveryCheckpoint>(Files.readString(dir.resolve("checkpoint.json")))
            if (record.schemaVersion != 1 || record.identity != identity) return null
            require(Hashing.sha256(record.payload.toByteArray()) == record.payloadSha256)
            val tree = dir.resolve("tree")
            require(record.files.size <= 500_000)
            require(record.files.map { it.path }.distinct().size == record.files.size)
            val actual = receipts(tree)
            require(actual == record.files) { "checkpoint content changed" }
            record
        } catch (e: Exception) {
            log("checkpoint: rejected $stage (${e.javaClass.simpleName}); recomputing")
            return null
        }
        // A valid snapshot that cannot be copied is a resource/I/O failure. Re-running JADX
        // over a partly copied directory would silently mix outputs from two attempts.
        if (target != null) {
            AtomicFiles.deleteRecursively(target)
            copyTree(dir.resolve("tree"), target)
        }
        log("checkpoint: restored $stage (${record.files.size} checked files)")
        return record.payload
    }

    fun save(stage: String, identity: String, source: Path?, payload: String) {
        Files.createDirectories(root)
        val temp = root.resolve(".$stage-${UUID.randomUUID()}")
        try {
            val tree = temp.resolve("tree")
            if (source == null) Files.createDirectories(tree) else copyTree(source, tree)
            AtomicFiles.writeString(temp.resolve("checkpoint.json"), Json.encode(RecoveryCheckpoint(
                identity = identity, payload = payload, payloadSha256 = Hashing.sha256(payload.toByteArray()), files = receipts(tree))))
            val target = stageDir(stage)
            AtomicFiles.deleteRecursively(target)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            log("checkpoint: saved $stage")
        } finally { AtomicFiles.deleteRecursively(temp) }
    }

    private fun stageDir(stage: String): Path {
        require(stage.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        return root.resolve(stage)
    }

    companion object {
        fun receipts(root: Path): List<CheckpointFile> {
            if (!Files.exists(root)) return emptyList()
            return Files.walk(root).use { stream -> stream.sorted().map { path ->
                require(!Files.isSymbolicLink(path)) { "symbolic links cannot be checkpointed" }
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) null else CheckpointFile(
                    root.relativize(path).toString().replace('\\', '/'), Files.size(path), Hashing.sha256(path))
            }.filter { it != null }.map { it!! }.toList() }.sortedBy { it.path }
        }

        fun copyTree(source: Path, target: Path) {
            Files.createDirectories(target)
            Files.walk(source).use { paths -> paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "symbolic links cannot be copied" }
                val to = target.resolve(source.relativize(path))
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(to)
                else { Files.createDirectories(to.parent); Files.copy(path, to, StandardCopyOption.REPLACE_EXISTING) }
            } }
        }
    }
}

@Serializable
internal data class NamingCheckpoint(val names: NameMapping, val evidence: List<NameOracle.Name>, val matches: List<LibraryMatcher.ClassMatch>)

@Serializable
internal data class VerificationCheckpoint(
    val quality: DecompileQuality, val cross: CrossCheckSummary?, val compile: EcjResult,
    val roundTrip: RoundTripSummary, val repair: RepairSummary, val repairs: List<RepairRecord>,
)

@Serializable
internal data class FullPublication(val output: String, val exitCode: Int, val report: FullRecoveryReport, val files: List<CheckpointFile>)

/** Cache identity changes whenever the executing engine changes, including local class builds. */
object RecoveryEngineIdentity {
    fun current(): String = Hashing.combine(*listOf(FullRecovery::class.java, dev.apkproject.compiler.ProcessWorker::class.java,
        dev.apkproject.dex.DexFile::class.java, dev.apkproject.lift.MethodIndex::class.java, dev.apkproject.archive.ZipInventoryReader::class.java,
        dev.apkproject.contracts.BuildInfo::class.java, dev.apkproject.store.AtomicFiles::class.java,
        dev.apkproject.reconstruct.Reconstructor::class.java, dev.apkproject.validate.BatchValidator::class.java,
        dev.apkproject.source.JavaPrinter::class.java).map { type ->
        val path = Path.of(type.protectionDomain.codeSource.location.toURI())
        if (Files.isDirectory(path)) Hashing.combine(*RecoveryCheckpoints.receipts(path).map { "${it.path}:${it.sha256}" }.toTypedArray())
        else Hashing.sha256(path)
    }.toTypedArray())
}
