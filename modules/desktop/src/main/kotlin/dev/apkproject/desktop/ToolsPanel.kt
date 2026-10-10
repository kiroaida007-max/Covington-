package dev.apkproject.desktop

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.isWindows
import dev.apkproject.orchestrator.ToolchainInstaller
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.nio.file.Files
import javax.swing.*
import javax.swing.table.AbstractTableModel

class ToolsPanel(private val env: Environment, private val settings: Settings, private val onChange: () -> Unit) : JPanel(BorderLayout(6, 6)) {
    private var states = emptyList<ToolchainInstaller.Status>()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = states.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Tool", "Status", "Details")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val s = states[row]
            val status = when (s.state) {
                ToolchainInstaller.Status.State.INSTALLED -> "Installed"
                ToolchainInstaller.Status.State.MISSING -> if (s.spec.optional) "Optional" else "Not installed"
                else -> "Damaged"
            }
            val detail = when {
                s.state == ToolchainInstaller.Status.State.INSTALLED -> "SHA-256 matches the pinned hash"
                s.spec.optional && s.state == ToolchainInstaller.Status.State.MISSING -> "Used for proofs; recovery works without it"
                else -> s.detail
            }
            return listOf(s.spec.title, status, detail)[column]
        }
    }
    private val folder = JTextField().apply { isEditable = false }
    private val runtime = JTextField().apply { isEditable = false }
    private val bar = JProgressBar(0, 1000).apply { isVisible = false; isStringPainted = true }
    private val message = JLabel("Checking tools…")
    private val install = Ui.primary("Install missing tools") { install() }
    private val cancel = Ui.secondary("Cancel") { cancelInstall() }.apply { isEnabled = false }
    private val change = Ui.secondary("Change…") { chooseFolder() }
    private val useDefault = Ui.secondary("Use default") { settings.toolsDir = null; refresh() }
    private var token: CancellationToken? = null
    private var generation = 0
    val installing: Boolean get() = token != null

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        val table = JTable(model).apply {
            name = "toolTable"
            fillsViewportHeight = true
            columnModel.getColumn(0).preferredWidth = 180
            columnModel.getColumn(1).preferredWidth = 120
            columnModel.getColumn(2).preferredWidth = 650
        }
        val locations = GroupBox(java.awt.GridLayout(0, 1, 0, 4), title = "Locations")
        locations.add(JLabel("Tools folder:")); locations.add(folder)
        locations.add(Ui.row(change, Ui.secondary("Open folder") { openFolder() }, useDefault))
        locations.add(JLabel("Java runtime:")); locations.add(runtime)
        add(Ui.column(Ui.title("Toolchain"), Ui.wrapped("Download tools from their publishers. This version verifies each download against a pinned SHA-256 hash.", 700), locations), BorderLayout.NORTH)
        add(GroupBox(BorderLayout(), title = "Installed tools").apply { add(JScrollPane(table).apply { preferredSize = Dimension(900, 220) }) }, BorderLayout.CENTER)
        add(Ui.column(Ui.row(install, cancel, Ui.secondary("Check again") { refresh() }), bar, message), BorderLayout.SOUTH)
        refresh()
    }

    fun ready() = states.isNotEmpty() && states.all { it.state == ToolchainInstaller.Status.State.INSTALLED || it.spec.optional }

    /** Hashing tools never blocks Swing's event thread. Older folder checks cannot overwrite newer ones. */
    fun refresh() {
        if (installing) return
        val revision = ++generation
        val installer = env.installer()
        folder.text = env.tools.toString()
        Thread({
            val result = runCatching { installer.status() to env.jdkHome() }
            SwingUtilities.invokeLater {
                if (revision != generation || installing) return@invokeLater
                result.onSuccess { (checks, jdk) ->
                    states = checks
                    model.fireTableDataChanged()
                    runtime.text = jdk?.let { "Java 17 with javac: $it" } ?: "Java 17 JDK not found; the packaged app includes one."
                    install.isEnabled = checks.any { it.state != ToolchainInstaller.Status.State.INSTALLED }
                    message.text = if (ready()) "Required tools are installed." else "Install the missing tools before recovery."
                }.onFailure { message.text = "Tool check failed: ${it.message}" }
                onChange()
            }
        }, "tool-check").apply { isDaemon = true }.start()
    }

    fun install() {
        if (installing) return
        ++generation
        val cancellation = CancellationToken()
        token = cancellation
        install.isEnabled = false
        change.isEnabled = false
        useDefault.isEnabled = false
        cancel.isEnabled = true
        bar.isVisible = true
        bar.value = 0
        message.text = "Starting…"
        val installer = env.installer()
        Thread({
            val result = runCatching {
                installer.install({ _, text, done, total ->
                    SwingUtilities.invokeLater {
                        message.text = text
                        if (total != null && total > 0) {
                            bar.isIndeterminate = false
                            bar.value = (done * 1000 / total).toInt()
                            bar.string = "${Ui.human(done)} of ${Ui.human(total)}"
                        } else { bar.isIndeterminate = true; bar.string = text }
                    }
                }, cancellation).also { if (isWindows()) env.jdkHome()?.let { installer.completeWindowsZ3(it) } }
            }
            SwingUtilities.invokeLater {
                token = null
                install.isEnabled = true
                change.isEnabled = true
                useDefault.isEnabled = true
                cancel.isEnabled = false
                bar.isVisible = false
                result.onSuccess { states = it; model.fireTableDataChanged(); onChange() }
                message.text = when {
                    cancellation.cancelled -> "Installation cancelled."
                    result.isFailure -> "Installation stopped: ${result.exceptionOrNull()?.message}"
                    states.all { it.state == ToolchainInstaller.Status.State.INSTALLED } -> "All tools are installed and verified."
                    else -> "Some tools are missing. Check the details above, then retry."
                }
            }
        }, "tool-install").apply { isDaemon = true }.start()
    }

    fun cancelInstall() { token?.cancel(); cancel.isEnabled = false }

    private fun chooseFolder() {
        val chooser = JFileChooser(env.tools.toFile()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Folder for tools" }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { settings.toolsDir = chooser.selectedFile.absolutePath; refresh() }
    }

    private fun openFolder() {
        val dir = Files.createDirectories(env.tools)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) runCatching { Desktop.getDesktop().open(dir.toFile()) }.onSuccess { return }
        JOptionPane.showMessageDialog(this, dir.toString(), "Tools folder", JOptionPane.INFORMATION_MESSAGE)
    }
}
