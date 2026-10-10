package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.*

/**
 * A deliberately narrow Moshi pattern: a registered adapter writes a model's String field through
 * the same writer operation as Moshi's String adapter. Names may all be obfuscated, and adapter
 * classes may contain R8-merged switch branches. No class name or application wire value is assumed.
 * Known singleton constructor values are examples, never a closed enum or a claim about the reader.
 */
internal class CustomScalarIndex(private val dex: List<Pair<String, DexFile>>) {
    private sealed interface V {
        data object Writer : V
        data object Input : V
        data object Unknown : V
        data object StringInput : V
        data class Cast(val type: String) : V
        data class Field(val owner: String, val name: String) : V
        data class Literal(val text: String) : V
        data class Number(val value: Long) : V
        data class Type(val desc: String) : V
        data class ObjectType(val desc: String, val site: Int = -1, val flags: Map<String, Long> = emptyMap()) : V
    }
    private data class Write(val method: String, val values: Set<V>, val ref: SourceRef)
    private val shapes: Map<String, ScalarWireShape> by lazy { recover() }
    private val constructorFlags: Map<String, List<String>> by lazy {
        dex.flatMap { (_, d) -> d.methods().mapNotNull { m ->
            val c = m.code ?: return@mapNotNull null
            if (m.name != "<init>" || m.proto.parameters.map { d.type(it) } != listOf("I")) return@mapNotNull null
            val insns = (InstructionDecoder.decode(d, c) as? Outcome.Ok)?.value?.insns ?: return@mapNotNull null
            if (c.tries.isNotEmpty() || insns.any { it.op.flow in setOf(FlowKind.IF, FlowKind.GOTO, FlowKind.SWITCH) }) return@mapNotNull null
            val fields = insns.filter { it.op.mnemonic == "iput" && it.regs[0] == c.registersSize - 1 && it.regs[1] == c.registersSize - 2 }
                .map { d.string(d.fieldIds[it.index].nameIdx) }
            d.methodDescriptor(m.encoded.methodIdx) to fields
        } }.toMap()
    }

    fun shape(signature: String?): ScalarWireShape? = shapes[signature]

