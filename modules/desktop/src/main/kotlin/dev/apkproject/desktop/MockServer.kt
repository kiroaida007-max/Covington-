package dev.apkproject.desktop

import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the synthesized backend (`backend/server-skeleton/BackendSkeleton.java`) as a separate
 * process: compiled with the app's javac, served on localhost, stopped with the app.
 */
class MockServer(private val jdkHome: Path) {
    private var process: Process? = null
    var url: String? = null
        private set

    val running: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start(skeleton: Path, port: Int): String {
        stop()
        if (!Files.isRegularFile(skeleton)) throw IOException("this project has no server skeleton")
        if (!free(port)) throw IOException("port $port is in use; choose another")
        val classes = Files.createTempDirectory("apkproject-mock")
        val exe = if (dev.apkproject.contracts.isWindows()) ".exe" else ""
        val javac = ProcessBuilder(jdkHome.resolve("bin/javac$exe").toString(), "-nowarn", "-d", classes.toString(), skeleton.toString())
            .redirectErrorStream(true).start()
        val compileLog = javac.inputStream.readBytes().decodeToString()
        if (!javac.waitFor(120, TimeUnit.SECONDS) || javac.exitValue() != 0) throw IOException("the server skeleton does not compile:\n$compileLog")
        val server = ProcessBuilder(jdkHome.resolve("bin/java$exe").toString(), "-cp", classes.toString(), "BackendSkeleton", port.toString())
            .redirectErrorStream(true).start()
        process = server
        // The skeleton prints one line once it listens.
        val line = server.inputStream.bufferedReader().readLine()
        if (line == null || !server.isAlive) {
            stop()
            throw IOException("the mock server stopped while starting")
        }
        Thread { runCatching { server.inputStream.readAllBytes() } }.apply { isDaemon = true }.start()
        url = "http://127.0.0.1:$port"
        return line
    }

    @Synchronized
    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        url = null
    }

    companion object {
        fun free(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

        fun firstFreePort(from: Int = 8080): Int = (from until from + 100).firstOrNull(::free) ?: 0
    }
}
