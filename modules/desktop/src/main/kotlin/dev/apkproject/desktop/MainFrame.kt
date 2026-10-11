package dev.apkproject.desktop

import dev.apkproject.orchestrator.ToolchainInstaller
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JToolBar
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import javax.swing.WindowConstants

/**
 * The main window, laid out like a Windows program: menu bar, toolbar, the open project (tree on
 * the left, details on the right) and a status bar. Without a project it shows a start page.
 */
class MainFrame(private val env: Environment, private val settings: Settings) : JFrame(APP_NAME) {
    private val center = JPanel(BorderLayout())
    val status = StatusBar()
    private var view: ProjectView? = null
    private val projectLoader = LatestTask("project-reader")
    private val recentMenu = JMenu("Recent Projects").apply { setMnemonic(KeyEvent.VK_J) }
    private val recentList = DefaultListModel<String>()

    private val ctrl = toolkit.menuShortcutKeyMaskEx
    val recoverAction = Ui.action("Recover APK...", KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), KeyEvent.VK_R) { recover(null) }
    val openAction = Ui.action("Open Project...", KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl or InputEvent.SHIFT_DOWN_MASK), KeyEvent.VK_O) { chooseProject() }
    private val showFolderAction = Ui.action("Show in Explorer", null, KeyEvent.VK_E) { view?.let { Ui.open(this, it.project.root) } }
    private val closeAction = Ui.action("Close Project", null, KeyEvent.VK_C) { closeProject() }
    private val refreshAction = Ui.action("Refresh", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), KeyEvent.VK_R) { view?.let { loadProject(it.project.root) } }
    val compareAction = Ui.action("Compare Versions...", KeyStroke.getKeyStroke(KeyEvent.VK_D, ctrl), KeyEvent.VK_C) { compare(null, null) }
    val toolsAction = Ui.action("Tools...", null, KeyEvent.VK_T) { ToolsDialog(this, env, settings).isVisible = true }
    private val exitAction = Ui.action("Exit", null, KeyEvent.VK_X) { exit() }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        iconImages = AppIcon.images()
        minimumSize = Dimension(760, 520)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = exit()
        })
        jMenuBar = menus()
        contentPane.layout = BorderLayout()
        contentPane.add(toolbar(), BorderLayout.NORTH)
        contentPane.add(center, BorderLayout.CENTER)
        contentPane.add(status, BorderLayout.SOUTH)
        transferHandler = FileDrop()
        (contentPane as JPanel).transferHandler = transferHandler
        restoreBounds()
        refreshRecent()
        showStart()
    }

    private fun menus(): JMenuBar {
        val bar = JMenuBar()
        bar.add(JMenu("File").apply {
            setMnemonic(KeyEvent.VK_F)
            add(JMenuItem(recoverAction))
            add(JMenuItem(openAction))
            add(recentMenu)
            addSeparator()
            add(JMenuItem(showFolderAction))
            add(JMenuItem(closeAction))
            addSeparator()
            add(JMenuItem(exitAction))
        })
        bar.add(JMenu("View").apply {
            setMnemonic(KeyEvent.VK_V)
            for ((k, page) in ProjectView.Page.entries.withIndex()) {
                // Ctrl+1 to Ctrl+9, then Ctrl+0 for the tenth page.
                val key = when { k < 9 -> KeyStroke.getKeyStroke(KeyEvent.VK_1 + k, ctrl); k == 9 -> KeyStroke.getKeyStroke(KeyEvent.VK_0, ctrl); else -> null }
                add(JMenuItem(Ui.action(page.title, key) { view?.select(page) }))
            }
            addSeparator()
            add(JMenuItem(refreshAction))
        })
        bar.add(JMenu("Tools").apply {
            setMnemonic(KeyEvent.VK_T)
            add(JMenuItem(compareAction))
            addSeparator()
            add(JMenuItem(toolsAction))
            add(JMenuItem(Ui.action("Recovery Jobs", null) { JobHistoryDialog(this@MainFrame, env) { show(Project(it)) }.isVisible = true }))
            add(JMenuItem(Ui.action("Verify Update Package...", null) { UpdatePackageDialog(this@MainFrame).isVisible = true }))
        })
        bar.add(JMenu("Help").apply {
            setMnemonic(KeyEvent.VK_H)
            add(JMenuItem(Ui.action("Evidence Levels", null, KeyEvent.VK_E) { HelpDialogs.evidence(this@MainFrame) }))
            add(JMenuItem(Ui.action("What Cannot Be Recovered", null, KeyEvent.VK_W) { HelpDialogs.limits(this@MainFrame) }))
            addSeparator()
            add(JMenuItem(Ui.action("About $APP_NAME", null, KeyEvent.VK_A) { HelpDialogs.about(this@MainFrame) }))
        })
        return bar
    }

    private fun toolbar(): JToolBar {
        val bar = JToolBar()
        bar.isFloatable = false
        bar.isRollover = true
        fun add(action: Action) = bar.add(action).apply { isFocusable = false; toolTipText = action.getValue(Action.NAME) as String; horizontalTextPosition = SwingConstants.CENTER }
        add(recoverAction)
        add(openAction)
        bar.addSeparator()
        add(compareAction)
        bar.addSeparator()
        add(showFolderAction)
        add(refreshAction)
        bar.addSeparator()
        add(toolsAction)
        return bar
    }

    private fun showStart() {
        projectLoader.cancel()
        view?.dispose()
        view = null
        title = APP_NAME
        center.removeAll()
        center.add(StartPage(), BorderLayout.CENTER)
        listOf(showFolderAction, closeAction, refreshAction).forEach { it.isEnabled = false }
        status.project(null)
        center.revalidate()
        center.repaint()
    }

    fun show(project: Project) {
        projectLoader.cancel()
        val v = ProjectView(project, env, settings, status)
        view?.dispose()
        view = v
        title = "${project.title} - $APP_NAME"
        center.removeAll()
        center.add(v, BorderLayout.CENTER)
        listOf(showFolderAction, closeAction, refreshAction).forEach { it.isEnabled = true }
        status.project(project)
        settings.addRecent(project.root.toString())
        refreshRecent()
        center.revalidate()
        center.repaint()
    }

    private fun loadProject(path: Path, readyMessage: String? = null) {
        closeAction.isEnabled = true
        status.message("Loading ${path.fileName}...")
        projectLoader.submit({ Project(path) }, { project ->
            show(project)
            readyMessage?.let { status.message(it) }
        }, { error ->
            closeAction.isEnabled = view != null
            status.message("Could not load ${path.fileName}")
            JOptionPane.showMessageDialog(this, "Could not open $path:\n${error.message}", APP_NAME, JOptionPane.ERROR_MESSAGE)
        })
    }

    fun select(page: ProjectView.Page) = view?.select(page)

    internal val projectView: ProjectView? get() = view
    internal val isLoading: Boolean get() = projectLoader.isBusy || view?.isLoadingFiles == true

    /** Opens what the program was started with or what was dropped on it. */
    fun open(path: Path) {
        when {
            Files.isDirectory(path) && Project.isProject(path) -> loadProject(path)
            Files.isDirectory(path) -> JOptionPane.showMessageDialog(this, "$path is not a recovered project.\nA project folder contains recovery\\report.json.", APP_NAME, JOptionPane.WARNING_MESSAGE)
            Files.isRegularFile(path) -> recover(path)
        }
    }

    private fun closeProject() {
        showStart()
    }

    private fun chooseProject() {
        val chooser = JFileChooser(settings.lastFolder)
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Open Project"
        chooser.approveButtonText = "Open"
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val dir = chooser.selectedFile.toPath()
        settings.lastFolder = dir.parent?.toString()
        open(dir)
    }

    fun recover(input: Path?) {
        val request = RecoverDialog(this, settings, input).ask() ?: return
        val result = RecoveryDialog(this, env, request) { ToolsDialog(this, env, settings).apply { startInstall() }.isVisible = true }.run()
        if (result != null) {
            loadProject(result, "Recovered ${request.input.fileName} in ${Ui.duration(System.currentTimeMillis() - request.started)}")
        }
    }

    /** Compares two builds and shows the result in its own window. */
    fun compare(old: Path?, new: Path?) {
        val (a, b) = CompareDialog(this, settings, old, new).ask() ?: return
        val started = System.currentTimeMillis()
        val result = CompareDialog.run(this, a, b) ?: return
        DiffFrame(result, settings).isVisible = true
        status.message("Compared ${a.fileName} with ${b.fileName} in ${Ui.duration(System.currentTimeMillis() - started)}")
    }

    /** At start: offer to install the tools if the required ones are missing. */
    fun checkTools() {
        Thread({
            val missing = env.installer().status().filter { it.state != ToolchainInstaller.Status.State.INSTALLED && !it.spec.optional }
            javax.swing.SwingUtilities.invokeLater {
                if (!isDisplayable || missing.isEmpty()) return@invokeLater
                val answer = JOptionPane.showConfirmDialog(
                    this,
                    "The decompiler and compilers this program uses are not installed:\n" + missing.joinToString("\n") { "    ${it.spec.title}" } +
                        "\n\nDownload and install them now? (about 140 MB, checked against pinned SHA-256 hashes)",
                    APP_NAME, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                )
                if (answer == JOptionPane.YES_OPTION) ToolsDialog(this, env, settings).apply { startInstall() }.isVisible = true
            }
        }, "startup-tool-check").apply { isDaemon = true }.start()
    }

    private fun refreshRecent() {
        val recent = settings.recentProjects.filter { Project.isProject(Paths.get(it)) }
        recentMenu.removeAll()
        recentList.clear()
        recent.forEachIndexed { k, path ->
            recentMenu.add(JMenuItem("${k + 1} $path").apply {
                if (k < 9) setMnemonic(KeyEvent.VK_1 + k)
                addActionListener { open(Paths.get(path)) }
            })
            recentList.addElement(path)
        }
        recentMenu.isEnabled = recent.isNotEmpty()
    }

    private fun exit() {
        projectLoader.close()
        view?.dispose()
        val b = bounds
        val maximized = extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        if (!maximized) settings.windowBounds = "${b.x},${b.y},${b.width},${b.height}" else settings.windowBounds = (settings.windowBounds?.substringBeforeLast(",max") ?: "${b.x},${b.y},1100,760") + ",max"
        dispose()
    }

    override fun dispose() {
        projectLoader.close()
        view?.dispose()
        super.dispose()
    }

    private fun restoreBounds() {
        val parts = settings.windowBounds?.split(',').orEmpty()
        val r = parts.take(4).mapNotNull { it.toIntOrNull() }
        val screen = graphicsConfiguration.bounds
        if (r.size == 4 && screen.intersects(Rectangle(r[0], r[1], r[2], r[3]))) {
            bounds = Rectangle(r[0], r[1], r[2], r[3])
            if (parts.getOrNull(4) == "max") extendedState = Frame.MAXIMIZED_BOTH
        } else {
            size = Dimension(minOf(1180, screen.width - 40), minOf(800, screen.height - 60))
            setLocationRelativeTo(null)
        }
    }

    /** What an empty window shows: the two ways to begin and the recent projects. */
    private inner class StartPage : JPanel(GridBagLayout()) {
        init {
            border = Ui.gap(24, 28, 24, 28)
            background = javax.swing.UIManager.getColor("TextField.background") ?: background
            var y = 0
            val heading = JLabel("Start").apply { font = Ui.font.deriveFont(Font.PLAIN, Ui.font.size * 1.6f) }
            add(heading, Ui.gbc(0, y++, insets = Insets(0, 0, 10, 0)))
            add(JLabel("Recover an Android app into an Android Studio project, open a project you recovered before, or compare two versions of an app."), Ui.gbc(0, y++, insets = Insets(0, 0, 12, 0)))
            val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(Ui.button("Recover APK...", KeyEvent.VK_R) { recover(null) })
                add(javax.swing.Box.createHorizontalStrut(6))
                add(Ui.button("Open Project...", KeyEvent.VK_O) { chooseProject() })
                add(javax.swing.Box.createHorizontalStrut(6))
                add(Ui.button("Compare Versions...", KeyEvent.VK_C) { compare(null, null) })
            }
            add(buttons, Ui.gbc(0, y++, insets = Insets(0, 0, 6, 0)))
            add(JLabel("You can also drag an APK file or a project folder onto this window, or two APK files to compare them.").apply { foreground = java.awt.Color.GRAY }, Ui.gbc(0, y++, insets = Insets(0, 0, 22, 0)))
            val recentLabel = JLabel("Recent projects")
            add(recentLabel, Ui.gbc(0, y++, insets = Insets(0, 0, 4, 0)))
            val list = JList(recentList)
            list.visibleRowCount = 8
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) list.selectedValue?.let { open(Paths.get(it)) }
                }
            })
            list.registerKeyboardAction({ list.selectedValue?.let { open(Paths.get(it)) } }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_FOCUSED)
            val scroll = JScrollPane(list).apply { preferredSize = Dimension(560, 160) }
            add(scroll, Ui.gbc(0, y++, fill = GridBagConstraints.NONE, insets = Insets(0, 0, 0, 0)))
            recentLabel.isVisible = !recentList.isEmpty
            scroll.isVisible = !recentList.isEmpty
            add(JPanel().apply { isOpaque = false }, Ui.gbc(0, y, weightx = 1.0, fill = GridBagConstraints.BOTH).apply { weighty = 1.0 })
        }
    }

    /** Dropping an APK starts a recovery, two APKs a comparison; dropping a project folder opens it. */
    private inner class FileDrop : TransferHandler() {
        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            @Suppress("UNCHECKED_CAST")
            val files = (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).map { it.toPath() }
            val file = files.firstOrNull() ?: return false
            if (files.size == 2 && files.all { Files.isRegularFile(it) }) {
                // Older first: by modification time, the usual order of downloads.
                val (a, b) = files.sortedBy { Files.getLastModifiedTime(it) }
                javax.swing.SwingUtilities.invokeLater { compare(a, b) }
            } else javax.swing.SwingUtilities.invokeLater { open(file) }
            return true
        }
    }

    companion object {
        const val APP_NAME = "APK Project Studio"
        val VERSION = MainFrame::class.java.getResourceAsStream("/release-version.txt")?.use { String(it.readAllBytes()).trim() } ?: "0.3.0"
    }
}

/** The bottom line of the window: a message, then the project's two headline counts. */
class StatusBar : JPanel(BorderLayout()) {
    private val text = JLabel("Ready")
    private val files = JLabel()
    private val methods = JLabel()

    init {
        border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color(0xD0D0D0)), Ui.gap(3, 6, 3, 6))
        add(text, BorderLayout.CENTER)
        val right = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0))
        right.add(files)
        right.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 16) })
        right.add(methods)
        add(right, BorderLayout.EAST)
        project(null)
    }

    fun message(s: String) { text.text = s.ifEmpty { "Ready" } }

    fun project(p: Project?) {
        val r = p?.report
        files.text = r?.compileCheck?.let { "Files compiling: ${Ui.count(it.files - it.filesWithErrors)} of ${Ui.count(it.files)}" } ?: ""
        methods.text = r?.roundTrip?.takeIf { it.methods > 0 }?.let { "Methods matching bytecode: ${Ui.count(it.equivalent)} of ${Ui.count(it.methods)}" } ?: ""
        files.parent?.isVisible = r != null
        text.text = "Ready"
    }
}
