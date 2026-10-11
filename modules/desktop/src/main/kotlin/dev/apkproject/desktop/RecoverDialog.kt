package dev.apkproject.desktop

import dev.apkproject.contracts.isWindows
import dev.apkproject.app.FullRecoveryOptions
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
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/** What the user asked to recover, and how. */
data class RecoveryRequest(
    val input: Path,
    val output: Path,
    val names: Boolean,
    val verify: Boolean,
    val repair: Boolean,
    val prove: Boolean,
    val libraries: Boolean,
    val started: Long = System.currentTimeMillis(),
    val vulnerabilities: Boolean = false,
) {
    internal fun options(z3: Boolean, rea: Boolean) = FullRecoveryOptions(
        nameLibraries = names,
        roundTrip = verify,
        repair = verify && repair,
        crossCheck = prove && z3,
        resolveLibraries = libraries,
        rea = rea,
        javaScript = rea,
        vulnerabilities = vulnerabilities,
    )
}

/** The "Recover APK" dialog: input file, output folder and options. [ask] returns null on Cancel. */
class RecoverDialog(private val owner: Frame, private val settings: Settings, input: Path?) : JDialog(owner, "Recover APK", true) {
    private val inputField = JTextField(42)
    private val outputField = JTextField(42)
    private val names = option("names", "Recover original &names", "Name R8-renamed library classes, and take back names the compilers left in the bytecode.")
    private val verify = option("verify", "&Verify every method against the original bytecode", "Recompiles the recovered source and compares each method. Slower, and the reason the result can be trusted.")
    private val repair = option("repair", "Re&pair decompiler mistakes", "Tries known fixes on methods that differ; a fix is kept only when recompiling proves it.")
    private val prove = option("prove", "Pro&ve simple methods with the Z3 solver", "Checks small arithmetic methods for every possible input.")
    private val libraries = option("libraries", "Use published &libraries (downloads from Maven)", "Replaces decompiled copies of libraries with the real published dependency when they match.")
    private val vulnerabilities = option("vulnerabilities", "Look up known &vulnerabilities on OSV.dev", "Sends detected Maven package names and versions to OSV.dev. The APK and recovered source are not uploaded. Leave off to skip this lookup.", false)
    private var result: RecoveryRequest? = null
    private var outputEdited = false

