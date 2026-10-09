package dev.apkproject.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
    fun `origin separates app code from libraries`() {
        val app = listOf("com.example.app")
        assertEquals("app", QualityScanner.origin("com/example/app/ui/Main.java", app))
        assertEquals("library", QualityScanner.origin("androidx/core/Foo.java", app))
        assertTrue(QualityScanner.origin("com/other/Thing.java", app) in setOf("other", "library"))
    }
}
