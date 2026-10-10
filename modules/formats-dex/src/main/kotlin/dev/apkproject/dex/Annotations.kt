package dev.apkproject.dex

/** A value in an annotation or a static field initialiser (`encoded_value`). */
sealed class EncodedValue {
    data class Num(val value: Long, val kind: String) : EncodedValue()
    data class Real(val value: Double, val kind: String) : EncodedValue()
    data class Str(val value: String) : EncodedValue()
    data class TypeRef(val descriptor: String) : EncodedValue()
    data class FieldRef(val descriptor: String) : EncodedValue()
    data class EnumRef(val descriptor: String) : EncodedValue()
    data class MethodRef(val descriptor: String) : EncodedValue()
    data class Arr(val values: List<EncodedValue>) : EncodedValue()
    data class Nested(val annotation: DexAnnotation) : EncodedValue()
    data class Bool(val value: Boolean) : EncodedValue()
    object Null : EncodedValue()

    /** Other index kinds (method types, method handles), kept only as their kind. */
    data class Opaque(val kind: Int) : EncodedValue()
}

data class DexAnnotation(val type: String, val visibility: Int, val elements: Map<String, EncodedValue>) {
    /** Every string directly in the elements (arrays flattened). */
    fun strings(): List<String> = elements.values.flatMap { v ->
        when (v) {
            is EncodedValue.Str -> listOf(v.value)
            is EncodedValue.Arr -> v.values.filterIsInstance<EncodedValue.Str>().map { it.value }
            else -> emptyList()
        }
    }
}

