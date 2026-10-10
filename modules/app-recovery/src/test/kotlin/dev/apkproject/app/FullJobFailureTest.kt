package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.Profiles
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullJobFailureTest {
    @TempDir lateinit var dir: Path
    private fun profile() = Profiles.load(Path.of(System.getProperty("apkproject.root"), "profiles",
        if (dev.apkproject.contracts.isWindows()) "android-windows.json" else "android-full.json")).also {
        assumeTrue(it.jadxJar?.let(Files::isRegularFile) == true, "full toolchain required")
    }
    private val options = FullRecoveryOptions(rea = false, javaScript = false, nameLibraries = false, resolveLibraries = false,
        crossCheck = false, roundTrip = false, compileCheck = false, repair = false, threads = 1)

    @Test fun `queued full jobs can be cancelled without waiting for the active job`() {
        val profile = profile()
        val input = TestApks.write(dir, "app.apk", TestApks.zip(mapOf("classes.dex" to TestApks.dex())))
        val queued = java.util.concurrent.CountDownLatch(1)
        val cancel = CancellationToken()
        val result = java.util.concurrent.atomic.AtomicReference<FullRecoveryResult?>()
        val governor = dev.apkproject.compiler.ResourceGovernor
        governor.withJob(governor.plan(), governor.defaultRoot(), CancellationToken(), {}) {
            val thread = Thread({ result.set(FullRecoverySupervisor.run(profile, options, input, dir.resolve("output"), dir.resolve("job"), cancel,
                { if (it.startsWith("resources: waiting")) queued.countDown() })) }, "queue-test").apply { isDaemon = true; start() }
            try { assertTrue(queued.await(15, java.util.concurrent.TimeUnit.SECONDS), "worker did not enter the resource queue") }
            finally { cancel.cancel(); thread.join(10000) }
            assertFalse(thread.isAlive)
            assertEquals(ExitCode.CANCELLED, result.get()?.exitCode)
        }
    }

    @Test fun `a worker whose supervisor disappeared exits without publishing`() {
        val input = TestApks.write(dir, "app.apk", TestApks.zip(mapOf("classes.dex" to TestApks.dex())))
        val files = FullJobFiles(Files.createDirectories(dir.resolve("job")))
        val profile = profile()
        val request = FullJobRequest(id = "abandoned", input = input.toString(), output = dir.resolve("output").toString(),
            profile = WorkerProfile.from(profile), options = options, inputSha256 = dev.apkproject.contracts.Hashing.sha256(input))
        dev.apkproject.store.AtomicFiles.writeString(files.request, dev.apkproject.contracts.Json.encode(request))
        val process = dev.apkproject.compiler.ProcessWorker.run(listOf(profile.java.toString(), "-Xmx512m", "-cp",
            System.getProperty("apkproject.worker.classpath"), RecoveryWorker::class.java.name, files.dir.toString(), Long.MAX_VALUE.toString()),
            dir, 15000, supervised = true)
        assertTrue(process.ok, process.stderr)
        assertEquals(130, files.readResult()?.exitCode)
        assertFalse(Files.exists(dir.resolve("output")))
    }

    @Test fun `a killed full worker leaves diagnostics and can resume in a fresh process`() {
        val input = TestApks.write(dir, "app.apk", TestApks.zip(mapOf("classes.dex" to TestApks.dex())))
        val job = dir.resolve("job")
        val result = FullRecoverySupervisor.run(profile(), options, input, dir.resolve("output"), job, timeoutMs = 1)
        assertEquals(ExitCode.WORKER_OR_RESOURCE_FAILURE, result.exitCode)
        assertNotNull(FullJobFiles(job).readResult())
        assertTrue(Files.isRegularFile(job.resolve("worker-stderr.log")))
        assertEquals("FAILED", FullJobHistory.list(dir).single().state.status)
        val bundle = dir.resolve("diagnostics.zip")
        RecoveryDiagnostics.export(job, bundle)
        java.util.zip.ZipFile(bundle.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("diagnostics.json" in names && "state.json" in names)
            assertFalse(names.any { it.endsWith(".apk") || it.endsWith(".java") || it == "request.json" })
            assertTrue(zip.entries().asSequence().all { it.size <= 65536 })
        }
        assertFalse(Files.exists(dir.resolve("output")))
        val resumed = FullRecoverySupervisor.resume(job)
        assertNotNull(resumed.outDir, resumed.message)
        Files.writeString(dir.resolve("output/README.md"), "user edit")
        assertEquals(ExitCode.INVALID_CONFIGURATION, FullRecoverySupervisor.resume(job).exitCode)
        assertEquals("user edit", Files.readString(dir.resolve("output/README.md")))
    }

    @Test fun `changed inputs cannot reuse a job and malformed inputs cannot publish a project`() {
        val input = Files.write(dir.resolve("broken.apk"), ByteArray(10))
        val job = dir.resolve("job")
        val result = FullRecoverySupervisor.run(profile(), options, input, dir.resolve("output"), job)
        assertEquals(ExitCode.MALFORMED_INPUT, result.exitCode)
        assertFalse(Files.exists(dir.resolve("output")))
        Files.write(input, ByteArray(11))
        assertEquals(ExitCode.INVALID_CONFIGURATION, FullRecoverySupervisor.resume(job).exitCode)
    }

    @Test fun `cancellation before a full worker starts keeps the job resumable`() {
        val input = TestApks.write(dir, "app.apk", TestApks.zip(mapOf("classes.dex" to TestApks.dex())))
        val job = dir.resolve("job")
        val token = CancellationToken().apply { cancel() }
        val result = FullRecoverySupervisor.run(profile(), options, input, dir.resolve("output"), job, token)
        assertEquals(ExitCode.CANCELLED, result.exitCode)
        assertFalse(Files.exists(dir.resolve("output")))
        assertNotNull(FullRecoverySupervisor.resume(job).outDir)
    }
}
