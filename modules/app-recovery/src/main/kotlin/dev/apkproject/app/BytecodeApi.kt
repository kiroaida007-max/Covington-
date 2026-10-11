package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.ClassDef
import dev.apkproject.dex.DexAnnotation
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.dex.NO_INDEX

/**
 * Retrofit endpoints read from the bytecode itself, independent of any decompiler, and robust to
 * obfuscation. R8 renames Retrofit's annotation types (`@GET` may become `@x0.b`), so each type is
 * identified by what Retrofit's own request parser does with it: `parseMethodAnnotation` tests
 * `annotation instanceof GET` and then passes the string "GET"; `parseParameterAnnotation` tests
 * `instanceof Path` and then reports errors that name "@Path". Unobfuscated names are used as is.
 */
object BytecodeApi {
    private val VERBS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
    private val PARAM_KINDS = setOf("Url", "Path", "Query", "QueryName", "QueryMap", "Header", "HeaderMap", "Field", "FieldMap", "Part", "PartMap", "Body", "Tag")

    /** Retrofit's `parseMethodAnnotation` and `parseParameterAnnotation` test annotation types in this order. */
    private val METHOD_ORDER = listOf("DELETE", "GET", "HEAD", "PATCH", "POST", "PUT", "OPTIONS", "HTTP", "Headers", "Multipart", "FormUrlEncoded")
    private val PARAM_ORDER = listOf("Url", "Path", "Query", "QueryName", "QueryMap", "Header", "HeaderMap", "Field", "FieldMap", "Part", "PartMap", "Body", "Tag")

    /** Strings that occur only in one branch of Retrofit's parser (error messages). */
    private val ANCHORS = listOf(
        "Multiple @Url method annotations found." to "Url", "@Url must be okhttp3.HttpUrl" to "Url",
        "@Path can only be used with relative url" to "Path", "@Path parameter name must match" to "Path",
        "@QueryMap parameter type must be Map." to "QueryMap", "@QueryMap keys must be of type String" to "QueryMap",
        "@HeaderMap parameter type must be Map" to "HeaderMap", "@HeaderMap keys must be of type String" to "HeaderMap",
        "@Field parameters can only be used with form encoding." to "Field",
        "@FieldMap parameter type must be Map." to "FieldMap", "@FieldMap keys must be of type String" to "FieldMap",
        "@Part annotation must supply a name" to "Part", "@Part parameters can only be used with multipart encoding." to "Part",
        "@PartMap parameter type must be Map." to "PartMap", "@PartMap values cannot be MultipartBody.Part" to "PartMap",
        "Unable to create @Body converter" to "Body", "Multiple @Body method annotations found." to "Body",
        "@Tag type " to "Tag",
        "@Headers annotation is empty." to "Headers", "@Headers value must be in the form" to "Headers",
        "Only one encoding annotation is allowed." to "Multipart",
        "FormUrlEncoded can only be specified on HTTP methods with request body" to "FormUrlEncoded",
    )

    data class Roles(val verbs: Map<String, String>, val params: Map<String, String>, val generic: Set<String>)

    /**
     * Annotation type descriptor -> HTTP verb / parameter kind. Unobfuscated names are used as is.
     * Otherwise Retrofit's parser is located (a method testing many annotation types) and its
     * sequence of `instanceof` types is aligned with Retrofit's fixed order, anchored by branches
     * whose strings are unique ("GET", "Multiple @Url method annotations found.", ...). Types
     * between two anchors are labelled by position only when the anchors agree on the spacing.
     */
    fun roles(dex: List<Pair<String, DexFile>>): Roles {
        val labels = HashMap<String, String>()
        for ((_, d) in dex) for (t in d.typeIds.indices) {
            val name = d.type(t)
            if (name.startsWith("Lretrofit2/http/")) labels[name] = name.removePrefix("Lretrofit2/http/").removeSuffix(";")
        }
        // Only annotation interfaces take part: the parser also tests its own handler classes.
        val annotationTypes = dex.flatMap { (_, d) -> d.classDefs.filter { it.accessFlags and AccessFlags.ANNOTATION != 0 }.map { d.type(it.classIdx) } }.toHashSet()
        for ((_, d) in dex) for (m in d.methods()) {
            val code = m.code ?: continue
            if (code.insns.size > 8000) continue
            val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value ?: continue
            val insns = decoded.insns
            val tests = insns.withIndex().filter { (_, i) -> i.op.mnemonic == "instance-of" && d.type(i.index) in annotationTypes }
            if (tests.size < 4) continue
            val sequence = tests.map { d.type(it.value.index) }.distinct()
            // Strings reachable from each test's true branch (compilers move throw blocks to the end of
            // a method, so "the strings after the test" is not enough). A string reachable from several
            // tests anchors none of them.
            val at = insns.withIndex().associate { it.value.address to it.index }
            fun successors(k: Int): List<Int> {
                val i = insns[k]
                val out = ArrayList<Int>()
                when (i.op.flow) {
                    dev.apkproject.dex.FlowKind.GOTO -> at[i.target]?.let { out += it }
                    dev.apkproject.dex.FlowKind.IF -> { at[i.target]?.let { out += it }; out += k + 1 }
                    dev.apkproject.dex.FlowKind.SWITCH -> { decoded.switchCases(i).forEach { (_, t) -> at[t]?.let { out += it } }; out += k + 1 }
                    dev.apkproject.dex.FlowKind.RETURN, dev.apkproject.dex.FlowKind.THROW -> Unit
                    else -> out += k + 1
                }
                return out.filter { it < insns.size }
            }
            val reach = HashMap<String, HashSet<String>>()
            for ((n, test) in tests.withIndex()) {
                val type = d.type(test.value.index)
                val result = test.value.regs[0]
                val branch = (test.index + 1..minOf(test.index + 3, insns.size - 1)).firstOrNull { insns[it].op.flow == dev.apkproject.dex.FlowKind.IF && insns[it].regs.firstOrNull() == result } ?: continue
                val cond = insns[branch]
                val target = at[cond.target] ?: continue
                val (entry, stop) = when (cond.op.mnemonic) {
                    "if-eqz" -> branch + 1 to target
                    "if-nez" -> target to branch + 1
                    else -> continue
                }
                val nextTest = tests.getOrNull(n + 1)?.index
                val seen = HashSet<Int>()
                val work = ArrayDeque(listOf(entry))
                val strings = HashSet<String>()
                while (work.isNotEmpty() && seen.size < 4000) {
                    val k = work.removeFirst()
                    if (k == stop || k == nextTest || !seen.add(k)) continue
                    val i = insns[k]
                    if (i.op.mnemonic.startsWith("const-string")) strings += d.string(i.index)
                    if (i.op.mnemonic == "instance-of" && k != test.index && d.type(i.index) in annotationTypes) continue
                    successors(k).forEach { work.addLast(it) }
                }
                reach.getOrPut(type) { HashSet() } += strings
            }
            val owners = HashMap<String, MutableSet<String>>()
            reach.forEach { (type, strings) -> strings.forEach { owners.getOrPut(it) { HashSet() } += type } }
            val anchors = HashMap<Int, String>()
            for ((type, strings) in reach) {
                val pos = sequence.indexOf(type)
                val unique = strings.filter { owners.getValue(it).size == 1 }
                unique.firstOrNull { it in VERBS }?.let { anchors.putIfAbsent(pos, it) }
                for ((needle, label) in ANCHORS) if (unique.any { it.startsWith(needle) }) anchors.putIfAbsent(pos, label)
            }
            for (order in listOf(METHOD_ORDER, PARAM_ORDER)) {
                val points = anchors.entries.filter { it.value in order }.map { it.key to order.indexOf(it.value) }.sortedBy { it.first }
                if (points.size < 2) continue
                for ((k, p) in points.withIndex()) {
                    labels.putIfAbsent(sequence[p.first], order[p.second])
                    val next = points.getOrNull(k + 1) ?: continue
                    if (next.first - p.first == next.second - p.second) {
                        for (step in 1 until next.first - p.first) labels.putIfAbsent(sequence[p.first + step], order[p.second + step])
                    }
                }
                // Before the first and after the last anchor, extend while both lists last, if the anchors agree on one offset.
                val offsets = points.map { it.first - it.second }.distinct()
                if (offsets.size == 1) {
                    val off = offsets.single()
                    for (j in order.indices) sequence.getOrNull(j + off)?.let { labels.putIfAbsent(it, order[j]) }
                }
            }
        }
        val verbs = labels.filterValues { it in VERBS }
        val params = labels.filterValues { it in PARAM_KINDS }
        val generic = labels.filterValues { it == "HTTP" }.keys
        return Roles(verbs, params, generic)
    }