/** Annotations of one class, keyed by field/method index; parameters list one set per parameter. */
data class ClassAnnotations(
    val classAnnotations: List<DexAnnotation>,
    val fields: Map<Int, List<DexAnnotation>>,
    val methods: Map<Int, List<DexAnnotation>>,
    val parameters: Map<Int, List<List<DexAnnotation>>>,
) {
    companion object {
        val EMPTY = ClassAnnotations(emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Bounded reader for `annotations_directory_item` and everything it points to. Counts and
 * nesting are limited, and every read is range-checked like the rest of the DEX reader.
 */
internal object AnnotationReader {
    private const val MAX_ITEMS = 1 shl 16
    private const val MAX_DEPTH = 16

    fun directory(r: DexBytes, off: Int, dex: DexFile): ClassAnnotations {
        if (off == 0) return ClassAnnotations.EMPTY
        val saved = r.section
        r.section = "annotations_directory"
        try {
            val classOff = r.u4Index(off, "class_annotations_off")
            val fields = count(r, off + 4, "fields_size")
            val methods = count(r, off + 8, "annotated_methods_size")
            val params = count(r, off + 12, "annotated_parameters_size")
            var p = off + 16
            val fieldMap = LinkedHashMap<Int, List<DexAnnotation>>()
            repeat(fields) { fieldMap[r.u4Index(p, "field_idx")] = set(r, r.u4Index(p + 4, "annotations_off"), dex); p += 8 }
            val methodMap = LinkedHashMap<Int, List<DexAnnotation>>()
            repeat(methods) { methodMap[r.u4Index(p, "method_idx")] = set(r, r.u4Index(p + 4, "annotations_off"), dex); p += 8 }
            val paramMap = LinkedHashMap<Int, List<List<DexAnnotation>>>()
            repeat(params) {
                val method = r.u4Index(p, "method_idx")
                val listOff = r.u4Index(p + 4, "annotations_off")
                p += 8
                if (listOff == 0) return@repeat
                val size = count(r, listOff, "annotation_set_ref_list size")
                paramMap[method] = List(size) { k -> set(r, r.u4Index(listOff + 4 + 4 * k, "annotation_set_ref"), dex) }
            }
            return ClassAnnotations(set(r, classOff, dex), fieldMap, methodMap, paramMap)
        } finally {
            r.section = saved
        }
    }

    private fun count(r: DexBytes, p: Int, what: String): Int {
        val n = r.u4Index(p, what)
        if (n > MAX_ITEMS) r.fail(p, "$what $n exceeds $MAX_ITEMS")
        return n
    }

    private fun set(r: DexBytes, off: Int, dex: DexFile): List<DexAnnotation> {
        if (off == 0) return emptyList()
        val size = count(r, off, "annotation_set size")
        return List(size) { k ->
            val itemOff = r.u4Index(off + 4 + 4 * k, "annotation_off")
            val c = r.Cursor(itemOff)
            val visibility = c.u1()
            annotation(r, c, dex, visibility, 0)
        }
    }

    private fun annotation(r: DexBytes, c: DexBytes.Cursor, dex: DexFile, visibility: Int, depth: Int): DexAnnotation {
        if (depth > MAX_DEPTH) r.fail(c.pos, "annotation nesting deeper than $MAX_DEPTH")
        val typeIdx = c.ulebIndex("annotation type_idx")
        if (typeIdx >= dex.typeIds.size) r.fail(c.pos, "annotation type index $typeIdx out of range")
        val size = c.ulebIndex("annotation size")
        if (size > MAX_ITEMS) r.fail(c.pos, "annotation has $size elements")
        val elements = LinkedHashMap<String, EncodedValue>()
        repeat(size) {
            val nameIdx = c.ulebIndex("annotation element name_idx")
            if (nameIdx >= dex.strings.size) r.fail(c.pos, "annotation element name index $nameIdx out of range")
            elements[dex.string(nameIdx)] = value(r, c, dex, depth + 1)
        }
        return DexAnnotation(dex.type(typeIdx), visibility, elements)
    }

    fun value(r: DexBytes, c: DexBytes.Cursor, dex: DexFile, depth: Int): EncodedValue {
        if (depth > MAX_DEPTH) r.fail(c.pos, "encoded value nesting deeper than $MAX_DEPTH")
        val header = c.u1()
        val type = header and 0x1f
        val arg = header ushr 5
        fun unsigned(): Long {
            var v = 0L
            for (k in 0..arg) v = v or (c.u1().toLong() shl (8 * k))
            return v
        }
        fun signed(): Long {
            val bytes = arg + 1
            val v = unsigned()
            val shift = 64 - 8 * bytes
            return (v shl shift) shr shift
        }
        fun index(limit: Int, what: String): Int {
            val at = c.pos
            val v = unsigned()
            if (v >= limit) r.fail(at, "$what index $v out of range")
            return v.toInt()
        }
        return when (type) {
            0x00 -> EncodedValue.Num(signed(), "byte")
            0x02 -> EncodedValue.Num(signed(), "short")
            0x03 -> EncodedValue.Num(unsigned(), "char")
            0x04 -> EncodedValue.Num(signed(), "int")
            0x06 -> EncodedValue.Num(signed(), "long")
            // Floats and doubles are zero-extended to the right: the bytes are the high-order ones.
            0x10 -> EncodedValue.Real(java.lang.Float.intBitsToFloat((unsigned() shl (8 * (3 - arg))).toInt()).toDouble(), "float")
            0x11 -> EncodedValue.Real(java.lang.Double.longBitsToDouble(unsigned() shl (8 * (7 - arg))), "double")
            0x15, 0x16 -> { unsigned(); EncodedValue.Opaque(type) }
            0x17 -> EncodedValue.Str(dex.string(index(dex.strings.size, "string")))
            0x18 -> EncodedValue.TypeRef(dex.type(index(dex.typeIds.size, "type")))
            0x19 -> EncodedValue.FieldRef(dex.fieldDescriptor(index(dex.fieldIds.size, "field")))
            0x1a -> EncodedValue.MethodRef(dex.methodDescriptor(index(dex.methodIds.size, "method")))
            0x1b -> EncodedValue.EnumRef(dex.fieldDescriptor(index(dex.fieldIds.size, "enum")))
            0x1c -> {
                val size = c.ulebIndex("encoded_array size")
                if (size > MAX_ITEMS) r.fail(c.pos, "encoded array has $size elements")
                EncodedValue.Arr(List(size) { value(r, c, dex, depth + 1) })
            }
            0x1d -> EncodedValue.Nested(annotation(r, c, dex, -1, depth + 1))
            0x1e -> EncodedValue.Null
            0x1f -> EncodedValue.Bool(arg != 0)
            else -> r.fail(c.pos - 1, "unknown encoded_value type 0x${type.toString(16)}")
        }
    }
}
