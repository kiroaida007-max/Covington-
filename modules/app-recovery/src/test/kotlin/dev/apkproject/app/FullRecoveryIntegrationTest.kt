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
        val jobDir = dir.resolve("job")
        val result = FullRecoverySupervisor.run(profile, options, apk, dir.resolve("out"), jobDir,
            dev.apkproject.compiler.CancellationToken(), { logs += it })
        val jobResult = assertNotNull(FullJobFiles(jobDir).readResult())
        assertTrue(jobResult.workerPid != ProcessHandle.current().pid() && jobResult.workerPid > 0)
        assertTrue(ProcessHandle.of(jobResult.workerPid).map { !it.isAlive }.orElse(true))
        val sourceHash = dev.apkproject.contracts.Hashing.sha256(dir.resolve("out/app/src/main/java/com/example/demo/Demo.java"))
        // Simulate a lost final publication after all expensive stages completed. A fresh JVM
        // must revalidate and reuse those snapshots, including the verified source repair.
        dev.apkproject.store.AtomicFiles.deleteRecursively(dir.resolve("out"))
        listOf("result.json", "publication.json", "output-files.json").forEach { Files.deleteIfExists(jobDir.resolve(it)) }
        val resumedLog = ArrayList<String>()
        val resumed = FullRecoverySupervisor.resume(jobDir, log = { resumedLog += it })
        assertNotNull(resumed.outDir, resumed.message)
        assertTrue(resumedLog.any { it.startsWith("checkpoint: restored decompile") }, resumedLog.joinToString("\n"))
        assertTrue(resumedLog.any { it.startsWith("checkpoint: restored verification") }, resumedLog.joinToString("\n"))
        assertEquals(sourceHash, dev.apkproject.contracts.Hashing.sha256(dir.resolve("out/app/src/main/java/com/example/demo/Demo.java")))
        assertEquals(result.report?.roundTrip?.equivalent, resumed.report?.roundTrip?.equivalent)
        assertEquals(resumed.report, FullRecoverySupervisor.resume(jobDir).report)
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
        val feedback = Files.readAllLines(out.resolve("recovery/learning-repairs.jsonl")).filter { it.isNotBlank() }.map { dev.apkproject.contracts.Json.decode<RepairLearningExample>(it) }
        assertTrue(feedback.any { it.label == 1 && it.improvedMethods > 0 && it.comparedMethods > 0 }, "Successful checked repair must produce local learning feedback")
        assertTrue(feedback.all { it.labelEvidence == "COMPLETE_ROUNDTRIP_NO_REGRESSION" })
        assertTrue(Regex("""\(\(?float\)""").containsMatchIn(recovered.substringAfter("onSurfaceChanged").substringBefore("}")), "the float division must be in the recovered source:\n$recovered")
        // Backend contract and native boundary.
        assertContains(Files.readString(out.resolve("backend/openapi.yaml")), "api.example.com")
        assertContains(Files.readString(out.resolve("native/jni-map.json")), "nativeSum")
        val backendAudit = Json.parseToJsonElement(Files.readString(out.resolve("backend/contract-audit.json"))).jsonObject
        assertContains(backendAudit.getValue("scope").jsonPrimitive.content, "not original server behavior")
        // Privacy map and SBOM, written beside the UI audit.
        assertTrue(Files.isRegularFile(out.resolve("audit/privacy.json")) && Files.isRegularFile(out.resolve("audit/sbom.cdx.json")))
        assertEquals(0, assertNotNull(report.privacy).flows)
        assertNotNull(report.supplyChain)
        assertTrue(report.layers.any { it.layer == "Privacy map" } && report.layers.any { it.layer == "Software bill of materials" }, report.layers.toString())
        // The report says what it did.
        val md = Files.readString(out.resolve("recovery/RECOVERY.md"))
        assertContains(md, "## Round-trip verification")
        assertTrue(Files.isRegularFile(out.resolve("README.md")))
    }
}
