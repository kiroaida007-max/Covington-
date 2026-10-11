package dev.apkproject.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Kotlin inference reproduces the training script's quantized model exactly (golden cases it wrote). */
class PrivacyApiModelTest {
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