    /** kotlin.Unit under an R8 name: the class whose toString() returns "kotlin.Unit". */
    private fun obfuscatedUnit(dex: List<Pair<String, DexFile>>): String? {
        for ((_, d) in dex) {
            val idx = d.strings.indexOf("kotlin.Unit").takeIf { it >= 0 } ?: continue
            for (m in d.methods()) {
                if (m.name != "toString" || m.code == null) continue
                val insns = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value?.insns ?: continue
                if (insns.size <= 3 && insns.any { it.op.mnemonic.startsWith("const-string") && it.index == idx }) return m.owner
            }
        }
        return null
    }

    /** Endpoints declared by annotated interface methods. */
    fun endpoints(dex: List<Pair<String, DexFile>>, roles: Roles = roles(dex)): List<Endpoint> {
        if (roles.verbs.isEmpty() && roles.generic.isEmpty()) return emptyList()
        val unit = obfuscatedUnit(dex)?.let { Signatures.javaType(it) }
        val out = ArrayList<Endpoint>()
        val unnamedParts = HashMap<String, MutableList<UnnamedPart>>()
        for ((name, d) in dex) for (c in d.classDefs) {
            if (c.annotationsOff == 0) continue
            val ann = runCatching { d.annotations(c) }.getOrNull() ?: continue
            if (ann.methods.isEmpty()) continue
            val owner = d.type(c.classIdx)
            for ((methodIdx, set) in ann.methods) {
                val verbAnn = set.firstOrNull { it.type in roles.verbs || it.type in roles.generic } ?: continue
                val strings = verbAnn.strings()
                val (verb, path) = if (verbAnn.type in roles.generic) {
                    val verbValue = strings.firstOrNull { it.uppercase() in VERBS } ?: "GET"
                    verbValue.uppercase() to (strings.firstOrNull { it != verbValue } ?: "")
                } else {
                    roles.verbs.getValue(verbAnn.type) to (strings.firstOrNull() ?: "")
                }
                val id = d.methodIds[methodIdx]
                val proto = d.protos[id.protoIdx]
                val paramTypes = proto.parameters.map { d.type(it) }
                val signature = set.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }?.strings()?.joinToString("")
                val genericParams = signature?.let { Signatures.parameters(it) }
                val paramAnns = ann.parameters[methodIdx].orEmpty()
                val params = ArrayList<EndpointParam>()
                var body: String? = null
                for ((k, raw) in paramTypes.withIndex()) {
                    val javaType = genericParams?.getOrNull(k)?.let(Signatures::javaType) ?: Signatures.javaType(raw)
                    val a = paramAnns.getOrNull(k).orEmpty().firstOrNull { it.type in roles.params } ?: continue
                    val kind = roles.params.getValue(a.type)
                    when (kind) {
                        "Body" -> body = javaType
                        "Tag" -> Unit
                        else -> {
                            val declared = a.strings().firstOrNull()
                            params += EndpointParam(declared ?: kind.lowercase(), kind.lowercase(), javaType, if (declared == null && kind == "Part") "unnamed" else "annotation")
                            if (declared == null && kind == "Part") unnamedParts.getOrPut(d.methodDescriptor(methodIdx)) { ArrayList() } += UnnamedPart(out.size, params.size - 1, k, paramTypes, raw)
                        }
                    }
                }
                // Kotlin suspend functions answer through their last (Continuation) parameter; R8 may
                // rename Continuation, but the method then still returns Object and the parameter
                // still carries the one type argument.
                val suspendLike = d.type(proto.returnType) == "Ljava/lang/Object;" && paramAnns.lastOrNull().isNullOrEmpty()
                val continuation = genericParams?.lastOrNull()
                    ?.takeIf { it.startsWith("Lkotlin/coroutines/Continuation<") || (suspendLike && it.count { c -> c == '<' } >= 1) }
                    ?.let { Signatures.typeArgument(it)?.removePrefix("-")?.removePrefix("+") }
                val returns = continuation ?: signature?.let { Signatures.returnType(it) } ?: d.type(proto.returnType)
                out += Endpoint(
                    method = verb,
                    path = path,
                    operation = d.string(id.nameIdx),
                    declaredIn = owner.removePrefix("L").removeSuffix(";").replace('/', '.'),
                    params = params,
                    bodyType = body,
                    responseType = unwrap(Signatures.javaType(returns)).let { if (it == unit || it == "kotlin.Unit") "Unit" else it },
                    evidence = listOf(SourceRef("dex-annotation", "$name:${d.methodDescriptor(methodIdx)}")),
                )
            }
        }
        if (unnamedParts.isNotEmpty()) namePartsFromCallSites(dex, unnamedParts, out)
        return out.sortedWith(compareBy({ it.path }, { it.method }))
    }

    /**
     * The interface methods that declare an endpoint, by full descriptor (`Lx/Api;->get(I)Ljava/lang/Object;`),
     * as `"VERB path"`: a call to one of them sends that request.
     */
    fun endpointMethods(dex: List<Pair<String, DexFile>>, roles: Roles = roles(dex)): Map<String, String> {
        if (roles.verbs.isEmpty() && roles.generic.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for ((_, d) in dex) for (c in d.classDefs) {
            if (c.annotationsOff == 0) continue
            val ann = runCatching { d.annotations(c) }.getOrNull() ?: continue
            for ((methodIdx, set) in ann.methods) {
                val verbAnn = set.firstOrNull { it.type in roles.verbs || it.type in roles.generic } ?: continue
                val strings = verbAnn.strings()
                val (verb, path) = if (verbAnn.type in roles.generic) {
                    val verbValue = strings.firstOrNull { it.uppercase() in VERBS } ?: "GET"
                    verbValue.uppercase() to (strings.firstOrNull { it != verbValue } ?: "")
                } else roles.verbs.getValue(verbAnn.type) to (strings.firstOrNull() ?: "")
                out[d.methodDescriptor(methodIdx)] = "$verb $path"
            }
        }
        return out
    }

    /** An unnamed `@Part`: endpoint [endpoint], its parameter [param], declared as method parameter [position]. */
    private class UnnamedPart(val endpoint: Int, val param: Int, val position: Int, val paramTypes: List<String>, val partType: String)

    /**
     * Names unnamed multipart parts from their call sites: the part passed for the parameter is built
     * by a call that returns the part type and takes the name as its first string argument
     * (`MultipartBody.Part.createFormData("file", ...)`, renamed or not). A forward may-dataflow over
     * the method's control-flow graph carries the possible names of every register (compilers swap
     * registers between branches); a `null` from the other arm of `x?.let { part(...) }` adds none.
     * A part is named only when every call site that names it agrees on one name.
     */
    private fun namePartsFromCallSites(dex: List<Pair<String, DexFile>>, pending: Map<String, List<UnnamedPart>>, out: MutableList<Endpoint>) {
        val votes = HashMap<Pair<Int, Int>, MutableSet<String>>()
        val partTypes = pending.values.flatten().map { it.partType }.toSet()
        for ((_, d) in dex) {
            val targets = HashMap<Int, List<UnnamedPart>>()
            for ((idx, _) in d.methodIds.withIndex()) pending[d.methodDescriptor(idx)]?.let { targets[idx] = it }
            if (targets.isEmpty()) continue
            for (m in d.methods()) {
                val code = m.code ?: continue
                if (!mayCall(code.insns, targets.keys)) continue
                val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value ?: continue
                PartNameFlow(d, decoded, targets, partTypes).run().forEach { (key, names) -> votes.getOrPut(key) { HashSet() } += names }
            }
        }
        for ((key, found) in votes) {
            val name = found.singleOrNull() ?: continue
            val (e, p) = key
            val endpoint = out[e]
            out[e] = endpoint.copy(params = endpoint.params.mapIndexed { k, param -> if (k == p) param.copy(name = name, nameSource = "call-site") else param })
        }
    }

    /** The may-dataflow behind [namePartsFromCallSites]: per register, the constant string or the part names it may hold. */
    private class PartNameFlow(val d: DexFile, val code: dev.apkproject.dex.DecodedCode, val targets: Map<Int, List<UnnamedPart>>, val partTypes: Set<String>) {
        /** Register state; [RESULT] stands for the pending invoke result. */
        data class State(val strings: Map<Int, String>, val names: Map<Int, Set<String>>) {
            fun merge(o: State) = State(
                strings.filter { (r, v) -> o.strings[r] == v },
                (names.keys + o.names.keys).associateWith { names[it].orEmpty() + o.names[it].orEmpty() },
            )
        }

        private val RESULT = -1

        fun run(): Map<Pair<Int, Int>, Set<String>> {
            val insns = code.insns
            val index = insns.withIndex().associate { (k, i) -> i.address to k }
            val entry = HashMap<Int, State>()
            entry[0] = State(emptyMap(), emptyMap())
            val work = ArrayDeque(listOf(0))
            val found = HashMap<Pair<Int, Int>, MutableSet<String>>()
            var steps = 0
            while (work.isNotEmpty() && steps++ < 50_000) {
                val k = work.removeFirst()
                val i = insns[k]
                val out = transfer(i, entry.getValue(k), found)
                for (next in successors(i, k, index)) {
                    val old = entry[next]
                    val merged = old?.merge(out) ?: out
                    if (merged != old) { entry[next] = merged; work.addLast(next) }
                }
            }
            return found
        }

        private fun successors(i: dev.apkproject.dex.Insn, k: Int, index: Map<Int, Int>): List<Int> = when (i.op.flow) {
            dev.apkproject.dex.FlowKind.RETURN, dev.apkproject.dex.FlowKind.THROW -> emptyList()
            dev.apkproject.dex.FlowKind.GOTO -> listOfNotNull(index[i.target])
            dev.apkproject.dex.FlowKind.IF -> listOfNotNull(index[i.next], index[i.target])
            dev.apkproject.dex.FlowKind.SWITCH -> listOfNotNull(index[i.next]) + code.switchCases(i).mapNotNull { index[it.second] }
            else -> listOfNotNull(index[i.next])
        }

        private fun transfer(i: dev.apkproject.dex.Insn, s: State, found: MutableMap<Pair<Int, Int>, MutableSet<String>>): State {
            val strings = HashMap(s.strings)
            val names = HashMap(s.names)
            fun kill(r: Int) { strings.remove(r); names.remove(r) }
            val op = i.op.mnemonic
            when {
                op.startsWith("const-string") -> { kill(i.regs[0]); strings[i.regs[0]] = d.string(i.index) }
                // null: the register may still hold a name from another path, and gains none here.
                (op == "const/4" || op == "const/16" || op == "const") && i.literal == 0L -> { strings.remove(i.regs[0]); names[i.regs[0]] = emptySet() }
                op.startsWith("move-result") -> { kill(i.regs[0]); names[RESULT]?.let { names[i.regs[0]] = it }; names.remove(RESULT) }
                op == "move-object" || op == "move-object/from16" || op == "move-object/16" -> {
                    val src = i.regs[1]
                    kill(i.regs[0])
                    s.strings[src]?.let { strings[i.regs[0]] = it }
                    s.names[src]?.let { names[i.regs[0]] = it }
                }
                op.startsWith("invoke-") -> {
                    names.remove(RESULT)
                    val id = d.methodIds[i.index]
                    val params = d.protos[id.protoIdx].parameters.map { d.type(it) }
                    var r = if (op.startsWith("invoke-static")) 0 else 1
                    val args = params.map { t -> i.regs.getOrElse(r) { -1 }.also { r += if (t == "J" || t == "D") 2 else 1 } }
                    targets[i.index]?.forEach { u ->
                        val n = s.names[args.getOrElse(u.position) { -1 }].orEmpty()
                        // One call site naming the same argument two ways says nothing.
                        if (n.size == 1) found.getOrPut(u.endpoint to u.param) { HashSet() } += n
                    }
                    val returns = d.type(d.protos[id.protoIdx].returnType)
                    val name = params.indexOf("Ljava/lang/String;").takeIf { it >= 0 }?.let { s.strings[args[it]] }
                    if (name != null && returns in partTypes) names[RESULT] = setOf(name)
                }
                op.startsWith("if-") || op.startsWith("iput") || op.startsWith("sput") || op.startsWith("aput") || op.startsWith("return") ||
                    op.startsWith("throw") || op.startsWith("check-cast") || op.startsWith("goto") || op.endsWith("switch") || op.startsWith("monitor") || op == "nop" -> Unit
                i.regs.isNotEmpty() -> { kill(i.regs[0]); if (op.contains("wide")) kill(i.regs[0] + 1) }
            }
            return State(strings, names)
        }
    }

    /** Cheap pre-filter: whether raw code units contain an invoke of one of [methods]. */
    private fun mayCall(units: IntArray, methods: Set<Int>): Boolean {
        for (k in 0 until units.size - 1) {
            val op = units[k] and 0xff
            if ((op in 0x6e..0x72 || op in 0x74..0x78) && units[k + 1] in methods) return true
        }
        return false
    }

    /**
     * The JSON body type inside call wrappers: Retrofit's Call/Response, Rx and coroutine types, and
     * obfuscated single-argument wrappers (an app's own result type renamed by R8). Collections stay.
     */
    fun unwrap(t: String): String {
        val m = Regex("""^([\w.$]+)<(.+)>$""").find(t) ?: return t
        val raw = m.groupValues[1]
        val args = m.groupValues[2]
        if (raw in setOf("List", "Set", "Map", "Collection", "Iterable") || raw.startsWith("java.") || raw.startsWith("kotlin.collections.")) return t
        val known = Regex("""^(?:retrofit2\.)?(?:Call|Response|io\.reactivex\.\w+\.(?:Single|Observable|Flowable|Maybe)|kotlinx\.coroutines\.Deferred|kotlinx\.coroutines\.flow\.Flow|androidx\.lifecycle\.LiveData)$""").matches(raw)
        val obfuscated = raw.split('.').let { parts -> parts.size >= 2 && parts.all { it.length <= 3 } }
        return if ((known || obfuscated) && !args.contains(',')) unwrap(args) else t
    }

    private val WIRE_NAMES = setOf(
        "Lcom/google/gson/annotations/SerializedName;", "Lcom/squareup/moshi/Json;", "Lkotlinx/serialization/SerialName;", "Lcom/fasterxml/jackson/annotation/JsonProperty;",
    )

    /** Whether an annotation type looks like a JSON wire-name annotation (Gson, Moshi, Jackson, kotlinx). */
    fun isWireName(a: DexAnnotation) = a.type in WIRE_NAMES

    /**
     * Wire-name annotation types in these DEX files: the known ones, and the same annotations under
     * R8's new names, recognised by their members: Moshi's `@Json` declares `name(): String` and
     * `ignore(): boolean`, Gson's `@SerializedName` `value(): String` and `alternate(): String[]`.
     */
    fun wireNameTypes(dex: List<Pair<String, DexFile>>): Set<String> {
        val out = HashSet(WIRE_NAMES)
        for ((_, d) in dex) for (c in d.classDefs) {
            if (c.accessFlags and AccessFlags.ANNOTATION == 0) continue
            val members = c.classData?.virtualMethods.orEmpty().map { val id = d.methodIds[it.methodIdx]; d.string(id.nameIdx) + d.protoDescriptor(id.protoIdx) }.toSet()
            if (("name()Ljava/lang/String;" in members && "ignore()Z" in members) || ("value()Ljava/lang/String;" in members && "alternate()[Ljava/lang/String;" in members)) out += d.type(c.classIdx)
        }
        return out
    }

    /** JSON models: Moshi codegen adapters, and fields with wire-name annotations, read from the bytecode. */
    fun models(dex: List<Pair<String, DexFile>>): List<ModelSchema> {
        val out = ArrayList<ModelSchema>()
        val wireTypes = wireNameTypes(dex)
        val enums = EnumIndex(dex, wireTypes)
        out += moshiCodegenModels(dex, enums, wireTypes, CustomScalarIndex(dex))
        for ((name, d) in dex) for (c in d.classDefs) {
            if (c.annotationsOff == 0 || c.accessFlags and AccessFlags.ANNOTATION != 0) continue
            // Enum constants carry wire names too; they are values, not models.
            if (c.superclassIdx != NO_INDEX && d.type(c.superclassIdx) == "Ljava/lang/Enum;") continue
            val ann = runCatching { d.annotations(c) }.getOrNull() ?: continue
            val fields = ann.fields.mapNotNull { (fieldIdx, set) ->
                val wire = set.firstOrNull { it.type in wireTypes } ?: return@mapNotNull null
                val f = d.fieldIds[fieldIdx]
                val sig = runCatching { ann.fields[fieldIdx] }.getOrNull()?.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }?.strings()?.joinToString("") ?: d.type(f.typeIdx)
                ModelField(wire.strings().firstOrNull() ?: d.string(f.nameIdx), d.string(f.nameIdx), Signatures.javaType(sig), enums.values(sig))
            }
            if (fields.isEmpty()) continue
            val owner = d.type(c.classIdx)
            out += ModelSchema(owner.removePrefix("L").removeSuffix(";").replace('/', '.'), fields, SourceRef("dex-annotation", "$name:$owner"))
        }
        return out.distinctBy { it.className }.sortedBy { it.className }
    }

    /**
     * Moshi's code generator writes `<Model>JsonAdapter` (kept by name, as Moshi looks it up by
     * reflection, so the name is the model's original name even when R8 renamed the model). Read
     * from the adapter's own bytecode:
     * - the constructor builds `JsonReader.Options.of("id", "url", ...)` with every JSON key, and
     *   one `moshi.adapter(type, annotations, "property")` per distinct property type, where
     *   `type` is a class literal, a primitive's `TYPE` or `Types.newParameterizedType(raw, args)`;
     * - `toJson` writes `name("key")` and then the value through one of those adapters, in
     *   straight-line code, which pairs every key with its exact type;
     * - `fromJson` reports nulls with `unexpectedNull("property", "key", reader)`, which pairs keys
     *   with Kotlin property names.
     * The model's constructor is only a fallback: R8 reorders constructor parameters (proto
     * normalization), so its parameter order need not be the key order.
     */
    private fun moshiCodegenModels(dex: List<Pair<String, DexFile>>, enums: EnumIndex, wireTypes: Set<String>, custom: CustomScalarIndex): List<ModelSchema> {
        val out = ArrayList<ModelSchema>()
        // An adapter and its model need not be in the same DEX or split. Indices in annotations
        // and constructors belong to the model's DEX, so keep the owner alongside the class.
        val modelClasses = dex.flatMap { (_, d) -> d.classDefs.map { d.type(it.classIdx) to (d to it) } }
            .groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }
        for ((name, d) in dex) {
            val classes = d.classDefs.associateBy { d.type(it.classIdx) }
            val all = d.methods()
            val methodsByClass = all.groupBy { it.classDef }
            val byIndex = all.associateBy { it.encoded.methodIdx }
            for ((adapter, def) in classes) {
                if (!adapter.endsWith("JsonAdapter;")) continue
                val base = adapter.removeSuffix("JsonAdapter;")
                val methods = methodsByClass[def].orEmpty().filter { it.code != null }
                val ctor = methods.firstOrNull { it.name == "<init>" } ?: continue
                val keys = stringArrayLiteral(d, ctor) ?: continue
                val adapterTypes = adapterFieldTypes(d, ctor, def)
                val keyFields = methods.asSequence().filter { it.name != "<init>" }.mapNotNull { keyToAdapterField(d, it, def, keys.toSet(), byIndex) }.maxByOrNull { it.size }.orEmpty()
                val modelFields = methods.filter { it.name != "<init>" }.map { m ->
                    WriteScan(d, def.classIdx, keys.toSet(), byIndex).also { it.scan(m, emptyMap(), 0) }.modelFields
                }.maxByOrNull { it.size }.orEmpty()
                val properties = HashMap<String, String>()
                val model = listOf("$base;", base.replace('_', '$') + ";").firstOrNull { it in modelClasses }
                val modelOwner = model?.let { modelClasses.getValue(it) }
                // Kotlin keeps property annotations on synthetic `get<Property>$annotations` methods.
                modelOwner?.let { (ownerDex, m) -> runCatching { ownerDex.annotations(m) }.getOrNull() }?.methods?.forEach { (idx, set) ->
                    val ownerDex = modelOwner.first
                    val method = ownerDex.string(ownerDex.methodIds[idx].nameIdx)
                    val property = Regex("^get(.+)\\\$annotations\$").find(method)?.groupValues?.get(1)?.replaceFirstChar { it.lowercase() } ?: return@forEach
                    val key = set.firstOrNull { it.type in wireTypes }?.strings()?.firstOrNull() ?: return@forEach
                    if (key in keys) properties.putIfAbsent(key, property)
                }
                // Without @Json the JSON name is the property name; null checks name the rest (codegen
                // suffixes '_' to locals that clash, e.g. `header_`).
                val checked = HashSet<String>()
                for (m in methods) for ((key, property) in nullCheckProperties(d, m, keys.toSet())) {
                    checked += key
                    if (property.isNotEmpty()) properties.putIfAbsent(key, property.trimEnd('_').ifEmpty { property })
                }
                // Codegen's reader throws unexpectedNull exactly for non-null properties; without a
                // readable toJson this is not that kind of adapter, and nullability stays unknown.
                val nullability = keyFields.isNotEmpty()
                val fallback = if (keyFields.size == keys.size) null else modelOwner?.let { (ownerDex, m) -> constructorTypes(ownerDex, m, keys.size) }
                val fields = keys.mapIndexed { k, key ->
                    val sig = keyFields[key]?.let { adapterTypes[it] }
                    val type = sig?.let { Signatures.javaType(it) } ?: fallback?.getOrNull(k)?.let { Signatures.javaType(it) } ?: "?"
                    ModelField(key, properties[key] ?: key, type, enums.values(sig ?: fallback?.getOrNull(k)), if (nullability) key !in checked else null, custom.shape(sig),
                        modelFields[key]?.let { listOf(SourceRef("dex-model-field", "$name:$it")) }.orEmpty())
                }
                // The adapter is named after the original model (Outer_InnerJsonAdapter), even when R8
                // renamed the model class itself.
                val className = model?.let { it.removePrefix("L").removeSuffix(";").replace('/', '.').replace('$', '.') }
                    ?: base.removePrefix("L").replace('/', '.').let { n -> n.substringBeforeLast('.') + "." + n.substringAfterLast('.').replace('_', '.') }
                out += ModelSchema(className, fields, SourceRef("dex-moshi-adapter", "$name:$adapter"))
            }
        }
        return out
    }

    /** The model constructor's parameter signatures, when one has exactly [count] parameters. */
    private fun constructorTypes(d: DexFile, modelDef: ClassDef, count: Int): List<String>? {
        val ctor = d.methods().firstOrNull { it.classDef === modelDef && it.name == "<init>" && it.accessFlags and AccessFlags.SYNTHETIC == 0 && it.proto.parameters.size == count } ?: return null
        val sig = runCatching { d.annotations(modelDef).methods[ctor.encoded.methodIdx] }.getOrNull()
            ?.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }?.strings()?.joinToString("")?.let { Signatures.parameters(it) }
        return ctor.proto.parameters.mapIndexed { k, t -> sig?.getOrNull(k) ?: d.type(t) }
    }

    /** What a register holds while reading an adapter constructor: enough to rebuild `java.lang.reflect.Type` values. */
    private sealed class Sym {
        data class Type(val signature: String) : Sym()
        class Array(val items: kotlin.Array<Sym?>) : Sym()
        data class Str(val value: String) : Sym()
        data class Num(val value: Long) : Sym()
        data class Adapter(val signature: String) : Sym()
    }

    private val PRIMITIVE_TYPES = mapOf(
        "Ljava/lang/Integer;" to "I", "Ljava/lang/Long;" to "J", "Ljava/lang/Boolean;" to "Z", "Ljava/lang/Double;" to "D",
        "Ljava/lang/Float;" to "F", "Ljava/lang/Short;" to "S", "Ljava/lang/Byte;" to "B", "Ljava/lang/Character;" to "C",
    )
    private const val TYPE = "Ljava/lang/reflect/Type;"
    private val TYPE_LIKE = setOf(TYPE, "Ljava/lang/Class;")

    /** Adapter field -> JVM type signature, from the `moshi.adapter(type, annotations, name)` calls in the constructor. */
    private fun adapterFieldTypes(d: DexFile, ctor: dev.apkproject.dex.DexMethod, adapterDef: ClassDef): Map<Int, String> {
        val insns = (InstructionDecoder.decode(d, ctor.code!!) as? Outcome.Ok)?.value?.insns ?: return emptyMap()
        val regs = HashMap<Int, Sym>()
        var result: Sym? = null
        val out = HashMap<Int, String>()
        fun signature(s: Sym?): String? = when (s) {
            is Sym.Type -> s.signature
            else -> null
        }
        for (i in insns) {
            val op = i.op.mnemonic
            when {
                op.startsWith("const-class") -> regs[i.regs[0]] = Sym.Type(d.type(i.index))
                op.startsWith("const-string") -> regs[i.regs[0]] = Sym.Str(d.string(i.index))
                op == "const" || op.startsWith("const/") -> regs[i.regs[0]] = Sym.Num(i.literal)
                op.startsWith("sget-object") -> {
                    val f = d.fieldIds[i.index]
                    val prim = PRIMITIVE_TYPES[d.type(f.classIdx)]
                    if (prim != null && d.string(f.nameIdx) == "TYPE") regs[i.regs[0]] = Sym.Type(prim) else regs.remove(i.regs[0])
                }
                op == "new-array" -> {
                    val n = (regs[i.regs[1]] as? Sym.Num)?.value?.toInt()
                    if (n != null && n in 0..64 && d.type(i.index) in setOf("[$TYPE", "[Ljava/lang/Class;")) regs[i.regs[0]] = Sym.Array(arrayOfNulls(n)) else regs.remove(i.regs[0])
                }
                op.startsWith("aput-object") -> {
                    val a = regs[i.regs[1]] as? Sym.Array
                    val k = (regs[i.regs[2]] as? Sym.Num)?.value?.toInt()
                    if (a != null && k != null && k in a.items.indices) a.items[k] = regs[i.regs[0]]
                }
                op.startsWith("filled-new-array") -> result = Sym.Array(i.regs.map { regs[it] }.toTypedArray())
                op.startsWith("invoke-") -> {
                    val id = d.methodIds[i.index]
                    val params = d.protos[id.protoIdx].parameters.map { d.type(it) }
                    val static = op.startsWith("invoke-static")
                    // Argument registers in order (a wide argument takes two registers).
                    val args = ArrayList<Int>()
                    var r = if (static) 0 else 1
                    for (p in params) { args += i.regs.getOrElse(r) { -1 }; r += if (p == "J" || p == "D") 2 else 1 }
                    fun arrayOf(reg: Int) = (regs[reg] as? Sym.Array)?.items?.map(::signature)
                    result = when {
                        // Types.newParameterizedType(raw, args...) and newParameterizedTypeWithOwner(owner, raw, args...);
                        // R8 may narrow a Type parameter to Class.
                        static && params.size == 2 && params[0] in TYPE_LIKE && params[1] == "[$TYPE" -> parameterized(signature(regs[args[0]]), arrayOf(args[1]))
                        static && params.size == 3 && params[0] in TYPE_LIKE && params[1] in TYPE_LIKE && params[2] == "[$TYPE" -> parameterized(signature(regs[args[1]]), arrayOf(args[2]))
                        // moshi.adapter(type, annotations, propertyName)
                        !static && params.size >= 3 && params[params.size - 3] in TYPE_LIKE && params.takeLast(2) == listOf("Ljava/util/Set;", "Ljava/lang/String;") ->
                            signature(regs[args[args.size - 3]])?.let { Sym.Adapter(it) }
                        else -> null
                    }
                }
                op.startsWith("move-result") -> { val v = result; if (v != null) regs[i.regs[0]] = v else regs.remove(i.regs[0]); result = null }
                op == "move-object" || op == "move-object/from16" || op == "move-object/16" -> regs[i.regs[1]]?.let { regs[i.regs[0]] = it } ?: regs.remove(i.regs[0])
                op.startsWith("iput-object") -> {
                    val f = d.fieldIds[i.index]
                    val a = regs[i.regs[0]] as? Sym.Adapter
                    if (a != null && f.classIdx == adapterDef.classIdx) out[i.index] = a.signature
                }
                op.startsWith("check-cast") -> Unit
                op.startsWith("if-") || op.startsWith("goto") || op.startsWith("return") || op.startsWith("throw") || op.startsWith("iput") || op.startsWith("sput") || op.startsWith("aput") -> Unit
                i.regs.isNotEmpty() -> regs.remove(i.regs[0])
            }
        }
        return out
    }

    private fun parameterized(raw: String?, args: List<String?>?): Sym? {
        if (raw == null || !raw.startsWith("L") || args == null || args.any { it == null }) return raw?.let { Sym.Type(it) }
        return Sym.Type(raw.removeSuffix(";") + "<" + args.joinToString("") + ">;")
    }

    /**
     * JSON key -> adapter field, from `toJson`: `name("key")`, then `adapter.toJson(writer, value)`.
     * R8 outlines repeated sequences into static helpers (e.g. `h(value, adapter, writer, "next")`,
     * which writes one value and then the next name), so calls that carry a key or an adapter are
     * followed into the helper, in order.
     */
    private fun keyToAdapterField(d: DexFile, m: DexMethod, adapterDef: ClassDef, keys: Set<String>, byIndex: Map<Int, DexMethod>): Map<String, Int>? {
        val state = WriteScan(d, adapterDef.classIdx, keys, byIndex)
        state.scan(m, emptyMap(), 0)
        return state.pairs.takeIf { it.isNotEmpty() }
    }

    private class WriteScan(val d: DexFile, val adapterClass: Int, val keys: Set<String>, val byIndex: Map<Int, DexMethod>) {
        sealed class V {
            data class Key(val key: String) : V()
            data class Adapter(val field: Int) : V()
            data class Field(val descriptor: String) : V()
            data object Input : V()
            data class ModelValue(val descriptor: String) : V()
        }
        val pairs = LinkedHashMap<String, Int>()
        val modelFields = LinkedHashMap<String, String>()
        private val conflictingFields = HashSet<String>()
        var current: String? = null
        private var fieldKey: String? = null

        fun scan(m: DexMethod, params: Map<Int, V>, depth: Int) {
            val code = m.code ?: return
            val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value ?: return
            val insns = decoded.insns
            val regs = HashMap<Int, V>(params)
            if (params.isEmpty() && !m.isStatic && m.proto.parameters.map { d.type(it) }.lastOrNull() == "Ljava/lang/Object;")
                regs[code.registersSize - 1] = V.Input
            val labels = decoded.labelAddresses()
            for (i in insns) {
                val op = i.op.mnemonic
                // Field evidence is stricter than the legacy adapter-type scan. A field/key pair
                // must be a direct read from the serializer's input in one straight-line region.
                if (i.address in labels) {
                    fieldKey = null
                    regs.entries.removeIf { it.value is V.Field || it.value is V.ModelValue || it.value == V.Input }
                }
                if (i.op.flow in setOf(dev.apkproject.dex.FlowKind.IF, dev.apkproject.dex.FlowKind.GOTO, dev.apkproject.dex.FlowKind.SWITCH)) {
                    fieldKey = null
                    regs.entries.removeIf { it.value is V.Field }
                }
                when {
                    op.startsWith("const-string") -> d.string(i.index).let { if (it in keys) regs[i.regs[0]] = V.Key(it) else regs.remove(i.regs[0]) }
                    op.startsWith("iget-object") -> if (d.fieldIds[i.index].classIdx == adapterClass) regs[i.regs[0]] = V.Adapter(i.index) else {
                        val receiver = regs[i.regs[1]] as? V.ModelValue
                        if (receiver?.descriptor == d.type(d.fieldIds[i.index].classIdx)) regs[i.regs[0]] = V.Field(d.fieldDescriptor(i.index)) else regs.remove(i.regs[0])
                    }
                    op == "move-object" || op == "move-object/from16" || op == "move-object/16" -> regs[i.regs[1]]?.let { regs[i.regs[0]] = it } ?: regs.remove(i.regs[0])
                    op.startsWith("invoke-") -> {
                        val id = d.methodIds[i.index]
                        val params2 = d.protos[id.protoIdx].parameters.map { d.type(it) }
                        val static = op.startsWith("invoke-static")
                        val callee = byIndex[i.index]
                        val carries = i.regs.any { regs[it] != null }
                        if (static && carries && depth < 3 && callee?.code != null) {
                            // Bind the callee's parameter registers (the last insSize registers) to our values.
                            val c = callee.code!!
                            val base = c.registersSize - c.insSize
                            scan(callee, i.regs.withIndex().mapNotNull { (k, r) -> regs[r]?.let { base + k to it } }.toMap(), depth + 1)
                        } else if (!static && params2 == listOf("Ljava/lang/String;") && regs[i.regs.getOrElse(1) { -1 }] is V.Key) {
                            current = (regs[i.regs[1]] as V.Key).key
                            fieldKey = current
                        } else if (!static && current != null) {
                            (regs[i.regs[0]] as? V.Adapter)?.let { a ->
                                val key = current!!
                                if (key !in pairs) pairs[key] = a.field
                                (regs[i.regs.getOrElse(2) { -1 }] as? V.Field)?.takeIf { key == fieldKey }?.let { f ->
                                    if (key !in conflictingFields) {
                                        val previous = modelFields.putIfAbsent(key, f.descriptor)
                                        if (previous != null && previous != f.descriptor) { modelFields.remove(key); conflictingFields += key }
                                    }
                                }
                                current = null
                                fieldKey = null
                            }
                        } else fieldKey = null
                    }
                    op == "check-cast" -> {
                        if (regs[i.regs[0]] == V.Input) regs[i.regs[0]] = V.ModelValue(d.type(i.index))
                    }
                    op.startsWith("move-result") || op.startsWith("const") || op.startsWith("new-") || op.startsWith("sget") || op.startsWith("iget") || op.startsWith("check-cast").not() && i.regs.isNotEmpty() && writesFirst(op) ->
                        if (i.regs.isNotEmpty()) regs.remove(i.regs[0])
                }
            }
        }

        /** Instructions whose first register is a destination. */
        private fun writesFirst(op: String) = !(op.startsWith("if-") || op.startsWith("invoke-") || op.startsWith("return") || op.startsWith("throw") ||
            op.startsWith("iput") || op.startsWith("sput") || op.startsWith("aput") || op.startsWith("goto") || op.startsWith("monitor") ||
            op.startsWith("fill-array") || op.startsWith("packed-switch") || op.startsWith("sparse-switch") || op.startsWith("check-cast") || op == "nop")
    }

    /** JSON key -> Kotlin property, from `unexpectedNull("property", "key", reader)` and `missingProperty(...)` calls. */
    private fun nullCheckProperties(d: DexFile, m: DexMethod, keys: Set<String>): Map<String, String> {
        val decoded = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value ?: return emptyMap()
        val strings = constantStrings(d, decoded)
        val out = HashMap<String, String>()
        for ((k, i) in decoded.insns.withIndex()) {
            if (!i.op.mnemonic.startsWith("invoke-static")) continue
            val params = d.protos[d.methodIds[i.index].protoIdx].parameters.map { d.type(it) }
            if (params.size != 3 || params[0] != "Ljava/lang/String;" || params[1] != "Ljava/lang/String;") continue
            val state = strings[k] ?: continue
            val property = state[i.regs[0]]
            val key = state[i.regs[1]]
            // A "property" that is another JSON key is a different call with two key strings:
            // the key is still checked, but its property name is unknown ("").
            if (property != null && key != null && key in keys) out[key] = if (property == key || property !in keys) property else out[key] ?: ""
        }
        return out
    }

    /**
     * For every instruction, the registers that hold the same constant string on every path to it:
     * readers load each key once, before the loop, and use it in many branches.
     */
    private fun constantStrings(d: DexFile, code: dev.apkproject.dex.DecodedCode): Array<Map<Int, String>?> {
        val insns = code.insns
        val index = insns.withIndex().associate { (k, i) -> i.address to k }
        val entry = arrayOfNulls<Map<Int, String>>(insns.size)
        if (insns.isEmpty()) return entry
        entry[0] = emptyMap()
        val work = ArrayDeque(listOf(0))
        var steps = 0
        while (work.isNotEmpty() && steps++ < 200_000) {
            val k = work.removeFirst()
            val i = insns[k]
            val out = HashMap(entry[k]!!)
            val op = i.op.mnemonic
            when {
                op.startsWith("const-string") -> out[i.regs[0]] = d.string(i.index)
                op == "move-object" || op == "move-object/from16" || op == "move-object/16" -> entry[k]!![i.regs[1]]?.let { out[i.regs[0]] = it } ?: out.remove(i.regs[0])
                i.regs.isNotEmpty() && writesFirstRegister(op) -> { out.remove(i.regs[0]); if (op.contains("wide")) out.remove(i.regs[0] + 1) }
            }
            val next = when (i.op.flow) {
                dev.apkproject.dex.FlowKind.RETURN, dev.apkproject.dex.FlowKind.THROW -> emptyList()
                dev.apkproject.dex.FlowKind.GOTO -> listOfNotNull(index[i.target])
                dev.apkproject.dex.FlowKind.IF -> listOfNotNull(index[i.next], index[i.target])
                dev.apkproject.dex.FlowKind.SWITCH -> listOfNotNull(index[i.next]) + code.switchCases(i).mapNotNull { index[it.second] }
                else -> listOfNotNull(index[i.next])
            }
            for (n in next) {
                val old = entry[n]
                val merged = old?.filter { (r, v) -> out[r] == v } ?: out
                if (merged != old) { entry[n] = merged; work.addLast(n) }
            }
        }
        return entry
    }

    /** Instructions whose first register operand is a destination. */
    private fun writesFirstRegister(op: String) = !(op.startsWith("if-") || op.startsWith("invoke-") || op.startsWith("return") || op.startsWith("throw") ||
        op.startsWith("iput") || op.startsWith("sput") || op.startsWith("aput") || op.startsWith("goto") || op.startsWith("monitor") ||
        op.startsWith("fill-array") || op.endsWith("switch") || op.startsWith("check-cast") || op == "nop")

    /** The first `String[]` literal of constant strings built in [m] (filled-new-array or new-array + aput). */
    private fun stringArrayLiteral(d: DexFile, m: dev.apkproject.dex.DexMethod): List<String>? {
        val insns = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value?.insns ?: return null
        val strings = HashMap<Int, String>()
        val ints = HashMap<Int, Long>()
        var array: Array<String?>? = null
        var arrayReg = -1
        for (i in insns) {
            val op = i.op.mnemonic
            when {
                op.startsWith("const-string") -> strings[i.regs[0]] = d.string(i.index)
                op == "const" || op.startsWith("const/") -> ints[i.regs[0]] = i.literal
                op.startsWith("filled-new-array") && i.op.index == IndexKind.TYPE && d.type(i.index) == "[Ljava/lang/String;" -> {
                    val values = i.regs.map { strings[it] }
                    if (values.isNotEmpty() && values.all { it != null }) return values.map { it!! }
                }
                op == "new-array" && d.type(i.index) == "[Ljava/lang/String;" -> {
                    val n = ints[i.regs[1]]?.toInt() ?: continue
                    if (n in 1..4096) { array = arrayOfNulls(n); arrayReg = i.regs[0] }
                }
                op == "aput-object" && i.regs[1] == arrayReg -> {
                    val a = array ?: continue
                    val idx = ints[i.regs[2]]?.toInt() ?: continue
                    if (idx in a.indices) a[idx] = strings[i.regs[0]]
                    if (a.all { it != null }) return a.map { it!! }
                }
            }
        }
        return null
    }
}

