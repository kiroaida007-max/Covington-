package dev.apkproject.desktop

import dev.apkproject.app.AppDiff
import dev.apkproject.app.Change
import dev.apkproject.app.ClassChange
import dev.apkproject.app.DiffMarkdown
import dev.apkproject.app.DiffReport
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * The result of comparing two builds, in its own window: the two versions side by side, then tabs
 * for the changes (ranked by how much each deserves review), the code that changed, the
 * capabilities of each build and the full report. Selecting a row shows its detail below.
 */
class DiffFrame(private val result: AppDiff.Result, private val settings: Settings) : JFrame() {
    private val r: DiffReport = result.report
    internal val tabs = JTabbedPane()

    init {
        title = "${r.old.file} → ${r.new.file} - Compare Versions - ${MainFrame.APP_NAME}"
        iconImages = AppIcon.images()
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        jMenuBar = menus()
        val top = JPanel(BorderLayout(0, 8))
        top.border = Ui.gap(10, 12, 6, 12)
        top.add(Ui.group("Versions", versions()), BorderLayout.CENTER)
        top.add(headline(), BorderLayout.SOUTH)
        tabs.addTab("Changes (${r.changes.size})", changes())
        tabs.addTab("Code (${r.code.modified} modified, ${r.code.added} added, ${r.code.removed} removed)", code())
        tabs.addTab("Capabilities", capabilities())
        tabs.addTab("Report", report())
        tabs.border = Ui.gap(0, 8, 8, 8)
        contentPane.layout = BorderLayout()
        contentPane.add(top, BorderLayout.NORTH)
        contentPane.add(tabs, BorderLayout.CENTER)
        minimumSize = Dimension(760, 520)
        // Fit the usable screen (taskbar excluded): 1180 x 820, smaller on small screens.
        val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        size = Dimension(minOf(1180, screen.width - 24).coerceAtLeast(minimumSize.width), minOf(820, screen.height - 24).coerceAtLeast(minimumSize.height))
        setLocationRelativeTo(null)
    }

