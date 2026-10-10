package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.compiler.ResourceGovernor
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.contracts.ToolchainProfile
import dev.apkproject.contracts.ResourcePlan
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Explicit paths, not the parent environment or credentials, cross the process boundary. */
@Serializable
data class WorkerProfile(val model: ToolchainProfile, val hash: String, val paths: Map<String, String>) {
    fun resolve(): ResolvedProfile {
        fun p(key: String) = paths[key]?.let(Path::of)
        return ResolvedProfile(model, hash, p("jdk")!!, p("d8")!!, p("lib")!!, p("z3")!!,
            p("jadx"), p("android"), p("rea"), p("reaEngine"), p("wakaru"), p("ecj"), p("cache"))
    }

    companion object {
        fun from(p: ResolvedProfile) = WorkerProfile(p.profile, p.profileHash, listOf(
            "jdk" to p.jdkHome, "d8" to p.d8Jar, "lib" to p.d8Lib, "z3" to p.z3,
            "jadx" to p.jadxJar, "android" to p.androidJar, "rea" to p.reaExecutable,
            "reaEngine" to p.reaEngineJar, "wakaru" to p.wakaru, "ecj" to p.ecjJar, "cache" to p.libraryCache,
        ).mapNotNull { (k, v) -> v?.let { k to it.toAbsolutePath().normalize().toString() } }.toMap())
    }
}

