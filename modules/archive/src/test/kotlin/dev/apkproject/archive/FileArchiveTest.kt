package dev.apkproject.archive

import dev.apkproject.contracts.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileArchiveTest {
    @TempDir lateinit var dir: Path

    @Test fun `large stored asset is inventoried in a 32 MB JVM without reading its payload`() {
        val apk = dir.resolve("large.apk")
        val chunk = ByteArray(8192)
        val count = 96 * 1024 * 1024 / chunk.size
        val crc = CRC32().also { c -> repeat(count) { c.update(chunk) } }.value
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("small")); zip.write("checked".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/large.bin").apply { method = ZipEntry.STORED; size = count.toLong() * chunk.size; compressedSize = size; this.crc = crc })
            repeat(count) { zip.write(chunk) }; zip.closeEntry()
        }
        val java = Path.of(System.getProperty("java.home"), "bin", if (dev.apkproject.contracts.isWindows()) "java.exe" else "java")
        val log = dir.resolve("probe.log")
        val process = ProcessBuilder(java.toString(), "-Xmx32m", "-cp", System.getProperty("apkproject.archiveProbe.classpath"),
            ArchiveMemoryProbe::class.java.name, apk.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "memory probe timed out")
            assertEquals(0, process.exitValue(), Files.readString(log))
            assertTrue(Files.readString(log).contains("checked"))
        } finally { process.destroyForcibly() }
    }

    @Test fun `file and byte readers agree on mutated inventories and extraction`() {
        val bytes = java.io.ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write(ByteArray(10000) { it.toByte() }); zip.closeEntry()
        } }.toByteArray()
        val random = java.util.Random(1984)
        val reader = ZipInventoryReader()
        repeat(200) {
            val mutated = bytes.copyOf()
            if (it > 0) mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
            val file = Files.write(dir.resolve("case.zip"), mutated)
            val a = reader.read(mutated, "test")
            val b = reader.read(file, "test")
            assertEquals(a, b)
            if (a is Outcome.Ok && b is Outcome.Ok) for (entry in a.value.entries) {
                val x = reader.extract(mutated, a.value, entry)
                val y = reader.extract(file, b.value, entry)
                if (x is Outcome.Ok && y is Outcome.Ok) assertContentEquals(x.value, y.value) else assertEquals(x, y)
            }
        }
    }
}

object ArchiveMemoryProbe {
    @JvmStatic fun main(args: Array<String>) {
        val path = Path.of(args.single())
        val reader = ZipInventoryReader()
        val inventory = reader.read(path, "probe").getOrThrow()
        check(inventory.entries.size == 2)
        println(String(reader.extract(path, inventory, inventory.entry("small")!!).getOrThrow()))
    }
}