    private fun menus(): JMenuBar {
        val ctrl = toolkit.menuShortcutKeyMaskEx
        val bar = JMenuBar()
        bar.add(JMenu("File").apply {
            setMnemonic(KeyEvent.VK_F)
            add(JMenuItem(Ui.action("Save Report As...", KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl or InputEvent.SHIFT_DOWN_MASK), KeyEvent.VK_A) { save() }))
            addSeparator()
            add(JMenuItem(Ui.action("Close", KeyStroke.getKeyStroke(KeyEvent.VK_W, ctrl), KeyEvent.VK_C) { dispose() }))
        })
        bar.add(JMenu("View").apply {
            setMnemonic(KeyEvent.VK_V)
            listOf("Changes", "Code", "Capabilities", "Report").forEachIndexed { k, name ->
                add(JMenuItem(Ui.action(name, KeyStroke.getKeyStroke(KeyEvent.VK_1 + k, ctrl)) { tabs.selectedIndex = k }))
            }
        })
        return bar
    }

    /** Old and new side by side, as a plain three-column list. */
    private fun versions(): JComponent {
        fun signer(v: dev.apkproject.app.VersionInfo) = v.signing?.signers?.joinToString { "${it.subject.substringBefore(',')} (SHA-256 ${it.sha256.take(12)}…, ${it.schemes.joinToString("+")})" } ?: "-"
        val rows = listOf(
            listOf("File", r.old.file, r.new.file),
            listOf("Package", r.old.packageName ?: "-", r.new.packageName ?: "-"),
            listOf("Version", "${r.old.versionName ?: "-"} (${r.old.versionCode ?: "-"})", "${r.new.versionName ?: "-"} (${r.new.versionCode ?: "-"})"),
            listOf("min / target SDK", "${r.old.minSdk ?: "-"} / ${r.old.targetSdk ?: "-"}", "${r.new.minSdk ?: "-"} / ${r.new.targetSdk ?: "-"}"),
            listOf("Signer", signer(r.old), signer(r.new)),
            listOf("Classes / methods", "${Ui.count(r.old.classes)} / ${Ui.count(r.old.methods)}", "${Ui.count(r.new.classes)} / ${Ui.count(r.new.methods)}"),
            listOf("Static risk score", r.risk?.let { "${it.oldScore} (grade ${it.oldGrade})" } ?: "-", r.risk?.let { "${it.newScore} (grade ${it.newGrade})" } ?: "-"),
        )
        val table = JTable(object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = listOf("", "Older", "Newer")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        })
        table.rowSelectionAllowed = false
        table.isFocusable = false
        Ui.columnWidths(table, 140, 420, 420)
        // A changed value is bold in the newer column.
        table.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, value, sel, foc, row, col)
                font = if (rows[row][1] != rows[row][2] && row != 0) Ui.font.deriveFont(Font.BOLD) else Ui.font
                return this
            }
        }
        val box = JPanel(BorderLayout())
        box.add(table.tableHeader, BorderLayout.NORTH)
        box.add(table, BorderLayout.CENTER)
        return box
    }

    private fun headline(): JComponent {
        val high = r.changes.count { it.attention == "HIGH" }
        val medium = r.changes.count { it.attention == "MEDIUM" }
        val verdict = JLabel(when {
            high > 0 -> "$high change${if (high == 1) "" else "s"} to review first, $medium more to review"
            medium > 0 -> "$medium change${if (medium == 1) "" else "s"} to review"
            else -> "Nothing that needs review first"
        })
        verdict.font = Ui.font.deriveFont(Font.BOLD, Ui.font.size * 1.25f)
        verdict.foreground = if (high > 0) Ui.bad else if (medium > 0) Ui.warn else Ui.good
        val panel = JPanel(GridBagLayout())
        panel.add(verdict, Ui.gbc(0, 0, insets = Insets(0, 0, 2, 0)))
        r.headline.drop(1).forEachIndexed { k, line -> panel.add(JLabel(line), Ui.gbc(0, k + 1, insets = Insets(0, 0, 0, 0))) }
        panel.add(JPanel(), Ui.gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        return panel
    }

    private fun attentionColor(level: String) = when (level) {
        "HIGH" -> Ui.bad
        "MEDIUM" -> Ui.warn
        else -> UIManager.getColor("Label.foreground")
    }

    /** A table with a read-only detail pane below it. */
    private fun master(table: JTable, detail: JTextArea, hint: String): JComponent {
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        table.rowHeight = (table.rowHeight * 1.2).toInt()
        detail.isEditable = false
        detail.lineWrap = true
        detail.wrapStyleWord = true
        detail.font = Ui.font
        detail.border = Ui.gap(6, 8, 6, 8)
        detail.text = hint
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(detail))
        split.resizeWeight = 0.72
        split.border = null
        return split
    }

    private fun changes(): JComponent {
        val items: List<Change> = r.changes
        val table = JTable(object : AbstractTableModel() {
            override fun getRowCount() = items.size
            override fun getColumnCount() = 5
            override fun getColumnName(column: Int) = listOf("Attention", "Area", "Change", "Subject", "Evidence")[column]
            override fun getValueAt(row: Int, column: Int): Any = items[row].let { listOf(it.attention, it.area, it.kind, it.subject, it.evidence)[column] }
        })
        Ui.columnWidths(table, 90, 120, 90, 520, 90)
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, value, sel, foc, row, col)
                if (!sel) foreground = attentionColor(value as String)
                font = Ui.font.deriveFont(Font.BOLD)
                return this
            }
        }
        val detail = JTextArea(5, 80)
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && table.selectedRow >= 0) {
                val c = items[table.convertRowIndexToModel(table.selectedRow)]
                detail.text = "${c.subject}\n\n${c.detail}\n\n${c.area} · ${c.kind} · attention ${c.attention} · evidence ${c.evidence}"
                detail.caretPosition = 0
            }
        }
        val view = master(table, detail, "Select a change to see its detail.")
        if (items.isNotEmpty()) table.setRowSelectionInterval(0, 0)
        return view
    }

    private fun code(): JComponent {
        val s = r.code
        val summary = JPanel(GridBagLayout())
        val cells = listOf(
            "Unchanged" to s.unchanged, "Modified" to s.modified, "Added" to s.added, "Removed" to s.removed,
            "Renamed by the obfuscator" to s.renamedByObfuscator, "Same code, references regrouped" to s.referencesOnly,
        )
        cells.forEachIndexed { k, (label, n) ->
            summary.add(JLabel("$label:"), Ui.gbc(k * 2, 0, insets = Insets(0, if (k == 0) 0 else 14, 0, 4)))
            summary.add(JLabel(Ui.count(n)).apply { font = Ui.font.deriveFont(Font.BOLD) }, Ui.gbc(k * 2 + 1, 0))
        }
        summary.add(JPanel(), Ui.gbc(cells.size * 2, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        val matched = JLabel("Paired by kept name ${Ui.count(s.matchedByName)}, by identical code ${Ui.count(s.matchedByIdenticalCode + s.matchedAmbiguously)}, by similar code ${Ui.count(s.matchedBySimilarCode)}. " +
            "Not counted as changes: ${Ui.count(s.methodsMoved)} static methods R8 moved and ${Ui.count(s.methodsSameBehaviour)} methods that differ only in layout.").apply { foreground = java.awt.Color.GRAY }
        summary.add(matched, Ui.gbc(0, 1, width = cells.size * 2 + 1, insets = Insets(6, 0, 0, 0)))

        val items: List<ClassChange> = r.classes
        val table = JTable(object : AbstractTableModel() {
            override fun getRowCount() = items.size
            override fun getColumnCount() = 6
            override fun getColumnName(column: Int) = listOf("Status", "Class", "Older name", "Newer name", "Methods mod/add/rem", "Matched by")[column]
            override fun getValueAt(row: Int, column: Int): Any = items[row].let {
                listOf(
                    it.status.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() },
                    it.label + (if (it.compilerMerged) " (R8-merged)" else "") + (if (it.onlyStaticMethods && it.status == "MODIFIED") " (static methods only)" else ""),
                    it.oldName ?: "", it.newName ?: "",
                    "${it.methodsModified.size} / ${it.methodsAdded.size} / ${it.methodsRemoved.size}",
                    it.matchedBy?.let { m -> m.replace('_', ' ').lowercase() + (it.similarity?.takeIf { x -> x < 1.0 }?.let { x -> " %.2f".format(x) } ?: "") } ?: "",
                )[column]
            }
        })
        Ui.columnWidths(table, 100, 280, 210, 210, 170, 150)
        val detail = JTextArea(7, 80)
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && table.selectedRow >= 0) {
                val c = items[table.convertRowIndexToModel(table.selectedRow)]
                detail.text = buildString {
                    append(c.label)
                    c.labelEvidence?.let { append("  (name INFERRED: ").append(it).append(')') }
                    append("\n").append(c.oldName ?: "-").append("  →  ").append(c.newName ?: "-").append('\n')
                    if (c.compilerMerged) append("R8 built this class from several; it regroups their contents between builds, so a change here is often not an edit.\n")
                    if (c.onlyStaticMethods && c.status == "MODIFIED") append("No method was edited; only static methods came or went. R8 also moves static helper methods between classes, so this is often not an edit.\n")
                    fun list(title: String, xs: List<String>) { if (xs.isNotEmpty()) append('\n').append(title).append(": ").append(xs.take(20).joinToString(", ")).append(if (xs.size > 20) ", …" else "") }
                    list("Methods modified", c.methodsModified)
                    list("Methods added", c.methodsAdded)
                    list("Methods removed", c.methodsRemoved)
                    list("New strings", c.stringsAdded.map { "\"$it\"" })
                    list("Strings no longer used", c.stringsRemoved.map { "\"$it\"" })
                    list("New framework calls", c.apisAdded)
                    list("Framework calls no longer used", c.apisRemoved)
                }
                detail.caretPosition = 0
            }
        }
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = Ui.gap(8, 4, 4, 4)
        panel.add(summary, BorderLayout.NORTH)
        panel.add(master(table, detail, "Select a class to see which methods, strings and framework calls changed."), BorderLayout.CENTER)
        return panel
    }

    private fun capabilities(): JComponent {
        val old = r.oldCapabilities.associateBy { it.id }
        val new = r.newCapabilities.associateBy { it.id }
        val ids = (r.newCapabilities.map { it.id } + r.oldCapabilities.map { it.id }).distinct()
        val rows = ids.map { id ->
            val c = new[id] ?: old.getValue(id)
            val permission = when (c.permissionDeclared) {
                true -> "requested"
                false -> "not requested (cannot run)"
                null -> if (c.permissions.isEmpty()) "none needed" else "unknown"
            }
            listOf(c.title, c.category, if (id in old) "yes" else "", if (id in new) "yes" else "", permission, c.usedIn.take(4).joinToString(", "))
        }
        val table = JTable(object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = 6
            override fun getColumnName(column: Int) = listOf("Capability", "Category", "Older", "Newer", "Permission", "Used in")[column]
            override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        })
        Ui.columnWidths(table, 260, 90, 60, 60, 170, 360)
        val sdks = (r.newSdks.map { it.name } + r.oldSdks.map { it.name }).distinct().joinToString("; ") { name ->
            val inOld = r.oldSdks.any { it.name == name }
            val inNew = r.newSdks.any { it.name == name }
            name + when { inOld && inNew -> ""; inNew -> " (new)"; else -> " (removed)" }
        }.ifEmpty { "none recognized" }
        val detail = JTextArea(4, 80)
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && table.selectedRow >= 0) {
                val id = ids[table.convertRowIndexToModel(table.selectedRow)]
                val c = new[id] ?: old.getValue(id)
                detail.text = "${c.title} (${c.reference})\n\nFramework calls: ${c.apis.joinToString(", ")}\nUsed in: ${c.usedIn.joinToString(", ")}" +
                    (if (c.permissions.isNotEmpty()) "\nNeeds one of: ${c.permissions.joinToString(", ")}" else "")
                detail.caretPosition = 0
            }
        }
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = Ui.gap(8, 4, 4, 4)
        panel.add(JLabel("<html>What the code can do with the platform, read from the framework calls it makes (names obfuscation cannot hide). Third-party SDKs: ${Ui.escape(sdks)}</html>"), BorderLayout.NORTH)
        panel.add(master(table, detail, "Select a capability to see the calls and classes behind it."), BorderLayout.CENTER)
        return panel
    }

    private fun report(): JComponent {
        val pane = JEditorPane("text/html", Markdown.page(DiffMarkdown.render(r)))
        pane.isEditable = false
        pane.caretPosition = 0
        return JScrollPane(pane)
    }

    /** Writes README.md, diff.json and code-map.tsv into a folder the user picks. */
    private fun save() {
        val chooser = JFileChooser(settings.lastFolder)
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Save Report In"
        chooser.approveButtonText = "Save"
        if (chooser.showDialog(this, null) != JFileChooser.APPROVE_OPTION) return
        val base = chooser.selectedFile.toPath().resolve("update-review-${r.old.versionCode ?: "old"}-${r.new.versionCode ?: "new"}")
        val dir = RecoverDialog.unusedFolder(base)
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("README.md"), DiffMarkdown.render(r))
            Files.writeString(dir.resolve("diff.json"), dev.apkproject.contracts.Json.encode(r))
            Files.writeString(dir.resolve("code-map.tsv"), result.classMap)
        }.onSuccess { Ui.open(this, dir) }.onFailure {
            JOptionPane.showMessageDialog(this, "The report could not be saved:\n${it.message}", "Save Report", JOptionPane.ERROR_MESSAGE)
        }
    }
}
