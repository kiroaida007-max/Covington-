package dev.apkproject.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

class MainWindow(private val env: Environment, settings: Settings) : JFrame("APK Project Studio") {
    private val pages = JTabbedPane().apply { name = "workspaceTabs" }
    private val status = JLabel("Ready")
    private val toolStatus = JLabel("Checking tools…")
    private val activity = JProgressBar().apply { isIndeterminate = true; isVisible = false; preferredSize = Dimension(120, 16) }
    val results: ResultsPanel = ResultsPanel(env) { project ->
        title = "${project?.title?.let { "$it — " }.orEmpty()}APK Project Studio"
        closeProject.isEnabled = project != null
        status.text = project?.root?.toString() ?: "Ready"
        if (project != null) select("results")
    }
    private val tools: ToolsPanel = ToolsPanel(env, settings) { SwingUtilities.invokeLater { toolStatus.text = if (tools.ready()) "Tools: ready" else "Tools: setup needed" } }
    private val recover: RecoverPanel = RecoverPanel(env, settings,
        { project -> results.show(project) }, { select("tools") },
        { message, busy -> status.text = message; activity.isVisible = busy; updateActions() },
    )
    private val openApp: Action = command("Open app…", KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK) { select("recover"); recover.browse() }
    private val openProject: Action = command("Open project…", KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK) { results.openFolder() }
    private val closeProject: Action = command("Close project", KeyEvent.VK_W, KeyEvent.CTRL_DOWN_MASK) { results.closeProject() }
    private val run: Action = command("Recover", KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK) { select("recover"); recover.start() }
    private val cancel: Action = command("Cancel recovery", KeyEvent.VK_ESCAPE, 0) { recover.cancelRun() }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        iconImages = listOf(16, 20, 24, 32, 48, 64, 128, 256).map { Theme.appImage(it) }
        val screen = graphicsConfiguration.bounds
        minimumSize = Dimension(minOf(800, screen.width), minOf(580, screen.height))
        size = Dimension(minOf(1180, screen.width - 40), minOf(800, screen.height - 60))
        setLocationRelativeTo(null)
        jMenuBar = menus()
        val toolbar = JToolBar().apply {
            isFloatable = false
            add(openApp); add(openProject); addSeparator(); add(run); add(cancel)
            addSeparator()
            add(command("Tools", KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK) { select("tools") })
            components.filterIsInstance<JButton>().forEach { it.isFocusable = false; it.toolTipText = it.text }
        }
        pages.addTab("Recovery", recover)
        pages.addTab("Project", results)
        pages.addTab("Tools", tools)
        listOf('R', 'P', 'T').forEachIndexed { i, key -> pages.setMnemonicAt(i, key.code) }
        val statusBar = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(3, 6, 3, 6))
            add(status, BorderLayout.CENTER)
            add(Ui.row(activity, toolStatus), BorderLayout.EAST)
        }
        contentPane = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(pages, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }
        rootPane.defaultButton = recover.defaultButton
        pages.addChangeListener { rootPane.defaultButton = if (pages.selectedIndex == 0) recover.defaultButton else null }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if ((recover.running || tools.installing) && JOptionPane.showConfirmDialog(this@MainWindow,
                    "Work is still running. Cancel it and exit?", "Exit APK Project Studio",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return
                dispose()
            }
        })
        select("recover")
        closeProject.isEnabled = false
        updateActions()
    }

    private fun command(label: String, key: Int, modifiers: Int, body: () -> Unit): Action = object : AbstractAction(label) {
        init { putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(key, modifiers)) }
        override fun actionPerformed(event: ActionEvent) = body()
    }

    private fun menus() = JMenuBar().apply {
        add(JMenu("File").apply {
            mnemonic = 'F'.code
            add(openApp); add(openProject); add(closeProject); addSeparator()
            add(command("Exit", KeyEvent.VK_F4, KeyEvent.ALT_DOWN_MASK) { dispatchEvent(WindowEvent(this@MainWindow, WindowEvent.WINDOW_CLOSING)) })
        })
        add(JMenu("Recovery").apply { mnemonic = 'R'.code; add(run); add(cancel) })
        add(JMenu("View").apply {
            mnemonic = 'V'.code
            listOf("Recovery" to "recover", "Project" to "results", "Tools" to "tools").forEachIndexed { i, (label, key) ->
                add(command(label, KeyEvent.VK_1 + i, KeyEvent.CTRL_DOWN_MASK) { select(key) })
            }
        })
        add(JMenu("Help").apply {
            mnemonic = 'H'.code
            add(JMenuItem("Evidence levels…").apply { addActionListener { evidenceDialog() } })
            add(JMenuItem("About APK Project Studio…").apply { addActionListener { aboutDialog() } })
        })
    }

    private fun updateActions() {
        openApp.isEnabled = !recover.running
        run.isEnabled = recover.hasInput && !recover.running
        cancel.isEnabled = recover.running && !recover.cancelling
    }

    fun select(key: String) {
        pages.selectedIndex = when (key) { "results" -> 1; "tools" -> 2; else -> 0 }
        if (key == "tools") tools.refresh()
    }

    fun open(path: Path) {
        when {
            Files.isDirectory(path) && Project.isProject(path) -> results.show(Project(path))
            Files.isRegularFile(path) -> { recover.choose(path.toAbsolutePath()); select("recover"); updateActions() }
            else -> JOptionPane.showMessageDialog(this, "Cannot open $path", "Open", JOptionPane.WARNING_MESSAGE)
        }
    }

    fun aboutDialog() = JOptionPane.showMessageDialog(this,
        "APK Project Studio $VERSION\n\nRecover Android source, original names and API contracts.\nVerification and evidence are recorded in each project.\n\nJADX: Apache 2.0 · ECJ: EPL 2.0 · D8/R8: BSD 3-Clause\nZ3: MIT · RSyntaxTextArea: BSD · Android SDK: SDK license",
        "About APK Project Studio", JOptionPane.INFORMATION_MESSAGE, ImageIcon(Theme.appImage(32)))

    private fun evidenceDialog() = JOptionPane.showMessageDialog(this,
        "PROVED — Equivalent within the supported Z3 model.\nROUND-TRIP EQUIVALENT — Recompiled method matches original dataflow.\nREPAIRED — A decompiler fix passed round-trip verification.\nVERIFIED IDENTITY — Library matched its published artifact.\nOBSERVED — Read from the app or compiler-written evidence.\nINFERRED — Derived from code or library structure.\nSYNTHESIZED — Generated scaffolding, including the mock server.\n\nErased names, comments and the server's original source are not recoverable.\nKotlin code is decompiled to Java.",
        "Evidence levels", JOptionPane.INFORMATION_MESSAGE)

    override fun dispose() { recover.cancelRun(); tools.cancelInstall(); results.shutdown(); super.dispose() }
    companion object { const val VERSION = "0.2.0" }
}
