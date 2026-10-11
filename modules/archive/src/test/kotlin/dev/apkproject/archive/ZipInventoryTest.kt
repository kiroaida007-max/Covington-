package dev.apkproject.archive

import dev.apkproject.contracts.Outcome
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ZipInventoryTest {
    private val reader = ZipInventoryReader()

    @Test
    fun `reads a well formed archive and extracts entries with CRC checks`() {
        val zip = jdkZip(mapOf("classes.dex" to "dex\n035\u0000body".toByteArray(), "res/a.txt" to ByteArray(5000) { 'a'.code.toByte() }))
        val inv = ok(reader.read(zip, "s"))
        assertEquals(listOf("classes.dex", "res/a.txt"), inv.entries.map { it.name })
        assertEquals(listOf("classes.dex"), inv.dexEntries().map { it.name })
        val data = ok(reader.extract(zip, inv, inv.entry("res/a.txt")!!))
        assertEquals(5000, data.size)
    }

    @Test
    fun `rejects differing local flags CRC sizes and out of range extra fields before extraction`() {
        val zip = RawZip().add("a", "hello".toByteArray()).build()
        for ((offset, width, value) in listOf(Triple(6, 2, 0x808), Triple(14, 4, 0), Triple(18, 4, 1), Triple(22, 4, 1), Triple(28, 2, 65535))) {
            val broken = zip.copyOf()
            val b = ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN)
            if (width == 2) b.putShort(offset, value.toShort()) else b.putInt(offset, value)
            assertMalformed(reader.read(broken, "s"), "local field at $offset")
        }
    }

    @Test
    fun `signed and unsigned descriptors are bounded and match central CRC and sizes`() {
        val zip = jdkZip(mapOf("a" to "hello".toByteArray()))
        val entry = ok(reader.read(zip, "s")).entries.single()
        val descriptor = (entry.dataOffset + entry.compressedSize).toInt()
        assertEquals(descriptor + 16L, entry.endOffset)
        for (offset in listOf(descriptor, descriptor + 4, descriptor + 8, descriptor + 12)) {
            val broken = zip.copyOf()
            broken[offset] = (broken[offset].toInt() xor 1).toByte()
            assertMalformed(reader.read(broken, "s"), "descriptor at $offset")
        }
        val unsigned = zip.copyOfRange(0, descriptor) + zip.copyOfRange(descriptor + 4, zip.size)
        val eocd = unsigned.size - 22
        val b = ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(eocd + 16, b.getInt(eocd + 16) - 4)
        val inv = ok(reader.read(unsigned, "s"))
        assertEquals(descriptor + 12L, inv.entries.single().endOffset)
        assertContentEquals("hello".toByteArray(), ok(reader.extract(unsigned, inv, inv.entries.single())))
        val missing = zip.copyOfRange(0, descriptor) + zip.copyOfRange(descriptor + 16, zip.size)
        val m = ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN)
        m.putInt(missing.size - 22 + 16, m.getInt(missing.size - 22 + 16) - 16)
        assertMalformed(reader.read(missing, "s"), "missing descriptor")
    }

    @Test
    fun `rejects traversal, absolute, backslash and drive names`() {
        for (bad in listOf("../evil", "a/../../b", "/etc/passwd", "a\\b", "C:/x", "a//b", "./a")) {
            val zip = RawZip().add(bad, "x".toByteArray()).build()
            assertMalformed(reader.read(zip, "s"), "name $bad")
        }
    }

    @Test
    fun `rejects duplicate names`() {
        val zip = RawZip().add("classes.dex", "a".toByteArray()).add("classes.dex", "b".toByteArray()).build()
        val f = assertMalformed(reader.read(zip, "s"), "duplicate")
        assertTrue(f.contains("duplicate"), f)
    }

    @Test
    fun `reports case-only collisions as warnings`() {
        val zip = RawZip().add("res/a.xml", "a".toByteArray()).add("res/A.xml", "b".toByteArray()).build()
        val inv = ok(reader.read(zip, "s"))
        assertEquals(1, inv.warnings.size)
    }

    @Test
    fun `rejects symlink entries`() {
        val zip = RawZip().add("lib/link", "target".toByteArray(), unixMode = 0xA1FF).build()
        assertMalformed(reader.read(zip, "s"), "symlink")
    }

    @Test
    fun `rejects a CRC mismatch while extracting`() {
        val zip = RawZip().add("a.bin", "hello".toByteArray(), crcOverride = 1234).build()
        val inv = ok(reader.read(zip, "s"))
        assertMalformed(reader.extract(zip, inv, inv.entries.single()), "crc")
    }

    @Test
    fun `rejects expansion beyond the declared size`() {
        val big = ByteArray(10_000)
        val zip = RawZip().add("bomb", big, deflate = true, sizeOverride = 100).build()
        val inv = ok(reader.read(zip, "s"))
        val f = assertMalformed(reader.extract(zip, inv, inv.entries.single()), "expansion")
        assertTrue(f.contains("beyond declared"), f)
    }

    @Test
    fun `rejects impossible compression ratios before inflating`() {
        val zip = RawZip().add("bomb", ByteArray(10), deflate = true, sizeOverride = 100_000_000).build()
        assertMalformed(reader.read(zip, "s"), "ratio")
    }

    @Test
    fun `rejects overlapping entries`() {
        val raw = RawZip().add("a", "aaaa".toByteArray()).add("b", "bbbb".toByteArray())
        raw.localOffsetOverride["b"] = 0 // central entry b points at a's local header
        assertMalformed(reader.read(raw.build(), "s"), "overlap")
    }

    @Test
    fun `truncated and garbage inputs fail without exceptions escaping`() {
        val zip = jdkZip(mapOf("classes.dex" to ByteArray(100)))
        for (cut in listOf(0, 10, 21, zip.size / 2, zip.size - 1)) {
            assertMalformed(reader.read(zip.copyOf(cut), "s"), "cut $cut")
        }
        val rnd = java.util.Random(7)
        repeat(200) {
            val junk = ByteArray(rnd.nextInt(300)).also(rnd::nextBytes)
            assertIs<Outcome.Err>(reader.read(junk, "s"))
        }
    }

    @Test
    fun `bare dex input is presented as classes dex`() {
        val dex = "dex\n035\u0000".toByteArray() + ByteArray(100)
        val snap = ok(ApkSnapshot.fromBytes(dex, "x.dex"))
        val files = ok(snap.dexFiles())
        assertEquals("classes.dex", files.single().entry.name)
        assertContentEquals(dex, files.single().bytes)
    }

    private fun <T> ok(o: Outcome<T>): T = when (o) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> fail("expected success, got ${o.failure}")
    }

    private fun assertMalformed(o: Outcome<*>, label: String): String = when (o) {
        is Outcome.Ok -> fail("$label: expected failure")
        is Outcome.Err -> o.failure.toString()
    }

    private fun jdkZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/** Writes ZIP structures byte by byte so tests can produce archives the JDK refuses to write. */
