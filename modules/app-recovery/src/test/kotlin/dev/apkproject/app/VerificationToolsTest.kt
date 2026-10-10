package dev.apkproject.app

import dev.apkproject.contracts.Profiles
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationToolsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `class file reader lists method names with descriptors`() {
        val bytes = String::class.java.getResourceAsStream("/java/lang/String.class")?.readBytes()
            ?: javaClass.getResourceAsStream("VerificationToolsTest.class")!!.readBytes()
        val sigs = assertNotNull(ClassFileReader.read(bytes))
        if (sigs.name == "java/lang/String") {
            assertTrue("length()I" in sigs.methods && "charAt(I)C" in sigs.methods, sigs.methods.take(10).toString())
        } else {
            assertEquals("dev/apkproject/app/VerificationToolsTest", sigs.name)
        }
        val own = assertNotNull(ClassFileReader.read(javaClass.getResourceAsStream("VerificationToolsTest.class")!!.readBytes()))
        assertTrue(own.methods.any { it.startsWith("<init>(") }, own.methods.toString())
        assertNull(ClassFileReader.read(ByteArray(16)))
    }

    @Test
    fun `ecj xml log is parsed per file with relative paths`() {
        val root = dir.resolve("java")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <compiler name="Eclipse Compiler for Java(TM)" copyright="" version="3.45.0">
              <sources>
                <source path="${root.resolve("com/example/A.java")}" output="x">
                  <problems problems="2" errors="2" warnings="0" infos="0">
                    <problem charEnd="1" charStart="0" severity="ERROR" line="3" id="UndefinedType">
                      <message value="Foo&lt;T&gt; cannot be resolved to a type"/>
                    </problem>
                    <problem charEnd="1" charStart="0" severity="ERROR" line="4" id="UndefinedName">
                      <message value="x cannot be resolved"/>
                    </problem>
                  </problems>
                </source>
                <source path="${root.resolve("com/example/B.java")}" output="y"><classfile path="B.class"/></source>
              </sources>
            </compiler>"""
        val parsed = EcjCompiler.parse(xml, root)
        assertEquals(setOf("com/example/A.java", "com/example/B.java"), parsed.keys)
        assertEquals(2, parsed.getValue("com/example/A.java").errors)
        assertEquals(listOf("Foo<T> cannot be resolved to a type", "x cannot be resolved"), parsed.getValue("com/example/A.java").firstErrors)
        assertEquals(0, parsed.getValue("com/example/B.java").errors)
        assertEquals(mapOf("UndefinedType" to 1, "UndefinedName" to 1), parsed.getValue("com/example/A.java").kinds)
        assertFalse(parsed.getValue("com/example/A.java").onlyCheckedExceptionErrors)
        assertTrue(FileDiagnostics("K.java", 3, emptyList(), mapOf("UnhandledException" to 2, "UnreachableCatch" to 1)).onlyCheckedExceptionErrors)
        assertFalse(FileDiagnostics("K.java", 3, emptyList(), mapOf("UnhandledException" to 2)).onlyCheckedExceptionErrors, "an error of unknown kind")
    }

    @Test
    fun `checked-exception errors are relaxed for the round trip, other errors are not`() {
        val root = Paths.get(System.getProperty("apkproject.root"))
        val profile = Profiles.load(root.resolve("profiles/android-full.json"))
        assumeTrue(profile.androidJar?.let { Files.isRegularFile(it) } == true && Files.isExecutable(profile.javac), "android.jar not installed; run scripts/setup-toolchain.sh --full")
        val java = Files.createDirectories(dir.resolve("java/p"))
        Files.writeString(java.resolve("Clean.java"), "package p;\npublic class Clean { int f(int x) { return x + 1; } }\n")
        // What decompiled Kotlin looks like: an undeclared checked exception and a catch javac thinks unreachable.
        Files.writeString(
            java.resolve("Kt.java"),
            "package p;\nimport java.io.IOException;\npublic class Kt implements Runnable {\n static void io() throws IOException { throw new IOException(); }\n" +
                " public void run() { io(); }\n int g(int x) { try { return x * 2; } catch (IOException e) { return -1; } }\n}\n",
        )
        Files.writeString(java.resolve("Bad.java"), "package p;\nclass Bad { int f() { Clean.io(); return \"x\"; } }\n")
        val classes = Files.createDirectories(dir.resolve("classes"))
        val r = JavacOverlay(profile).apply(dir.resolve("java"), listOf("p/Clean.java"), classes, emptyList(), dir.resolve("work"), 300_000, relaxable = listOf("p/Kt.java", "p/Bad.java"))
        assertEquals(listOf("p/Clean.java", "p/Kt.java"), r.javacFiles, r.note)
        assertEquals(listOf("p/Kt.java"), r.relaxedFiles)
        assertEquals(listOf("p/Bad.java"), r.rejected)
        assertTrue(Files.isRegularFile(classes.resolve("p/Kt.class")))
        // The catch javac thought unreachable is still compiled: the code is what the source says.
        val javap = ProcessBuilder(profile.jdkHome.resolve("bin/javap").toString(), "-c", "-p", classes.resolve("p/Kt.class").toString()).redirectErrorStream(true).start()
        val listing = javap.inputStream.readBytes().decodeToString().also { javap.waitFor() }
        assertContains(listing.substringAfter("int g(int)"), "Exception table", message = "the catch block must be in the bytecode")
    }

    @Test
    fun `only synthetic platform stubs are removed`() {
        val java = dir.resolve("java")
        fun write(rel: String, text: String) = Files.createDirectories(java.resolve(rel).parent).let { Files.writeString(java.resolve(rel), text) }
        val stub = "package android.view;\n\n/* synthetic */ interface OnReceiveContentListener {\n    static {\n        throw new NoClassDefFoundError();\n    }\n}\n"
        write("android/view/OnReceiveContentListener.java", stub)
        write("com/example/Fake.java", stub.replace("android.view", "com.example"))
        write("android/support/Real.java", "package android.support;\npublic class Real { int f() { return 1; } }\n")
        assertEquals(listOf("android/view/OnReceiveContentListener.java"), ArtifactCleaner.removePlatformStubs(java))
        assertTrue(Files.exists(java.resolve("com/example/Fake.java")), "app packages are never touched")
        assertTrue(Files.exists(java.resolve("android/support/Real.java")))
    }

    @Test
    fun `verified libraries replace their sources and become dependencies`() {
        val java = dir.resolve("java")
        for (rel in listOf("androidx/core/Foo.java", "androidx/core/R.java", "com/example/Main.java")) {
            Files.createDirectories(java.resolve(rel).parent)
            Files.writeString(java.resolve(rel), "class X {}")
        }
        val res = LibraryResolver.Resolution(
            libraries = listOf(
                ResolvedLibrary("androidx.core:core:1.9.0", "VERIFIED", "s", "r", 2, 2, 10, 10, "2/2 classes"),
                ResolvedLibrary("com.example:gone:1.0", "REJECTED", "s", "r", 2, 0, 0, 0, "0 classes"),
            ),
            jars = emptyList(),
            classes = setOf("androidx/core/Foo", "androidx/core/Foo\$Inner"),
            androidPackages = setOf("androidx.core"),
        )
        assertEquals(listOf("androidx/core/Foo.java", "androidx/core/R.java"), LibraryApplier.removeSources(java, res))
        assertTrue(Files.exists(java.resolve("com/example/Main.java")))

        val gradle = dir.resolve("build.gradle")
        Files.writeString(gradle, "android {\n}\n\ndependencies {\n    implementation 'x:y:1'\n}\n")
        LibraryApplier.addDependencies(gradle, res.libraries)
        val text = Files.readString(gradle)
        assertContains(text, "implementation 'androidx.core:core:1.9.0'")
        assertFalse(text.contains("com.example:gone"))
        assertEquals(1, Regex("""dependencies \{""").findAll(text).count())
    }

    @Test
    fun `kotlin stdlib version is read from either version holder`() {
        fun dex(owner: String, method: String, flags: String) = TestApks.dexFile(
            """
            .class public $owner
            .super Ljava/lang/Object;
            .method $flags $method
            .registers 4
            new-instance v0, Lkotlin/KotlinVersion;
            const/4 v1, #2
            const/4 v2, #2
            const/16 v3, #10
            invoke-direct {v0, v1, v2, v3}, Lkotlin/KotlinVersion;-><init>(III)V
            ${if (method.startsWith("<clinit>")) "sput-object v0, Lkotlin/KotlinVersion;->CURRENT:Lkotlin/KotlinVersion;\nreturn-void" else "return-object v0"}
            .end method
            """.trimIndent(),
        )
        val old = LibraryResolver.kotlinStdlibVersion(listOf("classes.dex" to dex("Lkotlin/KotlinVersion;", "<clinit>()V", "static constructor")))
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib" to "2.2.10", old?.let { it.coordinate to it.version })
        val new = LibraryResolver.kotlinStdlibVersion(listOf("classes.dex" to dex("Lkotlin/KotlinVersionCurrentValue;", "get()Lkotlin/KotlinVersion;", "public static")))
        assertEquals("2.2.10", new?.version)
        val noKotlin = TestApks.dexFile(".class public LA;\n.super Ljava/lang/Object;\n.method public static f()V\n.registers 1\nreturn-void\n.end method\n")
        assertNull(LibraryResolver.kotlinStdlibVersion(listOf("classes.dex" to noKotlin)))
    }

    @Test
    fun `method locator finds static int methods outside comments and strings`() {
        val src = """
            class A {
                // static int f(int a) { return 0; }
                String s = "static int f(int a) {";
                public static int f(int i) {
                    if (i > 0) { return i; }
                    return -i;
                }
                static int f(int a, int b) { return a; }
                abstract static class N { static int g(); }
            }
        """.trimIndent()
        val found = assertIs<JavaMethodLocator.Result.Found>(JavaMethodLocator.find(src, "f", 1)).found
        assertTrue(found.text.trimStart().startsWith("public static int f(int i)"), found.text)
        assertTrue(found.text.trimEnd().endsWith("}"))
        assertEquals("    ", found.indent)
        assertIs<JavaMethodLocator.Result.Found>(JavaMethodLocator.find(src, "f", 2))
        assertIs<JavaMethodLocator.Result.Missing>(JavaMethodLocator.find(src, "g", 0))
        assertIs<JavaMethodLocator.Result.Missing>(JavaMethodLocator.find(src, "h", 1))
        assertEquals(src.length, JavaMethodLocator.codeOnly(src).length)
        assertFalse(JavaMethodLocator.codeOnly(src).contains("return 0"))
    }

    @Test
    fun `anonymous classes are matched by what they do, not by their number`() {
        fun anon(n: Int, value: Int) = ".class final LO\$$n;\n.super Ljava/lang/Object;\n.method public run()I\n.registers 2\nconst/16 v0, #$value\nreturn v0\n.end method\n"
        fun classes(dex: dev.apkproject.dex.DexFile) = dex.methods().groupBy { it.owner }.mapValues { (_, ms) -> ms.associateBy { it.name + it.protoDescriptor } }
        val original = classes(TestApks.dexFile(anon(1, 10) + anon(2, 20) + anon(3, 30)))
        // The decompiler emitted the first two in the other order, so javac numbered them the other way round.
        val recompiled = classes(TestApks.dexFile(anon(1, 20) + anon(2, 10) + anon(3, 30)))
        val compare = { a: dev.apkproject.dex.DexMethod, b: dev.apkproject.dex.DexMethod ->
            fun shape(m: dev.apkproject.dex.DexMethod) = dev.apkproject.lift.DataflowShape.of(m.dex, (dev.apkproject.dex.InstructionDecoder.decode(m.dex, m.code!!) as dev.apkproject.contracts.Outcome.Ok).value)
            shape(a)?.let { sa -> shape(b)?.let { sb -> sa.compare(sb) } }
        }
        assertEquals(mapOf("LO\$1;" to "LO\$2;", "LO\$2;" to "LO\$1;", "LO\$3;" to "LO\$3;"), AnonymousClassMatcher.match(original, recompiled, compare))
    }

    @Test
    fun `pom dependencies resolve properties and skip test, provided and optional scopes`() {
        val pom = """
            <project>
              <version>2.1.0</version>
              <properties><okio.version>3.6.0</okio.version></properties>
              <dependencyManagement><dependencies><dependency><groupId>x</groupId><artifactId>managed</artifactId><version>9</version></dependency></dependencies></dependencyManagement>
              <dependencies>
                <dependency><groupId>com.squareup.okio</groupId><artifactId>okio</artifactId><version>${'$'}{okio.version}</version></dependency>
                <dependency><groupId>com.example</groupId><artifactId>self-sibling</artifactId><version>${'$'}{project.version}</version><scope>runtime</scope></dependency>
                <!-- <dependency><groupId>a</groupId><artifactId>commented</artifactId><version>1</version></dependency> -->
                <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13</version><scope>test</scope></dependency>
                <dependency><groupId>p</groupId><artifactId>provided</artifactId><version>1</version><scope>provided</scope></dependency>
                <dependency><groupId>o</groupId><artifactId>optional</artifactId><version>1</version><optional>true</optional></dependency>
                <dependency><groupId>r</groupId><artifactId>ranged</artifactId><version>[1.0,2.0)</version></dependency>
              </dependencies>
            </project>
        """.trimIndent()
        assertEquals(listOf("com.squareup.okio:okio:3.6.0", "com.example:self-sibling:2.1.0"), MavenRepository.parsePom(pom, "2.1.0"))
    }

    @Test
    fun `versions order numerically and pre-releases are recognised`() {
        assertTrue(LibraryResolver.compareVersions("1.10.0", "1.9.2") > 0)
        assertTrue(LibraryResolver.compareVersions("1.6.1", "1.6") > 0)
        assertEquals(0, LibraryResolver.compareVersions("2.2.10", "2.2.10"))
        assertEquals(listOf("1.0.0", "1.1.0", "1.4.0"), listOf("1.4.0", "1.0.0", "1.1.0").sortedWith(LibraryResolver::compareVersions))
        assertTrue(LibraryResolver.isPreRelease("1.5.0-rc01") && LibraryResolver.isPreRelease("1.3.0-alpha02"))
        assertFalse(LibraryResolver.isPreRelease("1.9.0"))
    }

    @Test
    fun `obscured packages and fields are renamed, and the mapping reads back`() {
        fun cls(name: String, fields: String = "") = ".class public $name\n.super Ljava/lang/Object;\n$fields"
        // R8 output: package k1 and class b0.k1 share a name; field b0.a.p shares a name with class b0.p.
        val dex = TestApks.dexFile(cls("Lk1/s;") + cls("Lb0/k1;") + cls("Lb0/p;") + cls("Lb0/a;", ".field public p:I\n.field public q:I\n"))
        val m = NameMapping.compute(listOf("classes.dex" to dex))
        assertEquals(mapOf("Lk1/s;" to "Lk1_/s;"), m.classes)
        assertEquals(mapOf("Lb0/a;" to mapOf("p" to "p_f")), m.fields)
        assertEquals(mapOf("Lk1_/s;" to "Lk1/s;"), m.classesBack())
        assertEquals(mapOf("Lb0/a;->p_f:" to "Lb0/a;->p:"), m.fieldsBack())
        val tiny = dir.resolve("names.tiny")
        m.writeTiny(tiny, listOf("classes.dex" to dex))
        assertEquals(
            listOf("tiny\t2\t0\tofficial\tnamed", "c\tb0/a\tb0/a", "\tf\tI\tp\tp_f", "c\tk1/s\tk1_/s"),
            Files.readAllLines(tiny),
        )
    }

    @Test
    fun `fields cannot hide imported framework or mapped library types`() {
        val dex = TestApks.dexFile("""
            .class public Lother/R;
            .super Ljava/lang/Object;
            .class public Lother/R_f;
            .super Ljava/lang/Object;
            .class public Lapp/Example;
            .super Ljava/lang/Object;
            .field public R:I
            .field public R_f_:I
            .field public Long:I
            .field public String:I
            .field public java:I
            .field public a:[Ljava/lang/Long;
            .field public b:Ljava/lang/String;
            .field public c:Ljava/lang/StringBuilder;
        """.trimIndent())
        val mapping = NameMapping.compute(listOf("classes.dex" to dex))
        assertEquals(mapOf("R" to "R_f__", "Long" to "Long_f", "String" to "String_f", "java" to "java_f"), mapping.fields["Lapp/Example;"])
        val library = LibraryMatcher.Result(emptyList(), emptyMap(), mapOf("Lapp/Example;" to mapOf("a" to "StringBuilder")), 0)
        val mapped = NameMapping.compute(listOf("classes.dex" to dex), library)
        assertEquals("StringBuilder_f", mapped.fields["Lapp/Example;"]?.get("a"))
        assertEquals(mapped.fields.getValue("Lapp/Example;").size, mapped.fields.getValue("Lapp/Example;").values.toSet().size)
    }

    @Test
    fun `origin separates app code from libraries`() {
        val app = listOf("com.example.app")
        assertEquals("app", QualityScanner.origin("com/example/app/ui/Main.java", app))
        assertEquals("library", QualityScanner.origin("androidx/core/Foo.java", app))
        assertTrue(QualityScanner.origin("com/other/Thing.java", app) in setOf("other", "library"))
    }
}
