package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.FlowKind
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.Insn
import dev.apkproject.dex.InstructionDecoder
import kotlinx.serialization.Serializable
import java.util.PriorityQueue

/** Personal data that reaches a place it can leave the app or the device from. */
@Serializable
data class PrivacyFlow(
    /** Data category: location, device-id, advertising-id, phone-number, contacts, call-log, sms, calendar, accounts, installed-apps, clipboard, wifi, audio, camera. */
    val data: String,
    val dataTitle: String,
    /** The platform API that produced it (`android.location.LocationManager.getLastKnownLocation`). */
    val sourceApi: String,
    /** The method that calls it. */
    val sourceIn: String,
    /** NETWORK, ANALYTICS (a third-party SDK), SMS, LOG, STORAGE, IPC, CLIPBOARD. */
    val sink: String,
    val sinkApi: String,
    /** The endpoint (`POST api/v1/statuses`), the SDK, or what the sink is. */
    val sinkDetail: String,
    val sinkIn: String,
    /** Where the data and the sink meet, then down to the sink call; the data's own way there is [sourcePath]. */
    val path: List<String>,
    /** From where they meet down to the source call (empty when the data came through a stored field). */
    val sourcePath: List<String>,
    /** HIGH, MEDIUM or LOW. */
    val attention: String,
    /** `app`, or the SDK or library the sink code belongs to. */
    val code: String,
    /** The field the data was stored in and read back from, when it took that way. */
    val throughField: String? = null,
    /**
     * Whether the manifest requests a permission the source API needs; null when it needs none or
     * the manifest is unknown. False: the API fails or returns nothing at run time, so the flow
     * cannot happen as built (often library code the app never enables); its attention is LOW.
     */
    val permissionDeclared: Boolean? = null,
    val evidence: String = "INFERRED",
)

/** One personal-data API the code calls, and whether any of its results reach a sink. */
@Serializable
data class PrivacySource(
    val data: String,
    val title: String,
    val api: String,
    val usedIn: List<String>,
    val reachesSink: Boolean,
    /** Runtime permissions the API needs (any one); empty when none. */
    val permissions: List<String> = emptyList(),
    val permissionDeclared: Boolean? = null,
)

/** Per data category, in the shape of Google Play's Data safety form. */
@Serializable
data class DataSafetyRow(
    val data: String,
    val title: String,
    /** The Play Data safety data type it corresponds to. */
    val playDataType: String,
    /** Leaves the device: reaches the network or SMS. */
    val collected: Boolean,
    /** Handed to a third-party SDK. */
    val shared: Boolean,
    val stored: Boolean,
    val logged: Boolean,
    val sinks: List<String>,
)

@Serializable
data class PrivacyStats(
    val methods: Int,
    /** Methods of well-known libraries modelled at their API instead of analysed (okhttp3, androidx, kotlin, ...). */
    val modelled: Int,
    val analyses: Int,
    val elapsedMs: Long,
    val truncated: Boolean,
    /** Method summaries that reached their size bound (48 sink edges or field writes); the rest were dropped. */
    val boundedSummaries: Int = 0,
)

@Serializable
data class PrivacyReport(
    val sources: List<PrivacySource>,
    val flows: List<PrivacyFlow>,
    val dataSafety: List<DataSafetyRow>,
    /** Sensitive platform capabilities the code references, with whether the manifest requests their permission. */
    val capabilities: List<Capability>,
    val sdks: List<SdkFinding>,
    val stats: PrivacyStats,
    val notes: List<String>,
)

/**
 * Privacy map: which personal data the code reads (location, device identifiers, contacts, ...)
 * and where it can go (the app's endpoints, third-party SDKs, logs, storage, other apps).
 *
 * The analysis is a static taint analysis over the DEX bytecode, built for precision first:
 * - every method gets a summary over symbolic labels for its parameters (what reaches its return
 *   value, which parameter objects it changes, which fields it writes, which parameters reach a
 *   sink), instantiated at each call site, so data passing through a shared helper returns only to
 *   the caller that passed it;
 * - object state is tracked one field deep per object, so storing data in one field of an object
 *   (a coroutine's `L$0`, an activity's listener) does not taint the whole object;
 * - fields are shared across instances only when their type is concrete (primitives, strings, final
 *   data types), never for `Object`, collections, interfaces or arrays;
 * - well-known libraries (androidx, kotlin, okhttp3, ...) are modelled at their API, not analysed.
 * A flow is a path the code allows, not proof that it runs: each is INFERRED.
 */
object PrivacyMap {
    fun analyze(dex: List<Pair<String, DexFile>>, manifestXml: String?, budgetMs: Long = 240_000): PrivacyReport {
        val started = System.nanoTime()
        val endpoints = runCatching { BytecodeApi.endpointMethods(dex) }.getOrDefault(emptyMap())
        val engine = TaintEngine(dex, endpoints, System.getenv("APKPROJECT_PRIVACY_BUDGET_MS")?.toLongOrNull() ?: budgetMs)
        engine.run()
        val manifest = manifestXml?.let { AppDiff.Manifest.parse(it) }
        val capabilities = Capabilities.of(engine.refsByClass(), manifest?.permissions)
        val hosts = dex.flatMap { (_, d) -> d.strings.mapNotNull { s -> HOST.find(s)?.groupValues?.get(1)?.lowercase() } }.toHashSet()
        val sdks = Capabilities.sdks(dex.flatMap { (_, d) -> d.typeIds.indices.map { d.type(it) } }.toHashSet(), hosts)
        val flows = engine.flows(manifest?.permissions)
        val sources = engine.sources(flows, manifest?.permissions)
        val stats = PrivacyStats(engine.methodCount, engine.modelledCount, engine.analyses, (System.nanoTime() - started) / 1_000_000, engine.truncated, engine.boundedCount)
        val notes = buildList {
            add("A flow is a path the bytecode allows from a personal-data API to a sink. Code on it can still be guarded at run time (a setting, a permission check), so every flow is INFERRED.")
            add("Data the user types is not treated as a source: sending a post or a message is the app's purpose.")
            add("Not followed: callbacks the platform calls later (listeners, LiveData, Handler.post), reflection, native code, and data stored and read back by a different run. A missing flow is not proof that data stays on the device.")
            add("Libraries under well-known package names (androidx, kotlin, okhttp3, retrofit2, ...) are modelled at their API; their internals are not analysed.")
            if (endpoints.isEmpty()) add("No Retrofit endpoints were found; network sinks are OkHttp, URLConnection, WebView and SDK calls.")
            if (flows.any { it.permissionDeclared == false }) add("Flows whose source needs a permission the manifest does not request cannot run as built; they are listed with LOW attention and left out of the Data safety summary.")
            if (engine.truncated) add("The analysis stopped at its time budget; flows may be missing.")
            if (engine.boundedCount > 0) add("${engine.boundedCount} method summaries reached their size bound (48 sink edges or field writes); flows through what was dropped may be missing.")
        }
        return PrivacyReport(sources, flows, dataSafety(flows, sources), capabilities, sdks, stats, notes)
    }

    /** "1 kind of personal data read", "3 kinds of personal data read". */
    fun kinds(n: Int) = "$n kind${if (n == 1) "" else "s"} of personal data"

    /** "1 flow reaches a sink", "2 flows reach a sink". */
    fun flowsReach(n: Int) = if (n == 1) "1 flow reaches a sink" else "$n flows reach a sink"

    private val HOST = Regex("""^(?:https?|wss?)://([A-Za-z0-9.-]+)""")

    internal val TITLES = mapOf(
        "location" to "Location", "device-id" to "Device identifiers (IMEI, serial, Android ID, MAC)", "advertising-id" to "Advertising ID",
        "phone-number" to "Phone number", "contacts" to "Contacts", "call-log" to "Call log", "sms" to "SMS messages", "calendar" to "Calendar events",
        "accounts" to "Device accounts", "installed-apps" to "Installed apps", "clipboard" to "Clipboard contents", "wifi" to "Wi-Fi networks",
        "audio" to "Microphone audio", "camera" to "Camera images",
    )

    private val PLAY_TYPES = mapOf(
        "location" to "Location: precise or approximate location", "device-id" to "Device or other IDs", "advertising-id" to "Device or other IDs",
        "phone-number" to "Personal info: phone number", "contacts" to "Contacts", "call-log" to "Messages: other in-app messages / call history",
        "sms" to "Messages: SMS or MMS", "calendar" to "Calendar: calendar events", "accounts" to "Personal info: email address / user IDs",
        "installed-apps" to "App info and performance / installed apps", "clipboard" to "App activity: other user-generated content",
        "wifi" to "Location: approximate location", "audio" to "Audio: voice or sound recordings", "camera" to "Photos and videos",
    )

    private fun dataSafety(flows: List<PrivacyFlow>, sources: List<PrivacySource>): List<DataSafetyRow> =
        sources.map { it.data }.distinct().map { data ->
            val fs = flows.filter { it.data == data && it.permissionDeclared != false }
            DataSafetyRow(
                data, TITLES[data] ?: data, PLAY_TYPES[data] ?: "Other",
                collected = fs.any { it.sink == "NETWORK" || it.sink == "SMS" || it.sink == "ANALYTICS" },
                shared = fs.any { it.sink == "ANALYTICS" || (it.code != "app" && it.sink == "NETWORK") },
                stored = fs.any { it.sink == "STORAGE" }, logged = fs.any { it.sink == "LOG" },
                sinks = fs.map { "${it.sink}: ${it.sinkDetail}" }.distinct().sorted(),
            )
        }
}

