package dev.apkproject.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

class RecoveryCheckpointsTest {
    @TempDir lateinit var dir: Path

    @Test fun `restores immutable stage only for the same identity`() {
        val source = Files.createDirectories(dir.resolve("source"))
        Files.writeString(source.resolve("Demo.java"), "original verified source")
        val cache = RecoveryCheckpoints(dir.resolve("cache"))
        cache.save("verification", "identity", source, "payload")
        Files.writeString(source.resolve("Demo.java"), "later repair")
        val target = Files.createDirectories(dir.resolve("target"))
        Files.writeString(target.resolve("stale.java"), "stale")
        assertNull(cache.restore("verification", "different-input", target))
        assertEquals("stale", Files.readString(target.resolve("stale.java")))
        assertEquals("payload", cache.restore("verification", "identity", target))
        assertEquals("original verified source", Files.readString(target.resolve("Demo.java")))
        assertFalse(Files.exists(target.resolve("stale.java")))
    }

    @Test fun `rejects corrupt missing extra and incomplete checkpoint files before touching output`() {
        val source = Files.createDirectories(dir.resolve("source"))
        Files.writeString(source.resolve("a"), "abc")
        val root = dir.resolve("cache")
        val cache = RecoveryCheckpoints(root)
        val target = Files.createDirectories(dir.resolve("target"))
        Files.writeString(target.resolve("keep"), "untouched")
        for (fault in listOf("corrupt", "missing", "extra", "incomplete")) {
            cache.save("decompile", "id", source, "metadata")
            val tree = root.resolve("decompile/tree")
            when (fault) {
                "corrupt" -> Files.writeString(tree.resolve("a"), "abd")
                "missing" -> Files.delete(tree.resolve("a"))
                "extra" -> Files.writeString(tree.resolve("extra"), "x")
                else -> Files.writeString(root.resolve("decompile/checkpoint.json"), "{")
            }
            assertNull(cache.restore("decompile", "id", target), fault)
            assertEquals("untouched", Files.readString(target.resolve("keep")), fault)
        }
    }
}
