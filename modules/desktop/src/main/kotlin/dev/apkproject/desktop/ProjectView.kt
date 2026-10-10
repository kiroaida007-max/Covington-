package dev.apkproject.desktop

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.filechooser.FileSystemView
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * An open project: a navigation tree on the left (summary, source folders, resources, backend,
 * names, report) and the selected item on the right, like Explorer or the Windows management
 * consoles. Source folders list their files with each file's verification result.
 */
class ProjectView(val project: Project, private val env: Environment, private val settings: Settings, private val status: StatusBar) : JPanel(BorderLayout()) {
    enum class Page(val title: String) { SUMMARY("Summary"), SOURCE("Source Code"), RESOURCES("Resources"), BACKEND("Backend API"), NAMES("Recovered Names"), REPORT("Report") }

    private sealed class Nav {
        data class PageNode(val page: Page, val label: String) : Nav() { override fun toString() = label }
        data class FolderNode(val page: Page, val dir: Path) : Nav() { override fun toString() = dir.fileName.toString() }
    }

    private val resRoot: Path = project.root.resolve("app/src/main/res")
    private val cards = CardLayout()
    private val right = JPanel(cards)
    private val pageNodes = HashMap<Page, DefaultMutableTreeNode>()
    private val folderNodes = HashMap<Path, DefaultMutableTreeNode>()
    private val tree: JTree
    private val folders = FolderView()
    private val backend = BackendView()

    init {
        val root = DefaultMutableTreeNode(Nav.PageNode(Page.SUMMARY, project.title))
        for (page in Page.entries) {
            val node = DefaultMutableTreeNode(Nav.PageNode(page, page.title))
            pageNodes[page] = node
            root.add(node)
            when (page) {
                Page.SOURCE -> addFolders(node, page, project.javaRoot)
                Page.RESOURCES -> addFolders(node, page, resRoot)
                else -> {}
            }
        }
        tree = JTree(DefaultTreeModel(root))
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.showsRootHandles = true
        tree.cellRenderer = NavRenderer()
        tree.expandPath(TreePath(root.path))
        tree.addTreeSelectionListener { e -> (e.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject?.let { navigate(it as Nav) } }

        right.add(summary(), Page.SUMMARY.name)
        right.add(folders, Page.SOURCE.name)
        right.add(backend, Page.BACKEND.name)
        right.add(names(), Page.NAMES.name)
        right.add(report(), Page.REPORT.name)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(tree).apply { border = null }, right)
        split.dividerLocation = settings.treeWidth
        split.dividerSize = 5
        split.border = null
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { settings.treeWidth = split.dividerLocation }
        add(split, BorderLayout.CENTER)
        select(Page.SUMMARY)
    }

    fun select(page: Page) {
        val node = pageNodes.getValue(page)
        tree.selectionPath = TreePath(node.path)
        tree.scrollPathToVisible(TreePath(node.path))
    }

    fun dispose() = backend.stopServer()

    /** Shows [file] selected in its folder's list, as Explorer's "open file location" does. */
    fun reveal(file: Path) {
        openFolderNode(file.parent)
        folders.selectFile(file)
    }

    private fun addFolders(parent: DefaultMutableTreeNode, page: Page, root: Path) {
        if (!Files.isDirectory(root)) return
        folderNodes[root] = parent
        val dirs = Files.walk(root).use { s -> s.filter { Files.isDirectory(it) && it != root }.sorted().toList() }
        for (dir in dirs) {
            val node = DefaultMutableTreeNode(Nav.FolderNode(page, dir))
            folderNodes[dir] = node
            folderNodes[dir.parent]?.add(node)
        }
    }

    private fun navigate(nav: Nav) {
        when (nav) {
            is Nav.PageNode -> when (nav.page) {
                Page.SOURCE -> showFolder(Page.SOURCE, project.javaRoot)
                Page.RESOURCES -> showFolder(Page.RESOURCES, resRoot)
                else -> { cards.show(right, nav.page.name); status.message("") }
            }
            is Nav.FolderNode -> showFolder(nav.page, nav.dir)
        }
    }

    private fun showFolder(page: Page, dir: Path) {
        folders.show(page, dir)
        cards.show(right, Page.SOURCE.name)
    }

