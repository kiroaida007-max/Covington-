package dev.apkproject.desktop

import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockServerTest {
    private val jdk = Path.of(System.getProperty("java.home"))
    private fun port() = ServerSocket(0).use { it.localPort }

    @Test
    fun `silent startup has a deadline and leaves no running server`(@TempDir dir: Path) {
        val source = dir.resolve("BackendSkeleton.java")
        Files.writeString(source, "public class BackendSkeleton { public static void main(String[] args) throws Exception { Thread.sleep(60000); } }")
        MockServer(jdk, startupTimeoutMs = 200).use { server ->
            val error = assertFailsWith<IOException> { server.start(source, port()) }
            assertTrue("timed out" in error.message.orEmpty(), error.toString())
            assertFalse(server.running)
            assertEquals(null, server.url)
        }
    }

    @Test
    fun `closing during startup cancels the reader and cannot resurrect the server`(@TempDir dir: Path) {
        val marker = dir.resolve("started")
        val source = dir.resolve("BackendSkeleton.java")
        Files.writeString(source, """public class BackendSkeleton {
            public static void main(String[] args) throws Exception {
                java.nio.file.Files.writeString(java.nio.file.Path.of("${marker.toString().replace("\\", "\\\\")}"), "started");
                Thread.sleep(60000);
            }
        }""")
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mock-server-test").apply { isDaemon = true } }
        val server = MockServer(jdk)
        try {
            val work = executor.submit<Exception?> { try { server.start(source, port()); null } catch (e: Exception) { e } }
            // This waits for fixture compilation/readiness, not the cancellation under test.
            // Concurrent recovery/emulator jobs can legitimately make javac take over ten seconds.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!Files.exists(marker) && !work.isDone && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(marker), "Server fixture never entered startup: ${if (work.isDone) work.get() else "readiness deadline"}")
            server.close()
            assertTrue(work.get(10, TimeUnit.SECONDS) is IOException)
            assertFalse(server.running)
            assertEquals(null, server.url)
            assertFailsWith<IOException> { server.start(source, port()) }
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test
    fun `server drains output and keeps classes for handlers loaded after startup`(@TempDir dir: Path) {
        val source = dir.resolve("BackendSkeleton.java")
        Files.writeString(source, """public class BackendSkeleton {
            static class Response { static String body() { return "alive"; } }
            public static void main(String[] args) throws Exception {
                com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
                s.createContext("/", x -> { byte[] body = Response.body().getBytes(); x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close(); });
                s.start();
                System.out.println("Listening on http://127.0.0.1:" + args[0] + " " + "x".repeat(10000));
                for (int i = 0; i < 20000; i++) System.out.println("discarded log output");
            }
        }""")
        MockServer(jdk).use { server ->
            assertEquals(4096, server.start(source, port()).length)
            val connection = URL(server.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try { assertEquals("alive", connection.inputStream.use { it.readBytes().decodeToString() }) }
            finally { connection.disconnect() }
            assertTrue(server.running)
            server.stop()
            assertFalse(server.running)
        }
    }
}
