package dev.apkproject.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualMethodNamesTest {
    @TempDir lateinit var dir: Path
    private fun cls(owner: String, parent: String = "Ljava/lang/Object;", members: String = "", interfaces: String = "") =
        ".class public abstract $owner\n.super $parent\n$interfaces$members"
    private fun method(name: String = "a", proto: String = "()I", flags: String = "public abstract") =
        if ("abstract" in flags) ".method $flags $name$proto\n.end method\n" else ".method $flags $name$proto\n.registers 2\nconst/4 v0, #1\nreturn v0\n.end method\n"
    private val seeds = mapOf("Llib/Base;" to mapOf("a()I" to "read"))

    @Test
    fun `virtual names reach unmatched subclasses and inherited receiver references across dex files`() {
        val base = TestApks.dexFile(cls("Llib/Base;", members = method()))
        val app = TestApks.dexFile(cls("Lapp/Child;", "Llib/Base;", method()) + cls("Lapp/Grandchild;", "Lapp/Child;") +
            ".class public Lapp/Calls;\n.super Ljava/lang/Object;\n.method public static call(Lapp/Grandchild;)I\n.registers 2\ninvoke-virtual {v1}, Lapp/Grandchild;->a()I\nmove-result v0\nreturn v0\n.end method\n")
        val r = LibraryMatcher.Result(emptyList(), seeds, emptyMap(), 1) // class name was kept
        val mapping = NameMapping.compute(listOf("classes.dex" to base, "classes2.dex" to app), r)
        for (owner in listOf("Llib/Base;", "Lapp/Child;", "Lapp/Grandchild;")) assertEquals("read", mapping.methods[owner]?.get("a()I"))
        assertFalse("Lapp/Child;" in mapping.classes)
        val tiny = dir.resolve("mapping.tiny")
        mapping.writeTiny(tiny, listOf("classes.dex" to base, "classes2.dex" to app))
        assertContains(Files.readString(tiny), "c\tapp/Child\tapp/Child\n\tm\t()I\ta\tread\n")
        assertEquals("Lapp/Grandchild;->a()I", mapping.methodsBack()["Lapp/Grandchild;->read()I"])
    }

    @Test
    fun `interfaces propagate and conflicting diamond names reject the entire family`() {
        val a = ".class public interface abstract LI;\n.super Ljava/lang/Object;\n" + method()
        val b = ".class public interface abstract LJ;\n.super Ljava/lang/Object;\n" + method()
        val d = TestApks.dexFile(a + b + cls("LC;", members = method(), interfaces = ".implements LI;\n.implements LJ;\n"))
        val good = VirtualMethodNames.expand(listOf(d), mapOf("LI;" to mapOf("a()I" to "read")))
        assertEquals("read", good["LC;"]?.get("a()I"))
        assertEquals("read", good["LJ;"]?.get("a()I"))
        val bad = VirtualMethodNames.expand(listOf(d), mapOf("LI;" to mapOf("a()I" to "read"), "LJ;" to mapOf("a()I" to "write")))
        assertTrue(bad.isEmpty(), bad.toString())
    }

    @Test
    fun `static private and inaccessible package methods are not overrides`() {
        for (flags in listOf("public static", "private", "")) {
            val base = cls("Llib/Base;", members = method(flags = flags))
            val child = cls("Lapp/Child;", "Llib/Base;", method(flags = "public"))
            val names = VirtualMethodNames.expand(listOf(TestApks.dexFile(base + child)), seeds)
            assertEquals("read", names["Llib/Base;"]?.get("a()I"))
            assertTrue(names["Lapp/Child;"].isNullOrEmpty(), "$flags: $names")
        }
    }

    @Test
    fun `new name collisions reject both base and subclass mappings`() {
        val base = cls("Llib/Base;", members = method())
        for (members in listOf(method() + method("read"), method("read"))) {
            val d = TestApks.dexFile(base + cls("Lapp/Child;", "Llib/Base;", members))
            assertTrue(VirtualMethodNames.expand(listOf(d), seeds).isEmpty(), "renaming must not create a duplicate or a new override")
        }
    }

    @Test
    fun `return-only overloads cannot create an invalid Java method or an unproved override`() {
        val base = cls("Llib/Base;", members = method())
        for (name in listOf("a", "read")) {
            val child = cls("Lapp/Child;", "Llib/Base;", method(name, "()Ljava/lang/String;"))
            assertTrue(VirtualMethodNames.expand(listOf(TestApks.dexFile(base + child)), seeds).isEmpty())
        }
        val d = TestApks.dexFile(cls("Llib/Base;", members = method() + method("b", "()Ljava/lang/String;")))
        assertTrue(VirtualMethodNames.expand(listOf(d), mapOf("Llib/Base;" to mapOf("a()I" to "read", "b()Ljava/lang/String;" to "read"))).isEmpty())
    }

    @Test
    fun `generic bridge and its concrete implementation share the inherited name`() {
        val d = TestApks.dexFile(cls("Llib/Base;", members = method(proto = "(Ljava/lang/Object;)Ljava/lang/Object;")) +
            cls("Lapp/Child;", "Llib/Base;", method(proto = "(Ljava/lang/String;)Ljava/lang/String;") + """
                .method public bridge synthetic a(Ljava/lang/Object;)Ljava/lang/Object;
                .registers 3
                check-cast v2, Ljava/lang/String;
                invoke-virtual {v1, v2}, Lapp/Child;->a(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
                .end method
            """.trimIndent() + "\n"))
        val names = VirtualMethodNames.expand(listOf(d), mapOf("Llib/Base;" to mapOf("a(Ljava/lang/Object;)Ljava/lang/Object;" to "convert")))
        assertEquals("convert", names["Lapp/Child;"]?.get("a(Ljava/lang/Object;)Ljava/lang/Object;"))
        assertEquals("convert", names["Lapp/Child;"]?.get("a(Ljava/lang/String;)Ljava/lang/String;"))
    }

    @Test
    fun `reverse method names preserve overloaded descriptors and renamed types`() {
        val mapping = NameMapping(mapOf("LA;" to "Llib/A;", "LB;" to "Llib/B;"), emptyMap(), mapOf("LA;" to mapOf("a(LB;)LB;" to "read", "b(I)I" to "read")))
        assertEquals(mapOf("Llib/A;->read(Llib/B;)Llib/B;" to "Llib/A;->a(Llib/B;)Llib/B;", "Llib/A;->read(I)I" to "Llib/A;->b(I)I"), mapping.methodsBack())
    }
}