    private fun openFolderNode(dir: Path) {
        val node = folderNodes[dir] ?: return
        val path = TreePath(node.path)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private inner class NavRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val nav = (value as? DefaultMutableTreeNode)?.userObject
            val folder = nav is Nav.FolderNode || (nav is Nav.PageNode && (nav.page == Page.SOURCE || nav.page == Page.RESOURCES))
            if (folder) icon = if (expanded && !leaf) openIcon else closedIcon
            return this
        }
    }

    // ---- Summary -------------------------------------------------------------------------

    private fun summary(): JComponent {
        val r = project.report
        val panel = JPanel(GridBagLayout())
        panel.border = Ui.gap(10, 12, 10, 12)
        var y = 0
        fun group(title: String, rows: List<Pair<String, String>>) {
            val g = JPanel(GridBagLayout())
            rows.forEachIndexed { k, (label, value) ->
                g.add(JLabel("$label:"), Ui.gbc(0, k, insets = Insets(3, 0, 3, 16), anchor = GridBagConstraints.NORTHWEST))
                g.add(JLabel(value), Ui.gbc(1, k, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 0), anchor = GridBagConstraints.NORTHWEST))
            }
            panel.add(Ui.group(title, g), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        if (r == null) {
            panel.add(JLabel("This folder has no readable recovery report (recovery\\report.json)."), Ui.gbc(0, y++))
        } else {
            val m = r.manifest
            group("Application", listOfNotNull(
                "Package" to (m.packageName ?: "unknown"),
                "Version" to listOfNotNull(m.versionName, m.versionCode?.let { "(code $it)" }).joinToString(" ").ifEmpty { "unknown" },
                "Built with" to r.originalToolchain.joinToString("; ").ifEmpty { "not recorded in the app" },
                "Components" to "${m.activities} activities, ${m.services} services, ${m.receivers} receivers, ${m.providers} providers",
                "Input" to File(r.input).name,
                "Recovered in" to Ui.duration(r.elapsedMs),
            ))
            val c = r.compileCheck
            val rt = r.roundTrip
            val x = r.crossCheck
            // "App code" is the app's own packages, as opposed to bundled libraries; omitted when the app declares none.
            fun app(part: Int, whole: Int) = if (whole > 0) ";  app code ${Ui.count(part)} of ${Ui.count(whole)}" else ""
            group("Verification", listOf(
                "Java files that compile" to "${Ui.count(c.files - c.filesWithErrors)} of ${Ui.count(c.files)} (${Ui.percent(c.files - c.filesWithErrors, c.files)})" + app(c.appFiles - c.appFilesWithErrors, c.appFiles),
                "Methods that match the original bytecode" to if (rt.methods == 0) "not verified" else "${Ui.count(rt.equivalent)} of ${Ui.count(rt.methods)} (${Ui.percent(rt.equivalent, rt.methods)})" + app(rt.appEquivalent, rt.appMethods),
                "Methods proved for every input (Z3)" to (x?.let { "${it.proved} of ${it.eligible} simple ${plural(it.eligible, "method")}" } ?: "not run"),
                "Decompiler mistakes repaired" to if (r.repair.methodsFixed > 0) "${r.repair.methodsFixed} ${plural(r.repair.methodsFixed, "method")} (each fix proved by recompiling)" else "none (${r.repair.status.lowercase()})",
            ))
            val e = r.evidenceNames
            group("Contents", listOf(
                "Names from compiler evidence (OBSERVED)" to if (e.size == 3) "${Ui.count(e[0])} classes, ${Ui.count(e[1])} fields, ${Ui.count(e[2])} methods" else "not recorded in this report",
                "Library classes named by structure (INFERRED)" to Ui.count(r.libraryClassesNamed),
                "Verified library dependencies" to "${r.libraries.count { it.status == "VERIFIED" }} (${Ui.count(r.librarySourcesRemoved)} decompiled files replaced by the published library)",
                "Backend API" to "${Ui.count(r.backend.endpoints)} endpoints, ${r.backend.hosts} hosts, ${r.backend.models} JSON models",
                "Native code" to "${r.native.libraries} ${plural(r.native.libraries, "library", "libraries")}, ${Ui.count(r.native.javaNativeMethods)} native ${plural(r.native.javaNativeMethods, "method")}",
            ))
            val layers = JTable(object : AbstractTableModel() {
                override fun getRowCount() = r.layers.size
                override fun getColumnCount() = 4
                override fun getColumnName(column: Int) = listOf("Layer", "Location", "Evidence", "Summary")[column]
                override fun getValueAt(row: Int, column: Int): Any = r.layers[row].let { listOf(it.layer, it.location, it.evidence, it.summary)[column] }
            })
            layers.autoCreateRowSorter = true
            Ui.columnWidths(layers, 150, 200, 200, 480)
            layers.preferredScrollableViewportSize = java.awt.Dimension(800, layers.rowHeight * (r.layers.size + 1))
            val box = JPanel(BorderLayout()).apply { add(JScrollPane(layers), BorderLayout.CENTER) }
            panel.add(Ui.group("Layers and how far to trust them", box), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        panel.add(JPanel(), Ui.gbc(0, y, weightx = 1.0, fill = GridBagConstraints.BOTH).apply { weighty = 1.0 })
        return JScrollPane(panel).apply { border = null; verticalScrollBar.unitIncrement = 16 }
    }

    private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) one else many

    // ---- Folders and files ---------------------------------------------------------------

    private data class Row(val name: String, val path: Path, val folder: Boolean, val size: Long, val verdict: FileVerdict?)

    private inner class FolderView : JPanel(BorderLayout()) {
        private var rows: List<Row> = emptyList()
        private var page = Page.SOURCE
        private val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = if (page == Page.SOURCE) 6 else 3
            override fun getColumnName(column: Int) =
                if (page == Page.SOURCE) listOf("Name", "Status", "Methods", "Matching", "Compile errors", "Size")[column] else listOf("Name", "Type", "Size")[column]
            override fun getColumnClass(column: Int): Class<*> = when {
                column == 0 -> Row::class.java
                page == Page.SOURCE && column in 2..4 -> Integer::class.java
                column == columnCount - 1 -> java.lang.Long::class.java
                else -> String::class.java
            }
            override fun getValueAt(row: Int, column: Int): Any? {
                val r = rows[row]
                if (column == 0) return r
                if (column == columnCount - 1) return if (r.folder) null else r.size
                if (page != Page.SOURCE) return if (r.folder) "File folder" else type(r.name)
                val v = r.verdict
                return when (column) {
                    1 -> if (r.folder) "File folder" else status(v)
                    2 -> v?.methods
                    3 -> v?.equivalent
                    4 -> v?.compileErrors?.takeIf { it > 0 }
                    else -> null
                }
            }
        }
        private val table = JTable(model)
        private val sorter = TableRowSorter(model)
        private val viewer = RSyntaxTextArea().apply {
            isEditable = false
            highlightCurrentLine = false
            isCodeFoldingEnabled = true
            antiAliasingEnabled = true
            font = Ui.monospace
        }
        private val info = JLabel(" ")
        private val openButton = Ui.button("Open") { current?.let { Ui.open(this, it) } }.apply { isEnabled = false }
        private var current: Path? = null
        private val icons = HashMap<String, Icon?>()
        private val split: JSplitPane

        init {
            table.rowSorter = sorter
            table.autoCreateRowSorter = false
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.fillsViewportHeight = true
            table.showVerticalLines = false
            table.showHorizontalLines = false
            table.intercellSpacing = java.awt.Dimension(0, 0)
            table.setDefaultRenderer(Row::class.java, NameRenderer())
            table.setDefaultRenderer(String::class.java, StatusRenderer())
            table.setDefaultRenderer(java.lang.Long::class.java, SizeRenderer())
            table.selectionModel.addListSelectionListener { e -> if (!e.valueIsAdjusting) selected()?.let { if (!it.folder) preview(it) } }
            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) selected()?.let { if (it.folder) openFolderNode(it.path) else Ui.open(this@FolderView, it.path) }
                }
            })
            table.registerKeyboardAction({ selected()?.let { if (it.folder) openFolderNode(it.path) } }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
            val viewerPanel = JPanel(BorderLayout())
            val header = JPanel(BorderLayout(8, 0)).apply {
                border = Ui.gap(4, 6, 4, 4)
                add(info, BorderLayout.CENTER)
                add(openButton, BorderLayout.EAST)
            }
            viewerPanel.add(header, BorderLayout.NORTH)
            viewerPanel.add(RTextScrollPane(viewer).apply { lineNumbersEnabled = true }, BorderLayout.CENTER)
            split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), viewerPanel)
            split.resizeWeight = 0.4
            split.dividerSize = 5
            split.border = null
            add(split, BorderLayout.CENTER)
        }

        fun show(page: Page, dir: Path) {
            val columnsChanged = page != this.page
            this.page = page
            val entries = if (Files.isDirectory(dir)) Files.list(dir).use { it.toList() } else emptyList()
            rows = entries.map { p ->
                val folder = Files.isDirectory(p)
                val relative = if (page == Page.SOURCE) project.javaRoot.relativize(p).toString().replace('\\', '/') else ""
                Row(p.fileName.toString(), p, folder, if (folder) 0 else runCatching { Files.size(p) }.getOrDefault(0), if (folder) null else project.verdicts[relative])
            }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
            model.fireTableStructureChanged()
            if (columnsChanged || table.columnModel.columnCount > 0) {
                if (page == Page.SOURCE) Ui.columnWidths(table, 280, 130, 70, 70, 100, 80) else Ui.columnWidths(table, 320, 160, 90)
            }
            sorter.modelStructureChanged()
            val files = rows.count { !it.folder }
            val checked = rows.mapNotNull { it.verdict }
            status.message(
                if (!Files.isDirectory(dir)) "This project has no ${page.title.lowercase()} (${project.root.relativize(dir)} does not exist)"
                else if (page == Page.SOURCE && checked.isNotEmpty()) "${relativeLabel(dir)}:  $files files;  ${checked.count { it.kind == FileVerdict.Kind.VERIFIED }} verified, ${checked.count { it.kind == FileVerdict.Kind.PARTIAL }} partly verified, ${checked.count { it.kind == FileVerdict.Kind.NOT_COMPILING }} do not compile"
                else "${relativeLabel(dir)}:  ${rows.size} items",
            )
            if (rows.none { it.folder } && rows.isNotEmpty()) table.setRowSelectionInterval(0, 0) else clearPreview()
        }

        fun selectFile(file: Path) {
            val k = rows.indexOfFirst { it.path == file }
            if (k < 0) return
            val view = table.convertRowIndexToView(k)
            table.setRowSelectionInterval(view, view)
            table.scrollRectToVisible(table.getCellRect(view, 0, true))
        }

        private fun relativeLabel(dir: Path) = project.root.relativize(dir).toString().ifEmpty { "." }

        private fun selected(): Row? = table.selectedRow.takeIf { it >= 0 }?.let { rows[table.convertRowIndexToModel(it)] }

        private fun clearPreview() {
            current = null
            viewer.text = ""
            info.text = " "
            info.foreground = UIManager.getColor("Label.foreground")
            openButton.isEnabled = false
        }

        private fun preview(row: Row) {
            current = row.path
            openButton.isEnabled = true
            val ext = row.name.substringAfterLast('.', "").lowercase()
            viewer.syntaxEditingStyle = when (ext) {
                "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA
                "xml" -> SyntaxConstants.SYNTAX_STYLE_XML
                "json" -> SyntaxConstants.SYNTAX_STYLE_JSON
                "gradle", "kts" -> SyntaxConstants.SYNTAX_STYLE_GROOVY
                "yaml", "yml" -> SyntaxConstants.SYNTAX_STYLE_YAML
                "properties" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
                else -> SyntaxConstants.SYNTAX_STYLE_NONE
            }
            val binary = ext in setOf("png", "webp", "jpg", "jpeg", "gif", "ttf", "otf", "so", "dex", "jar")
            viewer.text = when {
                binary -> "(binary file, ${Ui.human(row.size)}; use Open to view it)"
                row.size > 4L shl 20 -> "(file too large to preview, ${Ui.human(row.size)})"
                else -> runCatching { Files.readString(row.path) }.getOrElse { "(cannot read: ${it.message})" }
            }
            viewer.caretPosition = 0
            val v = row.verdict
            info.text = if (v != null) "${row.name}  —  ${v.describe()}" else row.name
            info.foreground = when (v?.kind) {
                FileVerdict.Kind.VERIFIED -> Ui.good
                FileVerdict.Kind.PARTIAL -> Ui.warn
                FileVerdict.Kind.NOT_COMPILING -> Ui.bad
                else -> UIManager.getColor("Label.foreground")
            }
            info.toolTipText = v?.firstErrors?.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { Ui.escape(it) }
            status.message(project.root.relativize(row.path).toString() + if (v != null) "  —  ${v.describe()}" else "")
        }

        private fun status(v: FileVerdict?) = when (v?.kind) {
            FileVerdict.Kind.VERIFIED -> "Verified"
            FileVerdict.Kind.PARTIAL -> "Partly verified"
            FileVerdict.Kind.NOT_COMPILING -> "Does not compile"
            FileVerdict.Kind.UNCHECKED -> "No methods"
            null -> "Not checked"
        }

        private fun type(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "xml" -> "XML document"
            "png", "webp", "jpg", "jpeg", "gif" -> "Image"
            "json" -> "JSON file"
            "ttf", "otf" -> "Font"
            "" -> "File"
            else -> name.substringAfterLast('.').uppercase() + " file"
        }

        /** The shell's own icon for each kind of file (Windows Explorer's icons on Windows). */
        private fun iconFor(row: Row): Icon? {
            if (row.folder) return UIManager.getIcon("FileView.directoryIcon")
            val ext = row.name.substringAfterLast('.', "").lowercase()
            return icons.getOrPut(ext) { runCatching { FileSystemView.getFileSystemView().getSystemIcon(row.path.toFile()) }.getOrNull() ?: UIManager.getIcon("FileView.fileIcon") }
        }

        private inner class NameRenderer : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                val r = value as Row
                super.getTableCellRendererComponent(table, r.name, isSelected, hasFocus, row, column)
                icon = iconFor(r)
                border = Ui.gap(0, 4, 0, 4)
                return this
            }
        }

        private inner class StatusRenderer : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                border = Ui.gap(0, 4, 0, 4)
                if (!isSelected) foreground = when (value) {
                    "Verified" -> Ui.good
                    "Partly verified" -> Ui.warn
                    "Does not compile" -> Ui.bad
                    else -> table.foreground
                }
                return this
            }
        }

        private inner class SizeRenderer : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.RIGHT }
            override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                val text = (value as? Long)?.let { if (it < 1024) "1 KB" else Ui.count(((it + 1023) / 1024).toInt()) + " KB" } ?: ""
                return super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column).also { border = Ui.gap(0, 4, 0, 8) }
            }
        }
    }

    // ---- Backend ---------------------------------------------------------------------------

    private inner class BackendView : JPanel(BorderLayout()) {
        private var server: MockServer? = null
        private val state = JLabel("Stopped")
        private val port = JSpinner(SpinnerNumberModel(MockServer.firstFreePort().takeIf { it > 0 } ?: 8080, 1024, 65535, 1)).apply { editor = JSpinner.NumberEditor(this, "#") }
        private val start = Ui.button("Start", KeyEvent.VK_S) { startServer() }
        private val stop = Ui.button("Stop", KeyEvent.VK_T) { stopServer() }.apply { isEnabled = false }
        private val browse = Ui.button("Open in Browser", KeyEvent.VK_B) { server?.url?.let { Ui.browse(this, it) } }.apply { isEnabled = false }

        init {
            border = Ui.gap(8, 8, 8, 8)
            val c = project.contract
            val endpoints = c?.endpoints.orEmpty()
            val model = object : AbstractTableModel() {
                override fun getRowCount() = endpoints.size
                override fun getColumnCount() = 4
                override fun getColumnName(column: Int) = listOf("Method", "Path", "Operation", "Response type")[column]
                override fun getValueAt(row: Int, column: Int): Any = endpoints[row].let { listOf(it.method, "/" + it.path.removePrefix("/"), it.operation, it.responseType ?: "")[column] }
            }
            val table = JTable(model)
            val sorter = TableRowSorter(model)
            table.rowSorter = sorter
            table.fillsViewportHeight = true
            Ui.columnWidths(table, 70, 380, 220, 260)

            val serverBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
            serverBox.add(JLabel("Status:"))
            serverBox.add(state)
            serverBox.add(javax.swing.Box.createHorizontalStrut(12))
            serverBox.add(JLabel("Port:"))
            serverBox.add(port)
            serverBox.add(start)
            serverBox.add(stop)
            serverBox.add(browse)
            val note = JLabel("<html>Runs the generated server skeleton on this computer. Routes with a recovered response model answer with example JSON in the app's own field names; the rest answer 501. It is SYNTHESIZED: the app's real server code is not in the APK.</html>")
            note.border = Ui.gap(2, 6, 0, 0)
            val serverPanel = JPanel(BorderLayout()).apply { add(serverBox, BorderLayout.NORTH); add(note, BorderLayout.CENTER) }
            if (c == null) { start.isEnabled = false; state.text = "No backend contract in this project" }

            val find = findField(sorter)
            val findRow = JPanel(BorderLayout(6, 0)).apply {
                border = Ui.gap(8, 0, 6, 0)
                add(JLabel("Find:").apply { displayedMnemonic = KeyEvent.VK_F; labelFor = find }, BorderLayout.WEST)
                add(find, BorderLayout.CENTER)
                add(Ui.button("Open OpenAPI File") { Ui.open(this, project.root.resolve("backend/openapi.yaml")) }.apply { isEnabled = Files.isRegularFile(project.root.resolve("backend/openapi.yaml")) }, BorderLayout.EAST)
            }
            val north = JPanel(BorderLayout()).apply {
                add(Ui.group("Mock server", serverPanel), BorderLayout.NORTH)
                add(findRow, BorderLayout.SOUTH)
            }
            add(north, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            val facts = if (c == null) "" else "${endpoints.size} endpoints, ${c.models.size} JSON models.  Base URLs: ${c.baseUrls.joinToString().ifEmpty { "none found" }}"
            add(JLabel(facts).apply { border = Ui.gap(6, 0, 0, 0) }, BorderLayout.SOUTH)
        }

        private fun startServer() {
            val jdk = env.jdkHome()
            if (jdk == null) {
                JOptionPane.showMessageDialog(this, "A Java 17 JDK is needed to compile and run the mock server.", "Mock server", JOptionPane.WARNING_MESSAGE)
                return
            }
            start.isEnabled = false
            state.text = "Starting..."
            val s = MockServer(jdk)
            val chosen = port.value as Int
            Thread({
                val result = runCatching { s.start(project.skeleton, chosen) }
                SwingUtilities.invokeLater {
                    result.onSuccess {
                        server = s
                        state.text = "Running at ${s.url}"
                        stop.isEnabled = true
                        browse.isEnabled = true
                        port.isEnabled = false
                    }.onFailure {
                        state.text = "Stopped"
                        start.isEnabled = true
                        JOptionPane.showMessageDialog(this, it.message, "Mock server", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }, "mock-server-start").apply { isDaemon = true }.start()
        }

        fun stopServer() {
            server?.stop()
            server = null
            state.text = "Stopped"
            start.isEnabled = project.contract != null
            stop.isEnabled = false
            browse.isEnabled = false
            port.isEnabled = true
        }
    }

    // ---- Names and report ------------------------------------------------------------------

    private fun names(): JComponent {
        val rows = project.names.map { n ->
            val decl = n.declaration
            val owner = decl.substringBefore("->").removePrefix("L").removeSuffix(";")
            val kind = when { "->" !in decl -> "Class"; "(" in decl -> "Method"; else -> "Field" }
            val was = if (kind == "Class") owner.replace('/', '.') else owner.substringAfterLast('/') + "." + decl.substringAfter("->").substringBefore('(').substringBefore(':')
            listOf(n.name, was, kind, n.rule, n.evidence)
        }
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 5
            override fun getColumnName(column: Int) = listOf("Original name", "Obfuscated name", "Kind", "Rule", "Evidence")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        }
        val table = JTable(model)
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        table.fillsViewportHeight = true
        Ui.columnWidths(table, 200, 220, 60, 200, 500)
        val find = findField(sorter)
        val intro = JLabel(
            if (rows.isEmpty()) "No names were taken back from compiler evidence. The app may not be obfuscated, or its compilers left no names behind."
            else "<html>${Ui.count(rows.size)} names read from strings the compilers wrote into the bytecode (OBSERVED). On an app with a known R8 mapping, 99.3% of such names were correct. Each row gives the evidence it rests on.</html>",
        )
        val north = JPanel(BorderLayout(0, 6)).apply {
            border = Ui.gap(0, 0, 6, 0)
            add(intro, BorderLayout.NORTH)
            add(JPanel(BorderLayout(6, 0)).apply { add(JLabel("Find:").apply { labelFor = find; displayedMnemonic = KeyEvent.VK_F }, BorderLayout.WEST); add(find, BorderLayout.CENTER) }, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = Ui.gap(8, 8, 8, 8)
            add(north, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    private fun report(): JComponent {
        val pane = JEditorPane("text/html", Markdown.page(project.reportMarkdown ?: "This project has no recovery report (recovery\\RECOVERY.md)."))
        pane.isEditable = false
        pane.addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { Ui.browse(this, it.toString()) } }
        pane.caretPosition = 0
        return JScrollPane(pane).apply { border = null }
    }

    private fun <M : javax.swing.table.TableModel> findField(sorter: TableRowSorter<M>): JTextField {
        val find = JTextField(30)
        find.document.addDocumentListener(object : DocumentListener {
            fun update() { sorter.rowFilter = if (find.text.isBlank()) null else RowFilter.regexFilter("(?i)" + Regex.escape(find.text.trim())) }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        return find
    }
}
