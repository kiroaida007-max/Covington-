package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DexAnnotation
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder

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
                        else -> params += EndpointParam(a.strings().firstOrNull() ?: kind.lowercase(), kind.lowercase(), javaType)
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
                    evidence = listOf(SourceRef("dex-annotation", "$name:$owner->${d.string(id.nameIdx)}")),
                )
            }
        }
        return out.sortedWith(compareBy({ it.path }, { it.method }))
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

    /** Whether an annotation type looks like a JSON wire-name annotation (Gson, Moshi, Jackson, kotlinx). */
    fun isWireName(a: DexAnnotation) = a.type in setOf(
        "Lcom/google/gson/annotations/SerializedName;", "Lcom/squareup/moshi/Json;", "Lkotlinx/serialization/SerialName;", "Lcom/fasterxml/jackson/annotation/JsonProperty;",
    )

    /** JSON models: Moshi codegen adapters, and fields with wire-name annotations, read from the bytecode. */
    fun models(dex: List<Pair<String, DexFile>>): List<ModelSchema> {
        val out = ArrayList<ModelSchema>()
        out += moshiCodegenModels(dex)
        for ((name, d) in dex) for (c in d.classDefs) {
            if (c.annotationsOff == 0 || c.accessFlags and AccessFlags.ANNOTATION != 0) continue
            val ann = runCatching { d.annotations(c) }.getOrNull() ?: continue
            val fields = ann.fields.mapNotNull { (fieldIdx, set) ->
                val wire = set.firstOrNull(::isWireName) ?: return@mapNotNull null
                val f = d.fieldIds[fieldIdx]
                ModelField(wire.strings().firstOrNull() ?: d.string(f.nameIdx), d.string(f.nameIdx), Signatures.javaType(d.type(f.typeIdx)))
            }
            if (fields.isEmpty()) continue
            val owner = d.type(c.classIdx)
            out += ModelSchema(owner.removePrefix("L").removeSuffix(";").replace('/', '.'), fields, SourceRef("dex-annotation", "$name:$owner"))
        }
        return out.distinctBy { it.className }.sortedBy { it.className }
    }

    /**
     * Moshi's code generator writes `<Model>JsonAdapter` (kept by name, as Moshi looks it up by
     * reflection); its constructor builds `JsonReader.Options.of("id", "url", ...)` with every JSON
     * key in property order. Types come from the model's constructor with as many parameters.
     */
    private fun moshiCodegenModels(dex: List<Pair<String, DexFile>>): List<ModelSchema> {
        val out = ArrayList<ModelSchema>()
        for ((name, d) in dex) {
            val classes = d.classDefs.associateBy { d.type(it.classIdx) }
            for ((adapter, def) in classes) {
                if (!adapter.endsWith("JsonAdapter;")) continue
                val base = adapter.removeSuffix("JsonAdapter;")
                val model = listOf("$base;", base.replace('_', '$') + ";").firstOrNull { it in classes } ?: continue
                val ctor = d.methods().firstOrNull { it.classDef === def && it.name == "<init>" && it.code != null } ?: continue
                val keys = stringArrayLiteral(d, ctor) ?: continue
                val modelDef = classes.getValue(model)
                val ctors = d.methods().filter { it.classDef === modelDef && it.name == "<init>" && it.accessFlags and AccessFlags.SYNTHETIC == 0 }
                val types = ctors.firstOrNull { it.proto.parameters.size == keys.size }?.let { c ->
                    val sig = runCatching { d.annotations(modelDef).methods[c.encoded.methodIdx] }.getOrNull()
                        ?.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }?.strings()?.joinToString("")?.let { Signatures.parameters(it) }
                    c.proto.parameters.mapIndexed { k, t -> Signatures.javaType(sig?.getOrNull(k) ?: d.type(t)) }
                }
                val fields = keys.mapIndexed { k, key -> ModelField(key, key, types?.getOrNull(k) ?: "?") }
                out += ModelSchema(model.removePrefix("L").removeSuffix(";").replace('/', '.').replace('$', '.'), fields, SourceRef("dex-moshi-adapter", "$name:$adapter"))
            }
        }
        return out
    }

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
            s.startsWith("+") -> "? extends " + javaType(s.substring(1))
            s.startsWith("-") -> "? super " + javaType(s.substring(1))
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
