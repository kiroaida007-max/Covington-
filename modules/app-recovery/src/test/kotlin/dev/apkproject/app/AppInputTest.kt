package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AppInputTest {
    @TempDir
    lateinit var dir: Path

    private fun open(name: String, bytes: ByteArray): AppInput = when (val o = AppInput.open(TestApks.write(dir, name, bytes))) {
        is Outcome.Ok -> o.value
        is Outcome.Err -> fail("open $name: ${o.failure}")
    }

    @Test
    fun `bare dex is wrapped as a single part`() {
        val input = open("classes.dex", TestApks.dex())
        assertEquals(InputKind.DEX, input.kind)
        assertEquals(listOf("classes.dex"), input.dexFiles().map { it.first })
        // Wrapping is deterministic: the same DEX gives the same part bytes.
        assertTrue(input.parts.single().bytes.contentEquals(open("again.dex", TestApks.dex()).parts.single().bytes))
    }

    @Test
    fun `multidex apk keeps numeric dex order and ignores nested dex paths`() {
        val apk = TestApks.zip(
            linkedMapOf(
                "classes10.dex" to TestApks.dex("LTen;"),
                "classes2.dex" to TestApks.dex("LTwo;"),
                "classes.dex" to TestApks.dex("LOne;"),
                "assets/classes.dex" to TestApks.dex("LAsset;"),
                "AndroidManifest.xml" to ByteArray(8),
            ),
        )
        val input = open("app.apk", apk)
        assertEquals(InputKind.APK, input.kind)
        val dex = input.dexFiles()
        assertEquals(listOf("classes.dex", "classes2.dex", "classes10.dex"), dex.map { it.first })
        assertEquals(listOf("LOne;", "LTwo;", "LTen;"), dex.map { it.second.type(it.second.classDefs.single().classIdx) })
    }

    @Test
    fun `split apk bundles expose every split with prefixed dex names`() {
        val bundle = TestApks.zip(
            linkedMapOf(
                "base.apk" to TestApks.zip(mapOf("classes.dex" to TestApks.dex("LBase;"))),
                "split_feature.apk" to TestApks.zip(mapOf("classes.dex" to TestApks.dex("LFeature;"))),
                "manifest.json" to "{}".toByteArray(),
            ),
        )
        val input = open("app.xapk", bundle)
        assertEquals(InputKind.APK_BUNDLE, input.kind)
        assertEquals(listOf("base.apk!classes.dex", "split_feature.apk!classes.dex"), input.dexFiles().map { it.first })
    }

    @Test
    fun `app bundles read module dex folders`() {
        val aab = TestApks.zip(
            linkedMapOf(
                "base/dex/classes.dex" to TestApks.dex("LBase;"),
                "base/manifest/AndroidManifest.xml" to ByteArray(4),
                "feature/dex/classes.dex" to TestApks.dex("LFeature;"),
            ),
        )
        val input = open("app.aab", aab)
        assertEquals(InputKind.APP_BUNDLE_AAB, input.kind)
        assertEquals(listOf("app.aab!base/dex/classes.dex", "app.aab!feature/dex/classes.dex"), input.dexFiles().map { it.first })
    }

    @Test
    fun `archives without code are unsupported, not malformed`() {
        val o = AppInput.open(TestApks.write(dir, "res.zip", TestApks.zip(mapOf("res/values/strings.xml" to ByteArray(3)))))
        val failure = (o as? Outcome.Err)?.failure ?: fail("expected failure")
        assertTrue(failure.unsupported, failure.toString())
    }

    @Test
    fun `anatomy detects frameworks from paths and magic numbers`() {
        val hermes = byteArrayOf(0xc6.toByte(), 0x1f, 0xbc.toByte(), 0x03, 0xc1.toByte(), 0x03, 0x19, 0x1f, 0, 0)
        val apk = TestApks.zip(
            linkedMapOf(
                "classes.dex" to TestApks.dex(),
                "assets/index.android.bundle" to hermes,
                "lib/arm64-v8a/libhermes.so" to ByteArray(16),
                "lib/arm64-v8a/libfoo.so" to ByteArray(16),
                "assets/www/cordova.js" to "x".toByteArray(),
                "META-INF/androidx.core_core.version" to "1.9.0\n".toByteArray(),
                "META-INF/kotlinx_coroutines_core.version" to "1.7.3".toByteArray(),
                "META-INF/maven/com.squareup.okhttp3/okhttp/pom.properties" to "groupId=com.squareup.okhttp3\nartifactId=okhttp\nversion=4.12.0\n".toByteArray(),
            ),
        )
        val input = open("hybrid.apk", apk)
        val a = AnatomyDetector.detect(input, input.dexFiles())
        val ids = a.frameworks.map { it.id }
        assertTrue("java" in ids && "react-native-hermes" in ids && "cordova" in ids && "native" in ids, ids.toString())
        assertEquals(Recoverability.BINARY_ONLY, a.frameworks.first { it.id == "react-native-hermes" }.recoverability)
        assertTrue(a.javaScriptBundles.isEmpty(), "Hermes bytecode is not a JavaScript bundle")
        assertEquals(listOf("assets/www/"), a.webAssetRoots)
        assertEquals(
            listOf("androidx.core:core" to "1.9.0", "com.squareup.okhttp3:okhttp" to "4.12.0", "org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.7.3"),
            a.libraries.map { it.coordinate to it.version },
        )
        assertEquals(listOf("arm64-v8a", "arm64-v8a"), a.nativeLibraries.map { it.abi })
    }

    @Test
    fun `plain react native bundles are queued for javascript recovery`() {
        val apk = TestApks.zip(linkedMapOf("classes.dex" to TestApks.dex(), "assets/index.android.bundle" to "var __BUNDLE_START_TIME__=1;".toByteArray()))
        val input = open("rn.apk", apk)
        val a = AnatomyDetector.detect(input, input.dexFiles())
        assertEquals(Recoverability.DECOMPILABLE, a.frameworks.first { it.id == "react-native" }.recoverability)
        assertEquals(listOf("assets/index.android.bundle"), a.javaScriptBundles)
    }
}
