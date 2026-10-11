package dev.apkproject.desktop

import dev.apkproject.app.DataReach
import dev.apkproject.app.ScreenFlow
import dev.apkproject.app.ScreenMapReport
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Screens & Data: for each screen (and service, receiver, worker), the endpoints, servers, SQL
 * tables and stored settings its code can reach, with the call path that leads there. Also the
 * same facts by endpoint and by table. Everything here is INFERRED from a static call graph.
 */
class ScreensView(private val map: ScreenMapReport?, private val status: StatusBar) : JPanel(BorderLayout()) {
    private var screen: ScreenFlow? = null
    private val hideCommon = JCheckBox().apply { mnemonic = KeyEvent.VK_H }
    private val detailTabs = JTabbedPane()
    private val detailModels = listOf(ReachModel("Network"), ReachModel("Database"), ReachModel("Stored Settings"))
    private val detailTables = detailModels.map { JTable(it) }
    private val links = DefaultListModel<String>()
    private val path = DefaultListModel<String>()

    init {
        border = Ui.gap(8, 8, 8, 8)
        if (map == null) {
            add(JLabel("This project has no screen map (audit/screen-map.json). Re-run the recovery with a current version of apkproject."), BorderLayout.NORTH)
        } else {
            val intro = JLabel(
                "<html>What each screen's code can reach: endpoints, servers, database tables and stored settings, with the call path to each. " +
                    "It is a static call graph, so every row is INFERRED: code can still be guarded at run time.</html>",
            )
            intro.border = Ui.gap(0, 0, 6, 0)
            add(intro, BorderLayout.NORTH)
            val tabs = JTabbedPane()
            tabs.addTab("By Screen", byScreen(map))
            tabs.addTab("By Endpoint", byEndpoint(map))
            tabs.addTab("By Table", byTable(map))
            tabs.setMnemonicAt(0, KeyEvent.VK_S)
            tabs.setMnemonicAt(1, KeyEvent.VK_E)
            tabs.setMnemonicAt(2, KeyEvent.VK_T)
            add(tabs, BorderLayout.CENTER)
        }
    }

    /** The screen's kind, capitalized, with "(launcher)" appended if it is the launcher activity. */
    private fun kind(s: ScreenFlow) = s.kind.lowercase().replaceFirstChar { it.uppercase() } + if (s.launcher) " (launcher)" else ""

    /** A component name shortened to its simple class name. */
    private fun short(component: String) = component.substringAfterLast('.')

