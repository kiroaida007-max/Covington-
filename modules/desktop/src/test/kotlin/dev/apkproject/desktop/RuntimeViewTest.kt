package dev.apkproject.desktop

import dev.apkproject.app.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.*

class RuntimeViewTest {
    @TempDir lateinit var dir: Path
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()

    @Test fun `native runtime page shows observed evidence capture limits and failed cleanup`() {
        val project = Project(dir)
        val r = RuntimeBehaviorReport(sessionId = "test", startedAt = "now", serial = "emulator-5554", apkSha256 = "a", scenarioSha256 = "b",
            packageName = "dev.test", activity = ".Main", uid = 10012, status = "FAILED", elapsedMs = 2000, actionsCompleted = 1,
            snapshots = listOf(RuntimeSnapshot(0, 1000, listOf(RuntimeUiNode(mapOf("text" to "Observed result"))), processIds = listOf(321), totalPssKb = 1234)),
            filesBefore = emptyList(), filesAfter = emptyList(), fileChanges = emptyList(), appLog = "app log", crashExcerpts = emptyList(),
            permissions = emptyMap(), deniedPackets = emptyMap(), collectors = listOf(RuntimeCollector("API calls and network payloads", "NOT_IMPLEMENTED", "No method hooks")),
            issues = listOf("network denial retained"), cleanupComplete = false, failure = "failed uninstall")
        SwingUtilities.invokeAndWait {
            assertFailsWith<IllegalArgumentException> { RuntimeView.validateDisplayReport(r.copy(snapshots = List(33) { r.snapshots.single() })) }
            assertFailsWith<IllegalArgumentException> { RuntimeView.validateDisplayReport(r.copy(appLog = "x".repeat(65_537))) }
            val oversizedNode = RuntimeUiNode(mapOf("text" to "x".repeat(257)))
            val oversizedSnapshot = r.snapshots.single().copy(ui = listOf(oversizedNode))
            assertFailsWith<IllegalArgumentException> { RuntimeView.validateDisplayReport(r.copy(snapshots = listOf(oversizedSnapshot))) }
            val view = RuntimeView(project, StatusBar())
            view.render(r, dir)
            val text = all(view).mapNotNull { when (it) { is JLabel -> it.text; is JTextArea -> it.text; else -> null } }.joinToString("\n")
            assertContains(text, "Cleanup: FAILED")
            assertContains(text, "NOT_IMPLEMENTED")
            assertContains(text, "No method hooks")
            assertContains(text, "Observed result")
            assertContains(text, "network denial retained")
            val table = all(view).filterIsInstance<JTable>().single { it.name == "runtimeObservations" }
            assertEquals(1, table.rowCount)
            assertEquals(1234L, table.getValueAt(0, 3))
            val cancel = all(view).filterIsInstance<JButton>().single { it.name == "runtimeCancel" }
            assertFalse(cancel.isEnabled)
            view.dispose()
        }
    }
}
