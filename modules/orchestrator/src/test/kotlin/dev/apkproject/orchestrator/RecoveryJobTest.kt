package dev.apkproject.orchestrator

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.ExportManifest
import dev.apkproject.contracts.JobState
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.RecoveryPolicy
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.contracts.WorkerEnvelope
import dev.apkproject.dexasm.Assembler
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryJobTest {
    @TempDir lateinit var dir: Path

    private fun profile(): ResolvedProfile {
        val p = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve(if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/v0-jvm-pure.json"))
        assumeTrue(Doctor.verify(p).all { it.status == CheckStatus.PASS }, "toolchain not installed; run scripts/setup-toolchain.sh")
        return p
    }

    private fun apk(source: String, name: String = "demo/Tiny.java"): Path {
        val built = ForwardCompiler(profile()).compile(mapOf(name to source), dir.resolve("hidden-build"), 120_000)
        assertEquals(CheckStatus.PASS, built.status, built.diagnostics)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.write(Files.readAllBytes(built.dexFile!!)); z.closeEntry()
        }
        return dir.resolve("tiny.apk").also { Files.write(it, out.toByteArray()) }
    }

    private val tiny = "package demo;\npublic class Tiny {\n" +
        "  public static int abs(int x) { return x < 0 ? -x : x; }\n" +
        "  public static int quot(int a, int b) { return b == 0 ? -1 : a / b; }\n" +
        "  public static int lerp(int a, int b, int t) { return a + (b - a) * t / 100; }\n}\n"

    @Test
    fun `recovers, journals and exports a small APK`() {
        val p = profile()
        val run = dir.resolve("run")
        val result = RecoveryJob(p).run(apk(tiny), run)
        assertEquals(JobState.ACCEPTED, result.state, result.summary)
        assertEquals(ExitCode.EXPORT_POLICY_SATISFIED, result.exitCode)

        // The journal is ordered and only contains legal transitions.
        val events = Files.readAllLines(run.resolve("journal.ndjson")).map { Json.decode<WorkerEnvelope>(it) }
        assertEquals(events.indices.map { it + 1L }, events.map { it.sequence })
        val states = events.filter { it.event.startsWith("state.") }.map { JobState.valueOf(it.event.removePrefix("state.")) }
        assertEquals(listOf(JobState.INGESTED, JobState.DECODED, JobState.MODELED, JobState.CANDIDATE, JobState.COMPILED, JobState.ACCEPTED), states)

        val report = Json.decode<ExportManifest>(Files.readString(run.resolve("reports/recovery.json")))
        assertEquals(4, report.methodsInScope) // three methods + implicit constructor
        assertEquals(4, report.methodsAccepted)
        assertTrue(report.methods.filter { it.method.name != "<init>" }.all { m -> m.obligations.single().status == CheckStatus.PASS })

        val project = dir.resolve("project")
        val exported = Operations.export(p, run, project, allowPartial = false)
        assertEquals(ExitCode.EXPORT_POLICY_SATISFIED, exported.exitCode, exported.message)
        val source = Files.readString(project.resolve("src/main/java/demo/Tiny.java"))
        assertContains(source, "public static int abs(int p0)")
        assertContains(source, "return p0 < 0 ? -p0 : p0;")
        for (f in listOf("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradle/wrapper/gradle-wrapper.properties", "src/test/java/demo/TinyRecoveredTest.java", "recovery/report.json", "recovery/source-map.json", "recovery/disassembly/classes.dasm", "recovery/files.sha256", "README.md")) {
            assertTrue(Files.exists(project.resolve(f)), "missing $f")
        }
        assertContains(Files.readString(project.resolve("src/test/java/demo/TinyRecoveredTest.java")), "assertEquals(")
        assertEquals(JobState.EXPORTED, JobDirectory(run).readState()!!.state)
        // A second export into a non-empty directory is refused rather than merged.
        assertEquals(ExitCode.INVALID_CONFIGURATION, Operations.export(p, run, project, allowPartial = false).exitCode)
    }

    @Test
    fun `malformed input fails with exit code 3`() {
        val p = profile()
        val bad = dir.resolve("bad.apk").also { Files.write(it, ByteArray(300) { (it * 7).toByte() }) }
        val result = RecoveryJob(p).run(bad, dir.resolve("run-bad"))
        assertEquals(JobState.FAILED, result.state)
        assertEquals(ExitCode.MALFORMED_INPUT, result.exitCode)
    }

    @Test
    fun `input with nothing in scope is partial, never stubbed`() {
        val p = profile()
        val dex = Assembler.assemble(
            ".class public LOnly;\n.super Ljava/lang/Object;\n.method public static f(I)I\n.registers 2\n" +
                "invoke-static {v1}, Ljava/lang/Math;->abs(I)I\nmove-result v0\nreturn v0\n.end method\n",
        )
        val input = dir.resolve("only.dex").also { Files.write(it, dex) }
        val run = dir.resolve("run-only")
        val result = RecoveryJob(p).run(input, run)
        assertEquals(JobState.PARTIAL, result.state)
        assertEquals(ExitCode.UNSUPPORTED_OR_PARTIAL, result.exitCode)
        val report = Json.decode<ExportManifest>(Files.readString(run.resolve("reports/recovery.json")))
        assertEquals("UNSUPPORTED", report.methods.single().status)
        assertEquals("opcode.unsupported", report.methods.single().gaps.first().code)
        assertEquals(ExitCode.UNSUPPORTED_OR_PARTIAL, Operations.export(p, run, dir.resolve("p"), allowPartial = true).exitCode)
    }

    @Test
    fun `cancelled jobs resume to completion`() {
        val p = profile()
        val input = apk(tiny)
        val run = dir.resolve("run-cancel")
        val token = CancellationToken().also { it.cancel() }
        val cancelled = RecoveryJob(p, cancel = token).run(input, run)
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertEquals(ExitCode.CANCELLED, cancelled.exitCode)
        val resumed = Operations.resume(p, run) {}!!
        assertEquals(JobState.ACCEPTED, resumed.state, resumed.summary)
    }

    @Test
    fun `partial-report policy is satisfied by an honest partial result`() {
        val p = profile()
        val src = "package demo;\npublic class Tiny {\n  public static int twice(int x) { return x + x; }\n" +
            "  public static int sum(int n) { int s = 0; for (int i = 0; i < n; i++) s += i; return s; }\n}\n"
        val run = dir.resolve("run-partial")
        val result = RecoveryJob(p, JobOptions(policy = RecoveryPolicy.PARTIAL_REPORT, experiments = false)).run(apk(src), run)
        assertEquals(JobState.PARTIAL, result.state)
        assertEquals(ExitCode.EXPORT_POLICY_SATISFIED, result.exitCode)
        val project = dir.resolve("partial-project")
        assertEquals(ExitCode.EXPORT_POLICY_SATISFIED, Operations.export(p, run, project, allowPartial = false).exitCode)
        val source = Files.readString(project.resolve("src/main/java/demo/Tiny.java"))
        assertContains(source, "twice")
        assertTrue("sum" !in source, "unrecovered methods are omitted, not stubbed")
    }
}