class RawZip {
    private class E(val name: String, val data: ByteArray, val deflate: Boolean, val unixMode: Int?, val crcOverride: Long?, val sizeOverride: Long?)

    private val entries = ArrayList<E>()
    val localOffsetOverride = HashMap<String, Long>()

    fun add(name: String, data: ByteArray, deflate: Boolean = false, unixMode: Int? = null, crcOverride: Long? = null, sizeOverride: Long? = null): RawZip {
        entries += E(name, data, deflate, unixMode, crcOverride, sizeOverride)
        return this
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for (e in entries) {
            val offset = out.size().toLong()
            val payload = if (e.deflate) deflate(e.data) else e.data
            val crc = e.crcOverride ?: CRC32().also { it.update(e.data) }.value
            val usize = e.sizeOverride ?: e.data.size.toLong()
            val name = e.name.toByteArray()
            val method = if (e.deflate) 8 else 0
            out.write(le(4, 0x04034b50)); out.write(le(2, 20)); out.write(le(2, 0x800)); out.write(le(2, method.toLong()))
            out.write(le(4, 0)); out.write(le(4, crc)); out.write(le(4, payload.size.toLong())); out.write(le(4, usize))
            out.write(le(2, name.size.toLong())); out.write(le(2, 0)); out.write(name); out.write(payload)
            val madeBy = if (e.unixMode != null) (3 shl 8) or 20 else 20
            central.write(le(4, 0x02014b50)); central.write(le(2, madeBy.toLong())); central.write(le(2, 20)); central.write(le(2, 0x800))
            central.write(le(2, method.toLong())); central.write(le(4, 0)); central.write(le(4, crc)); central.write(le(4, payload.size.toLong()))
            central.write(le(4, usize)); central.write(le(2, name.size.toLong())); central.write(le(2, 0)); central.write(le(2, 0))
            central.write(le(2, 0)); central.write(le(2, 0)); central.write(le(4, ((e.unixMode ?: 0).toLong() shl 16)))
            central.write(le(4, localOffsetOverride[e.name] ?: offset)); central.write(name)
        }
        val cdOffset = out.size().toLong()
        val cd = central.toByteArray()
        out.write(cd)
        out.write(le(4, 0x06054b50)); out.write(le(2, 0)); out.write(le(2, 0)); out.write(le(2, entries.size.toLong()))
        out.write(le(2, entries.size.toLong())); out.write(le(4, cd.size.toLong())); out.write(le(4, cdOffset)); out.write(le(2, 0))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data); d.finish()
        val buf = ByteArray(data.size + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private fun le(n: Int, v: Long): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
        return bb.array().copyOf(n)
    }
}