/** Summary-based taint analysis over DEX bytecode; see [PrivacyMap]. */
internal class TaintEngine(
    private val dex: List<Pair<String, DexFile>>,
    private val endpoints: Map<String, String>,
    private val budgetMs: Long,
) {
    // ---- labels ---------------------------------------------------------------------------------
    // 0..255: the value of parameter i; 256..511: parameter i with every field of its object;
    // above: interned (parameter, field) pairs, sources and markers.

    private class Label(val kind: Int, val param: Int, val field: String?, val data: String?, val api: String?, val method: String?)

    private val labels = ArrayList<Label>()
    private val interned = HashMap<String, Int>()

    private fun intern(key: String, make: () -> Label): Int = interned.getOrPut(key) {
        labels += make()
        MAXP * 2 + labels.size - 1
    }

    private fun label(id: Int): Label = labels[id - MAXP * 2]
    private fun isParam(id: Int) = id < MAXP
    private fun isParamAll(id: Int) = id in MAXP until MAXP * 2
    private fun isSymbolic(id: Int) = id < MAXP * 2 || label(id).kind == PARAM_FIELD
    private fun isSource(id: Int) = id >= MAXP * 2 && label(id).kind == SOURCE
    private fun isMark(id: Int) = id >= MAXP * 2 && label(id).kind == MARK

    private fun paramField(i: Int, field: String) = intern("pf|$i|$field") { Label(PARAM_FIELD, i, field, null, null, null) }
    private fun source(data: String, api: String, method: String) = intern("s|$data|$api|$method") { Label(SOURCE, -1, null, data, api, method) }

    /**
     * The same data after it was stored in a field shared across instances and read back. It can
     * still reach a sink, but is not stored into another shared field: one hop through the heap
     * covers "saved in a callback, sent on a click", while chains of hops spread through every
     * object that shares a field type (measured on Tusky: 5,000 fields from two sources).
     */
    private fun viaField(l: Int, field: String): Int {
        val src = label(l)
        if (src.field != null) return l
        return intern("v|$l|$field") { Label(SOURCE, -1, field, src.data, src.api, src.method) }
    }

    /** A marker: a content URI of a data category (`uri:contacts`), or a stream to the network or a file (`net`, `file`). */
    private fun mark(kind: String, api: String) = intern("m|$kind|$api") { Label(MARK, -1, null, kind, api, null) }

    // ---- values -----------------------------------------------------------------------------------

    /**
     * What a register holds: labels of the value itself, labels per field of the object (one level),
     * and which object it is (`alias`: parameter index, or -2-k for the object created at
     * instruction k, or -1 when unknown). A value read from a field remembers the object it was
     * read from ([holder], [holderField]): adding to it (a builder's `this.sb.append(x)`) adds to
     * that field of the holder.
     */
    private class RV(val whole: IntArray, val fields: Map<String, IntArray>?, val alias: Int, val holder: Int = -1, val holderField: String? = null) {
        fun isEmpty() = whole.isEmpty() && fields.isNullOrEmpty()
        fun same(o: RV): Boolean {
            if (this === o) return true
            if (alias != o.alias || holder != o.holder || holderField != o.holderField || !whole.contentEquals(o.whole)) return false
            val a = fields.orEmpty()
            val b = o.fields.orEmpty()
            return a.size == b.size && a.all { (k, v) -> b[k]?.contentEquals(v) == true }
        }
    }

    private fun rv(whole: IntArray, alias: Int = -1, fields: Map<String, IntArray>? = null, holder: Int = -1, holderField: String? = null): RV {
        if (whole.isEmpty() && fields.isNullOrEmpty() && alias == -1 && holder == -1) return EMPTY_RV
        // Too many tracked fields: the object as a whole carries them (a superset, so results only grow).
        if (fields != null && fields.size > MAX_FIELDS) {
            var w = whole
            fields.values.forEach { w = union(w, it) }
            return RV(widen(w), null, alias, holder, holderField)
        }
        return RV(widen(whole), fields?.takeIf { it.isNotEmpty() }, alias, holder, holderField)
    }

    private fun join(a: RV, b: RV): RV {
        if (a === b || b === EMPTY_RV && a.alias == -1 && a.holder == -1) return a
        if (a === EMPTY_RV && b.alias == -1 && b.holder == -1) return b
        val fields = if (a.fields == null) b.fields else if (b.fields == null) a.fields else HashMap(a.fields).also { m -> b.fields.forEach { (k, v) -> m[k] = union(m[k], v) } }
        val sameHolder = a.holder == b.holder && a.holderField == b.holderField
        return rv(union(a.whole, b.whole), if (a.alias == b.alias) a.alias else -1, fields, if (sameHolder) a.holder else -1, if (sameHolder) a.holderField else null)
    }

    /**
     * Keeps label sets small: more than [MAX_FIELD_LABELS] field labels of one parameter become that
     * parameter with all its fields (a superset), so summaries stay bounded along long call chains.
     */
    private fun widen(a: IntArray): IntArray {
        if (a.size <= MAX_FIELD_LABELS) return a
        var counts: HashMap<Int, Int>? = null
        for (l in a) if (l >= MAXP * 2 && label(l).kind == PARAM_FIELD) {
            if (counts == null) counts = HashMap()
            counts.merge(label(l).param, 1, Int::plus)
        }
        val wide = counts?.filterValues { it > MAX_FIELD_LABELS }?.keys ?: return a
        if (wide.isEmpty()) return a
        val kept = a.filter { l -> !(l >= MAXP * 2 && label(l).kind == PARAM_FIELD && label(l).param in wide) }.toIntArray()
        return union(kept, wide.map { MAXP + it }.sorted().toIntArray())
    }

    /** Value, fields, and (for a parameter's object) everything reachable from it. */
    private fun flatten(v: RV): IntArray {
        var out = v.whole
        v.fields?.values?.forEach { out = union(out, it) }
        if (v.alias in 0 until MAXP) out = union(out, intArrayOf(MAXP + v.alias))
        return out
    }

    // ---- program model ---------------------------------------------------------------------------

    private inner class MethodInfo(val ref: String, val d: DexFile, val m: dev.apkproject.dex.DexMethod, val id: Int) {
        val owner: String get() = m.owner
        var order = 0
        /** Registers of the parameters, by logical index (0 is the receiver of an instance method). */
        val paramRegs: IntArray by lazy {
            val c = m.code!!
            val types = (if (m.isStatic) emptyList() else listOf(m.owner)) + m.proto.parameters.map { d.type(it) }
            var r = c.registersSize - c.insSize
            IntArray(types.size) { i -> r.also { r += if (types[i] == "J" || types[i] == "D") 2 else 1 } }
        }
        private var cached: DecodedCode? = null
        fun code(): DecodedCode? {
            cached?.let { return it }
            val decoded = (InstructionDecoder.decode(d, m.code ?: return null) as? Outcome.Ok)?.value ?: return null
            if (keepDecoded) cached = decoded
            return decoded
        }
    }

    private val methods = HashMap<String, MethodInfo>()
    private val byId = ArrayList<MethodInfo>()
    private val superOf = HashMap<String, String>()
    private val interfacesOf = HashMap<String, List<String>>()
    private val subtypes = HashMap<String, MutableList<String>>()
    private val classFlags = HashMap<String, Int>()
    /** Every method with code, internal or modelled: owner -> signatures. */
    private val codeful = HashSet<String>()
    private val declaredFields = HashMap<String, MutableSet<String>>()
    /** Instance field types per class, to recognise flat data classes. */
    private val instanceFieldTypes = HashMap<String, List<String>>()
    private val keepDecoded: Boolean
    var methodCount = 0
        private set
    var modelledCount = 0
        private set
    var analyses = 0
        private set
    var truncated = false
        private set

    /** Classes and the framework members their code references, for [Capabilities.of]. */
    private val refs = HashMap<String, MutableSet<String>>()
    fun refsByClass(): List<Pair<String, Collection<String>>> = refs.map { (c, r) -> c.removePrefix("L").removeSuffix(";").replace('/', '.') to r }

    init {
        for ((_, d) in dex) for (c in d.classDefs) {
            val name = d.type(c.classIdx)
            if (name in classFlags) continue
            classFlags[name] = c.accessFlags
            if (c.superclassIdx >= 0 && c.superclassIdx < d.typeIds.size) d.type(c.superclassIdx).let { s -> superOf[name] = s; subtypes.getOrPut(s) { ArrayList() } += name }
            interfacesOf[name] = c.interfaces.map { d.type(it) }
            c.interfaces.forEach { i -> subtypes.getOrPut(d.type(i)) { ArrayList() } += name }
            c.classData?.let { cd ->
                (cd.staticFields + cd.instanceFields).forEach { f -> declaredFields.getOrPut(name) { HashSet() } += d.string(d.fieldIds[f.fieldIdx].nameIdx) }
                instanceFieldTypes[name] = cd.instanceFields.map { d.type(d.fieldIds[it.fieldIdx].typeIdx) }
            }
        }
        var total = 0
        for ((_, d) in dex) for (m in d.methods()) {
            if (m.code == null) continue
            val ref = "${m.owner}->${m.name}${m.protoDescriptor}"
            if (!codeful.add(ref)) continue
            total++
            if (modelled(m.owner)) { modelledCount++; continue }
            val info = MethodInfo(ref, d, m, byId.size)
            methods[ref] = info
            byId += info
        }
        methodCount = total
        // Decoded code is kept for apps of ordinary size; very large ones decode again on each visit.
        keepDecoded = byId.size <= 150_000
    }

    /** Well-known libraries whose behaviour is modelled at their API (their code is not analysed). */
    private fun modelled(owner: String) = MODELLED.any { owner.startsWith(it) }

    private fun ancestors(c: String): Set<String> = ancestorCache.getOrPut(c) {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(c))
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (!out.add(t)) continue
            superOf[t]?.let { queue += it }
            queue += interfacesOf[t].orEmpty()
        }
        out
    }
    private val ancestorCache = HashMap<String, Set<String>>()

    /** The class that declares field [f] (`Lsub;->x:I` may name a field of a superclass). */
    private fun normalizeField(f: String): String {
        val owner = f.substringBefore("->")
        val name = f.substringAfter("->").substringBefore(':')
        var c: String? = owner
        val seen = HashSet<String>()
        while (c != null && seen.add(c)) {
            if (name in declaredFields[c].orEmpty()) return if (c == owner) f else "$c->${f.substringAfter("->")}"
            c = superOf[c]
        }
        return f
    }

    /**
     * Whether a field may carry data across instances (field-based): its type holds a value, not a
     * container or a part of an object graph. `Object`, collections, interfaces, arrays and classes
     * holding other objects do not: shared across every instance, one tainted element would taint
     * every reader.
     */
    private fun concrete(type: String): Boolean = concreteCache.getOrPut(type) {
        when {
            // Flags, counters, sizes, timestamps and packed layout values (Z, B, C, S, I, F, J) are state,
            // not personal data; double holds coordinates. Measured on Tusky: AppCompat's night-mode
            // sunrise times (long, derived from location) reached Compose's packed longs everywhere.
            type.length == 1 -> type == "D"
            type.startsWith("[") -> false
            type in VALUE_TYPES -> true
            type.startsWith("Ljava/") || type.startsWith("Ljavax/") || type.startsWith("Lkotlin") || type.startsWith("Landroid") || type.startsWith("Lorg/json/") -> false
            else -> flatData(type)
        }
    }

    /**
     * A flat data class: concrete, with instance fields that all hold values (`LatLng(latitude,
     * longitude)`, `User(id, email)`). Classes that hold other objects (a layout node, a repository)
     * are parts of object graphs, tracked per instance only: renamed library internals made every
     * Compose object share its fields across instances.
     */
    private fun flatData(type: String): Boolean {
        val flags = classFlags[type] ?: return false
        if (flags and (AccessFlags.INTERFACE or AccessFlags.ABSTRACT) != 0) return false
        val fields = instanceFieldTypes[type].orEmpty()
        return fields.isNotEmpty() && fields.all { it.length == 1 || it in VALUE_TYPES }
    }
    private val concreteCache = HashMap<String, Boolean>()

    // ---- call resolution -------------------------------------------------------------------------

    private class Resolved(val internal: List<MethodInfo>)

    private val resolveCache = HashMap<String, Resolved>()

    /** Internal methods a call can run (class hierarchy analysis); empty: modelled or outside the DEX. */
    private fun resolve(ref: String, virtual: Boolean): Resolved = resolveCache.getOrPut(if (virtual) "v$ref" else ref) {
        val owner = ref.substringBefore("->")
        val sig = ref.substringAfter("->")
        fun upward(start: String): String? {
            var c: String? = start
            val seen = HashSet<String>()
            while (c != null && seen.add(c)) {
                if ("$c->$sig" in codeful) return "$c->$sig"
                c = superOf[c]
            }
            return null
        }
        val out = LinkedHashSet<MethodInfo>()
        upward(owner)?.let { methods[it]?.let(out::add) }
        if (virtual) {
            val queue = ArrayDeque(subtypes[owner].orEmpty())
            val seen = HashSet<String>()
            var count = 0
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (!seen.add(s)) continue
                if ("$s->$sig" in codeful) {
                    count++
                    if (count > MAX_TARGETS) return@getOrPut Resolved(emptyList())
                    methods["$s->$sig"]?.let(out::add)
                }
                queue += subtypes[s].orEmpty()
            }
        }
        Resolved(out.toList())
    }

    // ---- summaries ------------------------------------------------------------------------------

    private class SinkSite(val kind: String, val api: String, val detail: String, val method: String, val attention: String?) {
        val key = "$kind|$api|$detail|$method"
        /**
         * The sink without the API variant or the method that calls it (`LOG|log` for Log.d and Log.e,
         * `NETWORK|GET api/v1/...` per endpoint): summaries keep one edge per parameter and sink.
         */
        val apiKey = "$kind|$detail"
    }

    /** Labels of one parameter that reach one sink API, with the shortest call chain found. */
    private class Edge(val labels: IntArray, val site: SinkSite, val chain: List<String>)

    /** The parameter a symbolic label belongs to. */
    private fun paramOf(l: Int): Int = when {
        l < MAXP -> l
        l < MAXP * 2 -> l - MAXP
        else -> label(l).param
    }

    /** Adds [l] reaching [site] through [chain] to [sinks], merged per parameter and sink API. */
    private fun addEdge(sinks: HashMap<String, Edge>, l: Int, site: SinkSite, chain: List<String>) {
        val key = "${paramOf(l)}|${site.apiKey}"
        val old = sinks[key]
        sinks[key] = when {
            old == null -> Edge(intArrayOf(l), site, chain)
            chain.size < old.chain.size -> Edge(widen(union(old.labels, intArrayOf(l))), site, chain)
            else -> Edge(widen(union(old.labels, intArrayOf(l))), old.site, old.chain)
        }
    }

    private inner class Summary {
        var ret: RV = EMPTY_RV
        /** Whether any return was seen: the first sets [ret] as is, so its object identity (alias) survives. */
        var returns = false

        fun addReturn(v: RV) {
            ret = if (returns) join(ret, v) else v
            returns = true
        }
        val paramOut = HashMap<Int, RV>()
        val fieldWrites = HashMap<String, IntArray>()
        /** (parameter, sink API) -> the parameter's labels that reach it, with the shortest call chain. */
        val sinks = HashMap<String, Edge>()
        /** Some sink edges or field writes were dropped at the size bound. */
        var bounded = false

        fun same(o: Summary): Boolean = ret.same(o.ret) &&
            paramOut.size == o.paramOut.size && paramOut.all { (k, v) -> o.paramOut[k]?.same(v) == true } &&
            fieldWrites.size == o.fieldWrites.size && fieldWrites.all { (k, v) -> o.fieldWrites[k]?.contentEquals(v) == true } &&
            sinks.size == o.sinks.size && sinks.all { (k, e) -> o.sinks[k]?.labels?.contentEquals(e.labels) == true }
    }

    private val summaries = HashMap<Int, Summary>()
    private val global = HashMap<String, IntArray>()
    private val changedFields = HashSet<String>()

    private class Hit(val label: Int, val site: SinkSite, val chain: List<String>)
    private val hits = HashMap<String, Hit>()
    private val sourceUses = HashMap<String, MutableSet<String>>()

    private fun recordFlow(label: Int, site: SinkSite, chain: List<String>) {
        val key = "$label|${site.key}"
        val old = hits[key]
        if (old == null || chain.size < old.chain.size) hits[key] = Hit(label, site, chain)
    }

    // ---- driver ---------------------------------------------------------------------------------

    private val callers = HashMap<Int, MutableSet<Int>>()
    private val readers = HashMap<String, MutableSet<Int>>()
    private val callees = HashMap<Int, List<Int>>()

    fun run() {
        val started = System.nanoTime()
        // Call graph and field readers, for ordering and for re-analysis when something changes.
        for (mi in byId) {
            val code = mi.code() ?: continue
            val out = LinkedHashSet<Int>()
            val myRefs = refs.getOrPut(mi.owner) { HashSet() }
            for (i in code.insns) {
                val mn = i.op.mnemonic
                when {
                    i.op.index == IndexKind.METHOD && mn.startsWith("invoke") -> {
                        val ref = mi.d.methodDescriptor(i.index)
                        myRefs += ref
                        resolve(ref, mn.startsWith("invoke-virtual") || mn.startsWith("invoke-interface")).internal.forEach { out += it.id }
                    }
                    i.op.index == IndexKind.FIELD -> {
                        val f = mi.d.fieldDescriptor(i.index)
                        myRefs += f
                        if (mn.startsWith("iget") || mn.startsWith("sget")) readers.getOrPut(normalizeField(f)) { HashSet() } += mi.id
                    }
                }
            }
            callees[mi.id] = out.toList()
            for (c in out) callers.getOrPut(c) { HashSet() } += mi.id
        }
        // Capabilities also count the modelled libraries' references (AppCompat's location use).
        for ((_, d) in dex) for (m in d.methods()) {
            if (!modelled(m.owner) || m.code == null) continue
            val code = (InstructionDecoder.decode(d, m.code!!) as? Outcome.Ok)?.value ?: continue
            val myRefs = refs.getOrPut(m.owner) { HashSet() }
            for (i in code.insns) when (i.op.index) {
                IndexKind.METHOD -> myRefs += d.methodDescriptor(i.index)
                IndexKind.FIELD -> myRefs += d.fieldDescriptor(i.index)
                else -> Unit
            }
        }
        order()
        val queue = PriorityQueue<Int>(compareBy { byId[it].order })
        val queued = java.util.BitSet()
        for (mi in byId) { queue += mi.id; queued.set(mi.id) }
        val deadline = started + budgetMs * 1_000_000
        val visits = IntArray(byId.size)
        while (queue.isNotEmpty()) {
            if (System.nanoTime() > deadline) { truncated = true; break }
            val id = queue.poll()
            queued.clear(id)
            val mi = byId[id]
            if (++visits[id] > MAX_VISITS) { truncated = true; continue }
            changedFields.clear()
            val fresh = analyze(mi) ?: continue
            analyses++
            val old = summaries[id]
            // Summaries only grow: joined with the previous one, so the iteration terminates even
            // where a rule is not monotone (object identity known on one pass, not on the next).
            val s = if (old == null) fresh else old.joinedWith(fresh)
            if (fresh.bounded || s.bounded) boundedMethods.set(id)
            if (WATCH != null && WATCH in mi.ref && visits[id] < 8) System.err.println("watch ${mi.ref} #${visits[id]}: ${describe(s)}")
            if (old == null || !s.same(old)) {
                summaries[id] = s
                if (old != null || !s.isTrivial()) callers[id]?.forEach { c -> if (!queued.get(c)) { queued.set(c); queue += c } }
            } else {
                summaries[id] = s
            }
            for (f in changedFields) readers[f]?.forEach { r -> if (!queued.get(r)) { queued.set(r); queue += r } }
            if (DEBUG && analyses % 2000 == 0) System.err.println("privacy: $analyses analyses, ${(System.nanoTime() - started) / 1_000_000} ms, queue ${queue.size}, labels ${labels.size}, globals ${global.size}")
        }
        if (DEBUG) {
            System.err.println("privacy: done $analyses analyses of ${byId.size} methods in ${(System.nanoTime() - started) / 1_000_000} ms; labels ${labels.size}; globals ${global.size}")
            visits.withIndex().sortedByDescending { it.value }.take(15).forEach { (i, v) -> System.err.println("  $v x ${byId[i].ref} (${byId[i].code()?.insns?.size} insns)") }
            val big = summaries.entries.sortedByDescending { (_, x) -> x.sinks.size + x.ret.whole.size * 10 }.take(10)
            for ((i, x) in big) System.err.println("  summary ${byId[i].ref}: ret ${x.ret.whole.size} labels, fields ${x.ret.fields?.size}, sinks ${x.sinks.size}, paramOut ${x.paramOut.size}, fieldWrites ${x.fieldWrites.size}")
            val boundedKinds = summaries.filterKeys { boundedMethods.get(it) }.values.flatMap { x -> x.sinks.values.map { it.site.kind + " " + it.site.detail.take(30) } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)
            System.err.println("  sinks in bounded summaries: $boundedKinds; field writes in bounded: ${summaries.filterKeys { boundedMethods.get(it) }.values.sumOf { it.fieldWrites.size }}")
            System.err.println("  global field types: " + global.keys.groupingBy { it.substringAfterLast(':') }.eachCount().entries.sortedByDescending { it.value }.take(15))
            global.keys.take(30).forEach { System.err.println("    $it") }
            System.err.println("  sink edges total ${summaries.values.sumOf { it.sinks.size }}, ret labels total ${summaries.values.sumOf { it.ret.whole.size }}, param-field labels ${labels.count { it.kind == PARAM_FIELD }}")
        }
    }
    private val DEBUG = System.getenv("APKPROJECT_PRIVACY_DEBUG") != null
    private val WATCH = System.getenv("APKPROJECT_PRIVACY_WATCH")
    private fun lname(l: Int): String = when {
        isParam(l) -> "P$l"
        isParamAll(l) -> "P${l - MAXP}*"
        else -> label(l).let { x -> when (x.kind) { PARAM_FIELD -> "P${x.param}.${x.field!!.substringAfter("->").substringBefore(':')}"; SOURCE -> "S(${x.data})"; else -> "M(${x.data})" } }
    }
    private fun describe(v: RV) = "{" + v.whole.joinToString(",") { lname(it) } + (v.fields?.entries?.joinToString(",", " f[", "]") { (k, x) -> k.substringAfter("->").substringBefore(':') + "=" + x.joinToString("|") { lname(it) } } ?: "") + " a=${v.alias}}"
    private fun describe(s: Summary) = "ret=${describe(s.ret)} out=${s.paramOut.entries.joinToString { "${it.key}:${describe(it.value)}" }} fw=${s.fieldWrites.size} sinks=${s.sinks.size}"

    /**
     * Bounds a summary deterministically: the sink edges and field writes with the smallest keys.
     * Candidates only grow between analyses, so the kept set changes finitely often.
     */
    private fun Summary.bounded(): Summary {
        if (sinks.size > MAX_EDGES) {
            val keep = sinks.keys.sorted().take(MAX_EDGES).toSet()
            sinks.keys.retainAll(keep)
            bounded = true
        }
        if (fieldWrites.size > MAX_FIELD_WRITES) {
            val keep = fieldWrites.keys.sorted().take(MAX_FIELD_WRITES).toSet()
            fieldWrites.keys.retainAll(keep)
            bounded = true
        }
        return this
    }
    private val boundedMethods = java.util.BitSet()

    /** Methods whose summary reached a size bound (some sink edges or field writes dropped). */
    val boundedCount: Int get() = boundedMethods.cardinality()

    private fun Summary.joinedWith(o: Summary): Summary {
        val out = Summary()
        if (returns) out.addReturn(ret)
        if (o.returns) out.addReturn(o.ret)
        for (k in paramOut.keys + o.paramOut.keys) out.paramOut[k] = join(paramOut[k] ?: EMPTY_RV, o.paramOut[k] ?: EMPTY_RV)
        for (k in fieldWrites.keys + o.fieldWrites.keys) out.fieldWrites[k] = widen(union(fieldWrites[k], o.fieldWrites[k]))
        for (e in sinks.values + o.sinks.values) for (l in e.labels) addEdge(out.sinks, l, e.site, e.chain)
        return out.bounded()
    }

    private fun Summary.isTrivial() = ret.isEmpty() && ret.alias !in 0 until MAXP && paramOut.isEmpty() && fieldWrites.isEmpty() && sinks.isEmpty()

    /** Callees before callers: reverse topological order of the strongly connected components (iterative Tarjan). */
    private fun order() {
        val n = byId.size
        val index = IntArray(n) { -1 }
        val low = IntArray(n)
        val onStack = BooleanArray(n)
        val stack = ArrayDeque<Int>()
        var counter = 0
        var component = 0
        for (root in 0 until n) {
            if (index[root] >= 0) continue
            val work = ArrayDeque<IntArray>() // (node, next child position)
            work.addLast(intArrayOf(root, 0))
            index[root] = counter; low[root] = counter; counter++
            stack.addLast(root); onStack[root] = true
            while (work.isNotEmpty()) {
                val top = work.last()
                val v = top[0]
                val kids = callees[v].orEmpty()
                if (top[1] < kids.size) {
                    val w = kids[top[1]++]
                    if (index[w] < 0) {
                        index[w] = counter; low[w] = counter; counter++
                        stack.addLast(w); onStack[w] = true
                        work.addLast(intArrayOf(w, 0))
                    } else if (onStack[w]) low[v] = minOf(low[v], index[w])
                } else {
                    work.removeLast()
                    if (work.isNotEmpty()) { val p = work.last()[0]; low[p] = minOf(low[p], low[v]) }
                    if (low[v] == index[v]) {
                        while (true) {
                            val w = stack.removeLast()
                            onStack[w] = false
                            byId[w].order = component
                            if (w == v) break
                        }
                        component++
                    }
                }
            }
        }
    }

    // ---- intraprocedural analysis ----------------------------------------------------------------

    private class Cfg(val starts: IntArray, val ends: IntArray, val succ: Array<IntArray>)
    private val cfgs = HashMap<Int, Cfg>()

    private fun cfg(mi: MethodInfo, code: DecodedCode): Cfg = cfgs.getOrPut(mi.id) {
        val insns = code.insns
        val idx = HashMap<Int, Int>(insns.size * 2)
        insns.forEachIndexed { k, i -> idx[i.address] = k }
        val leaders = java.util.TreeSet<Int>().apply { add(0) }
        for ((k, i) in insns.withIndex()) {
            if (i.target >= 0 && !i.op.payloadRef) idx[i.target]?.let { leaders += it }
            if (i.op.flow == FlowKind.SWITCH) code.switchCases(i).forEach { (_, a) -> idx[a]?.let { leaders += it } }
            if (i.op.flow != FlowKind.NONE && k + 1 < insns.size) leaders += k + 1
        }
        val handlerTargets = ArrayList<Triple<Int, Int, List<Int>>>()
        for (t in code.code.tries) {
            val h = code.code.handlerFor(t)
            val targets = (h.typed.map { it.second } + listOfNotNull(h.catchAllAddr.takeIf { it >= 0 })).mapNotNull { idx[it] }
            targets.forEach { leaders += it }
            val first = idx[t.startAddr] ?: continue
            val end = idx[t.startAddr + t.insnCount] ?: insns.size
            leaders += first
            if (end < insns.size) leaders += end
            handlerTargets += Triple(first, end, targets)
        }
        val starts = leaders.filter { it < insns.size }.toIntArray()
        val blockOf = IntArray(insns.size)
        for (b in starts.indices) for (k in starts[b] until (starts.getOrNull(b + 1) ?: insns.size)) blockOf[k] = b
        val ends = IntArray(starts.size) { (starts.getOrNull(it + 1) ?: insns.size) }
        val succ = Array(starts.size) { b ->
            val last = insns[ends[b] - 1]
            val out = HashSet<Int>()
            if (last.op.flow !in setOf(FlowKind.GOTO, FlowKind.RETURN, FlowKind.THROW) && ends[b] < insns.size) out += blockOf[ends[b]]
            if (last.target >= 0 && !last.op.payloadRef) idx[last.target]?.let { out += blockOf[it] }
            if (last.op.flow == FlowKind.SWITCH) code.switchCases(last).forEach { (_, a) -> idx[a]?.let { out += blockOf[it] } }
            for ((first, end, targets) in handlerTargets) if (starts[b] in first until end) targets.forEach { out += blockOf[it] }
            out.toIntArray()
        }
        Cfg(starts, ends, succ)
    }

    private inner class Ctx(val mi: MethodInfo, val d: DexFile, val code: DecodedCode, val summary: Summary) {
        val regs = code.code.registersSize
    }

    private fun analyze(mi: MethodInfo): Summary? {
        val code = mi.code() ?: return null
        val g = cfg(mi, code)
        val ctx = Ctx(mi, mi.d, code, Summary())
        val n = g.starts.size
        val inState = arrayOfNulls<Array<RV>>(n)
        inState[0] = entry(mi, ctx.regs)
        val work = ArrayDeque<Int>().apply { add(0) }
        val inWork = BooleanArray(n).apply { this[0] = true }
        var steps = 0
        while (work.isNotEmpty()) {
            if (++steps > n * 40 + 1000) break
            val b = work.removeFirst()
            inWork[b] = false
            val state = inState[b]!!.copyOf()
            for (k in g.starts[b] until g.ends[b]) transfer(ctx, k, state)
            for (s in g.succ[b]) {
                val cur = inState[s]
                val next = if (cur == null) state.copyOf() else {
                    var changed = false
                    val merged = Array(cur.size) { r -> join(cur[r], state[r]).also { if (!it.same(cur[r])) changed = true } }
                    if (!changed) continue
                    merged
                }
                inState[s] = next
                if (!inWork[s]) { inWork[s] = true; work.addLast(s) }
            }
        }
        return ctx.summary.bounded()
    }

    /** Parameters hold their symbolic labels; a platform callback's parameter is also a source. */
    private fun entry(mi: MethodInfo, regs: Int): Array<RV> {
        val state = Array(regs + 1) { EMPTY_RV }
        val sig = mi.ref.substringAfter("->")
        val callback = CALLBACK_SOURCES[sig]
        for ((i, r) in mi.paramRegs.withIndex()) {
            if (i >= MAXP || r >= regs) break
            var whole = intArrayOf(i)
            if (callback != null && i == callback.second) {
                whole = union(whole, intArrayOf(source(callback.first, "callback ${sig.substringBefore('(')}", mi.ref)))
                sourceUses.getOrPut("${callback.first}|callback ${sig.substringBefore('(')}") { LinkedHashSet() } += mi.ref
            }
            state[r] = RV(whole, null, i)
        }
        return state
    }

    // ---- transfer -------------------------------------------------------------------------------

    private fun transfer(ctx: Ctx, k: Int, state: Array<RV>) {
        val i = ctx.code.insns[k]
        val mn = i.op.mnemonic
        val regs = ctx.regs
        val result = regs // the pseudo-register move-result reads
        fun set(r: Int, v: RV) {
            if (r < regs) state[r] = v
            if (i.op.wide.firstOrNull() == 'W' && r + 1 < regs) state[r + 1] = EMPTY_RV
        }
        when {
            mn == "nop" || mn.startsWith("goto") || mn.startsWith("if-") || mn.endsWith("-switch") || mn.startsWith("monitor-") ||
                mn == "throw" || mn == "fill-array-data" || mn == "return-void" || mn == "check-cast" -> Unit
            mn.startsWith("return") -> ctx.summary.addReturn(state[i.regs[0]])
            mn == "move-exception" -> set(i.regs[0], EMPTY_RV)
            mn.startsWith("move-result") -> set(i.regs[0], state[result])
            mn.startsWith("move") -> set(i.regs[0], state[i.regs[1]])
            mn.startsWith("const") -> set(i.regs[0], EMPTY_RV)
            mn == "instance-of" || mn == "array-length" -> set(i.regs[0], EMPTY_RV)
            mn == "new-instance" || mn == "new-array" -> set(i.regs[0], RV(EMPTY, null, -2 - k))
            mn.startsWith("filled-new-array") -> {
                var w = EMPTY
                i.regs.forEach { w = union(w, flatten(state[it])) }
                state[result] = rv(w)
            }
            mn.startsWith("aget") -> set(i.regs[0], rv(flatten(state[i.regs[1]])))
            mn.startsWith("aput") -> mutate(ctx, state, i.regs[1], flatten(state[i.regs[0]]), null)
            mn.startsWith("iget") -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                val base = state[i.regs[1]]
                val fieldPart = union(base.fields?.get(f), globalOf(f))
                // A field of a parameter's object is its own symbolic label: the caller knows that field's labels.
                val w = if (base.alias in 0 until MAXP) union(union(fieldPart, base.whole.filter { it != base.alias }.toIntArray()), intArrayOf(paramField(base.alias, f)))
                else union(fieldPart, base.whole)
                set(i.regs[0], rv(w, holder = base.alias, holderField = f.takeIf { base.alias != -1 }))
            }
            mn.startsWith("iput") -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                val value = flatten(state[i.regs[0]])
                if (value.isNotEmpty()) {
                    if (concrete(f.substringAfterLast(':'))) writeGlobal(ctx, f, value)
                    mutate(ctx, state, i.regs[1], null, f to value)
                }
            }
            mn.startsWith("sget") -> {
                val f = ctx.d.fieldDescriptor(i.index)
                set(i.regs[0], rv(union(globalOf(normalizeField(f)), staticSource(ctx, f))))
            }
            mn.startsWith("sput") -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                val value = flatten(state[i.regs[0]])
                if (value.isNotEmpty() && concrete(f.substringAfterLast(':'))) writeGlobal(ctx, f, value)
            }
            mn.startsWith("invoke") -> invoke(ctx, k, i, state)
            else -> {
                // Arithmetic, conversions, comparisons: the result depends on the operands.
                val reads = dev.apkproject.lift.DataflowShape.reads(i)
                var w = EMPTY
                for (r in reads) if (r < regs) w = union(w, state[r].whole)
                if (i.regs.isNotEmpty() && dev.apkproject.lift.DataflowShape.writes(i, regs).isNotEmpty()) set(i.regs[0], rv(w))
            }
        }
    }

    private fun globalOf(f: String): IntArray = global[f] ?: EMPTY

    private fun writeGlobal(ctx: Ctx, f: String, labels: IntArray) {
        // Data read back from a shared field is not stored into another one (see viaField).
        val concrete = labels.filter { isSource(it) && label(it).field == null && label(it).data !in HEAP_LOCAL }.map { viaField(it, f) }.sorted().toIntArray()
        val symbolic = labels.filter { isSymbolic(it) }.toIntArray()
        if (concrete.isNotEmpty()) {
            val old = global[f] ?: EMPTY
            val merged = union(old, concrete)
            if (merged.size != old.size) { global[f] = merged; changedFields += f }
        }
        if (symbolic.isNotEmpty()) ctx.summary.fieldWrites[f] = widen(union(ctx.summary.fieldWrites[f], symbolic))
    }

    /**
     * Adds labels to the object in register [r] (to its value, or to one of its fields), and to
     * every register holding the same object; a parameter's object is reported in the summary.
     */
    private fun mutate(ctx: Ctx, state: Array<RV>, r: Int, whole: IntArray?, field: Pair<String, IntArray>?) {
        if (r < 0 || r >= ctx.regs) return
        if ((whole == null || whole.isEmpty()) && (field == null || field.second.isEmpty())) return
        val target = state[r]
        fun apply(v: RV): RV {
            val fields = if (field == null) v.fields else HashMap(v.fields.orEmpty()).also { it[field.first] = union(it[field.first], field.second) }
            return rv(if (whole == null) v.whole else union(v.whole, whole), v.alias, fields, v.holder, v.holderField)
        }
        if (target.alias != -1) {
            for (x in 0 until ctx.regs) if (state[x].alias == target.alias) state[x] = apply(state[x])
        } else {
            state[r] = apply(target)
        }
        if (target.alias in 0 until MAXP) {
            val old = ctx.summary.paramOut[target.alias] ?: EMPTY_RV
            ctx.summary.paramOut[target.alias] = join(old, rv(whole ?: EMPTY, -1, field?.let { mapOf(it.first to it.second) }))
        }
        // Read from a field of another object (`this.sb`): that field of the holder now carries the labels too.
        val holderField = target.holderField
        if (target.holder != -1 && holderField != null) {
            val added = union(whole, field?.second)
            for (x in 0 until ctx.regs) if (state[x].alias == target.holder) {
                val v = state[x]
                state[x] = rv(v.whole, v.alias, HashMap(v.fields.orEmpty()).also { it[holderField] = union(it[holderField], added) }, v.holder, v.holderField)
            }
            if (target.holder in 0 until MAXP) {
                val old = ctx.summary.paramOut[target.holder] ?: EMPTY_RV
                ctx.summary.paramOut[target.holder] = join(old, rv(EMPTY, -1, mapOf(holderField to added)))
            }
        }
        checkMarks(ctx, state[r])
    }

    /** A stream to the network or a file that now carries data: written out. */
    private fun checkMarks(ctx: Ctx, v: RV) {
        val all = flatten(v)
        val marks = all.filter { isMark(it) && label(it).data in setOf("net", "file") }
        if (marks.isEmpty()) return
        val data = all.filter { isSource(it) || isSymbolic(it) }
        if (data.isEmpty()) return
        for (m in marks) {
            val l = label(m)
            val site = if (l.data == "net") SinkSite("NETWORK", l.api!!, "HTTP request body or socket", ctx.mi.ref, null)
            else SinkSite("STORAGE", l.api!!, "file", ctx.mi.ref, null)
            record(ctx, data.toIntArray(), site)
        }
    }

    /** Labels that reach a sink in this method: concrete ones are flows, symbolic ones go in the summary. */
    private fun record(ctx: Ctx, labels: IntArray, site: SinkSite) {
        for (l in labels) {
            when {
                isSource(l) -> recordFlow(l, site, listOf(ctx.mi.ref))
                isSymbolic(l) -> {
                    addEdge(ctx.summary.sinks, l, site, listOf(ctx.mi.ref))
                }
            }
        }
    }

    /** `Build.SERIAL`, and content URIs of personal data (`ContactsContract.Contacts.CONTENT_URI`). */
    private fun staticSource(ctx: Ctx, f: String): IntArray {
        if (f == "Landroid/os/Build;->SERIAL:Ljava/lang/String;") {
            sourceUses.getOrPut("device-id|android.os.Build.SERIAL") { LinkedHashSet() } += ctx.mi.ref
            return intArrayOf(source("device-id", "android.os.Build.SERIAL", ctx.mi.ref))
        }
        if (!f.endsWith(":Landroid/net/Uri;")) return EMPTY
        val owner = f.substringBefore("->")
        val data = URI_OWNERS.entries.firstOrNull { owner.startsWith(it.key) }?.value ?: return EMPTY
        return intArrayOf(mark("uri:$data", display(f.substringBefore(':'))))
    }

    // ---- calls ----------------------------------------------------------------------------------

    private fun invoke(ctx: Ctx, k: Int, i: Insn, state: Array<RV>) {
        val regs = ctx.regs
        val mn = i.op.mnemonic
        if (i.op.index != IndexKind.METHOD) {
            // invoke-custom: arguments flow to the result.
            var w = EMPTY
            i.regs.forEach { if (it < regs) w = union(w, flatten(state[it])) }
            state[regs] = rv(w)
            return
        }
        val ref = ctx.d.methodDescriptor(i.index)
        val static = mn.startsWith("invoke-static")
        val proto = ctx.d.protos[ctx.d.methodIds[i.index].protoIdx]
        val types = (if (static) emptyList() else listOf(ref.substringBefore("->"))) + proto.parameters.map { ctx.d.type(it) }
        val argRegs = IntArray(types.size)
        var slot = 0
        for ((p, t) in types.withIndex()) {
            argRegs[p] = i.regs.getOrElse(slot) { -1 }
            slot += if (t == "J" || t == "D") 2 else 1
        }
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        val owner = ref.substringBefore("->")
        val name = ref.substringAfter("->").substringBefore('(')
        val returnType = ctx.d.type(proto.returnType)
        val firstArg = if (static) 0 else 1

        // Sinks: endpoints first, then the API table.
        endpoints[ref]?.let { endpoint ->
            val site = SinkSite("NETWORK", display(ref), endpoint, ctx.mi.ref, null)
            for (p in firstArg until types.size) record(ctx, flatten(arg(p)), site)
        }
        for (spec in sinkSpecs(owner, name, static)) {
            // An inherited platform method is named by its platform class, not the app class it was called on.
            val api = if (spec.owner == owner) display(ref) else display("${spec.owner}->$name")
            val site = SinkSite(spec.kind, api, spec.detail, ctx.mi.ref, spec.attention)
            val params = spec.params ?: (firstArg until types.size).toList()
            for (p in params) if (p < types.size) record(ctx, flatten(arg(p)), site)
        }

        // Internal code: instantiate the summaries of every target.
        val resolved = resolve(ref, mn.startsWith("invoke-virtual") || mn.startsWith("invoke-interface"))
        var result: RV = EMPTY_RV
        if (resolved.internal.isNotEmpty()) {
            var first = true
            for (t in resolved.internal) {
                val s = summaries[t.id] ?: continue
                val r = instantiate(ctx, s, argRegs, state, k)
                result = if (first) r else join(result, r)
                first = false
            }
        } else {
            result = external(ctx, k, ref, owner, name, returnType, static, argRegs, state)
        }

        // Sources replace or add to the result.
        sourceOf(ctx, k, ref, owner, name, static, argRegs, state)?.let { (data, api) ->
            val l = source(data, api, ctx.mi.ref)
            sourceUses.getOrPut("$data|$api") { LinkedHashSet() } += ctx.mi.ref
            val buffer = ARG_SOURCES["$owner->$name"]
            if (buffer != null) mutate(ctx, state, argRegs.getOrElse(buffer) { -1 }, intArrayOf(l), null)
            else result = rv(union(result.whole, intArrayOf(l)), result.alias, result.fields)
        }
        // Streams to the network or a file are marked, to recognise data written to them.
        STREAM_MARKS["$owner->$name"]?.let { kind -> result = rv(union(result.whole, intArrayOf(mark(kind, display(ref)))), result.alias, result.fields) }
        if (name == "<init>" && !static) STREAM_MARKS["$owner-><init>"]?.let { kind -> mutate(ctx, state, argRegs[0], intArrayOf(mark(kind, display(ref))), null) }
        if (owner == "Landroid/net/Uri;" && name == "parse") constString(ctx, k, argRegs.getOrElse(0) { -1 })?.let { s ->
            uriData(s)?.let { result = rv(union(result.whole, intArrayOf(mark("uri:$it", "Uri.parse(\"${s.take(60)}\")"))), result.alias, result.fields) }
        }
        if (name == "openFileOutput" && isContext(owner)) result = rv(union(result.whole, intArrayOf(mark("file", "Context.openFileOutput"))), result.alias, result.fields)
        state[regs] = result
    }

    /** A callee's summary at this call: its parameter labels replaced by what the arguments carry. */
    private fun instantiate(ctx: Ctx, s: Summary, argRegs: IntArray, state: Array<RV>, k: Int): RV {
        val regs = ctx.regs
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        fun subst(labels: IntArray): IntArray {
            if (labels.isEmpty()) return EMPTY
            var out = EMPTY
            val concrete = ArrayList<Int>()
            for (l in labels) {
                when {
                    isParam(l) -> out = union(out, arg(l).whole)
                    isParamAll(l) -> out = union(out, flatten(arg(l - MAXP)))
                    l >= MAXP * 2 && label(l).kind == PARAM_FIELD -> {
                        val lb = label(l)
                        val a = arg(lb.param)
                        out = union(out, a.whole)
                        a.fields?.get(lb.field!!)?.let { out = union(out, it) }
                        if (a.alias in 0 until MAXP) out = union(out.filter { it != a.alias }.toIntArray(), intArrayOf(paramField(a.alias, lb.field!!)))
                    }
                    else -> concrete += l
                }
            }
            return if (concrete.isEmpty()) out else union(out, concrete.sorted().toIntArray())
        }
        // Return value: the same object as an argument (a builder returning itself), plus what the summary adds.
        val ret = s.ret
        var result = if (ret.alias in 0 until MAXP) arg(ret.alias) else EMPTY_RV
        val retFields = ret.fields?.mapValues { subst(it.value) }
        result = rv(union(result.whole, subst(ret.whole)), result.alias, if (retFields == null) result.fields else HashMap(result.fields.orEmpty()).also { m -> retFields.forEach { (f, v) -> m[f] = union(m[f], v) } })
        // Objects the callee changed.
        for ((p, delta) in s.paramOut) {
            val r = argRegs.getOrNull(p) ?: continue
            if (r !in 0 until regs) continue
            val w = subst(delta.whole)
            if (w.isNotEmpty()) mutate(ctx, state, r, w, null)
            delta.fields?.forEach { (f, v) -> subst(v).takeIf { it.isNotEmpty() }?.let { mutate(ctx, state, r, null, f to it) } }
        }
        // Fields it writes.
        for ((f, v) in s.fieldWrites) subst(v).takeIf { it.isNotEmpty() }?.let { writeGlobal(ctx, f, it) }
        // Parameters that reach a sink inside it.
        for (e in s.sinks.values) {
            val chain = if (e.chain.size >= MAX_CHAIN) e.chain else listOf(ctx.mi.ref) + e.chain
            for (l in subst(e.labels)) when {
                isSource(l) -> recordFlow(l, e.site, chain)
                isSymbolic(l) -> addEdge(ctx.summary.sinks, l, e.site, chain)
            }
        }
        return result
    }

    /** Library and platform code: data flows from the receiver and arguments to the result; containers and builders take in what is added to them. */
    private fun external(ctx: Ctx, k: Int, ref: String, owner: String, name: String, returnType: String, static: Boolean, argRegs: IntArray, state: Array<RV>): RV {
        val regs = ctx.regs
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        val first = if (static) 0 else 1
        var args = EMPTY
        for (p in first until argRegs.size) args = union(args, flatten(arg(p)))
        val receiver = if (static) EMPTY_RV else arg(0)
        if (!static && args.isNotEmpty() && absorbs(owner, name)) mutate(ctx, state, argRegs[0], args, null)
        if (returnType == "V" || returnType == "Z" || name in NON_DATA) return EMPTY_RV
        // toString describes the object's fields.
        if (name == "toString" && !static) return rv(union(flatten(receiver), args))
        val after = if (static) EMPTY_RV else state.getOrElse(argRegs[0]) { EMPTY_RV }
        // A builder returns itself: the same object, with what it took in.
        if (!static && returnType == owner && absorbs(owner, name)) return after
        return rv(union(receiver.whole, args))
    }

    private fun absorbs(owner: String, name: String): Boolean = when {
        name == "<init>" -> true
        owner == "Ljava/lang/StringBuilder;" || owner == "Ljava/lang/StringBuffer;" -> name in setOf("append", "insert", "replace", "setCharAt")
        owner.endsWith("\$Builder;") || owner.endsWith("Builder;") -> !name.startsWith("build")
        owner.startsWith("Ljava/util/") || owner.startsWith("Lkotlin/collections/") -> name in MUTATORS
        owner.startsWith("Ljava/io/") -> name.startsWith("write") || name.startsWith("print") || name == "append" || name == "format"
        owner == "Landroid/content/ContentValues;" || owner == "Landroid/os/Bundle;" || owner == "Landroid/os/BaseBundle;" || owner == "Landroid/os/PersistableBundle;" -> name.startsWith("put")
        owner == "Landroid/content/Intent;" -> name.startsWith("put") || name.startsWith("set") || name == "fillIn" || name == "replaceExtras"
        owner.startsWith("Lorg/json/") -> name == "put" || name.startsWith("put") || name == "accumulate" || name == "append"
        owner.startsWith("Lcom/google/gson/Json") -> name == "add" || name == "addProperty" || name == "set"
        owner == "Landroid/os/Parcel;" -> name.startsWith("write")
        else -> false
    }

    private fun isContext(owner: String) = ancestors(owner).any { it in CONTEXT_TYPES }

    private class SinkSpec(val kind: String, val owner: String, val names: Set<String>?, val params: List<Int>?, val detail: String, val attention: String? = null)

    private val sinkCache = HashMap<String, List<SinkSpec>>()

    private fun sinkSpecs(owner: String, name: String, static: Boolean): List<SinkSpec> = sinkCache.getOrPut("$owner->$name") {
        val direct = SINKS_BY_OWNER[owner].orEmpty().filter { it.names == null || name in it.names }
        if (direct.isNotEmpty() || static) return@getOrPut direct
        // Inherited platform methods called on an app class (`this.startActivity` in an activity, a WebView subclass).
        val ancestors = ancestors(owner)
        INHERITED_SINKS.filter { s -> (s.names == null || name in s.names) && s.owner in ancestors }.distinctBy { it.kind + "|" + it.detail }
    }

    private fun sourceOf(ctx: Ctx, k: Int, ref: String, owner: String, name: String, static: Boolean, argRegs: IntArray, state: Array<RV>): Pair<String, String>? {
        SOURCES["$owner->$name"]?.let { data ->
            // A text field reading the clipboard is the user pasting: user input, not a source.
            if (data == "clipboard" && ancestors(ctx.mi.owner).any { it in TEXT_VIEWS }) return null
            return data to display(ref)
        }
        when {
            owner == "Landroid/provider/Settings\$Secure;" || owner == "Landroid/provider/Settings\$System;" ->
                if (name == "getString" && constString(ctx, k, argRegs.getOrElse(1) { -1 }) == "android_id") return "device-id" to "Settings.Secure.getString(ANDROID_ID)"
            (owner == "Landroid/content/ContentResolver;" || owner == "Landroid/content/ContentProviderClient;") && name == "query" -> {
                val uri = argRegs.getOrElse(if (static) 0 else 1) { -1 }
                val labels = if (uri in 0 until ctx.regs) flatten(state[uri]) else EMPTY
                val m = labels.firstOrNull { isMark(it) && label(it).data!!.startsWith("uri:") } ?: return null
                return label(m).data!!.removePrefix("uri:") to "ContentResolver.query(${label(m).api})"
            }
        }
        return null
    }

    /** The data category of a constant content URI (`content://com.android.contacts/...`). */
    private fun uriData(s: String): String? {
        if (!s.startsWith("content://")) return null
        val authority = s.removePrefix("content://").substringBefore('/')
        return when (authority) {
            "com.android.contacts", "contacts" -> "contacts"
            "sms", "mms", "mms-sms" -> "sms"
            "call_log" -> "call-log"
            "com.android.calendar" -> "calendar"
            else -> null
        }
    }

    /** The string constant last written to register [r] before instruction [k] in straight-line code. */
    private fun constString(ctx: Ctx, k: Int, r: Int): String? {
        if (r < 0) return null
        for (j in k - 1 downTo maxOf(0, k - 24)) {
            val x = ctx.code.insns[j]
            if (x.op.flow != FlowKind.NONE) return null
            if (x.regs.isNotEmpty() && x.regs[0] == r && dev.apkproject.lift.DataflowShape.writes(x, ctx.regs).contains(r)) {
                return if (x.op.index == IndexKind.STRING) ctx.d.string(x.index) else null
            }
        }
        return null
    }

    // ---- results --------------------------------------------------------------------------------

    fun flows(permissions: Set<String>?): List<PrivacyFlow> {
        val out = ArrayList<PrivacyFlow>()
        // One flow per source call and sink call: the direct way if there is one, else the shortest.
        val best = HashMap<String, Hit>()
        for (h in hits.values) {
            val src = label(h.label)
            val key = "${src.data}|${src.api}|${src.method}|${h.site.key}"
            val old = best[key]
            if (old == null || (label(old.label).field != null && src.field == null) || (label(old.label).field == null) == (src.field == null) && h.chain.size < old.chain.size) best[key] = h
        }
        for (h in best.values) {
            val src = label(h.label)
            val data = src.data!!
            val chain = h.chain
            val meet = chain.first()
            val declared = declared(src.api!!, data, permissions)
            out += PrivacyFlow(
                data, PrivacyMap.TITLES[data] ?: data, src.api, display(src.method!!), h.site.kind, h.site.api, h.site.detail, display(h.site.method),
                chain.map(::display), if (src.field != null) emptyList() else sourcePath(meet, src.method).map(::display),
                if (declared == false) "LOW" else attention(data, h.site), codeOf(h.site.method), src.field?.let { display(it.substringBefore(':')) }, declared,
            )
        }
        return out.sortedWith(compareBy({ ATTENTION.indexOf(it.attention) }, { it.data }, { it.sink }, { it.sinkDetail }, { it.sourceIn }))
    }

    fun sources(flows: List<PrivacyFlow>, permissions: Set<String>?): List<PrivacySource> = sourceUses.entries.map { (key, users) ->
        val data = key.substringBefore('|')
        val api = key.substringAfter('|')
        PrivacySource(
            data, PrivacyMap.TITLES[data] ?: data, api, users.map(::display).sorted().take(20), flows.any { it.data == data && it.sourceApi == api },
            permissionsFor(api, data), declared(api, data, permissions),
        )
    }.sortedWith(compareBy({ it.data }, { it.api }))

    /** Runtime permissions the source API needs (any one of them); empty when it needs none. */
    private fun permissionsFor(api: String, data: String): List<String> = when {
        api.startsWith("Settings.Secure") || api.endsWith("WifiInfo.getMacAddress") || api.endsWith("NetworkInterface.getHardwareAddress") || api == "android.os.Build.SERIAL" -> emptyList()
        api.endsWith("getLine1Number") || api.endsWith("getPhoneNumber") || api.endsWith("SubscriptionInfo.getNumber") -> listOf("READ_PHONE_STATE", "READ_PHONE_NUMBERS", "READ_SMS")
        api.endsWith("BluetoothAdapter.getAddress") -> listOf("BLUETOOTH_CONNECT", "LOCAL_MAC_ADDRESS")
        data == "device-id" -> listOf("READ_PHONE_STATE", "READ_PRIVILEGED_PHONE_STATE")
        data == "location" -> listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")
        data == "contacts" -> listOf("READ_CONTACTS")
        data == "call-log" -> listOf("READ_CALL_LOG")
        data == "sms" -> listOf("READ_SMS")
        data == "calendar" -> listOf("READ_CALENDAR")
        data == "audio" -> listOf("RECORD_AUDIO")
        data == "camera" -> listOf("CAMERA")
        data == "wifi" -> listOf("ACCESS_WIFI_STATE")
        else -> emptyList()
    }

    private fun declared(api: String, data: String, permissions: Set<String>?): Boolean? {
        val needed = permissionsFor(api, data)
        if (needed.isEmpty() || permissions == null) return null
        return needed.any { "android.permission.$it" in permissions }
    }

    /** Calls from [from] down to [to], when the data came back through return values (breadth-first, bounded). */
    private fun sourcePath(from: String, to: String): List<String> {
        if (from == to) return listOf(from)
        val start = methods[from] ?: return emptyList()
        val parent = HashMap<Int, Int>()
        val queue = ArrayDeque(listOf(start.id))
        parent[start.id] = -1
        var depth = 0
        while (queue.isNotEmpty() && depth < 10) {
            repeat(queue.size) {
                val v = queue.removeFirst()
                for (w in callees[v].orEmpty()) {
                    if (w in parent) continue
                    parent[w] = v
                    if (byId[w].ref == to) {
                        val path = ArrayList<String>()
                        var at = w
                        while (at != -1) { path += byId[at].ref; at = parent.getValue(at) }
                        return path.reversed()
                    }
                    queue += w
                }
            }
            depth++
        }
        return emptyList()
    }

    private fun attention(data: String, site: SinkSite): String {
        site.attention?.let { return it }
        val strong = data in setOf("location", "device-id", "phone-number", "contacts", "call-log", "sms", "accounts", "audio", "camera", "calendar")
        return when (site.kind) {
            "NETWORK", "ANALYTICS", "SMS" -> if (strong || data == "advertising-id") "HIGH" else "MEDIUM"
            "LOG", "IPC" -> if (strong) "MEDIUM" else "LOW"
            else -> "LOW"
        }
    }

    private fun codeOf(method: String): String {
        val owner = method.substringBefore("->")
        return Capabilities.sdkFor(owner) ?: LIBRARY_NAMES.entries.firstOrNull { owner.startsWith(it.key) }?.value ?: "app"
    }

    private fun display(ref: String): String {
        val owner = ref.substringBefore("->")
        val cls = owner.removePrefix("L").removeSuffix(";").replace('/', '.')
        if ("->" !in ref) return cls
        return cls + "." + ref.substringAfter("->").substringBefore('(').substringBefore(':')
    }

    companion object {
        private const val MAXP = 256
        private const val PARAM_FIELD = 1
        private const val SOURCE = 3
        private const val MARK = 4
        /** Virtual calls with more implementations are generic (compareTo, invoke, run): modelled, not followed. */
        private const val MAX_TARGETS = 8
        /** Field labels of one parameter before they are widened to the whole parameter. */
        private const val MAX_FIELD_LABELS = 4
        /** Fields tracked per object, and sink edges and field writes per summary. */
        private const val MAX_FIELDS = 16
        private const val MAX_EDGES = 48
        private const val MAX_FIELD_WRITES = 128
        private const val MAX_CHAIN = 14
        /** Analyses of one method before it is left as it is (a safeguard; the lattice is finite). */
        private const val MAX_VISITS = 200
        private val EMPTY = IntArray(0)
        private val EMPTY_RV = RV(EMPTY, null, -1)
        private val ATTENTION = listOf("HIGH", "MEDIUM", "LOW")

        /** Sorted-set union of two sorted int arrays. */
        fun union(a: IntArray?, b: IntArray?): IntArray {
            if (a == null || a.isEmpty()) return b ?: EMPTY
            if (b == null || b.isEmpty() || a === b) return a
            val out = IntArray(a.size + b.size)
            var i = 0; var j = 0; var n = 0
            while (i < a.size && j < b.size) {
                val x = a[i]; val y = b[j]
                when {
                    x < y -> { out[n++] = x; i++ }
                    x > y -> { out[n++] = y; j++ }
                    else -> { out[n++] = x; i++; j++ }
                }
            }
            while (i < a.size) out[n++] = a[i++]
            while (j < b.size) out[n++] = b[j++]
            return if (n == a.size) a else if (n == b.size) b else out.copyOf(n)
        }

        private val LIBRARY_NAMES = mapOf(
            "Lcom/google/android/gms/" to "Google Play services", "Lcom/google/firebase/" to "Firebase", "Lcom/facebook/" to "Facebook SDK",
            "Lcom/google/android/exoplayer2/" to "ExoPlayer", "Landroidx/media3/" to "AndroidX Media3",
        )

        private val MODELLED = listOf(
            // The platform (never in a real APK's DEX, but test stubs and some SDK shims carry copies).
            "Landroid/", "Ljava/", "Ljavax/", "Ldalvik/", "Lorg/json/", "Lorg/w3c/", "Lorg/xml/",
            "Landroidx/", "Landroid/support/", "Lkotlin/", "Lkotlinx/", "Lokhttp3/", "Lokio/", "Lretrofit2/", "Lcom/google/gson/",
            "Lcom/squareup/moshi/", "Lcom/google/android/material/", "Lio/reactivex/", "Lrx/", "Lcom/bumptech/glide/", "Lcoil/", "Lcoil3/",
            "Ldagger/", "Ljavax/inject/", "Lcom/google/common/", "Lj\$/", "Lcom/fasterxml/jackson/", "Lorg/jetbrains/", "Lorg/intellij/",
            "Lcom/google/protobuf/", "Lorg/conscrypt/", "Lorg/bouncycastle/", "Lcom/google/errorprone/", "Ltimber/log/",
        )

        /** Types whose fields hold a value rather than a container. */
        private val VALUE_TYPES = setOf(
            "Ljava/lang/String;", "Ljava/lang/CharSequence;", "Ljava/lang/Integer;", "Ljava/lang/Long;", "Ljava/lang/Double;", "Ljava/lang/Float;",
            "Ljava/lang/Boolean;", "Ljava/lang/Short;", "Ljava/lang/Byte;", "Ljava/lang/Character;", "Landroid/location/Location;",
            "Landroid/accounts/Account;", "Landroid/net/wifi/WifiInfo;", "Landroid/location/Address;",
        )

        private val MUTATORS = setOf(
            "add", "addAll", "put", "putAll", "set", "offer", "push", "addFirst", "addLast", "offerFirst", "offerLast", "putIfAbsent",
            "merge", "compute", "computeIfAbsent", "computeIfPresent", "replace", "replaceAll",
        )

        /** Returns that describe a value, not carry it. */
        private val NON_DATA = setOf(
            "size", "length", "hashCode", "isEmpty", "indexOf", "lastIndexOf", "compareTo", "compareToIgnoreCase", "getCount",
            "getColumnIndex", "getColumnIndexOrThrow", "getColumnCount", "getClass", "ordinal", "describeContents",
        )

        /**
         * Data that does not survive a hop through a shared field: clipboard text kept in a text
         * field's state is what the user pasted, user input like anything typed. A clipboard read
         * sent on directly (or through return values) is still a flow.
         */
        private val HEAP_LOCAL = setOf("clipboard")

        private val TEXT_VIEWS = setOf("Landroid/widget/TextView;", "Landroid/widget/EditText;", "Landroid/widget/AutoCompleteTextView;", "Landroid/widget/MultiAutoCompleteTextView;")

        private val CONTEXT_TYPES = setOf(
            "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Activity;", "Landroid/app/Service;", "Landroid/app/Application;",
            "Landroid/view/ContextThemeWrapper;",
        )

        /** Platform APIs whose result is personal data: owner->name -> data category. */
        private val SOURCES: Map<String, String> = buildMap {
            fun add(data: String, owner: String, vararg names: String) = names.forEach { put("$owner->$it", data) }
            add("location", "Landroid/location/LocationManager;", "getLastKnownLocation")
            add("location", "Lcom/google/android/gms/location/FusedLocationProviderClient;", "getLastLocation", "getCurrentLocation")
            add("location", "Lcom/google/android/gms/location/LocationResult;", "getLastLocation", "getLocations")
            add("location", "Landroid/telephony/TelephonyManager;", "getAllCellInfo", "getCellLocation")
            add("device-id", "Landroid/telephony/TelephonyManager;", "getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber")
            add("device-id", "Landroid/os/Build;", "getSerial")
            add("device-id", "Landroid/net/wifi/WifiInfo;", "getMacAddress")
            add("device-id", "Landroid/bluetooth/BluetoothAdapter;", "getAddress")
            add("device-id", "Ljava/net/NetworkInterface;", "getHardwareAddress")
            add("phone-number", "Landroid/telephony/TelephonyManager;", "getLine1Number")
            add("phone-number", "Landroid/telephony/SubscriptionManager;", "getPhoneNumber")
            add("phone-number", "Landroid/telephony/SubscriptionInfo;", "getNumber")
            add("advertising-id", "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;", "getAdvertisingIdInfo")
            add("advertising-id", "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;", "getId")
            add("advertising-id", "Landroidx/ads/identifier/AdvertisingIdClient;", "getAdvertisingIdInfo")
            add("advertising-id", "Lcom/google/android/gms/appset/AppSetIdInfo;", "getId")
            add("accounts", "Landroid/accounts/AccountManager;", "getAccounts", "getAccountsByType", "getAccountsByTypeForPackage")
            add("installed-apps", "Landroid/content/pm/PackageManager;", "getInstalledPackages", "getInstalledApplications")
            add("clipboard", "Landroid/content/ClipboardManager;", "getPrimaryClip", "getText")
            add("wifi", "Landroid/net/wifi/WifiManager;", "getScanResults", "getConnectionInfo")
            add("wifi", "Landroid/net/wifi/WifiInfo;", "getSSID", "getBSSID")
            add("audio", "Landroid/media/AudioRecord;", "read")
        }

        /** Sources that fill an argument instead of returning: owner->name -> parameter index. */
        private val ARG_SOURCES = mapOf("Landroid/media/AudioRecord;->read" to 1)

        /** Platform callbacks whose parameter is personal data: signature -> (data, parameter index). */
        private val CALLBACK_SOURCES = mapOf(
            "onLocationChanged(Landroid/location/Location;)V" to ("location" to 1),
            "onLocationChanged(Ljava/util/List;)V" to ("location" to 1),
            "onLocationResult(Lcom/google/android/gms/location/LocationResult;)V" to ("location" to 1),
            "onPictureTaken([BLandroid/hardware/Camera;)V" to ("camera" to 1),
            "onPreviewFrame([BLandroid/hardware/Camera;)V" to ("camera" to 1),
        )

        /** Content URI holders: a query on one of their URIs returns that data. */
        private val URI_OWNERS = mapOf(
            "Landroid/provider/ContactsContract" to "contacts", "Landroid/provider/CallLog" to "call-log",
            "Landroid/provider/Telephony\$Sms" to "sms", "Landroid/provider/Telephony\$Mms" to "sms", "Landroid/provider/Telephony\$MmsSms" to "sms",
            "Landroid/provider/Telephony\$Threads" to "sms", "Landroid/provider/CalendarContract" to "calendar",
        )

        /** Calls that return or create a stream to the network or a file. */
        private val STREAM_MARKS = mapOf(
            "Ljava/net/URLConnection;->getOutputStream" to "net", "Ljava/net/HttpURLConnection;->getOutputStream" to "net",
            "Ljavax/net/ssl/HttpsURLConnection;->getOutputStream" to "net", "Ljava/net/Socket;->getOutputStream" to "net",
            "Ljava/io/FileOutputStream;-><init>" to "file", "Ljava/io/FileWriter;-><init>" to "file", "Ljava/io/RandomAccessFile;-><init>" to "file",
        )

        private val SINKS: List<SinkSpec> = buildList {
            fun net(owner: String, names: Set<String>?, detail: String, params: List<Int>? = null) = add(SinkSpec("NETWORK", owner, names, params, detail))
            net("Lokhttp3/Request\$Builder;", setOf("url", "header", "addHeader", "post", "put", "patch", "delete", "method"), "OkHttp request")
            net("Lokhttp3/HttpUrl\$Builder;", setOf("addQueryParameter", "addEncodedQueryParameter", "setQueryParameter", "setEncodedQueryParameter", "addPathSegment", "addEncodedPathSegment", "host"), "OkHttp URL")
            net("Lokhttp3/FormBody\$Builder;", setOf("add", "addEncoded"), "OkHttp form body")
            net("Lokhttp3/RequestBody;", setOf("create"), "OkHttp request body")
            net("Lokhttp3/RequestBody\$Companion;", setOf("create"), "OkHttp request body")
            net("Lokhttp3/MultipartBody\$Builder;", setOf("addFormDataPart", "addPart"), "OkHttp multipart body")
            net("Lokhttp3/WebSocket;", setOf("send"), "WebSocket message")
            net("Ljava/net/URL;", setOf("<init>"), "URL")
            for (c in listOf("Ljava/net/URLConnection;", "Ljava/net/HttpURLConnection;", "Ljavax/net/ssl/HttpsURLConnection;")) net(c, setOf("setRequestProperty", "addRequestProperty"), "HTTP header")
            net("Lcom/android/volley/toolbox/StringRequest;", setOf("<init>"), "Volley request")
            net("Lcom/android/volley/toolbox/JsonObjectRequest;", setOf("<init>"), "Volley request")
            net("Lcom/android/volley/toolbox/JsonArrayRequest;", setOf("<init>"), "Volley request")
            net("Lcom/google/firebase/firestore/DocumentReference;", setOf("set", "update"), "Firebase Firestore")
            net("Lcom/google/firebase/firestore/CollectionReference;", setOf("add"), "Firebase Firestore")
            net("Lcom/google/firebase/database/DatabaseReference;", setOf("setValue", "updateChildren"), "Firebase Realtime Database")
            add(SinkSpec("SMS", "Landroid/telephony/SmsManager;", setOf("sendTextMessage", "sendMultipartTextMessage", "sendDataMessage"), null, "SMS"))
            fun sdk(name: String, owner: String, vararg methods: String) = add(SinkSpec("ANALYTICS", owner, methods.toSet(), null, name))
            sdk("Firebase Analytics", "Lcom/google/firebase/analytics/FirebaseAnalytics;", "logEvent", "setUserId", "setUserProperty", "setDefaultEventParameters")
            sdk("Firebase Crashlytics", "Lcom/google/firebase/crashlytics/FirebaseCrashlytics;", "log", "setUserId", "setCustomKey", "recordException")
            sdk("Sentry", "Lio/sentry/Sentry;", "captureMessage", "captureException", "setUser", "addBreadcrumb", "setTag", "setExtra")
            sdk("Amplitude", "Lcom/amplitude/api/AmplitudeClient;", "logEvent", "setUserId", "setUserProperties")
            sdk("Amplitude", "Lcom/amplitude/android/Amplitude;", "track", "setUserId", "identify")
            sdk("Mixpanel", "Lcom/mixpanel/android/mpmetrics/MixpanelAPI;", "track", "identify", "registerSuperProperties", "alias")
            sdk("Mixpanel", "Lcom/mixpanel/android/mpmetrics/MixpanelAPI\$People;", "set", "identify")
            sdk("AppsFlyer", "Lcom/appsflyer/AppsFlyerLib;", "logEvent", "setCustomerUserId", "setAdditionalData")
            sdk("Adjust", "Lcom/adjust/sdk/Adjust;", "trackEvent", "addSessionCallbackParameter", "addSessionPartnerParameter")
            sdk("Adjust", "Lcom/adjust/sdk/AdjustEvent;", "addCallbackParameter", "addPartnerParameter")
            sdk("Facebook SDK", "Lcom/facebook/appevents/AppEventsLogger;", "logEvent", "setUserID", "setUserData")
            sdk("Segment", "Lcom/segment/analytics/Analytics;", "track", "identify", "screen")
            sdk("Bugsnag", "Lcom/bugsnag/android/Bugsnag;", "leaveBreadcrumb", "setUser", "addMetadata", "notify")
            sdk("OneSignal", "Lcom/onesignal/OneSignal;", "sendTag", "sendTags", "setExternalUserId", "login", "setEmail", "setSMSNumber")
            sdk("Flurry", "Lcom/flurry/android/FlurryAgent;", "logEvent", "setUserId")
            sdk("PostHog", "Lcom/posthog/PostHog;", "capture", "identify")
            fun log(owner: String, names: Set<String>?) = add(SinkSpec("LOG", owner, names, null, "log"))
            log("Landroid/util/Log;", setOf("v", "d", "i", "w", "e", "wtf", "println"))
            for (t in listOf("Ltimber/log/Timber;", "Ltimber/log/Timber\$Forest;", "Ltimber/log/Timber\$Tree;")) log(t, setOf("v", "d", "i", "w", "e", "wtf", "log"))
            log("Ljava/io/PrintStream;", setOf("print", "println", "printf", "format"))
            log("Ljava/util/logging/Logger;", setOf("info", "warning", "severe", "fine", "finer", "finest", "log", "config"))
            log("Lorg/slf4j/Logger;", setOf("trace", "debug", "info", "warn", "error"))
            add(SinkSpec("STORAGE", "Landroid/content/SharedPreferences\$Editor;", setOf("putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet"), listOf(2), "SharedPreferences"))
            add(SinkSpec("STORAGE", "Landroid/database/sqlite/SQLiteDatabase;", setOf("insert", "insertOrThrow", "insertWithOnConflict", "replace", "replaceOrThrow", "update", "updateWithOnConflict", "execSQL"), null, "SQLite database"))
            for (t in listOf("Landroid/database/sqlite/SQLiteStatement;", "Landroid/database/sqlite/SQLiteProgram;", "Landroidx/sqlite/db/SupportSQLiteStatement;", "Landroidx/sqlite/db/SupportSQLiteProgram;", "Landroidx/sqlite/SQLiteStatement;")) {
                add(SinkSpec("STORAGE", t, setOf("bindString", "bindLong", "bindDouble", "bindBlob", "bindText"), null, "SQLite database"))
            }
            add(SinkSpec("STORAGE", "Lkotlin/io/FilesKt__FileReadWriteKt;", setOf("writeText", "appendText", "writeBytes", "appendBytes"), null, "file"))
            add(SinkSpec("STORAGE", "Ljava/nio/file/Files;", setOf("write", "writeString"), null, "file"))
            add(SinkSpec("CLIPBOARD", "Landroid/content/ClipboardManager;", setOf("setPrimaryClip", "setText"), null, "clipboard", "LOW"))
        }

        private val SINKS_BY_OWNER: Map<String, List<SinkSpec>> = SINKS.groupBy { it.owner }

        /** Platform methods apps call on their own subclasses (an activity's startActivity, a WebView subclass's loadUrl). */
        private val INHERITED_SINKS: List<SinkSpec> = buildList {
            for (c in listOf("Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Activity;", "Landroid/app/Service;")) {
                add(SinkSpec("IPC", c, setOf("sendBroadcast", "sendOrderedBroadcast", "sendStickyBroadcast"), listOf(1), "broadcast to other apps", "MEDIUM"))
                add(SinkSpec("IPC", c, setOf("startActivity", "startActivityForResult", "startService", "startForegroundService"), listOf(1), "intent to another component", null))
            }
            add(SinkSpec("IPC", "Landroid/app/Activity;", setOf("setResult"), listOf(2), "result returned to the calling app", null))
            add(SinkSpec("NETWORK", "Landroid/webkit/WebView;", setOf("loadUrl", "postUrl", "evaluateJavascript", "loadData", "loadDataWithBaseURL"), null, "WebView", null))
        }
    }
}

