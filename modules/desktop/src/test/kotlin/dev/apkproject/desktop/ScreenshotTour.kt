package dev.apkproject.desktop

import dev.apkproject.app.FullRecovery
import dev.apkproject.app.FullRecoveryOptions
import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ForwardCompiler
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Opens every screen and dialog of the desktop app and saves a screenshot of each, so the look can
 * be checked on a real Windows desktop (CI) as well as under Xvfb. It first recovers a small demo
 * app with the installed tools, so the screens show a real project.
 *
 * `./gradlew :desktop:screenshots` (needs a display and the tools; APKPROJECT_TOOLS points to them).
 */
object ScreenshotTour {
    private val demo = mapOf(
        "com/example/notes/NotesClient.java" to """
            package com.example.notes;

            public class NotesClient {
                public static final String BASE = "https://api.example.com/v2/";
                public static final String NOTES = BASE + "notes";
                private float scale;

                public static int clamp(int x, int lo, int hi) {
                    if (x < lo) {
                        return lo;
                    }
                    if (x > hi) {
                        return hi;
                    }
                    return x;
                }

                public void onSurfaceChanged(int width, int height) {
                    float ratio = (float) width / height;
                    this.scale = ratio * 2.0f;
                }

                public String title(String name, int copies) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < copies; i++) {
                        sb.append(name).append(i);
                    }
                    return sb.toString();
                }

                public native int nativeChecksum(byte[] data);
            }
        """.trimIndent(),
        "com/example/notes/model/Note.java" to """
            package com.example.notes.model;

            public final class Note {
                private final long id;
                private final String title;
                private final boolean pinned;

                public Note(long id, String title, boolean pinned) {
                    this.id = id;
                    this.title = title;
                    this.pinned = pinned;
                }

                public long getId() { return id; }
                public String getTitle() { return title; }
                public boolean getPinned() { return pinned; }

                public int hashCode() {
                    return ((Long.hashCode(id) * 31) + title.hashCode()) * 31 + Boolean.hashCode(pinned);
                }

                public String toString() {
                    return "Note(id=" + id + ", title=" + title + ", pinned=" + pinned + ")";
                }
            }
        """.trimIndent(),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // The Swing thread keeps the JVM alive; a failed step must still end the run.
        val ok = runCatching { tour(args) }.onFailure { it.printStackTrace() }.isSuccess
        exitProcess(if (ok) 0 else 1)
    }

    private fun tour(args: Array<String>) {
        val out = Paths.get(args.getOrElse(0) { "build/screenshots" }).toAbsolutePath()
        Files.createDirectories(out)
        System.setProperty("java.net.useSystemProxies", "true")
        Ui.installLookAndFeel()
        val settings = Settings(null)
        val env = Environment(Files.createTempDirectory("apkproject-tour"), settings)
        val logs = ArrayList<String>()
        val (apk, project) = runCatching { demoProject(env, out.resolveSibling("tour-work"), logs) }.getOrElse {
            println("demo recovery failed: ${it.stackTraceToString()}")
            null to null
        }
        println("demo project: $project")

        lateinit var frame: MainFrame
        onEdt { frame = MainFrame(env, settings); frame.isVisible = true }
        shoot(frame, out, "01-start")
        if (project != null) {
            onEdt { frame.show(Project(project)) }
            shoot(frame, out, "02-summary")
            onEdt { frame.projectView!!.reveal(project.resolve("app/src/main/java/com/example/notes/NotesClient.java")) }
            shoot(frame, out, "03-source")
            onEdt { frame.select(ProjectView.Page.RESOURCES) }
            shoot(frame, out, "04-resources")
            onEdt { frame.select(ProjectView.Page.BACKEND) }
            shoot(frame, out, "05-backend")
            onEdt { frame.select(ProjectView.Page.NAMES) }
            shoot(frame, out, "06-names")
            onEdt { frame.select(ProjectView.Page.REPORT) }
            shoot(frame, out, "07-report")
            onEdt { frame.jMenuBar.getMenu(0).doClick() }
            shoot(frame, out, "08-file-menu", whole = true)
            onEdt { javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath() }
        }
        dialog(out, "09-recover-dialog") { RecoverDialog(frame, settings, apk ?: Paths.get("C:/Users/you/Downloads/app.apk")) }
        dialog(out, "10-progress-dialog") {
            val request = RecoveryRequest(apk ?: Paths.get("app.apk"), project ?: Paths.get("app-recovered"), true, true, true, true, false)
            RecoveryDialog(frame, env, request) {}.also { d ->
                val lines = logs.takeWhile { !it.startsWith("round-trip") }.ifEmpty { listOf("decompiling with JADX 1.5.6...") }
                lines.forEach { d.progress(it) }
            }
        }
        dialog(out, "11-tools-dialog") { ToolsDialog(frame, env, settings) }
        dialog(out, "12-about") { HelpDialogs.aboutDialog(frame) }
        dialog(out, "13-evidence") { HelpDialogs.evidenceDialog(frame) }
        println("screenshots in $out")
    }

    /** Compiles the demo sources into an APK and recovers it, as a user would. */
    private fun demoProject(env: Environment, work: Path, logs: MutableList<String>): Pair<Path, Path?> {
        if (Files.exists(work)) work.toFile().deleteRecursively()
        Files.createDirectories(work)
        val profile = env.profile()
        val build = ForwardCompiler(profile).compile(demo, work.resolve("build"), 300_000)
        val dex = build.dexFile ?: error("demo did not compile: ${build.diagnostics}")
        val apk = work.resolve("NotesDemo.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(Files.readAllBytes(dex))
            zip.closeEntry()
        }
        val options = FullRecoveryOptions(rea = false, javaScript = false, resolveLibraries = false, repairBudgetMs = 120_000)
        val result = FullRecovery(profile, options, CancellationToken()) { println(it); logs += it }.run(apk, work.resolve("NotesDemo-recovered"))
        println("recovery: ${result.exitCode} ${result.message}")
        return apk to result.outDir
    }

    private fun dialog(out: Path, name: String, make: () -> JDialog) {
        lateinit var d: JDialog
        onEdt {
            d = make()
            d.isModal = false
            d.isVisible = true
        }
        shoot(d, out, name)
        onEdt { d.dispose() }
    }

    /** The window as Windows draws it (title bar included); also its painted content if the screen grab is blank. */
    private fun shoot(w: Window, out: Path, name: String, whole: Boolean = false) {
        Thread.sleep(1500)
        onEdt { w.toFront() }
        Thread.sleep(500)
        var bounds = Rectangle()
        onEdt { bounds = if (whole) w.graphicsConfiguration.bounds else Rectangle(w.locationOnScreen, w.size) }
        val grab = Robot().createScreenCapture(bounds)
        ImageIO.write(grab, "png", out.resolve("$name.png").toFile())
        if (blank(grab)) {
            var painted: BufferedImage? = null
            onEdt {
                val root = (w as RootPaneContainer).rootPane
                painted = BufferedImage(root.width, root.height, BufferedImage.TYPE_INT_RGB).also { img ->
                    val g = img.createGraphics()
                    root.paint(g)
                    g.dispose()
                }
            }
            ImageIO.write(painted, "png", out.resolve("$name-content.png").toFile())
        }
        println("saved $name")
    }

    private fun blank(img: BufferedImage): Boolean {
        val first = img.getRGB(0, 0)
        for (y in 0 until img.height step 7) for (x in 0 until img.width step 7) if (img.getRGB(x, y) != first) return false
        return true
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
