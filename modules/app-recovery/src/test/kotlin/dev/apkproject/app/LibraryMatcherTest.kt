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
}
