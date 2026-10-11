package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A small notes app: three screens on a shared base activity, a ViewModel, a repository behind a
 * DI provider (a switching provider stored in a field, as Hilt generates), a Retrofit interface,
 * SQL and SharedPreferences, and a sync service one screen starts.
 */
class ScreenMapTest {
    @TempDir
    lateinit var dir: Path

    private val sources = mapOf(
        // Platform and library stubs (only their names matter to the walk).
        "android/app/Activity.java" to """
            package android.app;
            public class Activity {
                protected void onCreate(Object state) { }
                public void startActivity(android.content.Intent i) { }
                public void startService(android.content.Intent i) { }
                public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return null; }
            }
        """.trimIndent(),
        "android/app/Service.java" to "package android.app;\npublic class Service { public int onStartCommand(android.content.Intent i, int f, int id) { return 0; } }\n",
        "android/content/Intent.java" to "package android.content;\npublic class Intent { public Intent(Object context, Class<?> target) { } }\n",
        "android/content/SharedPreferences.java" to """
            package android.content;
            public interface SharedPreferences {
                String getString(String key, String defValue);
                Editor edit();
                interface Editor { Editor putString(String key, String value); void apply(); }
            }
        """.trimIndent(),
        "android/database/sqlite/SQLiteDatabase.java" to """
            package android.database.sqlite;
            public class SQLiteDatabase {
                public Object rawQuery(String sql, String[] args) { return null; }
                public void execSQL(String sql) { }
                public long insert(String table, String nullColumnHack, Object values) { return 0; }
            }
        """.trimIndent(),
        "retrofit2/http/GET.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface GET { String value(); }\n",
        "retrofit2/http/POST.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface POST { String value(); }\n",
        // The app.
        "com/example/api/Api.java" to """
            package com.example.api;
            import retrofit2.http.*;
            public interface Api {
                @GET("api/v1/notes") Object notes();
                @POST("api/v1/notes") Object create(String text);
                @GET("api/v1/sync") Object sync();
                @GET("api/v1/admin/audit") Object audit();
            }
        """.trimIndent(),
        "com/example/data/NotesRepository.java" to """
            package com.example.data;
            import android.database.sqlite.SQLiteDatabase;
            public class NotesRepository {
                private final com.example.api.Api api;
                private final SQLiteDatabase db;
                public NotesRepository(com.example.api.Api api, SQLiteDatabase db) { this.api = api; this.db = db; }
                public Object load() {
                    db.rawQuery("SELECT id, text FROM notes WHERE archived = 0", null);
                    return api.notes();
                }
                public void add(String text) {
                    db.insert("notes", null, text);
                    api.create(text);
                }
            }
        """.trimIndent(),
        "com/example/data/AuditRepository.java" to """
            package com.example.data;
            public class AuditRepository {
                public AuditRepository(com.example.api.Api api, android.database.sqlite.SQLiteDatabase db) {
                    db.execSQL("DELETE FROM audit_log WHERE age > 30");
                    api.audit();
                }
            }
        """.trimIndent(),
        // A switching provider: one get() over every binding, chosen by the id it is created with.
        "com/example/di/Factory.java" to "package com.example.di;\npublic interface Factory { Object get(); }\n",
        "com/example/di/Checked.java" to """
            package com.example.di;
            public final class Checked implements Factory {
                private final Factory delegate;
                private Checked(Factory d) { delegate = d; }
                public static Factory wrap(Factory d) { return new Checked(d); }
                public Object get() { return delegate.get(); }
            }
        """.trimIndent(),
        "com/example/di/Switching.java" to """
            package com.example.di;
            final class Switching implements Factory {
                private final Graph graph;
                private final int id;
                Switching(Graph graph, int id) { this.graph = graph; this.id = id; }
                public Object get() {
                    switch (id) {
                        case 0: return new com.example.data.NotesRepository(graph.api, graph.db);
                        case 1: return new com.example.data.AuditRepository(graph.api, graph.db);
                        default: throw new AssertionError(id);
                    }
                }
            }
        """.trimIndent(),
        "com/example/di/Graph.java" to """
            package com.example.di;
            public final class Graph {
                public static Graph instance;
                com.example.api.Api api;
                android.database.sqlite.SQLiteDatabase db;
                public final Factory notesProvider;
                public final Factory auditProvider;
                public Graph() {
                    notesProvider = Checked.wrap(new Switching(this, 0));
                    auditProvider = Checked.wrap(new Switching(this, 1));
                }
            }
        """.trimIndent(),
        "com/example/ui/BaseActivity.java" to """
            package com.example.ui;
            public class BaseActivity extends android.app.Activity {
                protected void onCreate(Object state) {
                    super.onCreate(state);
                    Telemetry.ping();
                }
            }
        """.trimIndent(),
        "com/example/ui/Telemetry.java" to """
            package com.example.ui;
            final class Telemetry {
                static void ping() {
                    try { new java.net.URL("https://telemetry.example.com/v1/ping").openConnection(); } catch (Exception e) { }
                }
            }
        """.trimIndent(),
        "com/example/ui/NotesViewModel.java" to """
            package com.example.ui;
            import com.example.data.NotesRepository;
            public class NotesViewModel {
                private final NotesRepository repo;
                public NotesViewModel() { repo = (NotesRepository) com.example.di.Graph.instance.notesProvider.get(); }
                public Object refresh() { return repo.load(); }
                public void add(String text) { repo.add(text); }
            }
        """.trimIndent(),
        "com/example/ui/ViewModels.java" to """
            package com.example.ui;
            final class ViewModels {
                static Object of(Class<?> type) { return null; }
            }
        """.trimIndent(),
        "com/example/ui/NotesActivity.java" to """
            package com.example.ui;
            public class NotesActivity extends BaseActivity {
                private NotesViewModel vm;
                protected void onCreate(Object state) {
                    super.onCreate(state);
                    vm = (NotesViewModel) ViewModels.of(NotesViewModel.class);
                    vm.refresh();
                    Runnable onSave = new Runnable() { public void run() { vm.add("note"); } };
                    onSave.run();
                    startActivity(new android.content.Intent(this, SettingsActivity.class));
                    startService(new android.content.Intent(this, com.example.sync.SyncService.class));
                }
            }
        """.trimIndent(),
        "com/example/ui/SettingsActivity.java" to """
            package com.example.ui;
            public class SettingsActivity extends BaseActivity {
                protected void onCreate(Object state) {
                    super.onCreate(state);
                    android.content.SharedPreferences prefs = getSharedPreferences("settings", 0);
                    String theme = prefs.getString("theme", "light");
                    prefs.edit().putString("last_theme", theme).apply();
                }
            }
        """.trimIndent(),
        "com/example/ui/AboutActivity.java" to """
            package com.example.ui;
            public class AboutActivity extends BaseActivity {
                protected void onCreate(Object state) { super.onCreate(state); }
            }
        """.trimIndent(),
        "com/example/sync/SyncService.java" to """
            package com.example.sync;
            public class SyncService extends android.app.Service {
                com.example.api.Api api;
                public int onStartCommand(android.content.Intent i, int f, int id) { api.sync(); return 0; }
            }
        """.trimIndent(),
    )

    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
          <application>
            <activity android:name=".ui.NotesActivity" android:exported="true">
              <intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
            </activity>
            <activity android:name=".ui.SettingsActivity"/>
            <activity android:name=".ui.AboutActivity"/>
            <service android:name=".sync.SyncService"/>
          </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `screens reach their endpoints, tables and settings`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        val build = ForwardCompiler(profile).compile(sources, dir, 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)

