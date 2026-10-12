package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ResourceLimitException
import dev.apkproject.store.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class SnapshotArtifact(val source: Path, val target: Path, val sha256: String, val limit: Long, val exactBytes: Long? = null)

/** Do not copy large optional artifacts until a valid proposal actually needs inference. */
internal class AssistantSnapshot(private val artifacts: List<SnapshotArtifact>, private val runtime: Path, private val executable: Path) {
    var state = "UNPREPARED"; private set
    var preparationMs = 0L; private set
    fun stagedBytes(): Long = artifacts.sumOf { runCatching { if (Files.isRegularFile(it.target)) Files.size(it.target) else 0L }.getOrDefault(0) }

    fun ensure(cancel: CancellationToken?, deadline: Long) {
        if (state == "VERIFIED") return
        require(state != "FAILED") { "assistant snapshot previously failed" }
        val started = System.nanoTime()
        try {
            checkBudget(cancel, deadline)
            AtomicFiles.deleteRecursively(runtime) // Never load unlisted native libraries left by a previous job attempt.
            for (artifact in artifacts) copyPinned(artifact, cancel, deadline)
            require(executable.toFile().setExecutable(true, true) || Files.isExecutable(executable))
            require(Files.isExecutable(executable))
            state = "VERIFIED"
        } catch (error: Exception) { state = "FAILED"; throw error }
        finally { preparationMs += (System.nanoTime() - started) / 1_000_000 }
    }

    companion object {
        private fun checkBudget(cancel: CancellationToken?, deadline: Long) {
            if (cancel?.cancelled == true) throw CancelledException()
            if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) throw ResourceLimitException("assistant preparation deadline")
        }

        fun copyPinned(artifact: SnapshotArtifact, cancel: CancellationToken? = null, deadline: Long = Long.MAX_VALUE) {
            fun checkedHash(path: Path, output: java.io.OutputStream? = null): String {
                val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        checkBudget(cancel, deadline)
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= artifact.limit) { "assistant artifact exceeds size bound" }
                        digest.update(buffer, 0, count); output?.write(buffer, 0, count)
                    }
                }
                require(artifact.exactBytes == null || total == artifact.exactBytes) { "assistant artifact size changed" }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
            checkBudget(cancel, deadline)
            require(Files.isRegularFile(artifact.source) && Files.size(artifact.source) <= artifact.limit)
            if (Files.isRegularFile(artifact.target) && Files.size(artifact.target) <= artifact.limit &&
                runCatching { checkedHash(artifact.target) }.getOrElse { error ->
                    if (error is CancelledException || error is ResourceLimitException) throw error
                    "invalid"
                } == artifact.sha256) return
            Files.createDirectories(artifact.target.parent)
            val temp = Files.createTempFile(artifact.target.parent, ".assistant-", ".partial")
            try {
                val hash = Files.newOutputStream(temp).use { checkedHash(artifact.source, it) }
                require(hash == artifact.sha256) { "assistant artifact hash changed" }
                Files.move(temp, artifact.target, StandardCopyOption.REPLACE_EXISTING)
            } finally { runCatching { Files.deleteIfExists(temp) } }
        }
    }
}
