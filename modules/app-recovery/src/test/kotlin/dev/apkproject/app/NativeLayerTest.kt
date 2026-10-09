package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLayerTest {
    @Test
    fun `jni symbols decode with the escape rules`() {
        assertEquals("com/example/Foo" to "bar", NativeMapper.decodeJni("Java_com_example_Foo_bar"))
        assertEquals("com/example/Foo" to "bar", NativeMapper.decodeJni("Java_com_example_Foo_bar__ILjava_lang_String_2"))
        assertEquals("com/my_app/Native" to "do_it", NativeMapper.decodeJni("Java_com_my_1app_Native_do_1it"))
        assertEquals("com/example/Outer\$Inner" to "f", NativeMapper.decodeJni("Java_com_example_Outer_00024Inner_f"))
        assertNull(NativeMapper.decodeJni("JNI_OnLoad"))
        assertNull(NativeMapper.decodeJni("Java_bad"))
    }

    @Test
    fun `elf reader rejects non elf input without throwing`() {
        assertTrue(ElfReader.read(ByteArray(64)) is Outcome.Err)
        assertTrue(ElfReader.read("\u007fELF".toByteArray()) is Outcome.Err)
        // 64-bit header whose section table lies past the end of the file.
        val truncated = ByteArray(64).also { b ->
            "\u007fELF".toByteArray().copyInto(b); b[4] = 2; b[5] = 1
            b[0x28] = 0x00; b[0x29] = 0x10 // e_shoff = 0x1000
            b[0x3A] = 64 // e_shentsize
            b[0x3C] = 4 // e_shnum
        }
        assertTrue(ElfReader.read(truncated) is Outcome.Err)
    }

    @Test
    fun `elf reader lists exported functions and needed libraries of a real shared object`() {
        val lib = listOf("/lib/x86_64-linux-gnu/libz.so.1", "/usr/lib/x86_64-linux-gnu/libz.so.1", "/lib64/libz.so.1", "/usr/lib/libz.so.1")
            .map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }
        assumeTrue(lib != null, "no system libz to read")
        val summary = (ElfReader.read(Files.readAllBytes(lib!!.toRealPath()), "libz") as Outcome.Ok).value
        assertEquals(64, summary.bits)
        assertTrue("inflate" in summary.exportedFunctions && "deflate" in summary.exportedFunctions, summary.exportedFunctions.take(10).toString())
        assertTrue(summary.needed.any { it.startsWith("libc.so") }, summary.needed.toString())
    }
}
