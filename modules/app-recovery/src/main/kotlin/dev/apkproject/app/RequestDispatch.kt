package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.*

/**
 * A deliberately small proof domain: final String fields copied into a request constructor,
 * then tested for null/empty before one of several Retrofit declarations is called. All call
 * sites must be accounted for. Unsupported operations, mutation, loops and budgets abstain.
 * This records client selection among declarations, never original server business logic.
 */
internal object RequestDispatch {
    private sealed interface V {
        data class Param(val register: Int) : V
        data class Field(val base: V, val descriptor: String, val depth: Int) : V
        data class Object(val address: Int, val type: String) : V
        data class Num(val value: Long) : V
        data class Text(val value: String) : V
        data class Length(val field: V) : V
        data class Empty(val field: V) : V
        data object Body : V
        data object Nonzero : V
        data class StringState(val state: String) : V
    }
    private data class State(val regs: Map<Int, V>, val result: V? = null)
    private data class Call(val endpoint: Int, val bodyArgument: Int)
    private data class Site(val method: String, val address: Int)
    private data class Frame(val index: Int, val state: State, val path: Set<Int>)
    private val stringStates = listOf("null", "empty", "nonempty")

    fun recover(dex: List<Pair<String, DexFile>>, endpoints: List<Endpoint>, models: List<ModelSchema>): List<Endpoint> {
        val out = endpoints.toMutableList()
        val groups = endpoints.withIndex().groupBy { (_, e) -> Triple(e.method, e.path, e.bodyType) }.values
            .filter { group -> group.size == 2 && group.map { it.value.responseType }.distinct().size == 2 && group.first().value.bodyType != null }
        if (groups.isEmpty()) return out
        val finalFields = dex.flatMap { (_, d) -> d.classDefs.flatMap { c ->
            (c.classData?.instanceFields.orEmpty()).filter { it.accessFlags and AccessFlags.FINAL != 0 }
                .map { d.fieldDescriptor(it.fieldIdx) }
        } }.toSet()
        val methods = dex.flatMap { (name, d) -> d.methods().map { Triple(name, d, it) } }
        val uniqueMethods = methods.groupBy { (_, _, m) -> "${m.owner}->${m.name}${m.protoDescriptor}" }
            .filterValues { it.size == 1 }.mapValues { it.value.single().third }
        val roles = BytecodeApi.roles(dex)
        val apiCalls = methods.mapNotNull { (_, d, m) ->
            val annotations = runCatching { d.annotations(m.classDef).methods[m.encoded.methodIdx] }.getOrNull().orEmpty()
            if (annotations.any { it.type in roles.verbs || it.type in roles.generic }) "${m.owner}->${m.name}${m.protoDescriptor}" else null
        }.toSet()
        for (group in groups) {
            val bodyName = group.first().value.bodyType ?: continue
            val model = models.singleOrNull { it.className == bodyName } ?: continue
            val fields = model.fields.filter { it.javaType in setOf("String", "java.lang.String") && it.wire == null }
                .mapNotNull { f -> f.evidence.singleOrNull { it.kind == "dex-model-field" }?.location
                    ?.substringAfter(':')?.takeIf { it in finalFields }?.let { it to f.jsonName } }.toMap()
            if (fields.isEmpty()) continue
            val calls = HashMap<String, Call>()
            for ((k, e) in group) for ((_, d, m) in methods) {
                if (Signatures.javaType(m.owner) != e.declaredIn || m.name != e.operation) continue
                val descriptor = "${m.owner}->${m.name}${m.protoDescriptor}"
                if (e.evidence.none { it.kind == "dex-annotation" && it.location.substringAfter(':') == descriptor }) continue
                val anns = runCatching { d.annotations(m.classDef).parameters[m.encoded.methodIdx] }.getOrNull() ?: continue
                val body = anns.indexOfFirst { set -> set.any { roles.params[it.type] == "Body" } }
                if (body < 0) continue
                var offset = if (m.isStatic) 0 else 1
                for (p in m.proto.parameters.take(body)) offset += if (d.type(p) in setOf("J", "D")) 2 else 1
                calls[descriptor] = Call(k, offset)
            }
            if (calls.values.map { it.endpoint }.toSet() != group.map { it.index }.toSet()) continue
            val sites = linkedSetOf<Site>()
            val proposals = arrayListOf<Map<Int, ClientSelection>>()
            val covered = linkedSetOf<Site>()
            var undecodableCandidate = false
            // A method reference index is an actual code unit in every supported invoke format.
            // This cheap prefilter can only add false positives; decoded instructions still
            // decide which sites count. Avoid decoding unrelated application/library methods.
            val references = dex.associate { (_, d) -> d to d.methodIds.indices.filter { d.methodDescriptor(it) in calls }.toSet() }
            for ((name, d, m) in methods) {
                val code = m.code ?: continue
                val referenced = references.getValue(d)
                if (referenced.isEmpty() || code.insns.none { it in referenced }) continue
                val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value
                if (decoded == null) {
                    // The raw reference may be a call. Without decoding it, complete caller
                    // coverage cannot be established; conservative prefilter hits abstain.
                    undecodableCandidate = true
                    continue
                }
                val key = "${m.owner}->${m.name}${m.protoDescriptor}"
                val localSites = decoded.insns.filter { it.op.index == IndexKind.METHOD && d.methodDescriptor(it.index) in calls }
                if (localSites.isEmpty()) continue
                sites += localSites.map { Site(key, it.address) }
                if (decoded.insns.size > 8000) continue
                val incoming = mustStates(d, m, decoded, finalFields) ?: continue
                for ((idx, i) in decoded.insns.withIndex()) {
                    if (!i.op.mnemonic.startsWith("invoke-direct") || i.op.index != IndexKind.METHOD) continue
                    val ctor = uniqueMethods[d.methodDescriptor(i.index)] ?: continue
                    if (ctor.name != "<init>" || fields.keys.none { it.startsWith("${ctor.owner}->") }) continue
                    val state = incoming[idx] ?: continue
                    val body = state.regs[i.regs.firstOrNull()] as? V.Object ?: continue
                    if (body.type != ctor.owner) continue
                    val bindings = constructorFields(ctor) ?: continue
                    for ((field, jsonName) in fields) {
                        val slot = bindings[field] ?: continue
                        val source = state.regs[i.regs.getOrElse(slot) { -1 }] as? V.Field ?: continue
                        if (!source.descriptor.endsWith(":Ljava/lang/String;")) continue
                        val start = state.regs.mapValues { (_, v) -> if (v == body) V.Body else v }
                        val results = stringStates.map { domain -> simulate(d, decoded, idx + 1, State(start), source, field, domain, calls, finalFields, apiCalls) }
                        if (results.any { it == null }) continue
                        val chosen = results.map { it!!.map { s -> calls.getValue(d.methodDescriptor(decoded.byAddress.getValue(s.address).index)).endpoint }.toSet() }
                        if (chosen.any { it.size != 1 } || chosen.flatten().toSet() != group.map { it.index }.toSet()) continue
                        val refs = listOf(SourceRef("dex-client-dispatch", "$name:$key@${i.address.toString(16)}; $field; ${source.descriptor}"))
                        proposals += group.associate { entry -> entry.index to ClientSelection(jsonName,
                            stringStates.filterIndexed { k, _ -> entry.index in chosen[k] }, refs) }
                        covered += results.flatMap { it!! }.map { Site(key, it.address) }
                    }
                }
            }
            if (undecodableCandidate || sites.isEmpty() || sites != covered || proposals.isEmpty()) continue
            // Independent callers must prove exactly the same partition of the String domain.
            if (group.any { (k, _) -> proposals.map { it.getValue(k).let { p -> p.jsonName to p.stringStates } }.distinct().size != 1 }) continue
            for ((k, _) in group) out[k] = out[k].copy(clientSelection = proposals.first().getValue(k).copy(
                evidence = proposals.flatMap { it.getValue(k).evidence }.distinct()))
        }
        return out
    }

