package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.compiler.ProcessWorker
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MethodNamingIntegrationTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `JADX naming preserves subclass dispatch execution and round trip`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/android-full.json"))
        assumeTrue(listOfNotNull(profile.jadxJar, profile.androidJar, profile.d8Jar).all { Files.isRegularFile(it) } && Files.isExecutable(profile.javac))
        val sources = mapOf(
            "resource/R.java" to "package resource; public class R { public static int value = 7; }",
            "example/Base.java" to "package example; public class Base { public String a(String x) { return \"base:\" + x; } public int b(int x) { return x + 1; } }",
            "example/Child.java" to "package example; public class Child extends Base { public String a(String x) { return \"child:\" + x; } }",
            "example/Grandchild.java" to "package example; public class Grandchild extends Child {}",
            "example/Commands.java" to """package example; public class Commands {
                public String a(String input) { return "request queued:" + input; }
                public String b(String input) { return "request cancelled:" + input; }
            }""",
            "example/Main.java" to """package example; public class Main {
                public static int R = 5;
                public static void main(String[] args) {
                    test(new Grandchild(), new Grandchild());
                    System.out.println(resource.R.value + R);
                }
                public static void test(Base base, Grandchild child) {
                    System.out.println(base.a("base receiver"));
                    System.out.println(child.a("child receiver"));
                    System.out.println(base.b(41));
                    Commands commands = new Commands();
                    System.out.println(commands.a("42"));
                    System.out.println(commands.b("42"));
                }
            }""",
        )
        val original = ForwardCompiler(profile).compile(sources, dir.resolve("original"), 120_000)
        assertEquals(CheckStatus.PASS, original.status, original.diagnostics)
        val dexPath = assertNotNull(original.dexFile)
        val dex = (DexFile.parse(Files.readAllBytes(dexPath)) as Outcome.Ok).value
        val reference = ForwardCompiler(profile).compile(mapOf("example/Commands.java" to sources.getValue("example/Commands.java")
            .replace("String a(", "String send(").replace("String b(", "String cancel(")), dir.resolve("reference"), 120_000)
        assertEquals(CheckStatus.PASS, reference.status, reference.diagnostics)
        val referenceDex = (DexFile.parse(Files.readAllBytes(assertNotNull(reference.dexFile))) as Outcome.Ok).value
        val inferred = LibraryMatcher.match(LibraryMatcher.profiles(listOf(dex)), LibraryMatcher.profiles(listOf(referenceDex)),
            mapOf("Lexample/Commands;" to "Lexample/Commands;"))
        assertEquals(mapOf("a(Ljava/lang/String;)Ljava/lang/String;" to "send", "b(Ljava/lang/String;)Ljava/lang/String;" to "cancel"), inferred.methods["Lexample/Commands;"])
        val library = LibraryMatcher.Result(
            listOf(LibraryMatcher.ClassMatch("Lexample/Base;", "Llibrary/Base;", 1.0, 1.0)),
            inferred.methods + mapOf("Lexample/Base;" to mapOf("a(Ljava/lang/String;)Ljava/lang/String;" to "read", "b(I)I" to "read")), emptyMap(), 1,
        )
        val mapping = NameMapping.compute(listOf("classes.dex" to dex), library)
        assertEquals("R_f", mapping.fields["Lexample/Main;"]?.get("R"))
        val tiny = dir.resolve("names.tiny")
        mapping.writeTiny(tiny, listOf("classes.dex" to dex))
        val work = Files.createDirectories(dir.resolve("jadx-work"))
        val out = dir.resolve("recovered")
        val run = JadxDecompiler(profile).export(dexPath, out, false, 2, work, tiny)
        assertEquals("COMPLETE", run.status, Files.readString(work.resolve("jadx.log")))
        val javaRoot = out.resolve("app/src/main/java")
        val recoveredSources = Files.walk(javaRoot).use { stream -> stream.filter { it.toString().endsWith(".java") }.toList() }
            .associate { javaRoot.relativize(it).toString() to Files.readString(it) }
        assertContains(recoveredSources.getValue("example/Child.java"), "String read(String")
        assertContains(recoveredSources.getValue("example/Commands.java"), "String send(String")
        assertContains(recoveredSources.getValue("example/Commands.java"), "String cancel(String")
        val recovered = ForwardCompiler(profile).compile(recoveredSources, dir.resolve("recompiled"), 120_000)
        assertEquals(CheckStatus.PASS, recovered.status, recovered.diagnostics)
        fun execute(classes: Path): String {
            val result = ProcessWorker.run(listOf(profile.java.toString(), "-cp", classes.toString(), "example.Main"), dir, 30_000)
            assertTrue(result.ok, result.stderr)
            return result.stdout.trim()
        }
        assertEquals("child:base receiver\nchild:child receiver\n42\nrequest queued:42\nrequest cancelled:42\n12", execute(original.classesDir))
        assertEquals(execute(original.classesDir), execute(recovered.classesDir))
        val verifier = RoundTripVerifier(profile).apply { names = mapping; renames = JadxRenames.parse(javaRoot) }
        val (_, methods) = verifier.verify(listOf("classes.dex" to dex), recovered.classesDir, emptyList(), emptyMap(), recoveredSources.keys,
            listOf("example"), 26, Files.createDirectories(dir.resolve("roundtrip")), 120_000)
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.all { it.result in RoundTripVerifier.EQUIVALENT }, methods.toString())
    }

    @Test
    fun `a package obscured by a type keeps its classes reachable after JADX`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/android-full.json"))
        assumeTrue(listOfNotNull(profile.jadxJar, profile.androidJar, profile.d8Jar).all { Files.isRegularFile(it) } && Files.isExecutable(profile.javac))
        // R8's layout on Tusky: package k1 and a class b0.k1 share a name, and k1 has a class N
        // while code in k1 also uses another package's N. Moving the package (k1 -> k1_) made
        // JADX 1.5.6 import q.N over the same-package N; renaming the type keeps both reachable.
        val sources = mapOf(
            "k1/N.java" to "package k1; public class N { public int v = 1; }",
            "q/N.java" to "package q; public class N { public String s = \"q\"; }",
            "k1/X.java" to "package k1; public class X { public static int f(N a, q.N b) { return a.v * 10 + b.s.length(); } }",
            "b0/k1.java" to "package b0; public class k1 { public static int z = 3; }",
            // A second type with that name: given one new name, the two would be confused the same way.
            "b1/k1.java" to "package b1; public class k1 { public static int y = 20; }",
            "b0/User.java" to "package b0; public class User { public static int g() { return k1.z + b1.k1.y; } }",
            "app/Main.java" to """package app; public class Main {
                public static void main(String[] args) { System.out.println(k1.X.f(new k1.N(), new q.N()) + b0.User.g()); }
            }""",
        )
        val original = ForwardCompiler(profile).compile(sources, dir.resolve("original"), 120_000)
        assertEquals(CheckStatus.PASS, original.status, original.diagnostics)
        val dexPath = assertNotNull(original.dexFile)
        val dex = (DexFile.parse(Files.readAllBytes(dexPath)) as Outcome.Ok).value
        val mapping = NameMapping.compute(listOf("classes.dex" to dex))
        assertEquals(mapOf("Lb0/k1;" to "Lb0/k1_b0;", "Lb1/k1;" to "Lb1/k1_b1;"), mapping.classes, "obscuring types get unique names and no package moves")
        val tiny = dir.resolve("names.tiny")
        mapping.writeTiny(tiny, listOf("classes.dex" to dex))
        val work = Files.createDirectories(dir.resolve("jadx-work"))
        val out = dir.resolve("recovered")
        val run = JadxDecompiler(profile).export(dexPath, out, false, 2, work, tiny)
        assertEquals("COMPLETE", run.status, Files.readString(work.resolve("jadx.log")))
        val javaRoot = out.resolve("app/src/main/java")
        val recoveredSources = Files.walk(javaRoot).use { stream -> stream.filter { it.toString().endsWith(".java") }.toList() }
            .associate { javaRoot.relativize(it).toString() to Files.readString(it) }
        val recovered = ForwardCompiler(profile).compile(recoveredSources, dir.resolve("recompiled"), 120_000)
        assertEquals(CheckStatus.PASS, recovered.status, recovered.diagnostics + "\n" + recoveredSources["k1/X.java"])
        fun execute(classes: Path): String {
            val result = ProcessWorker.run(listOf(profile.java.toString(), "-cp", classes.toString(), "app.Main"), dir, 30_000)
            assertTrue(result.ok, result.stderr)
            return result.stdout.trim()
        }
        assertEquals("34", execute(original.classesDir))
        assertEquals(execute(original.classesDir), execute(recovered.classesDir))
        val verifier = RoundTripVerifier(profile).apply { names = mapping; renames = JadxRenames.parse(javaRoot) }
        val (_, methods) = verifier.verify(listOf("classes.dex" to dex), recovered.classesDir, emptyList(), emptyMap(), recoveredSources.keys,
            listOf("app", "k1", "q", "b0", "b1"), 26, Files.createDirectories(dir.resolve("roundtrip")), 120_000)
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.all { it.result in RoundTripVerifier.EQUIVALENT }, methods.toString())
    }
}
