package dev.apkproject.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReturnTypeEvidenceTest {
    private fun returns(body: String): Set<String>? {
        val dex = TestApks.dexFile("""
            .class public LExample;
            .super Ljava/lang/Object;
            .method public static value(Ljava/lang/Object;)Ljava/lang/Object;
            .registers 4
            $body
            .end method
        """.trimIndent())
        return LibraryMatcher.profiles(listOf(dex)).getValue("LExample;")
            .methodEvidence.getValue("value(Ljava/lang/Object;)Ljava/lang/Object;").returnedTypes
    }

    @Test
    fun `tracks boxed results through register aliases`() {
        assertEquals(setOf("Ljava/lang/Integer;"), returns("""
            const/4 v0, #1
            invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v0
            move-object v1, v0
            return-object v1
        """.trimIndent()))
    }

    @Test
    fun `all return sites contribute rather than only the last one`() {
        assertEquals(setOf("Ljava/lang/String;", "Ljava/lang/Class;"), returns("""
            if-eqz v3, :other
            const-string v0, "text"
            return-object v0
            :other
            const-class v0, LExample;
            return-object v0
        """.trimIndent()))
    }

    @Test
    fun `unknown nullable and overwritten results prevent a contradiction`() {
        for (body in listOf(
            "return-object v3",
            "const-string v0, \"text\"\nconst/4 v0, #0\nreturn-object v0",
            "const-string v0, \"text\"\nif-eqz v3, :join\nconst-class v0, LExample;\n:join\nreturn-object v0",
            "if-eqz v3, :other\nconst-string v0, \"text\"\nreturn-object v0\n:other\nreturn-object v3",
            ":start\ninvoke-static {}, LSource;->read()V\n:end\nconst-string v0, \"text\"\nreturn-object v0\n:handler\nmove-exception v1\nreturn-object v3\n.catch Ljava/lang/Exception; {:start .. :end} :handler",
        )) assertNull(returns(body), body)
    }

    @Test
    fun `field loads and casts provide bounds not the declared Object return`() {
        assertEquals(setOf("Ljava/lang/String;"), returns("check-cast v3, Ljava/lang/String;\nreturn-object v3"))
        assertEquals(setOf("Ljava/lang/Boolean;"), returns("sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\nreturn-object v0"))
    }
}
