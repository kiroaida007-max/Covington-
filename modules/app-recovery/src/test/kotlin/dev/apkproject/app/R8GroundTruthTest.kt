package dev.apkproject.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class R8GroundTruthTest {
    @Test
    fun `mapping retains merged origins and translates overloaded array descriptors`() {
        val truth = R8GroundTruth("""
            lib.Parent -> a:
                1:2:lib.Parent[] read(lib.Child[],int):10:11 -> b
                int read(int) -> c
                1:1:int lib.Other.inlined(int):1:1 -> c
            lib.Child -> d:
            lib.Merge -> e:
            lib.Other -> e:
        """.trimIndent().lines())
        assertEquals("Llib/Parent;", truth.classes["La;"])
        assertEquals(setOf("Llib/Merge;", "Llib/Other;"), truth.origins["Le;"])
        assertNull(truth.classes["Le;"])
        assertEquals(setOf("read"), truth.methods["La;"]?.get("b([Ld;I)[La;"))
        assertEquals(setOf("read"), truth.methods["La;"]?.get("c(I)I"))
    }

    @Test
    fun `local inline ambiguity remains visible instead of counting it as correct`() {
        val truth = R8GroundTruth("""
            lib.Parent -> a:
                1:2:int helper(int):1:2 -> b
                1:2:int read(int):5 -> b
                java.lang.String name() -> c
                int field -> x
        """.trimIndent().lines())
        assertEquals(setOf("helper", "read"), truth.methods["La;"]?.get("b(I)I"))
        assertEquals(setOf("name"), truth.methods["La;"]?.get("c()Ljava/lang/String;"))
        assertEquals(2, truth.methods.getValue("La;").size)
    }
}
