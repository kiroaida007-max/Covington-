package dev.apkproject.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMemberEvidenceTest {
    private fun source(vararg methods: String) = ".class public Llib/Client;\n.super Ljava/lang/Object;\n" + methods.joinToString("\n")
    private fun method(name: String, literal: String, flags: String = "public") = """
        .method $flags $name(Ljava/lang/String;)V
        .registers 3
        const-string v0, "$literal"
        invoke-static {v0}, Ljava/util/Objects;->requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;
        return-void
        .end method
    """.trimIndent()
    private fun match(lib: String, apk: String): LibraryMatcher.Result {
        val references = LibraryMatcher.profiles(listOf(TestApks.dexFile(lib)))
        val actual = LibraryMatcher.profiles(listOf(TestApks.dexFile(apk)))
        return LibraryMatcher.match(actual, references, mapOf("Llib/Client;" to "Llib/Client;"))
    }

    @Test
    fun `distinctive method strings resolve otherwise identical prototypes`() {
        val lib = source(method("send", "request queued"), method("cancel", "request cancelled"))
        val apk = source(method("a", "request queued"), method("b", "request cancelled"))
        assertEquals(mapOf("a(Ljava/lang/String;)V" to "send", "b(Ljava/lang/String;)V" to "cancel"), match(lib, apk).methods["Llib/Client;"])
    }

    @Test
    fun `two distinctive framework members can resolve names without literals`() {
        fun body(name: String, calls: String) = ".method public $name(Ljava/lang/String;)V\n.registers 3\n$calls\nreturn-void\n.end method\n"
        val read = "invoke-virtual {v2}, Ljava/lang/String;->trim()Ljava/lang/String;\ninvoke-virtual {v2}, Ljava/lang/String;->length()I"
        val write = "invoke-virtual {v2}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;\ninvoke-virtual {v2}, Ljava/lang/String;->hashCode()I"
        val r = match(source(body("read", read), body("write", write)), source(body("a", read), body("b", write)))
        assertEquals("read", r.methods["Llib/Client;"]?.get("a(Ljava/lang/String;)V"))
        assertEquals("write", r.methods["Llib/Client;"]?.get("b(Ljava/lang/String;)V"))
    }

    @Test
    fun `common fingerprints and contradictory inline evidence do not resolve ambiguity`() {
        val lib = source(method("send", "request"), method("cancel", "request"))
        assertTrue(match(lib, source(method("a", "request"))).methods.isEmpty())
        val distinct = source(method("send", "request queued"), method("cancel", "request cancelled"))
        val merged = method("a", "request queued").replace("return-void", "const-string v0, \"request cancelled\"\nreturn-void")
        assertTrue(match(distinct, source(merged)).methods.isEmpty())
    }

    @Test
    fun `a method name cannot be assigned twice or collide with a kept declaration`() {
        val lib = source(method("send", "request queued"), method("cancel", "request cancelled"))
        for (other in listOf("b", "send")) {
            val r = match(lib, source(method("a", "request queued"), method(other, "request queued")))
            assertTrue(r.methods.isEmpty(), "a fingerprint must not merge two declarations: ${r.methods}")
        }
    }

    @Test
    fun `generated method names are withheld while bridges remain eligible`() {
        val generated = source(method("lambda\$send\$0\$lib-Client", "request queued", "public synthetic"))
        assertTrue(match(generated, source(method("a", "request queued"))).methods.isEmpty())
        assertTrue(match(source(method("send", "request queued")), source(method("a", "request queued", "public synthetic"))).methods.isEmpty())
        val bridges = match(source(method("send", "request queued", "public bridge synthetic")), source(method("a", "request queued", "public bridge synthetic")))
        assertEquals("send", bridges.methods["Llib/Client;"]?.get("a(Ljava/lang/String;)V"))
    }

    @Test
    fun `constructors and initializers are never candidates for ordinary method names`() {
        val constructor = method("<init>", "request queued", "public constructor")
        assertTrue(match(source(constructor), source(method("a", "request queued"))).methods.isEmpty())
        val initializer = method("<clinit>", "request queued", "static").replace("(Ljava/lang/String;)V", "()V")
        val ordinary = method("a", "request queued", "public static").replace("(Ljava/lang/String;)V", "()V")
        assertTrue(match(source(initializer), source(ordinary)).methods.isEmpty())
    }
}
