package dev.apkproject.desktop

import java.awt.GraphicsEnvironment
import java.nio.file.Paths
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * APK Project Studio: the desktop front end of `apkproject`. Started with a path, it opens it:
 * an Android app is queued for recovery, a recovered project folder is shown.
 */
fun main(args: Array<String>) {
    // Downloads go through the system's proxy settings (Windows Internet Options).
    System.setProperty("java.net.useSystemProxies", "true")
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("APK Project Studio needs a display. Use the apkproject command line instead (apkproject decompile app.apk --out app-src).")
        exitProcess(2)
    }
    val settings = Settings()
    Theme.install(settings.darkTheme)
    SwingUtilities.invokeLater {
        val window = MainWindow(Environment(Environment.appDataDir(), settings), settings)
        window.isVisible = true
        args.firstOrNull { !it.startsWith("-") }?.let { window.open(Paths.get(it)) }
    }
}
