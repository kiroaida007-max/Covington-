package dev.apkproject.desktop

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.TransferHandler

/** A rounded surface with a hairline border: the app's basic container. */
open class Card(layout: LayoutManager = BorderLayout(), private val padding: Int = 18, private val fill: () -> Color = { Theme.surface }) : JPanel(layout) {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 14f, 14f)
        g2.color = fill()
        g2.fill(shape)
        g2.color = Theme.border
        g2.draw(shape)
        g2.dispose()
        super.paintComponent(g)
    }
}

object Ui {
    fun title(text: String) = JLabel(text).apply { font = Theme.font(22f, true); foreground = Theme.text }
    fun heading(text: String) = JLabel(text).apply { font = Theme.font(15f, true); foreground = Theme.text }
    fun muted(text: String, size: Float = 13f) = JLabel(text).apply { font = Theme.font(size); foreground = Theme.muted }

    /** A label that wraps at [width] pixels. */
    fun wrapped(text: String, width: Int = 560, color: Color = Theme.muted, size: Float = 13f) =
        JLabel("<html><div style='width:${width}px'>${escape(text)}</div></html>").apply { font = Theme.font(size); foreground = color }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun primary(text: String, action: () -> Unit) = JButton(text).apply {
        putClientProperty("JButton.buttonType", "roundRect")
        putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #ffffff; borderWidth: 0; focusWidth: 0; font: bold +1; margin: 8,22,8,22; hoverBackground: #1D4ED8; pressedBackground: #1E40AF")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    fun secondary(text: String, action: () -> Unit) = JButton(text).apply {
        putClientProperty("FlatLaf.style", "margin: 6,14,6,14")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    fun link(text: String, action: () -> Unit) = JButton(text).apply {
        putClientProperty("JButton.buttonType", "borderless")
        foreground = Theme.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    fun row(vararg items: JComponent, gap: Int = 8) = JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
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

/** A colored pill: "Installed", "Does not compile", "INFERRED". */
class Chip(text: String, private val fg: () -> Color, private val bg: () -> Color) : JLabel(text) {
    init {
        font = Theme.font(12f, true)
        border = BorderFactory.createEmptyBorder(3, 10, 3, 10)
        horizontalAlignment = SwingConstants.CENTER
    }

    override fun paintComponent(g: Graphics) {
        foreground = fg()
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bg()
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), height.toFloat(), height.toFloat()))
        g2.dispose()
        super.paintComponent(g)
    }

    companion object {
        fun good(text: String) = Chip(text, { Theme.good }, { Theme.goodSoft })
        fun warn(text: String) = Chip(text, { Theme.warn }, { Theme.warnSoft })
        fun bad(text: String) = Chip(text, { Theme.bad }, { Theme.badSoft })
        fun info(text: String) = Chip(text, { Theme.accent }, { Theme.accentSoft })
    }
}

/** A number with a label and an optional share bar: "19,189 of 35,212 methods verified". */
class StatTile(label: String) : Card(GridBagLayout(), 16) {
    private val value = JLabel("—").apply { font = Theme.font(24f, true); foreground = Theme.text }
    private val caption = Ui.muted(label, 12.5f)
    private val detail = Ui.muted("", 12f)
    private var share: Double? = null
    private var shareColor: () -> Color = { Theme.accent }

    init {
        val c = GridBagConstraints().apply { gridx = 0; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL }
        add(caption, c.apply { gridy = 0 })
        add(value, c.apply { gridy = 1; insets = Insets(4, 0, 0, 0) })
        add(detail, c.apply { gridy = 2; insets = Insets(2, 0, 10, 0) })
        preferredSize = Dimension(200, 118)
    }

    fun set(value: String, detail: String = "", share: Double? = null, color: () -> Color = { Theme.accent }) {
        this.value.text = value
        this.detail.text = detail
        this.share = share
        this.shareColor = color
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val s = share ?: return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val x = 16f
        val w = width - 32f
        val y = height - 20f
        g2.color = Theme.border
        g2.fill(RoundRectangle2D.Float(x, y, w, 6f, 6f, 6f))
        g2.color = shareColor()
        g2.fill(RoundRectangle2D.Float(x, y, (w * s.coerceIn(0.0, 1.0)).toFloat(), 6f, 6f, 6f))
    }
}

/** The big target an APK is dropped on; clicking it opens the file dialog. */
class DropZone(private val onFile: (Path) -> Unit, private val onBrowse: () -> Unit) : JPanel(GridBagLayout()) {
    private var hover = false
    val headline = JLabel("Drop an Android app here").apply { font = Theme.font(17f, true); foreground = Theme.text }
    val hint = Ui.muted("APK, AAB, APKS, XAPK, APKM or DEX")

    init {
        isOpaque = false
        preferredSize = Dimension(560, 190)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val c = GridBagConstraints().apply { gridx = 0 }
        add(JLabel(Icons.upload(44)), c.apply { gridy = 0; insets = Insets(0, 0, 10, 0) })
        add(headline, c.apply { gridy = 1; insets = Insets(0, 0, 4, 0) })
        add(hint, c.apply { gridy = 2; insets = Insets(0, 0, 12, 0) })
        add(Ui.secondary("Choose a file…") { onBrowse() }, c.apply { gridy = 3 })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onBrowse()
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                val ok = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                if (hover != ok) { hover = ok; repaint() }
                return ok
            }

            override fun importData(support: TransferSupport): Boolean {
                hover = false
                repaint()
                @Suppress("UNCHECKED_CAST")
                val files = runCatching { support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull() ?: return false
                val file = files.firstOrNull { it.isFile } ?: return false
                onFile(file.toPath())
                return true
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val shape = RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 18f, 18f)
        g2.color = if (hover) Theme.accentSoft else Theme.surface
        g2.fill(shape)
        g2.color = if (hover) Theme.accent else Theme.border
        g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(7f, 6f), 0f)
        g2.draw(shape)
        g2.dispose()
        super.paintComponent(g)
    }
}
