package dev.apkproject.desktop

import java.awt.GraphicsEnvironment
import java.nio.file.Paths
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * APK Project Studio, the desktop front end of `apkproject`. Started with a path (or through the
 * .apk file association) it opens it: an Android app is offered for recovery, a recovered
 * project folder is shown.
 */
fun main(args: Array<String>) {
    // Downloads go through the system's proxy settings (Windows Internet Options).
    System.setProperty("java.net.useSystemProxies", "true")
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("APK Project Studio needs a display. Use the apkproject command line instead (apkproject decompile app.apk --out app-src).")
        exitProcess(2)
    }
    Ui.installLookAndFeel()
    val settings = Settings()
    SwingUtilities.invokeLater {
        val frame = MainFrame(Environment(Environment.appDataDir(), settings), settings)
        frame.isVisible = true
        val path = args.firstOrNull { !it.startsWith("-") }?.let { Paths.get(it) }
        if (path != null) frame.open(path) else frame.checkTools()
    }
}
