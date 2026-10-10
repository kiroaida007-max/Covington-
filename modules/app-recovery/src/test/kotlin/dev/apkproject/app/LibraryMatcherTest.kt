package dev.apkproject.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMatcherTest {
    /** A small library; [n] renames its classes and members the way R8 does. */
    private fun library(n: (String) -> String) = """
        .class public ${n("Lcom/lib/Client;")}
        .super Ljava/lang/Object;
        .field private ${n("config")}:${n("Lcom/lib/Config;")}
        .method public ${n("send")}(Ljava/lang/String;)V
        .registers 3
        sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
        const-string v1, "Client.send: request queued"
        invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
        return-void
        .end method
        .class public ${n("Lcom/lib/Config;")}
        .super Ljava/lang/Object;
        .method public ${n("timeout")}()I
        .registers 2
        const-string v0, "timeout must be positive"
        invoke-static {v0}, Ljava/util/Objects;->requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;
        const/16 v0, #30
        return v0
        .end method
        .class public interface abstract ${n("Lcom/lib/Callback;")}
        .super Ljava/lang/Object;
        .method public abstract ${n("onDone")}(${n("Lcom/lib/Config;")})V
        .end method
        .class public ${n("Lcom/lib/Tracker;")}
        .super Ljava/lang/Object;
        .method public ${n("track")}(${n("Lcom/lib/Callback;")}${n("Lcom/lib/Config;")})V
        .registers 4
        const-string v0, "tracker: callback registered"
        invoke-static {v0}, Ljava/util/Objects;->requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;
        invoke-virtual {v3}, Ljava/lang/Object;->hashCode()I
        return-void
        .end method
    """.trimIndent()

    private val r8 = mapOf(
        "Lcom/lib/Client;" to "La/a;", "Lcom/lib/Config;" to "La/b;", "Lcom/lib/Callback;" to "La/c;", "Lcom/lib/Tracker;" to "La/d;",
        "config" to "a", "send" to "a", "timeout" to "b", "onDone" to "c", "track" to "d",
    )

    @Test
    fun `renamed classes are found by strings, framework calls and structure`() {
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(library { it })))
        val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(library { r8.getValue(it) })))
        val r = LibraryMatcher.match(apk, lib)
        val found = r.classes.associate { it.obfuscated to it.original }
        assertEquals("Lcom/lib/Client;", found["La/a;"])
        assertEquals("Lcom/lib/Config;", found["La/b;"])
        assertEquals("Lcom/lib/Tracker;", found["La/d;"])
        // The interface has no strings or calls of its own: it is found through Tracker's signature.
        assertEquals("Lcom/lib/Callback;", found["La/c;"])
        assertEquals("structure", r.classes.single { it.obfuscated == "La/c;" }.by)
        // Members of matched classes get their names back where the shape is unique.
        assertEquals("send", r.methods["La/a;"]?.get("a(Ljava/lang/String;)V"))
        assertEquals("config", r.fields["La/a;"]?.get("a"))
    }

    @Test
    fun `nothing is matched without enough evidence`() {
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(library { it })))
        val unrelated = TestApks.dexFile(".class public La/z;\n.super Ljava/lang/Object;\n.method public a()V\n.registers 1\nreturn-void\n.end method\n")
        val r = LibraryMatcher.match(LibraryMatcher.profiles(listOf(unrelated)), lib)
        assertTrue(r.classes.isEmpty(), r.classes.toString())
    }

    @Test
    fun `signature votes do not name a large merged helper as a small library class`() {
        fun holder(type: String) = ".class public Lcom/lib/Holder;\n.super Ljava/lang/Object;\n.field public helper:$type\n"
        fun helper(name: String, methods: Int, fields: Int) = buildString {
            append(".class public $name\n.super Ljava/lang/Object;\n")
            repeat(fields) { append(".field public static f$it:I\n") }
            repeat(methods) { append(".method public static m$it()V\n.registers 1\nreturn-void\n.end method\n") }
        }
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("Lcom/lib/Helper;") + helper("Lcom/lib/Helper;", 1, 1))))
        for ((methods, fields) in listOf(40 to 1, 1 to 40)) {
            val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("La/a;") + helper("La/a;", methods, fields))))
            val r = LibraryMatcher.match(apk, lib, mapOf("Lcom/lib/Holder;" to "Lcom/lib/Holder;"))
            assertTrue(r.classes.none { it.obfuscated == "La/a;" }, "a merged helper cannot carry a single library name: ${r.classes}")
        }
        // Shrinking, and a small number of generated members, remain compatible.
        for (methods in listOf(0, 1, 3)) {
            val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("La/a;") + helper("La/a;", methods, 1))))
            assertEquals("Lcom/lib/Helper;", LibraryMatcher.match(apk, lib, mapOf("Lcom/lib/Holder;" to "Lcom/lib/Holder;")).classes.single().original)
        }
    }

    @Test
    fun `shared strings do not override synthetic or known superclass mismatches`() {
        val source = library { it }
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(source)))
        val renamed = library { r8.getValue(it) }
        for (apkSource in listOf(
            renamed.replace(".class public La/a;", ".class public synthetic La/a;"),
            renamed.replace(".class public La/a;\n.super Ljava/lang/Object;", ".class public La/a;\n.super La/b;"),
        )) {
            val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(apkSource)))
            assertTrue(LibraryMatcher.match(apk, lib).classes.none { it.obfuscated == "La/a;" }, "class generation or hierarchy changes invalidate a single library identity")
        }
        val libSubclass = LibraryMatcher.profiles(listOf(TestApks.dexFile(source.replace(".class public Lcom/lib/Client;\n.super Ljava/lang/Object;", ".class public Lcom/lib/Client;\n.super Lcom/lib/Config;"))))
        assertTrue(LibraryMatcher.match(LibraryMatcher.profiles(listOf(TestApks.dexFile(renamed))), libSubclass).classes.none { it.obfuscated == "La/a;" })
    }

    @Test
    fun `small merge discriminators and unexplained constructor arguments block signature votes`() {
        fun holder(type: String) = ".class public Llib/Holder;\n.super Ljava/lang/Object;\n.field public helper:$type\n"
        fun helper(type: String, field: String, parameters: String) = """
            .class public $type
            .super Ljava/lang/Object;
            $field
            .method public constructor <init>($parameters)V
            .registers 4
            return-void
            .end method
        """.trimIndent()
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("Llib/Helper;") + helper("Llib/Helper;", "", "Ljava/lang/Object;"))))
        val known = mapOf("Llib/Holder;" to "Llib/Holder;")
        for ((field, parameters) in listOf(".field private discriminator:I" to "Ljava/lang/Object;", "" to "Ljava/lang/Object;I", "" to "Ljava/lang/Object;Ljava/lang/Object;")) {
            val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("La/a;") + helper("La/a;", field, parameters))))
            assertTrue(LibraryMatcher.match(apk, lib, known).classes.isEmpty(), "a single signature vote cannot explain new primitive state")
        }
        val shrunk = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("La/a;") + helper("La/a;", "", ""))))
        assertEquals("Llib/Helper;", LibraryMatcher.match(shrunk, lib, known).classes.single().original)
    }

    @Test
    fun `a contradictory favourite remains a competitor instead of promoting the runner up`() {
        fun cls(type: String, field: String, count: Int) = buildString {
            append(".class public $type\n.super Ljava/lang/Object;\n$field\n.method public a()V\n.registers 1\n")
            repeat(count) { append("const-string v0, \"anchor $it\"\n") }
            append("return-void\n.end method\n")
        }
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(cls("Llib/Favourite;", "", 16) + cls("Llib/Alternative;", ".field private discriminator:I", 14))))
        val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(cls("La/a;", ".field private discriminator:I", 16))))
        val rankings = LibraryMatcher.rank(apk, lib, emptyMap()).getValue("La/a;")
        assertEquals("Llib/Favourite;", rankings.first().first)
        assertTrue(rankings.any { it.first == "Llib/Alternative;" && it.second >= 0.75 })
        assertTrue(LibraryMatcher.match(apk, lib).classes.isEmpty())
    }

    @Test
    fun `a lone signature vote cannot bypass an unrelated code fingerprint`() {
        fun holder(type: String) = ".class public Llib/Holder;\n.super Ljava/lang/Object;\n.field public helper:$type\n"
        fun helper(type: String, literal: String) = """
            .class public $type
            .super Ljava/lang/Object;
            .method public a()V
            .registers 2
            const-string v0, "$literal"
            const-string v1, "$literal again"
            return-void
            .end method
        """.trimIndent()
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("Llib/Helper;") + helper("Llib/Helper;", "reference"))))
        val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(holder("La/a;") + helper("La/a;", "unrelated"))))
        assertTrue(LibraryMatcher.match(apk, lib, mapOf("Llib/Holder;" to "Llib/Holder;")).classes.isEmpty())
    }

    @Test
    fun `a named parent can anchor its unique facade without ordinary methods`() {
        val parent = ".class public Llib/Parent;\n.super Ljava/lang/Object;\n"
        fun facade(type: String) = """
            .class public $type
            .super Llib/Parent;
            .method private constructor <init>()V
            .registers 1
            invoke-direct {v0}, Llib/Parent;-><init>()V
            return-void
            .end method
        """.trimIndent()
        val others = (1..30).joinToString("\n") { ".class public Llib/Other$it;\n.super Ljava/lang/Object;\n" }
        val lib = LibraryMatcher.profiles(listOf(TestApks.dexFile(parent + facade("Llib/Facade;") + "\n" + others)))
        val apk = LibraryMatcher.profiles(listOf(TestApks.dexFile(parent + facade("La/a;"))))
        assertEquals("Llib/Facade;", LibraryMatcher.match(apk, lib, mapOf("Llib/Parent;" to "Llib/Parent;")).classes.single().original)
    }
}
