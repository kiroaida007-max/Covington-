package dev.apkproject.dex

import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.Outcome
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Random
import java.util.zip.Adler32
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class MalformedDexTest {
    private val good = javaClass.getResourceAsStream("/d8/broad.dex")!!.readBytes()

    private fun err(bytes: ByteArray, limits: DexLimits = DexLimits()): String = when (val o = DexFile.parse(bytes, limits = limits)) {
        is Outcome.Ok -> fail("expected a failure")
        is Outcome.Err -> o.failure.toString()
    }

    @Test
    fun `rejects bad magic, version, sizes and checksums`() {
        assertContains(err(good.copyOf(50)), "shorter than")
        assertContains(err(good.clone().also { it[0] = 'D'.code.toByte() }), "magic")
        assertContains(err(fixChecksums(good.clone().also { it[6] = '6'.code.toByte() })), "036")
        val v41 = fixChecksums(good.clone().also { it[5] = '4'.code.toByte(); it[6] = '1'.code.toByte() })
        val f41 = (DexFile.parse(v41) as Outcome.Err).failure
        assertTrue(f41.unsupported, "041 is a known but unsupported version")
        assertContains(err(good + ByteArray(4)), "file_size")
        assertContains(err(good.clone().also { it[200] = (it[200] + 1).toByte() }), "checksum")
    }

    @Test
    fun `reports the section and offset of a broken table`() {
        val bytes = good.clone()
        // Point string_ids past the end of the file.
        writeU4(bytes, 0x3c, bytes.size.toLong())
        val message = err(fixChecksums(bytes))
        assertContains(message, "string_ids")
    }

    @Test
    fun `every truncation fails cleanly`() {
        for (cut in 0 until good.size step 37) {
            assertIs<Outcome.Err>(DexFile.parse(good.copyOf(cut)), "cut at $cut")
        }
    }

    @Test
    fun `random corruption never escapes as an unexpected exception`() {
        val rnd = Random(20261009)
        var accepted = 0
        repeat(3000) {
            val bytes = good.clone()
            repeat(1 + rnd.nextInt(4)) {
                val p = 0x70 + rnd.nextInt(bytes.size - 0x70)
                bytes[p] = (bytes[p].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }
            fixChecksums(bytes)
            try {
                when (val o = DexFile.parse(bytes)) {
                    is Outcome.Ok -> {
                        accepted++
                        // Whatever parses must also decode or fail with a typed error, never crash.
                        for (m in o.value.methods()) {
                            val code = m.code ?: continue
                            InstructionDecoder.decode(o.value, code)
                            Disassembler.method(m)
                        }
                    }
                    is Outcome.Err -> Unit
                }
            } catch (e: FormatException) {
                // Lazily parsed sections (debug info) report typed failures; acceptable.
            } catch (e: Throwable) {
                throw AssertionError("unexpected ${e::class.simpleName} for corrupted input", e)
            }
        }
        assertTrue(accepted < 3000, "corruption should usually be detected")
    }

    companion object {
        fun writeU4(b: ByteArray, p: Int, v: Long) {
            for (i in 0 until 4) b[p + i] = (v ushr (8 * i)).toByte()
        }

        fun fixChecksums(b: ByteArray): ByteArray {
            val sha1 = MessageDigest.getInstance("SHA-1").also { it.update(b, 32, b.size - 32) }.digest()
            System.arraycopy(sha1, 0, b, 12, 20)
            writeU4(b, 8, Adler32().also { it.update(b, 12, b.size - 12) }.value)
            return b
        }
    }
}