/**
 * Enum classes across the DEX files, with each constant's JSON value: its `@Json` /
 * `@SerializedName` name, else the constant's own name (the string `<clinit>` passes to
 * `Enum(name, ordinal)`, which survives renaming because `Enum.name()` returns it).
 */
class EnumIndex(dex: List<Pair<String, DexFile>>, private val wireTypes: Set<String> = BytecodeApi.wireNameTypes(dex)) {
    private val defs = HashMap<String, Pair<DexFile, ClassDef>>()
    private val cache = HashMap<String, List<String>>()

    init {
        for ((_, d) in dex) for (c in d.classDefs) {
            if (c.superclassIdx != NO_INDEX && d.type(c.superclassIdx) == "Ljava/lang/Enum;") defs[d.type(c.classIdx)] = d to c
        }
    }

    /** Values of the innermost enum named in a JVM type signature (`Lpkg/E;`, `Ljava/util/List<Lpkg/E;>;`). */
    fun values(signature: String?): List<String> {
        if (signature == null) return emptyList()
        val e = Regex("""L[^;<>]+;""").findAll(signature).map { it.value }.lastOrNull { it in defs } ?: return emptyList()
        return cache.getOrPut(e) { read(e) }
    }

    private fun read(desc: String): List<String> {
        val (d, c) = defs.getValue(desc)
        val data = c.classData ?: return emptyList()
        val constants = data.staticFields.filter { it.accessFlags and AccessFlags.ENUM != 0 && d.type(d.fieldIds[it.fieldIdx].typeIdx) == desc }
        // <clinit> calls Enum(name, ordinal, ...) for each constant: the name and the declaration order.
        val names = HashMap<Int, String>()
        val ordinals = HashMap<Int, Long>()
        data.directMethods.firstOrNull { d.string(d.methodIds[it.methodIdx].nameIdx) == "<clinit>" }?.let { DexMethod(d, c, it) }?.code?.let { code ->
            val insns = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value?.insns ?: return@let
            val strings = HashMap<Int, String>()
            val ints = HashMap<Int, Long>()
            val created = HashMap<Int, Pair<String, Long?>>()
            for (i in insns) {
                val op = i.op.mnemonic
                when {
                    op.startsWith("const-string") -> strings[i.regs[0]] = d.string(i.index)
                    op == "const" || op.startsWith("const/") -> ints[i.regs[0]] = i.literal
                    op.startsWith("invoke-direct") -> {
                        val id = d.methodIds[i.index]
                        val params = d.protos[id.protoIdx].parameters
                        if (d.type(id.classIdx) == desc && d.string(id.nameIdx) == "<init>" && params.isNotEmpty() && d.type(params[0]) == "Ljava/lang/String;") {
                            strings[i.regs[1]]?.let { created[i.regs[0]] = it to (if (params.size > 1 && d.type(params[1]) == "I") ints[i.regs.getOrElse(2) { -1 }] else null) }
                        }
                    }
                    op.startsWith("sput-object") -> created[i.regs[0]]?.let { (n, o) -> names[i.index] = n; o?.let { ordinals[i.index] = it } }
                }
            }
        }
        val annotations = runCatching { d.annotations(c) }.getOrNull()
        return constants.sortedBy { ordinals[it.fieldIdx] ?: Long.MAX_VALUE }.map { f ->
            annotations?.fields?.get(f.fieldIdx)?.firstOrNull { it.type in wireTypes }?.strings()?.firstOrNull()
                ?: names[f.fieldIdx] ?: d.string(d.fieldIds[f.fieldIdx].nameIdx)
        }
    }
}

