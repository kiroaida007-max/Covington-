package dev.apkproject.desktop

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.isWindows
import dev.apkproject.orchestrator.ToolchainInstaller
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Tools: which pinned tools are installed, where, and a button that installs the missing ones. */
class ToolsDialog(private val owner: Frame, private val env: Environment, private val settings: Settings) : JDialog(owner, "Tools", true) {
    private var statuses: List<ToolchainInstaller.Status> = emptyList()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = statuses.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Tool", "Status", "Details")[column]
        override fun getValueAt(row: Int, column: Int): Any = statuses[row].let { s ->
            when (column) {
                0 -> s.spec.title
                1 -> state(s)
                else -> detail(s)
            }
        }
    }
    private val table = JTable(model)
    private val folder = JTextField(40).apply { isEditable = false }
    private val runtime = JLabel(" ")
    private val bar = JProgressBar(0, 1000).apply { isStringPainted = true; isVisible = false }
    private val message = JLabel(" ")
    private val install = JButton("Install")
    private val close = JButton("Close")
    private var token: CancellationToken? = null

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = closeOrCancel()
        })
        val body = JPanel(GridBagLayout())
        body.border = Ui.gap(12, 12, 6, 12)
        var y = 0
        body.add(JLabel("<html>The decompiler and compilers this program uses. They are downloaded once from their publishers and checked against SHA-256 hashes pinned in this version.</html>").apply {
            preferredSize = java.awt.Dimension(560, preferredSize.height * 2)
        }, Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, width = 3, insets = Insets(0, 0, 8, 0)))
        table.setShowGrid(false)
        table.fillsViewportHeight = true
        table.rowSelectionAllowed = false
        table.setDefaultRenderer(Any::class.java, StateRenderer())
        Ui.columnWidths(table, 170, 100, 330)
        val scroll = JScrollPane(table).apply { preferredSize = java.awt.Dimension(600, table.rowHeight * 7 + 4) }
        body.add(scroll, Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.BOTH, width = 3, insets = Insets(0, 0, 8, 0)).apply { weighty = 1.0 })
        body.add(JLabel("Folder:").apply { displayedMnemonic = KeyEvent.VK_F; labelFor = folder }, Ui.gbc(0, y))
        body.add(folder, Ui.gbc(1, y, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(2, 0, 2, 6)))
        body.add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
            add(Ui.button("Change...", KeyEvent.VK_H) { chooseFolder() })
            add(Ui.button("Open", KeyEvent.VK_O) { Ui.open(this@ToolsDialog, Files.createDirectories(env.tools)) })
            add(Ui.button("Default", KeyEvent.VK_D) { settings.toolsDir = null; refresh() })
        }, Ui.gbc(2, y++, insets = Insets(2, 0, 2, 0)))
        body.add(JLabel("Java:"), Ui.gbc(0, y))
        body.add(runtime, Ui.gbc(1, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, width = 2))
        body.add(bar, Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, width = 3, insets = Insets(10, 0, 2, 0)))
        body.add(message, Ui.gbc(0, y, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, width = 3, insets = Insets(2, 0, 0, 0)))

        install.setMnemonic(KeyEvent.VK_I)
        install.addActionListener { startInstall() }
        close.addActionListener { closeOrCancel() }
        val buttons = Ui.buttonRow(install, Ui.button("Refresh", KeyEvent.VK_R) { refresh() }, close).apply { border = Ui.gap(6, 12, 12, 12) }
        contentPane.layout = BorderLayout()
        contentPane.add(body, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        Ui.dialogKeys(this, close) { closeOrCancel() }
        refresh()
        pack()
        setLocationRelativeTo(owner)
    }

    /** Reloads each tool's installed status, the tools folder and the detected JDK. */
    fun refresh() {
        statuses = env.installer().status()
        model.fireTableDataChanged()
        folder.text = env.tools.toString()
        val jdk = env.jdkHome()
        runtime.text = jdk?.let { "Java 17 with javac at $it" } ?: "No Java 17 JDK found. Install one (for example Eclipse Temurin 17), or use the installed program, which brings its own."
        runtime.foreground = if (jdk == null) Ui.bad else javax.swing.UIManager.getColor("Label.foreground")
        install.isEnabled = token == null && statuses.any { it.state != ToolchainInstaller.Status.State.INSTALLED }
    }

    /** Installs every missing or damaged tool; called from the button or when the dialog opens for it. */
    fun startInstall() {
        if (token != null) return
        val cancel = CancellationToken()
        token = cancel
        install.isEnabled = false
        close.text = "Cancel"
        bar.isVisible = true
        bar.value = 0
        message.foreground = javax.swing.UIManager.getColor("Label.foreground")
        message.text = "Starting..."
        pack()
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
                        } else {
                            bar.isIndeterminate = true
                            bar.string = ""
                        }
                    }
                }, cancel).also { if (isWindows()) env.jdkHome()?.let { installer.completeWindowsZ3(it) } }
            }
            SwingUtilities.invokeLater { finished(result) }
        }, "tool-install").apply { isDaemon = true }.start()
    }

    /** Reports the install outcome and refreshes the tool list. */
    private fun finished(result: Result<List<ToolchainInstaller.Status>>) {
        token = null
        close.text = "Close"
        close.isEnabled = true
        bar.isVisible = false
        val failures = result.getOrNull()?.filter { it.state != ToolchainInstaller.Status.State.INSTALLED }.orEmpty()
        val requiredFailed = failures.any { !it.spec.optional }
        message.text = when {
            result.isFailure -> "Installation stopped: ${result.exceptionOrNull()?.message}"
            failures.isEmpty() -> "All tools are installed and verified."
            else -> "Not installed: " + failures.joinToString("; ") { "${it.spec.title} (${it.detail})" }
        }
        message.toolTipText = message.text
        message.foreground = if (result.isFailure || requiredFailed) Ui.bad else Ui.good
        refresh()
        pack()
    }

    /** Cancels a running install, or closes the dialog if none is running. */
    private fun closeOrCancel() {
        val t = token
        if (t != null) {
            t.cancel()
            close.isEnabled = false
            message.text = "Cancelling..."
        } else {
            dispose()
        }
    }

    /** Asks for a new tools folder and refreshes the status with it. */
    private fun chooseFolder() {
        val chooser = JFileChooser(env.tools.toFile())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Tools Folder"
        chooser.approveButtonText = "Select Folder"
        if (chooser.showDialog(this, null) == JFileChooser.APPROVE_OPTION) {
            settings.toolsDir = chooser.selectedFile.absolutePath
            refresh()
        }
    }

    /** The display text for a tool's install state. */
    private fun state(s: ToolchainInstaller.Status) = when (s.state) {
        ToolchainInstaller.Status.State.INSTALLED -> "Installed"
        ToolchainInstaller.Status.State.MISSING -> if (s.spec.optional) "Not installed (optional)" else "Not installed"
        ToolchainInstaller.Status.State.UNSUPPORTED -> "Not available"
        else -> "Damaged"
    }

    /** The detail text for a tool's install state. */
    private fun detail(s: ToolchainInstaller.Status) = when {
        s.state == ToolchainInstaller.Status.State.INSTALLED -> "Matches the pinned SHA-256 hash"
        s.spec.optional && s.state == ToolchainInstaller.Status.State.MISSING -> "Only needed to prove simple methods"
        else -> s.detail
    }

    private inner class StateRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, false, false, row, column)
            border = Ui.gap(0, 4, 0, 4)
            toolTipText = value?.toString()
            foreground = if (column != 1) table.foreground else when (statuses[row].state) {
                ToolchainInstaller.Status.State.INSTALLED -> Ui.good
                ToolchainInstaller.Status.State.MISSING -> if (statuses[row].spec.optional) table.foreground else Ui.warn
                else -> Ui.bad
            }
            return this
        }
    }
}
