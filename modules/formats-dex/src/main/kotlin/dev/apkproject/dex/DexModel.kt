package dev.apkproject.dex

import dev.apkproject.contracts.ByteRange
import dev.apkproject.contracts.MethodKey

const val NO_INDEX: Int = -1 // 0xffffffff as a signed Int

data class DexHeader(
    val version: Int,
    val checksum: Long,
    val signature: String,
    val fileSize: Int,
    val headerSize: Int,
    val linkSize: Int,
    val linkOff: Int,
    val mapOff: Int,
    val stringIds: Section,
    val typeIds: Section,
    val protoIds: Section,
    val fieldIds: Section,
    val methodIds: Section,
    val classDefs: Section,
    val data: Section,
)

data class Section(val size: Int, val offset: Int)

data class MapItem(val type: Int, val size: Int, val offset: Int)

data class ProtoId(val shorty: String, val returnType: Int, val parameters: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is ProtoId && shorty == other.shorty && returnType == other.returnType && parameters.contentEquals(other.parameters)

    override fun hashCode(): Int = (shorty.hashCode() * 31 + returnType) * 31 + parameters.contentHashCode()
}

data class FieldId(val classIdx: Int, val typeIdx: Int, val nameIdx: Int)

data class MethodId(val classIdx: Int, val protoIdx: Int, val nameIdx: Int)

data class EncodedField(val fieldIdx: Int, val accessFlags: Int)

data class EncodedMethod(val methodIdx: Int, val accessFlags: Int, val codeOff: Int)

data class ClassData(
    val staticFields: List<EncodedField>,
    val instanceFields: List<EncodedField>,
    val directMethods: List<EncodedMethod>,
    val virtualMethods: List<EncodedMethod>,
)

data class ClassDef(
    val classIdx: Int,
    val accessFlags: Int,
    val superclassIdx: Int,
    val interfaces: IntArray,
    val sourceFileIdx: Int,
    val annotationsOff: Int,
    val classDataOff: Int,
    val staticValuesOff: Int,
    val classData: ClassData?,
    /** File offset of this class_def_item, for provenance. */
    val itemOffset: Int,
)

data class TryItem(val startAddr: Int, val insnCount: Int, val handlerOff: Int)

data class CatchHandler(
    /** Pairs of (type index, handler address). */
    val typed: List<Pair<Int, Int>>,
    val catchAllAddr: Int,
)

data class CodeItem(
    val offset: Int,
    val registersSize: Int,
    val insSize: Int,
    val outsSize: Int,
    val debugInfoOff: Int,
    /** Raw 16-bit code units. */
    val insns: IntArray,
    /** File offset of insns[0]. */
    val insnsOffset: Int,
    val tries: List<TryItem>,
    /** Handlers keyed by their offset inside encoded_catch_handler_list. */
    val handlers: Map<Int, CatchHandler>,
) {
    fun handlerFor(t: TryItem): CatchHandler = handlers.getValue(t.handlerOff)
}

data class DebugPosition(val address: Int, val line: Int)

data class DebugLocal(val register: Int, val name: String?, val type: String?, val startAddress: Int, val endAddress: Int)

data class DebugInfo(
    val lineStart: Int,
    val parameterNames: List<String?>,
    val positions: List<DebugPosition>,
    val locals: List<DebugLocal>,
)

/** A method with its declaring class, as needed by later stages. */
data class DexMethod(
    val dex: DexFile,
    val classDef: ClassDef,
    val encoded: EncodedMethod,
) {
    val id: MethodId get() = dex.methodIds[encoded.methodIdx]
    val name: String get() = dex.string(id.nameIdx)
    val owner: String get() = dex.type(id.classIdx)
    val proto: ProtoId get() = dex.protos[id.protoIdx]
    val protoDescriptor: String get() = dex.protoDescriptor(id.protoIdx)
    val accessFlags: Int get() = encoded.accessFlags
    val isStatic: Boolean get() = accessFlags and AccessFlags.STATIC != 0
    val key: MethodKey get() = MethodKey(dex.sha256, owner, name, protoDescriptor)
    val code: CodeItem? by lazy { dex.codeItem(encoded.codeOff) }
    val debugInfo: DebugInfo? by lazy { code?.let { c -> if (c.debugInfoOff != 0) dex.debugInfo(c.debugInfoOff) else null } }

    fun byteRangeOf(address: Int, units: Int): ByteRange {
        val c = code ?: error("$name has no code")
        return ByteRange(dex.sha256, (c.insnsOffset + address * 2).toLong(), (units * 2).toLong())
    }
}

object AccessFlags {
    const val PUBLIC = 0x1
    const val PRIVATE = 0x2
    const val PROTECTED = 0x4
    const val STATIC = 0x8
    const val FINAL = 0x10
    const val SYNCHRONIZED = 0x20
    const val VOLATILE = 0x40
    const val BRIDGE = 0x40
    const val TRANSIENT = 0x80
    const val VARARGS = 0x80
    const val NATIVE = 0x100
    const val INTERFACE = 0x200
    const val ABSTRACT = 0x400
    const val STRICT = 0x800
    const val SYNTHETIC = 0x1000
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000
    const val CONSTRUCTOR = 0x10000
    const val DECLARED_SYNCHRONIZED = 0x20000

    enum class Kind { CLASS, FIELD, METHOD }

    private fun names(kind: Kind): List<Pair<Int, String>> = buildList {
        add(PUBLIC to "public"); add(PRIVATE to "private"); add(PROTECTED to "protected"); add(STATIC to "static")
        add(FINAL to "final")
        when (kind) {
            Kind.METHOD -> {
                add(SYNCHRONIZED to "synchronized"); add(BRIDGE to "bridge"); add(VARARGS to "varargs"); add(NATIVE to "native")
                add(ABSTRACT to "abstract"); add(STRICT to "strictfp"); add(SYNTHETIC to "synthetic")
                add(CONSTRUCTOR to "constructor"); add(DECLARED_SYNCHRONIZED to "declared-synchronized")
            }
            Kind.FIELD -> {
                add(VOLATILE to "volatile"); add(TRANSIENT to "transient"); add(SYNTHETIC to "synthetic"); add(ENUM to "enum")
            }
            Kind.CLASS -> {
                add(INTERFACE to "interface"); add(ABSTRACT to "abstract"); add(SYNTHETIC to "synthetic")
                add(ANNOTATION to "annotation"); add(ENUM to "enum")
            }
        }
    }

    /** Space-separated flag names; bits without a name for this kind render as a hex literal. */
    fun render(flags: Int, kind: Kind): String {
        var rest = flags
        val parts = ArrayList<String>()
        for ((bit, name) in names(kind)) {
            if (flags and bit != 0) {
                parts += name
                rest = rest and bit.inv()
            }
        }
        if (rest != 0) parts += "0x" + rest.toString(16)
        return parts.joinToString(" ")
    }

    fun parse(words: List<String>, kind: Kind): Int {
        val table = names(kind).associate { it.second to it.first }
        var flags = 0
        for (w in words) {
            flags = flags or (table[w] ?: if (w.startsWith("0x")) w.substring(2).toInt(16) else throw IllegalArgumentException("unknown ${kind.name.lowercase()} flag '$w'"))
        }
        return flags
    }
}
