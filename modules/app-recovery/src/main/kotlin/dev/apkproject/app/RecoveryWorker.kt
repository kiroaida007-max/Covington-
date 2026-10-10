package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.contracts.ToolchainProfile
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
    fun log(line: String) {
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
        Files.createDirectories(jobDir)
        val files = FullJobFiles(jobDir.toAbsolutePath())
        FileChannel.open(files.dir.resolve("supervisor.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "job is already supervised")
            lock.use {
                val request = FullJobRequest(id = UUID.randomUUID().toString(),
                    input = input.toAbsolutePath().normalize().toString(), output = output.toAbsolutePath().normalize().toString(),
                    profile = WorkerProfile.from(profile), options = options)
                require(!Files.exists(files.request)) { "job directory already contains a request; resume it instead" }
                AtomicFiles.writeString(files.request, Json.encode(request))
                return launch(files, cancel, log, timeoutMs)
            }
        }
    }

    internal fun launch(files: FullJobFiles, cancel: CancellationToken, log: (String) -> Unit, timeoutMs: Long): FullRecoveryResult {
        val request = files.readRequest()
        val profile = request.profile.resolve()
        val classpath = (System.getProperty("apkproject.worker.classpath") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparator).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().normalize().toString() }
        val argv = listOf(profile.java.toString(), "-Xmx1024m", "-Djava.net.useSystemProxies=true", "-cp", classpath,
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
        val outcome = ProcessWorker.run(argv, files.dir, timeoutMs, cancel = hardCancel, onPoll = {
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
        val code = if (cancel.cancelled || outcome.cancelled) ExitCode.CANCELLED else ExitCode.WORKER_OR_RESOURCE_FAILURE
        val message = if (code == ExitCode.CANCELLED) "cancelled; job records kept at ${files.dir}"
            else "recovery worker ${if (outcome.timedOut) "timed out" else "exited without a result (${outcome.exitCode})"}; diagnostics: ${files.dir}"
        val failed = FullJobResult(jobId = request.id, workerPid = 0, exitCode = code.code, output = null, report = null, message = message)
        AtomicFiles.writeString(files.result, Json.encode(failed))
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
        FileChannel.open(files.dir.resolve("execution.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("job is still running")
            lock.use {
                val token = CancellationToken()
                val watcher = Thread({
                    while (!token.cancelled) {
                        if (Files.exists(files.cancel) || ProcessHandle.of(parent).map { !it.isAlive }.orElse(true)) token.cancel()
                        Thread.sleep(100)
                    }
                }, "recovery-cancel").apply { isDaemon = true; start() }
                val result = try {
                    FullRecovery(request.profile.resolve(), request.options, token, files::log)
                        .run(Path.of(request.input), Path.of(request.output))
                } catch (_: CancelledException) {
                    FullRecoveryResult(ExitCode.CANCELLED, null, null, "cancelled")
                } catch (error: Throwable) {
                    files.log("worker failure: ${error.javaClass.name}: ${error.message}")
                    AtomicFiles.writeString(files.dir.resolve("failure.log"), error.stackTraceToString().take(65536))
                    FullRecoveryResult(ExitCode.WORKER_OR_RESOURCE_FAILURE, null, null, "${error.javaClass.simpleName}: ${error.message}; diagnostics: ${files.dir}")
                } finally { token.cancel(); watcher.interrupt() }
                AtomicFiles.writeString(files.result, Json.encode(FullJobResult(jobId = request.id,
                    workerPid = ProcessHandle.current().pid(), exitCode = result.exitCode.code,
                    output = result.outDir?.toString(), report = result.report, message = result.message)))
            }
        }
    }
}
