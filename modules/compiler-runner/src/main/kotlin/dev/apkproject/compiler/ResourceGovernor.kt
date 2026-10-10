package dev.apkproject.compiler

import dev.apkproject.contracts.ResourceLimits
import dev.apkproject.contracts.ResourcePlan
import dev.apkproject.contracts.isWindows
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class ResourceLimitException(message: String) : RuntimeException(message)

/** One heavy job at a time per user, across GUI/CLI processes; bounded tools within that job. */
object ResourceGovernor {
    private data class Context(val plan: ResourcePlan, val tools: Semaphore = Semaphore(1, true))
    @Volatile private var context: Context? = null

    fun defaultRoot(): Path {
        val base = if (isWindows()) System.getenv("LOCALAPPDATA")?.let(Path::of)
            else System.getenv("XDG_DATA_HOME")?.let(Path::of)
        return (base ?: Path.of(System.getProperty("user.home"), ".local", "share")).resolve("apkproject/runtime")
    }

    fun hostMemoryMb(): Long {
        val physical = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize ?: (4L shl 30)
        val bounds = listOf("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes")
            .mapNotNull { path -> runCatching { Files.readString(Path.of(path)).trim().toLongOrNull() }.getOrNull() }
            .filter { it > 0 }
        return (listOf(physical) + bounds).min() / (1024 * 1024)
    }

    fun plan(limits: ResourceLimits = ResourceLimits(), memoryMb: Long = hostMemoryMb(), cpus: Int = Runtime.getRuntime().availableProcessors()): ResourcePlan {
        require(limits.memoryMb >= 0 && limits.cpuThreads >= 0 && limits.maxWorkBytes > 0 && limits.minFreeDiskBytes >= 0)
        val available = (memoryMb * 65 / 100).coerceAtMost(8192).toInt()
        val total = if (limits.memoryMb == 0) available else minOf(limits.memoryMb, available)
        require(total >= 768) { "insufficient memory for isolated recovery (need at least 768 MB after host reserve)" }
        val cpu = minOf(if (limits.cpuThreads == 0) minOf(8, total / 512) else limits.cpuThreads, cpus).coerceAtLeast(1)
        val engine = (total / 3).coerceIn(256, 2048)
        val tool = total - engine - 256 // native overhead within the shared job budget
        return ResourcePlan(total, cpu, engine, tool, limits.maxWorkBytes, limits.minFreeDiskBytes)
    }

    fun <T> withJob(plan: ResourcePlan, root: Path, cancel: CancellationToken, log: (String) -> Unit, block: () -> T): T {
        globalLease(root, cancel, log).use {
            check(context == null)
            context = Context(plan)
            try { return block() } finally { context = null }
        }
    }

    fun toolLease(cancel: CancellationToken?): AutoCloseable {
        val c = context ?: return globalLease(defaultRoot(), cancel, {})
        while (!c.tools.tryAcquire(100, TimeUnit.MILLISECONDS)) if (cancel?.cancelled == true) throw CancelledException()
        if (cancel?.cancelled == true) { c.tools.release(); throw CancelledException() }
        return AutoCloseable { c.tools.release() }
    }

    fun bound(argv: List<String>): List<String> {
        val plan = context?.plan ?: plan()
        val java = Path.of(argv.first()).fileName.toString().lowercase() in setOf("java", "java.exe")
        val javac = Path.of(argv.first()).fileName.toString().lowercase() in setOf("javac", "javac.exe")
        if (!java && !javac) return argv
        val heap = Regex("(-J)?-Xmx(\\d+)([kKmMgG]?)")
        val processor = if (javac) "-J-XX:ActiveProcessorCount=${plan.cpuThreads}" else "-XX:ActiveProcessorCount=${plan.cpuThreads}"
        val result = argv.map { arg ->
            val m = heap.matchEntire(arg)
            if (m == null) arg else {
                val n = m.groupValues[2].toLong()
                val mb = when (m.groupValues[3].lowercase()) { "g" -> n * 1024; "m" -> n; "k" -> n / 1024; else -> n / (1024 * 1024) }
                "${m.groupValues[1]}-Xmx${minOf(mb, plan.toolHeapMb.toLong()).coerceAtLeast(1)}m"
            }
        }.toMutableList()
        if (result.none { heap.matches(it) }) result.add(1, "${if (javac) "-J" else ""}-Xmx${plan.toolHeapMb}m")
        if (result.none { it.startsWith("${if (javac) "-J" else ""}-XX:ActiveProcessorCount=") }) result.add(1, processor)
        return result
    }

    fun checkDisk(plan: ResourcePlan, roots: List<Path>) {
        var bytes = 0L
        for (root in roots.distinct()) {
            if (!Files.exists(root)) continue
            checkFreeDisk(plan, root)
            Files.walk(root).use { paths -> paths.forEach { path ->
                if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) bytes += Files.size(path)
                if (bytes > plan.maxWorkBytes) throw ResourceLimitException("job exceeded its ${plan.maxWorkBytes}-byte disk budget")
            } }
        }
    }

    fun checkFreeDisk(plan: ResourcePlan, path: Path) {
        var existing = path.toAbsolutePath()
        while (!Files.exists(existing)) existing = existing.parent ?: throw ResourceLimitException("no filesystem for $path")
        if (Files.getFileStore(existing).usableSpace < plan.minFreeDiskBytes) throw ResourceLimitException("disk reserve reached at $path")
    }

    private fun globalLease(root: Path, cancel: CancellationToken?, log: (String) -> Unit): AutoCloseable {
        Files.createDirectories(root)
        val channel = FileChannel.open(root.resolve("heavy-job.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        var lock: FileLock? = null
        try {
            var announced = false
            while (lock == null) {
                if (cancel?.cancelled == true) throw CancelledException()
                lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) {
                    if (!announced) { log("resources: waiting for another heavy job to finish"); announced = true }
                    Thread.sleep(100)
                }
            }
            return AutoCloseable { lock.release(); channel.close() }
        } catch (e: Throwable) { channel.close(); throw e }
    }
}
