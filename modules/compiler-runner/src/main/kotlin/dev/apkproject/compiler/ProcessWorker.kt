package dev.apkproject.compiler

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.isWindows
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative cancellation shared by the orchestrator and every running worker. */
class CancellationToken {
    private val flag = AtomicBoolean(false)
    val cancelled: Boolean get() = flag.get()
    fun cancel() = flag.set(true)
}

class CancelledException : RuntimeException("job cancelled")

data class ProcessOutcome(
    val argv: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val elapsedMs: Long,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val environmentHash: String,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !cancelled
}

/**
 * Runs external tools as isolated, bounded subprocesses (dossier section 18): argument lists only
 * (no shell), an explicit environment, bounded logs, and termination of the whole process tree on
 * timeout or cancellation.
 */
object ProcessWorker {
    /** Variables passed through from the parent; everything else is dropped for reproducibility. */
    private val PASSTHROUGH = listOf("PATH", "SystemRoot", "TEMP", "TMP", "HOME", "USERPROFILE", "LANG")

    fun run(
        argv: List<String>,
        workDir: Path,
        timeoutMs: Long,
        maxLogBytes: Int = 1 shl 20,
        extraEnv: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
        cancel: CancellationToken? = null,
        onPoll: (ProcessHandle) -> Unit = {},
        supervised: Boolean = false,
        onStarted: (Process) -> AutoCloseable? = { null },
    ): ProcessOutcome {
        require(argv.isNotEmpty())
        if (supervised) return execute(argv, workDir, timeoutMs, maxLogBytes, extraEnv, stdin, cancel, onPoll, onStarted)
        ResourceGovernor.toolLease(cancel).use {
            return execute(ResourceGovernor.bound(argv), workDir, timeoutMs, maxLogBytes, extraEnv, stdin, cancel, onPoll, onStarted)
        }
    }

    private fun execute(
        argv: List<String>, workDir: Path, timeoutMs: Long, maxLogBytes: Int,
        extraEnv: Map<String, String>, stdin: ByteArray?, cancel: CancellationToken?, onPoll: (ProcessHandle) -> Unit, onStarted: (Process) -> AutoCloseable?,
    ): ProcessOutcome {
        require(argv.isNotEmpty() && timeoutMs > 0 && maxLogBytes >= 0)
        Files.createDirectories(workDir)
        val pb = ProcessBuilder(argv).directory(workDir.toFile())
        val env = pb.environment()
        val keep = PASSTHROUGH.mapNotNull { k -> System.getenv(k)?.let { k to it } }.toMap()
        env.clear()
        env.putAll(keep)
        env["LC_ALL"] = "C"
        env.putAll(extraEnv)
        val envHash = Hashing.combine(*(argv + env.toSortedMap().map { "${it.key}=${it.value}" }).toTypedArray())
        val start = System.nanoTime()
        if (cancel?.cancelled == true) throw CancelledException()
        val p = pb.start()
        val lease = try { onStarted(p) } catch (error: Throwable) {
            p.descendants().use { it.forEach { child -> child.destroyForcibly() } }
            p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS)
            runCatching { p.inputStream.close(); p.errorStream.close(); p.outputStream.close() }
            throw error
        }
        try {
        val out = BoundedCapture(p.inputStream, maxLogBytes).also { it.start() }
        val err = BoundedCapture(p.errorStream, maxLogBytes).also { it.start() }
        // A child that never reads stdin must not prevent timeout/cancellation from running.
        val writer = Thread({
            try { p.outputStream.use { if (stdin != null) it.write(stdin) } } catch (_: java.io.IOException) { }
        }, "worker-stdin").apply { isDaemon = true; start() }
        var timedOut = false
        var wasCancelled = false
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val descendants = LinkedHashMap<Long, ProcessHandle>()
        try {
            while (true) {
                p.descendants().use { handles -> handles.forEach { descendants[it.pid()] = it } }
                onPoll(p.toHandle())
                if (p.waitFor(50, TimeUnit.MILLISECONDS)) break
                if (cancel?.cancelled == true) {
                    wasCancelled = true; break
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true; break
                }
            }
        } finally {
            // Remember children even when the parent exits before them. Also clean up if the
            // supervisor callback throws or this thread is interrupted.
            p.descendants().use { handles -> handles.forEach { descendants[it.pid()] = it } }
            descendants.values.toList().asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
            // Let a live parent reap children before killing it. Killing both immediately can
            // leave zombies on hosts whose PID 1 does not reap orphaned processes.
            if (descendants.isNotEmpty() && p.isAlive) p.waitFor(500, TimeUnit.MILLISECONDS)
            if (p.isAlive) p.destroyForcibly()
        }
        p.waitFor(5, TimeUnit.SECONDS)
        writer.join(500)
        out.join(2_000); err.join(2_000)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return ProcessOutcome(
            argv = argv,
            exitCode = if (p.isAlive) null else p.exitValue(),
            timedOut = timedOut,
            cancelled = wasCancelled,
            elapsedMs = elapsed,
            stdout = out.text(),
            stderr = err.text(),
            truncated = out.truncated || err.truncated,
            environmentHash = envHash,
        )
        } finally { lease?.close() }
    }

    fun executable(dir: Path, name: String): Path = dir.resolve(if (isWindows()) "$name.exe" else name)

    /** Keeps the first [limit] bytes and keeps draining so the child never blocks on a full pipe. */
    private class BoundedCapture(private val input: InputStream, private val limit: Int) : Thread() {
        private val buffer = java.io.ByteArrayOutputStream()
        @Volatile var truncated = false

        init {
            isDaemon = true
        }

        override fun run() {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) {
                        val room = limit - buffer.size()
                        if (room > 0) buffer.write(chunk, 0, minOf(n, room))
                        if (n > room) truncated = true
                    }
                }
            } catch (_: java.io.IOException) {
                // stream closed by process termination
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toString(Charsets.UTF_8) }
    }
}
