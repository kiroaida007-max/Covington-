package dev.apkproject.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes Android's compiled (binary) XML, as `AndroidManifest.xml` ships inside an APK, back to
 * XML text in the form apktool and JADX print (`android:exported="true"`). References are named
 * through [ResourceTable] when the id is known (`@xml/network_security_config`), else written as
 * `@0x7f150003`. No external tool is needed, so manifest checks run in a second.
 */
object BinaryXml {
    private const val XML = 0x0003
    private const val STRING_POOL = 0x0001
    private const val RESOURCE_MAP = 0x0180
    private const val START_NAMESPACE = 0x0100
    private const val END_NAMESPACE = 0x0101
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val CDATA = 0x0104
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Attribute names an obfuscator may blank in the string pool, by their framework resource id. */
    private val ANDROID_ATTRS = mapOf(
        0x01010001 to "label", 0x01010002 to "icon", 0x01010003 to "name", 0x01010006 to "permission",
        0x01010007 to "readPermission", 0x01010008 to "writePermission", 0x01010009 to "protectionLevel",
        0x0101000b to "sharedUserId", 0x0101000e to "enabled", 0x0101000f to "debuggable", 0x01010010 to "exported",
        0x01010011 to "process", 0x01010018 to "authorities", 0x0101001b to "grantUriPermissions",
        0x01010024 to "value", 0x01010025 to "resource", 0x01010026 to "mimeType", 0x01010027 to "scheme",
        0x01010028 to "host", 0x01010029 to "port", 0x0101002a to "path", 0x0101002b to "pathPrefix",
        0x0101002c to "pathPattern", 0x0101020c to "minSdkVersion", 0x0101021b to "versionCode",
        0x0101021c to "versionName", 0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion",
        0x01010272 to "testOnly", 0x01010280 to "allowBackup", 0x010104ec to "usesCleartextTraffic",
        0x01010527 to "networkSecurityConfig",
    )

    /** The XML text, or null if [bytes] is not binary XML. */
    fun decode(bytes: ByteArray, resources: ResourceTable = ResourceTable.EMPTY): String? = runCatching { read(bytes, resources) }.getOrNull()

    private fun read(bytes: ByteArray, resources: ResourceTable): String? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || (b.getShort(0).toInt() and 0xffff) != XML) return null
        var strings: List<String> = emptyList()
        var resourceIds = IntArray(0)
        val namespaces = LinkedHashMap<String, String>() // uri -> prefix
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var rootWritten = false
        var openTag = false
        fun str(i: Int) = if (i < 0 || i >= strings.size) null else strings[i]
        var at = b.getShort(2).toInt() and 0xffff
        while (at + 8 <= bytes.size) {
            val type = b.getShort(at).toInt() and 0xffff
            val header = b.getShort(at + 2).toInt() and 0xffff
            val size = b.getInt(at + 4)
            if (size < 8 || at + size > bytes.size) break
            when (type) {
                STRING_POOL -> strings = ResourceTable.strings(b, at)
                RESOURCE_MAP -> resourceIds = IntArray((size - header) / 4) { b.getInt(at + header + 4 * it) }
                START_NAMESPACE -> {
                    val prefix = str(b.getInt(at + header))?.ifEmpty { null }
                    val uri = str(b.getInt(at + header + 4)) ?: ""
                    namespaces[uri] = prefix ?: if (uri == ANDROID_NS) "android" else "ns${namespaces.size}"
                }
                START_ELEMENT -> {
                    if (openTag) out.append(">\n")
                    val ext = at + header
                    val name = str(b.getInt(ext + 4)) ?: "element"
                    val attrStart = b.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = b.getShort(ext + 10).toInt() and 0xffff
                    val count = b.getShort(ext + 12).toInt() and 0xffff
                    out.append("    ".repeat(depth)).append('<').append(name)
                    if (!rootWritten) {
                        for ((uri, prefix) in namespaces) out.append(" xmlns:").append(prefix).append("=\"").append(escape(uri)).append('"')
                        rootWritten = true
                    }
                    for (k in 0 until count) {
                        val a = ext + attrStart + k * attrSize
                        val nsUri = str(b.getInt(a))
                        val nameIdx = b.getInt(a + 4)
                        val resId = resourceIds.getOrNull(nameIdx) ?: 0
                        val attrName = str(nameIdx)?.ifEmpty { null } ?: ANDROID_ATTRS[resId] ?: "attr_0x%08x".format(resId)
                        val prefix = nsUri?.let { namespaces[it] ?: if (it == ANDROID_NS) "android" else null }
                        val raw = str(b.getInt(a + 8))
                        val dataType = b.get(a + 15).toInt() and 0xff
                        val data = b.getInt(a + 16)
                        val value = raw ?: value(dataType, data, str(data), resources)
                        out.append(' ')
                        if (prefix != null) out.append(prefix).append(':')
                        out.append(attrName).append("=\"").append(escape(value)).append('"')
                    }
                    openTag = true
                    depth++
                }
                END_ELEMENT -> {
                    depth--
                    if (openTag) out.append(" />\n") else {
                        val name = str(b.getInt(at + header + 4)) ?: "element"
                        out.append("    ".repeat(depth)).append("</").append(name).append(">\n")
                    }
                    openTag = false
                }
                CDATA -> {
                    if (openTag) { out.append(">\n"); openTag = false }
                    str(b.getInt(at + header))?.takeIf { it.isNotBlank() }?.let { out.append("    ".repeat(depth)).append(escape(it.trim())).append('\n') }
                }
                END_NAMESPACE -> Unit
            }
            at += size
        }
        return if (rootWritten) out.toString() else null
    }

    private fun value(type: Int, data: Int, string: String?, resources: ResourceTable): String = when (type) {
        0x03 -> string ?: ""
        0x01, 0x07 -> reference(data, resources, "@")
        0x02 -> reference(data, resources, "?")
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        0x10 -> data.toString()
        0x12 -> if (data != 0) "true" else "false"
        in 0x1c..0x1f -> "#%08x".format(data)
        0x00 -> ""
        else -> "0x%08x".format(data)
    }

    private fun reference(id: Int, resources: ResourceTable, sigil: String): String {
        if (id == 0) return "${sigil}null"
        resources[id]?.let { (type, name) -> return "$sigil$type/$name" }
        return if (id ushr 24 == 0x01) "${sigil}android:0x%08x".format(id) else "${sigil}0x%08x".format(id)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
