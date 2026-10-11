package dev.apkproject.compiler

import dev.apkproject.contracts.ResourcePlan
import dev.apkproject.contracts.isWindows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class WorkerContainmentTest {
    @TempDir lateinit var dir: Path
    private val plan = ResourcePlan(128, 1, 32, 32, 1024 * 1024, 0)
    private fun command(mode: String) = listOf(ProcessWorker.executable(Path.of(System.getProperty("java.home"), "bin"), "java").toString(),
        "-Xmx32m", "-XX:MaxDirectMemorySize=512m", "-cp", System.getProperty("apkproject.processProbe.classpath"), ContainmentProbe::class.java.name, mode)

    @Test fun `native quota controls direct memory or strict mode refuses unavailable controls`() {
        val native = isWindows() || System.getenv("APKPROJECT_CGROUP_ROOT") != null
        if (!native) {
            var child: Process? = null
            assertFailsWith<ResourceLimitException> {
                ProcessWorker.run(command("sleep"), dir, 10000, supervised = true, onStarted = { child = it; WorkerContainment.attach(it, plan, true) })
            }
            assertFalse(assertNotNull(child).isAlive)
            return
        }
        var enforced = false
        val result = ProcessWorker.run(command("direct"), dir, 15000, supervised = true, onStarted = {
            WorkerContainment.attach(it, plan, true).also { lease -> enforced = lease.enforced }
        })
        assertTrue(enforced)
        assertFalse(result.ok, "Native allocation exceeded aggregate quota without termination")
        assertFalse(result.timedOut, "Quota must terminate the worker before the deadline")
        assertFalse("COMPLETE" in result.stdout)
    }

    @Test fun `containment teardown closes the process and startup failure remains recoverable`() {
        var child: Process? = null
        assertFailsWith<IllegalStateException> {
            ProcessWorker.run(command("sleep"), dir, 10000, supervised = true, onStarted = {
                child = it
                val lease = WorkerContainment.attach(it, plan)
                lease.close()
                error("release failed")
            })
        }
        assertFalse(assertNotNull(child).isAlive)
        assertTrue(ProcessWorker.run(command("ok"), dir, 10000, supervised = true).ok)
    }
}

object ContainmentProbe {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "sleep" -> Thread.sleep(30000)
            "ok" -> println("ok")
            "direct" -> {
                val buffers = ArrayList<ByteBuffer>()
                repeat(128) { buffers += ByteBuffer.allocateDirect(2 * 1024 * 1024); Thread.sleep(20) }
                println("COMPLETE ${buffers.size}")
            }
        }
    }
}
