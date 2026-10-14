package dev.apkproject.desktop

import dev.apkproject.app.PrivacyMap
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
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
import javax.swing.JTabbedPane
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
 * An open project: a navigation tree on the left (summary, source folders, resources, UI audit,
 * screens and the data they reach, privacy, components, backend, names, report) and the selected item on the right, like Explorer or the Windows management
 * consoles. Source folders list their files with each file's verification result.
 */
class ProjectView(val project: Project, private val env: Environment, private val settings: Settings, private val status: StatusBar,
                  private val refresh: () -> Unit = {}) : JPanel(BorderLayout()) {
    enum class Page(val title: String) { SUMMARY("Summary"), SOURCE("Source Code"), RESOURCES("Resources"), UI_RISK("UI & Risk"), SCREENS("Screens & Data"), PRIVACY("Privacy"), RUNTIME("Runtime Behavior"), COMPONENTS("Components"), BACKEND("Backend API"), NAMES("Recovered Names"), REPORT("Report"), STATUS("Analysis Status") }

    private sealed class Nav {
        data class PageNode(val page: Page, val label: String) : Nav() { override fun toString() = label }
        data class FolderNode(val page: Page, val dir: Path) : Nav() { override fun toString() = dir.fileName.toString() }
    }

    private val resRoot: Path = project.root.resolve("app/src/main/res")
    private val right = JTabbedPane().apply { name = "projectTabs"; tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT }
    private val folderPages = HashMap<Page, JPanel>()
    private var navigating = false
    private val pageNodes = HashMap<Page, DefaultMutableTreeNode>()
    private val folderNodes = HashMap<Path, DefaultMutableTreeNode>()
    private val tree: JTree
    private var folders: FolderView? = null
    private var backend: BackendView? = null
    private var runtime: RuntimeView? = null
    private var availability: AnalysisStatusView? = null
    private val loadedPages = HashSet<Page>()

    init {
        val root = DefaultMutableTreeNode(Nav.PageNode(Page.SUMMARY, project.title))
        for (page in Page.entries) {
            val node = DefaultMutableTreeNode(Nav.PageNode(page, page.title))
            pageNodes[page] = node
            root.add(node)
            when (page) {
                Page.SOURCE -> addFolders(node, page, project.javaRoot, project.sourceFolders)
                Page.RESOURCES -> addFolders(node, page, resRoot, project.resourceFolders)
                else -> {}
            }
        }
        tree = JTree(DefaultTreeModel(root)).apply { name = "projectTree" }
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.showsRootHandles = true
        tree.cellRenderer = NavRenderer()
        tree.expandPath(TreePath(root.path))
        tree.addTreeSelectionListener { e -> (e.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject?.let { navigate(it as Nav) } }

        for (page in Page.entries) {
            val placeholder = JPanel(BorderLayout())
            if (page == Page.SOURCE || page == Page.RESOURCES) folderPages[page] = placeholder
            right.addTab(page.title, placeholder)
        }
        right.addChangeListener { if (!navigating) select(Page.entries[right.selectedIndex]) }

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

    fun dispose() {
        folders?.dispose()
        backend?.dispose()
        runtime?.dispose()
        availability?.dispose()
    }

    internal val isLoadingFiles: Boolean get() = folders?.isLoading == true

    private fun folderView(): FolderView = folders ?: FolderView().also {
        folders = it
    }

    /** Shows [file] selected in its folder's list, as Explorer's "open file location" does. */
    fun reveal(file: Path) {
        openFolderNode(file.parent)
        folderView().reveal(file)
    }

    private fun addFolders(parent: DefaultMutableTreeNode, page: Page, root: Path, dirs: List<Path>) {
        folderNodes[root] = parent
        for (dir in dirs) {
            val node = DefaultMutableTreeNode(Nav.FolderNode(page, dir))
            folderNodes[dir] = node
            folderNodes[dir.parent]?.add(node)
        }
    }

    private fun navigate(nav: Nav) {
        navigating = true
        try {
            val page = when (nav) {
                is Nav.PageNode -> nav.page.also {
                    when (it) {
                        Page.SOURCE -> showFolder(it, project.javaRoot)
                        Page.RESOURCES -> showFolder(it, resRoot)
                        else -> { folders?.deactivate(); ensurePage(it); status.message("") }
                    }
                }
                is Nav.FolderNode -> nav.page.also { showFolder(it, nav.dir) }
            }
            right.selectedIndex = page.ordinal
        } finally { navigating = false }
    }

    private fun ensurePage(page: Page) {
        if (page in loadedPages) return
        val component = when (page) {
            Page.SUMMARY -> summary()
            Page.UI_RISK -> if (project.frontend == null) unavailable("audit/ui-audit.json") else uiRisk()
            Page.SCREENS -> if (project.screenMap == null) unavailable("audit/screen-map.json") else ScreensView(project.screenMap, status)
            Page.PRIVACY -> if (project.privacy == null) unavailable("audit/privacy.json") else PrivacyView(project.privacy, status)
            Page.RUNTIME -> RuntimeView(project, status).also { runtime = it }
            Page.COMPONENTS -> if (project.supplyChain == null) unavailable("audit/supply-chain.json") else ComponentsView(project.supplyChain, project.sbomFile, status)
            Page.BACKEND -> BackendView(project, env).also { backend = it }
            Page.NAMES -> if (project.reportStatuses.any { it.path == "recovery/recovered-names.json" && it.state != ReportReader.State.LOADED }) unavailable("recovery/recovered-names.json") else names()
            Page.REPORT -> if (project.reportMarkdown == null) unavailable("recovery/RECOVERY.md") else report()
            Page.STATUS -> AnalysisStatusView(project, ::select, refresh).also { availability = it }
            else -> return
        }
        right.setComponentAt(page.ordinal, component)
        loadedPages.add(page)
    }

    private fun unavailable(path: String): JComponent {
        val report = project.reportStatuses.firstOrNull { it.path == path }
        return JPanel(BorderLayout(6, 6)).apply {
            border = Ui.gap(12, 12, 12, 12)
            add(Ui.readOnlyText("$path\n${report?.let { AnalysisStatusView.label(it.state) + ": " + it.detail } ?: "Not available"}\n\n" +
                AnalysisStatusView.nextStep(report?.state ?: ReportReader.State.MISSING) + "\n\nThis unavailable report provides no result about app safety or recovery accuracy."), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(Ui.button("Review Analysis Status") { select(Page.STATUS) }) }, BorderLayout.CENTER)
        }
    }

    private fun showFolder(page: Page, dir: Path) {
        folderPages.getValue(page).apply { add(folderView(), BorderLayout.CENTER); revalidate(); repaint() }
        folderView().show(page, dir)
    }

    private fun openFolderNode(dir: Path) {
        val node = folderNodes[dir] ?: return
        val path = TreePath(node.path)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private inner class NavRenderer : DefaultTreeCellRenderer() {
        init { putClientProperty("html.disable", true) }
        override fun getTreeCellRendererComponent(tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val nav = (value as? DefaultMutableTreeNode)?.userObject
            val folder = nav is Nav.FolderNode || (nav is Nav.PageNode && (nav.page == Page.SOURCE || nav.page == Page.RESOURCES))
            if (folder) icon = if (expanded && !leaf) openIcon else closedIcon
            return this
        }
    }

    // ---- UI & Risk (front-end audit) ----------------------------------------------------

    private fun severityColor(severity: String): java.awt.Color = when (severity) {
        "CRITICAL", "HIGH" -> Ui.bad
        "MEDIUM" -> Ui.warn
        "LOW" -> Ui.good
        else -> javax.swing.UIManager.getColor("Label.foreground")
    }

    private fun uiRisk(): JComponent {
        val f = project.frontend
        val panel = Ui.formPanel()
        panel.border = Ui.gap(10, 12, 10, 12)
        var y = 0
        if (f == null) {
            panel.add(JLabel("This project has no front-end audit (audit/ui-audit.json). Re-run with a current version of apkproject."), Ui.gbc(0, y++))
            panel.add(JPanel(), Ui.gbc(0, y, weightx = 1.0, fill = GridBagConstraints.BOTH).apply { weighty = 1.0 })
            return JScrollPane(panel).apply { border = null }
        }
        // Risk score banner.
        val r = f.risk
        val grade = JLabel("Static risk ${r.score}/100   \u2014   grade ${r.grade}")
        grade.font = Ui.font.deriveFont(java.awt.Font.BOLD)
        grade.foreground = when (r.grade) { "A" -> Ui.good; "B" -> Ui.good; "C" -> Ui.warn; else -> Ui.bad }
        panel.add(grade, Ui.gbc(0, y++, insets = Insets(0, 0, 2, 0)))
        panel.add(Ui.readOnlyText(r.summary + " This grade covers the front-end static checks. Privacy flows and dependency vulnerabilities have separate reports; this is not a security certification."), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 8, 0)))
        run {
            val g = JPanel(GridBagLayout())
            val cells = listOf("Critical" to r.critical, "High" to r.high, "Medium" to r.medium, "Low" to r.low, "Info" to r.info)
            cells.forEachIndexed { k, (label, n) ->
                val v = JLabel("$n").apply { font = Ui.font.deriveFont(java.awt.Font.BOLD); foreground = if (n > 0 && label in setOf("Critical", "High")) Ui.bad else if (n > 0 && label == "Medium") Ui.warn else javax.swing.UIManager.getColor("Label.foreground") }
                g.add(JLabel("$label:"), Ui.gbc(k * 2, 0, insets = Insets(0, if (k == 0) 0 else 14, 0, 4)))
                g.add(v, Ui.gbc(k * 2 + 1, 0))
            }
            panel.add(Ui.group("Findings by severity", g), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        // Toolkit.
        run {
            val g = JPanel(GridBagLayout())
            val rows = listOf(
                "Built with" to f.toolkit.name,
                "Language" to f.toolkit.language,
                "Front-end recoverability" to f.toolkit.recoverability,
            ) + (if (f.toolkit.also.isNotEmpty()) listOf("Also present" to f.toolkit.also.joinToString(", ")) else emptyList())
            rows.forEachIndexed { k, (label, value) ->
                g.add(JLabel("$label:"), Ui.gbc(0, k, insets = Insets(3, 0, 3, 16), anchor = GridBagConstraints.NORTHWEST))
                g.add(Ui.readOnlyText(value), Ui.gbc(1, k, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 0)))
            }
            panel.add(Ui.group("UI toolkit", g), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        // Resilience / hardening.
        run {
            val h = f.hardening
            val g = JPanel(GridBagLayout())
            var yy = 0
            fun row(label: String, value: String) {
                g.add(JLabel("$label:"), Ui.gbc(0, yy, insets = Insets(3, 0, 3, 16), anchor = GridBagConstraints.NORTHWEST))
                g.add(Ui.readOnlyText(value), Ui.gbc(1, yy++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
            }
            val levelLabel = JLabel(h.level).apply { font = Ui.font.deriveFont(java.awt.Font.BOLD); foreground = if (h.level == "HARDENED") Ui.good else if (h.level == "BASIC") Ui.warn else javax.swing.UIManager.getColor("Label.foreground") }
            g.add(JLabel("Overall:"), Ui.gbc(0, yy, insets = Insets(3, 0, 3, 16), anchor = GridBagConstraints.NORTHWEST))
            g.add(levelLabel, Ui.gbc(1, yy++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
            row("Obfuscation", "${h.obfuscation.level} \u2014 ${h.obfuscation.evidence}")
            row("Protectors / packers", if (h.protectors.isEmpty()) "none detected" else h.protectors.joinToString("; ") { "${it.name} (${it.kind})" })
            row("Anti-analysis / integrity", if (h.defenses.isEmpty()) "none detected" else h.defenses.joinToString("; ") { "${it.name} [${it.evidence}]" })
            row("Impact on recovery", h.recoveryImpact)
            panel.add(Ui.group("Resilience / hardening", g), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        // Findings table.
        run {
            val model = object : AbstractTableModel() {
                override fun getRowCount() = f.findings.size
                override fun getColumnCount() = 6
                override fun getColumnName(column: Int) = listOf("Severity", "Category", "Finding", "Where", "Evidence", "Reference")[column]
                override fun getValueAt(row: Int, column: Int): Any = f.findings[row].let { listOf(it.severity + (if (it.count > 1) " \u00d7${it.count}" else ""), it.category, it.title, it.where, it.evidence, it.reference)[column] }
            }
            val table = JTable(model)
            table.autoCreateRowSorter = true
            table.fillsViewportHeight = true
            table.rowHeight = (table.rowHeight * 1.2).toInt()
            Ui.columnWidths(table, 90, 110, 320, 280, 90, 190)
            table.getColumnModel().getColumn(0).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, rr: Int, cc: Int): Component {
                    super.getTableCellRendererComponent(t, value, sel, foc, rr, cc)
                    if (!sel) foreground = severityColor((value as String).substringBefore(" "))
                    font = Ui.font.deriveFont(java.awt.Font.BOLD)
                    return this
                }
            }
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.autoResizeMode = JTable.AUTO_RESIZE_OFF
            val detail = Ui.readOnlyText("Select a finding above.", 6)
            detail.accessibleContext.accessibleName = "Finding detail and suggested fix"
            val detailScroll = JScrollPane(detail)
            table.selectionModel.addListSelectionListener { e ->
                if (!e.valueIsAdjusting && table.selectedRow >= 0) {
                    val finding = f.findings[table.convertRowIndexToModel(table.selectedRow)]
                    detail.text = "${finding.title}\n\n${finding.detail}\n\nSuggested fix: ${finding.fix}\n\nWhere: ${finding.where}\nEvidence: ${finding.evidence}\nReference: ${finding.reference}"
                    detail.caretPosition = 0
                    detailScroll.verticalScrollBar.value = 0
                    detailScroll.horizontalScrollBar.value = 0
                    status.message("${finding.severity}: ${finding.title} (${finding.evidence})")
                }
            }
            val box = JPanel(BorderLayout(0, 4))
            box.add(Ui.readOnlyText("Select a finding to read and copy its detail, suggested fix and reference. Evidence: OBSERVED (read from the APK) or INFERRED (a pattern that may be guarded)."), BorderLayout.NORTH)
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), detailScroll).apply {
                resizeWeight = 0.55
                dividerSize = 5
                border = null
            }
            box.add(split, BorderLayout.CENTER)
            box.preferredSize = java.awt.Dimension(600, 340)
            box.minimumSize = java.awt.Dimension(0, 340)
            if (f.findings.isNotEmpty()) table.setRowSelectionInterval(0, 0)
            panel.add(Ui.group("Findings (${f.findings.size})", box), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.BOTH, insets = Insets(0, 0, 10, 0)).apply { weighty = 1.0 })
        }
        // Screens table.
        run {
            val screens = f.screens.filter { it.kind.startsWith("ACTIVITY") }
            val model = object : AbstractTableModel() {
                override fun getRowCount() = screens.size
                override fun getColumnCount() = 4
                override fun getColumnName(column: Int) = listOf("Screen", "Exported", "Launcher", "Deep links")[column]
                override fun getValueAt(row: Int, column: Int): Any = screens[row].let { listOf(it.name, if (it.exported) "yes" else "no", if (it.launcher) "yes" else "", it.deepLinks.joinToString(", "))[column] }
            }
            val table = JTable(model)
            table.autoCreateRowSorter = true
            Ui.columnWidths(table, 320, 80, 80, 300)
            val box = JPanel(BorderLayout())
            box.add(JScrollPane(table), BorderLayout.CENTER)
            box.preferredSize = java.awt.Dimension(900, 180)
            box.minimumSize = java.awt.Dimension(0, 180)
            panel.add(Ui.group("Screens (${screens.size} activities; ${f.screens.count { it.kind == "FRAGMENT" }} fragments, ${f.screens.count { it.kind == "LAYOUT" }} layouts)", box), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        if (f.notes.isNotEmpty()) panel.add(Ui.readOnlyText("Notes: ${f.notes.joinToString("  ")}"), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        panel.add(JPanel(), Ui.gbc(0, y, weightx = 1.0, fill = GridBagConstraints.BOTH).apply { weighty = 1.0 })
        return JScrollPane(panel).apply { border = null; verticalScrollBar.unitIncrement = 16 }
    }

    // ---- Summary -------------------------------------------------------------------------

    private fun summary(): JComponent {
        val r = project.report
        val panel = Ui.formPanel()
        panel.border = Ui.gap(10, 12, 10, 12)
        var y = 0
        fun group(title: String, rows: List<Pair<String, String>>) {
            val g = JPanel(GridBagLayout())
            rows.forEachIndexed { k, (label, value) ->
                g.add(JLabel("$label:"), Ui.gbc(0, k, insets = Insets(3, 0, 3, 16), anchor = GridBagConstraints.NORTHWEST))
                g.add(Ui.readOnlyText(value), Ui.gbc(1, k, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(3, 0, 3, 0), anchor = GridBagConstraints.NORTHWEST))
            }
            panel.add(Ui.group(title, g), Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
        }
        val warnings = project.reportStatuses.filter { it.state !in setOf(ReportReader.State.LOADED, ReportReader.State.MISSING) }
        if (warnings.isNotEmpty()) group("Report loading issues — results incomplete", warnings.map { it.path to "${it.state}: ${it.detail}" })
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(Ui.button("Review Analysis Status") { select(Page.STATUS) })
        }, Ui.gbc(0, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(0, 0, 10, 0)))
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
                "Java files that compile" to if (c.status == "NOT_RUN") "Not run: ${c.note}" else "${Ui.count(c.files - c.filesWithErrors)} of ${Ui.count(c.files)} (${Ui.percent(c.files - c.filesWithErrors, c.files)})" + app(c.appFiles - c.appFilesWithErrors, c.appFiles),
                "Methods that match the original bytecode" to if (rt.methods == 0) "not verified" else "${Ui.count(rt.equivalent)} of ${Ui.count(rt.methods)} (${Ui.percent(rt.equivalent, rt.methods)})" + app(rt.appEquivalent, rt.appMethods),
                "Methods proved in the V0 model (Z3)" to (x?.let { "${it.proved} of ${it.eligible} simple ${plural(it.eligible, "method")}" } ?: "not run"),
                "Decompiler mistakes repaired" to if (r.repair.methodsFixed > 0) "${r.repair.methodsFixed} ${plural(r.repair.methodsFixed, "method")} (each fix verified by round trip)" else "none (${r.repair.status.lowercase()})",
            ))
            val e = r.evidenceNames
            group("Contents", listOf(
                "Names from compiler evidence (OBSERVED)" to if (e.size == 3) "${Ui.count(e[0])} classes, ${Ui.count(e[1])} fields, ${Ui.count(e[2])} methods" else "not recorded in this report",
                "Library classes named by structure (INFERRED)" to Ui.count(r.libraryClassesNamed),
                "Verified library dependencies" to "${r.libraries.count { it.status == "VERIFIED" }} (${Ui.count(r.librarySourcesRemoved)} decompiled files replaced by the published library)",
                "Backend API" to "${Ui.count(r.backend.endpoints)} endpoints, ${r.backend.hosts} hosts, ${r.backend.models} JSON models",
                "Native code" to "${r.native.libraries} ${plural(r.native.libraries, "library", "libraries")}, ${Ui.count(r.native.javaNativeMethods)} native ${plural(r.native.javaNativeMethods, "method")}",
                "Personal data flows (INFERRED)" to (r.privacy?.let { p ->
                    (if (p.flows == 0) "none found (${PrivacyMap.kinds(p.dataKinds.size)} read)" else "${p.flows} ${plural(p.flows, "flow")} (${p.high} high) from ${PrivacyMap.kinds(p.dataKinds.size)}") +
                        if (p.truncated || (project.privacy?.stats?.boundedSummaries ?: 0) > 0) " (analysis limits reached; see Privacy)" else ""
                } ?: "not in this report"),
                "Known vulnerabilities" to (r.supplyChain?.let { sc ->
                    when {
                        !sc.lookup.startsWith("OSV") -> "not looked up (${sc.libraries} libraries listed)"
                        sc.advisories == 0 -> "no advisories returned for queried packages" + if (sc.lookupComplete) "" else " (lookup incomplete)"
                        else -> "${sc.advisories} in ${sc.vulnerableComponents} ${plural(sc.vulnerableComponents, "component")} (${sc.critical} critical, ${sc.high} high)" + if (sc.lookupComplete) "" else " (lookup incomplete)"
                    }
                } ?: "not in this report"),
            ))
            val layers = ReportTable(object : AbstractTableModel() {
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

    private data class Row(val name: String, val path: Path, val folder: Boolean, val size: Long, val verdict: FileVerdict?, val blocked: Boolean = false) {
        override fun toString() = name
    }

    private inner class FolderView : JPanel(BorderLayout()) {
        private val listing = LatestTask("folder-reader")
        private val previewReader = LatestTask("file-preview-reader")
        private val filterReader = LatestTask("file-filter-reader")
        private var directoryRows: List<Row> = emptyList()
        private var pageIndex = 0
        private var currentDirectory = project.javaRoot
        private val pageLabel = JLabel(" ")
        private val previous = Ui.button("Previous") { pageIndex--; filterFiles(false) }.apply { name = "filesPrevious"; isEnabled = false }
        private val next = Ui.button("Next") { pageIndex++; filterFiles(false) }.apply { name = "filesNext"; isEnabled = false }
        private var pendingFile: Path? = null
        private var filterPending = false
        val isLoading: Boolean get() = listing.isBusy || previewReader.isBusy || filterReader.isBusy || filterPending
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
                if (column == 1 && r.blocked) return "Blocked link or special file"
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
        private val table = ReportTable(model).apply { name = "sourceTable" }
        private val sorter = TableRowSorter(model)
        private val find = JTextField(24).apply { name = "sourceFilter" }
        private val kinds = javax.swing.JComboBox(arrayOf("All files", "Verified", "Partly verified", "Does not compile", "Not compared"))
        private val viewer = RSyntaxTextArea().apply {
            name = "sourceEditor"
            isEditable = false
            highlightCurrentLine = false
            isCodeFoldingEnabled = true
            antiAliasingEnabled = true
            font = Ui.monospace
        }
        private val info = Ui.plainLabel(" ")
        private val openButton = Ui.button("Open") { current?.let { openExternal(it) } }.apply {
            isEnabled = false; toolTipText = "Open this document in its associated application"
        }
        private fun openExternal(path: Path) {
            runCatching { project.files.checked(path) }.fold({ Ui.open(this, it) }, {
                info.text = "Cannot open: ${it.message}"
            })
        }
        private var current: Path? = null
        private val icons = HashMap<String, Icon?>()
        private val split: JSplitPane

        private val filterTimer = javax.swing.Timer(150) { filterFiles() }.apply { isRepeats = false }

        private fun filterFiles(reset: Boolean = true) {
            if (listing.isBusy) return
            filterTimer.stop()
            filterPending = false
            if (reset) pageIndex = 0
            val snapshot = directoryRows
            val requested = pendingFile
            val requestedPage = pageIndex
            val query = find.text.trim()
            val kind = if (page == Page.SOURCE) kinds.selectedIndex else 0
            clearPreview()
            previous.isEnabled = false; next.isEnabled = false
            filterReader.submit({
                val matches = snapshot.filter { row ->
                    row.name.contains(query, ignoreCase = true) && (row.folder || when (kind) {
                        1 -> row.verdict?.kind == FileVerdict.Kind.VERIFIED
                        2 -> row.verdict?.kind == FileVerdict.Kind.PARTIAL
                        3 -> row.verdict?.kind == FileVerdict.Kind.NOT_COMPILING
                        4 -> row.verdict == null || row.verdict.kind == FileVerdict.Kind.UNCHECKED
                        else -> true
                    })
                }
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val revealIndex = requested?.let { file -> matches.indexOfFirst { it.path == file } } ?: -1
                val index = (if (revealIndex >= 0) revealIndex / 500 else requestedPage).coerceIn(0, (matches.size - 1).coerceAtLeast(0) / 500)
                Triple(matches.size, index, matches.drop(index * 500).take(500))
            }, { (count, index, entries) ->
                pageIndex = index; rows = entries
                model.fireTableDataChanged()
                previous.isEnabled = index > 0
                next.isEnabled = (index + 1) * 500 < count
                pageLabel.text = "Page ${index + 1} of ${((count + 499) / 500).coerceAtLeast(1)}; $count matching items (sort applies to this page)"
                status.message("${relativeLabel(currentDirectory)}: $count matching items; ${directoryRows.size} items in folder")
                if (pendingFile == requested) pendingFile = null
                if (requested != null) selectFile(requested)
                else if (rows.none { it.folder } && table.rowCount > 0) table.setRowSelectionInterval(0, 0)
            }, { error -> info.text = "Cannot filter files: ${error.message}" })
        }

        init {
            table.rowSorter = sorter
            find.document.addDocumentListener(object : DocumentListener {
                private fun changed() { filterPending = true; filterTimer.restart() }
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()
            })
            kinds.addActionListener { filterFiles() }
            add(JPanel(BorderLayout(6, 0)).apply {
                border = Ui.gap(4, 6, 4, 6)
                add(JLabel("Find:").apply { labelFor = find }, BorderLayout.WEST)
                add(find, BorderLayout.CENTER)
                add(kinds, BorderLayout.EAST)
                add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 2)).apply { add(previous); add(next); add(pageLabel) }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            table.autoCreateRowSorter = false
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.fillsViewportHeight = true
            table.showVerticalLines = false
            table.showHorizontalLines = false
            table.intercellSpacing = java.awt.Dimension(0, 0)
            table.setDefaultRenderer(Row::class.java, NameRenderer())
            table.setDefaultRenderer(String::class.java, StatusRenderer())
            table.setDefaultRenderer(java.lang.Long::class.java, SizeRenderer())
            table.selectionModel.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val row = selected()
                    if (row == null || row.folder) clearPreview()
                    else if (row.blocked) { clearPreview(); info.text = "Linked and special project files are blocked." }
                    else preview(row)
                }
            }
            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) selected()?.takeIf { !it.blocked }?.let { if (it.folder) openFolderNode(it.path) else preview(it) }
                }
            })
            table.registerKeyboardAction({ selected()?.takeIf { !it.blocked }?.let { if (it.folder) openFolderNode(it.path) else preview(it) } }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
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
            split.addComponentListener(object : java.awt.event.ComponentAdapter() {
                private var initialized = false
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (!initialized && split.height > 0) {
                        initialized = true
                        split.setDividerLocation(0.4)
                    }
                }
            })
            add(split, BorderLayout.CENTER)
        }

        fun show(page: Page, dir: Path) {
            this.page = page
            currentDirectory = dir
            filterReader.cancel()
            directoryRows = emptyList()
            pageIndex = 0
            pendingFile = null
            rows = emptyList()
            clearPreview()
            model.fireTableStructureChanged()
            sorter.modelStructureChanged()
            kinds.isEnabled = page == Page.SOURCE
            if (page == Page.SOURCE) Ui.columnWidths(table, 280, 130, 70, 70, 100, 80) else Ui.columnWidths(table, 320, 160, 90)
            status.message("Loading ${relativeLabel(dir)}...")
            listing.submit({
                val directory = try { project.files.directory(dir) } catch (error: java.nio.file.NoSuchFileException) { null }
                val exists = directory != null
                val entries = if (directory != null) Files.list(directory).use { paths ->
                    paths.limit(100_001).map { p ->
                        val a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        val blocked = a.isSymbolicLink || (!a.isDirectory && !a.isRegularFile) || (a.isDirectory && runCatching { project.files.checked(p) }.isFailure)
                        val relative = if (page == Page.SOURCE) project.javaRoot.relativize(p).toString().replace('\\', '/') else ""
                        Row(p.fileName.toString(), p, a.isDirectory, if (a.isDirectory) 0 else a.size(), if (a.isDirectory || blocked) null else project.verdicts[relative], blocked)
                    }.toList()
                } else emptyList()
                require(entries.size <= 100_000) { "Folder exceeds the 100,000-item index limit; use Open Folder to inspect the exported files" }
                exists to entries.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
            }, { (exists, entries) ->
                directoryRows = entries
                filterFiles(false)
            }, { error ->
                pendingFile = null
                filterTimer.stop()
                filterPending = false
                status.message("Cannot list ${relativeLabel(dir)}: ${error.message}")
                info.text = "Cannot list this folder: ${error.message}"
            })
        }

        private fun showFolderStatus(dir: Path, exists: Boolean) {
            val files = rows.count { !it.folder }
            val checked = rows.mapNotNull { it.verdict }
            status.message(
                if (!exists) "This project has no ${page.title.lowercase()} (${project.root.relativize(dir)} does not exist)"
                else if (page == Page.SOURCE && checked.isNotEmpty()) "${relativeLabel(dir)}:  $files files;  ${checked.count { it.kind == FileVerdict.Kind.VERIFIED }} verified, ${checked.count { it.kind == FileVerdict.Kind.PARTIAL }} partly verified, ${checked.count { it.kind == FileVerdict.Kind.NOT_COMPILING }} do not compile"
                else "${relativeLabel(dir)}:  ${rows.size} items",
            )
        }

        fun reveal(file: Path) {
            pendingFile = file
            if (!listing.isBusy) filterFiles(false)
        }

        fun deactivate() {
            filterTimer.stop()
            filterPending = false
            filterReader.cancel()
            listing.cancel()
            previewReader.cancel()
            pendingFile = null
        }

        fun dispose() {
            filterTimer.stop()
            filterPending = false
            filterReader.close()
            listing.close()
            previewReader.close()
        }

        fun selectFile(file: Path) {
            val k = rows.indexOfFirst { it.path == file }
            if (k < 0) return
            val view = table.convertRowIndexToView(k)
            if (view < 0) { clearPreview(); return }
            table.setRowSelectionInterval(view, view)
            table.scrollRectToVisible(table.getCellRect(view, 0, true))
        }

        private fun relativeLabel(dir: Path) = project.root.relativize(dir).toString().ifEmpty { "." }

        private fun selected(): Row? = table.selectedRow.takeIf { it >= 0 }?.let { rows[table.convertRowIndexToModel(it)] }

        private fun clearPreview() {
            previewReader.cancel()
            current = null
            viewer.text = ""
            info.text = " "
            info.toolTipText = null
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
            viewer.text = "Loading..."
            viewer.caretPosition = 0
            previewReader.submit({ FilePreview.content(row.path, project.files) }, { content ->
                viewer.text = content.text
                viewer.caretPosition = 0
                val expected = project.exportedHashes[project.root.relativize(row.path).toString().replace('\\', '/')]
                if (expected != null && content.sha256 != expected) {
                    info.text = "${row.name} — changed since recovery or not hash-checked; recorded checks do not apply"
                    info.foreground = Ui.warn
                    info.toolTipText = null
                } else if (expected == null && row.verdict != null) {
                    info.text = "${row.name} — ${row.verdict.describe()} (recorded at recovery; no source integrity record)"
                } else if (row.verdict != null) {
                    info.text = "${row.name} — ${row.verdict.describe()} (recorded at recovery; source hash matches)"
                    info.foreground = if (row.verdict.kind == FileVerdict.Kind.VERIFIED) Ui.good else Ui.warn
                }
            }, { error ->
                viewer.text = "(cannot read: ${error.message})"
                viewer.caretPosition = 0
                info.text = "${row.name} — preview unavailable; current source bytes were not checked"
                info.foreground = Ui.warn
                info.toolTipText = null
            })
            val v = row.verdict
            info.text = if (v != null) "${row.name} — checking current source bytes…" else row.name
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
            FileVerdict.Kind.VERIFIED -> "Matched at recovery"
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
            if (row.blocked) return UIManager.getIcon("FileView.fileIcon")
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
                    "Verified", "Matched at recovery" -> Ui.good
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
        val table = ReportTable(model)
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
        val debounce = javax.swing.Timer(150) { sorter.rowFilter = if (find.text.isBlank()) null else RowFilter.regexFilter("(?i)" + Regex.escape(find.text.trim())) }.apply { isRepeats = false }
        find.document.addDocumentListener(object : DocumentListener {
            fun update() { debounce.restart() }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        return find
    }
}