        val r = ScreenMap.analyze(dex, manifest)
        val screens = r.screens.associateBy { it.component }
        assertEquals(listOf("ACTIVITY", "ACTIVITY", "ACTIVITY", "SERVICE"), r.screens.map { it.kind })
        val notes = screens.getValue("com.example.ui.NotesActivity")
        assertTrue(notes.launcher && notes.exported)
        fun endpoints(s: ScreenFlow) = s.network.filter { it.kind == "ENDPOINT" }.map { it.target }.toSet()

        // Through the ViewModel (a class constant), the repository the DI provider field builds, and a
        // listener created on the screen; the service it starts counts too.
        assertEquals(setOf("GET api/v1/notes", "POST api/v1/notes", "GET api/v1/sync"), endpoints(notes))
        assertEquals(listOf("com.example.ui.SettingsActivity"), notes.opens)
        assertEquals(listOf("com.example.sync.SyncService"), notes.starts)
        val load = notes.network.single { it.target == "GET api/v1/notes" }
        assertEquals("com.example.ui.NotesActivity.onCreate", load.path.first())
        assertEquals("com.example.data.NotesRepository.load", load.path.last())
        // The provider's other binding (the audit repository) belongs to no screen.
        assertTrue(notes.database.none { "audit_log" in it.target })
        assertEquals(setOf("SELECT notes", "INSERT notes"), notes.database.filter { it.kind == "SQL" }.map { it.target }.toSet())

        // Settings: the file and keys, not the default value passed to getString.
        val settings = screens.getValue("com.example.ui.SettingsActivity")
        assertEquals(setOf("file settings", "theme (read)", "last_theme (write)"), settings.preferences.map { it.target }.toSet())
        assertTrue(endpoints(settings).isEmpty(), "opening a screen does not run it")
        assertEquals(listOf("settings"), r.preferenceFiles)

        // The base activity's telemetry is common to the three screens, and listed once.
        assertEquals(3, r.commonThreshold)
        val common = r.common.single { it.kind == "HOST" }
        assertEquals("telemetry.example.com", common.target)
        assertEquals(3, common.sharedBy)
        assertTrue(screens.getValue("com.example.ui.AboutActivity").network.all { it.sharedBy >= 3 })

        assertEquals(listOf("com.example.ui.NotesActivity"), r.endpoints.single { it.endpoint == "GET api/v1/notes" }.screens)
        assertEquals(emptyList(), r.endpoints.single { it.endpoint == "GET api/v1/admin/audit" }.screens)
        val tables = r.tables.associateBy { it.table }
        assertEquals(listOf("INSERT", "SELECT"), tables.getValue("notes").operations)
        assertEquals(listOf("DELETE"), tables.getValue("audit_log").operations)
        assertEquals(emptyList(), tables.getValue("audit_log").screens)

        val md = ScreenMapMarkdown.render(r)
        assertTrue("## Common to most components" in md && "telemetry.example.com" in md)
        assertTrue("## com.example.ui.NotesActivity (activity, exported, launcher)" in md)
        assertFalse("## com.example.ui.AboutActivity" in md, "a screen with only common reaches has no section of its own")
    }

    @Test
    fun `SQL is recognised by its shape`() {
        val sql = listOf("SELECT * FROM t", "insert or replace into `t` (a) values (?)", "UPDATE t SET a = 1", "DELETE FROM t", "WITH x AS (SELECT 1) SELECT * FROM x")
        val text = listOf("Update", "with tag", "Select an account", "Delete this post?", "Replace", "UPDATE")
        for (q in sql) assertNotNull(ScreenMap.sqlOp(q), q)
        for (t in text) assertEquals(null, ScreenMap.sqlOp(t), t)
    }
}
