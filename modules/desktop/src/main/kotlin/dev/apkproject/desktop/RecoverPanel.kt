package dev.apkproject.desktop

import dev.apkproject.app.FullRecovery
import dev.apkproject.app.FullRecoveryOptions
import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExitCode
import dev.apkproject.orchestrator.Doctor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

class RecoverPanel(
    private val env: Environment,
    private val settings: Settings,
    private val onDone: (Project) -> Unit,
    private val onNeedTools: () -> Unit,
) : JPanel(BorderLayout()) {
    private var input: Path? = null
    private var token: CancellationToken? = null
    private var started = 0L

    private val inputCards = CardLayout()
    private val inputHolder = JPanel(inputCards).apply { isOpaque = false }
    private val fileName = JLabel().apply { font = Theme.font(16f, true); foreground = Theme.text }
    private val fileFacts = Ui.muted("")
    private val output = JTextField(40)

    private class Option(val key: String, title: String, detail: String, default: Boolean, settings: Settings) {
        val box = JCheckBox(title, settings.option(key, default)).apply { font = Theme.font(14f, true); isOpaque = false; addActionListener { settings.setOption(key, isSelected) } }
        val panel: JComponent = Ui.column(box, Ui.wrapped(detail, 600).apply { border = BorderFactory.createEmptyBorder(0, 28, 0, 0) }, gap = 1)
    }

    private val names = Option("names", "Recover original names", "Give R8-renamed classes their library names, and take back names the compilers left in the bytecode (data classes, view bindings, Room tables, enums).", true, settings)
    private val verify = Option("verify", "Verify every method", "Recompile the recovered source and compare each method with the original bytecode. Slower, and the reason you can trust the result.", true, settings)
    private val repair = Option("repair", "Repair decompiler mistakes", "Try known fixes on methods that differ; a fix is kept only when recompiling proves it.", true, settings)
    private val prove = Option("prove", "Prove simple methods", "Check small arithmetic methods with the Z3 solver for every possible input.", true, settings)
    private val libraries = Option("libraries", "Use published libraries", "Download the libraries the app declares from Maven and replace matching decompiled copies with real dependencies.", true, settings)

    private val startButton = Ui.primary("Recover") { start() }
    private val cancelButton: JButton = Ui.secondary("Cancel") { cancelRun() }

    private val progress = Card(BorderLayout(), 20)
    private val stageRows = Stage.entries.associateWith { StageRow(it) }
    private val elapsed = Ui.muted("")
    private val outcome = JPanel(BorderLayout()).apply { isOpaque = false }
    private val log = JTextArea(10, 80).apply { isEditable = false; font = Theme.mono; lineWrap = false }
    private val logScroll = JScrollPane(log).apply { isVisible = false; preferredSize = Dimension(700, 220) }
    private var phase = 0
    private val ticker = Timer(40) {
        phase = (phase + 12) % 360
        stageRows.values.filter { it.state == StepState.RUNNING }.forEach { it.icon.repaint() }
        if (started > 0) elapsed.text = "Elapsed " + Ui.duration(System.currentTimeMillis() - started)
    }

    init {
        isOpaque = false
        val content = JPanel(GridBagLayout()).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(28, 36, 36, 36) }
        val c = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST }
        var y = 0
        fun add(component: JComponent, top: Int) = content.add(component, c.apply { gridy = y++; insets = Insets(top, 0, 0, 0) })

        add(Ui.title("Recover an app"), 0)
        add(Ui.wrapped("Get an Android app's screens, code, original names and the server API it talks to, as an Android Studio project. Every method is checked against the app's own bytecode.", 760, size = 14f), 6)

        val drop = DropZone({ choose(it) }, { browse() })
        val chosen = Card(BorderLayout(14, 0)).apply {
            add(JLabel(Icons.file(36) { Theme.accent }), BorderLayout.WEST)
            add(Ui.column(fileName, fileFacts, gap = 2), BorderLayout.CENTER)
            add(Ui.link("Choose another…") { browse() }, BorderLayout.EAST)
        }
        inputHolder.add(drop, "drop")
        inputHolder.add(chosen, "chosen")
        add(inputHolder, 22)

        val outputRow = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            add(Ui.heading("Save the project to"), BorderLayout.WEST)
            add(output, BorderLayout.CENTER)
            add(Ui.secondary("Change…") { chooseOutput() }, BorderLayout.EAST)
        }
        add(outputRow, 18)

        val options = Card(GridBagLayout()).apply {
            val oc = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.WEST }
            add(Ui.heading("What to recover"), oc.apply { gridy = 0; insets = Insets(0, 0, 8, 0) })
            listOf(names, verify, repair, prove, libraries).forEachIndexed { k, o -> add(o.panel, oc.apply { gridy = k + 1; insets = Insets(8, 0, 0, 0) }) }
        }
        verify.box.addActionListener { repair.box.isEnabled = verify.box.isSelected }
        repair.box.isEnabled = verify.box.isSelected
        add(options, 18)

        cancelButton.isVisible = false
        add(Ui.row(startButton, cancelButton, Ui.muted("A large app takes about ten minutes; small ones a minute or two."), gap = 12), 20)

        val stages = JPanel(GridBagLayout()).apply { isOpaque = false }
        val sc = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL }
        stageRows.values.forEachIndexed { k, row -> stages.add(row, sc.apply { gridy = k; insets = Insets(if (k == 0) 0 else 6, 0, 0, 0) }) }
        val toggle = Ui.link("Show details") {}
        toggle.addActionListener {
            logScroll.isVisible = !logScroll.isVisible
            toggle.text = if (logScroll.isVisible) "Hide details" else "Show details"
            progress.revalidate()
        }
        progress.add(JPanel(BorderLayout()).apply { isOpaque = false; add(Ui.heading("Progress"), BorderLayout.WEST); add(elapsed, BorderLayout.EAST) }, BorderLayout.NORTH)
        progress.add(Ui.column(Box.createVerticalStrut(6) as JComponent, stages, outcome, toggle, logScroll, gap = 10), BorderLayout.CENTER)
        progress.isVisible = false
        add(progress, 22)
        content.add(Box.createVerticalGlue(), c.apply { gridy = y; weighty = 1.0 })

        val centered = JPanel(GridBagLayout()).apply { isOpaque = false }
        centered.add(content, GridBagConstraints().apply { weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH; anchor = GridBagConstraints.NORTH })
        content.maximumSize = Dimension(980, Int.MAX_VALUE)
        add(JScrollPane(centered).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBar.unitIncrement = 18
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        inputCards.show(inputHolder, "drop")
    }

    fun choose(file: Path) {
        if (token != null) return
        input = file
        fileName.text = file.fileName.toString()
        fileFacts.text = "${Ui.human(Files.size(file))} · ${file.parent}"
        inputCards.show(inputHolder, "chosen")
        output.text = unusedFolder(file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + "-recovered")).toString()
        settings.lastFolder = file.parent.toString()
    }

    private fun browse() {
        val dialog = FileDialog(SwingUtilities.getWindowAncestor(this) as? Frame, "Choose an Android app", FileDialog.LOAD)
        settings.lastFolder?.let { dialog.directory = it }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("apk", "apks", "xapk", "apkm", "aab", "dex") }
        if (dev.apkproject.contracts.isWindows()) dialog.file = "*.apk;*.apks;*.xapk;*.apkm;*.aab;*.dex"
        dialog.isVisible = true
        val name = dialog.file ?: return
        choose(Paths.get(dialog.directory, name))
    }

    private fun chooseOutput() {
        val chooser = JFileChooser(output.text.takeIf { it.isNotBlank() }?.let { Paths.get(it).parent?.toFile() })
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Where should the project go?"
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) output.text = chooser.selectedFile.toPath().resolve(
            (input?.fileName?.toString()?.substringBeforeLast('.') ?: "app") + "-recovered",
        ).let(::unusedFolder).toString()
    }

    private fun unusedFolder(base: Path): Path {
        if (!Files.exists(base) || Files.list(base).use { !it.findAny().isPresent }) return base
        var k = 2
        while (true) {
            val p = base.resolveSibling("${base.fileName}-$k")
            if (!Files.exists(p)) return p
            k++
        }
    }

    private fun start() {
        val file = input
        if (file == null || !Files.isRegularFile(file)) {
            JOptionPane.showMessageDialog(this, "Choose an Android app first (drop it on the box, or use Choose a file…).", "No app chosen", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val profile = runCatching { env.profile() }.getOrElse {
            missingTools(it.message ?: "The toolchain profile could not be loaded.")
            return
        }
        val checks = Doctor.verify(profile)
        val required = checks.filter { it.status == CheckStatus.FAIL && it.name != "z3" }
        if (required.isNotEmpty()) {
            missingTools(required.joinToString("\n") { "• ${it.name}: ${it.detail}" })
            return
        }
        val z3Ready = checks.none { it.name == "z3" && it.status == CheckStatus.FAIL }
        val out = Paths.get(output.text.trim().ifEmpty { unusedFolder(file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + "-recovered")).toString() })
        val options = FullRecoveryOptions(
            nameLibraries = names.box.isSelected,
            roundTrip = verify.box.isSelected,
            repair = verify.box.isSelected && repair.box.isSelected,
            crossCheck = prove.box.isSelected && z3Ready,
            resolveLibraries = libraries.box.isSelected,
            rea = profile.reaExecutable?.let { Files.isExecutable(it) } == true,
            javaScript = profile.reaExecutable?.let { Files.isExecutable(it) } == true,
        )
        val skipped = buildSet {
            if (!options.nameLibraries) add(Stage.NAMES)
            if (!options.crossCheck) add(Stage.PROVE)
            if (!options.roundTrip) { add(Stage.VERIFY); add(Stage.REPAIR) }
            if (!options.repair) add(Stage.REPAIR)
        }
        stageRows.forEach { (stage, row) -> row.set(if (stage in skipped) StepState.SKIPPED else StepState.PENDING, if (stage in skipped) "skipped" else "") }
        log.text = ""
        outcome.removeAll()
        progress.isVisible = true
        startButton.isVisible = false
        cancelButton.isVisible = true
        cancelButton.isEnabled = true
        cancelButton.text = "Cancel"
        val cancel = CancellationToken()
        token = cancel
        started = System.currentTimeMillis()
        ticker.start()
        var current: Stage? = null
        fun advance(line: String) {
            val stage = Stage.of(line)
            if (stage != null && (current == null || stage.ordinal >= current!!.ordinal)) {
                for (s in Stage.entries) {
                    if (s.ordinal >= stage.ordinal) break
                    val row = stageRows.getValue(s)
                    if (row.state == StepState.PENDING || row.state == StepState.RUNNING) row.set(if (row.state == StepState.RUNNING) StepState.DONE else StepState.SKIPPED)
                }
                current = stage
                stageRows.getValue(stage).set(StepState.RUNNING, line)
            } else {
                current?.let { stageRows.getValue(it).detail(line) }
            }
        }
        Thread({
            val result = runCatching {
                FullRecovery(profile, options, cancel) { line -> SwingUtilities.invokeLater { appendLog(line); advance(line) } }.run(file, out)
            }
            SwingUtilities.invokeLater { finish(result, out) }
        }, "recovery").apply { isDaemon = true }.start()
    }

    private fun appendLog(line: String) {
        log.append(line + "\n")
        if (log.lineCount > 4000) log.replaceRange("", 0, log.getLineEndOffset(500))
        log.caretPosition = log.document.length
    }

    private fun finish(result: Result<dev.apkproject.app.FullRecoveryResult>, out: Path) {
        ticker.stop()
        token = null
        startButton.isVisible = true
        cancelButton.isVisible = false
        val took = Ui.duration(System.currentTimeMillis() - started)
        started = 0
        elapsed.text = "Took $took"
        val r = result.getOrNull()
        val error = result.exceptionOrNull()
        val succeeded = r != null && r.outDir != null && r.exitCode in setOf(ExitCode.EXPORT_POLICY_SATISFIED, ExitCode.UNSUPPORTED_OR_PARTIAL)
        for (row in stageRows.values) {
            if (row.state == StepState.RUNNING) row.set(if (succeeded) StepState.DONE else StepState.FAILED)
            else if (row.state == StepState.PENDING) row.set(if (succeeded) StepState.DONE else StepState.SKIPPED)
        }
        outcome.removeAll()
        if (succeeded) {
            stageRows.getValue(Stage.WRITE).set(StepState.DONE, out.toString())
            val banner = Card(BorderLayout(14, 0), 14) { Theme.goodSoft }
            banner.add(JLabel(Icons.check(28)), BorderLayout.WEST)
            banner.add(Ui.column(Ui.heading("Recovered"), Ui.wrapped(r!!.message, 520, Theme.text)), BorderLayout.CENTER)
            banner.add(Ui.primary("View results") { onDone(Project(out)) }, BorderLayout.EAST)
            outcome.add(banner)
            onDone(Project(out))
        } else {
            val message = when {
                error is dev.apkproject.compiler.CancelledException || r?.exitCode == ExitCode.CANCELLED -> "Cancelled. Nothing was written to $out."
                error != null -> "The recovery stopped with an error: ${error.message ?: error}"
                else -> r!!.message
            }
            val banner = Card(BorderLayout(14, 0), 14) { Theme.badSoft }
            banner.add(JLabel(Icons.cross(28)), BorderLayout.WEST)
            banner.add(Ui.column(Ui.heading("Not recovered"), Ui.wrapped(message, 640, Theme.text)), BorderLayout.CENTER)
            outcome.add(banner)
            logScroll.isVisible = true
        }
        progress.revalidate()
        progress.repaint()
    }

    private fun cancelRun() {
        token?.cancel()
        cancelButton.isEnabled = false
        cancelButton.text = "Cancelling…"
    }

    private fun missingTools(detail: String) {
        val choice = JOptionPane.showOptionDialog(
            this, "Some tools this app drives are not installed yet:\n\n$detail\n\nInstall them now? (about 140 MB, downloaded once and checked against pinned hashes)",
            "Tools needed", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, arrayOf("Open Tools", "Not now"), "Open Tools",
        )
        if (choice == 0) onNeedTools()
    }

    /** One line of the progress checklist. */
    private inner class StageRow(stage: Stage) : JPanel(BorderLayout(12, 0)) {
        var state = StepState.PENDING
            private set
        val icon = JLabel(Icons.pending(20))
        private val title = JLabel(stage.title).apply { font = Theme.font(14f) }
        private val detail = Ui.muted("", 12.5f)

        init {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
            add(detail, BorderLayout.EAST)
            detail.preferredSize = Dimension(420, 18)
            set(StepState.PENDING)
        }

        fun set(state: StepState, text: String? = null) {
            this.state = state
            icon.icon = when (state) {
                StepState.PENDING -> Icons.pending(20)
                StepState.RUNNING -> Icons.spinner(20) { phase }
                StepState.DONE -> Icons.check(20)
                StepState.SKIPPED -> Icons.skipped(20)
                StepState.FAILED -> Icons.cross(20)
            }
            title.foreground = if (state == StepState.PENDING || state == StepState.SKIPPED) Theme.muted else Theme.text
            title.font = Theme.font(14f, state == StepState.RUNNING)
            text?.let { detail(it) }
        }

        fun detail(text: String) {
            detail.text = if (text.length > 70) text.take(69) + "…" else text
            detail.toolTipText = text
        }
    }
}

private enum class StepState { PENDING, RUNNING, DONE, SKIPPED, FAILED }
