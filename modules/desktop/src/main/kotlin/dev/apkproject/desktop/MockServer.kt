package dev.apkproject.desktop

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ProcessWorker
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Compiles and runs the synthesized localhost backend with bounded output and startup deadlines. */
class MockServer(
    private val jdkHome: Path,
    private val startupTimeoutMs: Long = 30_000,
    private val compileTimeoutMs: Long = 120_000,
) : AutoCloseable {
    private val stateLock = Any()
    private val startLock = Any()
    private var token: CancellationToken? = null
    private var process: Process? = null
    private var closed = false
    @Volatile var url: String? = null
        private set

    val running: Boolean get() = synchronized(stateLock) { process?.isAlive == true }

    fun start(skeleton: Path, port: Int): String = synchronized(startLock) {
        stop()
        val cancel = CancellationToken()
        synchronized(stateLock) {
            if (closed) throw IOException("mock server has been closed")
            token = cancel
        }
        var server: Process? = null
        val classes = Files.createTempDirectory("apkproject-mock")
        try {
            if (!Files.isRegularFile(skeleton)) throw IOException("this project has no server skeleton")
            if (!free(port)) throw IOException("port $port is in use; choose another")
            val compile = ProcessWorker.run(
                listOf(ProcessWorker.executable(jdkHome.resolve("bin"), "javac").toString(), "-nowarn", "-d", classes.toString(), skeleton.toAbsolutePath().toString()),
                classes, compileTimeoutMs, maxLogBytes = 64 * 1024, cancel = cancel,
            )
            if (!compile.ok) throw IOException(
                when {
                    compile.cancelled -> "mock server startup cancelled"
                    compile.timedOut -> "server compilation timed out"
                    else -> "the server skeleton does not compile:\n${compile.stdout}\n${compile.stderr}"
                },
            )
            synchronized(stateLock) {
                if (cancel.cancelled || closed) throw IOException("mock server startup cancelled")
                server = ProcessBuilder(ProcessWorker.executable(jdkHome.resolve("bin"), "java").toString(), "-cp", classes.toString(), "BackendSkeleton", port.toString())
                    .redirectErrorStream(true).start()
                process = server
            }
            val child = server!!
            val firstLine = CompletableFuture<String>()
            // Keep only a bounded first line and drain all later output without retaining it.
            Thread({
                try {
                    child.inputStream.use { input ->
                        val line = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (!firstLine.isDone) for (i in 0 until n) {
                                if (chunk[i] == '\n'.code.toByte()) {
                                    firstLine.complete(line.toString(Charsets.UTF_8)); break
                                }
                                if (line.size() < 4096) line.write(chunk[i].toInt())
                            }
                        }
                    }
                    firstLine.completeExceptionally(IOException("mock server exited before announcing startup"))
                } catch (e: IOException) { firstLine.completeExceptionally(e) }
                finally {
                    // Classes remain available for lazy class loading throughout the server's life.
                    runCatching { child.waitFor() }
                    cleanup(classes)
                }
            }, "mock-server-output").apply { isDaemon = true }.start()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs)
            while (!firstLine.isDone) {
                if (cancel.cancelled) throw IOException("mock server startup cancelled")
                if (System.nanoTime() >= deadline) throw IOException("mock server startup timed out")
                child.waitFor(25, TimeUnit.MILLISECONDS)
            }
            val line = try { firstLine.get() } catch (e: java.util.concurrent.ExecutionException) {
                throw IOException(e.cause?.message ?: "mock server startup failed", e.cause)
            }
            val address = "http://127.0.0.1:$port"
            if (address !in line) throw IOException("mock server did not announce its localhost address:\n$line")
            synchronized(stateLock) {
                if (cancel.cancelled || closed || !child.isAlive) throw IOException("mock server stopped while starting")
                url = address
            }
            line
        } catch (e: Exception) {
            server?.let(::terminate)
            synchronized(stateLock) { if (token === cancel) { process = null; url = null; token = null } }
            if (server == null) cleanup(classes)
            throw e
        }
    }

    /** Cancellation and termination never wait for a compiler or the startup reader on Swing. */
    fun stop() {
        val child = synchronized(stateLock) {
            token?.cancel()
            token = null
            process.also { process = null; url = null }
        }
        child?.let(::terminate)
    }

    override fun close() {
        synchronized(stateLock) { closed = true }
        stop()
    }

    private fun terminate(child: Process) {
        runCatching { child.descendants().forEach { it.destroyForcibly() } }
        child.destroyForcibly()
    }

    private fun cleanup(dir: Path) {
        runCatching {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    companion object {
        fun free(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
        fun firstFreePort(from: Int = 8080): Int = (from until from + 100).firstOrNull(::free) ?: 0
    }
}