    /** Straight-line constructor assignments, with no escaping `this`, delegating ctor or handlers. */
    private fun constructorFields(m: DexMethod): Map<String, Int>? {
        val code = m.code ?: return null
        if (code.tries.isNotEmpty()) return null
        val insns = (InstructionDecoder.decode(m.dex, code) as? Outcome.Ok)?.value?.insns ?: return null
        if (insns.any { it.op.flow in setOf(FlowKind.IF, FlowKind.GOTO, FlowKind.SWITCH) }) return null
        val base = code.registersSize - code.insSize
        val slots = (0 until code.insSize).associate { base + it to it }.toMutableMap()
        val fields = linkedMapOf<String, Int>()
        for (i in insns) {
            val op = i.op.mnemonic
            when {
                op.startsWith("move") && !op.startsWith("move-result") -> slots[i.regs[1]]?.let { slots[i.regs[0]] = it } ?: slots.remove(i.regs[0])
                op == "iput-object" -> {
                    val field = m.dex.fieldDescriptor(i.index)
                    if (slots[i.regs[1]] != 0 || !field.startsWith("${m.owner}->") || field in fields) return null
                    fields[field] = slots[i.regs[0]] ?: return null
                }
                op.startsWith("invoke-") -> {
                    val desc = m.dex.methodDescriptor(i.index)
                    if (i.regs.any { slots[it] == 0 } && desc != "Ljava/lang/Object;-><init>()V") return null
                }
                op == "check-cast" || !writesFirst(op) -> Unit
                i.regs.isNotEmpty() -> slots.remove(i.regs[0])
            }
        }
        return fields
    }

