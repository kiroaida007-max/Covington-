package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ProcessOutcome
import dev.apkproject.compiler.ProcessWorker
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One owned foreground ADB server. Existing SDK servers and their unrelated clients survive. */
internal class RuntimeAdbTransport(private val request: RuntimeRequest) : RuntimeCommand {
    private val stop = CancellationToken()
    private val started = CountDownLatch(1)
    private val done = CountDownLatch(1)
    private val process = AtomicReference<Process>()
    private val error = AtomicReference<Throwable>()
    private var port: Int? = null

    private fun startServer(cancel: CancellationToken?) {
        if (port != null) return
        val allocated = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        port = allocated
        Thread({
            try {
                // ADB's server socket syntax accepts tcp:PORT; a hostname is rejected by
                // platform-tools 37. Without -a, this socket binds loopback only.
                val result = ProcessWorker.run(listOf(request.adb.toAbsolutePath().toString(), "-L", "tcp:$allocated", "nodaemon", "server"),
                    request.out, (request.sessionSeconds + 60L) * 1000, maxLogBytes = 64 * 1024, cancel = stop, supervised = true,
                    onStarted = { p -> process.set(p); started.countDown(); null })
                if (!stop.cancelled && !result.ok) error.set(IllegalStateException("ADB server exited: ${result.stderr.take(512)}"))
            } catch (e: Throwable) { error.set(e) }
            finally { started.countDown(); done.countDown() }
        }, "runtime-adb-server").apply { isDaemon = true }.start()
        check(started.await(5, TimeUnit.SECONDS)) { "Owned ADB server did not start" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (cancel?.cancelled == true) throw dev.apkproject.compiler.CancelledException()
            check(error.get() == null && process.get()?.isAlive == true) { "Owned ADB server failed to start: ${error.get()?.message.orEmpty()}" }
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", allocated), 100) }; true }.getOrDefault(false)) return
            Thread.sleep(25)
        }
        error("Owned ADB server did not accept a local connection")
    }

    override fun run(argv: List<String>, timeoutMs: Long, cancel: CancellationToken?): ProcessOutcome {
        val begun = System.nanoTime()
        val client = argv.first() == request.adb.toAbsolutePath().toString() && argv.drop(1) != listOf("version")
        if (client) {
            startServer(cancel)
            check(process.get()?.isAlive == true && error.get() == null) { "Owned ADB server stopped unexpectedly" }
        }
        val actual = if (client) listOf(argv.first(), "-P", port.toString()) + argv.drop(1) else argv
        val remaining = timeoutMs - (System.nanoTime() - begun) / 1_000_000
        check(remaining > 0) { "ADB server startup exceeded the command deadline" }
        return ProcessWorker.run(actual, request.out, remaining, maxLogBytes = 1 shl 20, cancel = cancel)
    }

    override fun close() {
        if (port == null) return
        stop.cancel()
        check(done.await(5, TimeUnit.SECONDS) && process.get()?.isAlive != true) { "Owned ADB server did not stop" }
    }
}
