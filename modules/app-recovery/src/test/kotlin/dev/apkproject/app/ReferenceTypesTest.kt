package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ReferenceTypesTest {
    @TempDir lateinit var dir: Path
    private val apk = "a".repeat(64)
    private val source = """
        package sample
        import sample.Notification.Type as Kind
        data class Notification(val type: Kind) {
            sealed class Type(val name: String)
        }
        class Elsewhere { enum class Type { A, B } }
        val lure = "data class Notification(val type: Elsewhere.Type) { } // fake"
        /* outer /* nested */ comment */
    """.trimIndent()
    private fun contract() = ApiContract(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(
        ModelSchema("sample.Notification", listOf(ModelField("type", "type", "a.b", nullable = false,
            wire = ScalarWireShape("string", listOf("sample")), evidence = listOf(SourceRef("dex-model-field", "classes.dex:Lsample/Notification;->x:La/b;")))),
            SourceRef("dex-moshi-adapter", "classes.dex:Lsample/NotificationJsonAdapter;"))), emptyList(), emptyList())
    private fun manifest(text: String = source): ReferenceTypeManifest {
        Files.writeString(dir.resolve("Notification.kt"), text)
        return ReferenceTypeManifest(apkSha256 = apk, repository = "https://example.org/project.git", revision = "b".repeat(40), bindings = listOf(
            ReferenceTypeBinding("sample.Notification", "type", "a.b", "sample.Notification.Type", "Notification.kt", Hashing.sha256(text.toByteArray()))))
    }
    private fun apply(m: ReferenceTypeManifest, c: ApiContract = contract()): ApiContract {
        val path = dir.resolve("reference.json")
        Files.writeString(path, Json.encode(m))
        return ReferenceTypes.apply(c, apk, path, dir)
    }

    @Test fun `source names stay distinct from bytecode identity and wire shape`() {
        val c = apply(manifest())
        val f = c.models.single().fields.single()
        assertEquals("a.b", f.javaType)
        assertEquals("sample.Notification.Type", f.referenceType?.name)
        assertContains(f.referenceType!!.scope, "REFERENCE_SOURCE")
        assertEquals(apk, f.referenceType!!.apkSha256)
        assertEquals(contract().models.single().fields.single().wire, f.wire)
        assertEquals(c, Json.decode<ApiContract>(Json.encode(c)))
        assertContains(ContractRenderer.openApi(c, "sample.apk", "1"), "x-apkproject-reference-type")
        assertContains(ContractRenderer.readme(c), "not prove bytecode identity")
        assertEquals(c, apply(manifest(), c))
    }

    @Test fun `wrong APK source bytes type and absent evidence fail before attribution`() {
        val m = manifest()
        assertFailsWith<IllegalArgumentException> { apply(m.copy(apkSha256 = "c".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { apply(m.copy(bindings = m.bindings.map { it.copy(sourceSha256 = "d".repeat(64)) })) }
        assertFailsWith<IllegalArgumentException> { apply(m.copy(bindings = m.bindings.map { it.copy(bytecodeType = "a.c") })) }
        assertFailsWith<IllegalArgumentException> { apply(m.copy(bindings = m.bindings.map { it.copy(referenceType = "sample.Elsewhere.Type") })) }
        val noEvidence = contract().let { c -> c.copy(models = c.models.map { it.copy(fields = it.fields.map { f -> f.copy(evidence = emptyList()) }) }) }
        assertFailsWith<IllegalArgumentException> { apply(m, noEvidence) }
        Files.writeString(dir.resolve("Notification.kt"), source + "\n// changed\n")
        assertFailsWith<IllegalArgumentException> { apply(m) }
    }

    @Test fun `duplicate bindings paths and credential URLs are rejected`() {
        val m = manifest()
        assertFailsWith<IllegalArgumentException> { apply(m.copy(bindings = m.bindings + m.bindings)) }
        for (p in listOf("../Notification.kt", "/Notification.kt", "C:/Notification.kt", "sub/../../Notification.kt", "sub\\Notification.kt")) {
            assertFailsWith<IllegalArgumentException> { apply(m.copy(bindings = m.bindings.map { it.copy(sourcePath = p) })) }
        }
        for (url in listOf("http://example.org/project", "https://user:pass@example.org/project", "https://example.org/project?key=value", "https://example.org/project#ref")) {
            assertFailsWith<IllegalArgumentException> { apply(m.copy(repository = url)) }
        }
        if (!dev.apkproject.contracts.isWindows()) {
            val outside = Files.createTempFile("external-reference-", ".kt")
            try {
                Files.writeString(outside, source)
                Files.createSymbolicLink(dir.resolve("escaped.kt"), outside)
                assertFailsWith<IllegalArgumentException> { apply(m.copy(bindings = m.bindings.map { it.copy(sourcePath = "escaped.kt") })) }
            } finally { Files.deleteIfExists(outside) }
        }
    }

    @Test fun `shadowed scopes nested default declarations and unsupported syntax cannot assert a type`() {
        val shadow = "package sample\nimport sample.Elsewhere.Type\ndata class Notification(val type: Type) { sealed class Type(val name: String) }\nclass Elsewhere { enum class Type { A } }"
        // Kotlin lexical declarations shadow imports. Until a compiler resolves that case, abstain.
        assertFailsWith<IllegalArgumentException> { apply(manifest(shadow)) }
        val shadowed = manifest(shadow)
        assertFailsWith<IllegalArgumentException> { apply(shadowed.copy(bindings = shadowed.bindings.map { it.copy(referenceType = "sample.Elsewhere.Type") })) }
        val nested = "package sample\ndata class Notification(val other: String = run { data class Local(val type: Notification.Type); \"x\" }) { sealed class Type(val name: String) }"
        assertFailsWith<IllegalArgumentException> { apply(manifest(nested)) }
        val generic = "package sample\ndata class Notification(val type: List<Notification.Type>) { sealed class Type(val name: String) }"
        assertFailsWith<IllegalArgumentException> { apply(manifest(generic)) }
        assertFailsWith<IllegalArgumentException> { apply(manifest(source + "\nval x = \"" + '$' + "{broken()}\"")) }
    }

    @Test fun `simple nested property resolves without an import and nullable syntax is accepted`() {
        val text = "package sample\ndata class Notification(val type: Type?) { sealed class Type(val name: String) }"
        assertEquals("sample.Notification.Type", apply(manifest(text)).models.single().fields.single().referenceType?.name)
    }

    @Test fun `source and manifest byte nesting and UTF8 bounds fail explicitly`() {
        val m = manifest()
        val tooLarge = " ".repeat(2 * 1024 * 1024) + source
        Files.writeString(dir.resolve("Notification.kt"), tooLarge)
        assertContains(assertFailsWith<IllegalArgumentException> { apply(m) }.message!!, "byte budget")
        val nested = source + "\n" + "{".repeat(257) + "}".repeat(257)
        assertContains(assertFailsWith<IllegalArgumentException> { apply(manifest(nested)) }.message!!, "nesting budget")
        Files.write(dir.resolve("Notification.kt"), byteArrayOf(0xc3.toByte(), 0x28))
        assertContains(assertFailsWith<IllegalArgumentException> { apply(m) }.message!!, "UTF-8")
        val file = dir.resolve("large.json")
        Files.writeString(file, " ".repeat(64 * 1024) + Json.encode(m))
        assertContains(assertFailsWith<IllegalArgumentException> { ReferenceTypes.apply(contract(), apk, file, dir) }.message!!, "byte budget")
    }
}
