package dev.apkproject.desktop

import java.awt.Component
import java.awt.Container
import java.awt.Robot
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.*
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import kotlin.system.exitProcess

/** Runs the shipped desktop UI, asserts its behavior and captures native desktop pixels. */
object DesktopCapture {
    fun run(projectPath: Path, output: Path, input: Path?) {
        Files.createDirectories(output)
        val project = Project(projectPath.toAbsolutePath())
        check(project.report != null) { "A real recovery report is required" }
        val settings = Settings(null)
        val env = Environment(output.resolve("app-data"), settings)
        val frame = edt { MainFrame(env, settings).apply { isVisible = true } }
        Thread({
            try {
                val robot = Robot()
                fun waitFor(condition: () -> Boolean) {
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
                    while (!condition()) { check(System.nanoTime() < deadline) { "UI operation timed out" }; robot.delay(100) }
                }
                fun shoot(name: String, window: Window = frame) {
                    robot.waitForIdle(); robot.delay(350)
                    ImageIO.write(robot.createScreenCapture(edt { window.bounds }), "png", output.resolve("$name.png").toFile())
                }
                fun dialog(name: String, make: () -> JDialog) {
                    val d = edt { make() }
                    SwingUtilities.invokeLater { d.isVisible = true }
                    waitFor { edt { d.isVisible } }
                    shoot(name, d)
                    edt { d.dispose() }
                }
                if (dev.apkproject.contracts.isWindows()) check(UIManager.getLookAndFeel().javaClass.name == "com.sun.java.swing.plaf.windows.WindowsLookAndFeel")
                shoot("01-start")
                edt {
                    check(!action(frame, "Close Project").isEnabled)
                    frame.show(project)
                    check(action(frame, "Close Project").isEnabled)
                    check(frame.projectView?.project === project)
                }
                shoot("02-summary")
                val source = Files.walk(project.javaRoot).use { it.filter { p -> p.toString().endsWith(".java") }.findFirst().orElseThrow() }
                edt {
                    frame.projectView!!.reveal(source)
                    val table = named<JTable>(frame, "sourceTable")
                    val filter = named<JTextField>(frame, "sourceFilter")
                    filter.text = "__nonexistent_source__"
                    check(table.rowCount == 0)
                    filter.text = ""
                    frame.projectView!!.reveal(source)
                    val editor = named<RSyntaxTextArea>(frame, "sourceEditor")
                    check(!editor.isEditable && editor.text == Files.readString(source))
                }
                shoot("03-source")
                for ((page, name) in listOf(ProjectView.Page.RESOURCES to "04-resources", ProjectView.Page.BACKEND to "05-backend", ProjectView.Page.NAMES to "06-names", ProjectView.Page.REPORT to "07-report")) {
                    edt { frame.select(page) }; shoot(name)
                }
                if (Files.isRegularFile(project.skeleton) && project.contract != null) {
                    edt { frame.select(ProjectView.Page.BACKEND); named<JButton>(frame, "mockStart").doClick() }
                    waitFor { edt { named<JLabel>(frame, "mockState").text.startsWith("Running at ") } }
                    val address = edt { named<JLabel>(frame, "mockState").text.removePrefix("Running at ") }
                    val connection = java.net.URI(address).toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    try { check(connection.responseCode in setOf(200, 404, 501)) } finally { connection.disconnect() }
                    val port = java.net.URI(address).port
                    edt { action(frame, "Close Project").actionPerformed(java.awt.event.ActionEvent(frame, 0, "capture")) }
                    waitFor { MockServer.free(port) }
                    edt { frame.show(project); frame.select(ProjectView.Page.BACKEND); named<JButton>(frame, "mockStart").doClick(); action(frame, "Close Project").actionPerformed(java.awt.event.ActionEvent(frame, 0, "capture")) }
                    waitFor { Thread.getAllStackTraces().keys.none { it.name == "mock-server-start" && it.isAlive } }
                    edt { /* Flush startup completion and stale-server cleanup. */ }
                    check(MockServer.free(port))
                }
                edt {
                    repeat(3) { frame.show(project) }
                    action(frame, "Close Project").actionPerformed(java.awt.event.ActionEvent(frame, 0, "capture"))
                    check(frame.projectView == null && !action(frame, "Close Project").isEnabled)
                    frame.show(project); frame.projectView!!.reveal(source); frame.jMenuBar.getMenu(0).doClick()
                }
                shoot("08-file-menu")
                edt { MenuSelectionManager.defaultManager().clearSelectedPath() }
                dialog("09-recover-dialog") { RecoverDialog(frame, settings, input) }
                if (input != null) {
                    val target = output.resolve("cancelled-project").toAbsolutePath()
                    val request = RecoveryRequest(input.toAbsolutePath(), target, false, true, false, true, false)
                    val recovery = edt { RecoveryDialog(frame, env, request) {} }
                    SwingUtilities.invokeLater { recovery.run() }
                    waitFor { edt { recovery.isVisible && recovery.running } }
                    shoot("10-progress-dialog", recovery)
                    edt { recovery.cancelRun() }
                    waitFor { edt { !recovery.running } }
                    edt { Window.getWindows().filterIsInstance<JDialog>().filter { it.isVisible }.forEach { it.dispose() } }
                    check(!Project.isProject(target)) { "Cancelled recovery published a project" }
                }
                dialog("11-tools-dialog") { ToolsDialog(frame, env, settings) }
                dialog("12-about") { HelpDialogs.aboutDialog(frame) }
                dialog("13-evidence") { HelpDialogs.evidenceDialog(frame) }
                Files.writeString(output.resolve("runtime.txt"), "OS: ${System.getProperty("os.name")}\nJava: ${System.getProperty("java.runtime.version")}\nRuntime: ${System.getProperty("java.home")}\nLook and feel: ${UIManager.getLookAndFeel().javaClass.name}\nFont: ${UIManager.getFont("Label.font")}\nChecks: source content/filter, close/reopen/menu state, mock-server lifecycle, recovery cancellation/output publication\n")
                edt { frame.dispose() }
                println("Desktop capture passed: $output")
                exitProcess(0)
            } catch (failure: Throwable) {
                failure.printStackTrace(); Files.writeString(output.resolve("failure.txt"), failure.stackTraceToString())
                edt { Window.getWindows().forEach { it.dispose() } }; exitProcess(1)
            }
        }, "desktop-capture").start()
    }
    private fun action(frame: MainFrame, title: String) = descendants(frame.jMenuBar).filterIsInstance<JMenuItem>().first { it.text == title }.action!!
    private fun descendants(c: Component): Sequence<Component> = sequence {
        yield(c)
        if (c is JMenu) c.menuComponents.forEach { yieldAll(descendants(it)) }
        else if (c is Container) c.components.forEach { yieldAll(descendants(it)) }
    }
    private inline fun <reified T : Component> named(root: Component, name: String): T = descendants(root).filterIsInstance<T>().first { it.name == name }
    private fun <T> edt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(action); SwingUtilities.invokeAndWait(task); return task.get()
    }
}