    init {
        val form = JPanel(GridBagLayout())
        form.border = Ui.gap(12, 12, 6, 12)
        val inputLabel = JLabel("Android app:").apply { displayedMnemonic = KeyEvent.VK_A; labelFor = inputField }
        val outputLabel = JLabel("Save project in:").apply { displayedMnemonic = KeyEvent.VK_S; labelFor = outputField }
        form.add(inputLabel, Ui.gbc(0, 0))
        form.add(inputField, Ui.gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 6)))
        form.add(Ui.button("Browse...", KeyEvent.VK_B) { browseInput() }, Ui.gbc(2, 0, insets = Insets(3, 0, 3, 0)))
        form.add(outputLabel, Ui.gbc(0, 1))
        form.add(outputField, Ui.gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 6)))
        form.add(Ui.button("Browse...", KeyEvent.VK_R) { browseOutput() }, Ui.gbc(2, 1, insets = Insets(3, 0, 3, 0)))
        form.add(JLabel("APK, AAB, APKS, XAPK, APKM or DEX").apply { foreground = java.awt.Color.GRAY }, Ui.gbc(1, 2, insets = Insets(0, 0, 8, 0)))

        val options = JPanel(GridBagLayout())
        listOf(names, verify, repair, prove, libraries, vulnerabilities).forEachIndexed { k, box ->
            options.add(box, Ui.gbc(0, k, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(1, if (box === repair) 20 else 0, 1, 0)))
        }
        options.add(Ui.readOnlyText("OSV lookup sends detected Maven package names and versions to OSV.dev. It does not upload the APK or recovered source."), Ui.gbc(0, 6, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(4, 20, 2, 0)))
        verify.addActionListener { repair.isEnabled = verify.isSelected }
        repair.isEnabled = verify.isSelected
        form.add(Ui.group("Options", options), Ui.gbc(0, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, width = 3, insets = Insets(4, 0, 6, 0)))
        form.add(JLabel("Recovery time depends on the app size and the selected checks."), Ui.gbc(0, 4, width = 3, insets = Insets(2, 0, 0, 0)))

        val ok = Ui.button("Recover") { accept() }
        val cancel = Ui.button("Cancel") { dispose() }
        val buttons = Ui.buttonRow(ok, cancel).apply { border = Ui.gap(6, 12, 12, 12) }
        contentPane.layout = BorderLayout()
        contentPane.add(form, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        Ui.dialogKeys(this, ok)
        outputField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { if (outputField.hasFocus()) outputEdited = true }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { if (outputField.hasFocus()) outputEdited = true }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })
        input?.let { setInput(it) }
        pack()
        isResizable = false
        setLocationRelativeTo(owner)
    }

    fun ask(): RecoveryRequest? {
        if (inputField.text.isBlank() && !browseInput()) return null
        isVisible = true
        return result
    }

    /** A check box whose label marks its mnemonic with `&`, as Windows resource files do. */
    private fun option(key: String, label: String, tip: String, default: Boolean = true): JCheckBox {
        val k = label.indexOf('&')
        val box = JCheckBox(label.replace("&", ""), settings.option(key, default))
        if (k >= 0) {
            box.mnemonic = KeyEvent.getExtendedKeyCodeForChar(label[k + 1].code)
            box.displayedMnemonicIndex = k
        }
        box.toolTipText = tip
        box.addActionListener { settings.setOption(key, box.isSelected) }
        return box
    }

    private fun setInput(file: Path) {
        inputField.text = file.toString()
        if (!outputEdited) outputField.text = unusedFolder(file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + "-recovered")).toString()
        file.parent?.let { settings.lastFolder = it.toString() }
    }

    /** The system's own Open dialog. */
    private fun browseInput(): Boolean {
        val dialog = FileDialog(owner, "Open Android App", FileDialog.LOAD)
        settings.lastFolder?.let { dialog.directory = it }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in EXTENSIONS }
        if (isWindows()) dialog.file = EXTENSIONS.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val name = dialog.file ?: return false
        setInput(Paths.get(dialog.directory, name))
        return true
    }

    private fun browseOutput() {
        val current = outputField.text.trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
        val chooser = JFileChooser(current?.parent?.toFile())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Save Project In"
        chooser.approveButtonText = "Select Folder"
        if (chooser.showDialog(this, null) != JFileChooser.APPROVE_OPTION) return
        val name = inputField.text.trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it).fileName.toString().substringBeforeLast('.') } ?: "app"
        outputField.text = unusedFolder(chooser.selectedFile.toPath().resolve("$name-recovered")).toString()
        outputEdited = true
    }

    private fun accept() {
        val input = runCatching { inputField.text.trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it) } }.getOrNull()
        if (input == null || !Files.isRegularFile(input)) {
            JOptionPane.showMessageDialog(this, "Choose the Android app to recover.", title, JOptionPane.WARNING_MESSAGE)
            inputField.requestFocusInWindow()
            return
        }
        val output = runCatching { outputField.text.trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it) } }.getOrNull()
        if (output == null) {
            JOptionPane.showMessageDialog(this, "Choose the folder to save the project in.", title, JOptionPane.WARNING_MESSAGE)
            outputField.requestFocusInWindow()
            return
        }
        if (Files.exists(output) && (!Files.isDirectory(output) || Files.list(output).use { it.findAny().isPresent })) {
            JOptionPane.showMessageDialog(this, "$output already exists and is not empty.\nChoose a new or empty folder.", title, JOptionPane.WARNING_MESSAGE)
            outputField.requestFocusInWindow()
            return
        }
        result = RecoveryRequest(input, output, names.isSelected, verify.isSelected, verify.isSelected && repair.isSelected, prove.isSelected, libraries.isSelected, vulnerabilities = vulnerabilities.isSelected)
        dispose()
    }

    companion object {
        val EXTENSIONS = listOf("apk", "aab", "apks", "xapk", "apkm", "dex")

        fun unusedFolder(base: Path): Path {
            if (!Files.exists(base) || (Files.isDirectory(base) && Files.list(base).use { !it.findAny().isPresent })) return base
            var k = 2
            while (true) {
                val p = base.resolveSibling("${base.fileName} ($k)")
                if (!Files.exists(p)) return p
                k++
            }
        }
    }
}
