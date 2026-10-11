package dev.apkproject.desktop

import dev.apkproject.app.UpdateVerifier
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

/** Verification only: installation stays with Windows' installer and the user's normal controls. */
class UpdatePackageDialog(owner: Frame) : JDialog(owner, "Verify Update Package", true) {
    private val manifest = JTextField(42)
    private val signature = JTextField(42)
    private val installer = JTextField(42)
    private val status = JTextArea(4, 55).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; background = UIManager.getColor("Panel.background") }
    private val key = javaClass.getResourceAsStream("/update-public-key.der")?.use { it.readNBytes(257) }

    init {
        layout = BorderLayout(8, 8); rootPane.border = Ui.gap(12, 12, 12, 12)
        val fields = JPanel(GridBagLayout())
        listOf("Release manifest:" to manifest, "Manifest signature:" to signature, "Installer or ZIP:" to installer).forEachIndexed { row, (label, field) ->
            fields.add(JLabel(label), Ui.gbc(0, row)); fields.add(field, Ui.gbc(1, row, weightx = 1.0, fill = java.awt.GridBagConstraints.HORIZONTAL))
            fields.add(JButton("Browse...").apply { addActionListener { val chooser = JFileChooser(); if (chooser.showOpenDialog(this@UpdatePackageDialog) == JFileChooser.APPROVE_OPTION) field.text = chooser.selectedFile.absolutePath } }, Ui.gbc(2, row))
        }
        add(fields, BorderLayout.NORTH); add(status, BorderLayout.CENTER)
        val verify = JButton("Verify").apply { name = "verifyUpdateButton"; isEnabled = key != null; addActionListener { verify(this) } }
        val close = JButton("Close").apply { addActionListener { dispose() } }
        add(Ui.buttonRow(verify, close), BorderLayout.SOUTH)
        status.text = if (key == null) "This development build has no configured release signing key. Signed release builds pin the publisher's key here." else "Select a newer release's manifest, its detached signature, and the downloaded package."
        Ui.dialogKeys(this, close) { dispose() }; pack(); setLocationRelativeTo(owner)
    }

    private fun verify(button: JButton) {
        val paths = listOf(manifest.text, signature.text, installer.text)
        button.isEnabled = false; status.text = "Checking signature and package bytes..."
        Thread({
            val result = runCatching {
                val preferences = java.util.prefs.Preferences.userRoot().node("dev/apkproject/updates")
                val m = UpdateVerifier.verify(Files.newInputStream(Path.of(paths[0])).use { it.readNBytes(65537) },
                    Files.newInputStream(Path.of(paths[1])).use { it.readNBytes(65) }, key!!, MainFrame.VERSION, preferences.getLong("highestReleaseId", 0))
                val asset = UpdateVerifier.verifyAsset(m, Path.of(paths[2]))
                preferences.putLong("highestReleaseId", maxOf(m.releaseId, preferences.getLong("highestReleaseId", 0)))
                "Verified ${m.version}: ${asset.name}. Signature, package hash, expiry and rollback checks passed."
            }
            SwingUtilities.invokeLater { button.isEnabled = true; status.text = result.getOrElse { "Verification failed: ${it.message}" } }
        }, "verify-update").apply { isDaemon = true }.start()
    }
}
