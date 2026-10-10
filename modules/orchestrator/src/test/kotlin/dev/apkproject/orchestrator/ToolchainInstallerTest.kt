package dev.apkproject.orchestrator

import com.sun.net.httpserver.HttpServer
import dev.apkproject.contracts.Hashing
import dev.apkproject.orchestrator.ToolchainInstaller.Status.State
import dev.apkproject.orchestrator.ToolchainInstaller.ToolSpec
import dev.apkproject.orchestrator.ToolchainInstaller.Unpack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolchainInstallerTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var server: HttpServer
    private val files = HashMap<String, ByteArray>()
    private val requests = AtomicInteger()
    private lateinit var serverWorkers: java.util.concurrent.ExecutorService
    private lateinit var stallStarted: java.util.concurrent.CountDownLatch

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Several threads: a stalled response must not hold up the next request.
        serverWorkers = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = serverWorkers
        stallStarted = java.util.concurrent.CountDownLatch(1)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            if (exchange.requestURI.path.startsWith("/stall/")) {
                // Headers and the first bytes, then silence: what dl.google.com did on Windows CI.
                val body = files.getValue(exchange.requestURI.path.removePrefix("/stall"))
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body, 0, body.size / 4)
                exchange.responseBody.flush()
                stallStarted.countDown()
                runCatching { Thread.sleep(30_000) }
                exchange.close()
                return@createContext
            }
            val body = files[exchange.requestURI.path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() { server.stop(0); serverWorkers.shutdownNow() }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = Hashing.sha256(bytes)

    @Test
    fun `tools are downloaded, verified and unpacked once`() {
        val jar = "a compiler".toByteArray()
        val tool = "the tool".toByteArray()
        val bundle = zip(mapOf("pkg-1.0/lib/tool.jar" to tool, "pkg-1.0/bin/run" to "#!/bin/sh".toByteArray(), "pkg-1.0/docs/huge.txt" to ByteArray(10), "other/x" to ByteArray(1)))
        files["/compiler.jar"] = jar
        files["/bundle.zip"] = bundle
        val specs = listOf(
            ToolSpec("compiler", "Compiler", listOf(url("/compiler.jar")), sha(jar), Unpack.AsFile("compiler.jar"), "compiler.jar" to sha(jar)),
            ToolSpec(
                "bundle", "Bundle", listOf(url("/bundle.zip")), sha(bundle),
                Unpack.Unzip("pkg", "pkg-1.0/", executables = setOf("bin/run"), only = setOf("lib/tool.jar", "bin/run")), "pkg/lib/tool.jar" to sha(tool),
            ),
        )
        val tools = dir.resolve("tools")
        val messages = ArrayList<String>()
        val result = ToolchainInstaller(tools, specs).install({ step, message, _, _ -> messages += "$step: $message" })
        assertEquals(listOf(State.INSTALLED, State.INSTALLED), result.map { it.state }, result.toString())
        assertTrue(Files.readAllBytes(tools.resolve("compiler.jar")).contentEquals(jar))
        assertTrue(Files.readAllBytes(tools.resolve("pkg/lib/tool.jar")).contentEquals(tool))
        assertFalse(Files.exists(tools.resolve("pkg/docs/huge.txt")), "only the requested entries are unpacked")
        if (!dev.apkproject.contracts.isWindows()) assertTrue(Files.isExecutable(tools.resolve("pkg/bin/run")))
        assertTrue(messages.any { "Bundle ready" in it }, messages.toString())

        // A second run finds everything in place and downloads nothing.
        val before = requests.get()
        assertEquals(listOf(State.INSTALLED, State.INSTALLED), ToolchainInstaller(tools, specs).install().map { it.state })
        assertEquals(before, requests.get())
    }

    @Test
    fun `a download with the wrong checksum is rejected and nothing is installed`() {
        files["/tampered.jar"] = "not what was pinned".toByteArray()
        val spec = ToolSpec("t", "Tampered", listOf(url("/tampered.jar")), sha("the real thing".toByteArray()), Unpack.AsFile("t.jar"), "t.jar" to sha("the real thing".toByteArray()))
        val result = ToolchainInstaller(dir.resolve("tools"), listOf(spec)).install().single()
        assertEquals(State.FAILED, result.state)
        assertContains(result.detail, "SHA-256")
        assertFalse(Files.exists(dir.resolve("tools/t.jar")))
        assertEquals(1, requests.get(), "a wrong file is not downloaded again")
    }

    @Test
    fun `archive entries may not escape their folder`() {
        val evil = zip(mapOf("../../escaped.txt" to "x".toByteArray()))
        files["/evil.zip"] = evil
        val spec = ToolSpec("e", "Evil", listOf(url("/evil.zip")), sha(evil), Unpack.Unzip("e"), "e/marker" to sha(ByteArray(0)))
        val result = ToolchainInstaller(dir.resolve("tools"), listOf(spec)).install().single()
        assertEquals(State.FAILED, result.state)
        assertFalse(Files.exists(dir.resolve("escaped.txt")))
    }

    @Test
    fun `a mirror is used when the first source fails`() {
        val jar = "mirrored".toByteArray()
        files["/mirror/tool.jar"] = jar
        val spec = ToolSpec("m", "Mirrored", listOf(url("/missing/tool.jar"), url("/mirror/tool.jar")), sha(jar), Unpack.AsFile("tool.jar"), "tool.jar" to sha(jar))
        val tools = dir.resolve("tools")
        assertEquals(State.INSTALLED, ToolchainInstaller(tools, listOf(spec)).install().single().state)
        assertTrue(Files.readAllBytes(tools.resolve("tool.jar")).contentEquals(jar))
    }

    @Test
    fun `a download that stops sending is abandoned and retried elsewhere`() {
        val jar = ByteArray(200_000) { (it % 251).toByte() }
        files["/tool.jar"] = jar
        val spec = ToolSpec("s", "Stalled", listOf(url("/stall/tool.jar"), url("/tool.jar")), sha(jar), Unpack.AsFile("tool.jar"), "tool.jar" to sha(jar))
        val tools = dir.resolve("tools")
        val started = System.nanoTime()
        val result = ToolchainInstaller(tools, listOf(spec), stallTimeout = java.time.Duration.ofMillis(500)).install().single()
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(State.INSTALLED, result.state, result.detail)
        assertTrue(Files.readAllBytes(tools.resolve("tool.jar")).contentEquals(jar))
        // Three stalled attempts (0.5 s each plus 1 + 2 + 4 s back-off) and then the mirror; far below the 30 s stall.
        assertTrue(seconds < 15, "took $seconds s")
    }

    @Test
    fun `cancelling a stalled download closes its body without retry or publication`() {
        val jar = ByteArray(200_000) { (it % 251).toByte() }
        files["/tool.jar"] = jar
        val spec = ToolSpec("s", "Stalled", listOf(url("/stall/tool.jar")), sha(jar), Unpack.AsFile("tool.jar"), "tool.jar" to sha(jar))
        val tools = dir.resolve("tools")
        val cancel = dev.apkproject.compiler.CancellationToken()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit {
                assertFailsWith<dev.apkproject.compiler.CancelledException> { ToolchainInstaller(tools, listOf(spec)).install(cancel = cancel) }
            }
            assertTrue(stallStarted.await(5, java.util.concurrent.TimeUnit.SECONDS), "server must begin sending")
            cancel.cancel()
            result.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, requests.get(), "cancelled requests must not be retried")
            assertFalse(Files.exists(tools.resolve("tool.jar")))
            assertFalse(Files.exists(tools.resolve(".downloads/s.part")))
        } finally { cancel.cancel(); worker.shutdownNow() }
    }

    @Test
    fun `pinned tools match the profiles they install for`() {
        val root = java.nio.file.Paths.get(System.getProperty("apkproject.root"))
        for ((platform, profile) in listOf(ToolchainInstaller.Platform.LINUX_X64 to "android-full.json", ToolchainInstaller.Platform.WINDOWS_X64 to "android-windows.json")) {
            assertEquals(profile, ToolchainInstaller.profileName(platform))
            val text = Files.readString(root.resolve("profiles/$profile"))
            for (spec in ToolchainInstaller.pinned(platform)) {
                assertContains(text, spec.installed.second, message = "${spec.id} for $profile")
                assertContains(text, spec.installed.first.substringAfterLast('/'), message = "${spec.id} path for $profile")
            }
        }
    }
}
