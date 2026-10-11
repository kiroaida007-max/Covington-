package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.FillArrayDataPayload
import dev.apkproject.dex.Format
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.dex.PackedSwitchPayload
import dev.apkproject.dex.SparseSwitchPayload
import dev.apkproject.lift.DataflowShape
import dev.apkproject.lift.RoundTripMatch
import kotlin.math.ln

/**
 * Every class of one build, tokenized once in a form that does not depend on obfuscated names.
 *
 * Some constants change with every release without any code edit, and are normalized:
 *  - resource ids the code inlines (`R.string.x` is a literal `0x7f13…`) are written as their
 *    resource names from [resources]: adding one string renumbers the ids after it;
 *  - the app's own [versionName] inside string constants (an About screen, a User-Agent);
 *  - the class id R8 passes to a merged class (it merges lambdas and small classes into one
 *    class that dispatches on an int field; ids shift when the group changes);
 *  - field names R8 writes into strings for reflective field updaters (`newUpdater(…, "n")`).
 */
class CodeIndex(
    dex: List<Pair<String, DexFile>>,
    private val resources: ResourceTable = ResourceTable.EMPTY,
    versionName: String? = null,
) {
    class Insn(
        /** Hash of the instruction without the classes it names: opcode, literals, strings, kept member names, relative branch targets. */
        val base: Long,
        /** The classes it names (owner, parameter and return types), as type ids. */
        val refs: IntArray,
        /** True for a call to a method the app defines: R8 may reorder its parameters (proto normalization). */
        val appCall: Boolean = false,
    ) {
        /** For `invoke-static` of an app method: its index in [statics], else -1. */
        var callee: Int = -1
            internal set
    }

    class Method(
        val owner: String,
        val name: String,
        val proto: String,
        val params: IntArray,
        val ret: Int,
        val flags: Int,
        val insns: List<Insn>,
        val dexMethod: DexMethod,
        /** String constants the code loads. */
        val strings: List<String>,
        /** Members of classes outside the app (framework, platform) the code uses, e.g. `Landroid/location/LocationManager;->getLastKnownLocation(...)`. */
        val externalRefs: List<String>,
    ) {
        val isStatic: Boolean get() = flags and AccessFlags.STATIC != 0
        val signature: String get() = name + proto
        val ref: String get() = "$owner->$name$proto"

        /** Index in [statics] for a static method with code, else -1. */
        var staticIndex: Int = -1
            internal set
    }

    class Field(val name: String, val type: Int, val flags: Int) {
        val isStatic: Boolean get() = flags and AccessFlags.STATIC != 0
    }

    class Class(
        val index: Int,
        val name: String,
        val type: Int,
        val flags: Int,
        val superType: Int,
        val interfaces: IntArray,
        val fields: List<Field>,
        val methods: List<Method>,
    ) {
        val strings: Set<String> by lazy { methods.flatMapTo(LinkedHashSet()) { it.strings } }
        val externalRefs: Set<String> by lazy { methods.flatMapTo(LinkedHashSet()) { it.externalRefs } }
        val javaName: String get() = name.removePrefix("L").removeSuffix(";").replace('/', '.')
    }

    private val typeIds = HashMap<String, Int>()
    val typeNames = ArrayList<String>()
    val classes: List<Class>

    /** Static methods with code. R8 moves them between classes (it merges unrelated static helpers into one class), so calls name them, not their class. */
    val statics: List<Method>

    /** Type id -> index in [classes], or -1 for a class the app does not define. */
    val classOfType: IntArray

    private val version = versionName?.takeIf { it.length >= 3 && it.any(Char::isDigit) }
        ?.let { Regex("(?<![0-9A-Za-z.])" + Regex.escape(it) + "(?![0-9A-Za-z]|\\.[0-9])") }

    /** The stable integer id for a type descriptor, assigning a new one if it has not been seen before. */
    fun typeId(descriptor: String): Int = typeIds.getOrPut(descriptor) { typeNames += descriptor; typeNames.size - 1 }

    /** The app's own [Class] with this descriptor, or null if it is not one the app defines. */
    fun classNamed(descriptor: String): Class? = typeIds[descriptor]?.let { classOfType.getOrNull(it) }?.takeIf { it >= 0 }?.let { classes[it] }

    /** Classes R8 built by merging several classes: they dispatch on an int class id set by the constructor. */
    val merged: Set<String>

    init {
        val defined = HashSet<String>()
        for ((_, d) in dex) for (c in d.classDefs) defined += d.type(c.classIdx)
        merged = dex.flatMap { (_, d) -> mergedClasses(d) }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<Class>()
        val calls = ArrayList<Pair<Insn, String>>()
        for ((_, d) in dex) {
            val byClass = d.methods().groupBy { it.classDef.classIdx }
            for (c in d.classDefs) {
                val name = d.type(c.classIdx)
                if (!seen.add(name)) continue
                val data = c.classData
                val fields = data?.let { (it.staticFields + it.instanceFields) }.orEmpty().map { f ->
                    val id = d.fieldIds[f.fieldIdx]
                    Field(d.string(id.nameIdx), typeId(d.type(id.typeIdx)), f.accessFlags)
                }
                val methods = byClass[c.classIdx].orEmpty().map { m -> method(d, m, defined, calls) }
                out += Class(
                    out.size, name, typeId(name), c.accessFlags,
                    c.superclassIdx.takeIf { it >= 0 && it < d.typeIds.size }?.let { typeId(d.type(it)) } ?: -1,
                    c.interfaces.map { typeId(d.type(it)) }.toIntArray(), fields, methods,
                )
            }
        }
        classes = out
        classOfType = IntArray(typeNames.size) { -1 }
        for (c in classes) classOfType[c.type] = c.index
        statics = classes.flatMap { c -> c.methods.filter { it.isStatic && it.insns.isNotEmpty() } }
        statics.forEachIndexed { k, m -> m.staticIndex = k }
        val byRef = statics.associateBy { it.ref }
        for ((insn, ref) in calls) byRef[ref]?.let { insn.callee = it.staticIndex }
    }

    /** Builds a tokenized [Method] from one decoded DEX method. */
    private fun method(d: DexFile, m: DexMethod, defined: Set<String>, calls: MutableList<Pair<Insn, String>>): Method {
        val proto = m.proto
        val params = proto.parameters.map { typeId(d.type(it)) }.toIntArray()
        val ret = typeId(d.type(proto.returnType))
        val code = m.code
        val decoded = code?.let { (InstructionDecoder.decode(d, it) as? Outcome.Ok)?.value }
        val strings = ArrayList<String>()
        val external = ArrayList<String>()
        val insns = decoded?.let { tokens(d, it, defined, strings, external, calls) }.orEmpty()
        return Method(m.owner, m.name, m.protoDescriptor, params, ret, m.accessFlags, insns, m, strings, external)
    }

    /** Tokenizes a method's decoded instructions into [Insn]s, collecting its strings and external references. */
    private fun tokens(
        d: DexFile,
        code: DecodedCode,
        defined: Set<String>,
        strings: MutableList<String>,
        external: MutableList<String>,
        calls: MutableList<Pair<Insn, String>>,
    ): List<Insn> {
        val position = HashMap<Int, Int>(code.insns.size * 2)
        code.insns.forEachIndexed { k, i -> position[i.address] = k }
        return code.insns.mapIndexed { k, i ->
            val mnemonic = CodeMatcher.base(i.op.mnemonic)
            val text = StringBuilder(mnemonic)
            val refs = ArrayList<Int>(4)
            var call: String? = null
            if (i.op.format in LITERAL_FORMATS) text.append(" #").append(if (mnemonic == "const" && isMergedClassId(d, code, k)) "{classId}" else constant(i.literal))
            when (i.op.index) {
                IndexKind.STRING -> d.string(i.index).also {
                    strings += it
                    text.append(" s:").append(if (isUpdaterFieldName(d, code, k, it)) "{fieldName}" else version?.replace(it, "{versionName}") ?: it)
                }
                IndexKind.TYPE -> refs += typeId(d.type(i.index))
                IndexKind.FIELD -> {
                    val f = d.fieldIds[i.index]
                    val owner = d.type(f.classIdx)
                    val name = d.string(f.nameIdx)
                    val outside = owner !in defined
                    if (outside) external += d.fieldDescriptor(i.index)
                    text.append(" f:").append(if (outside || CodeMatcher.stableMember(name)) name else "?")
                    refs += typeId(owner); refs += typeId(d.type(f.typeIdx))
                }
                IndexKind.METHOD, IndexKind.METHOD_AND_PROTO -> {
                    val mid = d.methodIds[i.index]
                    val owner = d.type(mid.classIdx)
                    val name = d.string(mid.nameIdx)
                    val outside = owner !in defined
                    if (outside) external += d.methodDescriptor(i.index)
                    else if (mnemonic == "invoke-static") call = d.methodDescriptor(i.index)
                    text.append(" m:").append(if (outside || CodeMatcher.stableMember(name)) name else "?")
                    refs += typeId(owner)
                    val p = d.protos[mid.protoIdx]
                    p.parameters.forEach { refs += typeId(d.type(it)) }
                    refs += typeId(d.type(p.returnType))
                }
                IndexKind.NONE -> Unit
                else -> text.append(" x:").append(i.op.index.name)
            }
            if (i.target >= 0 && !i.op.payloadRef) text.append(" @").append((position[i.target] ?: k) - k)
            when (val p = code.payloads[i.target]) {
                is PackedSwitchPayload, is SparseSwitchPayload -> code.switchCases(i).forEach { (key, t) -> text.append(' ').append(constant(key.toLong())).append(':').append((position[t] ?: k) - k) }
                is FillArrayDataPayload -> {
                    text.append(" data ").append(p.elementWidth).append('x').append(p.count)
                    if (p.elementWidth == 4 && resources.size > 0) {
                        val b = java.nio.ByteBuffer.wrap(p.data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (e in 0 until p.count) text.append(' ').append(constant(b.getInt(4 * e).toLong()))
                    } else text.append(' ').append(p.data.contentHashCode())
                }
                else -> Unit
            }
            if (i.op.format in INVOKE_FORMATS) text.append(" /").append(i.regs.size)
            val appCall = i.op.index == IndexKind.METHOD && d.type(d.methodIds[i.index].classIdx) in defined
            Insn(CodeMatcher.hash(text), refs.toIntArray(), appCall).also { insn -> call?.let { calls += insn to it } }
        }
    }

    /** The first later instruction that reads the register instruction [k] wrote, or null if it is overwritten first. */
    private fun firstUse(code: DecodedCode, k: Int): dev.apkproject.dex.Insn? {
        val r = code.insns[k].regs.firstOrNull() ?: return null
        for (j in k + 1 until minOf(code.insns.size, k + 64)) {
            val x = code.insns[j]
            if (r in DataflowShape.reads(x)) return x
            if (r in DataflowShape.writes(x, code.code.registersSize)) return null
        }
        return null
    }

    /** An int constant whose first use is the class-id argument of a merged class's constructor. */
    private fun isMergedClassId(d: DexFile, code: DecodedCode, k: Int): Boolean {
        if (merged.isEmpty()) return false
        val use = firstUse(code, k) ?: return false
        if (CodeMatcher.base(use.op.mnemonic) != "invoke-direct" || use.regs.getOrNull(1) != code.insns[k].regs[0]) return false
        val m = d.methodIds[use.index]
        return d.string(m.nameIdx) == "<init>" && d.type(m.classIdx) in merged && d.protos[m.protoIdx].parameters.firstOrNull()?.let { d.type(it) } == "I"
    }

    /** A one- or two-letter string handed to a reflective field lookup: R8 rewrote it to an obfuscated field name. */
    private fun isUpdaterFieldName(d: DexFile, code: DecodedCode, k: Int, s: String): Boolean {
        if (s.length > 2 || !s.all { it.isLetter() }) return false
        val use = firstUse(code, k) ?: return false
        if (use.op.index != IndexKind.METHOD) return false
        val target = d.methodDescriptor(use.index)
        return "FieldUpdater;->newUpdater(" in target || "Ljava/lang/Class;->getDeclaredField(" in target || "objectFieldOffset(" in target
    }

    /**
     * Classes R8 merged: a constructor taking an int first that stores it into an int field of the
     * class, and a method that switches on that field.
     */
    private fun mergedClasses(d: DexFile): List<String> {
        val out = ArrayList<String>()
        val byClass = d.methods().filter { it.code != null }.groupBy { it.owner }
        for ((owner, methods) in byClass) {
            val ids = methods.filter { it.name == "<init>" && it.protoDescriptor.startsWith("(I") }.mapNotNull { m ->
                val code = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value ?: return@mapNotNull null
                val self = m.code!!.registersSize - m.code!!.insSize
                val first = code.insns.firstOrNull { !it.op.mnemonic.startsWith("move") } ?: return@mapNotNull null
                if (first.op.mnemonic != "iput" || first.regs.toList() != listOf(self + 1, self)) return@mapNotNull null
                d.fieldDescriptor(first.index).takeIf { it.startsWith("$owner->") && it.endsWith(":I") }
            }.toSet()
            if (ids.isEmpty()) continue
            val dispatches = methods.any { m ->
                val code = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value ?: return@any false
                code.insns.withIndex().any { (k, i) ->
                    i.op.mnemonic == "iget" && d.fieldDescriptor(i.index) in ids &&
                        code.insns.getOrNull(k + 1)?.let { n -> n.op.mnemonic.endsWith("-switch") && n.regs.firstOrNull() == i.regs[0] } == true
                }
            }
            if (dispatches) out += owner
        }
        return out
    }

    /** A literal, or `@type/name` when it is a resource id of the app's own package. */
    private fun constant(value: Long): String {
        if (value ushr 24 != 0x7fL || resources.size == 0) return value.toString()
        val (type, name) = resources[value.toInt()] ?: return value.toString()
        return "@$type/$name"
    }

    private companion object {
        val LITERAL_FORMATS = setOf(Format.F11n, Format.F21s, Format.F21h, Format.F31i, Format.F51l, Format.F22b, Format.F22s)
        val INVOKE_FORMATS = setOf(Format.F35c, Format.F3rc, Format.F45cc, Format.F4rcc)
    }
}

/**
 * Matches the classes and methods of two builds of one app across obfuscation. R8 hands out short
 * names (`a.b`, `c`) in order, so one added class renames others between releases, and comparing
 * by name compares the wrong classes. What renaming does not change is matched instead: the
 * operations, constants, strings, framework calls and the shape of every reference, with the
 * app's own classes standing for whatever they were matched to so far.
 *
 *  1. Classes whose names are evidently not obfuscated are paired by name ([How.NAME]).
 *  2. Classes whose code is identical once names are factored out are paired when that code is
 *     unique on both sides ([How.IDENTICAL_CODE]). Each pairing turns the classes into known names
 *     for the next round, which separates more classes; rounds repeat until nothing new pairs.
 *     Static methods are paired the same way on their own, because R8 merges unrelated static
 *     helpers into shared classes and redistributes them between builds: a static call is
 *     identified by the method it reaches, not by the class that currently holds it.
 *  3. The rest are compared by weighted token overlap and paired only when each is the other's
 *     clearly best candidate ([How.SIMILAR_CODE]); identical-code rounds then resume.
 *  4. Classes still alone were added or removed.
 *
 * Within a pair, methods are matched the same way. A static method that only moved to another
 * class is [MethodStatus.MOVED], not a change. A changed method is re-checked with
 * [DataflowShape] after renaming both sides to common names: if only code layout differs (block
 * order, registers), it is [MethodStatus.SAME_BEHAVIOUR], not an edit.
 */
object CodeMatcher {
    enum class How { NAME, IDENTICAL_CODE, IDENTICAL_CODE_AMBIGUOUS, SIMILAR_CODE }

    enum class MethodStatus {
        UNCHANGED,
        /** Differs only in code layout (block order, registers): same dataflow under common names. */
        SAME_BEHAVIOUR,
        /** A static method R8 placed in another class this time. */
        MOVED,
        /** The same instructions; only the classes they reference differ, consistently (R8 regroups merged classes between builds). */
        REFERENCES_ONLY,
        MODIFIED,
        ADDED,
        REMOVED,
    }

    class ClassPair(val old: CodeIndex.Class, val new: CodeIndex.Class, val how: How, val similarity: Double)

    class MethodPair(val old: CodeIndex.Method?, val new: CodeIndex.Method?, val status: MethodStatus, val similarity: Double)

    class Result(
        val old: CodeIndex,
        val new: CodeIndex,
        val pairs: List<ClassPair>,
        val removed: List<CodeIndex.Class>,
        val added: List<CodeIndex.Class>,
        /** Static methods paired across builds, wherever R8 placed them. */
        val staticPairs: List<Pair<CodeIndex.Method, CodeIndex.Method>>,
        val rounds: Int,
        internal val idOld: LongArray,
        internal val idNew: LongArray,
        internal val midOld: LongArray,
        internal val midNew: LongArray,
    ) {
        private val byOld = pairs.associateBy { it.old.name }
        private val byNew = pairs.associateBy { it.new.name }
        internal val pairedStatics: Set<CodeIndex.Method> = staticPairs.flatMapTo(HashSet()) { listOf(it.first, it.second) }
        internal val methodCache = HashMap<ClassPair, List<MethodPair>>()
        /** Hashes of every static method of each side, paired or not (identical copies cannot be paired one to one). */
        internal val staticHashesOld: Set<Long> by lazy { Hasher(old, idOld, midOld).let { h -> old.statics.mapTo(HashSet()) { h.method(it) } } }
        internal val staticHashesNew: Set<Long> by lazy { Hasher(new, idNew, midNew).let { h -> new.statics.mapTo(HashSet()) { h.method(it) } } }
        fun pairOfOld(descriptor: String): ClassPair? = byOld[descriptor]
        fun pairOfNew(descriptor: String): ClassPair? = byNew[descriptor]
    }

    private const val UNKNOWN = 0x5bd1e9955bd1e995L
    private const val UNKNOWN_METHOD = 0x2545f4914f6cdd1dL
    private const val KIND = AccessFlags.INTERFACE or AccessFlags.ABSTRACT or AccessFlags.ENUM or AccessFlags.ANNOTATION
    private const val METHOD_KIND = AccessFlags.STATIC or AccessFlags.ABSTRACT or AccessFlags.NATIVE

    // ---- names -----------------------------------------------------------------------------

    /**
     * A class name an obfuscator evidently did not assign: an outer simple name of three or more
     * characters with an upper-case letter (or five or more), and no compiler-numbered parts
     * (`$1`, `$$ExternalSyntheticLambda0`), whose numbers shift when code is added.
     */
    fun stableClassName(descriptor: String): Boolean {
        val simple = descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/')
        if ("$$" in simple || "-$" in simple || "\$r8\$" in simple || "ExternalSynthetic" in simple || "\$Lambda" in simple) return false
        val parts = simple.split('$')
        if (parts.any { it.isEmpty() || it.all(Char::isDigit) }) return false
        val outer = parts[0]
        if (outer == "R") return true
        fun meaningful(s: String) = s.length >= 3 && (s.any(Char::isUpperCase) || s.length >= 5)
        return meaningful(outer) && parts.drop(1).all { it.length >= 3 }
    }

    /** A member name that is neither obfuscated (`a`, `bc`) nor compiler-numbered (`lambda$onCreate$0`, `access$000`). */
    fun stableMember(name: String): Boolean = name.startsWith('<') || (name.length >= 3 && '$' !in name)

    // ---- hashing ---------------------------------------------------------------------------

    /** FNV-1a hash of a string. */
    internal fun hash(s: CharSequence): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return fmix(h)
    }

    /** Bit mixer used to spread hash bits before combining them. */
    private fun fmix(k: Long): Long {
        var x = k
        x = x xor (x ushr 33); x *= -0xae502812aa7333L
        x = x xor (x ushr 33); x *= -0x3b314601e57a13adL
        return x xor (x ushr 33)
    }

    /** Combines a running hash [h] with one more value [v]. */
    private fun combine(h: Long, v: Long): Long = fmix(h * -0x61c8864680b583ebL + v)

    /** Order-independent hash of a set of values (sorted before combining). */
    private fun unordered(values: LongArray): Long {
        values.sort()
        var h = values.size.toLong()
        for (v in values) h = combine(h, v)
        return h
    }

    /** Strips Dalvik opcode size/format suffixes so instructions that differ only in encoding compare equal. */
    internal fun base(m: String): String = m
        .removeSuffix("/range").removeSuffix("/jumbo").removeSuffix("/2addr").removeSuffix("/lit8").removeSuffix("/lit16")
        .removeSuffix("/16").removeSuffix("/32").removeSuffix("/4").removeSuffix("/high16").removeSuffix("/from16")

    /** Hashes under the current identities of one side's classes ([id]) and static methods ([mid]). */
    private class Hasher(val index: CodeIndex, val id: LongArray, val mid: LongArray) {
        private val names = HashMap<String, Long>()
        /** Caches the hash of a name string. */
        fun nameHash(n: String) = names.getOrPut(n) { hash(n) }
        /** Hashes one instruction under the current class/static-method identities. */
        fun insn(i: CodeIndex.Insn): Long {
            var h = i.base
            val callee = i.callee
            // The method reached, not the class R8 put it in this time. Once it is paired, its
            // identity says everything.
            if (callee >= 0 && mid[callee] != UNKNOWN_METHOD) return combine(h, mid[callee])
            if (i.appCall) {
                // R8 sorts the parameters of app methods by type (proto normalization), and the
                // order follows obfuscated names, so it changes between builds: compare them as a set.
                if (callee < 0) h = combine(h, id[i.refs[0]])
                h = combine(h, unordered(LongArray(i.refs.size - 2) { id[i.refs[it + 1]] }))
                return combine(h, id[i.refs[i.refs.size - 1]])
            }
            for (r in i.refs) h = combine(h, id[r])
            return h
        }
        /** Hashes a method's body as the ordered sequence of its instruction hashes. */
        fun body(m: CodeIndex.Method): Long {
            var h = m.insns.size.toLong()
            for (i in m.insns) h = combine(h, insn(i))
            return h
        }
        /** Name (when kept), kind, parameter types as a set (see [insn]) and return type. */
        fun shape(m: CodeIndex.Method): Long = combine(if (stableMember(m.name)) nameHash(m.name) else 1L, anonymousShape(m))
        /** Shape without the name, for methods R8 may have renamed. */
        fun anonymousShape(m: CodeIndex.Method): Long {
            val h = combine((m.flags and METHOD_KIND).toLong(), unordered(LongArray(m.params.size) { id[m.params[it]] }))
            return combine(h, id[m.ret])
        }
        /** Hashes a whole method: its shape combined with its body. */
        fun method(m: CodeIndex.Method) = combine(shape(m), body(m))
        /** Hashes a field by its kept name (or none), static flag and type. */
        fun field(f: CodeIndex.Field) = combine(combine(if (stableMember(f.name)) nameHash(f.name) else 1L, (f.flags and AccessFlags.STATIC).toLong()), id[f.type])
        /** Hashes a class by its kind, supertype, interfaces, fields and methods (all order-independent). */
        fun cls(c: CodeIndex.Class): Long {
            var h = combine((c.flags and KIND).toLong(), if (c.superType < 0) 0 else id[c.superType])
            h = combine(h, unordered(LongArray(c.interfaces.size) { id[c.interfaces[it]] }))
            h = combine(h, unordered(LongArray(c.fields.size) { field(c.fields[it]) }))
            return combine(h, unordered(LongArray(c.methods.size) { method(c.methods[it]) }))
        }
        /** Multiset of tokens for similarity: instructions, member shapes, fields, supertypes. */
        fun tokens(c: CodeIndex.Class): LongArray {
            val out = ArrayList<Long>()
            for (m in c.methods) {
                out += combine(7, anonymousShape(m))
                if (stableMember(m.name)) out += combine(11, shape(m))
                for (i in m.insns) out += insn(i)
            }
            for (f in c.fields) out += combine(13, field(f))
            if (c.superType >= 0) out += combine(17, id[c.superType])
            for (i in c.interfaces) out += combine(19, id[i])
            return out.toLongArray().also { it.sort() }
        }
        /** Sorted instruction-hash multiset of a method, for similarity scoring. */
        fun tokens(m: CodeIndex.Method): LongArray = LongArray(m.insns.size) { insn(m.insns[it]) }.also { it.sort() }
    }

    // ---- class matching --------------------------------------------------------------------

    /** Matches the classes and static methods of two builds across obfuscation; see the class doc for the algorithm. */
    fun match(old: CodeIndex, new: CodeIndex): Result {
        val idOld = LongArray(old.typeNames.size) { t -> if (old.classOfType.getOrElse(t) { -1 } >= 0) UNKNOWN else hash(old.typeNames[t]) }
        val idNew = LongArray(new.typeNames.size) { t -> if (new.classOfType.getOrElse(t) { -1 } >= 0) UNKNOWN else hash(new.typeNames[t]) }
        val midOld = LongArray(old.statics.size) { UNKNOWN_METHOD }
        val midNew = LongArray(new.statics.size) { UNKNOWN_METHOD }
        val pairOld = IntArray(old.classes.size) { -1 }
        val pairNew = IntArray(new.classes.size) { -1 }
        val pairs = ArrayList<ClassPair>()
        val staticPairs = ArrayList<Pair<CodeIndex.Method, CodeIndex.Method>>()
        val ho = Hasher(old, idOld, midOld)
        val hn = Hasher(new, idNew, midNew)
        // Which classes mention a type or call a static method, so only those are re-hashed when it gets an identity.
        /** Reverse index of which classes reference a given type or call a given static method. */
        class Users(index: CodeIndex) {
            val ofType: Array<IntArray>
            val ofStatic: Array<IntArray>
            init {
                val t = Array(index.typeNames.size) { HashSet<Int>() }
                val s = Array(index.statics.size) { HashSet<Int>() }
                for (c in index.classes) {
                    if (c.superType >= 0) t[c.superType] += c.index
                    c.interfaces.forEach { t[it] += c.index }
                    c.fields.forEach { t[it.type] += c.index }
                    for (m in c.methods) {
                        m.params.forEach { t[it] += c.index }; t[m.ret] += c.index
                        for (i in m.insns) {
                            for (r in i.refs) t[r] += c.index
                            if (i.callee >= 0) s[i.callee] += c.index
                        }
                    }
                }
                ofType = Array(t.size) { t[it].toIntArray() }
                ofStatic = Array(s.size) { s[it].toIntArray() }
            }
        }
        val usersOld = Users(old)
        val usersNew = Users(new)
        val dirtyOld = HashSet<Int>(old.classes.indices.toList())
        val dirtyNew = HashSet<Int>(new.classes.indices.toList())
        /** Records a class pairing and gives both classes a shared identity so later rounds see them as known. */
        fun assign(o: CodeIndex.Class, n: CodeIndex.Class, how: How, similarity: Double) {
            val k = pairs.size
            pairs += ClassPair(o, n, how, similarity)
            pairOld[o.index] = k; pairNew[n.index] = k
            val identity = hash("#pair$k")
            idOld[o.type] = identity; idNew[n.type] = identity
            usersOld.ofType[o.type].forEach { dirtyOld += it }
            usersNew.ofType[n.type].forEach { dirtyNew += it }
        }
        /** Records a static method pairing and gives both methods a shared identity. */
        fun assignStatic(a: CodeIndex.Method, b: CodeIndex.Method) {
            val identity = hash("#static${staticPairs.size}")
            staticPairs += a to b
            midOld[a.staticIndex] = identity; midNew[b.staticIndex] = identity
            usersOld.ofStatic[a.staticIndex].forEach { dirtyOld += it }
            usersNew.ofStatic[b.staticIndex].forEach { dirtyNew += it }
        }

        // 1. Names an obfuscator did not assign.
        for (o in old.classes) {
            if (!stableClassName(o.name)) continue
            val n = new.classNamed(o.name) ?: continue
            if (pairNew[n.index] < 0) assign(o, n, How.NAME, 1.0)
        }

        val hashOld = LongArray(old.classes.size)
        val hashNew = LongArray(new.classes.size)
        /** Recomputes the class hash of every class made dirty by a pairing since the last refresh. */
        fun refresh() {
            for (c in dirtyOld) if (pairOld[c] < 0) hashOld[c] = ho.cls(old.classes[c])
            for (c in dirtyNew) if (pairNew[c] < 0) hashNew[c] = hn.cls(new.classes[c])
            dirtyOld.clear(); dirtyNew.clear()
        }
        // Who uses a class (matched classes only), to separate classes whose own code is identical.
        fun incoming(index: CodeIndex, users: Users, pair: IntArray, h: Hasher, c: CodeIndex.Class): Long =
            unordered(users.ofType[c.type].filter { pair[it] >= 0 }.map { u -> combine(h.id[index.classes[u].type], index.classes[u].methods.count { m -> m.insns.any { c.type in it.refs } }.toLong()) }.toLongArray())

        // Static methods with identical code, unique on both sides.
        fun staticRound(): Int {
            val so = old.statics.filter { midOld[it.staticIndex] == UNKNOWN_METHOD }.groupBy { ho.method(it) }
            val sn = new.statics.filter { midNew[it.staticIndex] == UNKNOWN_METHOD }.groupBy { hn.method(it) }
            var found = 0
            for ((h, os) in so) {
                val ns = sn[h] ?: continue
                if (os.size == 1 && ns.size == 1) { assignStatic(os[0], ns[0]); found++ }
            }
            return found
        }

        // 2. Identical code, unique on both sides.
        fun exactRounds(final: Boolean): Int {
            var total = 0
            while (true) {
                refresh()
                val groupsOld = old.classes.filter { pairOld[it.index] < 0 }.groupBy { hashOld[it.index] }
                val groupsNew = new.classes.filter { pairNew[it.index] < 0 }.groupBy { hashNew[it.index] }
                var found = 0
                for ((h, os) in groupsOld) {
                    val ns = groupsNew[h] ?: continue
                    if (os.size == 1 && ns.size == 1) { assign(os[0], ns[0], How.IDENTICAL_CODE, 1.0); found++; continue }
                    val byOld = os.groupBy { incoming(old, usersOld, pairOld, ho, it) }
                    val byNew = ns.groupBy { incoming(new, usersNew, pairNew, hn, it) }
                    for ((k, so) in byOld) {
                        val sn = byNew[k] ?: continue
                        if (so.size == 1 && sn.size == 1) { assign(so[0], sn[0], How.IDENTICAL_CODE, 1.0); found++ }
                        else if (final) so.sortedBy { it.name }.zip(sn.sortedBy { it.name }).forEach { (a, b) -> assign(a, b, How.IDENTICAL_CODE_AMBIGUOUS, 1.0); found++ }
                    }
                }
                found += staticRound()
                total += found
                if (found == 0) return total
            }
        }

        // 3. Similar code: weighted multiset overlap, mutual best with a clear margin.
        fun similarRound(): Int {
            val os = old.classes.filter { pairOld[it.index] < 0 }
            val ns = new.classes.filter { pairNew[it.index] < 0 }
            if (os.isEmpty() || ns.isEmpty()) return 0
            val to = os.associateWith { ho.tokens(it) }
            val tn = ns.associateWith { hn.tokens(it) }
            val df = HashMap<Long, Int>()
            for (t in to.values + tn.values) { var prev = 0L; var first = true; for (x in t) { if (first || x != prev) df.merge(x, 1, Int::plus); prev = x; first = false } }
            val total = (os.size + ns.size).toDouble()
            fun w(t: Long) = ln(1.0 + total / (df[t] ?: 1))
            fun weight(t: LongArray) = t.sumOf { w(it) }
            val wo = to.mapValues { weight(it.value) }
            val wn = tn.mapValues { weight(it.value) }
            val index = HashMap<Long, MutableList<CodeIndex.Class>>()
            for ((c, t) in tn) for (x in t.distinct()) if ((df[x] ?: 0) <= 40) index.getOrPut(x) { ArrayList() } += c
            /** Weighted Dice coefficient between two sorted token multisets. */
            fun dice(a: LongArray, b: LongArray, wa: Double, wb: Double): Double {
                var i = 0; var j = 0; var overlap = 0.0
                while (i < a.size && j < b.size) when {
                    a[i] == b[j] -> { overlap += w(a[i]); i++; j++ }
                    a[i] < b[j] -> i++
                    else -> j++
                }
                return 2 * overlap / (wa + wb).coerceAtLeast(1e-9)
            }
            /** Whether two classes are the same kind (class/interface/enum/annotation) and have compatible supertypes. */
            fun compatible(a: CodeIndex.Class, b: CodeIndex.Class): Boolean {
                if ((a.flags and (AccessFlags.INTERFACE or AccessFlags.ENUM or AccessFlags.ANNOTATION)) != (b.flags and (AccessFlags.INTERFACE or AccessFlags.ENUM or AccessFlags.ANNOTATION))) return false
                val sa = if (a.superType < 0) 0 else idOld[a.superType]
                val sb = if (b.superType < 0) 0 else idNew[b.superType]
                return sa == UNKNOWN || sb == UNKNOWN || sa == sb
            }
            val scores = HashMap<CodeIndex.Class, MutableList<Pair<CodeIndex.Class, Double>>>()
            val reverse = HashMap<CodeIndex.Class, MutableList<Pair<CodeIndex.Class, Double>>>()
            for ((a, ta) in to) {
                if (wo.getValue(a) < MIN_WEIGHT) continue
                val counts = HashMap<CodeIndex.Class, Int>()
                for (x in ta.distinct()) index[x]?.forEach { counts.merge(it, 1, Int::plus) }
                val candidates = counts.entries.sortedByDescending { it.value }.take(40).map { it.key }
                for (b in candidates) {
                    if (wn.getValue(b) < MIN_WEIGHT || !compatible(a, b)) continue
                    val s = dice(ta, tn.getValue(b), wo.getValue(a), wn.getValue(b))
                    scores.getOrPut(a) { ArrayList() } += b to s
                    reverse.getOrPut(b) { ArrayList() } += a to s
                }
            }
            var found = 0
            for ((a, list) in scores) {
                val ranked = list.sortedByDescending { it.second }
                val (b, s) = ranked.first()
                if (s < MIN_SIMILARITY || s - (ranked.getOrNull(1)?.second ?: 0.0) < MARGIN) continue
                val back = reverse.getValue(b).sortedByDescending { it.second }
                if (back.first().first != a || s - (back.getOrNull(1)?.second ?: 0.0) < MARGIN) continue
                if (pairOld[a.index] >= 0 || pairNew[b.index] >= 0) continue
                assign(a, b, How.SIMILAR_CODE, s)
                found++
            }
            return found
        }

        var rounds = 0
        while (true) {
            rounds++
            exactRounds(final = false)
            if (similarRound() == 0 || rounds >= 20) break
        }
        exactRounds(final = true)
        return Result(
            old, new, pairs,
            old.classes.filter { pairOld[it.index] < 0 },
            new.classes.filter { pairNew[it.index] < 0 },
            staticPairs, rounds, idOld, idNew, midOld, midNew,
        )
    }

    private const val MIN_WEIGHT = 6.0
    private const val MIN_SIMILARITY = 0.5
    private const val MARGIN = 0.1

    // ---- method matching -------------------------------------------------------------------

    /** Methods of one class pair, matched; unchanged methods included. */
    fun methods(r: Result, p: ClassPair, layoutCheck: LayoutCheck? = null): List<MethodPair> {
        val raw = r.methodCache.getOrPut(p) { matchMethods(r, p) }
        if (layoutCheck == null) return raw
        return raw.map { mp ->
            if (mp.status == MethodStatus.MODIFIED && layoutCheck.sameBehaviour(mp.old!!, mp.new!!)) MethodPair(mp.old, mp.new, MethodStatus.SAME_BEHAVIOUR, mp.similarity) else mp
        }
    }

    /** Matches one class pair's methods in several passes: identical, moved, renamed, similar, then leftovers. */
    private fun matchMethods(r: Result, p: ClassPair): List<MethodPair> {
        val ho = Hasher(r.old, r.idOld, r.midOld)
        val hn = Hasher(r.new, r.idNew, r.midNew)
        val left = p.old.methods.toMutableList()
        val right = p.new.methods.toMutableList()
        val out = ArrayList<MethodPair>()
        /** Pairs [a] and [b], removing them from the candidate pools and refining a MODIFIED status to REFERENCES_ONLY where it applies. */
        fun take(a: CodeIndex.Method, b: CodeIndex.Method, status: MethodStatus, s: Double) {
            left.remove(a); right.remove(b)
            out += MethodPair(a, b, if (status == MethodStatus.MODIFIED && referencesOnly(a, b, ho, hn)) MethodStatus.REFERENCES_ONLY else status, s)
        }
        // Identical (name when kept, shape and body).
        val exactNew = right.groupBy { hn.method(it) }
        for ((h, os) in left.groupBy { ho.method(it) }) {
            val ns = exactNew[h] ?: continue
            os.zip(ns).forEach { (a, b) -> take(a, b, MethodStatus.UNCHANGED, 1.0) }
        }
        // Static methods paired elsewhere, or identical to a static method on the other side, were
        // moved by R8 to or from another class: moves, not edits.
        left.filter { it in r.pairedStatics || (it.isStatic && ho.method(it) in r.staticHashesNew) }.forEach { out += MethodPair(it, null, MethodStatus.MOVED, 1.0); left.remove(it) }
        right.filter { it in r.pairedStatics || (it.isStatic && hn.method(it) in r.staticHashesOld) }.forEach { out += MethodPair(null, it, MethodStatus.MOVED, 1.0); right.remove(it) }
        // Same kept name and shape: edited body.
        for (a in left.toList()) {
            if (!stableMember(a.name)) continue
            val b = right.singleOrNull { it.name == a.name && hn.shape(it) == ho.shape(a) } ?: continue
            take(a, b, MethodStatus.MODIFIED, similarity(ho.tokens(a), hn.tokens(b)))
        }
        // Same body and shape under another (obfuscated) name: renamed only.
        val bodiesNew = right.groupBy { combine(hn.anonymousShape(it), hn.body(it)) }
        for ((h, os) in left.groupBy { combine(ho.anonymousShape(it), ho.body(it)) }) {
            val ns = bodiesNew[h] ?: continue
            os.filter { it in left }.zip(ns.filter { it in right }).forEach { (a, b) -> take(a, b, MethodStatus.UNCHANGED, 1.0) }
        }
        // Same shape, similar body, each the other's best.
        val scored = left.flatMap { a ->
            right.filter { b -> hn.anonymousShape(b) == ho.anonymousShape(a) && (!stableMember(a.name) || !stableMember(b.name) || a.name == b.name) }
                .map { b -> Triple(a, b, similarity(ho.tokens(a), hn.tokens(b))) }
        }.sortedByDescending { it.third }
        for ((a, b, s) in scored) if (s >= 0.3 && a in left && b in right) take(a, b, MethodStatus.MODIFIED, s)
        // Same kept name, new signature.
        for (a in left.toList()) {
            if (!stableMember(a.name) || a.name.startsWith('<')) continue
            val b = right.singleOrNull { it.name == a.name } ?: continue
            take(a, b, MethodStatus.MODIFIED, similarity(ho.tokens(a), hn.tokens(b)))
        }
        left.forEach { out += MethodPair(it, null, MethodStatus.REMOVED, 0.0) }
        right.forEach { out += MethodPair(null, it, MethodStatus.ADDED, 0.0) }
        return out
    }

    /**
     * The same instructions, constants and calls, where only referenced classes differ, and each
     * old class always corresponds to the same new class (a consistent renaming, not an edit).
     */
    private fun referencesOnly(a: CodeIndex.Method, b: CodeIndex.Method, ho: Hasher, hn: Hasher): Boolean {
        if (a.insns.size != b.insns.size || a.params.size != b.params.size) return false
        val forward = HashMap<Long, Long>()
        val backward = HashMap<Long, Long>()
        fun same(x: Long, y: Long): Boolean = forward.getOrPut(x) { y } == y && backward.getOrPut(y) { x } == x
        for (k in a.insns.indices) {
            val x = a.insns[k]; val y = b.insns[k]
            if (x.base != y.base || x.refs.size != y.refs.size) return false
            if (ho.insn(x) == hn.insn(y)) continue
            val cx = x.callee.takeIf { it >= 0 }?.let { ho.mid[it] }
            val cy = y.callee.takeIf { it >= 0 }?.let { hn.mid[it] }
            if (cx != null && cy != null && cx != UNKNOWN_METHOD && cy != UNKNOWN_METHOD && cx != cy) return false
            for (j in x.refs.indices) if (!same(ho.id[x.refs[j]], hn.id[y.refs[j]])) return false
        }
        return forward.any { (x, y) -> x != y }
    }

    /** Dice overlap of two sorted token multisets (unweighted). */
    private fun similarity(a: LongArray, b: LongArray): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        var i = 0; var j = 0; var overlap = 0
        while (i < a.size && j < b.size) when {
            a[i] == b[j] -> { overlap++; i++; j++ }
            a[i] < b[j] -> i++
            else -> j++
        }
        return 2.0 * overlap / (a.size + b.size)
    }

    /** Fields a pair shares: by kept name, then by type in name order, as (old, new). */
    private fun fieldPairs(r: Result, p: ClassPair): List<Pair<CodeIndex.Field, CodeIndex.Field>> {
        val out = ArrayList<Pair<CodeIndex.Field, CodeIndex.Field>>()
        val right = p.new.fields.toMutableList()
        val left = ArrayList<CodeIndex.Field>()
        for (f in p.old.fields) {
            val g = right.firstOrNull { it.name == f.name && stableMember(f.name) && it.isStatic == f.isStatic }
            if (g != null) { out += f to g; right.remove(g) } else left += f
        }
        fun key(f: CodeIndex.Field, id: LongArray) = combine(id[f.type], if (f.isStatic) 1 else 0)
        val byOld = left.groupBy { key(it, r.idOld) }
        val byNew = right.groupBy { key(it, r.idNew) }
        val order = compareBy<CodeIndex.Field>({ it.name.length }, { it.name })
        for ((k, fs) in byOld) {
            val gs = byNew[k] ?: continue
            fs.sortedWith(order).zip(gs.sortedWith(order)).forEach { out += it }
        }
        return out
    }

    /**
     * Whether two methods differ only in code layout: both are renamed to common names (matched
     * classes and members get one neutral name; unmatched ones keep a side-specific name, so they
     * never compare equal) and compared with [DataflowShape].
     */
    class LayoutCheck(private val r: Result, private val budget: Int = 20_000) {
        private var used = 0
        private val classesOld = HashMap<String, String>()
        private val classesNew = HashMap<String, String>()
        private val membersOld = HashMap<String, String>()
        private val membersNew = HashMap<String, String>()
        private var ready = false

        /** Common names for every matched class and member, built on first use. */
        private fun prepare() {
            if (ready) return
            ready = true
            r.pairs.forEachIndexed { k, p ->
                classesOld[p.old.name] = "L~$k;"
                classesNew[p.new.name] = "L~$k;"
            }
            r.removed.forEach { classesOld[it.name] = "L~old/" + it.name.removePrefix("L") }
            r.added.forEach { classesNew[it.name] = "L~new/" + it.name.removePrefix("L") }
            val descriptor = Regex("""L[^;\s(),<>\[]+;""")
            fun neutral(desc: String, map: Map<String, String>): String = descriptor.replace(desc) { map[it.value] ?: it.value }
            r.pairs.forEachIndexed { k, p ->
                methods(r, p).filter { it.old != null && it.new != null }.forEachIndexed { j, mp ->
                    val a = mp.old!!; val b = mp.new!!
                    val name = if (a.name.startsWith('<')) a.name else "m$j"
                    membersOld[a.ref] = "L~$k;->$name" + neutral(a.proto, classesOld)
                    membersNew[b.ref] = "L~$k;->$name" + neutral(b.proto, classesNew)
                }
                fieldPairs(r, p).forEachIndexed { j, (f, g) ->
                    membersOld["${p.old.name}->${f.name}:"] = "L~$k;->f$j:"
                    membersNew["${p.new.name}->${g.name}:"] = "L~$k;->f$j:"
                }
            }
            // Static methods by their own pairing, wherever R8 put them.
            r.staticPairs.forEachIndexed { k, (a, b) ->
                membersOld[a.ref] = "L~static;->s$k" + neutral(a.proto, classesOld)
                membersNew[b.ref] = "L~static;->s$k" + neutral(b.proto, classesNew)
            }
        }

        /** Whether [a] and [b] have equivalent dataflow once both are renamed to common, obfuscation-independent names. */
        fun sameBehaviour(a: CodeIndex.Method, b: CodeIndex.Method): Boolean {
            if (used >= budget) return false
            used++
            prepare()
            val sa = shape(a.dexMethod)?.renamedMembers(membersOld)?.renamedClasses(classesOld) ?: return false
            val sb = shape(b.dexMethod)?.renamedMembers(membersNew)?.renamedClasses(classesNew) ?: return false
            return sa.compare(sb).match in setOf(RoundTripMatch.EQUIVALENT_DATAFLOW, RoundTripMatch.EQUIVALENT_MODULO_LAYOUT)
        }

        /** Decodes and computes the [DataflowShape] of a method, or null if it cannot be decoded. */
        private fun shape(m: DexMethod): DataflowShape? {
            val code = m.code ?: return null
            val decoded = (InstructionDecoder.decode(m.dex, code) as? Outcome.Ok)?.value ?: return null
            return runCatching { DataflowShape.of(m.dex, decoded) }.getOrNull()
        }
    }
}