    /** The "By Screen" tab: the screen list, find box and "hide common" filter, with details below. */
    private fun byScreen(map: ScreenMapReport): JComponent {
        val screens = map.screens
        val common = map.commonThreshold
        /** `xs` filtered to drop items shared by `common` or more components, when that filter is on. */
        fun own(xs: List<DataReach>) = if (hideCommon.isSelected && common > 0) xs.filter { it.sharedBy < common } else xs
        val model = object : AbstractTableModel() {
            override fun getRowCount() = screens.size
            override fun getColumnCount() = 6
            override fun getColumnName(column: Int) = listOf("Screen", "Kind", "Endpoints", "Servers", "SQL", "Settings")[column]
            override fun getColumnClass(column: Int): Class<*> = if (column >= 2) Integer::class.java else String::class.java
            override fun getValueAt(row: Int, column: Int): Any = screens[row].let { s ->
                when (column) {
                    0 -> short(s.component)
                    1 -> kind(s)
                    2 -> own(s.network).count { it.kind == "ENDPOINT" }
                    3 -> own(s.network).count { it.kind == "HOST" }
                    4 -> own(s.database).count { it.kind == "SQL" }
                    else -> own(s.preferences).size
                }
            }
        }
        val list = object : JTable(model) {
            override fun getToolTipText(e: java.awt.event.MouseEvent): String? = rowAtPoint(e.point).takeIf { it >= 0 }?.let { screens[convertRowIndexToModel(it)].component }
        }
        val sorter = TableRowSorter(model)
        list.rowSorter = sorter
        list.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fillsViewportHeight = true
        Ui.columnWidths(list, 300, 140, 80, 70, 60, 70)
        list.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) list.selectedRow.takeIf { it >= 0 }?.let { show(screens[list.convertRowIndexToModel(it)]) }
        }

        hideCommon.text = if (common > 0) "Hide what most components share (reached by $common or more of ${screens.size})" else "Hide what most components share"
        hideCommon.isSelected = common > 0
        hideCommon.isEnabled = common > 0
        hideCommon.addActionListener { model.fireTableDataChanged(); screen?.let { show(it) } }
        val find = JTextField(24)
        find.document.addDocumentListener(object : DocumentListener {
            /** Applies the find text as a case-insensitive filter on the screen column. */
            fun update() { sorter.rowFilter = if (find.text.isBlank()) null else RowFilter.regexFilter("(?i)" + Regex.escape(find.text.trim()), 0) }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        val top = JPanel(BorderLayout(0, 6))
        top.add(JPanel(BorderLayout(12, 0)).apply {
            add(JPanel(BorderLayout(6, 0)).apply { add(JLabel("Find:").apply { labelFor = find; displayedMnemonic = KeyEvent.VK_F }, BorderLayout.WEST); add(find, BorderLayout.CENTER) }, BorderLayout.WEST)
            add(hideCommon, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        top.add(JScrollPane(list), BorderLayout.CENTER)

        detailTables.forEachIndexed { k, t ->
            t.autoCreateRowSorter = true
            t.fillsViewportHeight = true
            t.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            Ui.columnWidths(t, 110, 330, 420, 70)
            t.selectionModel.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) t.selectedRow.takeIf { it >= 0 }?.let { showPath(detailModels[k].rows[t.convertRowIndexToModel(it)]) }
            }
            detailTabs.addTab(detailModels[k].title, JScrollPane(t))
        }
        detailTabs.addTab("Opens and Starts", JScrollPane(JList(links)))
        detailTabs.addChangeListener { path.clear() }
        val pathList = JList(path)
        pathList.font = Ui.monospace
        val pathBox = JPanel(BorderLayout()).apply { add(JScrollPane(pathList), BorderLayout.CENTER) }
        pathBox.preferredSize = java.awt.Dimension(400, 130)
        val bottom = JSplitPane(JSplitPane.VERTICAL_SPLIT, detailTabs, Ui.group("Call path (select a row above)", pathBox))
        bottom.resizeWeight = 0.6
        bottom.dividerSize = 5
        bottom.border = null

        // As Event Viewer: the list on top, the selected screen's details below.
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom)
        split.resizeWeight = 0.35
        split.dividerSize = 5
        split.border = null
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = Ui.gap(6, 6, 6, 6)
        panel.add(split, BorderLayout.CENTER)
        if (screens.isNotEmpty()) list.setRowSelectionInterval(0, 0)
        return panel
    }

    /** Fills the detail tabs and status bar with what the selected screen's code can reach. */
    private fun show(s: ScreenFlow) {
        screen = s
        val common = map?.commonThreshold ?: 0
        /** `xs` filtered to drop items shared by `common` or more components, when that filter is on. */
        fun own(xs: List<DataReach>) = if (hideCommon.isSelected && common > 0) xs.filter { it.sharedBy < common } else xs
        val parts = listOf(own(s.network), own(s.database), own(s.preferences))
        parts.forEachIndexed { k, xs ->
            detailModels[k].rows = xs
            detailModels[k].fireTableDataChanged()
            detailTabs.setTitleAt(k, "${detailModels[k].title} (${xs.size})")
        }
        links.clear()
        s.opens.forEach { links.addElement("Opens  $it") }
        s.starts.forEach { links.addElement("Starts  $it") }
        detailTabs.setTitleAt(3, "Opens and Starts (${s.opens.size + s.starts.size})")
        path.clear()
        val hidden = (s.network.size + s.database.size + s.preferences.size) - parts.sumOf { it.size }
        status.message(
            "${s.component}: ${Ui.count(s.methodsReached)} methods reached" + (if (s.truncated) " (stopped at the walk's limit)" else "") +
                (if (hidden > 0) ";  $hidden shared items hidden" else ""),
        )
    }

    /** Fills the call-path list and status bar for a selected reach row. */
    private fun showPath(r: DataReach) {
        path.clear()
        r.path.forEachIndexed { k, step -> path.addElement("%2d. %s".format(k + 1, step)) }
        path.addElement("    → ${r.target}")
        status.message("${r.target}  —  ${r.detail}  (shared by ${r.sharedBy} ${if (r.sharedBy == 1) "component" else "components"}; INFERRED)")
    }

    private class ReachModel(val title: String) : AbstractTableModel() {
        var rows: List<DataReach> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Kind", "Target", "Detail", "Shared by")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 3) Integer::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) {
                0 -> KINDS[it.kind] ?: it.kind
                1 -> it.target
                2 -> it.detail
                else -> it.sharedBy
            }
        }
    }

    /** The "By Endpoint" tab: each endpoint with the screens that can reach it. */
    private fun byEndpoint(map: ScreenMapReport): JComponent {
        val rows = map.endpoints
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = listOf("Endpoint", "Screens", "Reached from")[column]
            override fun getColumnClass(column: Int): Class<*> = if (column == 1) Integer::class.java else String::class.java
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
                when (column) {
                    0 -> it.endpoint
                    1 -> it.screens.size
                    else -> it.screens.joinToString(", ") { c -> short(c) }.ifEmpty { "(no screen reaches it statically)" }
                }
            }
        }
        return tablePage(model, "${rows.count { it.screens.isNotEmpty() }} of ${rows.size} endpoints are reached from at least one screen or component.", 300, 70, 600)
    }

    /** The "By Table" tab: each SQL table with the screens that can reach it. */
    private fun byTable(map: ScreenMapReport): JComponent {
        val rows = map.tables
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 4
            override fun getColumnName(column: Int) = listOf("Table", "Operations in the code", "Screens", "Reached from")[column]
            override fun getColumnClass(column: Int): Class<*> = if (column == 2) Integer::class.java else String::class.java
            override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
                when (column) {
                    0 -> it.table
                    1 -> it.operations.joinToString(", ").ifEmpty { "schema only" }
                    2 -> it.screens.size
                    else -> it.screens.joinToString(", ") { c -> short(c) }.ifEmpty { "-" }
                }
            }
        }
        val files = if (map.preferenceFiles.isEmpty()) "" else "  Settings files: ${map.preferenceFiles.joinToString(", ")}."
        return tablePage(model, "${rows.size} tables named by the app's SQL; ${rows.count { it.screens.isNotEmpty() }} are reached from a screen or component.$files", 220, 200, 70, 500)
    }

    /** A sortable table with a caption above it, used by the By Endpoint and By Table tabs. */
    private fun tablePage(model: AbstractTableModel, caption: String, vararg widths: Int): JComponent {
        val table = JTable(model)
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        table.fillsViewportHeight = true
        Ui.columnWidths(table, *widths)
        return JPanel(BorderLayout(0, 6)).apply {
            border = Ui.gap(6, 6, 6, 6)
            add(JLabel(caption), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    companion object {
        private val KINDS = mapOf(
            "ENDPOINT" to "Endpoint", "HOST" to "Server", "NETWORK_CALL" to "Network call", "SQL" to "SQL",
            "CONTENT_PROVIDER" to "Content provider", "PREFERENCE" to "Setting",
        )
    }
}
