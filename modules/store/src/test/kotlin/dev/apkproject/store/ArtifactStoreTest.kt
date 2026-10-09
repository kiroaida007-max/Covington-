package dev.apkproject.store

import dev.apkproject.contracts.Hashing
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ArtifactStoreTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `stores content by hash and reads it back`() {
        val store = ArtifactStore(dir)
        val bytes = "hello".toByteArray()
        val ref = store.put(bytes, "a/b.txt", "text/plain")
        assertEquals(Hashing.sha256(bytes), ref.sha256)
        assertEquals(5L, ref.byteSize)
        assertContentEquals(bytes, store.read(ref))
        // identical content is stored once
        store.put(bytes, "other.txt", "text/plain")
        assertEquals(1, Files.list(store.pathFor(ref.sha256).parent).count())
    }

    @Test
    fun `detects corrupted artifacts`() {
        val store = ArtifactStore(dir)
        val ref = store.put("payload".toByteArray(), "p", "text/plain")
        Files.writeString(store.pathFor(ref.sha256), "tampered")
        assertThrows<IllegalStateException> { store.read(ref) }
    }
}