    private fun initial(m: DexMethod): State {
        val c = m.code!!
        return State((c.registersSize - c.insSize until c.registersSize).associateWith { V.Param(it) })
    }

    /** Only equal values survive a CFG join. Exception edges drop all symbolic facts. */
    private fun mustStates(d: DexFile, m: DexMethod, dc: DecodedCode, finals: Set<String>): Array<State?>? {
        val states = arrayOfNulls<State>(dc.insns.size)
        if (states.isEmpty()) return states
        val indexes = dc.insns.withIndex().associate { it.value.address to it.index }
        states[0] = initial(m)
        val queue = ArrayDeque(listOf(0))
        var steps = 0
        fun merge(k: Int, next: State) {
            if (k !in states.indices) return
            val old = states[k]
            val joined = if (old == null) next else State(old.regs.filter { (r, v) -> next.regs[r] == v }, old.result.takeIf { it == next.result })
            if (old != joined) { states[k] = joined; queue += k }
        }
        while (queue.isNotEmpty()) {
            if (++steps > 100_000) return null
            val k = queue.removeFirst()
            val i = dc.insns[k]
            val next = transfer(d, i, states[k]!!, finals)
            successors(dc, k, indexes).forEach { merge(it, next) }
            for (t in dc.code.tries) if (i.address in t.startAddr until t.startAddr + t.insnCount) {
                val h = dc.code.handlerFor(t)
                (h.typed.map { it.second } + listOf(h.catchAllAddr).filter { it >= 0 }).mapNotNull { indexes[it] }.forEach { merge(it, State(emptyMap())) }
            }
        }
        return states
    }

    private fun simulate(d: DexFile, dc: DecodedCode, start: Int, initial: State, source: V.Field, bodyField: String,
                         domain: String, calls: Map<String, Call>, finals: Set<String>, apiCalls: Set<String>): Set<Site>? {
        val indexes = dc.insns.withIndex().associate { it.value.address to it.index }
        val queue = ArrayDeque(listOf(Frame(start, initial, emptySet())))
        val seen = HashSet<Pair<Int, State>>()
        val found = linkedSetOf<Site>()
        var steps = 0
        fun concrete(v: V?): V? = when {
            v == source -> if (domain == "null") V.Num(0) else V.StringState(domain)
            v is V.Length && v.field == source -> if (domain == "empty") V.Num(0) else V.Nonzero
            v is V.Empty && v.field == source -> V.Num(if (domain == "empty") 1 else 0)
            else -> v
        }
        while (queue.isNotEmpty()) {
            if (++steps > 10_000 || seen.size > 4000) return null
            val (k, state, path) = queue.removeFirst()
            if (k in path) return null
            if (k !in dc.insns.indices || !seen.add(k to state)) continue
            val i = dc.insns[k]
            if (dc.code.tries.any { i.address in it.startAddr until it.startAddr + it.insnCount }) return null
            if (i.op.flow == FlowKind.SWITCH) return null
            val op = i.op.mnemonic
            val call = if (i.op.index == IndexKind.METHOD) calls[d.methodDescriptor(i.index)] else null
            if (call != null) {
                if (state.regs[i.regs.getOrElse(call.bodyArgument) { -1 }] != V.Body) return null
                found += Site("", i.address)
                continue
            }
            // A different declared request (e.g. editing an existing status) is outside this
            // route's selection domain. Stop before the request can escape or be modified.
            if (i.op.index == IndexKind.METHOD && d.methodDescriptor(i.index) in apiCalls) continue
            if (op.startsWith("iput") && state.regs[i.regs[1]] == V.Body) return null
            if (op.startsWith("iput") && d.fieldDescriptor(i.index) == source.descriptor) return null
            if (op.startsWith("invoke-") && i.regs.any { state.regs[it] == V.Body }) return null
            val symbolic = transfer(d, i, state, finals, bodyField, source)
            // Materialize only the decision value; final-field provenance remains in the registers.
            val next = symbolic.copy(result = concrete(symbolic.result))
            val successors = if (i.op.flow == FlowKind.IF) {
                val a = concrete(state.regs[i.regs[0]])
                val b = if (i.regs.size == 1) V.Num(0) else concrete(state.regs[i.regs[1]])
                val decision = compare(op, a, b)
                if (decision == null) successors(dc, k, indexes) else listOfNotNull(if (decision) indexes[i.target] else indexes[i.next])
            } else successors(dc, k, indexes)
            successors.forEach { queue += Frame(it, next, path + k) }
        }
        return found
    }

