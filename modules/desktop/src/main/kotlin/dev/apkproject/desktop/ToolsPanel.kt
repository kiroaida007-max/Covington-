package dev.apkproject.desktop

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.isWindows
import dev.apkproject.orchestrator.ToolchainInstaller
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class ToolsPanel(private val env: Environment, private val settings: Settings, private val onChange: () -> Unit) : JPanel(BorderLayout()) {
    private val list = JPanel(GridBagLayout()).apply { isOpaque = false }
    private val folder = Ui.muted("")
    private val runtime = Ui.muted("")
    private val bar = JProgressBar(0, 1000).apply { isVisible = false; isStringPainted = true }
    private val message = Ui.muted("")
    private val install = Ui.primary("Install missing tools") { install() }
    private var token: CancellationToken? = null

    init {
        isOpaque = false
        val content = JPanel(GridBagLayout()).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(28, 36, 36, 36) }
        val c = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST }
        var y = 0
        content.add(Ui.title("Tools"), c.apply { gridy = y++ })
        content.add(Ui.wrapped("The decompiler and compilers this app drives. They are downloaded once from their publishers and checked against SHA-256 hashes pinned in this version, so every result can be reproduced with exactly the same tools.", 760, size = 14f), c.apply { gridy = y++; insets = Insets(6, 0, 0, 0) })
        val tools = Card(BorderLayout(0, 12))
        tools.add(list, BorderLayout.CENTER)
        tools.add(Ui.column(Ui.row(install, Ui.secondary("Check again") { refresh() }, gap = 10), bar, message, gap = 10), BorderLayout.SOUTH)
        content.add(tools, c.apply { gridy = y++; insets = Insets(20, 0, 0, 0) })
        val where = Card(GridBagLayout())
        val wc = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.WEST }
        where.add(Ui.heading("Tools folder"), wc.apply { gridy = 0 })
        where.add(folder, wc.apply { gridy = 1; insets = Insets(4, 0, 8, 0) })
        where.add(Ui.row(Ui.secondary("Change…") { chooseFolder() }, Ui.secondary("Open") { openFolder() }, Ui.link("Use the default") { settings.toolsDir = null; refresh() }), wc.apply { gridy = 2 })
        where.add(Ui.heading("Java runtime"), wc.apply { gridy = 3; insets = Insets(18, 0, 0, 0) })
        where.add(runtime, wc.apply { gridy = 4; insets = Insets(4, 0, 0, 0) })
        content.add(where, c.apply { gridy = y++; insets = Insets(18, 0, 0, 0) })
        content.add(JPanel().apply { isOpaque = false }, c.apply { gridy = y; weighty = 1.0 })
        add(JScrollPane(content).apply { border = null; isOpaque = false; viewport.isOpaque = false }, BorderLayout.CENTER)
        refresh()
    }

    /** True when everything needed to recover an app is installed (proofs with Z3 are optional). */
    fun ready(): Boolean = env.installer().status().all { it.state == ToolchainInstaller.Status.State.INSTALLED || it.spec.optional }

    fun refresh() {
        list.removeAll()
        val c = GridBagConstraints().apply { gridy = 0; anchor = GridBagConstraints.WEST; insets = Insets(6, 0, 6, 16) }
        list.add(Ui.heading("Tool"), c.apply { gridx = 0 })
        list.add(Ui.heading("Status"), c.apply { gridx = 1 })
        list.add(Ui.heading("Details"), c.apply { gridx = 2; weightx = 1.0 })
        for ((k, s) in env.installer().status().withIndex()) {
            c.gridy = k + 1
            list.add(JLabel(s.spec.title).apply { font = Theme.font(14f) }, c.apply { gridx = 0; weightx = 0.0 })
            val chip = when (s.state) {
                ToolchainInstaller.Status.State.INSTALLED -> Chip.good("Installed")
                ToolchainInstaller.Status.State.MISSING -> if (s.spec.optional) Chip.info("Optional") else Chip.warn("Not installed")
                else -> Chip.bad("Damaged")
            }
            list.add(chip, c.apply { gridx = 1 })
            val detail = if (s.state == ToolchainInstaller.Status.State.INSTALLED) "SHA-256 matches the pinned hash" else if (s.spec.optional && s.state == ToolchainInstaller.Status.State.MISSING) "used only for proofs; recovery works without it" else s.detail
            list.add(Ui.muted(detail, 12.5f), c.apply { gridx = 2; weightx = 1.0 })
        }
        folder.text = env.tools.toString()
        val jdk = env.jdkHome()
        runtime.text = if (jdk != null) "Java 17 with javac: $jdk" else "No Java 17 JDK found. Install one (for example Eclipse Temurin 17) or use the packaged app, which brings its own."
        runtime.foreground = if (jdk != null) Theme.muted else Theme.bad
        install.isVisible = !ready() || env.installer().status().any { it.state != ToolchainInstaller.Status.State.INSTALLED }
        list.revalidate()
        list.repaint()
        onChange()
    }

    fun install() {
        if (token != null) return
        val cancel = CancellationToken()
        token = cancel
        install.isEnabled = false
        bar.isVisible = true
        bar.value = 0
        message.text = "Starting…"
        val installer = env.installer()
        Thread({
            val result = runCatching {
                installer.install({ _, text, done, total ->
                    SwingUtilities.invokeLater {
                        message.text = text
                        if (total != null && total > 0) { bar.isIndeterminate = false; bar.value = (done * 1000 / total).toInt(); bar.string = "${Ui.human(done)} of ${Ui.human(total)}" }
                        else { bar.isIndeterminate = true; bar.string = text }
                    }
                }, cancel).also { if (isWindows()) env.jdkHome()?.let { installer.completeWindowsZ3(it) } }
            }
            SwingUtilities.invokeLater {
                token = null
                install.isEnabled = true
                bar.isVisible = false
                val failures = result.getOrNull()?.filter { it.state != ToolchainInstaller.Status.State.INSTALLED }.orEmpty()
                message.text = when {
                    result.isFailure -> "Installation stopped: ${result.exceptionOrNull()?.message}"
                    failures.isEmpty() -> "All tools are installed and verified."
                    else -> "Some tools could not be installed: " + failures.joinToString("; ") { "${it.spec.title}: ${it.detail}" }
                }
                message.foreground = if (result.isSuccess && failures.none { !it.spec.optional }) Theme.good else Theme.bad
                refresh()
            }
        }, "tool-install").apply { isDaemon = true }.start()
    }

    private fun chooseFolder() {
        val chooser = JFileChooser(env.tools.toFile())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Folder for the tools"
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.toolsDir = chooser.selectedFile.absolutePath
            refresh()
        }
    }

    private fun openFolder() {
        val dir = Files.createDirectories(env.tools)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) runCatching { Desktop.getDesktop().open(dir.toFile()) }.onSuccess { return }
        JOptionPane.showMessageDialog(this, dir.toString(), "Tools folder", JOptionPane.INFORMATION_MESSAGE)
    }
}
