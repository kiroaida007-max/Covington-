package dev.apkproject.desktop

import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AnalysisStatusTest {
    @TempDir lateinit var dir: Path
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()

    @Test fun `unavailable privacy page shows failure and links to analysis status`() {
        Files.createDirectory(dir.resolve("audit"))
        Files.writeString(dir.resolve("audit/privacy.json"), "{malformed")
        val project = Project(dir)
        SwingUtilities.invokeAndWait {
            val view = ProjectView(project, Environment(dir.resolve("data"), Settings(null)), Settings(null), StatusBar())
            try {
                view.select(ProjectView.Page.PRIVACY)
                assertTrue(all(view).filterIsInstance<JTextArea>().any { "Invalid" in it.text && "no result about app safety" in it.text })
                all(view).filterIsInstance<JButton>().last { it.text == "Review Analysis Status" }.doClick()
                assertTrue(all(view).filterIsInstance<JTable>().any { it.name == "analysisStatusTable" })
            } finally { view.dispose() }
        }
    }

    @Test fun `sorting filtering navigation refresh and bounded raw preview share the selected report`() {
        Files.createDirectory(dir.resolve("audit"))
        val raw = "{malformed privacy report"
        Files.writeString(dir.resolve("audit/privacy.json"), raw)
        val project = Project(dir)
        var page: ProjectView.Page? = null
        var refreshed = false
        lateinit var view: AnalysisStatusView
        lateinit var preview: JTextArea
        SwingUtilities.invokeAndWait {
            view = AnalysisStatusView(project, { page = it }, { refreshed = true })
            val table = all(view).filterIsInstance<JTable>().single()
            table.rowSorter.toggleSortOrder(1)
            table.rowSorter.toggleSortOrder(1)
            all(view).filterIsInstance<JCheckBox>().single().doClick()
            val index = (0 until table.rowCount).single { table.getValueAt(it, 1) == "audit/privacy.json" }
            table.setRowSelectionInterval(index, index)
            assertTrue(all(view).filterIsInstance<JTextArea>().first { it.name == "analysisStatusDetail" }.text.contains("Invalid"))
            all(view).filterIsInstance<JButton>().single { it.text == "Show Analysis" }.doClick()
            assertEquals(ProjectView.Page.PRIVACY, page)
            all(view).filterIsInstance<JButton>().single { it.text == "Refresh Reports" }.doClick()
            assertTrue(refreshed)
            preview = all(view).filterIsInstance<JTextArea>().single { it.name == "analysisReportPreview" }
            all(view).filterIsInstance<JButton>().single { it.text == "Preview Report" }.doClick()
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait { ready = preview.text == raw }
                if (!ready) Thread.sleep(10)
            }
            assertTrue(ready, "The selected report's literal text was not shown")
        } finally { SwingUtilities.invokeAndWait { view.dispose() } }
        Files.writeString(dir.resolve("large.json"), "x".repeat(600 * 1024))
        assertTrue(FilePreview.content(dir.resolve("large.json"), project.files, 512 * 1024).text.contains("too large"))
    }

    @Test fun `table strings and markdown cannot add images or launch nonweb URI schemes`() {
        SwingUtilities.invokeAndWait {
            val table = ReportTable(javax.swing.table.DefaultTableModel(arrayOf(arrayOf<Any>("<html><img src='file:///private-file'>")), arrayOf("Evidence")))
            val cell = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0) as JComponent
            assertNull(cell.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
            val status = StatusBar()
            status.message("<html><img src='file:///private-file'>")
            assertTrue(all(status).filterIsInstance<JLabel>().all { it.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null })
        }
        val html = Markdown.inline("[file](file:///etc/passwd) [script](javascript:alert) [web](https://example.com/a?q=1)")
        assertFalse("file:" in html || "javascript:" in html)
        assertTrue("href=\"https://example.com/a?q=1\"" in html)
        assertNull(Ui.webAddress("https://user:password@example.com"))
        assertNull(Ui.webAddress("file:///tmp/app.exe"))
        assertFalse(Ui.passiveDocument(Path.of("payload.cmd")))
        assertFalse(Ui.passiveDocument(Path.of("payload.lnk")))
        assertTrue(Ui.passiveDocument(Path.of("Source.java")))
    }
}
