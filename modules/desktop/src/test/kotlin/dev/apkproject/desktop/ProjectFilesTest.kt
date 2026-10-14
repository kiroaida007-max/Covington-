package dev.apkproject.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class ProjectFilesTest {
    @TempDir lateinit var dir: Path

    private fun link(path: Path, target: Path) {
        assumeTrue(runCatching { Files.createSymbolicLink(path, target) }.isSuccess, "Host does not permit symbolic links")
    }

    @Test fun `linked reports and source roots are withheld and never read`() {
        val project = Files.createDirectory(dir.resolve("project"))
        val outside = Files.createDirectory(dir.resolve("outside"))
        Files.writeString(outside.resolve("privacy.json"), "sensitive external contents")
        Files.writeString(outside.resolve("A.java"), "sensitive external source")
        Files.createDirectories(project.resolve("audit"))
        Files.createDirectories(project.resolve("app/src/main"))
        link(project.resolve("audit/privacy.json"), outside.resolve("privacy.json"))
        link(project.resolve("app/src/main/java"), outside)
        val loaded = Project(project)
        assertNull(loaded.privacy)
        assertTrue(loaded.sourceFolders.isEmpty())
        assertEquals(ReportReader.State.BLOCKED, loaded.reportStatuses.single { it.path == "audit/privacy.json" }.state)
        assertEquals(ReportReader.State.BLOCKED, loaded.reportStatuses.single { it.path == "app/src/main/java" }.state)
        assertFailsWith<ProjectAccessException> { FilePreview.content(project.resolve("app/src/main/java/A.java"), loaded.files) }
    }

    @Test fun `internal links traversal and nonregular files are also rejected`() {
        val root = Files.createDirectory(dir.resolve("project"))
        val content = root.resolve("A.java")
        Files.writeString(content, "class A {}")
        val files = ProjectFiles(root)
        assertEquals("class A {}", files.input(content).use { it.readBytes().decodeToString() })
        assertFailsWith<ProjectAccessException> { files.input(root.resolve("../outside.txt")) }
        assertFailsWith<ProjectAccessException> { files.input(root) }
        link(root.resolve("alias.java"), content)
        assertFailsWith<ProjectAccessException> { files.input(root.resolve("alias.java")) }
    }

    @Test fun `folder replacement cannot redirect preview or runtime writes`() {
        val root = Files.createDirectory(dir.resolve("project"))
        Files.createDirectories(root.resolve("runtime"))
        Files.writeString(root.resolve("A.java"), "original")
        val files = ProjectFiles(root)
        assertEquals(root.resolve("runtime/sessions/new"), files.outputDirectory(root.resolve("runtime/sessions/new")))
        val outside = Files.createDirectory(dir.resolve("outside"))
        Files.move(root.resolve("runtime"), root.resolve("old-runtime"))
        link(root.resolve("runtime"), outside)
        assertFailsWith<ProjectAccessException> { files.outputDirectory(root.resolve("runtime/sessions/escape")) }
        assertFalse(Files.exists(outside.resolve("sessions")))
        Files.move(root, dir.resolve("old-project"))
        Files.createDirectory(root)
        Files.writeString(root.resolve("A.java"), "replaced")
        assertFailsWith<ProjectAccessException> { files.input(root.resolve("A.java")) }
    }
}
