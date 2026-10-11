package dev.apkproject.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a small AndroidManifest.xml in Android's binary XML form, as aapt2 would, so the demo APK
 * of the screenshot tour has real screens. Only what the tour needs: elements with string and
 * boolean attributes, the android namespace, and the attribute resource ids.
 */
object TourManifest {
    class Element(val name: String, val attrs: List<Pair<String, Any>> = emptyList(), val children: List<Element> = emptyList())

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val ATTR_IDS = mapOf("name" to 0x01010003, "exported" to 0x01010010)

    /** Encodes [root] and its descendants as a binary AndroidManifest.xml byte array. */
    fun encode(root: Element): ByteArray {
        // Attribute names with resource ids come first in the pool: the resource map is indexed alike.
        val strings = ArrayList<String>(ATTR_IDS.keys)
        fun index(s: String): Int = strings.indexOf(s).takeIf { it >= 0 } ?: strings.size.also { strings += s }
        fun collect(e: Element) {
            index(e.name)
            for ((k, v) in e.attrs) { index(k.removePrefix("android:")); if (v is String) index(v) }
            e.children.forEach(::collect)
        }
        index("android")
        index(ANDROID_NS)
        collect(root)

        val body = ByteArrayOutputStream()
        fun chunk(type: Int, header: Int, payload: ByteArray) {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(type.toShort()).putShort(header.toShort()).putInt(8 + payload.size)
            body.write(b.array())
            body.write(payload)
        }
        fun ints(vararg xs: Int): ByteArray = ByteBuffer.allocate(4 * xs.size).order(ByteOrder.LITTLE_ENDIAN).apply { xs.forEach { putInt(it) } }.array()

        // String pool (UTF-16).
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            data.size().also {
                val b = ByteBuffer.allocate(2 + 2 * s.length + 2).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(s.length.toShort())
                s.forEach { c -> b.putChar(c) }
                b.putShort(0)
                data.write(b.array())
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolHeader = 28
        chunk(0x0001, poolHeader, ints(strings.size, 0, 0, poolHeader + 4 * strings.size, 0) + ints(*offsets.toIntArray()) + data.toByteArray())
        chunk(0x0180, 8, ints(*ATTR_IDS.values.toIntArray()))
        chunk(0x0100, 16, ints(1, -1, index("android"), index(ANDROID_NS)))
        fun element(e: Element) {
            val attrs = ByteArrayOutputStream()
            for ((k, v) in e.attrs) {
                val android = k.startsWith("android:")
                val name = index(k.removePrefix("android:"))
                val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(if (android) index(ANDROID_NS) else -1).putInt(name)
                when (v) {
                    is String -> b.putInt(index(v)).putShort(8).put(0).put(0x03).putInt(index(v))
                    is Boolean -> b.putInt(-1).putShort(8).put(0).put(0x12).putInt(if (v) -1 else 0)
                    else -> error("unsupported attribute value $v")
                }
                attrs.write(b.array())
            }
            val ext = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            ext.putInt(-1).putInt(index(e.name)).putShort(20).putShort(20).putShort(e.attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
            chunk(0x0102, 16, ints(1, -1) + ext.array() + attrs.toByteArray())
            e.children.forEach(::element)
            chunk(0x0103, 16, ints(1, -1, -1, index(e.name)))
        }
        element(root)
        chunk(0x0101, 16, ints(1, -1, index("android"), index(ANDROID_NS)))

        val out = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003).putShort(8).putInt(8 + body.size())
        out.put(body.toByteArray())
        return out.array()
    }
}
