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
import javax.swing.JScrollPane
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
    val repairModel: Path? = null,
    val privacyModel: Boolean = false,
    val repairAssistant: Path? = null,
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
        repairModelPath = repairModel?.toAbsolutePath()?.normalize()?.toString(),
        privacyApiModel = privacyModel,
        repairAssistantPath = repairAssistant?.toAbsolutePath()?.normalize()?.toString(),
    )
}

/** The "Recover APK" dialog: input file, output folder and options. [ask] returns null on Cancel. */
class RecoverDialog(private val owner: Frame, private val settings: Settings, input: Path?) : JDialog(owner, "Recover APK", true) {
    private val inputField = JTextField(42)
    private val outputField = JTextField(42)
    private val repairModelField = JTextField(32)
    private val repairAssistantField = JTextField(32)
    private val names = option("names", "Recover original &names", "Name R8-renamed library classes, and take back names the compilers left in the bytecode.")
    private val verify = option("verify", "&Verify every method against the original bytecode", "Recompiles the recovered source and compares each method. Slower, and the reason the result can be trusted.")
    private val repair = option("repair", "Re&pair decompiler mistakes", "Tries known fixes on methods that differ; a fix is kept only when recompiling proves it.")
    private val prove = option("prove", "Pro&ve simple methods with the Z3 solver", "Checks small arithmetic methods for every possible input.")
    private val libraries = option("libraries", "Use published &libraries (downloads from Maven)", "Replaces decompiled copies of libraries with the real published dependency when they match.")
    private val vulnerabilities = option("vulnerabilities", "Look up known &vulnerabilities on OSV.dev", "Sends detected Maven package names and versions to OSV.dev. The APK and recovered source are not uploaded. Leave off to skip this lookup.", false)
    private val privacyModel = option("privacyModel", "Suggest additional privacy sources (experimental ML)", "Uses signature-based predictions, labelled as model-based. Confidence is not measured accuracy. Disabled by default; known privacy rules remain available.", false)
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
        listOf(names, verify, repair, prove, libraries, vulnerabilities, privacyModel).forEachIndexed { k, box ->
            options.add(box, Ui.gbc(0, k, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(1, if (box === repair) 20 else 0, 1, 0)))
        }
        options.add(Ui.readOnlyText("OSV lookup sends detected Maven package names and versions to OSV.dev. It does not upload the APK or recovered source."), Ui.gbc(0, 7, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(4, 20, 2, 0)))
        verify.addActionListener { repair.isEnabled = verify.isSelected }
        repair.isEnabled = verify.isSelected
        form.add(Ui.group("Options", options), Ui.gbc(0, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, width = 3, insets = Insets(4, 0, 6, 0)))
        val learning = JPanel(GridBagLayout())
        learning.add(JLabel("Repair model (optional):"), Ui.gbc(0, 0))
        learning.add(repairModelField, Ui.gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        learning.add(Ui.button("Browse…") {
            val chooser = JFileChooser().apply { dialogTitle = "Open Local Repair Priority Model" }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) repairModelField.text = chooser.selectedFile.absolutePath
        }, Ui.gbc(2, 0))
        learning.add(Ui.readOnlyText("A model can reorder repair proposals. Every kept edit still passes the bytecode checks. Invalid or unpromoted models use the normal rule order. Checked feature-only feedback is saved locally in recovery/learning-repairs.jsonl."), Ui.gbc(0, 1, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        learning.add(JLabel("Local coding assistant:"), Ui.gbc(0, 2))
        learning.add(repairAssistantField, Ui.gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        learning.add(Ui.button("Browse…") {
            val chooser = JFileChooser().apply { dialogTitle = "Open Local Coding Assistant Configuration" }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) repairAssistantField.text = chooser.selectedFile.absolutePath
        }, Ui.gbc(2, 2))
        learning.add(Ui.readOnlyText("Optional downloaded model and local repair examples. It can suggest more fixes, with a CPU and memory cost. Only completely checked edits are kept. Source stays on this computer."), Ui.gbc(0, 3, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        form.add(Ui.group("Local learning", learning), Ui.gbc(0, 4, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        form.add(JLabel("Recovery time depends on the app size and the selected checks."), Ui.gbc(0, 5, width = 3, insets = Insets(2, 0, 0, 0)))

        val ok = Ui.button("Recover") { accept() }
        val cancel = Ui.button("Cancel") { dispose() }
        val buttons = Ui.buttonRow(ok, cancel).apply { border = Ui.gap(6, 12, 12, 12) }
        contentPane.layout = BorderLayout()
        val scroll = JScrollPane(form).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 20
        }
        contentPane.add(scroll, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        Ui.dialogKeys(this, ok)
        outputField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { if (outputField.hasFocus()) outputEdited = true }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { if (outputField.hasFocus()) outputEdited = true }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })
        input?.let { setInput(it) }
        pack()
        val screen = graphicsConfiguration.bounds
        val screenInsets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
        val usableHeight = screen.height - screenInsets.top - screenInsets.bottom - 40
        setSize(width, minOf(height, usableHeight.coerceAtLeast(240)))
        isResizable = true
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
        val model = runCatching { repairModelField.text.takeIf { it.isNotBlank() }?.let(Path::of) }.getOrElse {
            JOptionPane.showMessageDialog(this, "Select a valid local repair model path.", title, JOptionPane.WARNING_MESSAGE); return
        }
        if (model != null && !Files.isRegularFile(model)) {
            JOptionPane.showMessageDialog(this, "The repair model file does not exist.", title, JOptionPane.WARNING_MESSAGE); return
        }
        val assistant = runCatching { repairAssistantField.text.trim().takeIf { it.isNotEmpty() }?.let(Path::of) }.getOrElse {
            JOptionPane.showMessageDialog(this, "Select a valid local assistant configuration.", title, JOptionPane.WARNING_MESSAGE); return
        }
        if (assistant != null && !Files.isRegularFile(assistant)) {
            JOptionPane.showMessageDialog(this, "The local assistant configuration file does not exist.", title, JOptionPane.WARNING_MESSAGE); return
        }
        result = RecoveryRequest(input, output, names.isSelected, verify.isSelected, verify.isSelected && repair.isSelected, prove.isSelected, libraries.isSelected,
            vulnerabilities = vulnerabilities.isSelected, repairModel = model, privacyModel = privacyModel.isSelected, repairAssistant = assistant)
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
