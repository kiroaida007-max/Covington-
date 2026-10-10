package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each rule on bytecode shaped like its code generator's output after R8 renamed the declarations
 * (Java stands in for the generated Kotlin; javac and D8 build the DEX).
 */
class NameOracleTest {
    @TempDir
    lateinit var dir: Path

    private fun dex(sources: Map<String, String>): DexFile {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        val build = ForwardCompiler(profile).compile(sources, dir.resolve("build"), 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        return (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value
    }

    @Test
    fun `data classes are named by toString when hashCode covers the same fields`() {
        val d = dex(
            mapOf(
                // data class User(val id: String, val age: Int), renamed to p.a with fields b, c.
                "p/a.java" to """
                    package p;
                    public final class a {
                        public final String b; public final int c;
                        public a(String b, int c) { this.b = b; this.c = c; }
                        public final String d() { return b; }
                        public String toString() { return "User(id=" + b + ", age=" + c + ")"; }
                        public int hashCode() { return b.hashCode() * 31 + c; }
                    }
                """.trimIndent(),
                // A hand-written toString in the same format: hashCode covers another field.
                "p/e.java" to """
                    package p;
                    public final class e {
                        public final Object f; public final int g;
                        public e(Object f, int g) { this.f = f; this.g = g; }
                        public String toString() { return "PaddingValues(start=" + f + ")"; }
                        public int hashCode() { return g; }
                    }
                """.trimIndent(),
                // Kept names are the original names: nothing to recover.
                "p/Item.java" to """
                    package p;
                    public final class Item {
                        public final String title;
                        public Item(String title) { this.title = title; }
                        public String toString() { return "Item(title=" + title + ")"; }
                        public int hashCode() { return title.hashCode(); }
                    }
                """.trimIndent(),
            ),
        )
        val r = NameOracle.recover(listOf(d))
        assertEquals("User", r.classes["Lp/a;"]?.name)
        assertEquals(mapOf("b" to "id", "c" to "age"), r.fields["Lp/a;"]?.mapValues { it.value.name })
        assertEquals("getId", r.methods["Lp/a;"]?.get("d()Ljava/lang/String;")?.name, "the getter of a named field")
        assertNull(r.classes["Lp/e;"], "a hand-written toString names no class")
        assertEquals(NameOracle.PRINTED, r.fields["Lp/e;"]?.get("f")?.rule, "its labels still name the printed field")
        assertNull(r.classes["Lp/Item;"])
        assertNull(r.fields["Lp/Item;"])
    }

    @Test
    fun `moshi adapters name themselves and the model they construct`() {
        val d = dex(
            mapOf(
                "m/l.java" to "package m;\npublic enum l { ON, OFF }\n",
                "m/h.java" to "package m;\npublic final class h { public final l x, y; public h(l x, l y) { this.x = x; this.y = y; } }\n",
                // AccountJsonAdapter: casts the enum fields more often than the model, but constructs only the model.
                "m/g.java" to """
                    package m;
                    public final class g {
                        public Object a(Object[] r) { return new h((l) r[0], (l) r[1]); }
                        public void b(Object w, Object v) { h model = (h) v; Object x = (l) w; }
                        public String toString() { return "GeneratedJsonAdapter(Account)"; }
                    }
                """.trimIndent(),
            ),
        )
        val r = NameOracle.recover(listOf(d))
        assertEquals("AccountJsonAdapter", r.classes["Lm/g;"]?.name)
        assertEquals("Account", r.classes["Lm/h;"]?.name)
        assertNull(r.classes["Lm/l;"])
    }

    @Test
    fun `room insert adapters name the entity and its columns`() {
        val d = dex(
            mapOf(
                "r/s.java" to "package r;\npublic interface s { void a(int i, long v); void b(int i, String v); void c(int i); }\n",
                "r/o.java" to "package r;\npublic final class o { public long p; public String q; }\n",
                "r/l.java" to """
                    package r;
                    public final class l {
                        public String m() { return "INSERT OR REPLACE INTO `Draft` (`id`,`text`) VALUES (?,?)"; }
                        public void n(s stmt, Object value) {
                            o e = (o) value;
                            stmt.a(1, e.p);
                            if (e.q == null) stmt.c(2); else stmt.b(2, e.q);
                        }
                    }
                """.trimIndent(),
            ),
        )
        val r = NameOracle.recover(listOf(d))
        assertEquals("Draft", r.classes["Lr/o;"]?.name)
        assertEquals(mapOf("p" to "id", "q" to "text"), r.fields["Lr/o;"]?.mapValues { it.value.name })
    }

    @Test
    fun `enum constants name their own fields and not their aliases`() {
        val d = TestApks.dexFile(
            """
            .class public final Le/q;
            .super Ljava/lang/Enum;
            .field public static final a:Le/q;
            .field public static final b:Le/q;
            .field public static final c:Le/q;
            .method private <init>(Ljava/lang/String;I)V
            .registers 3
            invoke-direct {p0, p1, p2}, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
            return-void
            .end method
            .method static <clinit>()V
            .registers 3
            new-instance v0, Le/q;
            const-string v1, "AUTO_SYSTEM"
            const/4 v2, #0
            invoke-direct {v0, v1, v2}, Le/q;-><init>(Ljava/lang/String;I)V
            sput-object v0, Le/q;->a:Le/q;
            new-instance v0, Le/q;
            const-string v1, "LIGHT"
            const/4 v2, #1
            invoke-direct {v0, v1, v2}, Le/q;-><init>(Ljava/lang/String;I)V
            sput-object v0, Le/q;->b:Le/q;
            sput-object v0, Le/q;->c:Le/q;
            return-void
            .end method
            """.trimIndent().replace("p0", "v0").replace("p1", "v1").replace("p2", "v2"),
        )
        val r = NameOracle.recover(listOf(d))
        assertEquals(mapOf("a" to "AUTO_SYSTEM", "b" to "LIGHT"), r.fields["Le/q;"]?.mapValues { it.value.name })
    }

    @Test
    fun `view bindings are named from their layout and view ids, also when bind is inlined`() {
        val d = dex(
            mapOf(
                // Just enough of the framework for the shapes ViewBinding generates.
                "android/view/View.java" to "package android.view;\npublic class View { public View findViewById(int id) { return null; } public android.content.res.Resources getResources() { return null; } }\n",
                "android/view/ViewGroup.java" to "package android.view;\npublic class ViewGroup extends View { }\n",
                "android/view/LayoutInflater.java" to "package android.view;\npublic class LayoutInflater { public View inflate(int layout, ViewGroup root, boolean attach) { return null; } }\n",
                "android/content/res/Resources.java" to "package android.content.res;\npublic class Resources { public String getResourceName(int id) { return null; } }\n",
                "android/widget/TextView.java" to "package android.widget;\npublic class TextView extends android.view.View { }\n",
                "android/widget/LinearLayout.java" to "package android.widget;\npublic class LinearLayout extends android.view.ViewGroup { }\n",
                "androidx/viewbinding/ViewBinding.java" to "package androidx.viewbinding;\npublic interface ViewBinding { android.view.View getRoot(); }\n",
                "b/j.java" to """
                    package b;
                    public final class j implements androidx.viewbinding.ViewBinding {
                        public final android.widget.LinearLayout a; public final android.widget.TextView b;
                        public j(android.widget.LinearLayout a, android.widget.TextView b) { this.a = a; this.b = b; }
                        public android.view.View getRoot() { return a; }
                    }
                """.trimIndent(),
                // R8 inlined ActivityMainBinding.inflate into a merged lambda class: a switch over two uses.
                "b/k.java" to """
                    package b;
                    public final class k {
                        public final int i; public final android.view.LayoutInflater l;
                        public k(int i, android.view.LayoutInflater l) { this.i = i; this.l = l; }
                        public Object invoke() {
                            switch (i) {
                                case 0:
                                    android.view.View root = l.inflate(0x7f0d0001, null, false);
                                    int id = 0x7f0a0002;
                                    android.widget.TextView t = (android.widget.TextView) root.findViewById(id);
                                    if (t != null) return new j((android.widget.LinearLayout) root, t);
                                    throw new NullPointerException("Missing required view with ID: ".concat(root.getResources().getResourceName(id)));
                                default:
                                    return null;
                            }
                        }
                    }
                """.trimIndent(),
            ),
        )
        val resources = ResourceTable.of(mapOf(0x7f0d0001 to ("layout" to "activity_main"), 0x7f0a0002 to ("id" to "title_text")))
        val r = NameOracle.recover(listOf(d), resources)
        assertEquals("ActivityMainBinding", r.classes["Lb/j;"]?.name)
        assertEquals(mapOf("a" to "rootView", "b" to "titleText"), r.fields["Lb/j;"]?.mapValues { it.value.name })
        assertNull(r.classes["Lb/k;"])
    }

    @Test
    fun `recovered class names stay unique and overrule contradicting library matches`() {
        val d = TestApks.dexFile(
            listOf("Lq/a;", "Lq/b;", "Lq/c;").joinToString("") { ".class public $it\n.super Ljava/lang/Object;\n" } +
                ".class public Lq/u;\n.super Ljava/lang/Object;\n.method public static f()V\n.registers 1\nconst-class v0, Landroid/view/View;\nreturn-void\n.end method\n",
        )
        fun name(n: String) = NameOracle.Name("", n, NameOracle.DATA_CLASS, "test")
        // q.a is a View (taken by android.view.View), q.b a User, and a library match calls q.b a Token.
        val evidence = NameOracle.Result(mapOf("Lq/a;" to name("View"), "Lq/b;" to name("User")), emptyMap(), emptyMap(), emptyList())
        val library = LibraryMatcher.Result(
            listOf(LibraryMatcher.ClassMatch("Lq/b;", "Llib/Token;", 1.0, 1.0), LibraryMatcher.ClassMatch("Lq/c;", "Llib/Client;", 1.0, 1.0)), emptyMap(), emptyMap(), 1,
        )
        val m = NameMapping.compute(listOf("classes.dex" to d), library, evidence)
        assertEquals("Lq/View_q;", m.classes["Lq/a;"])
        assertEquals("Lq/User;", m.classes["Lq/b;"])
        assertEquals("Llib/Client;", m.classes["Lq/c;"])
        assertEquals(1, m.libraryMatchesVetoed)
        assertEquals(2, m.evidenceClasses)
    }

    @Test
    fun `resource ids and names are read from a compiled resource table`() {
        val table = ResourceTable.parse(arsc(0x7f, listOf("id", "layout"), mapOf(1 to listOf("title_text", "send"), 2 to listOf("activity_main"))))
        assertEquals("id" to "title_text", table[0x7f010000])
        assertEquals("send", table.name(0x7f010001, "id"))
        assertEquals("activity_main", table.name(0x7f020000, "layout"))
        assertNull(table.name(0x7f020000, "id"))
        assertTrue(ResourceTable.parse(ByteArray(16)).size == 0)
    }

    /** A minimal resources.arsc: one package, UTF-8 string pools, one default-config type chunk per type. */
    private fun arsc(packageId: Int, types: List<String>, entries: Map<Int, List<String>>): ByteArray {
        fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fun pool(strings: List<String>): ByteArray {
            val data = java.io.ByteArrayOutputStream()
            val offsets = strings.map { s ->
                val bytes = s.toByteArray()
                val at = data.size()
                data.write(s.length); data.write(bytes.size); data.write(bytes); data.write(0)
                at
            }
            while (data.size() % 4 != 0) data.write(0)
            val header = 28
            val b = le(header + 4 * strings.size + data.size())
            b.putShort(1); b.putShort(header.toShort()); b.putInt(b.capacity()); b.putInt(strings.size); b.putInt(0); b.putInt(0x100)
            b.putInt(header + 4 * strings.size); b.putInt(0)
            offsets.forEach { b.putInt(it) }
            b.put(data.toByteArray())
            return b.array()
        }
        val keys = entries.values.flatten()
        val typePool = pool(types)
        val keyPool = pool(keys)
        val chunks = java.io.ByteArrayOutputStream()
        for ((typeId, names) in entries.toSortedMap()) {
            val header = 20 + 64
            val b = le(header + 4 * names.size + 16 * names.size)
            b.putShort(0x0201); b.putShort(header.toShort()); b.putInt(b.capacity())
            b.put(typeId.toByte()); b.put(0); b.putShort(0); b.putInt(names.size); b.putInt(header + 4 * names.size)
            b.putInt(64); repeat(60) { b.put(0) } // ResTable_config: size, then zeros
            names.indices.forEach { b.putInt(16 * it) }
            for (name in names) {
                b.putShort(8); b.putShort(0); b.putInt(keys.indexOf(name)) // entry: size, flags, key
                b.putShort(8); b.put(0); b.put(0x10); b.putInt(0) // value: an int
            }
            chunks.write(b.array())
        }
        val packageHeader = 288
        val pkg = le(packageHeader + typePool.size + keyPool.size + chunks.size())
        pkg.putShort(0x0200); pkg.putShort(packageHeader.toShort()); pkg.putInt(pkg.capacity()); pkg.putInt(packageId)
        repeat(128) { pkg.putShort(0) }
        pkg.putInt(packageHeader); pkg.putInt(types.size); pkg.putInt(packageHeader + typePool.size); pkg.putInt(keys.size); pkg.putInt(0)
        pkg.put(typePool); pkg.put(keyPool); pkg.put(chunks.toByteArray())
        val globalPool = pool(emptyList())
        val table = le(12 + globalPool.size + pkg.capacity())
        table.putShort(2); table.putShort(12); table.putInt(table.capacity()); table.putInt(1)
        table.put(globalPool); table.put(pkg.array())
        return table.array()
    }
}
