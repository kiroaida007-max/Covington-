package dev.apkproject.desktop

import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/** Report availability is a workflow state, independent of the analysis findings in each report. */
internal class AnalysisStatusView(
    private val project: Project,
    private val navigate: (ProjectView.Page) -> Unit,
    refresh: () -> Unit,
) : JPanel(BorderLayout(6, 6)) {
    private val rows = project.reportStatuses
    private val reader = LatestTask("analysis-report-preview")
    private val detail = Ui.readOnlyText("Select an analysis report to inspect its availability and next step.", 5).apply { name = "analysisStatusDetail" }
    private val preview = JTextArea().apply { name = "analysisReportPreview"; isEditable = false; font = Ui.monospace }
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Analysis", "Project file", "Availability")[column]
        override fun getValueAt(row: Int, column: Int): String = rows[row].let {
            listOf(page(it.path)?.title ?: "Project files", it.path, label(it.state))[column]
        }
    }
    private val table = ReportTable(model).apply {
        name = "analysisStatusTable"; setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }
    private val sorter = TableRowSorter(model)
    private val show = Ui.button("Show Analysis") { selected()?.let { row -> page(row.path)?.let(navigate) } }.apply { isEnabled = false }
    private val read = Ui.button("Preview Report") { selected()?.let(::previewReport) }.apply { isEnabled = false }

    init {
        border = Ui.gap(8, 8, 8, 8)
        table.rowSorter = sorter
        Ui.columnWidths(table, 160, 400, 140)
        val issues = JCheckBox("Unavailable reports only").apply {
            name = "analysisStatusIssues"
            addActionListener {
                sorter.rowFilter = if (!isSelected) null else object : RowFilter<AbstractTableModel, Int>() {
                    override fun include(entry: Entry<out AbstractTableModel, out Int>) = rows[entry.identifier].state != ReportReader.State.LOADED
                }
            }
        }
        add(JPanel(BorderLayout(0, 6)).apply {
            add(Ui.readOnlyText("Loaded means the file could be read; it does not establish recovery accuracy or app safety. Missing, blocked, changed or invalid reports provide no negative finding. File hashes detect changes against this folder's inventory; they do not authenticate its publisher."), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(issues); add(Ui.button("Refresh Reports", run = refresh))
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        val lower = JPanel(BorderLayout(4, 4)).apply {
            add(JPanel(BorderLayout(4, 4)).apply {
                add(JScrollPane(detail), BorderLayout.CENTER)
                add(Ui.buttonRow(show, read), BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JScrollPane(preview), BorderLayout.CENTER)
        }
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), lower).apply {
            resizeWeight = 0.5; dividerSize = 5
        }, BorderLayout.CENTER)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
        if (rows.isNotEmpty()) {
            val index = rows.indexOfFirst { it.state !in setOf(ReportReader.State.LOADED, ReportReader.State.MISSING) }
                .takeIf { it >= 0 } ?: rows.indexOfFirst { it.path == "recovery/report.json" }.coerceAtLeast(0)
            table.setRowSelectionInterval(index, index)
        }
    }

    private fun selected() = table.selectedRow.takeIf { it >= 0 }?.let { rows[table.convertRowIndexToModel(it)] }
    private fun selectionChanged() {
        reader.cancel(); preview.text = "Use Preview Report to inspect the exported text here. No project code is executed."
        val row = selected()
        show.isEnabled = row?.let { page(it.path) != null } == true
        read.isEnabled = row != null && row.state !in setOf(ReportReader.State.MISSING, ReportReader.State.BLOCKED)
        detail.text = row?.let { "${it.path}\n${label(it.state)}: ${it.detail}\n\n${nextStep(it.state)}" } ?: "Select an analysis report."
        detail.caretPosition = 0
    }

    private fun previewReport(row: ReportReader.Status) {
        preview.text = "Loading report text…"
        reader.submit({ FilePreview.content(project.root.resolve(row.path), project.files, 512 * 1024).text }, {
            preview.text = it; preview.caretPosition = 0
        }, { preview.text = "Report preview unavailable: ${it.message}" })
    }

    fun dispose() = reader.close()

    companion object {
        fun label(state: ReportReader.State): String = when (state) {
            ReportReader.State.LOADED -> "Loaded"
            ReportReader.State.MISSING -> "Not available"
            ReportReader.State.INVALID -> "Invalid"
            ReportReader.State.UNREADABLE -> "Unreadable"
            ReportReader.State.LIMIT_REACHED -> "Loading limit reached"
            ReportReader.State.STALE -> "Changed since recovery"
            ReportReader.State.BLOCKED -> "Blocked location"
        }

        fun nextStep(state: ReportReader.State): String = when (state) {
            ReportReader.State.LOADED -> "Open the analysis to review its evidence, coverage and unresolved cases."
            ReportReader.State.MISSING -> "This analysis was not produced or its file is absent. Re-run recovery with the corresponding analysis enabled."
            ReportReader.State.STALE -> "The recorded checks no longer apply to these bytes. Recover into a new project folder before relying on them."
            ReportReader.State.BLOCKED -> "Links and special files are not analysis input. Use a regular exported file inside this project and refresh the reports."
            ReportReader.State.LIMIT_REACHED -> "The desktop withheld this report to stay within its loading budget. Inspect the export separately or recover a smaller input."
            ReportReader.State.INVALID -> "Inspect the report as text, then regenerate it with a compatible software version. A partial report cannot support a clean result."
            ReportReader.State.UNREADABLE -> "Check the exported file and its read permissions, then refresh the reports."
        }

        fun page(path: String): ProjectView.Page? = when (path) {
            "recovery/report.json" -> ProjectView.Page.SUMMARY
            "recovery/recovered-names.json" -> ProjectView.Page.NAMES
            "recovery/RECOVERY.md" -> ProjectView.Page.REPORT
            "recovery/round-trip.json", "recovery/compile-diagnostics.json", "recovery/javac-relaxed.txt" -> ProjectView.Page.SOURCE
            "backend/api-contract.json", "backend/contract-audit.json", "backend/embedded/report.json" -> ProjectView.Page.BACKEND
            "audit/ui-audit.json" -> ProjectView.Page.UI_RISK
            "audit/privacy.json" -> ProjectView.Page.PRIVACY
            "audit/screen-map.json" -> ProjectView.Page.SCREENS
            "audit/supply-chain.json" -> ProjectView.Page.COMPONENTS
            else -> null
        }
    }
}
