package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ResourceLimitException
import dev.apkproject.contracts.Hashing
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AssistantSnapshotTest {
    @TempDir lateinit var dir: Path
    private fun snapshot(badHash: Boolean = false): AssistantSnapshot {
        val model = dir.resolve("original-model"); val exe = dir.resolve("original-runtime")
        Files.writeString(model, "model"); Files.writeString(exe, "runtime")
        return AssistantSnapshot(listOf(
            SnapshotArtifact(model, dir.resolve("stage/model.gguf"), if (badHash) "0".repeat(64) else Hashing.sha256(model), 5, 5),
            SnapshotArtifact(exe, dir.resolve("stage/runtime/completion"), Hashing.sha256(exe), 7)),
            dir.resolve("stage/runtime"), dir.resolve("stage/runtime/completion"))
    }

    @Test fun `unused models allocate no snapshot and first use copies verified immutable bytes and purges stale libraries`() {
        val snapshot = snapshot()
        assertEquals("UNPREPARED", snapshot.state)
        assertEquals(0, snapshot.stagedBytes())
        assertFalse(Files.exists(dir.resolve("stage")))
        Files.createDirectories(dir.resolve("stage/runtime")); Files.writeString(dir.resolve("stage/runtime/unlisted.dll"), "old")
        snapshot.ensure(null, System.nanoTime() + 5_000_000_000)
        assertEquals("VERIFIED", snapshot.state)
        assertEquals(12, snapshot.stagedBytes())
        assertFalse(Files.exists(dir.resolve("stage/runtime/unlisted.dll")))
        Files.writeString(dir.resolve("original-model"), "other")
        snapshot.ensure(null, System.nanoTime() + 5_000_000_000)
        assertEquals("model", Files.readString(dir.resolve("stage/model.gguf")))
    }

    @Test fun `corrupted artifacts fail closed and do not leave temporary copies`() {
        val snapshot = snapshot(badHash = true)
        assertFailsWith<IllegalArgumentException> { snapshot.ensure(null, System.nanoTime() + 5_000_000_000) }
        assertEquals("FAILED", snapshot.state)
        assertFalse(Files.exists(dir.resolve("stage/model.gguf")))
        assertFalse(Files.exists(dir.resolve("stage/runtime/completion")))
        assertTrue(Files.list(dir.resolve("stage")).use { it.noneMatch { path -> path.fileName.toString().endsWith(".partial") } })
        assertFailsWith<IllegalArgumentException> { snapshot.ensure(null, Long.MAX_VALUE) }
    }

    @Test fun `preparation honors cancellation and deadlines before copying artifacts`() {
        val cancelled = snapshot()
        val token = CancellationToken().apply { cancel() }
        assertFailsWith<CancelledException> { cancelled.ensure(token, Long.MAX_VALUE) }
        assertFalse(Files.exists(dir.resolve("stage")))
        val expired = snapshot()
        assertFailsWith<ResourceLimitException> { expired.ensure(null, System.nanoTime() - 1) }
        assertFalse(Files.exists(dir.resolve("stage")))
    }
}