    private fun recover(): Map<String, ScalarWireShape> {
        val bases = dex.flatMap { (_, d) -> d.classDefs.filter { d.type(it.classIdx).endsWith("JsonAdapter;") && it.superclassIdx != NO_INDEX }
            .map { d.type(it.superclassIdx) } }.toSet() - "Ljava/lang/Object;"
        if (bases.isEmpty()) return emptyMap()
        val writes = ArrayList<Pair<String, Write>>()
        val registrations = HashMap<Pair<String, V.ObjectType>, MutableList<SourceRef>>()
        val serializers = arrayListOf<Triple<String, DexFile, DexMethod>>()
        for ((name, d) in dex) for (m in d.methods()) {
            val code = m.code ?: continue
            val owner = d.type(m.classDef.classIdx)
            val params = m.proto.parameters.map { d.type(it) }
            val adapter = m.classDef.superclassIdx != NO_INDEX && d.type(m.classDef.superclassIdx) in bases
            val serializer = adapter && !m.isStatic && params.size == 2 && params[1] == "Ljava/lang/Object;" && d.type(m.proto.returnType) == "V"
            if (serializer) serializers += Triple(name, d, m)
            // Most methods cannot register an adapter; avoid decoding their bodies.
            val registration = code.insns.any { (it.toInt() and 0xff) == 0x1c } // const-class
            if (!serializer && !registration) continue
            val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value ?: continue
            val initial = if (serializer) mapOf(code.registersSize - code.insSize + 1 to setOf<V>(V.Writer),
                code.registersSize - code.insSize + 2 to setOf<V>(V.Input)) else emptyMap()
            flow(d, decoded, initial) { i, state ->
                if (!i.op.mnemonic.startsWith("invoke-") || i.regs.isEmpty()) return@flow
                val id = d.methodIds[i.index]
                val ps = d.protos[id.protoIdx].parameters.map { d.type(it) }
                val static = i.op.mnemonic.startsWith("invoke-static")
                val receiver = if (static) emptySet() else state[i.regs[0]].orEmpty()
                if (serializer && receiver == setOf(V.Writer) && ps == listOf("Ljava/lang/String;")) {
                    writes += owner to Write(d.methodDescriptor(i.index), state[i.regs[1]].orEmpty(), SourceRef("dex-custom-adapter", "$name:${d.methodDescriptor(m.encoded.methodIdx)}@${i.address}"))
                }
                if (!static && ps.size == 2 && ps[0] in setOf("Ljava/lang/Class;", "Ljava/lang/reflect/Type;") && ps[1] in bases) {
                    val type = state[i.regs[1]]?.singleOrNull() as? V.Type
                    val instance = state[i.regs[2]]?.singleOrNull() as? V.ObjectType
                    if (type != null && instance != null) registrations.getOrPut(type.desc to instance) { arrayListOf() }
                        .add(SourceRef("dex-adapter-registration", "$name:${d.methodDescriptor(m.encoded.methodIdx)}@${i.address}"))
                }
            }
        }
        // Discover writer.value(String) by its use for an actual String adapter, not its renamed name.
        val stringWriters = writes.filter { it.second.values == setOf(V.StringInput) }.map { it.second.method }.toSet()
        val recovered = arrayListOf<Pair<String, ScalarWireShape>>()
        val unsupported = hashSetOf<String>()
        for ((registered, refs) in registrations) {
            val (type, instance) = registered
            val usable = arrayListOf<Pair<V.Field, SourceRef>>()
            var unknownDiscriminator = false
            for ((name, d, m) in serializers.filter { it.third.owner == instance.desc }) {
                val c = m.code!!; val decoded = (InstructionDecoder.decode(d, c) as? Outcome.Ok)?.value ?: continue
                if (c.tries.isNotEmpty()) { unknownDiscriminator = true; continue }
                val base = c.registersSize - c.insSize
                flow(d, decoded, mapOf(base to setOf(instance), base + 1 to setOf(V.Writer), base + 2 to setOf(V.Input))) { i, state ->
                    if (i.op.mnemonic == "iget" && state[i.regs[1]] == setOf(instance) && d.string(d.fieldIds[i.index].nameIdx) !in instance.flags)
                        unknownDiscriminator = true
                    if (!i.op.mnemonic.startsWith("invoke-") || d.methodDescriptor(i.index) !in stringWriters || i.regs.size != 2 || state[i.regs[0]] != setOf(V.Writer)) return@flow
                    val vs = state[i.regs[1]].orEmpty()
                    if (vs.isNotEmpty() && vs.all { it is V.Field || it == V.Number(0) })
                        vs.filterIsInstance<V.Field>().filter { it.owner == type }.forEach {
                            usable += it to SourceRef("dex-custom-adapter", "$name:${d.methodDescriptor(m.encoded.methodIdx)}@${i.address}") }
                }
            }
            val field = usable.map { it.first }.distinct().singleOrNull()
            if (unknownDiscriminator || field == null) { unsupported += type; continue }
            val constants = singletonValues(field)
            recovered += type to ScalarWireShape("string", constants.first, (usable.map { it.second } + refs + constants.second).distinct())
        }
        // Competing registrations with different supported shapes must agree; never take the last one.
        return recovered.groupBy { it.first }.mapNotNull { (type, found) ->
            if (type in unsupported) return@mapNotNull null
            val unique = found.map { it.second.copy(evidence = emptyList()) }.distinct().singleOrNull() ?: return@mapNotNull null
            type to unique.copy(evidence = found.flatMap { it.second.evidence }.distinct())
        }.toMap()
    }

