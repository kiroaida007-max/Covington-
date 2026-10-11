package dev.apkproject.desktop

import dev.apkproject.app.AppDiff
import dev.apkproject.contracts.isWindows
import java.awt.BorderLayout
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import javax.swing.SwingWorker

/** The "Compare Versions" dialog: an older and a newer build of the same app. [ask] returns null on Cancel. */
class CompareDialog(private val owner: Frame, private val settings: Settings, old: Path? = null, new: Path? = null) : JDialog(owner, "Compare Versions", true) {
    private val oldField = JTextField(42)
    private val newField = JTextField(42)
    private var result: Pair<Path, Path>? = null

    init {
        val form = JPanel(GridBagLayout())
        form.border = Ui.gap(12, 12, 6, 12)
        form.add(JLabel("Compare two builds of the same app: what changed in what it asks for, talks to and can do,"), Ui.gbc(0, 0, width = 3))
        form.add(JLabel("and which code changed. Classes are matched by their code, so obfuscator renames are not changes."), Ui.gbc(0, 1, width = 3, insets = Insets(0, 0, 10, 0)))
        val oldLabel = JLabel("Older version:").apply { displayedMnemonic = KeyEvent.VK_O; labelFor = oldField }
        val newLabel = JLabel("Newer version:").apply { displayedMnemonic = KeyEvent.VK_N; labelFor = newField }
        form.add(oldLabel, Ui.gbc(0, 2))
        form.add(oldField, Ui.gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 6)))
        form.add(Ui.button("Browse...", KeyEvent.VK_B) { browse(oldField, "Open Older Version") }, Ui.gbc(2, 2, insets = Insets(3, 0, 3, 0)))
        form.add(newLabel, Ui.gbc(0, 3))
        form.add(newField, Ui.gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 6)))
        form.add(Ui.button("Browse...", KeyEvent.VK_R) { browse(newField, "Open Newer Version") }, Ui.gbc(2, 3, insets = Insets(3, 0, 3, 0)))
        form.add(JLabel("APK, AAB, APKS, XAPK, APKM or DEX. A large app takes about half a minute.").apply { foreground = java.awt.Color.GRAY }, Ui.gbc(1, 4, insets = Insets(0, 0, 4, 0)))

        val ok = Ui.button("Compare") { accept() }
        val cancel = Ui.button("Cancel") { dispose() }
        contentPane.layout = BorderLayout()
        contentPane.add(form, BorderLayout.CENTER)
        contentPane.add(Ui.buttonRow(ok, cancel).apply { border = Ui.gap(6, 12, 12, 12) }, BorderLayout.SOUTH)
        Ui.dialogKeys(this, ok)
        old?.let { oldField.text = it.toString() }
        new?.let { newField.text = it.toString() }
        pack()
        isResizable = false
        setLocationRelativeTo(owner)
    }

    /** Shows the dialog and blocks until it closes, returning the chosen pair or null on Cancel. */
    fun ask(): Pair<Path, Path>? {
        isVisible = true
        return result
    }

    /** The system's own Open dialog. */
    private fun browse(field: JTextField, title: String) {
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        settings.lastFolder?.let { dialog.directory = it }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in RecoverDialog.EXTENSIONS }
        if (isWindows()) dialog.file = RecoverDialog.EXTENSIONS.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val name = dialog.file ?: return
        field.text = Paths.get(dialog.directory, name).toString()
        settings.lastFolder = dialog.directory
    }

    /** Validates both fields and closes the dialog with the chosen pair, or leaves it open on error. */
    private fun accept() {
        /** Reads and validates one field, reporting an error and returning null if it is not a file. */
        fun file(field: JTextField, what: String): Path? {
            val p = field.text.trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
            if (p == null || !Files.isRegularFile(p)) {
                JOptionPane.showMessageDialog(this, "Choose the $what.", title, JOptionPane.WARNING_MESSAGE)
                field.requestFocusInWindow()
                return null
            }
            return p
        }
        val old = file(oldField, "older version") ?: return
        val new = file(newField, "newer version") ?: return
        if (old.toAbsolutePath() == new.toAbsolutePath()) {
            JOptionPane.showMessageDialog(this, "Choose two different files.", title, JOptionPane.WARNING_MESSAGE)
            return
        }
        result = old to new
        dispose()
    }

    companion object {
        /** Compares in the background behind a small progress dialog; returns null on failure (already reported). */
        fun run(owner: Frame, old: Path, new: Path): AppDiff.Result? {
            val dialog = JDialog(owner, "Comparing Versions", true)
            val message = JLabel("Reading ${old.fileName}...")
            val bar = JProgressBar().apply { isIndeterminate = true }
            val panel = JPanel(GridBagLayout())
            panel.border = Ui.gap(14, 14, 14, 14)
            panel.add(JLabel("${old.fileName}  →  ${new.fileName}"), Ui.gbc(0, 0, insets = Insets(0, 0, 8, 0)))
            panel.add(bar, Ui.gbc(0, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 6, 0)))
            panel.add(message, Ui.gbc(0, 2))
            panel.preferredSize = java.awt.Dimension(460, panel.preferredSize.height)
            dialog.contentPane = panel
            dialog.defaultCloseOperation = DO_NOTHING_ON_CLOSE
            dialog.pack()
            dialog.setLocationRelativeTo(owner)
            var outcome: Result<AppDiff.Result>? = null
            val worker = object : SwingWorker<AppDiff.Result, String>() {
                override fun doInBackground(): AppDiff.Result = AppDiff.compare(old, new) { publish(it) }
                override fun process(chunks: List<String>) { message.text = chunks.last().replaceFirstChar { it.uppercase() } + "..." }
                override fun done() {
                    outcome = runCatching { get() }
                    dialog.dispose()
                }
            }
            worker.execute()
            dialog.isVisible = true
            val failure = outcome?.exceptionOrNull()
            if (failure != null) {
                val cause = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
                JOptionPane.showMessageDialog(owner, "The versions could not be compared:\n${cause.message ?: cause}", "Compare Versions", JOptionPane.ERROR_MESSAGE)
                return null
            }
            return outcome?.getOrNull()
        }
    }
}
