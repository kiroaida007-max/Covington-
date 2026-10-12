package dev.apkproject.compiler

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import dev.apkproject.contracts.isWindows
import java.nio.file.Files
import java.nio.file.Path

/** A sampled resident-memory bound, separate from optional aggregate OS containment. */
object NativeProcessMemory {
    fun residentBytes(process: ProcessHandle): Long? = try {
        if (isWindows()) windows(process.pid()) else {
            val bytes = Files.newInputStream(Path.of("/proc/${process.pid()}/status")).use { it.readNBytes(32_769) }
            if (bytes.size > 32_768) null else Regex("(?m)^VmRSS:\\s+(\\d+)\\s+kB$")
                .find(bytes.toString(Charsets.US_ASCII))?.groupValues?.get(1)?.toLongOrNull()?.let { Math.multiplyExact(it, 1024) }
        }
    } catch (_: Exception) { null } catch (_: LinkageError) { null }

    private interface Kernel : com.sun.jna.win32.StdCallLibrary {
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
    }
    private interface Psapi : com.sun.jna.win32.StdCallLibrary {
        fun GetProcessMemoryInfo(process: Pointer, counters: Pointer, size: Int): Boolean
    }
    @Structure.FieldOrder("cb", "pageFaultCount", "peakWorkingSetSize", "workingSetSize", "quotaPeakPagedPoolUsage", "quotaPagedPoolUsage",
        "quotaPeakNonPagedPoolUsage", "quotaNonPagedPoolUsage", "pagefileUsage", "peakPagefileUsage")
    class Counters : Structure() {
        @JvmField var cb = 0; @JvmField var pageFaultCount = 0
        @JvmField var peakWorkingSetSize = WorkerContainment.SizeT(); @JvmField var workingSetSize = WorkerContainment.SizeT()
        @JvmField var quotaPeakPagedPoolUsage = WorkerContainment.SizeT(); @JvmField var quotaPagedPoolUsage = WorkerContainment.SizeT()
        @JvmField var quotaPeakNonPagedPoolUsage = WorkerContainment.SizeT(); @JvmField var quotaNonPagedPoolUsage = WorkerContainment.SizeT()
        @JvmField var pagefileUsage = WorkerContainment.SizeT(); @JvmField var peakPagefileUsage = WorkerContainment.SizeT()
    }
    private val kernel by lazy { Native.load("kernel32", Kernel::class.java, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS) }
    private val psapi by lazy { Native.load("psapi", Psapi::class.java, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS) }
    private fun windows(pid: Long): Long? {
        val handle = kernel.OpenProcess(0x0400 or 0x0010, false, Math.toIntExact(pid)) ?: return null
        try {
            val counters = Counters().apply { cb = size(); write() }
            if (!psapi.GetProcessMemoryInfo(handle, counters.pointer, counters.size())) return null
            counters.read()
            return counters.workingSetSize.toLong().takeIf { it >= 0 }
        } finally { kernel.CloseHandle(handle) }
    }
}
