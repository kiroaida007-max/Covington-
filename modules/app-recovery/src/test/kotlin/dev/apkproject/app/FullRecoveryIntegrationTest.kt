package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.Profiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole `decompile` pipeline on an APK built here from known source. The source reproduces a
 * real JADX mistake (a float division printed as an int division), so the test also checks that the
 * round trip catches it and the verified repair fixes it. Skipped without the full toolchain.
 */
class FullRecoveryIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val source = """
        package com.example.demo;

        public class Demo {
            public static final String API = "https://api.example.com/v2/users";
            private float scale;

            public static int clamp(int x, int lo, int hi) {
                if (x < lo) {
                    return lo;
                }
                if (x > hi) {
                    return hi;
                }
                return x;
            }

            public void onSurfaceChanged(int width, int height) {
                float ratio = (float) width / height;
                this.scale = ratio * 2.0f;
            }

            public String greet(String name) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    sb.append(name).append(i);
                }
                return sb.toString();
            }

            public native int nativeSum(int a, int b);
        }
    """.trimIndent()

    @Test
    fun `decompile recovers, verifies and repairs a small app`() {
        val root = Paths.get(System.getProperty("apkproject.root"))
        val profile = Profiles.load(root.resolve(if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/android-full.json"))
        val tools = listOfNotNull(profile.jadxJar, profile.ecjJar, profile.androidJar, profile.d8Jar)
        assumeTrue(tools.size == 4 && tools.all { Files.isRegularFile(it) } && Files.isExecutable(profile.javac) && Files.isExecutable(profile.z3), "full toolchain not installed; run scripts/setup-toolchain.sh --full")

        val build = ForwardCompiler(profile).compile(mapOf("com/example/demo/Demo.java" to source), dir.resolve("build"), 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val apk = TestApks.write(dir, "demo.apk", TestApks.zip(mapOf("classes.dex" to Files.readAllBytes(assertNotNull(build.dexFile)))))

        val logs = ArrayList<String>()
        val options = FullRecoveryOptions(rea = false, javaScript = false, resolveLibraries = false, repairBudgetMs = 300_000)
        val result = FullRecovery(profile, options, dev.apkproject.compiler.CancellationToken()) { logs += it }.run(apk, dir.resolve("out"))
        val out = assertNotNull(result.outDir, result.message + "\n" + logs.joinToString("\n"))
        assertTrue(result.exitCode == ExitCode.EXPORT_POLICY_SATISFIED || result.exitCode == ExitCode.UNSUPPORTED_OR_PARTIAL, result.message)
        val report = assertNotNull(result.report)

        // Proof: clamp is inside the V0 model and is proved against the bytecode.
        assertTrue((report.crossCheck?.proved ?: 0) >= 1, "cross-check: ${report.crossCheck}")
        // Round trip: every method ends equivalent, the decompiler's float mistake included (repaired).
        val methods = Json.parseToJsonElement(Files.readString(out.resolve("recovery/round-trip.json"))).jsonArray.map { it.jsonObject }
        val verdicts = methods.associate { it["method"]!!.jsonPrimitive.content.substringAfter("->") to it["result"]!!.jsonPrimitive.content }
        for (m in listOf("clamp(III)I", "onSurfaceChanged(II)V", "greet(Ljava/lang/String;)Ljava/lang/String;")) {
            assertTrue(verdicts[m] in RoundTripVerifier.EQUIVALENT, "$m: ${verdicts[m]}\n${logs.joinToString("\n")}")
        }
        val recovered = Files.readString(out.resolve("app/src/main/java/com/example/demo/Demo.java"))
        assertTrue(Regex("""\(\(?float\)""").containsMatchIn(recovered.substringAfter("onSurfaceChanged").substringBefore("}")), "the float division must be in the recovered source:\n$recovered")
        // Backend contract and native boundary.
        assertContains(Files.readString(out.resolve("backend/openapi.yaml")), "api.example.com")
        assertContains(Files.readString(out.resolve("native/jni-map.json")), "nativeSum")
        val backendAudit = Json.parseToJsonElement(Files.readString(out.resolve("backend/contract-audit.json"))).jsonObject
        assertContains(backendAudit.getValue("scope").jsonPrimitive.content, "not original server behavior")
        // The report says what it did.
        val md = Files.readString(out.resolve("recovery/RECOVERY.md"))
        assertContains(md, "## Round-trip verification")
        assertTrue(Files.isRegularFile(out.resolve("README.md")))
    }
}
