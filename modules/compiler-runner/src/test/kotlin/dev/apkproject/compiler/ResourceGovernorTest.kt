package dev.apkproject.compiler

import dev.apkproject.contracts.ResourceLimits
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceGovernorTest {
    @TempDir lateinit var dir: Path

    @Test fun `budgets account for engine tool and native memory together`() {
        for (memory in listOf(2048L, 4096L, 8192L, 32768L)) {
            val p = ResourceGovernor.plan(memoryMb = memory, cpus = 64)
            assertTrue(p.engineHeapMb + p.toolHeapMb + 256 <= p.memoryMb)
            assertTrue(p.memoryMb <= memory * 65 / 100)
            assertTrue(p.cpuThreads in 1..8)
        }
        assertFailsWith<IllegalArgumentException> { ResourceGovernor.plan(memoryMb = 512) }
        assertEquals(2, ResourceGovernor.plan(ResourceLimits(cpuThreads = 32), memoryMb = 8192, cpus = 2).cpuThreads)
    }

    @Test fun `caps compiler heaps and processor count without a shell`() {
        val p = ResourceGovernor.plan(ResourceLimits(memoryMb = 1536, cpuThreads = 2), memoryMb = 8192, cpus = 8)
        ResourceGovernor.withJob(p, dir.resolve("gate"), CancellationToken(), {}) {
            val java = ResourceGovernor.bound(listOf("java", "-Xmx8g", "-jar", "tool.jar"))
            assertTrue("-Xmx${p.toolHeapMb}m" in java)
            assertTrue("-XX:ActiveProcessorCount=2" in java)
            assertTrue("-XX:ActiveProcessorCount=2" in ResourceGovernor.bound(listOf("java", "-XX:ActiveProcessorCount=64", "-jar", "tool.jar")))
            assertTrue("-J-Xmx${p.toolHeapMb}m" in ResourceGovernor.bound(listOf("javac", "-J-Xmx3g", "a.java")))
            assertEquals(listOf("z3", "-in"), ResourceGovernor.bound(listOf("z3", "-in")))
        }
    }

    @Test fun `disk quota and reserve fail explicitly and release the job slot`() {
        Files.write(dir.resolve("data"), ByteArray(4096))
        val p = ResourceGovernor.plan(ResourceLimits(maxWorkBytes = 1024, minFreeDiskBytes = 0), memoryMb = 8192)
        assertFailsWith<ResourceLimitException> { ResourceGovernor.checkDisk(p, listOf(dir)) }
        assertFailsWith<ResourceLimitException> { ResourceGovernor.checkFreeDisk(p.copy(minFreeDiskBytes = Long.MAX_VALUE), dir) }
        assertFailsWith<IllegalStateException> { ResourceGovernor.withJob(p, dir.resolve("gate"), CancellationToken(), {}) { error("crash") } }
        ResourceGovernor.withJob(p, dir.resolve("gate"), CancellationToken(), {}) { assertTrue(true) }
    }
}