/** JVM generic signatures (from `dalvik.annotation.Signature`) to readable Java types. */
object Signatures {
    /** Parameter signatures of a method signature `(...)R`, one string per parameter. */
    fun parameters(sig: String): List<String>? {
        val start = sig.indexOf('(')
        val end = sig.indexOf(')', start)
        if (start < 0 || end < 0) return null
        val out = ArrayList<String>()
        var i = start + 1
        while (i < end) {
            val next = skip(sig, i) ?: return null
            out += sig.substring(i, next)
            i = next
        }
        return out
    }

    fun returnType(sig: String): String? = sig.indexOf(')').takeIf { it >= 0 }?.let { sig.substring(it + 1) }

    /** First type argument of `Lpkg/Name<ARG;...>;`. */
    fun typeArgument(sig: String): String? {
        val open = sig.indexOf('<').takeIf { it >= 0 } ?: return null
        val end = skip(sig, open + 1) ?: return null
        return sig.substring(open + 1, end)
    }

    /** End index (exclusive) of the type signature starting at [i]. */
    private fun skip(s: String, i0: Int): Int? {
        var i = i0
        while (i < s.length && (s[i] == '[' || s[i] == '+' || s[i] == '-')) i++
        if (i >= s.length) return null
        return when (s[i]) {
            'L', 'T' -> {
                var depth = 0
                var k = i
                while (k < s.length) {
                    when (s[k]) {
                        '<' -> depth++
                        '>' -> depth--
                        ';' -> if (depth == 0) return k + 1
                    }
                    k++
                }
                null
            }
            '*' -> i + 1
            else -> i + 1
        }
    }