/** Human-readable privacy map. */
object PrivacyMarkdown {
    fun render(r: PrivacyReport): String = buildString {
        appendLine("# Privacy map")
        appendLine()
        val kinds = r.sources.map { it.data }.distinct()
        appendLine("The code reads ${PrivacyMap.kinds(kinds.size)}${if (kinds.isEmpty()) "" else " (" + kinds.joinToString { PrivacyMap.TITLES[it] ?: it } + ")"}; " +
            "${PrivacyMap.flowsReach(r.flows.size)}. ${r.stats.methods} methods (${r.stats.modelled} of well-known libraries modelled at their API), ${r.stats.elapsedMs} ms" +
            (if (r.stats.truncated) ", **stopped at the time budget**" else "") + ".")
        appendLine()
        if (r.dataSafety.isNotEmpty()) {
            appendLine("## Data safety")
            appendLine()
            appendLine("| Data | Play data type | Leaves the device | Shared with an SDK | Stored | Logged | Where |")
            appendLine("|---|---|---|---|---|---|---|")
            fun yn(b: Boolean) = if (b) "yes" else "-"
            for (d in r.dataSafety) appendLine("| ${d.title} | ${d.playDataType} | ${yn(d.collected)} | ${yn(d.shared)} | ${yn(d.stored)} | ${yn(d.logged)} | ${cell(d.sinks.joinToString().ifEmpty { "no sink reached" })} |")
            appendLine()
        }
        if (r.flows.isNotEmpty()) {
            appendLine("## Flows")
            appendLine()
            appendLine("| Attention | Data | Source | Sink | Code | Path |")
            appendLine("|---|---|---|---|---|---|")
            for (f in r.flows.take(200)) {
                val path = when {
                    f.throughField != null -> "${f.sourceIn} ⇢ field ${f.throughField} ⇢ " + f.path.joinToString(" → ")
                    else -> (f.sourcePath.reversed().dropLast(1) + f.path).joinToString(" → ")
                }
                val attention = f.attention + if (f.permissionDeclared == false) " (cannot run: permission not requested)" else ""
                appendLine("| $attention | ${f.dataTitle} | ${cell(f.sourceApi)} in ${cell(f.sourceIn)} | ${f.sink}: ${cell(f.sinkDetail)} (${cell(f.sinkApi)}) | ${f.code} | ${cell(path)} |")
            }
            if (r.flows.size > 200) appendLine("\n${r.flows.size - 200} more in privacy.json.")
            appendLine()
        }
        if (r.sources.isNotEmpty()) {
            appendLine("## Personal data the code reads")
            appendLine()
            appendLine("| Data | API | Permission | Reaches a sink | Called in |")
            appendLine("|---|---|---|---|---|")
            for (s in r.sources) {
                val permission = when (s.permissionDeclared) { true -> "requested"; false -> "not requested (cannot run)"; null -> if (s.permissions.isEmpty()) "none needed" else "-" }
                appendLine("| ${s.title} | ${cell(s.api)} | $permission | ${if (s.reachesSink) "yes" else "no"} | ${cell(s.usedIn.take(5).joinToString())} |")
            }
            appendLine()
        }
        val privacy = r.capabilities.filter { it.category == "PRIVACY" }
        if (privacy.isNotEmpty()) {
            appendLine("## Capabilities")
            appendLine()
            appendLine("Platform APIs the code references, whether or not a flow was found; \"cannot run\" means the manifest does not request the permission.")
            appendLine()
            appendLine("| Capability | Permission | Used in |")
            appendLine("|---|---|---|")
            for (c in privacy) appendLine("| ${c.title} | ${when (c.permissionDeclared) { true -> "requested"; false -> "not requested (cannot run)"; null -> "-" }} | ${cell(c.usedIn.take(4).joinToString())} |")
            appendLine()
        }
        if (r.sdks.isNotEmpty()) {
            appendLine("## Third-party SDKs")
            appendLine()
            for (s in r.sdks) appendLine("- ${s.name} (${s.category}, ${s.evidence}): ${s.signals.take(3).joinToString()}")
            appendLine()
        }
        appendLine("## Notes")
        appendLine()
        r.notes.forEach { appendLine("- $it") }
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").let { if (it.length > 300) it.take(297) + "…" else it }
}
