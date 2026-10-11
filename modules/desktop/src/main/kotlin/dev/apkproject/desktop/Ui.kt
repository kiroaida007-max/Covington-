package dev.apkproject.desktop

import com.formdev.flatlaf.FlatLightLaf
import dev.apkproject.contracts.isWindows
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.net.URI
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.UIManager
import javax.swing.border.Border

/** Platform look, fonts, the few status colors, and small Swing helpers. */
object Ui {
    /**
     * The operating system's own look: native Windows controls on Windows and Aqua on macOS.
     * Linux has no native Swing look worth the name, so it gets FlatLaf's plain light theme.
     */
    fun installLookAndFeel() {
        val os = System.getProperty("os.name").lowercase()
        val native = isWindows() || os.contains("mac")
        val installed = native && runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }.isSuccess
        if (!installed) FlatLightLaf.setup()
    }

    val font: Font get() = UIManager.getFont("Label.font") ?: Font(Font.DIALOG, Font.PLAIN, 12)

    /** Consolas on Windows, else the first installed of a few common code fonts. */
    val monospace: Font by lazy {
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val family = listOf("Consolas", "Cascadia Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono").firstOrNull { it in installed } ?: Font.MONOSPACED
        Font(family, Font.PLAIN, font.size + 1)
    }

    // Text colors for verdicts; dark enough to read on a white background.
    val good = Color(0x0E7A0D)
    val warn = Color(0x8A5300)
    val bad = Color(0xC42B1C)

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes bytes"
    }

    fun count(n: Int): String = String.format("%,d", n)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    fun percent(part: Int, whole: Int): String = if (whole == 0) "" else String.format("%.1f%%", 100.0 * part / whole)

    fun action(name: String, key: KeyStroke? = null, mnemonic: Int? = null, run: () -> Unit): Action = object : AbstractAction(name) {
        init {
            key?.let { putValue(Action.ACCELERATOR_KEY, it) }
            mnemonic?.let { putValue(Action.MNEMONIC_KEY, it) }
        }
        override fun actionPerformed(e: ActionEvent) = run()
    }

    fun button(text: String, mnemonic: Int? = null, run: () -> Unit) = JButton(text).apply {
        mnemonic?.let { setMnemonic(it) }
        addActionListener { run() }
    }

    /** OK/Cancel-style buttons, right-aligned and equally wide, as Windows dialogs have them. */
    fun buttonRow(vararg buttons: JButton): JPanel {
        val width = maxOf(buttons.maxOf { it.preferredSize.width }, 80)
        buttons.forEach { it.preferredSize = java.awt.Dimension(width, it.preferredSize.height) }
        return JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { buttons.forEach { add(it) } }
    }

    /** Escape closes the dialog; Enter presses [default]. */
    fun dialogKeys(dialog: JDialog, default: JButton?, onEscape: () -> Unit = { dialog.dispose() }) {
        dialog.rootPane.defaultButton = default
        dialog.rootPane.registerKeyboardAction({ onEscape() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
    }

    /** A Windows group box: an etched frame with its caption on the line. */
    fun group(title: String, content: JComponent): JComponent {
        content.border = BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), BorderFactory.createEmptyBorder(4, 8, 8, 8))
        return content
    }

    fun gap(top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0): Border = BorderFactory.createEmptyBorder(top, left, bottom, right)

    fun gbc(x: Int, y: Int, weightx: Double = 0.0, fill: Int = GridBagConstraints.NONE, insets: Insets = Insets(2, 0, 2, 8), anchor: Int = GridBagConstraints.WEST, width: Int = 1) =
        GridBagConstraints().also {
            it.gridx = x; it.gridy = y; it.weightx = weightx; it.fill = fill; it.insets = insets; it.anchor = anchor; it.gridwidth = width
        }

    fun columnWidths(table: JTable, vararg widths: Int) = widths.forEachIndexed { k, w -> table.columnModel.getColumn(k).preferredWidth = w }

    /** Opens a file or folder with its Windows association (Explorer for folders). */
    fun open(parent: Component, path: Path) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) && runCatching { Desktop.getDesktop().open(path.toFile()) }.isSuccess) return
        JOptionPane.showMessageDialog(parent, path.toString(), "Location", JOptionPane.INFORMATION_MESSAGE)
    }

    fun browse(parent: Component, url: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) && runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess) return
        JOptionPane.showMessageDialog(parent, url, "Address", JOptionPane.INFORMATION_MESSAGE)
    }
}
