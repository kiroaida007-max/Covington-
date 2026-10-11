package dev.apkproject.desktop

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ReportLoadingTest {
    @TempDir lateinit var dir: Path

    @Test fun `missing malformed oversized and successful reports are distinct`() {
        val reader = ReportReader(dir, 80)
        val strings = ListSerializer(String.serializer())
        assertNull(reader.read("absent.json", strings))
        Files.writeString(dir.resolve("invalid.json"), "[\"ok\",")
        assertNull(reader.read("invalid.json", strings))
        Files.writeString(dir.resolve("large.json"), "x".repeat(100))
        assertNull(reader.read("large.json", strings))
        Files.writeString(dir.resolve("valid.json"), "[\"ok\"]")
        assertEquals(listOf("ok"), reader.read("valid.json", strings))
        assertEquals(listOf(ReportReader.State.MISSING, ReportReader.State.INVALID, ReportReader.State.LIMIT_REACHED, ReportReader.State.LOADED), reader.statuses.values.map { it.state })
    }

    @Test fun `combined size UTF8 and nesting limits preserve incomplete status`() {
        val reader = ReportReader(dir, 512)
        Files.writeString(dir.resolve("deep.json"), "[".repeat(129) + "]".repeat(129))
        assertNull(reader.read("deep.json", ListSerializer(String.serializer())))
        assertEquals(ReportReader.State.LIMIT_REACHED, reader.statuses.getValue("deep.json").state)
        Files.write(dir.resolve("bad.txt"), byteArrayOf(0xc3.toByte(), 0x28))
        assertNull(reader.text("bad.txt"))
        assertEquals(ReportReader.State.INVALID, reader.statuses.getValue("bad.txt").state)
        Files.writeString(dir.resolve("remaining.txt"), "x".repeat(260))
        assertNull(reader.text("remaining.txt"))
        assertEquals(ReportReader.State.LIMIT_REACHED, reader.statuses.getValue("remaining.txt").state)
    }

    @Test fun `stream bound applies to actual bytes and both read methods`() {
        BoundedReportStream(ByteArrayInputStream(ByteArray(10)), 4).use { input ->
            assertEquals(0, input.read())
            assertFailsWith<ReportLimitException> { input.readNBytes(10) }
        }
        BoundedReportStream(ByteArrayInputStream(ByteArray(4)), 4).use { assertEquals(4, it.readBytes().size) }
    }

    @Test fun `corrupt audit is visible instead of an empty clean result`() {
        Files.createDirectories(dir.resolve("audit"))
        Files.writeString(dir.resolve("audit/privacy.json"), "{\"sources\": [")
        val project = Project(dir)
        assertNull(project.privacy)
        assertEquals(ReportReader.State.INVALID, project.reportStatuses.single { it.path == "audit/privacy.json" }.state)
    }

    @Test fun `changed report cannot retain the exported check`() {
        val relative = "recovery/round-trip.json"
        Files.createDirectories(dir.resolve("recovery"))
        val original = """[{"method":"A.m()V","file":"A.java","result":"EQUIVALENT_DATAFLOW"}]""" + "\n  "
        Files.writeString(dir.resolve(relative), original)
        val hash = dev.apkproject.contracts.Hashing.sha256(original)
        Files.writeString(dir.resolve("recovery/files.sha256"), "$hash  $relative\n")
        assertEquals(FileVerdict.Kind.VERIFIED, Project(dir).verdicts.getValue("A.java").kind)
        Files.writeString(dir.resolve(relative), original.replace("A.java", "B.java"))
        assertTrue(Project(dir).verdicts.isEmpty())
        val reader = ReportReader(dir).apply { expectedHashes = mapOf(relative to hash) }
        assertNull(reader.text(relative))
        assertEquals(ReportReader.State.STALE, reader.statuses.getValue(relative).state)
    }

    @Test fun `preview digest identifies edited source bytes`() {
        val source = dir.resolve("A.java")
        Files.writeString(source, "class A {}")
        val first = FilePreview.content(source)
        assertEquals(dev.apkproject.contracts.Hashing.sha256("class A {}"), first.sha256)
        Files.writeString(source, "class A { int x; }")
        assertNotEquals(first.sha256, FilePreview.content(source).sha256)
    }

    @Test fun `method report token and nesting limits discard all partial checks`() {
        val file = Files.createDirectories(dir.resolve("recovery")).resolve("round-trip.json")
        Files.writeString(file, "[{\"method\":\"" + "x".repeat(1024 * 1024 + 1) + "\"}]")
        var project = Project(dir)
        assertTrue(project.verdicts.isEmpty())
        assertEquals(ReportReader.State.LIMIT_REACHED, project.reportStatuses.single { it.path == "recovery/round-trip.json" }.state)
        Files.writeString(file, "[".repeat(129) + "]".repeat(129))
        project = Project(dir)
        assertTrue(project.verdicts.isEmpty())
        assertEquals(ReportReader.State.LIMIT_REACHED, project.reportStatuses.single { it.path == "recovery/round-trip.json" }.state)
    }
}
