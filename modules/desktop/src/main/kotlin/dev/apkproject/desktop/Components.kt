package dev.apkproject.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path
import javax.swing.*

/** Standard desktop group box. No painted surfaces or custom button skins. */
class GroupBox(layout: LayoutManager = BorderLayout(), padding: Int = 8, title: String = "") : JPanel(layout) {
    init {
        border = BorderFactory.createCompoundBorder(
            if (title.isEmpty()) BorderFactory.createEtchedBorder() else BorderFactory.createTitledBorder(title),
            BorderFactory.createEmptyBorder(padding, padding, padding, padding),
        )
    }
}

object Ui {
    fun title(text: String) = JLabel(text).apply { font = Theme.font(bold = true) }
    fun heading(text: String) = title(text)
    fun muted(text: String, size: Float = Theme.font().size2D) = JLabel(text).apply { font = Theme.font(size) }
    fun wrapped(text: String, width: Int = 560, color: Color = Theme.text, size: Float = Theme.font().size2D) =
        JLabel("<html><div style='width:${width}px'>${escape(text)}</div></html>").apply { font = Theme.font(size); foreground = color }
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun primary(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }
    fun secondary(text: String, action: () -> Unit) = primary(text, action)
    fun link(text: String, action: () -> Unit) = primary(text, action)
    fun row(vararg items: JComponent, gap: Int = 6) = JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
        isOpaque = false
        items.forEach { add(it) }
    }
    fun column(vararg items: JComponent, gap: Int = 6) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        items.forEachIndexed { i, c ->
            c.alignmentX = 0f
            if (i > 0) add(Box.createVerticalStrut(gap))
            add(c)
        }
    }
    fun filterLabel(field: JTextField) = JLabel("Filter:").apply { labelFor = field; displayedMnemonic = 'F'.code }
    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
    fun count(n: Int): String = String.format("%,d", n)
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }
}

/** Text and a small status glyph, including a readable status for color-blind users. */
class StatusLabel(text: String, icon: Icon) : JLabel(text, icon, SwingConstants.LEFT) {
    companion object {
        fun good(text: String) = StatusLabel(text, Icons.check(16))
        fun warn(text: String) = StatusLabel(text, Icons.warning(16))
        fun bad(text: String) = StatusLabel(text, Icons.cross(16))
        fun info(text: String) = StatusLabel(text, UIManager.getIcon("FileView.fileIcon") ?: Icons.pending(16))
    }
}

/** Compact input group with a keyboard-accessible file picker and drag-and-drop support. */
class DropZone(private val onFile: (Path) -> Unit, onBrowse: () -> Unit) : JPanel(BorderLayout(6, 6)) {
    init {
        border = BorderFactory.createTitledBorder("Input app")
        add(Ui.column(JLabel("Choose a file, or drop it here."), Ui.muted("APK, AAB, APKS, XAPK, APKM or DEX")), BorderLayout.CENTER)
        add(Ui.secondary("Browse…", onBrowse).apply { mnemonic = 'B'.code }, BorderLayout.EAST)
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = isEnabled && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = runCatching { support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull() ?: return false
                val file = files.firstOrNull { it.isFile } ?: return false
                onFile(file.toPath())
                return true
            }
        }
    }
}

/** Native table with readable row spacing and complete text available for clipped cells. */
class ReportTable(model: javax.swing.table.TableModel) : JTable(model) {
    init {
        fillsViewportHeight = true
        rowHeight = maxOf(rowHeight, getFontMetrics(font).height + 4)
        toolTipText = ""
    }
    override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
        val row = rowAtPoint(event.point)
        val column = columnAtPoint(event.point)
        if (row < 0 || column < 0) return null
        return "<html><div style='width:500px'>${Ui.escape(getValueAt(row, column)?.toString().orEmpty())}</div></html>"
    }
}
