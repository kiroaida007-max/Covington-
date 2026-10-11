package dev.apkproject.desktop

import dev.apkproject.app.FullRecovery
import dev.apkproject.app.FullRecoveryOptions
import dev.apkproject.app.FullRecoveryResult
import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExitCode
import dev.apkproject.orchestrator.Doctor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Runs one recovery in a progress dialog, like Windows' file-copy dialog: the current step, a bar,
 * the latest message, elapsed time, and the full log under "More details". [run] blocks until the
 * dialog closes and returns the project folder, or null if the recovery failed or was cancelled.
 */
class RecoveryDialog(private val owner: Frame, private val env: Environment, private val request: RecoveryRequest, private val onNeedTools: () -> Unit) :
    JDialog(owner, "Recovering ${request.input.fileName}", true) {
    private val step = JLabel("Preparing...")
    private val bar = JProgressBar(0, Stage.entries.size)
    private val detail = JLabel(" ")
    private val elapsed = JLabel(" ")
    private val log = JTextArea(14, 90).apply { isEditable = false; font = Ui.monospace; lineWrap = false }
    private val logScroll = JScrollPane(log).apply { isVisible = false }
    private val details = JButton("More details")
    private val cancel = JButton("Cancel")
    private val token = CancellationToken()
    private var running = false
    private var output: Path? = null
    private var current: Stage? = null
    private val ticker = Timer(1000) { elapsed.text = "Elapsed time: " + Ui.duration(System.currentTimeMillis() - request.started) }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = if (running) cancelRun() else dispose()
        })
        val body = JPanel(GridBagLayout())
        body.border = Ui.gap(12, 12, 6, 12)
        val wide = Dimension(520, step.preferredSize.height)
        listOf(step, detail, elapsed).forEach { it.preferredSize = wide; it.minimumSize = Dimension(200, wide.height) }
        body.add(JLabel("From: ${request.input}"), Ui.gbc(0, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 2, 0)))
        body.add(JLabel("To: ${request.output}"), Ui.gbc(0, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        body.add(step, Ui.gbc(0, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 4, 0)))
        body.add(bar, Ui.gbc(0, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 4, 0)))
        body.add(detail, Ui.gbc(0, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 2, 0)))
        body.add(elapsed, Ui.gbc(0, 5, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 6, 0)))
        body.add(logScroll, Ui.gbc(0, 6, weightx = 1.0, fill = GridBagConstraints.BOTH, insets = Insets(0, 0, 0, 0)).apply { weighty = 1.0 })
        details.addActionListener { toggleDetails() }
        cancel.addActionListener { if (running) cancelRun() else dispose() }
        val south = JPanel(BorderLayout()).apply {
            border = Ui.gap(6, 12, 12, 12)
            add(details, BorderLayout.WEST)
            add(Ui.buttonRow(cancel), BorderLayout.EAST)
        }
        contentPane.layout = BorderLayout()
        contentPane.add(body, BorderLayout.CENTER)
        contentPane.add(south, BorderLayout.SOUTH)
        Ui.dialogKeys(this, null) { if (running) cancelRun() else dispose() }
        pack()
        setLocationRelativeTo(owner)
    }

    fun run(): Path? {
        val profile = runCatching { env.profile() }.getOrElse {
            needTools(it.message ?: "The toolchain profile could not be loaded.")
            return null
        }
        val checks = Doctor.verify(profile)
        val required = checks.filter { it.status == CheckStatus.FAIL && it.name != "z3" }
        if (required.isNotEmpty()) {
            needTools(required.joinToString("\n") { "    ${it.name}: ${it.detail}" })
            return null
        }
        val z3 = checks.none { it.name == "z3" && it.status == CheckStatus.FAIL }
        val rea = profile.reaExecutable?.let { Files.isExecutable(it) } == true
        val options = FullRecoveryOptions(
            nameLibraries = request.names,
            roundTrip = request.verify,
            repair = request.repair,
            crossCheck = request.prove && z3,
            resolveLibraries = request.libraries,
            rea = rea,
            javaScript = rea,
        )
        running = true
        ticker.start()
        Thread({
            val result = runCatching {
                FullRecovery(profile, options, token) { line -> SwingUtilities.invokeLater { progress(line) } }.run(request.input, request.output)
            }
            SwingUtilities.invokeLater { finish(result) }
        }, "recovery").apply { isDaemon = true }.start()
        isVisible = true
        return output
    }

    internal fun progress(line: String) {
        log.append(line + "\n")
        if (log.lineCount > 5000) log.replaceRange("", 0, log.getLineEndOffset(1000))
        log.caretPosition = log.document.length
        detail.text = line
        val stage = Stage.of(line)
        if (stage != null && (current == null || stage.ordinal >= current!!.ordinal)) {
            current = stage
            step.text = "Step ${stage.ordinal + 1} of ${Stage.entries.size}: ${stage.title}"
            bar.value = stage.ordinal
        }
    }

    private fun finish(result: Result<FullRecoveryResult>) {
        running = false
        ticker.stop()
        val r = result.getOrNull()
        val error = result.exceptionOrNull()
        val ok = r?.outDir != null && r.exitCode in setOf(ExitCode.EXPORT_POLICY_SATISFIED, ExitCode.UNSUPPORTED_OR_PARTIAL)
        if (ok) {
            output = r!!.outDir
            dispose()
            return
        }
        if (error is CancelledException || r?.exitCode == ExitCode.CANCELLED) {
            dispose()
            JOptionPane.showMessageDialog(owner, "The recovery was cancelled. Nothing was written to ${request.output}.", MainFrame.APP_NAME, JOptionPane.INFORMATION_MESSAGE)
            return
        }
        title = "Recovery failed"
        step.text = "The app could not be recovered."
        step.foreground = Ui.bad
        detail.text = error?.let { it.message ?: it.toString() } ?: r?.message ?: "Unknown error."
        detail.toolTipText = detail.text
        error?.let { log.append("\n" + it.stackTraceToString()) }
        if (!logScroll.isVisible) toggleDetails()
        cancel.text = "Close"
        cancel.isEnabled = true
    }

    private fun cancelRun() {
        token.cancel()
        cancel.isEnabled = false
        step.text = "Cancelling..."
    }

    private fun toggleDetails() {
        logScroll.isVisible = !logScroll.isVisible
        details.text = if (logScroll.isVisible) "Fewer details" else "More details"
        pack()
    }

    private fun needTools(detail: String) {
        val answer = JOptionPane.showConfirmDialog(
            owner,
            "Some tools this program needs are not installed:\n\n$detail\n\nOpen the Tools window to install them?",
            MainFrame.APP_NAME, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
        )
        if (answer == JOptionPane.YES_OPTION) onNeedTools()
    }
}
