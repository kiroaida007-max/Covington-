package dev.apkproject.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The Kotlin inference reproduces the training script's quantized model exactly (golden cases it wrote). */
class PrivacyApiModelTest {
    @Test
    fun `malformed model and descriptors abstain without unbounded allocation or hangs`() {
        assertFalse(PrivacyApiModel.validModelBytes(ByteArray(12)))
        assertFalse(PrivacyApiModel.validModelBytes(ByteArray(600_001)))
        val bytes = javaClass.getResourceAsStream("/dev/apkproject/app/privacy-api-model.bin")!!.use { it.readBytes() }
        assertTrue(PrivacyApiModel.validModelBytes(bytes))
        assertFalse(PrivacyApiModel.validModelBytes(bytes.copyOf(20)))
        assertFalse(PrivacyApiModel.validModelBytes(bytes + byteArrayOf(1)))
        val corrupt = bytes.copyOf().apply { java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(6, Int.MAX_VALUE) }
        assertFalse(PrivacyApiModel.validModelBytes(corrupt))
        for (ref in listOf("garbage", "LA;->f([)I", "LA;->f(Lbroken)I", "LA;->f(V)I", "LA;->f()Vgarbage")) assertNull(PrivacyApiModel.predict(ref))
    }
    @Test
    fun `predictions match the training script's`() {
        assertTrue(PrivacyApiModel.available, "privacy-api-model.bin is missing from the resources")
        val golden = javaClass.getResourceAsStream("/privacy-api-model-golden.tsv")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(golden.size >= 10)
        for (line in golden) {
            val (cls, ret, name, params, label, p) = line.split('\t')
            val prediction = assertNotNull(PrivacyApiModel.predict(cls, ret, name, if (params.isEmpty()) emptyList() else params.split(',')))
            assertEquals(label, prediction.label, line)
            assertEquals(p.toDouble(), prediction.probability, 1e-6, line)
        }
    }

    @Test
    fun `DEX references and features read like the training data`() {
        assertEquals("java.lang.String[]", PrivacyApiModel.javaName("[Ljava/lang/String;"))
        assertEquals("byte[]", PrivacyApiModel.javaName("[B"))
        assertEquals(listOf("get", "last", "known", "location"), PrivacyApiModel.camel("getLastKnownLocation"))
        assertEquals(listOf("get", "ssid"), PrivacyApiModel.camel("getSSID"))
        assertEquals(listOf("get", "url", "string"), PrivacyApiModel.camel("getURLString"))
        assertEquals(0x4f9f2cabL, PrivacyApiModel.fnv1a("hello"))
        val byRef = assertNotNull(PrivacyApiModel.predict("Landroid/location/LocationManager;->getLastKnownLocation(Ljava/lang/String;)Landroid/location/Location;"))
        assertEquals("source:location", byRef.label)
        assertTrue(byRef.probability > 0.95)
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
