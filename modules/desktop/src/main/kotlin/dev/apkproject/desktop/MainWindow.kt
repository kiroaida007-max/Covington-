package dev.apkproject.desktop

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

class MainWindow(private val env: Environment, private val settings: Settings) : JFrame("APK Project Studio") {
    private val cards = CardLayout()
    private val pages = JPanel(cards)
    private val nav = LinkedHashMap<String, NavItem>()
    private val results = ResultsPanel(env)
    private val tools = ToolsPanel(env, settings) { updateToolsBadge() }
    private val recover = RecoverPanel(env, settings, { project -> results.show(project); select("results") }, { select("tools") })

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { Theme.appImage(it) }
        minimumSize = Dimension(980, 660)
        size = Dimension(1260, 860)
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { results.shutdown() }
        })

        pages.background = Theme.background
        pages.add(recover, "recover")
        pages.add(results, "results")
        pages.add(tools, "tools")
        pages.add(about(), "about")

        val side = object : JPanel(GridBagLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = Theme.sidebar
                g.fillRect(0, 0, width, height)
                g.color = Theme.border
                g.fillRect(width - 1, 0, 1, height)
            }
        }
        side.preferredSize = Dimension(214, 0)
        val c = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH }
        val brand = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(20, 18, 22, 12)
            add(JLabel(ImageIcon(Theme.appImage(34))), BorderLayout.WEST)
            add(Ui.column(JLabel("APK Project").apply { font = Theme.font(15f, true) }, Ui.muted("Studio", 12f), gap = 0), BorderLayout.CENTER)
        }
        side.add(brand, c.apply { gridy = 0 })
        var y = 1
        fun item(key: String, title: String, icon: (Boolean) -> Icon) {
            val n = NavItem(title, icon) { select(key) }
            nav[key] = n
            side.add(n, c.apply { gridy = y++; insets = Insets(2, 10, 2, 10) })
        }
        item("recover", "Recover", { selected -> Icons.recover(18) { if (selected) Theme.accent else Theme.muted } })
        item("results", "Results", { selected -> Icons.results(18) { if (selected) Theme.accent else Theme.muted } })
        item("tools", "Tools", { selected -> Icons.tools(18) { if (selected) Theme.accent else Theme.muted } })
        item("about", "About", { selected -> Icons.info(18) { if (selected) Theme.accent else Theme.muted } })
        side.add(JPanel().apply { isOpaque = false }, c.apply { gridy = y++; weighty = 1.0 })
        val dark = JCheckBox("Dark theme", settings.darkTheme).apply {
            isOpaque = false
            font = Theme.font(12.5f)
            addActionListener {
                settings.darkTheme = isSelected
                Theme.install(isSelected)
                SwingUtilities.updateComponentTreeUI(this@MainWindow)
                this@MainWindow.repaint()
            }
        }
        side.add(dark, c.apply { gridy = y++; weighty = 0.0; insets = Insets(0, 18, 6, 10) })
        side.add(Ui.muted("Version $VERSION", 11.5f), c.apply { gridy = y; insets = Insets(0, 20, 16, 10) })

        contentPane = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) { g.color = Theme.background; g.fillRect(0, 0, width, height) }
        }
        contentPane.add(side, BorderLayout.WEST)
        contentPane.add(pages, BorderLayout.CENTER)
        pages.isOpaque = false
        select(if (tools.ready()) "recover" else "tools")
    }

    fun select(key: String) {
        cards.show(pages, key)
        nav.forEach { (k, n) -> n.selected = k == key }
        if (key == "tools") tools.refresh()
    }

    /** Opens what the app was started with: an Android app to recover, or a recovered project. */
    fun open(path: Path) {
        when {
            Files.isDirectory(path) && Project.isProject(path) -> { results.show(Project(path)); select("results") }
            Files.isRegularFile(path) -> { recover.choose(path); select("recover") }
        }
    }

    private fun updateToolsBadge() {
        nav["tools"]?.badge = !tools.ready()
    }

    private fun about(): JComponent {
        val p = JPanel(GridBagLayout()).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(28, 36, 36, 36) }
        val c = GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST }
        p.add(Ui.title("About"), c.apply { gridy = 0 })
        p.add(Ui.wrapped("APK Project Studio turns an Android app back into an editable project and says, for every part, how far it can be trusted. It runs entirely on this computer; only the tools themselves and published libraries are downloaded.", 760, size = 14f), c.apply { gridy = 1; insets = Insets(6, 0, 18, 0) })
        val legend = Card(GridBagLayout())
        val lc = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = Insets(5, 0, 5, 14) }
        val levels = listOf(
            "PROVED" to "Z3 found no input on which the method and the bytecode differ.",
            "ROUND-TRIP EQUIVALENT" to "Recompiled, the method has the same dataflow as the original bytecode.",
            "REPAIRED" to "A decompiler mistake was fixed, and recompiling proved the fix.",
            "VERIFIED IDENTITY" to "A library matched its published artifact, class by class.",
            "OBSERVED" to "Read from the app itself: resources, strings, names the compilers left behind.",
            "INFERRED" to "Derived from code: endpoints, library names matched by structure.",
            "SYNTHESIZED" to "Generated, never recovered: the mock server.",
        )
        legend.add(Ui.heading("Evidence levels"), lc.apply { gridx = 0; gridy = 0; gridwidth = 2 })
        lc.gridwidth = 1
        levels.forEachIndexed { k, (level, meaning) ->
            legend.add(Chip.info(level), lc.apply { gridx = 0; gridy = k + 1 })
            legend.add(Ui.muted(meaning), lc.apply { gridx = 1; weightx = 1.0 })
            lc.weightx = 0.0
        }
        p.add(legend, c.apply { gridy = 2 })
        p.add(Ui.wrapped("Not recoverable from any APK: the server's own code (the app only calls it), names R8 erased without a trace, comments and formatting. Kotlin is recovered as Java. Bundled tools: JADX (Apache 2.0), Eclipse compiler for Java (EPL 2.0), D8/R8 (BSD 3-clause), Z3 (MIT), Android platform android.jar (Android SDK license), FlatLaf and RSyntaxTextArea (Apache 2.0 / BSD).", 760), c.apply { gridy = 3; insets = Insets(18, 0, 0, 0) })
        p.add(JPanel().apply { isOpaque = false }, c.apply { gridy = 4; weighty = 1.0 })
        return p
    }

    /** A sidebar entry: icon and title, highlighted when selected, with an optional attention dot. */
    private class NavItem(title: String, private val icon: (Boolean) -> Icon, onClick: () -> Unit) : JPanel(BorderLayout(10, 0)) {
        private val label = JLabel(title).apply { font = Theme.font(14f) }
        private val iconLabel = JLabel()
        private var hover = false
        var selected = false
            set(value) { field = value; label.font = Theme.font(14f, value); label.foreground = if (value) Theme.text else Theme.muted; iconLabel.icon = icon(value); repaint() }
        var badge = false
            set(value) { field = value; repaint() }

        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(9, 12, 9, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(iconLabel, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
            selected = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (selected || hover) {
                g2.color = if (selected) Theme.accentSoft else Color(Theme.border.red, Theme.border.green, Theme.border.blue, 110)
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
            }
            if (selected) {
                g2.color = Theme.accent
                g2.fill(RoundRectangle2D.Float(0f, height * 0.25f, 3f, height * 0.5f, 3f, 3f))
            }
            if (badge) {
                g2.color = Theme.warn
                g2.fillOval(width - 18, height / 2 - 4, 8, 8)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }

    companion object {
        const val VERSION = "0.2.0"
    }
}
