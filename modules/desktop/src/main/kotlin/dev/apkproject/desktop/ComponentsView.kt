package dev.apkproject.desktop

import dev.apkproject.app.Advisory
import dev.apkproject.app.SupplyChainReport
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Components: the third-party libraries and native files the app carries (a software bill of
 * materials, also saved as CycloneDX), and their known vulnerabilities (libraries from OSV.dev,
 * native libraries from the NVD) with the version that fixes each for the installed line.
 */
class ComponentsView(private val report: SupplyChainReport?, private val sbomFile: Path, private val status: StatusBar) : JPanel(BorderLayout()) {
    init {
        border = Ui.gap(8, 8, 8, 8)
        if (report == null) {
            add(JLabel("This project has no software bill of materials (audit/supply-chain.json). Re-run the recovery with a current version of apkproject."), BorderLayout.NORTH)
        } else {
            val libs = report.components.count { it.type == "library" }
            val files = report.components.count { it.type == "file" }
            val vulnerabilities = when {
                !report.lookup.startsWith("OSV") -> "Known vulnerabilities were not looked up."
                !report.lookupComplete -> "The vulnerability lookup did not complete: ${report.lookup}."
                report.advisories.isEmpty() -> "No known vulnerabilities in these versions (${sources(report)})."
                else -> "${report.advisories.size} known vulnerabilit${if (report.advisories.size == 1) "y" else "ies"} in ${report.risks.size} component${if (report.risks.size == 1) "" else "s"} (${sources(report)})."
            }
            val top = JPanel(BorderLayout(0, 4))
            top.add(JLabel("<html>$libs libraries with a version and $files native files, each read from the package itself (OBSERVED: version files, version strings in code, file hashes). ${Ui.escape(vulnerabilities)}</html>"), BorderLayout.NORTH)
            top.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JLabel("CycloneDX 1.5 SBOM: ${sbomFile.fileName}  "))
                add(Ui.button("Show in Explorer", KeyEvent.VK_X) { Ui.open(this@ComponentsView, sbomFile.parent) })
            }, BorderLayout.CENTER)
            top.border = Ui.gap(0, 0, 6, 0)
            add(top, BorderLayout.NORTH)
            val tabs = JTabbedPane()
            tabs.addTab("Vulnerable Components", vulnerable(report))
            tabs.addTab("Advisories", advisories(report.advisories))
            tabs.addTab("Libraries", libraries(report))
            tabs.addTab("Native Files", natives(report))
            tabs.setMnemonicAt(0, KeyEvent.VK_V)
            tabs.setMnemonicAt(1, KeyEvent.VK_A)
            tabs.setMnemonicAt(2, KeyEvent.VK_L)
            tabs.setMnemonicAt(3, KeyEvent.VK_N)
            // Nothing vulnerable: the libraries are what there is to see.
            if (report.risks.isEmpty()) tabs.selectedIndex = 2
            add(tabs, BorderLayout.CENTER)
        }
    }

    /** The table of components that have a known vulnerability, with the version that fixes them. */
    private fun vulnerable(r: SupplyChainReport): JComponent {
        val rows = r.risks
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 5
            override fun getColumnName(column: Int) = listOf("Component", "Installed", "Advisories", "Worst", "Upgrade to")[column]
            override fun getColumnClass(column: Int): Class<*> = if (column == 2) Integer::class.java else String::class.java
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let { k ->
                listOf(k.coordinate, k.installed, k.advisories, k.worst, (k.upgradeTo ?: "no fix published") + if (k.unfixed > 0 && k.upgradeTo != null) " (${k.unfixed} without a fix)" else "")[column]
            }
        }
        val table = table(model, 360, 110, 90, 100, 260)
        table.columnModel.getColumn(3).cellRenderer = severityRenderer()
        return panel(table, if (rows.isEmpty()) "No component has a known vulnerability." else "The lowest version that fixes every advisory with a published fix, on the installed version's line where one exists.")
    }

    /** The table of individual advisories, with a button to open the advisory in a browser. */
    private fun advisories(rows: List<Advisory>): JComponent {
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 9
            override fun getColumnName(column: Int) = listOf("Severity", "ID", "CVE", "Source", "CVSS", "Component", "Installed", "Fixed in", "Summary")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let { a ->
                listOf(a.severity, a.id, dev.apkproject.app.SupplyChainMarkdown.cves(a).joinToString(", "), a.source, a.score?.toString() ?: "", a.coordinate, a.installed, a.fixedIn.joinToString(", ").ifEmpty { "no fix" }, a.summary)[column]
            }
        }
        val table = table(model, 90, 170, 140, 60, 60, 280, 90, 110, 420)
        table.columnModel.getColumn(0).cellRenderer = severityRenderer()
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) table.selectedRow.takeIf { it >= 0 }?.let { rows[table.convertRowIndexToModel(it)] }?.let { a ->
                status.message("${a.id}: ${a.summary}  ${a.vector ?: ""}  ${a.url}")
            }
        }
        val open = Ui.button("Open Advisory", KeyEvent.VK_O) {
            table.selectedRow.takeIf { it >= 0 }?.let { rows[table.convertRowIndexToModel(it)] }?.let { a -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(a.url)) } }
        }
        val panel = panel(table, "Fixed in: the release that fixes it for the installed version's line, not fixes of older lines.")
        panel.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(open) }, BorderLayout.SOUTH)
        return panel
    }

    /** Where the vulnerabilities were looked up: OSV.dev for libraries, the NVD for native libraries. */
    private fun sources(r: SupplyChainReport) = if ("NVD" in r.lookup) "OSV.dev and the NVD" else "OSV.dev"

    /** The table of third-party libraries with a known version. */
    private fun libraries(r: SupplyChainReport): JComponent {
        val rows = r.components.filter { it.type == "library" }
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = listOf("Component", "Version", "Evidence")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let { listOf(it.coordinate, it.version ?: "", it.evidence)[column] }
        }
        return panel(table(model, 380, 150, 520), "Libraries R8 renamed that ship no version file are not listed: naming them would be a guess.")
    }

    /** The table of native library files with their hash. */
    private fun natives(r: SupplyChainReport): JComponent {
        val rows = r.components.filter { it.type == "file" }
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = listOf("File", "Size", "SHA-256")[column]
            override fun getColumnClass(column: Int): Class<*> = if (column == 1) java.lang.Long::class.java else String::class.java
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let { listOf(it.path ?: it.name, it.size ?: 0L, it.sha256 ?: "")[column] }
        }
        val table = table(model, 360, 90, 520)
        table.columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer().apply { font = Ui.monospace }
        return panel(table, "Every native library file with its hash. Native projects that stamp their version (OpenSSL, zlib, libpng, ...) are listed under Libraries with a CPE and looked up in the NVD.")
    }

    /** A sortable table with the given column widths. */
    private fun table(model: AbstractTableModel, vararg widths: Int): JTable {
        val table = JTable(model)
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        Ui.columnWidths(table, *widths)
        return table
    }

    /** A table wrapped in a scroll pane with an explanatory note above it. */
    private fun panel(table: JTable, note: String): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = Ui.gap(6, 6, 6, 6)
        panel.add(JLabel(note), BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    /** Colors a severity cell red for CRITICAL/HIGH and amber for MEDIUM. */
    private fun severityRenderer() = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(t, value, sel, foc, row, column)
            if (!sel) foreground = when (value) { "CRITICAL", "HIGH" -> Ui.bad; "MEDIUM" -> Ui.warn; else -> javax.swing.UIManager.getColor("Label.foreground") }
            return this
        }
    }
}
