package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.ResolvedProfile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDiffTest {
    @TempDir
    lateinit var dir: Path

    private val root: Path get() = Paths.get(System.getProperty("apkproject.root"))

    /**
     * A small app in two versions. Twelve classes carry their own strings; eight form a chain that
     * differs only in the class each one references, so they can only be told apart once their
     * neighbours are matched. Version 2 adds a class that sorts first (R8 then renames the others),
     * edits one constant in C05, and removes C11.
     */
    private fun sources(version: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val labelled = (1..12).filter { version == 1 || it != 11 }
        for (k in labelled) {
            val n = "%02d".format(k)
            val add = if (k == 5 && version == 2) 30 else 3
            out["app/C$n.java"] = """
                package app;
                public class C$n {
                    private final String label;
                    public C$n(String label) { this.label = label; }
                    public String describe(int x) { return label + "#$n:" + (x * $k + $add); }
                }
            """.trimIndent()
        }
        for (k in 1..8) {
            val next = if (k < 8) "N%02d".format(k + 1) else "C01"
            val n = "%02d".format(k)
            out["app/N$n.java"] = """
                package app;
                public class N$n {
                    private $next next;
                    public void link($next n) { next = n; }
                    public int depth() { return next == null ? 1 : 2; }
                }
            """.trimIndent()
        }
        if (version == 2) out["app/A00Telemetry.java"] = """
            package app;
            public class A00Telemetry {
                public static final String ENDPOINT = "https://telemetry.example.net/v1/collect";
                public static String send() throws Exception {
                    Runtime.getRuntime().exec(new String[] {"logcat", "-d"});
                    return ENDPOINT;
                }
            }
        """.trimIndent()
        val uses = labelled.joinToString("\n") { "        System.out.println(new C%02d(\"x\").describe(args.length));".format(it) } +
            (1..8).joinToString("\n", "\n") { "        System.out.println(new N%02d().depth());".format(it) } +
            (if (version == 2) "\n        try { System.out.println(A00Telemetry.send()); } catch (Exception e) { }" else "")
        out["app/Main.java"] = "package app;\npublic class Main {\n    public static void main(String[] args) {\n$uses\n    }\n}\n"
        return out
    }

    /** javac, then R8 with renaming (no shrinking or optimization, so only names change). */
    private fun r8(profile: ResolvedProfile, sources: Map<String, String>, work: Path): Pair<Path, Path> {
        val build = ForwardCompiler(profile).compile(sources, work, 300_000, withDex = false)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val rules = Files.writeString(work.resolve("rules.pro"), "-keep class app.Main { public static void main(java.lang.String[]); }\n-dontoptimize\n-dontshrink\n")
        val out = Files.createDirectories(work.resolve("r8"))
        val map = work.resolve("mapping.txt")
        val argv = listOf(
            profile.java.toString(), "-cp", profile.d8Jar.toString(), "com.android.tools.r8.R8", "--release", "--min-api", "26",
            "--lib", profile.d8Lib.toString(), "--output", out.toString(), "--pg-map-output", map.toString(), "--pg-conf", rules.toString(),
        ) + build.classFiles.map { it.toString() }
        val r = ProcessWorker.run(argv, work, 300_000)
        assertTrue(r.ok, r.stderr + r.stdout)
        return out.resolve("classes.dex") to map
    }

    /** Builds, diffs and re-matches two obfuscated versions, checking the pairing and the resulting report. */
    @Test
    fun `classes are paired across R8 renames and only the real edits are reported`() {
        val profile = Profiles.load(root.resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        val (dex1, map1) = r8(profile, sources(1), dir.resolve("v1"))
        val (dex2, map2) = r8(profile, sources(2), dir.resolve("v2"))
        val truth1 = R8GroundTruth(Files.readAllLines(map1))
        val truth2 = R8GroundTruth(Files.readAllLines(map2))
        fun original(truth: R8GroundTruth, obfuscated: String) = truth.origins[obfuscated]?.single() ?: obfuscated

        // The test means something only if R8 really renamed classes between the builds.
        val obfuscatedIn1 = truth1.origins.entries.associate { (obf, names) -> names.single() to obf }
        val obfuscatedIn2 = truth2.origins.entries.associate { (obf, names) -> names.single() to obf }
        val renamed = obfuscatedIn1.count { (orig, obf) -> obfuscatedIn2[orig] != null && obfuscatedIn2[orig] != obf }
        assertTrue(renamed >= 5, "R8 renamed only $renamed classes; the fixture no longer exercises renames")

        val result = AppDiff.compare(dex1, dex2)
        val pairs = result.classMap.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val matched = pairs.filter { it[2] !in setOf("ADDED", "REMOVED") }
        for ((old, new, how) in matched) assertEquals(original(truth1, old), original(truth2, new), "wrong pair by $how: $old -> $new")
        // Classes R8 did not rename (Main) are absent from its map and keep their own name.
        val inOld = pairs.filter { it[0] != "-" }.map { original(truth1, it[0]) }.toSet()
        val inNew = pairs.filter { it[1] != "-" }.map { original(truth2, it[1]) }.toSet()
        assertEquals(inOld intersect inNew, matched.map { original(truth1, it[0]) }.toSet(), "every class present in both builds is paired")
        assertEquals(20, matched.size)

        val report = result.report
        fun originals(status: String) = report.classes.filter { it.status == status }.map { c ->
            if (status == "REMOVED") original(truth1, "L" + c.oldName!!.replace('.', '/') + ";") else original(truth2, "L" + c.newName!!.replace('.', '/') + ";")
        }.toSet()
        assertEquals(setOf("Lapp/C05;", "Lapp/Main;"), originals("MODIFIED"), report.classes.toString())
        assertEquals(setOf("Lapp/A00Telemetry;"), originals("ADDED"))
        assertEquals(setOf("Lapp/C11;"), originals("REMOVED"))
        assertTrue(report.code.renamedByObfuscator >= 5, report.code.toString())

        // What the new class brings is surfaced for review.
        assertTrue(report.changes.any { it.area == "NETWORK" && it.kind == "ADDED" && it.subject == "telemetry.example.net" }, report.changes.toString())
        assertTrue(report.changes.any { it.area == "CAPABILITIES" && it.kind == "ADDED" && it.subject == "Runs shell commands" }, report.changes.toString())
        val modified = report.classes.single { it.status == "MODIFIED" && original(truth2, "L" + it.newName!!.replace('.', '/') + ";") == "Lapp/C05;" }
        assertEquals(1, modified.methodsModified.size, modified.toString())
        assertTrue(DiffMarkdown.render(report).contains("Update review"))
    }

    /** Diffs two real Tusky release APKs and checks signing, API and code-change metrics. */
    @Test
    fun `two real Tusky releases - same signer, the five changed endpoints, nothing to review first`() {
        val old = root.resolve(".tools/dl/tusky/tusky-141.apk")
        val new = root.resolve(".tools/dl/tusky/tusky-142.apk")
        assumeTrue(Files.isRegularFile(old) && Files.isRegularFile(new), "Tusky 32.1 and 32.2 APKs not downloaded (see benchmarks/update-diff)")
        val r = AppDiff.compare(old, new).report
        assertEquals("com.keylesspalace.tusky", r.new.packageName)
        assertEquals(141L, r.old.versionCode)
        assertEquals(142L, r.new.versionCode)
        val signer = assertNotNull(r.new.signing?.signers?.singleOrNull())
        assertTrue(signer.subject.startsWith("CN=FDroid"), signer.toString())
        assertEquals(listOf("v1", "v2", "v3"), signer.schemes)
        assertNull(r.changes.firstOrNull { it.area == "SIGNING" })
        assertTrue(r.changes.none { it.attention == "HIGH" }, r.changes.filter { it.attention == "HIGH" }.toString())
        // 32.2 added a min_id query parameter to these timelines (MastodonApi.kt).
        val api = r.changes.filter { it.area == "API" }
        assertEquals(
            setOf("GET api/v1/bookmarks", "GET api/v1/favourites", "GET api/v1/timelines/list/{listId}", "GET api/v1/timelines/public", "GET api/v1/timelines/tag/{hashtag}"),
            api.map { it.subject }.toSet(), api.toString(),
        )
        assertTrue(api.all { it.detail == "new parameters: query min_id" })
        // Regression guards for the matching measured in benchmarks/update-diff (precision 0.999).
        assertTrue(r.code.unchanged >= 7_800, r.code.toString())
        assertTrue(r.code.modified <= 100, r.code.toString())
        // Location code exists (AppCompat's night-mode helper) but Tusky does not request the permission.
        assertEquals(false, r.newCapabilities.single { it.id == "location" }.permissionDeclared)
    }

    /** Capability detection reports whether the matching permission is declared, and SDKs are identified. */
    @Test
    fun `capabilities know whether their permission is requested, and SDKs are recognized`() {
        val refs = listOf("a.b" to listOf("Landroid/location/LocationManager;->getLastKnownLocation(Ljava/lang/String;)Landroid/location/Location;"))
        assertEquals(false, Capabilities.of(refs, emptySet()).single().permissionDeclared)
        assertEquals(true, Capabilities.of(refs, setOf("android.permission.ACCESS_COARSE_LOCATION")).single().permissionDeclared)
        assertNull(Capabilities.of(refs, null).single().permissionDeclared)
        val sdks = Capabilities.sdks(setOf("Lio/sentry/Sentry;", "La/b;"), listOf("api2.amplitude.com"))
        assertEquals(setOf("Sentry" to "OBSERVED", "Amplitude" to "INFERRED"), sdks.map { it.name to it.evidence }.toSet())
    }

    /** Obfuscated or compiler-generated class and member names are rejected as stable identities. */
    @Test
    fun `obfuscated and compiler-numbered names are not trusted as identities`() {
        for (kept in listOf("Lcom/example/MainActivity;", "Lokhttp3/OkHttpClient\$Builder;", "Landroidx/core/R;", "Lcom/example/R\$string;")) {
            assertTrue(CodeMatcher.stableClassName(kept), kept)
        }
        for (renamed in listOf("La/b;", "Lcom/example/a;", "Lzg/f;", "Lcom/example/Main\$1;", "Lcom/example/Main\$\$ExternalSyntheticLambda0;", "Lcom/example/Main\$a;")) {
            assertTrue(!CodeMatcher.stableClassName(renamed), renamed)
        }
        assertTrue(CodeMatcher.stableMember("onCreate") && CodeMatcher.stableMember("<init>"))
        assertTrue(!CodeMatcher.stableMember("a") && !CodeMatcher.stableMember("lambda\$onCreate\$0"))
    }
}
