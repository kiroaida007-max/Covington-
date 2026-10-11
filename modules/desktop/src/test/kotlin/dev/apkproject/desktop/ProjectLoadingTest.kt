package dev.apkproject.desktop

import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.SwingUtilities
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProjectLoadingTest {
    private fun all(c: Component): List<Component> = listOf(c) +
        if (c is Container) c.components.flatMap(::all) else emptyList()

    @Test
    fun `superseded reads cannot update Swing and waiting requests stay bounded`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val failures = AtomicReference<Throwable>()
        val results = mutableListOf<Int>()
        val task = LatestTask("test-latest-reader")
        try {
            SwingUtilities.invokeAndWait {
                task.submit({
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    entered.countDown()
                    // Model a filesystem read which does not honour interruption immediately.
                    while (!release.awaitIgnoringInterrupt()) { }
                    1
                }, { results.add(it) }, { failures.set(it) })
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                repeat(100) { n ->
                    task.submit({ n + 2 }, {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        results.add(it)
                        complete.countDown()
                    }, { failures.set(it); complete.countDown() })
                }
            }
            release.countDown()
            assertTrue(complete.await(10, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                assertEquals(listOf(101), results)
                assertEquals(null, failures.get())
                assertFalse(task.isBusy)
            }
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { task.close() }
        }
    }

    @Test
    fun `closing a reader suppresses a delayed completion`() {
        val read = CountDownLatch(1)
        val task = LatestTask("test-disposed-reader")
        var called = false
        SwingUtilities.invokeAndWait {
            task.submit({ read.countDown(); 1 }, { called = true }, { called = true })
            assertTrue(read.await(10, TimeUnit.SECONDS))
            task.close()
        }
        SwingUtilities.invokeAndWait { assertFalse(called) }
    }

    @Test
    fun `a failed read reaches the error callback and a later read still succeeds`() {
        val failed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val task = LatestTask("test-failed-reader")
        val error = AtomicReference<Exception>()
        try {
            SwingUtilities.invokeAndWait {
                task.submit<Int>({ throw java.io.IOException("deleted") }, { error.set(IllegalStateException("unexpected success")); failed.countDown() }, {
                    error.set(it); failed.countDown()
                })
            }
            assertTrue(failed.await(10, TimeUnit.SECONDS))
            assertEquals("deleted", error.get().message)
            SwingUtilities.invokeAndWait { task.submit({ 2 }, { done.countDown() }, { error.set(it); done.countDown() }) }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals("deleted", error.get().message)
        } finally { SwingUtilities.invokeAndWait { task.close() } }
    }

    @Test
    fun `a truncated round trip cannot claim that its valid prefix is verified`(@TempDir dir: Path) {
        val recovery = Files.createDirectories(dir.resolve("recovery"))
        Files.writeString(recovery.resolve("round-trip.json"), """[{"method":"A.m()V","file":"A.java","result":"EQUIVALENT_DATAFLOW"},{"method":"broken"""")
        assertTrue(Project(dir).verdicts.isEmpty())
    }

    @Test
    fun `streamed counters retain synthetic null different and relaxed diagnostic semantics`(@TempDir dir: Path) {
        val recovery = Files.createDirectories(dir.resolve("recovery"))
        Files.writeString(recovery.resolve("round-trip.json"), """[
            {"method":"A.a()V","file":"A.java","result":"EQUIVALENT_DATAFLOW"},
            {"method":"A.b()V","file":"A.java","result":"SIMILAR_OPERATIONS"},
            {"method":"A.c()V","file":"A.java","result":"NOT_COMPILED"},
            {"method":"A.d()V","file":"A.java","result":"SYNTHETIC"},
            {"method":"A.e()V","file":null,"result":"DIFFERENT"}]
        """)
        Files.writeString(recovery.resolve("compile-diagnostics.json"), """[{"path":"A.java","errors":3,"firstErrors":[]},{"path":"B.java","errors":2,"firstErrors":["error"]}]""")
        Files.writeString(recovery.resolve("javac-relaxed.txt"), "A.java\n")
        val project = Project(dir)
        assertEquals(FileVerdict(3, 1, 1, 1, 0, emptyList()), project.verdicts["A.java"])
        assertEquals(FileVerdict.Kind.NOT_COMPILING, project.verdicts.getValue("B.java").kind)
        assertEquals(2, project.verdicts.size)
    }

    @Test
    fun `preview enforces the current file size and reports invalid UTF8`(@TempDir dir: Path) {
        val path = dir.resolve("Large.java")
        Files.writeString(path, "class Large {}")
        assertEquals("class Large {}", FilePreview.read(path))
        java.io.RandomAccessFile(path.toFile(), "rw").use { it.setLength(FilePreview.MAX_BYTES.toLong() + 1) }
        assertTrue("too large" in FilePreview.read(path))
        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<java.nio.charset.CharacterCodingException> { FilePreview.read(path) }
    }

    @Test
    fun `folder selection clears a previous preview and source reveal waits for background listing`(@TempDir dir: Path) {
        val java = Files.createDirectories(dir.resolve("app/src/main/java"))
        Files.createDirectories(java.resolve("nested"))
        val file = java.resolve("A.java")
        Files.writeString(file, "class A { int value = 7; }")
        val project = Project(dir)
        assertEquals(listOf(java.resolve("nested")), project.sourceFolders)
        val view = AtomicReference<ProjectView>()
        try {
            SwingUtilities.invokeAndWait {
                view.set(ProjectView(project, Environment(dir.resolve("data"), Settings(null)), Settings(null), StatusBar()))
                assertTrue(all(view.get()).filterIsInstance<JTable>().size == 1, "Unused audit pages were built eagerly")
                view.get().reveal(file)
            }
            awaitFiles(view.get())
            SwingUtilities.invokeAndWait {
                val v = view.get()
                val area = all(v).filterIsInstance<RSyntaxTextArea>().single()
                assertEquals("class A { int value = 7; }", area.text)
                val table = all(v).filterIsInstance<JTable>().single()
                val folder = (0 until table.rowCount).first { table.getValueAt(it, 1) == "File folder" }
                table.setRowSelectionInterval(folder, folder)
                assertEquals("", area.text)
                assertFalse(all(v).filterIsInstance<JButton>().single { it.text == "Open" }.isEnabled)
                v.select(ProjectView.Page.RESOURCES)
                v.select(ProjectView.Page.SUMMARY)
                assertFalse(v.isLoadingFiles)
            }
        } finally { SwingUtilities.invokeAndWait { view.get()?.dispose() } }
    }

    private fun awaitFiles(view: ProjectView) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var busy = true
            SwingUtilities.invokeAndWait { busy = view.isLoadingFiles }
            if (!busy) return
            Thread.sleep(10)
        }
        error("Background file load did not finish")
    }

    private fun CountDownLatch.awaitIgnoringInterrupt(): Boolean =
        try { await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
}