    private fun compare(op: String, a: V?, b: V?): Boolean? {
        if (a is V.Num && b is V.Num) return when (op.removeSuffix("z")) {
            "if-eq" -> a.value == b.value; "if-ne" -> a.value != b.value
            "if-lt" -> a.value < b.value; "if-le" -> a.value <= b.value
            "if-gt" -> a.value > b.value; "if-ge" -> a.value >= b.value; else -> null
        }
        if (b == V.Num(0) && (a == V.Nonzero || a is V.StringState || a == V.Body || a is V.Object)) return when (op) {
            "if-eqz", "if-eq" -> false; "if-nez", "if-ne" -> true; else -> null
        }
        return null
    }

    private fun transfer(d: DexFile, i: Insn, state: State, finals: Set<String>, bodyField: String? = null, source: V? = null): State {
        val regs = state.regs.toMutableMap()
        val op = i.op.mnemonic
        var result: V? = null
        var value: V? = null
        when {
            op.startsWith("move-result") -> value = state.result
            op.startsWith("move") && op != "move-exception" -> value = regs[i.regs[1]]
            op == "const" || op.startsWith("const/") || op.startsWith("const-wide") -> value = V.Num(i.literal)
            op.startsWith("const-string") -> value = V.Text(d.string(i.index))
            op == "new-instance" -> value = V.Object(i.address, d.type(i.index))
            op == "iget-object" -> {
                val field = d.fieldDescriptor(i.index)
                val base = regs[i.regs[1]]
                if (base == V.Body && field == bodyField) value = source
                else if (field in finals && base != null && base != V.Body) {
                    val depth = ((base as? V.Field)?.depth ?: 0) + 1
                    if (depth <= 4) value = V.Field(base, field, depth)
                }
            }
            op.startsWith("invoke-") && i.op.index == IndexKind.METHOD -> {
                val descriptor = d.methodDescriptor(i.index)
                val receiver = regs[i.regs.firstOrNull()]
                result = when (descriptor) {
                    "Ljava/lang/String;->length()I", "Ljava/lang/CharSequence;->length()I" -> receiver?.let { V.Length(it) }
                    "Ljava/lang/String;->isEmpty()Z" -> receiver?.let { V.Empty(it) }
                    else -> null
                }
            }
            op == "xor-int/lit8" || op == "xor-int/lit16" -> (regs[i.regs[1]] as? V.Num)?.let { value = V.Num(it.value xor i.literal) }
            op == "check-cast" -> return State(regs)
        }
        if (writesFirst(op) && i.regs.isNotEmpty()) {
            if (value == null) regs.remove(i.regs[0]) else regs[i.regs[0]] = value
            if ("wide" in op || (("long" in op || "double" in op) && !op.startsWith("cmp"))) regs.remove(i.regs[0] + 1)
        }
        return State(regs, result)
    }

    private fun successors(dc: DecodedCode, k: Int, indexes: Map<Int, Int>): List<Int> {
        val i = dc.insns[k]
        return when (i.op.flow) {
            FlowKind.GOTO -> listOfNotNull(indexes[i.target])
            FlowKind.IF -> listOfNotNull(indexes[i.target], indexes[i.next])
            FlowKind.SWITCH -> (dc.switchCases(i).mapNotNull { indexes[it.second] } + listOfNotNull(indexes[i.next])).distinct()
            FlowKind.RETURN, FlowKind.THROW -> emptyList()
            else -> listOfNotNull(indexes[i.next])
        }
    }
    private fun writesFirst(op: String) = !(op.startsWith("if-") || op.startsWith("invoke-") || op.startsWith("return") || op == "throw" ||
        op.startsWith("iput") || op.startsWith("sput") || op.startsWith("aput") || op.startsWith("goto") || op.startsWith("monitor") ||
        op.startsWith("fill-array") || op.endsWith("switch") || op == "check-cast" || op == "nop")
}
