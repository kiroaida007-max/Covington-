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
        "com/example/notes/data/NotesStore.java" to """
            package com.example.notes.data;

            import android.database.sqlite.SQLiteDatabase;

            public class NotesStore {
                private final SQLiteDatabase db;

                public NotesStore(SQLiteDatabase db) {
                    this.db = db;
                }

                public int count() {
                    android.database.Cursor c = db.rawQuery("SELECT id, title, pinned FROM notes ORDER BY pinned DESC", null);
                    return c.getCount();
                }

                public void pin(long id) {
                    db.execSQL("UPDATE notes SET pinned = 1 WHERE id = " + id);
                }
            }
        """.trimIndent(),
        "com/example/notes/ui/NotesActivity.java" to """
            package com.example.notes.ui;

            import android.app.Activity;
            import android.content.Intent;
            import android.os.Bundle;

            public class NotesActivity extends Activity {
                @Override
                protected void onCreate(Bundle state) {
                    super.onCreate(state);
                    com.example.notes.data.NotesStore store = new com.example.notes.data.NotesStore(openOrCreateDatabase("notes.db", MODE_PRIVATE, null));
                    setTitle(store.count() + " notes");
                    startService(new Intent(this, com.example.notes.sync.SyncService.class));
                    findViewById(android.R.id.button1).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
                }
            }
        """.trimIndent(),
        "com/example/notes/ui/SettingsActivity.java" to """
            package com.example.notes.ui;

            import android.app.Activity;
            import android.content.SharedPreferences;
            import android.os.Bundle;

            public class SettingsActivity extends Activity {
                @Override
                protected void onCreate(Bundle state) {
                    super.onCreate(state);
                    SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
                    boolean wifiOnly = prefs.getBoolean("sync_on_wifi_only", true);
                    prefs.edit().putString("last_opened", String.valueOf(wifiOnly)).apply();
                }
            }
        """.trimIndent(),
        "com/example/notes/sync/SyncService.java" to """
            package com.example.notes.sync;

            import android.app.Service;
            import android.content.Intent;
            import android.os.IBinder;

            public class SyncService extends Service {
                @Override
                public int onStartCommand(Intent intent, int flags, int startId) {
                    new Thread(() -> {
                        try {
                            java.net.URLConnection c = new java.net.URL(com.example.notes.NotesClient.NOTES).openConnection();
                            c.setRequestProperty("X-Device", DeviceInfo.id(this));
                            c.connect();
                        } catch (java.io.IOException e) {
                            stopSelf();
                        }
                    }).start();
                    return START_NOT_STICKY;
                }

                @Override
                public IBinder onBind(Intent intent) {
                    return null;
                }
            }
        """.trimIndent(),
        "com/example/notes/sync/DeviceInfo.java" to """
            package com.example.notes.sync;

            import android.content.Context;
            import android.provider.Settings;

            public final class DeviceInfo {
                public static String id(Context context) {
                    return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                }
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
        val (apk, project) = demoProject(env, out.resolveSibling("tour-work"), logs)
        check(project != null) { "Demo recovery produced no project; refusing to publish incomplete screenshots.\n${logs.joinToString("\n")}" }
        println("demo project: $project")

        // Start from a clean folder so stale screenshots from earlier runs do not linger.
        Files.list(out).use { s -> s.filter { it.toString().endsWith(".png") }.forEach { Files.deleteIfExists(it) } }

        lateinit var frame: MainFrame
        onEdt { frame = MainFrame(env, settings); frame.isVisible = true }
        shoot(frame, out, "01-start")

        // APKPROJECT_TOUR_PROJECT points at a real recovered project, for a content-rich UI & Risk shot.
        System.getenv("APKPROJECT_TOUR_PROJECT")?.let { path ->
            val p = Paths.get(path)
            if (Project.isProject(p)) {
                onEdt { frame.show(Project(p)); frame.select(ProjectView.Page.UI_RISK) }
                shoot(frame, out, "00-ui-risk-real")
                onEdt { frame.select(ProjectView.Page.SCREENS); showFirstPath(frame, "Network") }
                shoot(frame, out, "00-screens-real")
                onEdt { frame.select(ProjectView.Page.PRIVACY) }
                shoot(frame, out, "00-privacy-real")
                onEdt { frame.select(ProjectView.Page.COMPONENTS) }
                shoot(frame, out, "00-components-real")
            }
        }
        if (project != null) {
            onEdt { frame.show(Project(project)) }
            shoot(frame, out, "02-summary")
            onEdt { frame.projectView!!.reveal(project.resolve("app/src/main/java/com/example/notes/NotesClient.java")) }
            shoot(frame, out, "03-source")
            onEdt { frame.select(ProjectView.Page.RESOURCES) }
            shoot(frame, out, "04-resources")
            onEdt { frame.select(ProjectView.Page.UI_RISK) }
            shoot(frame, out, "05-ui-risk")
            onEdt { showRiskDetails(frame.contentPane) }
            shoot(frame, out, "05-ui-risk-details")
            onEdt { frame.select(ProjectView.Page.SCREENS); showFirstPath(frame, "Database") }
            shoot(frame, out, "05b-screens")
            onEdt { frame.select(ProjectView.Page.PRIVACY) }
            shoot(frame, out, "05c-privacy")
            for ((tab, name) in listOf("Data Safety" to "05c-privacy-data-safety", "Data Read" to "05c-privacy-data-read", "Analysis Notes" to "05c-privacy-notes")) {
                onEdt { showPrivacyTab(frame, tab) }
                shoot(frame, out, name)
            }
            onEdt { frame.select(ProjectView.Page.COMPONENTS) }
            shoot(frame, out, "05d-components")
            onEdt { frame.select(ProjectView.Page.BACKEND) }
            shoot(frame, out, "06-backend")
            onEdt { frame.select(ProjectView.Page.NAMES) }
            shoot(frame, out, "07-names")
            onEdt { frame.select(ProjectView.Page.REPORT) }
            shoot(frame, out, "08-report")
            onEdt { frame.jMenuBar.getMenu(0).doClick() }
            shoot(frame, out, "09-file-menu", whole = true)
            onEdt { javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath() }
        }
        dialog(out, "10-recover-dialog") { RecoverDialog(frame, settings, apk ?: Paths.get("C:/Users/you/Downloads/app.apk")) }
        dialog(out, "11-progress-dialog") {
            val request = RecoveryRequest(apk ?: Paths.get("app.apk"), project ?: Paths.get("app-recovered"), true, true, true, true, false)
            RecoveryDialog(frame, env, request) {}.also { d ->
                val lines = logs.takeWhile { !it.startsWith("round-trip") }.ifEmpty { listOf("decompiling with JADX 1.5.6...") }
                lines.forEach { d.progress(it) }
            }
        }
        dialog(out, "12-tools-dialog") { ToolsDialog(frame, env, settings) }
        dialog(out, "13-about") { HelpDialogs.aboutDialog(frame) }
        dialog(out, "14-evidence") { HelpDialogs.evidenceDialog(frame) }

        // Compare Versions: the demo app and a later version of it.
        val newer = newerDemo(env, apk.resolveSibling("v2"))
        dialog(out, "15-compare-dialog") { CompareDialog(frame, settings, apk ?: Paths.get("C:/Users/you/Downloads/app-1.0.apk"), newer ?: Paths.get("C:/Users/you/Downloads/app-1.1.apk")) }
        if (apk != null && newer != null) diffShots(out, "", apk, newer, settings)
        // APKPROJECT_TOUR_DIFF="old.apk;new.apk" adds the same screens for a real pair of releases.
        System.getenv("APKPROJECT_TOUR_DIFF")?.split(';')?.takeIf { it.size == 2 }?.let { (a, b) -> diffShots(out, "00-real-", Paths.get(a), Paths.get(b), settings) }
        println("screenshots in $out")
    }

    private fun diffShots(out: Path, prefix: String, old: Path, new: Path, settings: Settings) {
        val result = dev.apkproject.app.AppDiff.compare(old, new)
        lateinit var diff: DiffFrame
        onEdt { diff = DiffFrame(result, settings); diff.isVisible = true }
        listOf("16-diff-changes", "17-diff-code", "18-diff-capabilities", "19-diff-report").forEachIndexed { k, name ->
            onEdt { diff.tabs.selectedIndex = k }
            shoot(diff, out, prefix + name)
        }
        onEdt { diff.dispose() }
    }

    /** Version 1.1 of the demo: a changed method, a new server, and code that runs shell commands. */
    private fun newerDemo(env: Environment, work: Path): Path {
        val sources = demo.toMutableMap()
        sources["com/example/notes/NotesClient.java"] = sources.getValue("com/example/notes/NotesClient.java")
            .replace("if (x > hi) {", "if (x >= hi) {")
        sources["com/example/notes/Telemetry.java"] = """
            package com.example.notes;

            public class Telemetry {
                public static final String ENDPOINT = "https://telemetry.example.net/v1/collect";

                public static String deviceReport() throws java.io.IOException {
                    Process p = Runtime.getRuntime().exec(new String[] {"getprop", "ro.product.model"});
                    return ENDPOINT + "?model=" + p.getInputStream().read();
                }
            }
        """.trimIndent()
        Files.createDirectories(work)
        val profile = env.profile()
        val build = ForwardCompiler(profile).compile(sources, work.resolve("build"), 300_000, classpath = listOfNotNull(profile.androidJar))
        val dex = build.dexFile ?: error("demo v2 did not compile: ${build.diagnostics}")
        return apk(work.resolve("NotesDemo-1.1.apk"), dex, "1.1")
    }

    /** Compiles the demo sources into an APK and recovers it, as a user would. */
    private fun demoProject(env: Environment, work: Path, logs: MutableList<String>): Pair<Path, Path?> {
        if (Files.exists(work)) work.toFile().deleteRecursively()
        Files.createDirectories(work)
        val profile = env.profile()
        val build = ForwardCompiler(profile).compile(demo, work.resolve("build"), 300_000, classpath = listOfNotNull(profile.androidJar))
        val dex = build.dexFile ?: error("demo did not compile: ${build.diagnostics}")
        val apk = apk(work.resolve("NotesDemo.apk"), dex, "1.0")
        val options = FullRecoveryOptions(rea = false, javaScript = false, resolveLibraries = false, repairBudgetMs = 120_000)
        val result = FullRecovery(profile, options, CancellationToken()) { println(it); logs += it }.run(apk, work.resolve("NotesDemo-recovered"))
        println("recovery: ${result.exitCode} ${result.message}")
        return apk to result.outDir
    }

    /** On the Screens & Data page: opens the detail tab whose title starts with [tab] and selects its first row. */
    private fun showFirstPath(frame: MainFrame, tab: String) {
        fun all(c: java.awt.Component): Sequence<java.awt.Component> = sequenceOf(c) + ((c as? java.awt.Container)?.components?.asSequence()?.flatMap { all(it) } ?: emptySequence())
        val view = all(frame.contentPane).filterIsInstance<ScreensView>().firstOrNull { it.isShowing } ?: return
        val tabs = all(view).filterIsInstance<javax.swing.JTabbedPane>().firstOrNull { t -> (0 until t.tabCount).any { t.getTitleAt(it).startsWith(tab) } } ?: return
        val k = (0 until tabs.tabCount).first { tabs.getTitleAt(it).startsWith(tab) }
        tabs.selectedIndex = k
        val table = all(tabs.getComponentAt(k)).filterIsInstance<javax.swing.JTable>().firstOrNull() ?: return
        if (table.rowCount > 0) table.setRowSelectionInterval(0, 0)
    }

    private fun showPrivacyTab(frame: MainFrame, title: String) {
        fun all(c: java.awt.Component): Sequence<java.awt.Component> = sequenceOf(c) + ((c as? java.awt.Container)?.components?.asSequence()?.flatMap { all(it) } ?: emptySequence())
        val view = all(frame.contentPane).filterIsInstance<PrivacyView>().first { it.isShowing }
        val tabs = all(view).filterIsInstance<javax.swing.JTabbedPane>().first()
        tabs.selectedIndex = (0 until tabs.tabCount).first { tabs.getTitleAt(it) == title }
    }

    internal fun showRiskDetails(root: java.awt.Container, showingOnly: Boolean = true) {
        fun all(c: java.awt.Component): Sequence<java.awt.Component> = sequenceOf(c) + ((c as? java.awt.Container)?.components?.asSequence()?.flatMap { all(it) } ?: emptySequence())
        val detail = all(root).filterIsInstance<javax.swing.JTextArea>().first {
            (!showingOnly || it.isShowing) && it.accessibleContext.accessibleName == "Finding detail and suggested fix"
        }
        // scrollRectToVisible stops at the detail pane's own viewport. Move the enclosing form,
        // so the second capture actually shows the details and screen rows below the overview.
        val page = generateSequence(detail.parent) { it.parent }.filterIsInstance<javax.swing.JScrollPane>().first {
            it.viewport.view is javax.swing.JPanel && it.viewport.view is javax.swing.Scrollable
        }
        val point = SwingUtilities.convertPoint(detail, 0, 0, page.viewport.view)
        page.verticalScrollBar.value = (point.y - 80).coerceAtLeast(0)
    }

    /** The demo APK: its DEX and a binary manifest declaring its two screens and its service. */
    private fun apk(apk: Path, dex: Path, version: String): Path {
        fun e(name: String, attrs: List<Pair<String, Any>> = emptyList(), children: List<TourManifest.Element> = emptyList()) = TourManifest.Element(name, attrs, children)
        val manifest = e("manifest", listOf("package" to "com.example.notes", "android:versionName" to version), listOf(
            e("application", emptyList(), listOf(
                e("activity", listOf("android:name" to "com.example.notes.ui.NotesActivity", "android:exported" to true), listOf(
                    e("intent-filter", emptyList(), listOf(
                        e("action", listOf("android:name" to "android.intent.action.MAIN")),
                        e("category", listOf("android:name" to "android.intent.category.LAUNCHER")),
                    )),
                )),
                e("activity", listOf("android:name" to "com.example.notes.ui.SettingsActivity", "android:exported" to false)),
                e("service", listOf("android:name" to "com.example.notes.sync.SyncService", "android:exported" to false)),
            )),
        ))
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(TourManifest.encode(manifest))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(Files.readAllBytes(dex))
            zip.closeEntry()
            // Library version files, as Gradle packages them: the Components page lists them, and this Gson has a known advisory.
            zip.putNextEntry(ZipEntry("META-INF/androidx.core_core.version"))
            zip.write("1.6.0\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/maven/com.google.code.gson/gson/pom.properties"))
            zip.write("groupId=com.google.code.gson\nartifactId=gson\nversion=2.8.5\n".toByteArray())
            zip.closeEntry()
        }
        return apk
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
