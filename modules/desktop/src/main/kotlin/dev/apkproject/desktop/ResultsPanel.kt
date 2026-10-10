package dev.apkproject.desktop

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme as EditorTheme
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.RowFilter
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ResultsPanel(private val env: Environment, private val onProject: (Project?) -> Unit = {}) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val body = JPanel(cards).apply { isOpaque = false }
    private var mock: MockServer? = null
    private var projectGeneration = 0
    private var projectView: JComponent? = null
    private var projectTabs: JTabbedPane? = null
    var project: Project? = null
        private set

    init {
        isOpaque = false
        add(body, BorderLayout.CENTER)
        body.add(empty(), "empty")
        cards.show(body, "empty")
        Runtime.getRuntime().addShutdownHook(Thread { mock?.stop() })
    }

    private fun empty(): JComponent {
        val p = JPanel(GridBagLayout()).apply { isOpaque = false }
        val c = GridBagConstraints().apply { gridx = 0 }
        p.add(JLabel(javax.swing.UIManager.getIcon("FileView.directoryIcon")), c.apply { gridy = 0; insets = Insets(0, 0, 12, 0) })
        p.add(Ui.heading("No project open"), c.apply { gridy = 1 })
        p.add(Ui.muted("Recover an app, or open a project you recovered before."), c.apply { gridy = 2; insets = Insets(4, 0, 14, 0) })
        p.add(Ui.secondary("Open a recovered project…") { openFolder() }, c.apply { gridy = 3 })
        return p
    }

    fun openFolder() {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Open a recovered project"
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val dir = chooser.selectedFile.toPath()
        if (!Project.isProject(dir)) {
            JOptionPane.showMessageDialog(this, "$dir is not a recovered project (it has no recovery/report.json).", "Not a project", JOptionPane.WARNING_MESSAGE)
            return
        }
        show(Project(dir))
    }

    fun show(project: Project) {
        projectView?.let { body.remove(it) }
        this.project = project
        shutdown()
        mock = null
        val view = JPanel(BorderLayout(0, 6)).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
        view.add(header(project), BorderLayout.NORTH)
        val tabs = JTabbedPane()
        tabs.name = "projectTabs"
        projectTabs = tabs
        tabs.addTab("Overview", overview(project))
        tabs.addTab("Source", source(project))
        tabs.addTab("Backend", backend(project))
        tabs.addTab("Names", names(project))
        tabs.addTab("Report", report(project))
        view.add(tabs, BorderLayout.CENTER)
        projectView = view
        body.add(view, "project")
        cards.show(body, "project")
        revalidate()
        onProject(project)
    }

    private fun header(p: Project): JComponent {
        val head = JPanel(BorderLayout(16, 0)).apply { isOpaque = false }
        head.add(Ui.column(Ui.title(p.title), Ui.muted(p.subtitle), gap = 4), BorderLayout.CENTER)
        head.add(Ui.row(Ui.secondary("Open folder") { open(p.root) }, Ui.secondary("Open another…") { openFolder() }), BorderLayout.EAST)
        return head
    }

    private fun overview(p: Project): JComponent {
        val r = p.report
        val rows = r?.let {
            val c = it.compileCheck
            val rt = it.roundTrip
            val e = it.evidenceNames
            listOf(
                listOf("Java files compiling", if (c.status == "NOT_RUN") "Not run" else "${Ui.count(c.files - c.filesWithErrors)} / ${Ui.count(c.files)}", if (c.status == "NOT_RUN") c.note else "App files: ${Ui.count(c.appFiles - c.appFilesWithErrors)} / ${Ui.count(c.appFiles)}"),
                listOf("Equivalent methods", if (rt.status == "NOT_RUN") "Not run" else "${Ui.count(rt.equivalent)} / ${Ui.count(rt.methods)}", if (rt.status == "NOT_RUN") rt.note else "ROUND-TRIP EQUIVALENT · app: ${Ui.count(rt.appEquivalent)} / ${Ui.count(rt.appMethods)}"),
                listOf("Proved methods", it.crossCheck?.let { x -> "${x.proved} / ${x.eligible}" } ?: "Not run", "PROVED · supported arithmetic methods only"),
                listOf("Compiler-evidence names", if (e.size == 3) Ui.count(e.sum()) else "Not recorded", if (e.size == 3) "OBSERVED · ${e[0]} classes, ${e[1]} fields, ${e[2]} methods" else "OBSERVED"),
                listOf("Library classes named", Ui.count(it.libraryClassesNamed), "INFERRED · structural matching"),
                listOf("Backend endpoints", Ui.count(it.backend.endpoints), "INFERRED · ${it.backend.hosts} hosts, ${it.backend.models} JSON models"),
                listOf("Verified library dependencies", Ui.count(it.libraries.count { l -> l.status == "VERIFIED" }), "VERIFIED IDENTITY · ${it.librarySourcesRemoved} decompiled files replaced"),
            )
        } ?: listOf(listOf("Recovery report", "Unavailable", "No readable recovery/report.json"))
        val summary = ReportTable(object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = listOf("Measure", "Result", "Evidence / details")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        }).apply {
            name = "summaryTable"
            fillsViewportHeight = true
            columnModel.getColumn(0).preferredWidth = 240
            columnModel.getColumn(1).preferredWidth = 150
            columnModel.getColumn(2).preferredWidth = 660
        }
        val layers = GroupBox(BorderLayout(0, 10))
        layers.add(Ui.heading("What is in the project, and how far to trust it"), BorderLayout.NORTH)
        val layerRows = r?.layers.orEmpty()
        val table = ReportTable(object : AbstractTableModel() {
            override fun getRowCount() = layerRows.size
            override fun getColumnCount() = 4
            override fun getColumnName(column: Int) = listOf("Layer", "Where", "Evidence", "Summary")[column]
            override fun getValueAt(row: Int, column: Int): Any = layerRows[row].let { listOf(it.layer, it.location, it.evidence, it.summary)[column] }
        })
        table.columnModel.getColumn(0).preferredWidth = 150
        table.columnModel.getColumn(1).preferredWidth = 220
        table.columnModel.getColumn(2).preferredWidth = 220
        table.columnModel.getColumn(3).preferredWidth = 520
        layers.add(JScrollPane(table).apply { preferredSize = Dimension(900, 230) }, BorderLayout.CENTER)
        val panel = JPanel(BorderLayout(0, 6)).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(16, 2, 2, 2) }
        panel.add(GroupBox(BorderLayout(), title = "Verification summary").apply { add(JScrollPane(summary).apply { preferredSize = Dimension(900, 190) }) }, BorderLayout.NORTH)
        panel.add(layers, BorderLayout.CENTER)
        return JScrollPane(panel).apply { border = null; isOpaque = false; viewport.isOpaque = false }
    }

    private class FileNode(val path: String, val verdict: FileVerdict?) {
        override fun toString() = path.substringAfterLast('/')
    }

    private fun source(p: Project): JComponent {
        val files: List<String> = if (Files.isDirectory(p.javaRoot)) Files.walk(p.javaRoot).use { s ->
            s.filter { it.toString().endsWith(".java") }.map { p.javaRoot.relativize(it).toString().replace('\\', '/') }.sorted().toList()
        } else emptyList()
        val filter = JTextField().apply { name = "sourceFilter" }
        val kinds = JComboBox(arrayOf("All files", "All methods verified", "Partly verified", "Does not compile", "Not compared"))
        val fileCount = Ui.muted("")
        val tree = JTree(DefaultMutableTreeNode())
        tree.name = "sourceTree"
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val o = (value as? DefaultMutableTreeNode)?.userObject
                icon = when {
                    o is FileNode -> when (o.verdict?.kind) {
                        FileVerdict.Kind.VERIFIED -> Icons.check(14)
                        FileVerdict.Kind.PARTIAL -> Icons.warning(14)
                        FileVerdict.Kind.NOT_COMPILING -> Icons.cross(14)
                        else -> Icons.dot(14) { Theme.border }
                    }
                    else -> javax.swing.UIManager.getIcon(if (expanded) "Tree.openIcon" else "Tree.closedIcon")
                }
                return this
            }
        }
        fun rebuild() {
            val text = filter.text.trim().lowercase()
            val want = kinds.selectedIndex
            val root = DefaultMutableTreeNode()
            val packages = LinkedHashMap<String, DefaultMutableTreeNode>()
            var shown = 0
            for (f in files) {
                if (text.isNotEmpty() && !f.lowercase().contains(text)) continue
                val v = p.verdicts[f]
                val ok = when (want) {
                    1 -> v?.kind == FileVerdict.Kind.VERIFIED
                    2 -> v?.kind == FileVerdict.Kind.PARTIAL
                    3 -> v?.kind == FileVerdict.Kind.NOT_COMPILING
                    4 -> v == null || v.kind == FileVerdict.Kind.UNCHECKED
                    else -> true
                }
                if (!ok) continue
                val pkg = f.substringBeforeLast('/', "(default package)").replace('/', '.')
                packages.getOrPut(pkg) { DefaultMutableTreeNode(pkg).also { root.add(it) } }.add(DefaultMutableTreeNode(FileNode(f, v)))
                shown++
            }
            fileCount.text = "${Ui.count(shown)} of ${Ui.count(files.size)} files"
            tree.model = DefaultTreeModel(root)
            if (packages.size <= 3 || text.isNotEmpty()) for (r in 0 until minOf(tree.rowCount, 400)) tree.expandRow(r)
        }
        rebuild()
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        kinds.addActionListener { rebuild() }

        val editor = RSyntaxTextArea().apply {
            name = "sourceEditor"
            syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_JAVA
            isEditable = false
            antiAliasingEnabled = true
            isCodeFoldingEnabled = true
            runCatching {
                EditorTheme.load(javaClass.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/idea.xml")).apply(this)
            }
            font = Theme.mono
            text = "// Choose a file on the left.\n// Green: every method matches the original bytecode.\n// Amber: some methods differ, and are listed in recovery/round-trip.json.\n// Red: the file does not compile as recovered."
        }
        val fileTitle = JLabel("Select a source file").apply { font = Theme.font(bold = true) }
        val verdict = JPanel(BorderLayout()).apply { isOpaque = false }
        tree.addTreeSelectionListener { e ->
            val node = (e.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode ?: return@addTreeSelectionListener
            val file = p.javaRoot.resolve(node.path)
            editor.text = runCatching { Files.readString(file) }.getOrElse { "// cannot read $file: ${it.message}" }
            editor.caretPosition = 0
            fileTitle.text = node.path
            fileTitle.toolTipText = node.path
            verdict.removeAll()
            val v = node.verdict
            if (v != null) {
                val chip = when (v.kind) {
                    FileVerdict.Kind.VERIFIED -> StatusLabel.good(v.describe())
                    FileVerdict.Kind.PARTIAL -> StatusLabel.warn(v.describe())
                    FileVerdict.Kind.NOT_COMPILING -> StatusLabel.bad(v.describe())
                    FileVerdict.Kind.UNCHECKED -> StatusLabel.info(v.describe())
                }
                chip.toolTipText = v.firstErrors.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { Ui.escape(it) }
                verdict.add(chip, BorderLayout.WEST)
            }
            verdict.revalidate()
            verdict.repaint()
        }
        val left = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 0, 0, 8)
            isOpaque = false
            add(Ui.column(Ui.filterLabel(filter), filter, kinds, fileCount, gap = 6), BorderLayout.NORTH)
            add(JScrollPane(tree), BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 8, 0, 0)
            isOpaque = false
            add(Ui.column(fileTitle, verdict, gap = 3), BorderLayout.NORTH)
            add(RTextScrollPane(editor), BorderLayout.CENTER)
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { dividerLocation = 340; border = null; isOpaque = false }
    }

    private fun backend(p: Project): JComponent {
        val c = p.contract
        val endpoints = c?.endpoints.orEmpty()
        val model = object : AbstractTableModel() {
            override fun getRowCount() = endpoints.size
            override fun getColumnCount() = 4
            override fun getColumnName(column: Int) = listOf("Method", "Path", "Operation", "Response")[column]
            override fun getValueAt(row: Int, column: Int): Any = endpoints[row].let { listOf(it.method, "/" + it.path.removePrefix("/"), it.operation, it.responseType ?: "") [column] }
        }
        val table = ReportTable(model).apply { fillsViewportHeight = true }
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        table.columnModel.getColumn(0).preferredWidth = 70
        table.columnModel.getColumn(1).preferredWidth = 360
        table.columnModel.getColumn(2).preferredWidth = 220
        table.columnModel.getColumn(3).preferredWidth = 260
        val filter = JTextField(24).apply { name = "endpointFilter" }
        filter.document.addDocumentListener(object : DocumentListener {
            fun update() { sorter.rowFilter = if (filter.text.isBlank()) null else RowFilter.regexFilter("(?i)" + Regex.escape(filter.text.trim())) }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })

        // The mock server, one click from the contract.
        val serverCard = GroupBox(BorderLayout(0, 10))
        val status = Ui.wrapped("Runs the generated server on this computer. Routes whose response model was recovered answer with example JSON in the app's own field names (marked by an X-Apkproject-Mock header); the rest answer 501. It is SYNTHESIZED, not the app's real server.", 760)
        val port = JSpinner(SpinnerNumberModel(MockServer.firstFreePort().takeIf { it >= 1024 } ?: 8080, 1024, 65535, 1)).apply { editor = JSpinner.NumberEditor(this, "#") }
        val url = Ui.link("") { mock?.url?.let { browse(it) } }.apply { name = "mockUrl"; isVisible = false }
        val toggle = Ui.primary("Start mock server") {}.apply { name = "mockToggle"; isEnabled = Files.isRegularFile(p.skeleton) }
        toggle.addActionListener {
            val jdk = env.jdkHome()
            if (mock?.running == true) {
                mock?.stop()
                toggle.text = "Start mock server"
                url.isVisible = false
                return@addActionListener
            }
            if (jdk == null) {
                JOptionPane.showMessageDialog(this, "A Java 17 JDK is needed to run the mock server.", "No JDK", JOptionPane.WARNING_MESSAGE)
                return@addActionListener
            }
            toggle.isEnabled = false
            val generation = projectGeneration
            val chosenPort = port.value as Int
            Thread({
                val server = MockServer(jdk)
                val result = runCatching { server.start(p.skeleton, chosenPort) }
                SwingUtilities.invokeLater {
                    if (generation != projectGeneration) { server.stop(); return@invokeLater }
                    toggle.isEnabled = true
                    result.onSuccess {
                        mock = server
                        toggle.text = "Stop mock server"
                        url.text = "${server.url}  (${it.substringBefore(" on ")})"
                        url.isVisible = true
                    }.onFailure { JOptionPane.showMessageDialog(this, it.message, "Mock server", JOptionPane.ERROR_MESSAGE) }
                }
            }, "mock-server").start()
        }
        serverCard.add(Ui.column(Ui.heading("Mock server"), status, gap = 6), BorderLayout.NORTH)
        serverCard.add(Ui.row(JLabel("Port"), port, toggle, url, gap = 10), BorderLayout.CENTER)

        val hosts = Ui.wrapped(
            if (c == null) "This project has no backend contract." else
                "${endpoints.size} endpoints · ${c.models.size} JSON models · base URLs: ${c.baseUrls.joinToString().ifEmpty { "none found" }} · hosts: ${c.hosts.take(8).joinToString()}${if (c.hosts.size > 8) " …" else ""}",
            900, Theme.text,
        )
        val top = Ui.column(serverCard, hosts, Ui.row(Ui.filterLabel(filter), filter, Ui.secondary("Open OpenAPI file") { open(p.root.resolve("backend/openapi.yaml")) }), gap = 12)
        return JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(14, 2, 2, 2)
            add(top, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    private fun names(p: Project): JComponent {
        val rows = p.names.map { n ->
            val decl = n.declaration
            val owner = decl.substringBefore("->").removePrefix("L").removeSuffix(";")
            val kind = when { "->" !in decl -> "class"; "(" in decl -> "method"; else -> "field" }
            val was = when (kind) {
                "class" -> owner.replace('/', '.')
                else -> owner.substringAfterLast('/') + "." + decl.substringAfter("->").substringBefore('(')
            }
            listOf(n.name, was, kind, n.rule, n.evidence)
        }
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 5
            override fun getColumnName(column: Int) = listOf("Original name", "Obfuscated as", "Kind", "Rule", "Evidence")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        }
        val table = ReportTable(model).apply { fillsViewportHeight = true }
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        listOf(200, 220, 70, 210, 520).forEachIndexed { k, w -> table.columnModel.getColumn(k).preferredWidth = w }
        val filter = JTextField(24).apply { name = "nameFilter" }
        filter.document.addDocumentListener(object : DocumentListener {
            fun update() { sorter.rowFilter = if (filter.text.isBlank()) null else RowFilter.regexFilter("(?i)" + Regex.escape(filter.text.trim())) }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        val intro = Ui.wrapped(
            if (rows.isEmpty()) "No names were recovered from compiler evidence (the app may not be obfuscated, or its compilers left no names behind)."
            else "${Ui.count(rows.size)} names taken back from strings the compilers wrote into the bytecode. Measured on an app with a known R8 mapping, 99.3% of such names were right; each row says what it rests on.",
            900,
        )
        return JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(14, 2, 2, 2)
            add(Ui.column(intro, Ui.row(Ui.filterLabel(filter), filter), gap = 6), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    private fun report(p: Project): JComponent {
        val pane = JEditorPane("text/html", Markdown.page(p.reportMarkdown ?: "No report found in this project."))
        pane.isEditable = false
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { browse(it.toString()) } }
        pane.caretPosition = 0
        return JScrollPane(pane).apply { border = BorderFactory.createEmptyBorder(10, 0, 0, 0) }
    }

    private fun open(path: Path) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) runCatching { Desktop.getDesktop().open(path.toFile()) }.onSuccess { return }
        JOptionPane.showMessageDialog(this, path.toString(), "Location", JOptionPane.INFORMATION_MESSAGE)
    }

    private fun browse(url: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) runCatching { Desktop.getDesktop().browse(URI(url)) }.onSuccess { return }
        JOptionPane.showMessageDialog(this, url, "Address", JOptionPane.INFORMATION_MESSAGE)
    }

    fun selectTab(title: String) {
        projectTabs?.let { tabs -> for (i in 0 until tabs.tabCount) if (tabs.getTitleAt(i) == title) tabs.selectedIndex = i }
    }

    fun closeProject() {
        shutdown()
        mock = null
        projectView?.let { body.remove(it) }
        projectView = null
        projectTabs = null
        project = null
        cards.show(body, "empty")
        onProject(null)
        revalidate()
        repaint()
    }

    fun shutdown() { projectGeneration++; mock?.stop() }
}
