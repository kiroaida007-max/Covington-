package dev.apkproject.desktop

import dev.apkproject.app.FullJobHistory
import dev.apkproject.app.FullJobHistoryEntry
import dev.apkproject.app.FullJobFiles
import dev.apkproject.app.RecoveryDiagnostics
import java.awt.BorderLayout
import java.awt.Frame
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Native task history, including interrupted jobs and their retained partial project folders. */
class JobHistoryDialog(private val owner: Frame, private val env: Environment, private val openProject: (Path) -> Unit) : JDialog(owner, "Recovery Jobs", true) {
    private val model = object : DefaultTableModel(arrayOf("Input", "State", "Last update", "Details"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = ReportTable(model).apply { name = "jobHistoryTable"; setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private var entries = emptyList<FullJobHistoryEntry>()
    private val status = JLabel("Loading jobs...")

    init {
        layout = BorderLayout(8, 8)
        rootPane.border = Ui.gap(12, 12, 12, 12)
        add(JScrollPane(table), BorderLayout.CENTER)
        val resume = JButton("Resume").apply { addActionListener { selected()?.let(::resume) } }
        val folder = JButton("Open Job Folder").apply { addActionListener { selected()?.let { java.awt.Desktop.getDesktop().open(it.dir.toFile()) } } }
        val export = JButton("Export Diagnostics...").apply { addActionListener { selected()?.let(::export) } }
        val close = JButton("Close").apply { addActionListener { dispose() } }
        add(JPanel(BorderLayout()).apply { add(status, BorderLayout.NORTH); add(Ui.buttonRow(resume, folder, export, close), BorderLayout.SOUTH) }, BorderLayout.SOUTH)
        setSize(950, 400); setLocationRelativeTo(owner); Ui.dialogKeys(this, close) { dispose() }
        refresh()
    }

    private fun selected(): FullJobHistoryEntry? = table.selectedRow.takeIf { it >= 0 }?.let { entries[table.convertRowIndexToModel(it)] }
    private fun refresh() {
        Thread({
            val result = runCatching { FullJobHistory.list(env.appData.resolve("jobs")) }
            SwingUtilities.invokeLater {
                entries = result.getOrDefault(emptyList()); model.rowCount = 0
                entries.forEach { model.addRow(arrayOf(it.state.inputName, it.state.status,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.state.updatedAt)), it.state.message)) }
                status.text = result.exceptionOrNull()?.message ?: "${entries.size} jobs; checkpoints are checked again when resuming."
                if (entries.isNotEmpty()) table.setRowSelectionInterval(0, 0)
            }
        }, "job-history").apply { isDaemon = true }.start()
    }

    private fun resume(entry: FullJobHistoryEntry) {
        runCatching {
            val r = FullJobFiles(entry.dir).readRequest()
            val request = RecoveryRequest(Path.of(r.input), Path.of(r.output), r.options.nameLibraries, r.options.roundTrip,
                r.options.repair, r.options.crossCheck, r.options.resolveLibraries)
            val output = RecoveryDialog(owner, env, request, entry.dir) {}.run()
            if (output != null) { dispose(); openProject(output) } else refresh()
        }.onFailure { JOptionPane.showMessageDialog(this, it.message, title, JOptionPane.ERROR_MESSAGE) }
    }

    private fun export(entry: FullJobHistoryEntry) {
        val chooser = JFileChooser().apply { dialogTitle = "Export Job Diagnostics"; selectedFile = java.io.File("recovery-${entry.state.jobId}.zip") }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.toPath()
        status.text = "Writing diagnostic bundle..."
        Thread({
            val result = runCatching { RecoveryDiagnostics.export(entry.dir, target) }
            SwingUtilities.invokeLater { status.text = result.exceptionOrNull()?.message ?: "Diagnostics saved to $target" }
        }, "job-diagnostics").apply { isDaemon = true }.start()
    }
}
