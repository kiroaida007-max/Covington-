package dev.apkproject.compiler

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real subprocess failures; no mock Process or mirrored implementation assertions. */
class ProcessFailureTest {
    @TempDir lateinit var dir: Path
    private fun command(mode: String) = listOf(ProcessWorker.executable(Path.of(System.getProperty("java.home"), "bin"), "java").toString(),
        "-Xmx32m", "-cp", System.getProperty("apkproject.processProbe.classpath"), ProcessFailureProbe::class.java.name, mode)

    @Test fun `a child that ignores a full stdin pipe still times out`() {
        val result = ProcessWorker.run(command("hang"), dir, 500, stdin = ByteArray(4 * 1024 * 1024))
        assertTrue(result.timedOut)
        assertTrue(result.elapsedMs < 8000)
    }

    @Test fun `JVM crash and heap exhaustion stay in the worker`() {
        assertEquals(73, ProcessWorker.run(command("crash"), dir, 10000).exitCode)
        val oom = ProcessWorker.run(command("oom"), dir, 10000)
        assertFalse(oom.ok)
        assertTrue(oom.stderr.contains("OutOfMemoryError"), oom.stderr)
        assertEquals(0, ProcessWorker.run(command("ok"), dir, 10000).exitCode)
    }

    @Test fun `cancelling a worker also stops its descendant JVM`() {
        val cancel = CancellationToken()
        val start = System.nanoTime()
        val result = ProcessWorker.run(command("child"), dir, 15000, cancel = cancel, onPoll = { process ->
            if (process.descendants().use { it.count() } > 0 && System.nanoTime() - start > 500_000_000) cancel.cancel()
        })
        assertTrue(result.cancelled)
        val pid = result.stdout.trim().toLong()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "orphan JVM $pid")
    }

    @Test fun `repeated noisy tool runs retain bounded logs and remain usable`() {
        repeat(20) {
            val result = ProcessWorker.run(command("spam"), dir, 10000, maxLogBytes = 1024)
            assertTrue(result.ok)
            assertTrue(result.truncated)
            assertEquals(1024, result.stdout.toByteArray().size)
            assertEquals(1024, result.stderr.toByteArray().size)
        }
    }
}

object ProcessFailureProbe {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "hang" -> Thread.sleep(30000)
            "crash" -> Runtime.getRuntime().halt(73)
            "oom" -> { val held = ArrayList<ByteArray>(); while (true) held += ByteArray(4 * 1024 * 1024) }
            "spam" -> { val bytes = ByteArray(4096) { 120 }; repeat(32) { System.out.write(bytes); System.err.write(bytes) } }
            "child" -> {
                val java = ProcessWorker.executable(Path.of(System.getProperty("java.home"), "bin"), "java")
                val child = ProcessBuilder(java.toString(), "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                    ProcessFailureProbe::class.java.name, "hang").start()
                println(child.pid()); System.out.flush()
                child.waitFor()
            }
            "ok" -> println("ok")
            else -> error("unknown probe")
        }
    }
}
