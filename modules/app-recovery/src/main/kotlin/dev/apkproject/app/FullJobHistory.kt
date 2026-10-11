package dev.apkproject.app

import dev.apkproject.contracts.Json
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class FullJobState(val schemaVersion: Int = 1, val jobId: String, val inputName: String, val output: String,
    val status: String, val workerPid: Long, val message: String, val updatedAt: Long = System.currentTimeMillis())

data class FullJobHistoryEntry(val dir: Path, val state: FullJobState)

object FullJobHistory {
    fun list(root: Path): List<FullJobHistoryEntry> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { paths -> paths.filter { Files.isDirectory(it) }.limit(1000).map { dir ->
            runCatching {
                val file = dir.resolve("state.json")
                require(Files.size(file) < 65536)
                val state = Json.decode<FullJobState>(Files.readString(file))
                val inactive = state.status in setOf("QUEUED", "RUNNING", "WAITING") && ProcessHandle.of(state.workerPid).map { !it.isAlive }.orElse(true)
                FullJobHistoryEntry(dir, if (inactive) state.copy(status = "INTERRUPTED") else state)
            }.getOrNull()
        }.filter { it != null }.map { it!! }.toList() }.sortedByDescending { it.state.updatedAt }
    }
}

@Serializable
data class RecoveryDiagnosticReport(val schemaVersion: Int = 1, val jobId: String, val inputSha256: String,
    val profileId: String, val profileHash: String, val state: FullJobState, val resources: dev.apkproject.contracts.ResourcePlan,
    val javaVersion: String, val operatingSystem: String, val checkpointStages: List<String>, val partialProject: String?,
    val evidence: String = "Job status and completed stage availability; partial Java remains decompiled unless the verification records establish stronger evidence.")

object RecoveryDiagnostics {
    fun write(files: FullJobFiles) {
        val request = files.readRequest()
        val state = Json.decode<FullJobState>(Files.readString(files.dir.resolve("state.json")))
        val root = files.dir.resolve("checkpoints")
        val stages = if (Files.isDirectory(root)) Files.list(root).use { it.filter { p -> Files.isRegularFile(p.resolve("checkpoint.json")) }.map { p -> p.fileName.toString() }.sorted().toList() } else emptyList()
        val partial = files.dir.resolve("partial-project.txt").takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).take(4096) }
        AtomicFiles.writeString(files.dir.resolve("diagnostics.json"), Json.encode(RecoveryDiagnosticReport(jobId = request.id,
            inputSha256 = request.inputSha256, profileId = request.profile.model.id, profileHash = request.profile.hash, state = state,
            resources = files.dir.resolve("runtime-resources.json").takeIf { Files.isRegularFile(it) }
                ?.let { Json.decode<dev.apkproject.contracts.ResourcePlan>(Files.readString(it)) } ?: request.resources,
            javaVersion = System.getProperty("java.version"), operatingSystem = System.getProperty("os.name"),
            checkpointStages = stages, partialProject = partial)))
    }

    /** A bounded support bundle. APK bytes, recovered source and credentials are excluded. */
    fun export(jobDir: Path, target: Path) {
        val files = FullJobFiles(jobDir)
        write(files)
        Files.createDirectories(target.toAbsolutePath().parent)
        val temp = Files.createTempFile(target.toAbsolutePath().parent, ".diagnostics-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
                for (name in listOf("diagnostics.json", "state.json", "progress.log", "progress.previous.log", "failure.log", "worker-stderr.log", "containment.txt")) {
                    val file = jobDir.resolve(name)
                    if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                    zip.putNextEntry(ZipEntry(name))
                    Files.newInputStream(file).use { zip.write(it.readNBytes(65536)) }
                    zip.closeEntry()
                }
            }
            Files.move(temp, target.toAbsolutePath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}
