package dev.apkproject.desktop

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.app.ApiContract
import dev.apkproject.app.ContractRenderer
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

    /** Project Java is an export for review, never input to the desktop's executable mock. */
    fun start(contract: ApiContract, port: Int): String = startGenerated(port) { classes, cancel ->
        require(contract.endpoints.size <= 4096 && contract.models.size <= 4096 &&
            contract.models.sumOf { it.fields.size.toLong() } <= 65_536) { "Contract exceeds desktop mock limits" }
        val data = dev.apkproject.contracts.Json.encodeLine(contract).toByteArray(Charsets.UTF_8)
        require(data.size <= 8 * 1024 * 1024) { "Contract exceeds desktop mock input limit" }
        val input = classes.resolve("contract.json")
        val source = classes.resolve("BackendSkeleton.java")
        Files.write(input, data)
        // Rendering adversarial/repetitive model graphs must not consume the desktop's heap.
        val render = ProcessWorker.run(listOf(ProcessWorker.executable(jdkHome.resolve("bin"), "java").toString(),
            "-Xmx128m", "-XX:MaxDirectMemorySize=32m", "-cp", rendererClasspath(),
            "dev.apkproject.desktop.MockTemplate", input.toString(), source.toString()),
            classes, compileTimeoutMs, maxLogBytes = 64 * 1024, cancel = cancel)
        if (!render.ok) throw IOException(when {
            render.cancelled -> "mock generation cancelled"
            render.timedOut -> "mock generation timed out"
            else -> "checked mock generation failed:\n${render.stdout}\n${render.stderr}"
        })
        source
    }

    internal fun startFixture(source: String, port: Int): String = startGenerated(port) { classes, _ ->
        require(source.length <= 8 * 1024 * 1024)
        classes.resolve("BackendSkeleton.java").also { Files.writeString(it, source) }
    }

    private fun startGenerated(port: Int, generate: (Path, CancellationToken) -> Path): String = synchronized(startLock) {
        stop()
        val cancel = CancellationToken()
        synchronized(stateLock) {
            if (closed) throw IOException("mock server has been closed")
            token = cancel
        }
        var server: Process? = null
        val classes = Files.createTempDirectory("apkproject-mock")
        try {
            val skeleton = generate(classes, cancel)
            if (cancel.cancelled) throw IOException("mock server startup cancelled")
            if (!free(port)) throw IOException("port $port is in use; choose another")
            val compile = ProcessWorker.run(
                listOf(ProcessWorker.executable(jdkHome.resolve("bin"), "javac").toString(), "-J-Xmx256m", "-J-XX:MaxDirectMemorySize=32m", "-proc:none", "-encoding", "UTF-8",
                    "-nowarn", "-classpath", classes.toString(), "-sourcepath", classes.toString(), "-d", classes.toString(), skeleton.toString()),
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
                server = ProcessBuilder(ProcessWorker.executable(jdkHome.resolve("bin"), "java").toString(), "-Xmx128m", "-XX:MaxDirectMemorySize=32m",
                    "-cp", classes.toString(), "BackendSkeleton", port.toString()).directory(classes.toFile()).redirectErrorStream(true).start()
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
                    synchronized(stateLock) { if (process === child) { process = null; url = null; token = null } }
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
        // Gradle and packaged launchers can load libraries outside java.class.path. Use only
        // the code locations of the trusted renderer and its actual serialization dependencies.
        private fun rendererClasspath(): String = listOf(MockTemplate::class.java, ApiContract::class.java,
            dev.apkproject.contracts.Json::class.java, kotlin.Unit::class.java,
            kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().toString() }
            .distinct().joinToString(java.io.File.pathSeparator)

        fun free(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
        fun firstFreePort(from: Int = 8080): Int = (from until from + 100).firstOrNull(::free) ?: 0
    }
}

/** Runs in a bounded child JVM; only contract data and the bundled template are inputs. */
internal object MockTemplate {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val data = Files.newInputStream(Path.of(args[0])).use { it.readNBytes(8 * 1024 * 1024 + 1) }
        require(data.size <= 8 * 1024 * 1024) { "Contract exceeds desktop mock input limit" }
        val contract = dev.apkproject.contracts.Json.line.decodeFromString(ApiContract.serializer(), data.toString(Charsets.UTF_8))
        val source = ContractRenderer.serverSkeleton(contract)
        require(source.length <= 8 * 1024 * 1024) { "Generated mock exceeds the source size limit" }
        Files.writeString(Path.of(args[1]), source)
    }
}
