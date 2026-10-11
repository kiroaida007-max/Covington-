package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Profiles
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.*

class EmbeddedBackendTest {
    @TempDir lateinit var dir: Path
    private fun profile() = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve(
        if (dev.apkproject.contracts.isWindows()) "profiles/android-windows.json" else "profiles/android-full.json")).also { p ->
        assumeTrue(listOfNotNull(p.jadxJar, p.ecjJar, p.androidJar, p.d8Jar).size == 4 &&
            listOfNotNull(p.jadxJar, p.ecjJar, p.androidJar, p.d8Jar).all(Files::isRegularFile), "full toolchain is required")
    }
    private fun fixture(dynamic: Boolean = false, extraHandlers: Int = 0): Map<String, String> {
        fun annotation(pkg: String, name: String, body: String = "") =
            "package $pkg; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface $name { $body }"
        val files = linkedMapOf(
            "javax/ws/rs/Path.java" to annotation("javax.ws.rs", "Path", "String value();"),
            "javax/ws/rs/GET.java" to annotation("javax.ws.rs", "GET"),
            "javax/ws/rs/QueryParam.java" to annotation("javax.ws.rs", "QueryParam", "String value();"),
            "retrofit2/http/GET.java" to annotation("retrofit2.http", "GET", "String value();"),
            "demo/RemoteClient.java" to "package demo; public interface RemoteClient { @retrofit2.http.GET(\"/remote\") int get(); }",
            "demo/Policy.java" to """
                package demo;
                public class Policy {
                    public ${if (dynamic) "" else "static"} int calculate(int amount) {
                        if (amount < 0) throw new IllegalArgumentException("negative amount \u4e2d\u6587");
                        if (amount >= 100) return amount - amount / 5;
                        return amount + 7;
                    }
                }
            """.trimIndent(),
            "demo/Pricing.java" to """
                package demo;
                @javax.ws.rs.Path("/pricing")
                public class Pricing {
                    @javax.ws.rs.GET @javax.ws.rs.Path("/quote")
                    public static int quote(@javax.ws.rs.QueryParam("amount") int amount) {
                        return ${if (dynamic) "new Policy()" else "Policy"}.calculate(amount);
                    }
                    @javax.ws.rs.GET @javax.ws.rs.Path("/net")
                    public static long net(@javax.ws.rs.QueryParam("amount") long amount) {
                        return amount - amount / 10L;
                    }
                    ${ (0 until extraHandlers).joinToString("\n") { "@javax.ws.rs.GET @javax.ws.rs.Path(\"/extra$it\") public static int extra$it() { return $it; }" } }
                }
            """.trimIndent(),
        )
        return files
    }

    @Test fun `embedded APK exports real dependent calculations and runnable checked HTTP adapter`() {
        val p = profile()
        val mixed = fixture() + mapOf(
            "demo/RuntimePolicy.java" to "package demo; public class RuntimePolicy { public int apply(int amount) { return amount + 2; } }",
            "demo/Review.java" to "package demo; @javax.ws.rs.Path(\"/review\") public class Review { @javax.ws.rs.GET @javax.ws.rs.Path(\"/value\") public static int value(@javax.ws.rs.QueryParam(\"amount\") int amount) { return new RuntimePolicy().apply(amount); } }",
        )
        val original = ForwardCompiler(p).compile(mixed, dir.resolve("original"), 120_000)
        assertEquals(CheckStatus.PASS, original.status, original.diagnostics)
        val apk = TestApks.write(dir, "pricing.apk", TestApks.zip(mapOf("classes.dex" to Files.readAllBytes(original.dexFile!!))))
        val logs = ArrayList<String>()
        val result = FullRecovery(p, FullRecoveryOptions(rea = false, javaScript = false, resolveLibraries = false,
            nameLibraries = false, repair = false, crossCheck = false), log = { logs += it }).run(apk, dir.resolve("recovered"))
        val out = assertNotNull(result.outDir, result.message + logs.joinToString("\n"))
        val backend = out.resolve("backend/embedded")
        val report = Json.decode<EmbeddedBackendReport>(Files.readString(backend.resolve("report.json")))
        assertEquals("RUNNABLE_ADAPTER", report.status, report.toString() + logs.joinToString("\n"))
        assertEquals(3, report.handlers.size)
        assertEquals(2, result.report!!.backend.runnableEmbeddedHandlers)
        assertEquals(setOf("/pricing/quote", "/pricing/net", "/review/value"), report.handlers.flatMap { it.paths }.toSet())
        assertEquals(2, report.handlerChecks.count { it.status == "RUNNABLE" })
        val review = report.handlerChecks.single { "Ldemo/Review;" in it.method }
        assertEquals("REQUIRES_REVIEW", review.status)
        assertTrue(review.issues.any { "Virtual dispatch" in it })
        assertTrue(report.sources.any { it.path == "src/demo/Review.java" })
        assertFalse("Review.java" in Files.readString(backend.resolve("sources.args")))
        assertFalse("RuntimePolicy.java" in Files.readString(backend.resolve("sources.args")))
        assertTrue(report.handlers.none { "RemoteClient" in it.method })
        assertTrue(report.sources.any { it.path == "src/demo/Policy.java" })
        assertTrue(report.methods.all { it.result in RoundTripVerifier.EQUIVALENT })
        assertEquals(Hashing.sha256(apk), report.inputSha256)
        for (file in report.sources) assertEquals(file.sha256, Hashing.sha256(backend.resolve(file.path)))
        assertContains(Files.readString(backend.resolve("RecoveredBackend.java")), "demo.Pricing.quote(intValue(q, \"amount\"))")
        assertTrue(Files.exists(backend.resolve("run.cmd")) && Files.exists(backend.resolve("run.sh")))
        // Explicit source encoding must preserve Unicode constants even under a non-UTF-8 JDK default.
        val compile = ProcessWorker.run(listOf(p.javac.toString(), "-J-Dfile.encoding=windows-1252", "--release", "8", "-encoding", "UTF-8", "-proc:none", "-d", "classes", "@sources.args"), backend, 60_000)
        assertTrue(compile.ok, compile.stderr)

        // Independently execute the known original classes. Compare actual request-dependent answers.
        val oracle = dir.resolve("Oracle.java")
        Files.writeString(oracle, """
            public class Oracle {
                public static void main(String[] a) {
                    try { System.out.print(demo.Pricing.quote(Integer.parseInt(a[0]))); }
                    catch (IllegalArgumentException e) { System.out.print("negative amount \u4e2d\u6587".equals(e.getMessage())); }
                }
            }
        """.trimIndent())
        assertTrue(ProcessWorker.run(listOf(p.javac.toString(), "-cp", original.classesDir.toString(), "-d", original.classesDir.toString(), oracle.toString()), dir, 60_000).ok)
        assertTrue(ProcessWorker.run(listOf(p.javac.toString(), "-encoding", "UTF-8", "-cp", "classes", "-d", "classes", oracle.toString()), backend, 60_000).ok)
        val unicode = ProcessWorker.run(listOf(p.java.toString(), "-cp", "classes", "Oracle", "-1"), backend, 5000)
        assertTrue(unicode.ok, unicode.stderr)
        assertEquals("true", unicode.stdout.trim(), "the recompiled recovered business exception must preserve its Unicode constant")
        val process = ProcessBuilder(p.java.toString(), "-cp", "classes", "RecoveredBackend", "0").directory(backend.toFile())
            .redirectErrorStream(true).start()
        val console = StringBuffer()
        val drainer = Thread {
            runCatching { process.inputStream.bufferedReader().use { reader -> reader.forEachLine { console.append(it.take(1024)).append('\n') } } }
        }.apply { isDaemon = true; start() }
        var port = 0
        fun request(path: String, method: String = "GET"): Pair<Int, String> {
            val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = method; connection.connectTimeout = 1000; connection.readTimeout = 3000
            return try { val status = connection.responseCode; status to (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() } }
            finally { connection.disconnect() }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (port == 0) {
                val text = console.toString()
                port = Regex("127\\.0\\.0\\.1:(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                assertTrue(process.isAlive && System.nanoTime() < deadline, text)
                if (port == 0) Thread.sleep(50)
            }
            for (amount in listOf(0, 10, 99, 100, 250, Int.MAX_VALUE)) {
                val expected = ProcessWorker.run(listOf(p.java.toString(), "-cp", original.classesDir.toString(), "Oracle", amount.toString()), dir, 5000)
                assertTrue(expected.ok, expected.stderr)
                assertEquals(200 to expected.stdout.trim(), request("/pricing/quote?amount=$amount"))
            }
            assertEquals(200 to "830103483316929823", request("/pricing/net?amount=922337203685477581"))
            assertEquals(500, request("/pricing/quote?amount=-1").first, "business exceptions must not become input parse errors")
            for (query in listOf("", "?amount=invalid", "?amount=1&amount=2", "?amount=2147483648")) assertEquals(400, request("/pricing/quote$query").first)
            assertEquals(404, request("/missing?amount=3").first)
            assertEquals(404, request("/review/value?amount=3").first, "an unrelated handler requiring review must not be served")
            assertEquals(405, request("/pricing/quote?amount=3", "POST").first)
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "adapter process did not terminate")
            }
            process.outputStream.close()
            drainer.join(5000)
            process.inputStream.close(); process.errorStream.close()
            assertFalse(drainer.isAlive, "adapter output reader did not terminate")
        }
    }

    @Test fun `runtime dispatch and absent verification export code without runnable claims`() {
        val p = profile()
        val original = ForwardCompiler(p).compile(fixture(dynamic = true), dir.resolve("dynamic"), 120_000)
        assertEquals(CheckStatus.PASS, original.status, original.diagnostics)
        val apk = TestApks.write(dir, "dynamic.apk", TestApks.zip(mapOf("classes.dex" to Files.readAllBytes(original.dexFile!!))))
        val result = FullRecovery(p, FullRecoveryOptions(rea = false, javaScript = false, resolveLibraries = false,
            nameLibraries = false, repair = false, crossCheck = false, roundTrip = false)).run(apk, dir.resolve("out"))
        val backend = assertNotNull(result.outDir).resolve("backend/embedded")
        val report = Json.decode<EmbeddedBackendReport>(Files.readString(backend.resolve("report.json")))
        assertEquals("CODE_EXPORTED", report.status)
        assertTrue(report.issues.any { "Virtual dispatch" in it })
        assertTrue(report.issues.any { "lack matching" in it })
        assertTrue(report.sources.any { it.path.endsWith("Policy.java") })
        assertTrue(report.runnableHandlers.isEmpty())
        assertFalse(Files.exists(backend.resolve("run.cmd")))
        Files.writeString(backend.resolve("run.cmd"), "stale generated launcher")
        EmbeddedBackend.write(backend, report)
        assertFalse(Files.exists(backend.resolve("run.cmd")))
    }

    private fun independent() = "package demo; @javax.ws.rs.Path(\"/independent\") public class Independent { @javax.ws.rs.GET @javax.ws.rs.Path(\"/value\") public static int value(@javax.ws.rs.QueryParam(\"amount\") int amount) { return amount * 3; } }"

    @Test fun `shared helper review crosses DEX parts without blocking unrelated handlers`() {
        val p = profile()
        val helperSources = fixture().filterKeys { it.startsWith("javax/") } + mapOf(
            "demo/RuntimePolicy.java" to "package demo; public class RuntimePolicy { public int apply(int amount) { return amount + 2; } }",
            "demo/Shared.java" to "package demo; public class Shared { public static int calculate(int amount) { return new RuntimePolicy().apply(amount); } }",
        )
        val helpers = ForwardCompiler(p).compile(helperSources, dir.resolve("helpers"), 120_000)
        assertEquals(CheckStatus.PASS, helpers.status, helpers.diagnostics)
        val roots = ForwardCompiler(p).compile(mapOf(
            "demo/Pricing.java" to fixture().getValue("demo/Pricing.java").replace("Policy.calculate(amount)", "Shared.calculate(amount)"),
            "demo/Other.java" to "package demo; @javax.ws.rs.Path(\"/other\") public class Other { @javax.ws.rs.GET @javax.ws.rs.Path(\"/value\") public static int value(@javax.ws.rs.QueryParam(\"amount\") int amount) { return Shared.calculate(amount); } }",
            "demo/Independent.java" to independent(),
        ), dir.resolve("roots"), 120_000, classpath = listOf(helpers.classesDir))
        assertEquals(CheckStatus.PASS, roots.status, roots.diagnostics)
        val apk = TestApks.write(dir, "shared.apk", TestApks.zip(mapOf(
            "classes.dex" to Files.readAllBytes(helpers.dexFile!!), "classes2.dex" to Files.readAllBytes(roots.dexFile!!))))
        val result = FullRecovery(p, FullRecoveryOptions(rea = false, javaScript = false, resolveLibraries = false,
            nameLibraries = false, repair = false, crossCheck = false)).run(apk, dir.resolve("shared-out"))
        val backend = assertNotNull(result.outDir, result.message).resolve("backend/embedded")
        val report = Json.decode<EmbeddedBackendReport>(Files.readString(backend.resolve("report.json")))
        assertEquals("RUNNABLE_ADAPTER", report.status, report.toString())
        assertEquals(4, report.handlers.size)
        assertEquals(listOf("Ldemo/Independent;->value(I)I"), report.runnableHandlers)
        val dependent = report.handlerChecks.filter { "Ldemo/Independent;" !in it.method }
        assertEquals(3, dependent.size)
        for (checked in dependent) {
            assertEquals("REQUIRES_REVIEW", checked.status)
            assertContains(checked.dependencies, "Ldemo/Shared;")
            assertTrue(checked.issues.any { "Virtual dispatch" in it && "Shared" in it }, checked.toString())
        }
        assertTrue(report.sources.any { "Shared.java" in it.path })
        val args = Files.readString(backend.resolve("sources.args"))
        assertContains(args, "Independent.java")
        for (name in listOf("Pricing.java", "Other.java", "Shared.java", "RuntimePolicy.java")) assertFalse(name in args, args)
    }

    @Test fun `source check gaps and route conflicts are attributed to affected handlers`() {
        val p = profile()
        val sources = fixture() + mapOf(
            "demo/Independent.java" to independent(),
            "demo/Collision.java" to "package demo; @javax.ws.rs.Path(\"/pricing\") public class Collision { @javax.ws.rs.GET @javax.ws.rs.Path(\"/quote\") public static int value(@javax.ws.rs.QueryParam(\"amount\") int amount) { return amount + 1; } }",
        )
        val original = ForwardCompiler(p).compile(sources, dir.resolve("known"), 120_000)
        assertEquals(CheckStatus.PASS, original.status, original.diagnostics)
        val dex = listOf("classes.dex" to dev.apkproject.dex.DexFile.parse(Files.readAllBytes(original.dexFile!!)).getOrThrow())
        val (summary, checks) = RoundTripVerifier(p).verify(dex, original.classesDir, emptyList(), emptyMap(),
            sources.keys, emptyList(), 26, dir.resolve("known-roundtrip"), 120_000)
        assertEquals("MEASURED", summary.status)
        assertTrue(checks.all { it.result in RoundTripVerifier.EQUIVALENT }, checks.toString())
        val work = dir.resolve("reused-work")
        // Old output must not enter the fresh portable build or its bytecode comparison.
        Files.write(Files.createDirectories(work.resolve("classes")).resolve("stale.class"), byteArrayOf(0, 1, 2))
        val output = dir.resolve("selective")
        fun recover(methods: List<MethodRoundTrip>) = EmbeddedBackend.recover(dex, "a".repeat(64), dir.resolve("known/src"),
            output, NameMapping(emptyMap(), emptyMap()), methods, p, work, CancellationToken())
        val initial = recover(checks)
        assertEquals(setOf("Ldemo/Pricing;->net(J)J", "Ldemo/Independent;->value(I)I"), initial.runnableHandlers.toSet())
        assertTrue(initial.handlerChecks.filter { "quote" in it.method || "Collision" in it.method }
            .all { it.status == "REQUIRES_REVIEW" && it.issues.any { note -> "ambiguous" in note } })
        val gap = recover(checks.filterNot { it.method == "Ldemo/Pricing;->quote(I)I" })
        assertEquals(listOf("Ldemo/Independent;->value(I)I"), gap.runnableHandlers)
        assertTrue(gap.handlerChecks.single { "net(J)J" in it.method }.issues.any { "lack matching" in it })
        Files.delete(dir.resolve("known/src/demo/Policy.java"))
        val missing = recover(checks)
        assertEquals(listOf("Ldemo/Independent;->value(I)I"), missing.runnableHandlers)
        assertTrue(missing.handlerChecks.single { "net(J)J" in it.method }.issues.any { "Source unavailable" in it })
        assertFalse("Policy.java" in Files.readString(output.resolve("sources.args")))
    }

    @Test fun `handler scan is bounded and client declarations alone are not servers`() {
        val p = profile()
        val many = ForwardCompiler(p).compile(fixture(extraHandlers = 130), dir.resolve("many"), 120_000)
        assertEquals(CheckStatus.PASS, many.status, many.diagnostics)
        val dex = dev.apkproject.dex.DexFile.parse(Files.readAllBytes(many.dexFile!!)).getOrThrow()
        val report = EmbeddedBackend.scan(listOf("classes.dex" to dex), "a".repeat(64))
        assertEquals(128, report.handlers.size)
        assertTrue(report.issues.any { "budget" in it })
        val client = ForwardCompiler(p).compile(fixture().filterKeys { it.startsWith("retrofit2/") || it.endsWith("RemoteClient.java") }, dir.resolve("client"), 120_000)
        assertEquals(CheckStatus.PASS, client.status, client.diagnostics)
        val cd = dev.apkproject.dex.DexFile.parse(Files.readAllBytes(client.dexFile!!)).getOrThrow()
        assertEquals("NOT_DETECTED", EmbeddedBackend.scan(listOf("classes.dex" to cd), "a".repeat(64)).status)
        val token = CancellationToken().apply { cancel() }
        assertFailsWith<dev.apkproject.compiler.CancelledException> { EmbeddedBackend.scan(listOf("classes.dex" to cd), "a".repeat(64), token) }
    }

    @Test fun `jakarta and spring mappings are retained with bodies and class prefixes`() {
        val p = profile()
        val sources = fixture().filterKeys { it.startsWith("javax/") }.mapKeys { it.key.replace("javax/", "jakarta/") }.mapValues { it.value.replace("javax.ws.rs", "jakarta.ws.rs") }.toMutableMap()
        val ns = "org.springframework.web.bind.annotation"
        for (a in listOf("RequestMapping", "GetMapping")) sources["${ns.replace('.', '/')}/$a.java"] = "package $ns; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface $a { String[] value() default {}; String[] path() default {}; }"
        sources["${ns.replace('.', '/')}/RestController.java"] = "package $ns; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface RestController { }"
        sources["demo/Jakarta.java"] = "package demo; @jakarta.ws.rs.Path(\"/j\") public class Jakarta { @jakarta.ws.rs.GET @jakarta.ws.rs.Path(\"/x\") public int x(){ return 3; } }"
        sources["demo/Spring.java"] = "package demo; @$ns.RestController @$ns.RequestMapping(path={\"/s\"}) public class Spring { @$ns.GetMapping(value={\"/x\",\"/y\"}) public int x(){ return 4; } }"
        sources["fi/iki/elonen/NanoHTTPD.java"] = "package fi.iki.elonen; public class NanoHTTPD { public interface IHTTPSession {} public static class Response {} public Response serve(IHTTPSession session) { return null; } }"
        sources["demo/LocalServer.java"] = "package demo; public class LocalServer extends fi.iki.elonen.NanoHTTPD { @Override public Response serve(IHTTPSession s) { return new Response(); } }"
        sources["demo/Handler.java"] = "package demo; public class Handler implements com.sun.net.httpserver.HttpHandler { public void handle(com.sun.net.httpserver.HttpExchange x) {} }"
        val build = ForwardCompiler(p).compile(sources, dir.resolve("frameworks"), 120_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = dev.apkproject.dex.DexFile.parse(Files.readAllBytes(build.dexFile!!)).getOrThrow()
        val report = EmbeddedBackend.scan(listOf("classes.dex" to dex), "a".repeat(64))
        assertEquals(4, report.handlers.size)
        assertEquals(listOf("/j/x"), report.handlers.single { it.framework == "JAX-RS" }.paths)
        assertEquals(setOf("/s/x", "/s/y"), report.handlers.single { it.framework == "Spring MVC" }.paths.toSet())
        assertTrue(report.handlers.filter { it.framework in listOf("JAX-RS", "Spring MVC") }.all { it.verbs == listOf("GET") })
        assertTrue(report.handlers.filter { it.framework in listOf("NanoHTTPD", "JDK HttpHandler") }.all { it.paths.isEmpty() && it.verbs.isEmpty() })
        assertTrue(report.handlers.none { it.method.startsWith("Lfi/iki/elonen/NanoHTTPD;") })
    }
}
