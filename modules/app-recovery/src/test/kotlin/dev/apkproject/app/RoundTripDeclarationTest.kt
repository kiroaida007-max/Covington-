package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import dev.apkproject.dexasm.Assembler
import dev.apkproject.lift.MethodIndex
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class RoundTripDeclarationTest {
    private fun dex(flags: String): DexFile = (DexFile.parse(Assembler.assemble(
        ".class public Lp/Flags;\n.super Ljava/lang/Object;\n.method $flags f()I\n.registers 1\nconst/4 v0, #7\nreturn v0\n.end method\n"
    )) as Outcome.Ok).value
    private fun compare(original: String, recovered: String): MethodRoundTrip {
        val a = dex(original); val b = dex(recovered)
        val profile = Profiles.load(Path.of(System.getProperty("apkproject.root")).resolve(
            if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/android-full.json"))
        val verifier = RoundTripVerifier(profile)
        val owners = verifier.originalByClass(listOf("original.dex" to a), setOf("p/Flags.java"))
        val methods = b.methods().associateBy { it.key.display() }
        val compiled = RoundTripVerifier.Recompiled(mapOf("release" to methods, "debug" to methods), emptySet(),
            mapOf("release" to MethodIndex(listOf(b)), "debug" to MethodIndex(listOf(b))))
        return verifier.compare(owners, compiled, emptyMap()).single()
    }

    @Test fun `identical constant-return bodies cannot hide a static versus instance declaration change`() {
        val result = compare("public static", "public")
        assertEquals("DIFFERENT", result.result)
        assertContains(assertNotNull(result.declarationMismatch), "static/instance")
        assertEquals("DIFFERENT", compare("public", "public static").result)
    }

    @Test fun `implicit synchronization changes cannot be called equivalent just because bodies match`() {
        val result = compare("public static declared-synchronized", "public static")
        assertEquals("DIFFERENT", result.result)
        assertContains(assertNotNull(result.declarationMismatch), "synchronization")
        assertEquals("DIFFERENT", compare("public static", "public static synchronized").result)
    }

    @Test fun `both legal synchronization flag encodings preserve an otherwise matching method`() {
        assertTrue(compare("public static synchronized", "public static declared-synchronized").result in RoundTripVerifier.EQUIVALENT)
        assertTrue(compare("public static", "public static").result in RoundTripVerifier.EQUIVALENT)
    }
}
