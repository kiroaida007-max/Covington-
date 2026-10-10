package dev.apkproject.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.DefaultTableModel

/** Help menu dialogs: About, the evidence levels, and what no tool can recover from an APK. */
object HelpDialogs {
    val levels = listOf(
        "PROVED" to "Z3 found no input on which the method and the original bytecode differ.",
        "ROUND-TRIP EQUIVALENT" to "Recompiled, the method has the same dataflow as the original bytecode.",
        "REPAIRED" to "A decompiler mistake was fixed, and recompiling proved the fix.",
        "VERIFIED IDENTITY" to "A library matched its published artifact, class by class.",
        "OBSERVED" to "Read from the app itself: resources, strings, names the compilers left behind.",
        "INFERRED" to "Derived from code: endpoints, library names matched by structure.",
        "SYNTHESIZED" to "Generated, never recovered: the mock server.",
    )

    fun about(owner: Frame) { aboutDialog(owner).isVisible = true }

    fun aboutDialog(owner: Frame): JDialog {
        val body = JPanel(GridBagLayout())
        body.border = Ui.gap(14, 14, 6, 14)
        body.add(JLabel(ImageIcon(AppIcon.image(48))), Ui.gbc(0, 0, insets = Insets(0, 0, 0, 14), anchor = GridBagConstraints.NORTHWEST).apply { gridheight = 3 })
        body.add(JLabel(MainFrame.APP_NAME).apply { font = Ui.font.deriveFont(Font.BOLD) }, Ui.gbc(1, 0, weightx = 1.0))
        body.add(JLabel("Version ${MainFrame.VERSION}"), Ui.gbc(1, 1))
        body.add(JLabel("<html>Turns an Android app back into an editable project, and says for every part how far it can be trusted. Everything runs on this computer; only the tools and published libraries are downloaded.</html>").apply {
            preferredSize = Dimension(380, preferredSize.height * 3)
        }, Ui.gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = Insets(8, 0, 8, 0)))
        val licenses = JTextArea(
            """
            Tools this program downloads and runs:
              JADX 1.5.6 - Apache License 2.0
              Eclipse Compiler for Java 3.45.0 - Eclipse Public License 2.0
              D8/R8 8.7.18 - BSD 3-Clause License
              Z3 4.13.3 - MIT License
              Android platform android.jar 35 - Android Software Development Kit License

            Libraries built into this program:
              FlatLaf - Apache License 2.0
              RSyntaxTextArea - BSD 3-Clause License
              Kotlin standard library, kotlinx.serialization - Apache License 2.0
            """.trimIndent(),
        )
        licenses.isEditable = false
        licenses.font = Ui.font
        licenses.rows = 11
        body.add(JScrollPane(licenses), Ui.gbc(0, 3, weightx = 1.0, fill = GridBagConstraints.BOTH, width = 2, insets = Insets(4, 0, 0, 0)))
        return build(owner, "About ${MainFrame.APP_NAME}", body)
    }

    fun evidence(owner: Frame) { evidenceDialog(owner).isVisible = true }

    fun evidenceDialog(owner: Frame): JDialog {
        val model = DefaultTableModel(levels.map { arrayOf<Any>(it.first, it.second) }.toTypedArray(), arrayOf<Any>("Level", "Meaning"))
        val table = object : JTable(model) { override fun isCellEditable(row: Int, column: Int) = false }
        table.setShowGrid(false)
        Ui.columnWidths(table, 170, 470)
        table.preferredScrollableViewportSize = Dimension(640, table.rowHeight * levels.size)
        val body = JPanel(BorderLayout(0, 8))
        body.border = Ui.gap(12, 12, 6, 12)
        body.add(JLabel("Every recovered file, method, name and layer carries one of these levels."), BorderLayout.NORTH)
        body.add(JScrollPane(table), BorderLayout.CENTER)
        return build(owner, "Evidence Levels", body)
    }

    fun limits(owner: Frame) { limitsDialog(owner).isVisible = true }

    fun limitsDialog(owner: Frame): JDialog {
        val text = JTextArea(
            """
            No tool can recover these from an APK, because they are not in it:

              - The server's own code. The app only calls its server; the Backend API page
                lists those calls, and the mock server is generated from them (SYNTHESIZED).
              - Names that R8 erased and that the compilers left no trace of.
              - Comments, formatting and the original file layout.
              - Kotlin source. Kotlin code is recovered as Java.

            Files that do not compile, and methods that do not match the bytecode, are marked
            as such in the Source Code list and in the report.
            """.trimIndent(),
        )
        text.isEditable = false
        text.font = Ui.font
        text.isOpaque = false
        val body = JPanel(BorderLayout())
        body.border = Ui.gap(12, 12, 6, 12)
        body.add(text, BorderLayout.CENTER)
        return build(owner, "What Cannot Be Recovered", body)
    }

    private fun build(owner: Frame, title: String, body: JComponent): JDialog {
        val dialog = JDialog(owner, title, true)
        val ok = Ui.button("OK") { dialog.dispose() }
        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(body, BorderLayout.CENTER)
        dialog.contentPane.add(Ui.buttonRow(ok).apply { border = Ui.gap(6, 12, 12, 12) }, BorderLayout.SOUTH)
        Ui.dialogKeys(dialog, ok)
        dialog.pack()
        dialog.isResizable = false
        dialog.setLocationRelativeTo(owner)
        return dialog
    }
}
