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
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import kotlin.system.exitProcess

/** CI verification of the real application, including images taken from the desktop with Robot. */
object DesktopCapture {
    fun run(projectPath: Path, output: Path, inputPath: Path? = null) {
        Files.createDirectories(output)
        val project = Project(projectPath.toAbsolutePath())
        check(project.report != null) { "Capture requires a readable, real recovery report" }
        val settings = Settings(null)
        val window = edt { MainWindow(Environment(output.resolve("app-data"), settings), settings).apply { isVisible = true } }
        Thread({
            try {
                val robot = Robot().apply { autoDelay = 100 }
                if (dev.apkproject.contracts.isWindows()) check(UIManager.getLookAndFeel().javaClass.name == "com.sun.java.swing.plaf.windows.WindowsLookAndFeel")
                fun capture(name: String, target: Window = window) {
                    robot.waitForIdle()
                    robot.delay(350)
                    val bounds = edt { target.bounds }
                    check(bounds.width > 0 && bounds.height > 0)
                    ImageIO.write(robot.createScreenCapture(bounds), "png", output.resolve("$name.png").toFile())
                }
                capture("01-recovery")
                edt {
                    check(!menuAction(window, "Recover").isEnabled)
                    check(!menuAction(window, "Cancel recovery").isEnabled)
                    window.open(projectPath.toAbsolutePath())
                    check(menuAction(window, "Close project").isEnabled)
                    check(window.results.project?.root == projectPath.toAbsolutePath())
                    val table = named<JTable>(window, "summaryTable")
                    check(table.getValueAt(0, 1) == if (project.report.compileCheck.status == "NOT_RUN") "Not run" else "${Ui.count(project.report.compileCheck.files - project.report.compileCheck.filesWithErrors)} / ${Ui.count(project.report.compileCheck.files)}")
                }
                capture("02-overview")
                edt {
                    window.results.selectTab("Source")
                    val tree = named<JTree>(window, "sourceTree")
                    val filter = named<JTextField>(window, "sourceFilter")
                    filter.text = "__no_file_should_have_this_name__"
                    check((tree.model.root as DefaultMutableTreeNode).childCount == 0)
                    filter.text = ""
                    val root = tree.model.root as DefaultMutableTreeNode
                    val file = root.depthFirstEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>()
                        .first { it.isLeaf && it.toString().endsWith(".java") }
                    tree.selectionPath = TreePath(file.path)
                    val editor = named<RSyntaxTextArea>(window, "sourceEditor")
                    check(editor.text.isNotBlank() && !editor.isEditable)
                    // The viewer must display an actual recovered file, not placeholder text.
                    check(Files.walk(project.javaRoot).use { paths -> paths.filter { it.fileName.toString() == file.toString() }.anyMatch { Files.readString(it) == editor.text } })
                }
                capture("03-source")
                for ((tab, name) in listOf("Backend" to "04-backend", "Names" to "05-names", "Report" to "06-report")) {
                    edt { window.results.selectTab(tab) }
                    capture(name)
                }
                if (Files.isRegularFile(project.skeleton)) {
                    fun waitFor(condition: () -> Boolean) {
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                        while (!condition()) {
                            check(System.nanoTime() < deadline) { "Mock server lifecycle timed out" }
                            robot.delay(100)
                        }
                    }
                    edt { window.results.selectTab("Backend"); named<JButton>(window, "mockToggle").doClick() }
                    waitFor { edt { named<JButton>(window, "mockToggle").text == "Stop mock server" } }
                    val address = edt { named<JButton>(window, "mockUrl").text.substringBefore(' ') }
                    val connection = java.net.URI(address).toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    try { check(connection.responseCode in 100..599) } finally { connection.disconnect() }
                    val port = java.net.URI(address).port
                    edt { window.results.closeProject() }
                    waitFor { MockServer.free(port) }
                    edt {
                        window.results.show(project)
                        window.results.selectTab("Backend")
                        // Closing while compilation is pending must also stop the stale server.
                        named<JButton>(window, "mockToggle").doClick()
                        window.results.closeProject()
                    }
                    waitFor { Thread.getAllStackTraces().keys.none { it.name == "mock-server" && it.isAlive } }
                    edt { /* Drain the queued completion/stop callback. */ }
                    check(MockServer.free(port))
                    edt { window.results.show(project) }
                }
                edt { window.select("tools") }
                capture("07-tools")
                edt {
                    // Reopening and closing must discard the old project and reset menu state.
                    repeat(3) { window.results.show(project) }
                    window.results.closeProject()
                    check(window.results.project == null && !menuAction(window, "Close project").isEnabled)
                    window.open(projectPath.toAbsolutePath())
                    window.results.selectTab("Source")
                    window.jMenuBar.getMenu(0).doClick()
                }
                capture("08-file-menu")
                edt { MenuSelectionManager.defaultManager().clearSelectedPath() }
                SwingUtilities.invokeLater { window.aboutDialog() }
                robot.delay(500)
                val dialog = edt { Window.getWindows().filterIsInstance<JDialog>().first { it.isVisible } }
                capture("09-about", dialog)
                edt { dialog.dispose() }
                if (inputPath != null) {
                    edt {
                        window.open(inputPath.toAbsolutePath())
                        named<JTextField>(window, "recoveryOutput").text = output.resolve("cancelled-project").toAbsolutePath().toString()
                        check(menuAction(window, "Recover").isEnabled)
                        menuAction(window, "Recover").actionPerformed(java.awt.event.ActionEvent(window, 0, "capture"))
                        check(menuAction(window, "Cancel recovery").isEnabled)
                        check(!menuAction(window, "Open app…").isEnabled)
                    }
                    capture("10-recovery-progress")
                    edt {
                        menuAction(window, "Cancel recovery").actionPerformed(java.awt.event.ActionEvent(window, 0, "capture"))
                        check(!menuAction(window, "Cancel recovery").isEnabled)
                    }
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
                    while (!edt { menuAction(window, "Recover").isEnabled }) {
                        check(System.nanoTime() < deadline) { "Cancelled recovery did not finish" }
                        robot.delay(100)
                    }
                    check(!Project.isProject(output.resolve("cancelled-project"))) { "Cancelled recovery published a project" }
                    edt { check(menuAction(window, "Open app…").isEnabled) }
                }
                val facts = """
                    OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}
                    Java: ${System.getProperty("java.runtime.version")}
                    Runtime: ${System.getProperty("java.home")}
                    Look and feel: ${UIManager.getLookAndFeel().javaClass.name}
                    Label font: ${UIManager.getFont("Label.font")}
                    javac: ${Environment(output.resolve("app-data"), settings).jdkHome()}
                    Project: ${project.root}
                    Checks: summary data, source filter, recovered source viewer, project reopen/close, mock-server lifecycle, menu state${if (inputPath != null) ", recovery start/cancel and output publication" else ""}
                """.trimIndent()
                Files.writeString(output.resolve("runtime.txt"), facts + "\n")
                println("Desktop capture passed: $output")
                edt { window.dispose() }
                exitProcess(0)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                Files.writeString(output.resolve("failure.txt"), failure.stackTraceToString())
                edt { Window.getWindows().forEach { it.dispose() } }
                exitProcess(1)
            }
        }, "desktop-capture").start()
    }

    private fun menuAction(window: MainWindow, title: String): Action =
        descendants(window.jMenuBar).filterIsInstance<JMenuItem>().first { it.text == title }.action!!

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is JMenu) component.menuComponents.forEach { yieldAll(descendants(it)) }
        else if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

    private inline fun <reified T : Component> named(root: Component, name: String): T =
        descendants(root).filterIsInstance<T>().first { it.name == name }

    private fun <T> edt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
