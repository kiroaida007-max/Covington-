package dev.apkproject.contracts

object BuildInfo {
    val version: String = BuildInfo::class.java.getResourceAsStream("/engine-version.txt")?.use { String(it.readAllBytes()).trim() } ?: "0.3.0"
}
