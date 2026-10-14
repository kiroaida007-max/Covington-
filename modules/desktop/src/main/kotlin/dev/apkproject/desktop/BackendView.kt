package dev.apkproject.desktop

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/** Contract browsing and trusted mock lifecycle, independent of the source navigator. */
internal class BackendView(private val project: Project, private val env: Environment) : JPanel(BorderLayout()) {
    private var server: MockServer? = null
    private var disposed = false
    private val state = JLabel("Stopped").apply { name = "mockState" }
    private val port = JSpinner(SpinnerNumberModel(MockServer.firstFreePort().takeIf { it > 0 } ?: 8080, 1024, 65535, 1)).apply { editor = JSpinner.NumberEditor(this, "#") }
    private val start = Ui.button("Start", KeyEvent.VK_S) { startServer() }.apply { name = "mockStart" }
    private val stop = Ui.button("Stop", KeyEvent.VK_T) { stopServer() }.apply { isEnabled = false }
    private val browse = Ui.button("Open in Browser", KeyEvent.VK_B) { server?.url?.let { Ui.browse(this, it) } }.apply { isEnabled = false }
    private val lifecycle = Timer(500) {
        if (server != null && !server!!.running) {
            stopServer(); state.text = "Mock server exited; start it again to retry."
        }
    }

    init {
        border = Ui.gap(8, 8, 8, 8)
        val c = project.contract
        val endpoints = c?.endpoints.orEmpty()
        val model = object : AbstractTableModel() {
            override fun getRowCount() = endpoints.size
            override fun getColumnCount() = 4
            override fun getColumnName(column: Int) = listOf("Method", "Path", "Operation", "Response type")[column]
            override fun getValueAt(row: Int, column: Int): Any = endpoints[row].let { listOf(it.method, "/" + it.path.removePrefix("/"), it.operation, it.responseType ?: "")[column] }
        }
        val table = ReportTable(model)
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        table.fillsViewportHeight = true
        Ui.columnWidths(table, 70, 380, 220, 260)

        val serverBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        serverBox.add(JLabel("Status:"))
        serverBox.add(state)
        serverBox.add(javax.swing.Box.createHorizontalStrut(12))
        serverBox.add(JLabel("Port:"))
        serverBox.add(port)
        serverBox.add(start)
        serverBox.add(stop)
        serverBox.add(browse)
        val note = JLabel("<html>Runs a freshly generated localhost mock from this contract using checked response examples; unknown, conflicting or invalid examples answer 501. For business code contained in the APK and any checked runnable adapter, open Embedded Logic. The desktop mock uses the built-in template; exported Java is never executed.</html>")
        note.border = Ui.gap(2, 6, 0, 0)
        val serverPanel = JPanel(BorderLayout()).apply { add(serverBox, BorderLayout.NORTH); add(note, BorderLayout.CENTER) }
        if (c == null) { start.isEnabled = false; state.text = "No backend contract in this project" }

        val find = JTextField(30)
        val debounce = Timer(150) {
            sorter.rowFilter = if (find.text.isBlank()) null else RowFilter.regexFilter("(?i)" + Regex.escape(find.text.trim()))
        }.apply { isRepeats = false }
        find.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = debounce.restart()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = debounce.restart()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = debounce.restart()
        })
        val findRow = JPanel(BorderLayout(6, 0)).apply {
            border = Ui.gap(8, 0, 6, 0)
            add(JLabel("Find:").apply { displayedMnemonic = KeyEvent.VK_F; labelFor = find }, BorderLayout.WEST)
            add(find, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(Ui.button("Embedded Logic") { openDocument(project.root.resolve("backend/embedded/README.md")) }.apply { isEnabled = project.embedded != null })
                add(Ui.button("Open Audit") { openDocument(project.root.resolve("backend/contract-audit.json")) }.apply { isEnabled = project.contractAudit != null })
                add(Ui.button("Open OpenAPI File") { openDocument(project.root.resolve("backend/openapi.yaml")) }.apply { isEnabled = Files.isRegularFile(project.root.resolve("backend/openapi.yaml")) })
            }, BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout()).apply {
            add(Ui.group("Mock server", serverPanel), BorderLayout.NORTH)
            add(findRow, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        val assurance = project.contractAudit?.let { "${it.checkedExamples} examples structurally checked, ${it.checkedClientSelections} checked client selections.<br>${it.withheldExamples} withheld, ${it.checkedResponseVariants} selectable checked variants, ${it.issues.size} issues." }
            ?: "No example audit in this project; recover again to check the generated backend."
        val embeddedFacts = project.embedded?.let { "<br>Embedded logic: ${it.handlers.size} handlers; ${it.runnableHandlers.size} runnable through the local adapter." }.orEmpty()
        val facts = if (c == null) "" else "<html>${endpoints.size} endpoints, ${c.models.size} JSON models.<br>$assurance$embeddedFacts</html>"
        add(JLabel(facts).apply { border = Ui.gap(6, 0, 0, 0); toolTipText = c?.baseUrls?.joinToString()?.ifEmpty { "No base URL found" }?.let(Ui::textTooltip) }, BorderLayout.SOUTH)
    }

    private fun openDocument(path: java.nio.file.Path) {
        runCatching { project.files.checked(path) }.fold({ Ui.open(this, it) }, {
            JOptionPane.showMessageDialog(this, it.message, "Project file unavailable", JOptionPane.WARNING_MESSAGE)
        })
    }

    private fun startServer() {
        val jdk = env.jdkHome()
        if (jdk == null) {
            JOptionPane.showMessageDialog(this, "A Java 17 JDK is needed to compile and run the mock server.", "Mock server", JOptionPane.WARNING_MESSAGE)
            return
        }
        start.isEnabled = false
        state.text = "Starting..."
        val s = MockServer(jdk)
        server = s
        stop.isEnabled = true
        port.isEnabled = false
        val chosen = port.value as Int
        Thread({
            val result = runCatching { s.start(requireNotNull(project.contract), chosen) }
            SwingUtilities.invokeLater {
                if (disposed || server !== s) { s.close(); return@invokeLater }
                result.onSuccess {
                    state.text = "Running at ${s.url}"
                    stop.isEnabled = true
                    browse.isEnabled = true
                    port.isEnabled = false
                    lifecycle.start()
                }.onFailure {
                    s.close()
                    server = null
                    state.text = "Stopped"
                    start.isEnabled = true
                    stop.isEnabled = false
                    port.isEnabled = true
                    JOptionPane.showMessageDialog(this, it.message, "Mock server", JOptionPane.ERROR_MESSAGE)
                }
            }
        }, "mock-server-start").start()
    }

    fun stopServer() {
        lifecycle.stop()
        server?.close()
        server = null
        state.text = "Stopped"
        start.isEnabled = project.contract != null
        stop.isEnabled = false
        browse.isEnabled = false
        port.isEnabled = true
    }

    fun dispose() {
        disposed = true
        stopServer()
    }
}
