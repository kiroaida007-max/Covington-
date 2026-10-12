package dev.apkproject.desktop

import dev.apkproject.app.*
import java.awt.Component
import java.awt.Container
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.JScrollPane
import javax.swing.Scrollable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditViewsTest {
    private fun all(c: Component): List<Component> = listOf(c) +
        if (c is Container) c.components.flatMap(::all) else emptyList()

    private fun text(c: Component) = all(c).mapNotNull {
        when (it) { is JLabel -> it.text; is JTextArea -> it.text; else -> null }
    }.joinToString("\n")

    @Test
    fun `privacy limits and unknown manifest remain visible when no flow is found`() {
        SwingUtilities.invokeAndWait {
            val source = PrivacySource("location", "Location", "getLastKnownLocation", listOf("App.read"), false, listOf("ACCESS_FINE_LOCATION"), null)
            val report = PrivacyReport(listOf(source), emptyList(), emptyList(), emptyList(), emptyList(),
                PrivacyStats(100, 10, 110, 100, false, 17), listOf("Not followed: callbacks and native code."))
            val view = PrivacyView(report, StatusBar())
            val visible = text(view)
            assertTrue("17" in visible && "bound" in visible.lowercase(), visible)
            assertTrue("missing flow is not proof" in visible.lowercase(), visible)
            assertTrue("callbacks and native code" in visible, visible)
            val sourceTable = all(view).filterIsInstance<JTable>().single { it.columnCount == 5 }
            assertTrue("unknown" in sourceTable.getValueAt(0, 2).toString().lowercase())
            assertTrue("not observed" in sourceTable.getValueAt(0, 3).toString().lowercase())
        }
    }

    @Test
    fun `empty vulnerability tables do not claim a clean result for skipped or failed lookup`() {
        SwingUtilities.invokeAndWait {
            for (lookup in listOf("not looked up (offline)", "OSV.dev; FAILED for 1")) {
                val r = SupplyChainReport(SbomApp(null, null, null, "a"), emptyList(), emptyList(), emptyList(), lookup, false, emptyList())
                val view = ComponentsView(r, Paths.get("sbom.cdx.json"), StatusBar())
                val visible = text(view)
                assertFalse("No component has a known vulnerability" in visible, visible)
                assertTrue("not looked up" in visible || "did not complete" in visible, visible)
            }
        }
    }

    @Test
    fun `desktop recovery only queries OSV when the user selects it`() {
        val request = RecoveryRequest(Paths.get("app.apk"), Paths.get("out"), true, true, true, true, false)
        assertFalse(request.options(z3 = true, rea = false).vulnerabilities)
        assertTrue(request.copy(vulnerabilities = true).options(z3 = true, rea = false).vulnerabilities)
        assertFalse(request.copy(vulnerabilities = false).options(z3 = true, rea = false).vulnerabilities)
        assertFalse(request.options(z3 = true, rea = false).privacyApiModel)
        assertTrue(request.copy(privacyModel = true).options(z3 = true, rea = false).privacyApiModel)
        assertEquals(Paths.get("model.json").toAbsolutePath().normalize().toString(), request.copy(repairModel = Paths.get("model.json")).options(z3 = true, rea = false).repairModelPath)
    }

    @Test
    fun `risk page fits the viewport and sorted selection exposes the full suggested fix`(@TempDir dir: Path) {
        val audit = Files.createDirectories(dir.resolve("audit"))
        val main = Files.createDirectories(dir.resolve("app/src/main"))
        val anatomy = AppAnatomy("APK", emptyList(), emptyList(), 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val report = FrontendAudit.analyze(main, """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example"><application android:debuggable="true" android:usesCleartextTraffic="true" android:allowBackup="true"><activity android:name=".Main"/><activity android:name=".Settings"/></application></manifest>""", emptyList(), anatomy)
        Files.writeString(audit.resolve("ui-audit.json"), Json.encodeToString(FrontendReport.serializer(), report))
        val formRef = AtomicReference<JScrollPane>()
        val viewRef = AtomicReference<ProjectView>()
        SwingUtilities.invokeAndWait {
            val view = ProjectView(Project(dir), Environment(dir.resolve("app-data"), Settings(null)), Settings(null), StatusBar())
            viewRef.set(view)
            view.select(ProjectView.Page.UI_RISK)
            view.setSize(800, 700)
            fun layout(c: Component) {
                if (c is Container) { c.doLayout(); c.components.forEach(::layout) }
            }
            repeat(3) { layout(view) }
            val form = all(view).filterIsInstance<JScrollPane>().single { it.isVisible && it.viewport.view is javax.swing.JPanel && it.viewport.view is Scrollable }
            formRef.set(form)
            assertEquals(form.viewport.width, form.viewport.view.width)
            val table = all(view).filterIsInstance<JTable>().single { it.columnCount == 6 && it.getColumnName(2) == "Finding" }
            table.rowSorter.toggleSortOrder(2)
            table.setRowSelectionInterval(table.rowCount - 1, table.rowCount - 1)
            val finding = report.findings[table.convertRowIndexToModel(table.selectedRow)]
            val detail = all(view).filterIsInstance<JTextArea>().single { it.accessibleContext.accessibleName == "Finding detail and suggested fix" }
            assertTrue(finding.detail in detail.text && finding.fix in detail.text && finding.reference in detail.text, detail.text)
            assertFalse(detail.isEditable)
            assertTrue(detail.lineWrap)
        }
        // Caret visibility requests are queued; inspect after they have had a chance to run.
        SwingUtilities.invokeAndWait {
            assertEquals(0, formRef.get().viewport.viewPosition.y, "A read-only update scrolled the whole risk form")
            val screens = all(viewRef.get()).filterIsInstance<JTable>().single { it.columnCount == 4 && it.getColumnName(0) == "Screen" }
            assertEquals(2, screens.rowCount)
            assertTrue(screens.parent.height >= screens.rowHeight * screens.rowCount, "Screens list collapsed to its header")
            ScreenshotTour.showRiskDetails(viewRef.get(), showingOnly = false)
            assertTrue(formRef.get().viewport.viewPosition.y > 0, "Detail capture did not move the enclosing form")
        }
    }

    @Test
    fun `privacy negative cells mean not observed and a missing path does not crash the page`() {
        SwingUtilities.invokeAndWait {
            val flow = PrivacyFlow("location", "Location", "Api.location", "App.read", "NETWORK", "URL.connect", "Network", "App.send", emptyList(), emptyList(), "HIGH", "app")
            val row = DataSafetyRow("location", "Location", "Location", false, false, false, false, emptyList())
            val report = PrivacyReport(emptyList(), listOf(flow), listOf(row), emptyList(), emptyList(), PrivacyStats(1, 0, 1, 1, true), emptyList())
            val view = PrivacyView(report, StatusBar())
            val table = all(view).filterIsInstance<JTable>().single { it.columnCount == 7 }
            for (col in 2..5) assertEquals("Not observed", table.getValueAt(0, col))
            assertTrue("flows may be missing" in text(view))
        }
    }
}
