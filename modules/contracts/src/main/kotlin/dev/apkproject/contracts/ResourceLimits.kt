package dev.apkproject.contracts

import kotlinx.serialization.Serializable

@Serializable
data class ResourceLimits(
    /** Zero selects a host/cgroup-aware budget. */
    val memoryMb: Int = 0,
    val cpuThreads: Int = 0,
    val maxWorkBytes: Long = 8L * 1024 * 1024 * 1024,
    val minFreeDiskBytes: Long = 256L * 1024 * 1024,
)

@Serializable
data class ResourcePlan(val memoryMb: Int, val cpuThreads: Int, val engineHeapMb: Int, val toolHeapMb: Int,
    val maxWorkBytes: Long, val minFreeDiskBytes: Long)
