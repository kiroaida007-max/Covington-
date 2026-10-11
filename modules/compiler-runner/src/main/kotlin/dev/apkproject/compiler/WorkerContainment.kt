package dev.apkproject.compiler

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import dev.apkproject.contracts.ResourcePlan
import dev.apkproject.contracts.isWindows
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Aggregate process-tree quotas. This does not provide filesystem or network isolation. */
object WorkerContainment {
    interface Lease : AutoCloseable { val enforced: Boolean; val description: String }

    fun attach(process: Process, plan: ResourcePlan, required: Boolean = false): Lease {
        val lease = try {
            if (isWindows()) windows(process, plan)
            else linux(process, plan, System.getenv("APKPROJECT_CGROUP_ROOT")?.let(Path::of))
        } catch (error: Exception) {
            unavailable("OS containment unavailable (${error.javaClass.simpleName})")
        } catch (error: LinkageError) { unavailable("Native containment library unavailable") }
        if (required && !lease.enforced) {
            lease.close()
            process.destroyForcibly()
            throw ResourceLimitException(lease.description)
        }
        return lease
    }

    private fun unavailable(detail: String) = object : Lease {
        override val enforced = false
        override val description = detail
        override fun close() {}
    }

    private fun linux(process: Process, plan: ResourcePlan, root: Path?): Lease {
        if (root == null) return unavailable("No delegated cgroup configured; JVM heaps and disk monitoring only")
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.getFileStore(root).type() == "cgroup2") {
            "APKPROJECT_CGROUP_ROOT must be a delegated cgroup v2 directory"
        }
        val group = Files.createDirectory(root.resolve("worker-${UUID.randomUUID()}"))
        fun write(name: String, value: String) { Files.writeString(group.resolve(name), value) }
        try {
            // All limits must succeed before any APK analysis is released by the supervisor.
            write("memory.max", (plan.memoryMb.toLong() * 1024 * 1024).toString())
            write("memory.swap.max", "0")
            write("memory.oom.group", "1")
            write("cpu.max", "${plan.cpuThreads.toLong() * 100_000} 100000")
            write("pids.max", "256") // JVM threads count as tasks in cgroup v2.
            require(Files.isRegularFile(group.resolve("cgroup.kill"))) { "cgroup.kill is required" }
            write("cgroup.procs", process.pid().toString())
        } catch (error: Exception) {
            runCatching { write("cgroup.kill", "1") }
            runCatching { Files.delete(group) }
            throw error
        }
        return object : Lease {
            override val enforced = true
            override val description = "cgroup v2: ${plan.memoryMb} MiB aggregate memory, ${plan.cpuThreads} CPUs, 256 tasks; no swap"
            override fun close() {
                runCatching { write("cgroup.kill", "1") }
                // Removal may race with the final process exit; retry on a daemon, never Swing.
                Thread({ repeat(100) { if (runCatching { Files.delete(group) }.isSuccess) return@Thread; Thread.sleep(50) } }, "cgroup-cleanup")
                    .apply { isDaemon = true; start() }
            }
        }
    }

    private interface Kernel : com.sun.jna.win32.StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, kind: Int, information: Pointer, length: Int): Boolean
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    class SizeT(value: Long = 0) : com.sun.jna.IntegerType(Native.POINTER_SIZE, value, true) {
        override fun toByte(): Byte = toLong().toByte()
        override fun toShort(): Short = toLong().toShort()
    }

    @Structure.FieldOrder("processTime", "jobTime", "flags", "minimumWorkingSet", "maximumWorkingSet", "activeProcesses", "affinity", "priority", "scheduling")
    class Basic : Structure() {
        @JvmField var processTime = 0L; @JvmField var jobTime = 0L
        @JvmField var flags = 0
        @JvmField var minimumWorkingSet = SizeT()
        @JvmField var maximumWorkingSet = SizeT()
        @JvmField var activeProcesses = 0
        @JvmField var affinity = SizeT()
        @JvmField var priority = 0; @JvmField var scheduling = 0
    }

    @Structure.FieldOrder("readOperations", "writeOperations", "otherOperations", "readBytes", "writeBytes", "otherBytes")
    class Io : Structure() {
        @JvmField var readOperations=0L; @JvmField var writeOperations=0L; @JvmField var otherOperations=0L
        @JvmField var readBytes=0L; @JvmField var writeBytes=0L; @JvmField var otherBytes=0L
    }

    @Structure.FieldOrder("basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory")
    class Extended : Structure() {
        @JvmField var basic = Basic(); @JvmField var io = Io()
        @JvmField var processMemory = SizeT()
        @JvmField var jobMemory = SizeT()
        @JvmField var peakProcessMemory = SizeT()
        @JvmField var peakJobMemory = SizeT()
    }

    @Structure.FieldOrder("flags", "rate")
    class Cpu : Structure() { @JvmField var flags=5; @JvmField var rate=0 }

    private fun windows(process: Process, plan: ResourcePlan): Lease {
        // Native Windows BOOLs are 32-bit; JNA's Win32 mapper also selects stdcall on x86.
        val kernel = Native.load("kernel32", Kernel::class.java, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS)
        val job = kernel.CreateJobObjectW(null, null) ?: error("CreateJobObject failed")
        try {
            val limits = Extended().apply {
                basic.flags = 0x2000 or 0x0200 or 0x0008 // kill on close, aggregate committed memory, process count
                basic.activeProcesses = 32
                jobMemory = SizeT(plan.memoryMb.toLong() * 1024 * 1024)
                write()
            }
            check(kernel.SetInformationJobObject(job, 9, limits.pointer, limits.size())) { "Job memory/process limits failed" }
            val cpu = Cpu().apply { rate = (plan.cpuThreads.toLong() * 10_000 / Runtime.getRuntime().availableProcessors()).coerceIn(1, 10_000).toInt(); write() }
            check(kernel.SetInformationJobObject(job, 15, cpu.pointer, cpu.size())) { "Job CPU limit failed" }
            val child = kernel.OpenProcess(0x0100 or 0x0001, false, Math.toIntExact(process.pid())) ?: error("OpenProcess failed")
            try { check(kernel.AssignProcessToJobObject(job, child)) { "AssignProcessToJobObject failed" } }
            finally { kernel.CloseHandle(child) }
        } catch (error: Throwable) { kernel.CloseHandle(job); throw error }
        return object : Lease {
            override val enforced = true
            override val description = "Windows Job Object: ${plan.memoryMb} MiB aggregate committed memory, ${plan.cpuThreads} CPUs, 32 processes"
            private val closed = java.util.concurrent.atomic.AtomicBoolean()
            override fun close() { if (closed.compareAndSet(false, true)) kernel.CloseHandle(job) }
        }
    }
}
