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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HardeningAuditTest {
    @TempDir
    lateinit var dir: Path

    private fun anatomy(nativeLibs: List<NativeLibraryEntry>) =
        AppAnatomy("APK", emptyList(), emptyList(), 0, 0, emptyList(), nativeLibs, emptyList(), emptyList(), emptyList())

    @Test
    fun `a packed, RASP-protected app is detected without false positives from benign strings`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        // Genuine protection indicators as string constants, next to benign look-alikes that must
        // NOT match ("ExposedDropdownMenu" contains "xposed", "blackfriday" contains "frida").
        val pin = "sha256/" + "A".repeat(43) + "="
        val sources = mapOf(
            "app/Guard.java" to """
                package app;
                public class Guard {
                    static final String[] S = {
                        "com.topjohnwu.magisk", "/system/bin/su",
                        "ro.kernel.qemu", "goldfish",
                        "re.frida.server", "gum-js-loop", "de.robv.android.xposed",
                        "$pin",
                        "ExposedDropdownMenu", "blackfriday", "Signature is invalid"
                    };
                    public String[] get() { return S; }
                }
            """.trimIndent(),
        )
        val build = ForwardCompiler(profile).compile(sources, dir.resolve("build"), 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)

        // A Qihoo 360 Jiagu packer, by its native library.
        val nativeLibs = listOf(NativeLibraryEntry("lib/arm64-v8a/libjiagu.so", "arm64-v8a", 1024))
        val h = HardeningAudit.analyze(dex, anatomy(nativeLibs), listOf("lib/arm64-v8a/libjiagu.so", "assets/libjiagu.so"), "<manifest/>")

        assertEquals("HARDENED", h.level, "a packer present means HARDENED")
        assertTrue(h.protectors.any { it.name.contains("Jiagu") && it.kind == "PACKER" }, h.protectors.toString())
        val defenses = h.defenses.map { it.name }
        assertTrue(defenses.any { it.startsWith("Root detection") }, defenses.toString())
        assertTrue(defenses.any { it.startsWith("Emulator detection") }, defenses.toString())
        assertTrue(defenses.any { it.contains("Frida") }, defenses.toString())
        assertTrue(h.defenses.any { it.name.startsWith("TLS certificate pinning") && it.evidence == "OBSERVED" }, h.defenses.toString())
        // The benign look-alikes did not create a tamper or extra hooking finding.
        assertTrue(h.defenses.none { it.name.startsWith("Signature") }, "\"Signature is invalid\" must not read as a tamper check")
        assertEquals(1, h.defenses.count { it.name.contains("Frida") }, "no duplicate hooking finding from 'ExposedDropdownMenu'/'blackfriday'")
    }

    @Test
    fun `an unprotected, unobfuscated app reports NONE`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        val sources = (1..30).associate { "com/example/app/Screen$it.java" to "package com.example.app;\npublic class Screen$it { public String title() { return \"Screen $it\"; } }\n" }
        val build = ForwardCompiler(profile).compile(sources, dir.resolve("plain"), 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)

        val h = HardeningAudit.analyze(dex, anatomy(emptyList()), emptyList(), "<manifest/>")
        assertEquals("NONE", h.level)
        assertTrue(h.protectors.isEmpty() && h.defenses.isEmpty())
        // Full, readable class names: not obfuscated.
        assertEquals("NONE", h.obfuscation.level, h.obfuscation.evidence)
        assertTrue(h.recoveryImpact.contains("not obfuscated"))
    }
}