@Serializable
data class FullJobRequest(
    val schemaVersion: Int = 1,
    val id: String,
    val input: String,
    val output: String,
    val profile: WorkerProfile,
    val options: FullRecoveryOptions,
    val inputSha256: String,
    val resources: ResourcePlan = ResourceGovernor.plan(options.resources),
    val resourceRoot: String = ResourceGovernor.defaultRoot().toString(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class FullJobResult(
    val schemaVersion: Int = 1,
    val jobId: String,
    val workerPid: Long,
    val exitCode: Int,
    val output: String?,
    val report: FullRecoveryReport?,
    val message: String,
    val finishedAt: Long = System.currentTimeMillis(),
) {
    fun recoveryResult() = FullRecoveryResult(ExitCode.entries.first { it.code == exitCode }, output?.let(Path::of), report, message)
}

/** Durable files also survive a GUI crash; a result is published only by atomic rename. */
class FullJobFiles(val dir: Path) {
    val request = dir.resolve("request.json")
    val result = dir.resolve("result.json")
    val progress = dir.resolve("progress.log")
    val cancel = dir.resolve("cancel.requested")
    fun readRequest() = Json.decode<FullJobRequest>(Files.readString(request))
    fun readResult(): FullJobResult? = if (Files.isRegularFile(result)) Json.decode<FullJobResult>(Files.readString(result)) else null
    fun mark(status: String, message: String, pid: Long = ProcessHandle.current().pid()) {
        val r = readRequest()
        AtomicFiles.writeString(dir.resolve("state.json"), Json.encode(FullJobState(jobId = r.id,
            inputName = Path.of(r.input).fileName.toString(), output = r.output, status = status, workerPid = pid, message = message.take(4096))))
    }
    fun complete(value: FullJobResult) {
        AtomicFiles.writeString(result, Json.encode(value))
        mark(when (value.exitCode) { 0 -> "COMPLETED"; 4 -> "PARTIAL"; 130 -> "CANCELLED"; else -> "FAILED" }, value.message, value.workerPid)
        runCatching { RecoveryDiagnostics.write(this) }
    }
    fun log(line: String) {
        if (line.startsWith("checkpoint:") || line.startsWith("resources: waiting"))
            mark(if (line.startsWith("resources: waiting")) "WAITING" else "RUNNING", line)
        // One writer, no unbounded journal or giant compiler diagnostic in the UI queue.
        if (!Files.exists(progress) || Files.size(progress) < 8L * 1024 * 1024)
            Files.writeString(progress, line.take(4096).replace('\n', ' ').replace('\r', ' ') + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

/** Launches the same engine in a dedicated JVM, on Windows as well as Unix. */
object FullRecoverySupervisor {
    fun run(profile: ResolvedProfile, options: FullRecoveryOptions, input: Path, output: Path,
        jobDir: Path, cancel: CancellationToken = CancellationToken(), log: (String) -> Unit = {},
        timeoutMs: Long = 6 * 60 * 60 * 1000L): FullRecoveryResult {
        require(output.toAbsolutePath().parent != null) { "output must be a project directory, not a filesystem root" }
        require(!jobDir.toAbsolutePath().normalize().startsWith(output.toAbsolutePath().normalize())) { "job directory must be outside the output project" }
        require(Files.isRegularFile(input)) { "input must be a regular APK, bundle or DEX file" }
        if (Files.size(input) > 1024L * 1024 * 1024)
            return FullRecoveryResult(ExitCode.MALFORMED_INPUT, null, null, "input exceeds the 1 GiB archive limit")
        Files.createDirectories(jobDir)
        val files = FullJobFiles(jobDir.toAbsolutePath())
        FileChannel.open(files.dir.resolve("supervisor.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "job is already supervised")
            lock.use {
                val request = FullJobRequest(id = UUID.randomUUID().toString(),
                    input = input.toAbsolutePath().normalize().toString(), output = output.toAbsolutePath().normalize().toString(),
                    profile = WorkerProfile.from(profile), options = options, inputSha256 = Hashing.sha256(input))
                require(!Files.exists(files.request)) { "job directory already contains a request; resume it instead" }
                AtomicFiles.writeString(files.request, Json.encode(request))
                files.mark("QUEUED", "Waiting for worker startup")
                return launch(files, cancel, log, timeoutMs)
            }
        }
    }

    fun resume(jobDir: Path, cancel: CancellationToken = CancellationToken(), log: (String) -> Unit = {}): FullRecoveryResult {
        val files = FullJobFiles(jobDir.toAbsolutePath())
        require(Files.isRegularFile(files.request)) { "no full recovery request in $jobDir" }
        FileChannel.open(files.dir.resolve("supervisor.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "job is already supervised")
            lock.use {
                FileChannel.open(files.dir.resolve("execution.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { execution ->
                    val idle = try { execution.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                    if (idle == null) return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "job worker is still running")
                    idle.release()
                }
                val request = files.readRequest()
                if (request.schemaVersion != 1 || Hashing.sha256(Path.of(request.input)) != request.inputSha256)
                    return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "input changed; create a new job")
                val previous = files.readResult()
                val published = files.dir.resolve("publication.json").takeIf { Files.isRegularFile(it) }
                    ?.let { Json.decode<FullPublication>(Files.readString(it)) }
                if (previous?.output == null && published != null && Files.isDirectory(Path.of(published.output)) &&
                    published.report.inputSha256 == request.inputSha256 && RecoveryCheckpoints.receipts(Path.of(published.output)) == published.files) {
                    val recovered = FullJobResult(jobId = request.id, workerPid = 0, exitCode = published.exitCode,
                        output = published.output, report = published.report, message = FullRecovery.summaryLine(published.report))
                    AtomicFiles.writeString(files.dir.resolve("output-files.json"), Json.encode(published.files))
                    files.complete(recovered)
                    return recovered.recoveryResult()
                }
                if (previous?.output != null) {
                    val expected = Json.decode<List<CheckpointFile>>(Files.readString(files.dir.resolve("output-files.json")))
                    if (RecoveryCheckpoints.receipts(Path.of(previous.output)) != expected)
                        return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "completed project changed; it will not be overwritten")
                    return previous.recoveryResult()
                }
                Files.deleteIfExists(files.cancel)
                Files.deleteIfExists(files.result)
                return launch(files, cancel, log, 6 * 60 * 60 * 1000L)
            }
        }
    }

    internal fun launch(files: FullJobFiles, cancel: CancellationToken, log: (String) -> Unit, timeoutMs: Long): FullRecoveryResult {
        val request = files.readRequest()
        if (Files.isRegularFile(files.progress)) Files.move(files.progress, files.dir.resolve("progress.previous.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        // A resumed job can be on a smaller host or under a changed container limit.
        val resources = ResourceGovernor.plan(request.options.resources.copy(memoryMb = request.resources.memoryMb, cpuThreads = request.resources.cpuThreads))
        AtomicFiles.writeString(files.dir.resolve("runtime-resources.json"), Json.encode(resources))
        files.mark("QUEUED", "Waiting for worker startup")
        val profile = request.profile.resolve()
        val classpath = (System.getProperty("apkproject.worker.classpath") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparator).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().normalize().toString() }
        val proxyProperties = listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore")
            .mapNotNull { key -> System.getProperty(key)?.let { "-D$key=$it" } }
        val argv = listOf(profile.java.toString(), "-Xmx${resources.engineHeapMb}m", "-XX:ActiveProcessorCount=${resources.cpuThreads}",
            "-Djava.net.useSystemProxies=true") + proxyProperties + listOf("-cp", classpath,
            RecoveryWorker::class.java.name, files.dir.toString(), ProcessHandle.current().pid().toString())
        val hardCancel = CancellationToken()
        var cancelAt: Long? = null
        var offset = 0L
        fun drain() {
            if (!Files.isRegularFile(files.progress)) return
            RandomAccessFile(files.progress.toFile(), "r").use { reader ->
                reader.seek(offset)
                repeat(128) {
                    val start = reader.filePointer
                    val line = reader.readLine() ?: return@use
                    // Do not consume a partial final line while the worker is writing it.
                    if (reader.filePointer == reader.length() && reader.length() > 0) {
                        reader.seek(reader.length() - 1)
                        if (reader.read() != '\n'.code) { offset = start; return@use }
                    }
                    offset = start + line.toByteArray(Charsets.ISO_8859_1).size + 1
                    log(String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
                    reader.seek(offset)
                }
            }
        }
        val outcome = ProcessWorker.run(argv, files.dir, timeoutMs, cancel = hardCancel, supervised = true, onPoll = {
            drain()
            if (cancel.cancelled || Files.exists(files.cancel)) {
                if (cancelAt == null) {
                    AtomicFiles.writeString(files.cancel, "cancel\n")
                    cancelAt = System.nanoTime()
                }
                if (System.nanoTime() - cancelAt!! > 5_000_000_000L) hardCancel.cancel()
            }
        })
        drain()
        AtomicFiles.writeString(files.dir.resolve("worker-stderr.log"), outcome.stderr)
        val result = runCatching { files.readResult() }.getOrNull()
        if (result != null && result.jobId == request.id) return result.recoveryResult()
        val code = if (cancel.cancelled || outcome.cancelled || cancelAt != null) ExitCode.CANCELLED else ExitCode.WORKER_OR_RESOURCE_FAILURE
        val message = if (code == ExitCode.CANCELLED) "cancelled; job records kept at ${files.dir}"
            else "recovery worker ${if (outcome.timedOut) "timed out" else "exited without a result (${outcome.exitCode})"}; diagnostics: ${files.dir}"
        val failed = FullJobResult(jobId = request.id, workerPid = 0, exitCode = code.code, output = null, report = null, message = message)
        files.complete(failed)
        return failed.recoveryResult()
    }
}

/** Internal entry point. No Swing classes are loaded in this process. */
object RecoveryWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val files = FullJobFiles(Path.of(args[0]))
        val parent = args[1].toLong()
        val request = files.readRequest()
        require(request.schemaVersion == 1)
        val resources = files.dir.resolve("runtime-resources.json").takeIf { Files.isRegularFile(it) }
            ?.let { Json.decode<ResourcePlan>(Files.readString(it)) } ?: ResourceGovernor.plan(request.options.resources)
        FileChannel.open(files.dir.resolve("execution.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("job is still running")
            lock.use {
                files.mark("RUNNING", "Worker started")
                val token = CancellationToken()
                val resourceFailure = java.util.concurrent.atomic.AtomicReference<String?>()
                val watcher = Thread({
                    try {
                        var nextDiskCheck = 0L
                        var abandonedAt: Long? = null
                        while (!Thread.currentThread().isInterrupted) {
                            if (Files.exists(files.cancel) || ProcessHandle.of(parent).map { !it.isAlive }.orElse(true)) token.cancel()
                            if (System.nanoTime() >= nextDiskCheck) {
                                val partial = files.dir.resolve("partial-project.txt").takeIf { Files.isRegularFile(it) }
                                    ?.let { Path.of(Files.readString(it)) }
                                try { ResourceGovernor.checkDisk(resources, listOfNotNull(files.dir, partial)) }
                                catch (e: Exception) { resourceFailure.set(e.message ?: "disk budget check failed"); token.cancel() }
                                nextDiskCheck = System.nanoTime() + 5_000_000_000L
                            }
                            if (ProcessHandle.of(parent).map { !it.isAlive }.orElse(true) || resourceFailure.get() != null) {
                                if (abandonedAt == null) abandonedAt = System.nanoTime()
                                if (System.nanoTime() - abandonedAt!! > 5_000_000_000L) {
                                    ProcessHandle.current().descendants().use { handles -> handles.forEach { it.destroyForcibly() } }
                                    Runtime.getRuntime().halt(if (resourceFailure.get() != null) 6 else 130)
                                }
                            }
                            Thread.sleep(100)
                        }
                    } catch (_: InterruptedException) {
                        // Normal worker completion.
                    }
                }, "recovery-cancel").apply { isDaemon = true; start() }
                val result = try {
                    ResourceGovernor.withJob(resources, Path.of(request.resourceRoot), token, files::log) {
                        ResourceGovernor.checkDisk(resources, listOf(files.dir))
                        ResourceGovernor.checkFreeDisk(resources, Path.of(request.output).toAbsolutePath().parent)
                        val options = request.options.copy(threads = minOf(request.options.threads, resources.cpuThreads))
                        files.log("resources: ${resources.memoryMb} MB total, ${resources.engineHeapMb} MB engine / ${resources.toolHeapMb} MB tools, ${options.threads} threads")
                        val snapshot = Files.createDirectories(files.dir.resolve("input")).resolve(Path.of(request.input).fileName)
                        if (!Files.isRegularFile(snapshot) || Hashing.sha256(snapshot) != request.inputSha256) {
                            Files.copy(Path.of(request.input), snapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        if (Hashing.sha256(snapshot) != request.inputSha256)
                            return@withJob FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "input changed while taking its snapshot; create a new job")
                        FullRecovery(request.profile.resolve(), options, token, files::log)
                            .run(snapshot, Path.of(request.output), files.dir, request.inputSha256)
                    }
                } catch (_: CancelledException) {
                    FullRecoveryResult(ExitCode.CANCELLED, null, null, "cancelled")
                } catch (error: Throwable) {
                    files.log("worker failure: ${error.javaClass.name}: ${error.message}")
                    AtomicFiles.writeString(files.dir.resolve("failure.log"), error.stackTraceToString().take(65536))
                    FullRecoveryResult(ExitCode.WORKER_OR_RESOURCE_FAILURE, null, null, "${error.javaClass.simpleName}: ${error.message}; diagnostics: ${files.dir}")
                } finally { token.cancel(); watcher.interrupt(); watcher.join(1000) }
                val checkedResult = resourceFailure.get()?.let { FullRecoveryResult(ExitCode.WORKER_OR_RESOURCE_FAILURE, null, null, "resource limit: $it; job records: ${files.dir}") } ?: result
                if (checkedResult.outDir != null) AtomicFiles.writeString(files.dir.resolve("output-files.json"), Json.encode(RecoveryCheckpoints.receipts(checkedResult.outDir)))
                files.complete(FullJobResult(jobId = request.id,
                    workerPid = ProcessHandle.current().pid(), exitCode = checkedResult.exitCode.code,
                    output = checkedResult.outDir?.toString(), report = checkedResult.report, message = checkedResult.message))
            }
        }
    }
}
