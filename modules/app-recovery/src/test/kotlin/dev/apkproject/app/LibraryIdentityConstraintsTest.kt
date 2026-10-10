package dev.apkproject.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryIdentityConstraintsTest {
    private fun profiles(source: String) = LibraryMatcher.profiles(listOf(TestApks.dexFile(source)))
    private fun holder(type: String) = """
        .class public LHolder;
        .super Ljava/lang/Object;
        .field public first:$type
        .method public accept($type)V
        .registers 2
        return-void
        .end method
    """.trimIndent() + "\n"

    private fun iterator(name: String, body: String) = """
        .class public $name
        .super Ljava/lang/Object;
        .method public next()Ljava/lang/Object;
        .registers 2
        ${ (1..12).joinToString("\n") { "const-string v0, \"iterator diagnostic $it\"" } }
        $body
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `incompatible final return classes veto both feature and structural claims`() {
        val lib = profiles(holder("LIterator;") + iterator("LIterator;", "new-instance v0, LBox;\nreturn-object v0") +
            ".class public final LBox;\n.super Ljava/lang/Object;\n")
        val apk = profiles(holder("La;") + iterator("La;", "const/4 v0, #1\ninvoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\nmove-result-object v0\nreturn-object v0"))
        assertTrue(LibraryMatcher.rank(apk, lib, emptyMap()).getValue("La;").first().second >= 0.75)
        for (known in listOf(emptyMap(), mapOf("LHolder;" to "LHolder;"))) {
            assertTrue(LibraryMatcher.match(apk, lib, known).classes.none { it.obfuscated == "La;" })
        }
    }

    @Test
    fun `overlapping unknown and nonfinal return bounds remain compatible`() {
        val integer = "const/4 v0, #1\ninvoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\nmove-result-object v0\nreturn-object v0"
        val apk = profiles(holder("La;") + iterator("La;", integer))
        for (reference in listOf(integer,
            "const/4 v0, #0\nreturn-object v0",
            "invoke-static {}, LFactory;->make()Ljava/lang/Number;\nmove-result-object v0\nreturn-object v0",
            "if-eqz v1, :other\nconst-string v0, \"text\"\nreturn-object v0\n:other\n$integer")) {
            val lib = profiles(holder("LIterator;") + iterator("LIterator;", reference))
            assertEquals("LIterator;", LibraryMatcher.match(apk, lib, mapOf("LHolder;" to "LHolder;")).classes.single { it.obfuscated == "La;" }.original)
        }
    }

    private fun utility(name: String, constructor: Boolean = false) = """
        .class public $name
        .super Ljava/lang/Object;
        ${if (constructor) ".method public <init>()V\n.registers 1\ninvoke-direct {v0}, Ljava/lang/Object;-><init>()V\nreturn-void\n.end method" else ""}
        .method public static operation()V
        .registers 1
        const-string v0, "distinct utility diagnostic"
        invoke-static {v0}, Ljava/util/Objects;->requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;
        return-void
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `reference array storage distinguishes boxed collections from primitive arrays`() {
        val body = "const/4 v0, #0\nreturn-object v0"
        fun collection(name: String, type: String) = iterator(name, body).replace(".method public next", ".field public values:$type\n.method public next")
        val apk = profiles(collection("La;", "[Ljava/lang/Object;"))
        val library = profiles(collection("LReferenceCollection;", "[Ljava/lang/Object;") + collection("LPrimitiveCollection;", "[I"))
        assertEquals("LReferenceCollection;", LibraryMatcher.match(apk, library).classes.single().original)
        // Object fields can become arrays through type strengthening; do not reject that case.
        val broad = profiles(collection("LReferenceCollection;", "Ljava/lang/Object;"))
        assertEquals("LReferenceCollection;", LibraryMatcher.match(apk, broad).classes.single().original)
    }

    @Test
    fun `folded static code does not identify an ancestor over its empty facade`() {
        val facade = ".class public LFacade;\n.super LUtility;\n"
        val lib = profiles(holder("LUtility;") + utility("LUtility;") + facade)
        val apk = profiles(holder("La;") + utility("La;"))
        assertTrue(LibraryMatcher.match(apk, lib, mapOf("LHolder;" to "LHolder;")).classes.none { it.obfuscated == "La;" })
        // An independently surviving facade removes this particular ambiguity.
        val intact = profiles(holder("La;") + utility("La;") + facade.replace("LUtility;", "La;"))
        assertEquals("LUtility;", LibraryMatcher.match(intact, lib, mapOf("LHolder;" to "LHolder;", "LFacade;" to "LFacade;")).classes.single { it.obfuscated == "La;" }.original)
    }

    @Test
    fun `instance state and constructors are not treated as erased static facades`() {
        for (suffix in listOf(".field public state:I\n", ".method public run()V\n.registers 1\nreturn-void\n.end method\n")) {
            val lib = profiles(holder("LUtility;") + utility("LUtility;") + ".class public LChild;\n.super LUtility;\n" + suffix)
            val apk = profiles(holder("La;") + utility("La;"))
            assertEquals("LUtility;", LibraryMatcher.match(apk, lib, mapOf("LHolder;" to "LHolder;")).classes.single { it.obfuscated == "La;" }.original)
        }
        val lib = profiles(holder("LUtility;") + utility("LUtility;", true) + ".class public LFacade;\n.super LUtility;\n")
        val apk = profiles(holder("La;") + utility("La;", true))
        assertEquals("LUtility;", LibraryMatcher.match(apk, lib, mapOf("LHolder;" to "LHolder;")).classes.single { it.obfuscated == "La;" }.original)
    }
}