    /** `Lretrofit2/Call<Ljava/util/List<Lcom/x/User;>;>;` -> `retrofit2.Call<java.util.List<com.x.User>>`, with java.lang/java.util shortened. */
    fun javaType(sig: String): String {
        var s = sig
        var dims = 0
        while (s.startsWith("[")) { dims++; s = s.substring(1) }
        val base = when {
            // Kotlin's declaration-site variance (List<out E>) puts wildcards into signatures; JSON has no variance.
            s.startsWith("+") || s.startsWith("-") -> javaType(s.substring(1))
            s == "*" -> "?"
            s.startsWith("T") -> s.removePrefix("T").removeSuffix(";")
            s.startsWith("L") -> {
                val body = s.removePrefix("L").removeSuffix(";")
                val lt = body.indexOf('<')
                val raw = (if (lt < 0) body else body.substring(0, lt)).replace('/', '.').replace('$', '.')
                    .removePrefix("java.lang.").let { if (it.startsWith("java.util.") && it.count { c -> c == '.' } == 2) it.removePrefix("java.util.") else it }
                if (lt < 0) raw else {
                    val args = ArrayList<String>()
                    var i = lt + 1
                    val inner = body
                    while (i < inner.length && inner[i] != '>') {
                        val next = skip(inner, i) ?: break
                        args += javaType(inner.substring(i, next))
                        i = next
                    }
                    "$raw<${args.joinToString(", ")}>"
                }
            }
            else -> when (s) {
                "I" -> "int"; "J" -> "long"; "Z" -> "boolean"; "B" -> "byte"; "C" -> "char"; "S" -> "short"; "F" -> "float"; "D" -> "double"; "V" -> "void"
                else -> s
            }
        }
        return base + "[]".repeat(dims)
    }
}