    /** Follow register provenance over all branches. Missing/unknown values never become evidence. */
    private fun flow(d: DexFile, code: DecodedCode, initial: Map<Int, Set<V>>, visit: (Insn, Map<Int, Set<V>>) -> Unit) {
        val insns = code.insns
        if (insns.isEmpty()) return
        val at = insns.withIndex().associate { it.value.address to it.index }
        val entry = HashMap<Int, Map<Int, Set<V>>>()
        entry[0] = initial
        val work = ArrayDeque(listOf(0))
        var steps = 0
        while (work.isNotEmpty() && steps++ < 100_000) {
            val k = work.removeFirst(); val i = insns[k]; val before = entry.getValue(k)
            val out = before.toMutableMap(); val op = i.op.mnemonic
            when {
                op == "const-class" -> out[i.regs[0]] = setOf(V.Type(d.type(i.index)))
                op == "new-instance" -> out[i.regs[0]] = setOf(V.ObjectType(d.type(i.index), i.address))
                op.startsWith("const-string") -> out[i.regs[0]] = setOf(V.Literal(d.string(i.index)))
                op == "const" || op.startsWith("const/") -> out[i.regs[0]] = setOf(V.Number(i.literal))
                op.startsWith("move-object") -> out[i.regs[0]] = before[i.regs[1]] ?: setOf(V.Unknown)
                op == "check-cast" -> if (before[i.regs[0]] == setOf(V.Input)) out[i.regs[0]] =
                    setOf(if (d.type(i.index) == "Ljava/lang/String;") V.StringInput else V.Cast(d.type(i.index)))
                op == "iget-object" -> {
                    val f = d.fieldIds[i.index]; val owner = d.type(f.classIdx)
                    out[i.regs[0]] = if (before[i.regs[1]] == setOf(V.Cast(owner)) && d.type(f.typeIdx) == "Ljava/lang/String;")
                        setOf(V.Field(owner, d.string(f.nameIdx))) else setOf(V.Unknown)
                }
                op == "iget" -> {
                    val obj = before[i.regs[1]]?.singleOrNull() as? V.ObjectType
                    out[i.regs[0]] = obj?.flags?.get(d.string(d.fieldIds[i.index].nameIdx))?.let { setOf(V.Number(it)) } ?: setOf(V.Unknown)
                }
                op.startsWith("invoke-direct") && i.regs.size == 2 -> {
                    val fields = constructorFlags[d.methodDescriptor(i.index)]
                    val obj = before[i.regs[0]]?.singleOrNull() as? V.ObjectType
                    val arg = before[i.regs[1]]?.singleOrNull() as? V.Number
                    if (fields != null && obj != null && arg != null) {
                        val initialized = obj.copy(flags = fields.associateWith { arg.value })
                        for ((r, vs) in before) if (obj in vs) out[r] = vs - obj + initialized
                    }
                }
                i.regs.isNotEmpty() && !(op.startsWith("invoke") || op.startsWith("if-") || op.startsWith("goto") ||
                    op.startsWith("return") || op.startsWith("throw") || op.startsWith("iput") || op.startsWith("sput") ||
                    op.startsWith("aput") || op.startsWith("monitor") || op.endsWith("switch") || op.startsWith("fill-array")) -> out[i.regs[0]] = setOf(V.Unknown)
            }
            val successors = when (i.op.flow) {
                FlowKind.RETURN, FlowKind.THROW -> emptyList()
                FlowKind.GOTO -> listOfNotNull(at[i.target])
                FlowKind.IF -> {
                    val n = before[i.regs[0]]?.singleOrNull() as? V.Number
                    val test = n?.let { when (op) { "if-eqz" -> it.value == 0L; "if-nez" -> it.value != 0L; else -> null } }
                    if (test == null) listOfNotNull(at[i.next], at[i.target]) else listOfNotNull(at[if (test) i.target else i.next])
                }
                FlowKind.SWITCH -> {
                    val n = before[i.regs[0]]?.singleOrNull() as? V.Number
                    if (n == null) listOfNotNull(at[i.next]) + code.switchCases(i).mapNotNull { at[it.second] }
                    else listOfNotNull(at[code.switchCases(i).firstOrNull { it.first.toLong() == n.value }?.second ?: i.next])
                }
                else -> listOfNotNull(at[i.next])
            }
            for (n in successors) {
                val old = entry[n]
                val merged = if (old == null) out.toMap() else (old.keys + out.keys).associateWith {
                    (old[it] ?: setOf(V.Unknown)) + (out[it] ?: setOf(V.Unknown)) }
                if (merged != old) { entry[n] = merged; work.addLast(n) }
            }
        }
        // Collect facts only after reaching a fixed point; an exhausted budget yields no evidence.
        if (work.isEmpty()) for (k in entry.keys.sorted()) visit(insns[k], entry.getValue(k))
    }

    private fun singletonValues(field: V.Field): Pair<List<String>, List<SourceRef>> {
        val constructors = HashSet<String>()
        for ((_, d) in dex) for (m in d.methods()) {
            if (m.name != "<init>" || m.code == null || m.proto.parameters.map { d.type(it) } != listOf("Ljava/lang/String;")) continue
            val decoded = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value ?: continue
            val param = m.code!!.registersSize - 1
            if (decoded.insns.any { it.op.mnemonic == "iput-object" && it.regs[0] == param &&
                d.type(d.fieldIds[it.index].classIdx) == field.owner && d.string(d.fieldIds[it.index].nameIdx) == field.name }) constructors += d.methodDescriptor(m.encoded.methodIdx)
        }
        val values = linkedSetOf<String>(); val refs = arrayListOf<SourceRef>()
        for ((name, d) in dex) for (m in d.methods()) {
            if (m.name != "<clinit>" || m.code == null) continue
            val decoded = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value ?: continue
            if (decoded.insns.any { it.op.flow in setOf(FlowKind.IF, FlowKind.GOTO, FlowKind.SWITCH) }) continue
            val initialized = HashMap<Int, String>()
            flow(d, decoded, emptyMap()) { i, state ->
                val op = i.op.mnemonic
                if (op.startsWith("invoke-direct") && d.methodDescriptor(i.index) in constructors && i.regs.size == 2) {
                    val value = (state[i.regs[1]]?.singleOrNull() as? V.Literal)?.text
                    if (state[i.regs[0]]?.singleOrNull() is V.ObjectType && value != null) initialized[i.regs[0]] = value
                } else if (op == "sput-object") {
                    initialized.remove(i.regs[0])?.let { values += it; refs += SourceRef("dex-wire-singleton", "$name:${d.methodDescriptor(m.encoded.methodIdx)}@${i.address}") }
                } else if (op == "new-instance") initialized.remove(i.regs[0])
            }
        }
        return values.toList() to refs
    }
}
