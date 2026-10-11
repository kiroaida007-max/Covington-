package dev.apkproject.desktop

import dev.apkproject.app.PrivacyFlow
import dev.apkproject.app.PrivacyMap
import dev.apkproject.app.PrivacyReport
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Privacy: the personal data the code reads (location, device identifiers, contacts, ...) and the
 * flows that take it to the network, an SDK, logs, storage or other apps, with the path of each.
 * Every flow is INFERRED from the bytecode.
 */
class PrivacyView(private val report: PrivacyReport?, private val status: StatusBar) : JPanel(BorderLayout()) {
    private val path = DefaultListModel<String>()

    init {
        border = Ui.gap(8, 8, 8, 8)
        if (report == null) {
            add(JLabel("This project has no privacy map (audit/privacy.json). Re-run the recovery with a current version of apkproject."), BorderLayout.NORTH)
        } else {
            val kinds = report.sources.map { it.title }.distinct()
            val intro = JLabel(
                "<html>The code reads ${PrivacyMap.kinds(kinds.size)}" +
                    (if (kinds.isEmpty()) "" else " (${Ui.escape(kinds.joinToString(", "))})") + "; ${PrivacyMap.flowsReach(report.flows.size)}. " +
                    "A flow is a path the bytecode allows, so every row is INFERRED: code on it can still be guarded at run time. " +
                    "${Ui.count(report.stats.methods)} methods analysed in ${if (report.stats.elapsedMs < 1000) "under a second" else Ui.duration(report.stats.elapsedMs)}" +
                    (if (report.stats.truncated) "; the analysis stopped before reaching a fixed point." else ".") + "</html>",
            )
            intro.border = Ui.gap(0, 0, 6, 0)
            val heading = JPanel(BorderLayout(0, 4))
            heading.add(intro, BorderLayout.NORTH)
            val limits = buildList {
                if (report.stats.truncated) add("The analysis stopped before reaching a fixed point; flows may be missing.")
                if (report.stats.boundedSummaries > 0) add("${report.stats.boundedSummaries} method summaries reached a size bound; flows may be missing.")
                add("A missing flow is not proof that data stays on the device. See Analysis Notes for coverage limits.")
            }.joinToString(" ")
            heading.add(Ui.readOnlyText(limits).apply {
                if (report.stats.truncated || report.stats.boundedSummaries > 0) foreground = Ui.warn
            }, BorderLayout.CENTER)
            add(heading, BorderLayout.NORTH)
            val tabs = JTabbedPane()
            tabs.addTab("Flows", flows(report))
            tabs.addTab("Data Safety", dataSafety(report))
            tabs.addTab("Data Read", dataRead(report))
            tabs.addTab("Analysis Notes", JScrollPane(Ui.readOnlyText(report.notes.joinToString("\n\n"))).apply { border = Ui.gap(8, 8, 8, 8) })
            tabs.setMnemonicAt(0, KeyEvent.VK_F)
            tabs.setMnemonicAt(1, KeyEvent.VK_D)
            tabs.setMnemonicAt(2, KeyEvent.VK_R)
            tabs.setMnemonicAt(3, KeyEvent.VK_N)
            // No flow: the personal data the code reads, and that it reaches no sink, is what there is to see.
            if (report.flows.isEmpty()) tabs.selectedIndex = 2
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun flows(r: PrivacyReport): JComponent {
        val flows = r.flows
        val model = object : AbstractTableModel() {
            override fun getRowCount() = flows.size
            override fun getColumnCount() = 6
            override fun getColumnName(column: Int) = listOf("Attention", "Data", "Goes to", "Where", "Read by", "Code")[column]
            override fun getValueAt(row: Int, column: Int): Any = flows[row].let { f ->
                when (column) {
                    0 -> f.attention + if (f.permissionDeclared == false) " (cannot run)" else ""
                    1 -> f.dataTitle
                    2 -> sinkName(f.sink)
                    3 -> f.sinkDetail
                    4 -> f.sourceApi.substringAfterLast('.').let { api -> if (api.startsWith("callback ") || '(' in api) api else "$api()" } + " in " + short(f.sourceIn)
                    else -> f.code
                }
            }
        }
        val table = JTable(model)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        Ui.columnWidths(table, 130, 180, 130, 260, 260, 110)
        table.columnModel.getColumn(0).cellRenderer = attentionRenderer()
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) table.selectedRow.takeIf { it >= 0 }?.let { show(flows[table.convertRowIndexToModel(it)]) }
        }
        val top = JPanel(BorderLayout(0, 6))
        if (flows.isEmpty()) {
            top.add(JLabel("No flow from personal data to a sink was found; Data Read lists the APIs the code calls."), BorderLayout.NORTH)
        }
        top.add(JScrollPane(table), BorderLayout.CENTER)
        val pathList = JList(path)
        pathList.font = Ui.monospace
        val pathBox = JPanel(BorderLayout()).apply { add(JScrollPane(pathList), BorderLayout.CENTER) }
        pathBox.preferredSize = java.awt.Dimension(400, 150)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, Ui.group("Path (select a flow above)", pathBox))
        split.resizeWeight = 0.6
        split.dividerSize = 5
        split.border = null
        val panel = JPanel(BorderLayout())
        panel.border = Ui.gap(6, 6, 6, 6)
        panel.add(split, BorderLayout.CENTER)
        if (flows.isNotEmpty()) table.setRowSelectionInterval(0, 0)
        return panel
    }

    private fun show(f: PrivacyFlow) {
        path.clear()
        // Read, carried back up (or through a field) to where it meets the sink's call chain, then down to the sink.
        path.addElement("${f.dataTitle}: ${f.sourceApi}")
        path.addElement("  read in ${f.sourceIn}")
        val meet = f.path.firstOrNull() ?: f.sinkIn
        when {
            f.throughField != null -> path.addElement("  stored in field ${f.throughField}, read back in $meet")
            f.sourcePath.isNotEmpty() -> f.sourcePath.reversed().drop(1).forEach { path.addElement("  returned to $it") }
            meet != f.sourceIn -> path.addElement("  reaches $meet")
        }
        f.path.drop(1).forEach { path.addElement("  calls $it") }
        path.addElement("${sinkName(f.sink)}: ${f.sinkApi} (${f.sinkDetail})")
        val permission = when (f.permissionDeclared) { false -> " The permission it needs is not requested: it cannot run as built."; else -> "" }
        status.message("${f.attention}: ${f.dataTitle} can reach ${sinkName(f.sink).lowercase()} (${f.sinkDetail}); evidence ${f.evidence}.$permission")
    }

    private fun dataSafety(r: PrivacyReport): JComponent {
        val rows = r.dataSafety
        fun yes(b: Boolean) = if (b) "Inferred" else "Not observed"
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 7
            override fun getColumnName(column: Int) = listOf("Data", "Play data type", "Potential collection", "SDK / third-party path", "Stored", "Logged", "Where")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let { d ->
                listOf(d.title, d.playDataType, yes(d.collected), yes(d.shared), yes(d.stored), yes(d.logged), d.sinks.joinToString("; ").ifEmpty { "No sink found" })[column]
            }
        }
        val table = JTable(model)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        Ui.columnWidths(table, 200, 230, 110, 120, 70, 70, 360)
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = Ui.gap(6, 6, 6, 6)
        panel.add(Ui.readOnlyText("Static clues for a Google Play Data safety review, not a completed declaration. Collection and sharing need runtime and policy review, including SDK behavior and sharing exceptions. Flows whose required permission is not requested are left out; absence of a detected flow does not establish absence of collection."), BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun dataRead(r: PrivacyReport): JComponent {
        val rows = r.sources
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 5
            override fun getColumnName(column: Int) = listOf("Data", "API", "Permission", "Reaches a sink", "Called in")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let { s ->
                val permission = when (s.permissionDeclared) { true -> "Requested"; false -> "Not requested"; null -> if (s.permissions.isEmpty()) "None needed" else "Unknown (manifest unavailable)" }
                listOf(s.title, s.api, permission, if (s.reachesSink) "Inferred" else "Not observed", s.usedIn.joinToString(", "))[column]
            }
        }
        val table = JTable(model)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        Ui.columnWidths(table, 200, 340, 170, 100, 360)
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = Ui.gap(6, 6, 6, 6)
        val capabilities = r.capabilities.filter { it.category == "PRIVACY" }
        panel.add(JLabel("<html>Platform APIs that return personal data, where the code calls them. ${capabilities.size} privacy capabilit${if (capabilities.size == 1) "y is" else "ies are"} referenced in all" +
            (if (capabilities.isEmpty()) "" else ": " + Ui.escape(capabilities.joinToString("; ") { it.title + if (it.permissionDeclared == false) " (permission not requested)" else "" })) + ".</html>"), BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun attentionRenderer() = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(t, value, sel, foc, row, column)
            if (!sel) foreground = when ((value as String).substringBefore(' ')) { "HIGH" -> Ui.bad; "MEDIUM" -> Ui.warn; else -> javax.swing.UIManager.getColor("Label.foreground") }
            return this
        }
    }

    private fun sinkName(kind: String) = when (kind) {
        "NETWORK" -> "Network"
        "ANALYTICS" -> "Third-party SDK"
        "SMS" -> "SMS"
        "LOG" -> "Log"
        "STORAGE" -> "Storage"
        "IPC" -> "Other apps"
        "CLIPBOARD" -> "Clipboard"
        else -> kind
    }

    private fun short(method: String): String {
        val cls = method.substringBeforeLast('.')
        return cls.substringAfterLast('.') + "." + method.substringAfterLast('.')
    }
}
