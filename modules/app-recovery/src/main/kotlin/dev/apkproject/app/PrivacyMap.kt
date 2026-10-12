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
    /**
     * Set when the source API was recognised by the privacy API model (PrivacyApiModel) rather
     * than named by a table: the model's confidence. Such a flow is model-based.
     */
    val model: Double? = null,
    /** What the source API returns at run time on the Android versions the app supports ([SourceRuntime]); null when the platform does not limit it. */
    val runtime: String? = null,
    /**
     * False when the source API returns no data on any Android version the app supports (by its
     * minSdk, targetSdk and permissions): the flow carries nothing, its attention is LOW. Null when
     * the platform does not limit the API or the manifest is unknown.
     */
    val returnsData: Boolean? = null,
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
    /** The privacy API model's confidence when it, not a table, recognised the API. */
    val model: Double? = null,
    /** What the API returns at run time on the Android versions the app supports (see [PrivacyFlow.runtime]). */
    val runtime: String? = null,
    /** False when it returns no data on any of them (see [PrivacyFlow.returnsData]). */
    val returnsData: Boolean? = null,
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
    /**
     * [useModel]: also take platform methods the tables do not name as sources when the privacy API
     * model is confident (they are reported as model-based). Off unless `APKPROJECT_PRIVACY_MODEL=1`,
     * as on `codex/production-candidate`: with whole API owners held out, the model's kind accuracy is
     * too low to add sources by default (scripts/ml/README.md).
     */
    fun analyze(dex: List<Pair<String, DexFile>>, manifestXml: String?, budgetMs: Long = 240_000, useModel: Boolean = System.getenv("APKPROJECT_PRIVACY_MODEL") == "1"): PrivacyReport {
        val started = System.nanoTime()
        val endpoints = runCatching { BytecodeApi.endpointMethods(dex) }.getOrDefault(emptyMap())
        val manifest = manifestXml?.let { AppDiff.Manifest.parse(it) }
        val engine = TaintEngine(dex, endpoints, System.getenv("APKPROJECT_PRIVACY_BUDGET_MS")?.toLongOrNull() ?: budgetMs, appPrefixes(manifest), useModel && PrivacyApiModel.available)
        engine.run()
        val capabilities = Capabilities.of(engine.refsByClass(), manifest?.permissions)
        val hosts = dex.flatMap { (_, d) -> d.strings.mapNotNull { s -> HOST.find(s)?.groupValues?.get(1)?.lowercase() } }.toHashSet()
        val sdks = Capabilities.sdks(dex.flatMap { (_, d) -> d.typeIds.indices.map { d.type(it) } }.toHashSet(), hosts)
        val app = SourceRuntime.App.of(manifest)
        val flows = engine.flows(manifest?.permissions, app)
        val sources = engine.sources(flows, manifest?.permissions, app)
        val stats = PrivacyStats(engine.methodCount, engine.modelledCount, engine.analyses, (System.nanoTime() - started) / 1_000_000, engine.truncated, engine.boundedCount)
        val notes = buildList {
            add("A flow is a path the bytecode allows from a personal-data API to a sink. Code on it can still be guarded at run time (a setting, a permission check), so every flow is INFERRED.")
            add("Data the user types is not treated as a source: sending a post or a message is the app's purpose.")
            add("Followed: callbacks of app objects handed to platform or library code (listeners, Runnables, lambdas, LiveData observers, flow collectors), LiveData/flow/queue fields of the app's classes, SharedPreferences keys, SQLite tables, files read back and intent or bundle extras under a constant name or key, and binder calls (AIDL proxies, Messenger). Not followed: reflection, native code, files or extras named at run time, what another app does with data it receives, and callback or channel types under renamed (obfuscated) library names. A missing flow is not proof that data stays on the device.")
            add("Libraries under well-known package names (androidx, kotlin, okhttp3, retrofit2, ...) are modelled at their API; their internals are not analysed.")
            if (endpoints.isEmpty()) add("No Retrofit endpoints were found; network sinks are OkHttp, URLConnection, WebView and SDK calls.")
            if (flows.any { it.permissionDeclared == false }) add("Flows whose source needs a permission the manifest does not request cannot run as built; they are listed with LOW attention and left out of the Data safety summary.")
            if (app != null && sources.any { it.runtime != null }) add("Run-time behaviour (minSdk ${app.minSdk}, targetSdk ${app.targetSdk}): what each source API returns on the Android versions the app supports, from the platform's documented behaviour changes (device identifiers, MAC addresses, Wi-Fi, phone number, advertising ID, accounts, package visibility, clipboard), not observed on a device." +
                if (flows.any { it.returnsData == false }) " Flows whose source returns no data on any of those versions are listed with LOW attention and left out of the Data safety summary." else "")
            if (engine.truncated) add("The analysis stopped at its time budget; flows may be missing.")
            if (engine.boundedCount > 0) add("${engine.boundedCount} method summaries reached their size bound (48 sink edges or field writes); flows through what was dropped may be missing.")
            if (sources.any { it.model != null }) add("${sources.count { it.model != null }} personal-data APIs no table names were recognised by the privacy API model (confidence at least ${(TaintEngine.MODEL_THRESHOLD * 100).toInt()} %); they and their flows are marked model-based with the model's confidence. It was trained on SuSi's and FlowDroid's labelled API lists and this tool's own tables (scripts/ml/README.md). Sinks come from the tables only.")
        }
        return PrivacyReport(sources, flows, dataSafety(flows, sources), capabilities, sdks, stats, notes)
    }

    /**
     * Where the app's own code lives: the manifest package, and the packages of its declared
     * components that share its first two segments (`com.example.app` and `com.example.app.sync`).
     * Components of libraries merged into the manifest (`androidx.work...`) do not count.
     */
    internal fun appPrefixes(manifest: AppDiff.Manifest?): List<String> {
        val pkg = manifest?.packageName ?: return emptyList()
        val root = pkg.split('.').take(2).joinToString(".")
        val packages = (listOf(pkg) + manifest.components.values.map { it.name.substringBeforeLast('.', "") }.filter { it == root || it.startsWith("$root.") }).toSet()
        return packages.map { "L" + it.replace('.', '/') + "/" }.sorted()
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
            val fs = flows.filter { it.data == data && it.permissionDeclared != false && it.returnsData != false }
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
    /** Package prefixes of the app's own code (`Lcom/example/app/`); empty: everything counts as the app's. */
    private val appPrefixes: List<String> = emptyList(),
    /** Ask the privacy API model about platform methods the tables do not name. */
    private val useModel: Boolean = false,
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
    private fun isSymbolic(id: Int) = id < MAXP * 2 || label(id).kind == PARAM_FIELD || label(id).kind == REF
    private fun isRef(id: Int) = id >= MAXP * 2 && label(id).kind == REF
    private fun isSource(id: Int) = id >= MAXP * 2 && label(id).kind == SOURCE
    private fun isMark(id: Int) = id >= MAXP * 2 && label(id).kind == MARK

    private fun paramField(i: Int, field: String) = intern("pf|$i|$field") { Label(PARAM_FIELD, i, field, null, null, null) }

    /**
     * A reference held in a field: parameter i's object itself ([path] null), or the object at its field path
     * (`p.f`, `p.f.g`). Only a field's contents hold one (an object's fields, a summary's changed fields and
     * returned fields); as data (a value, a field write, a sink) it is everything in that object ([deref]).
     */
    private fun ref(i: Int, path: String?) = intern("r|$i|${path ?: ""}") { Label(REF, i, path, null, null, null) }

    /** References as data: the object with everything in it (`p*`, or the deep path label `p.f`). */
    private fun deref(a: IntArray?): IntArray? {
        if (a == null || a.none(::isRef)) return a
        val out = java.util.TreeSet<Int>()
        for (l in a) out += if (!isRef(l)) l else label(l).let { x -> if (x.field == null) MAXP + x.param else paramField(x.param, x.field) }
        return out.toIntArray()
    }

    /** The object [v] is, as a reference the caller can name: a parameter's object, or the object read from a parameter's field path. */
    private fun refOf(ctx: Ctx, v: RV): Int? = when {
        ctx.conservative -> null
        v.alias in 0 until MAXP -> ref(v.alias, null)
        v.holder in 0 until MAXP && v.holderField != null && v.holderField != CONTENT && v.holderField != HELD_REQUEST &&
            interned["pf|${v.holder}|${v.holderField}"]?.let { it in v.whole } == true -> ref(v.holder, v.holderField)
        else -> null
    }
    /** The label in [v]'s value that stands for the object itself (its parameter, or its field path). */
    private fun selfLabel(v: RV): Int = if (v.alias in 0 until MAXP) v.alias else paramField(v.holder, v.holderField!!)
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
            fields.values.forEach { w = union(w, deref(it)) }
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
     * Keeps label sets small and canonical: a label another one in the set includes is dropped (`p*`
     * includes every field of p, `p.a` includes `p.a.b`), and more than [MAX_FIELD_LABELS] field
     * labels of one parameter become that parameter with all its fields (a superset), so summaries
     * stay bounded along long call chains. Dropping what is included makes the result the same
     * whichever order sets are joined in, so a summary joined with its previous value converges.
     */
    private fun widen(a: IntArray, max: Int = MAX_FIELD_LABELS): IntArray {
        // Sorted: only whole-parameter labels (below MAXP), or nothing symbolic beyond them.
        if (a.isEmpty() || a[a.size - 1] < MAXP) return a
        var all: MutableSet<Int>? = null
        var fieldLabels = 0
        for (l in a) {
            if (l in MAXP until MAXP * 2) (all ?: HashSet<Int>().also { all = it }) += l - MAXP
            else if (l >= MAXP * 2 && label(l).kind == PARAM_FIELD) fieldLabels++
        }
        if (fieldLabels == 0 || all == null && fieldLabels == 1) return a
        val wide = all ?: HashSet()
        // Field paths per parameter not already covered by `p*`.
        val paths = HashMap<Int, MutableSet<String>>()
        for (l in a) if (l >= MAXP * 2) label(l).takeIf { it.kind == PARAM_FIELD && it.param !in wide }?.let { paths.getOrPut(it.param) { HashSet() } += it.field!! }
        val dropped = HashSet<Int>()
        for ((param, ps) in paths) {
            val kept = ps.filter { path -> NEST !in path || path.substringBefore(NEST) !in ps }
            if (kept.size > max) wide += param
            else if (kept.size < ps.size) (ps - kept.toSet()).forEach { dropped += paramField(param, it) }
        }
        if (dropped.isEmpty() && wide.isEmpty()) return a
        val out = a.filter { l ->
            if (l in MAXP until MAXP * 2 || l in dropped) false
            else if (l >= MAXP * 2) label(l).let { it.kind != PARAM_FIELD || it.param !in wide }
            else true
        }.toIntArray()
        return union(out, wide.map { MAXP + it }.sorted().toIntArray())
    }

    /**
     * Value, fields, and (for a parameter's object) everything reachable from it. A stream's mark comes
     * only from the value itself: an object holding a stream in a field is not that stream.
     */
    private fun flatten(v: RV): IntArray {
        var out = v.whole
        v.fields?.values?.forEach { x -> val f = deref(x)!!; out = union(out, if (f.any(::isMark)) f.filterNot(::isMark).toIntArray() else f) }
        if (v.alias in 0 until MAXP) out = union(out, intArrayOf(MAXP + v.alias))
        return out
    }

    // ---- program model ---------------------------------------------------------------------------

    /**
     * A method with code. A variant ([base] set) is one case of a method that switches on its class's
     * dispatch id (an R8 merged lambda group): after the switch at [switchAt] only [branch] runs, the
     * code of the [cases] it lists (null: the default branch, for the ids no case lists).
     */
    private inner class MethodInfo(
        val ref: String, val d: DexFile, val m: dev.apkproject.dex.DexMethod, val id: Int,
        val base: MethodInfo? = null, val switchAt: Int = -1, val branch: Int = -1, val cases: Set<Int>? = null,
    ) {
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
            base?.let { return it.code() }
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
    /** Every method signature (`name(proto)ret`) a class in the DEX declares, abstract ones included. */
    private val declaredSigs = HashMap<String, MutableSet<String>>()
    /** Internal methods per class. */
    private val byOwner = HashMap<String, MutableList<MethodInfo>>()
    /** Instance field types per class, to recognise flat data classes. */
    private val instanceFieldTypes = HashMap<String, List<String>>()
    private val keepDecoded: Boolean
    /** Classes that run one body per instance, chosen by an id their constructor stores (R8 merged lambda groups). */
    private val dispatch: Map<String, AppGraph.Dispatch>
    /** A method that switches on its class's dispatch id -> its variants, one per branch; it is analysed only through them. */
    private val variantsOf = HashMap<Int, List<MethodInfo>>()
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
            declaredSigs.getOrPut(m.owner) { HashSet() } += m.name + m.protoDescriptor
            if (m.code == null) continue
            val ref = "${m.owner}->${m.name}${m.protoDescriptor}"
            if (!codeful.add(ref)) continue
            total++
            if (modelled(m.owner)) { modelledCount++; continue }
            val info = MethodInfo(ref, d, m, byId.size)
            methods[ref] = info
            byId += info
            byOwner.getOrPut(m.owner) { ArrayList() } += info
        }
        methodCount = total
        // Decoded code is kept for apps of ordinary size; very large ones decode again on each visit.
        keepDecoded = byId.size <= 150_000
        // Merged lambda groups: each case is analysed on its own, or data from one lambda would reach
        // the code of every other lambda merged into the class (they share fields and code blocks).
        val candidates = byId.filter { mi -> mi.m.name == "<init>" || instanceFieldTypes[mi.owner]?.contains("I") == true }
            .mapNotNull { mi -> mi.code()?.let { Triple(mi.d, mi.m, it) } }
        dispatch = AppGraph.findDispatch(candidates)
        for (mi in byId.toList()) {
            val disp = dispatch[mi.owner] ?: continue
            if (mi.m.isStatic) continue
            val code = mi.code() ?: continue
            val at = AppGraph.dispatchSwitch(mi.d, code, disp.field) ?: continue
            val position = HashMap<Int, Int>()
            code.insns.forEachIndexed { k, i -> position[i.address] = k }
            val byBranch = LinkedHashMap<Int, MutableSet<Int>>()
            for ((key, target) in code.switchCases(code.insns[at])) position[target]?.let { byBranch.getOrPut(it) { LinkedHashSet() } += key }
            val variants = byBranch.map { (branch, keys) -> MethodInfo(mi.ref, mi.d, mi.m, byId.size, mi, at, branch, keys).also { byId += it } } +
                MethodInfo(mi.ref, mi.d, mi.m, byId.size, mi, at, at + 1, null).also { byId += it }
            variantsOf[mi.id] = variants
        }
        if (System.getenv("APKPROJECT_PRIVACY_DEBUG") != null) System.err.println("privacy: dispatch classes ${dispatch.size}; methods split ${variantsOf.size} into ${variantsOf.values.sumOf { it.size }} variants; largest: " +
            variantsOf.entries.sortedByDescending { it.value.size }.take(8).joinToString { "${byId[it.key].ref.substringBefore('(')}=${it.value.size}" })
    }

    /** The variants of [mi] that can run for an instance with one of [cases] (all of them when unknown). */
    private fun variants(mi: MethodInfo, cases: Set<Int>?): List<MethodInfo> {
        val all = variantsOf[mi.id] ?: return listOf(mi)
        if (cases == null) return all
        val listed = all.filter { v -> v.cases != null && v.cases.any { it in cases } }
        val unlisted = cases.any { c -> all.none { v -> v.cases?.contains(c) == true } }
        return if (unlisted) listed + all.filter { it.cases == null } else listed
    }

    /** Well-known libraries whose behaviour is modelled at their API (their code is not analysed). */
    private fun modelled(owner: String) = MODELLED.any { owner.startsWith(it) }

    /**
     * Whether a class is the app's own: under one of [appPrefixes], or in a package renamed beyond
     * recognition (`La/b;`), which cannot be attributed. A library class under its own name
     * (`Lorg/slf4j/...`) is not.
     */
    private fun appOwned(owner: String): Boolean = appOwnedCache.getOrPut(owner) {
        if (appPrefixes.isEmpty() || appPrefixes.any { owner.startsWith(it) }) return@getOrPut true
        val pkg = owner.removePrefix("L").substringBeforeLast('/', "")
        pkg.isEmpty() || pkg.split('/').all { it.length <= 2 }
    }
    private val appOwnedCache = HashMap<String, Boolean>()

    /**
     * Whether writes to field [f] are shared with every reader of it (field-based). A static field is
     * one storage location, so sharing is exact. An instance field is shared only when its type holds
     * a value and it belongs to the app's own classes: measured on GPSLogger, instance fields of
     * library value classes (slf4j's `LoggingEvent.message`, commons-httpclient's
     * `NameValuePair.value`) turned one real log or HTTP flow into hundreds through the library.
     */
    private fun shared(f: String, static: Boolean): Boolean {
        val type = f.substringAfterLast(':')
        if (!(concrete(type) || type in CHANNEL_TYPES)) return false
        return static || appOwned(f.substringBefore("->"))
    }

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

    /**
     * Where a method an app class inherits without defining comes from: its first superclass that is
     * platform or modelled library code (`CorrectingLocation.getTime()` is `Location.getTime()`).
     */
    private fun platformOwner(owner: String): String = platformCache.getOrPut(owner) {
        var c = owner
        val seen = HashSet<String>()
        while (c in classFlags && !modelled(c) && seen.add(c)) c = superOf[c] ?: return@getOrPut owner
        c
    }
    private val platformCache = HashMap<String, String>()

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

    /**
     * [generic]: a virtual call with too many app implementations to follow (not platform code).
     * [exact]: the receiver's class is known (created here, or the only classes its field holds), so
     * no other implementation, the platform's included, can run.
     */
    private class Resolved(val internal: List<MethodInfo>, val generic: Boolean = false, val exact: Boolean = false)

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
        upward(owner)?.let { methods[it]?.let { mi -> out += variants(mi, null) } }
        if (virtual) {
            val queue = ArrayDeque(subtypes[owner].orEmpty())
            val seen = HashSet<String>()
            var count = 0
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (!seen.add(s)) continue
                if ("$s->$sig" in codeful) {
                    count++
                    if (count > MAX_TARGETS) return@getOrPut Resolved(emptyList(), generic = true)
                    methods["$s->$sig"]?.let { mi -> out += variants(mi, null) }
                }
                queue += subtypes[s].orEmpty()
            }
        }
        Resolved(out.toList())
    }

    // ---- field types ----------------------------------------------------------------------------

    /**
     * The classes an object can be: [classes] (each with its subclasses), plus whatever is passed for
     * the parameters in [params] (method id and index, packed by [paramKey]).
     */
    private class TypeSet(val classes: Set<String> = emptySet(), val params: Set<Long> = emptySet())
    private fun paramKey(method: Int, p: Int) = (method.toLong() shl 8) or p.toLong()

    /** Classes stored into interface- or abstract-typed fields, where every store is known; null: unknown; absent: never stored. */
    private val fieldTypes = HashMap<String, Set<String>?>()
    /** Signatures called through a dispatch too wide to follow: any method with one may be called from anywhere. */
    private val openSigs = HashSet<String>()
    private val refined = HashMap<Long, Resolved>()

    /**
     * Where register [r] before instruction [k] was last written, following straight-line code back
     * through blocks with one predecessor: the instruction, -1 when it still holds the method's
     * parameter, or null when another path may have written it.
     */
    private fun definition(code: DecodedCode, g: Cfg, k: Int, r: Int): Int? {
        var j = k - 1
        var block = g.blockOf(k)
        val regs = code.code.registersSize
        repeat(96) {
            if (j < g.starts[block]) {
                val preds = g.preds[block]
                if (preds.isEmpty()) return if (block == 0) -1 else null
                if (preds.size != 1 || preds[0] == block) return null
                block = preds[0]
                j = g.ends[block] - 1
            } else {
                if (r in dev.apkproject.lift.DataflowShape.writes(code.insns[j], regs)) return j
                j--
            }
        }
        return null
    }

    /** The classes the object in [r] before [k] can be, from where it was created or passed in; null: unknown. */
    private fun localTypes(mi: MethodInfo, code: DecodedCode, g: Cfg, k: Int, r: Int, depth: Int = 0): TypeSet? {
        if (depth > 6 || r < 0) return null
        val at = definition(code, g, k, r) ?: return null
        if (at == -1) {
            val p = mi.paramRegs.indexOf(r).takeIf { it >= 0 } ?: return null
            return if (p == 0 && !mi.m.isStatic) TypeSet(classes = setOf(mi.owner)) else TypeSet(params = setOf(paramKey((mi.base ?: mi).id, p)))
        }
        val x = code.insns[at]
        val mn = x.op.mnemonic
        return when {
            mn == "new-instance" -> TypeSet(classes = setOf(mi.d.type(x.index)))
            mn.startsWith("move-object") -> localTypes(mi, code, g, at, x.regs[1], depth + 1)
            mn == "check-cast" -> localTypes(mi, code, g, at, x.regs[0], depth + 1)
            (mn == "const/4" || mn == "const/16" || mn == "const") && x.literal == 0L -> TypeSet()
            else -> null
        }
    }

    /**
     * Whether every call of [mi] is in the analysed code, so its parameters hold only what those calls
     * pass: private, static and constructors, and methods that implement nothing the platform, a
     * modelled library or a dispatch too wide to follow can call.
     */
    private fun closed(mi: MethodInfo): Boolean {
        val m = mi.m
        if (m.isStatic || m.name == "<init>" || m.accessFlags and AccessFlags.PRIVATE != 0) return true
        val sig = mi.ref.substringAfter("->")
        if (sig in openSigs) return false
        for (a in ancestors(mi.owner)) {
            if (a == mi.owner) continue
            when {
                a == "Ljava/lang/Object;" -> if (sig in OBJECT_SIGS) return false
                a !in classFlags -> if (PLATFORM_INTERFACE_METHODS[a]?.let { m.name in it } != false) return false
                modelled(a) -> if (sig in declaredSigs[a].orEmpty()) return false
            }
        }
        return true
    }

    /**
     * A field whose declared type an object of several app classes can fill: an interface or an abstract
     * class, or a platform class the app extends (`OutputStream`: a thread's `this.out` holding a pipe's
     * stream would otherwise run every app stream's `write`).
     */
    private fun refinable(f: String): Boolean {
        val t = f.substringAfterLast(':')
        if (!t.startsWith("L") || t == "Ljava/lang/Object;") return false
        val flags = classFlags[t] ?: return t in platformInterfaces || t in platformSuperclasses
        return flags and (AccessFlags.INTERFACE or AccessFlags.ABSTRACT) != 0
    }
    private val platformInterfaces: Set<String> by lazy { interfacesOf.values.flatten().filter { it !in classFlags }.toSet() }
    private val platformSuperclasses: Set<String> by lazy { superOf.values.filter { it !in classFlags && it != "Ljava/lang/Object;" }.toSet() }

    /**
     * Which classes each interface- or abstract-typed field can hold: every store of it, traced back
     * to a `new-instance`, `this`, or a parameter of a method whose calls are all known (whose
     * arguments are traced the same way). One store that cannot be traced leaves the field unknown.
     */
    private fun computeFieldTypes() {
        val stores = HashMap<String, MutableList<TypeSet?>>()
        val args = HashMap<Long, MutableList<TypeSet?>>()
        for (mi in byId) {
            if (mi.base != null) continue
            val code = mi.code() ?: continue
            val g = cfg(mi, code)
            for ((k, i) in code.insns.withIndex()) {
                val mn = i.op.mnemonic
                if (mn == "iput-object" || mn == "sput-object") {
                    val f = normalizeField(mi.d.fieldDescriptor(i.index))
                    if (refinable(f)) stores.getOrPut(f) { ArrayList() } += localTypes(mi, code, g, k, i.regs[0])
                } else if (mn.startsWith("invoke") && i.op.index == IndexKind.METHOD) {
                    val ref = mi.d.methodDescriptor(i.index)
                    val virtual = mn.startsWith("invoke-virtual") || mn.startsWith("invoke-interface")
                    val res = resolve(ref, virtual)
                    if (res.generic) openSigs += ref.substringAfter("->")
                    val static = mn.startsWith("invoke-static")
                    val proto = mi.d.protos[mi.d.methodIds[i.index].protoIdx]
                    val types = (if (static) emptyList() else listOf(ref.substringBefore("->"))) + proto.parameters.map { mi.d.type(it) }
                    var slot = 0
                    val argRegs = IntArray(types.size) { p -> i.regs.getOrElse(slot) { -1 }.also { slot += if (types[p] == "J" || types[p] == "D") 2 else 1 } }
                    val callees = res.internal.map { it.base ?: it }.distinct()
                    if (callees.isEmpty()) continue
                    for (p in (if (static) 0 else 1) until types.size) {
                        if (!types[p].startsWith("L") && !types[p].startsWith("[")) continue
                        val t = localTypes(mi, code, g, k, argRegs[p])
                        for (c in callees) args.getOrPut(paramKey(c.id, p)) { ArrayList() } += t
                    }
                }
            }
        }
        // Parameters: what their calls pass, to a fixpoint (sets only grow; unknown absorbs).
        val known = HashMap<Long, Set<String>?>()
        fun param(q: Long): Set<String>? = if (q in known) known[q] else if (closed(byId[(q shr 8).toInt()])) emptySet() else null
        fun valueOf(t: TypeSet?): Set<String>? {
            if (t == null) return null
            var out = t.classes
            for (q in t.params) out = out + (param(q) ?: return null)
            return out
        }
        for (q in args.keys) known[q] = if (closed(byId[(q shr 8).toInt()])) emptySet() else null
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 30) {
            changed = false
            for ((q, sites) in args) {
                val old = known[q] ?: continue
                var now: Set<String>? = old
                for (t in sites) { val v = valueOf(t); now = if (v == null) null else now!! + v; if (now == null) break }
                if (now != old) { known[q] = now; changed = true }
            }
        }
        if (changed) for (q in args.keys) known[q] = null
        for ((f, list) in stores) {
            var out: Set<String>? = emptySet()
            for (t in list) { val v = valueOf(t); out = if (v == null) null else out!! + v; if (out == null) break }
            fieldTypes[f] = out
        }
        if (DEBUG) {
            System.err.println("privacy: field types known for ${fieldTypes.values.count { it != null }} of ${fieldTypes.size} interface fields; open signatures ${openSigs.size}")
            if (WATCH != null) for ((f, list) in stores) if (WATCH in f) System.err.println("  field $f = ${fieldTypes[f]} from ${list.map { t -> t?.let { "${it.classes}+${it.params.map { q -> byId[(q shr 8).toInt()].ref + "#" + (q and 255) + "=" + param(q) }}" } }}")
        }
    }

    /**
     * The methods a call can run. A virtual call on an object created in this method runs that class's
     * method; on an object just read from a field whose classes are known ([fieldTypes]), those classes'
     * methods only, not every implementation of its type.
     */
    private fun targetsAt(mi: MethodInfo, code: DecodedCode, g: Cfg, k: Int, ref: String, mn: String): Resolved {
        val virtual = mn.startsWith("invoke-virtual") || mn.startsWith("invoke-interface")
        val all = resolve(ref, virtual)
        if (!virtual) return all
        return refined.getOrPut(paramKey(mi.id, 0) xor (k.toLong() shl 40)) {
            var r = code.insns[k].regs.firstOrNull() ?: return@getOrPut all
            var at = definition(code, g, k, r)
            repeat(4) {
                val x = at?.takeIf { it >= 0 }?.let { code.insns[it] } ?: return@repeat
                when {
                    x.op.mnemonic.startsWith("move-object") -> { r = x.regs[1]; at = definition(code, g, at!!, r) }
                    x.op.mnemonic == "check-cast" -> at = definition(code, g, at!!, r)
                }
            }
            val x = at?.takeIf { it >= 0 }?.let { code.insns[it] } ?: return@getOrPut all
            // A connection's or socket's own stream, from the platform: the platform's class, no app override.
            if (x.op.mnemonic.startsWith("move-result") && at!! > 0) {
                val call = code.insns[at!! - 1]
                if (call.op.index == IndexKind.METHOD && call.op.mnemonic.startsWith("invoke")) {
                    val callee = mi.d.methodDescriptor(call.index)
                    val owner = callee.substringBefore("->")
                    if (callee.substringAfter("->").substringBefore('(') in PLATFORM_STREAMS && (owner !in classFlags || modelled(owner))) return@getOrPut Resolved(emptyList(), exact = true)
                }
            }
            // Created right here: exactly that class (a `new BufferedOutputStream(..)` is no app stream).
            if (x.op.mnemonic == "new-instance") return@getOrPut Resolved(resolve("${mi.d.type(x.index)}->${ref.substringAfter("->")}", false).internal, exact = true)
            if (x.op.mnemonic != "iget-object" && x.op.mnemonic != "sget-object") return@getOrPut all
            // Empty: every store is known and none puts an object there (only null, or a parameter
            // nothing passes), so the call has nothing to run.
            val types = fieldTypes[normalizeField(mi.d.fieldDescriptor(x.index))] ?: return@getOrPut all
            val sig = ref.substringAfter("->")
            val out = LinkedHashSet<MethodInfo>()
            for (t in types) {
                val r2 = resolve("$t->$sig", true)
                if (r2.generic) return@getOrPut all
                out += r2.internal
            }
            Resolved(out.toList(), exact = true)
        }
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
    private class Edge(val labels: IntArray, val places: Map<Int, Place>) {
        /** Where [l] reaches the sink: its own place, or (a label widening made) the edge's nearest one. */
        fun placeOf(l: Int): Place = places[l] ?: nearest
        val nearest: Place get() = places.values.minBy { it.chain.size }
        val site: SinkSite get() = nearest.site
    }

    /** A sink call and the chain down to it. */
    private class Place(val site: SinkSite, val chain: List<String>)

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
        if (old == null) { sinks[key] = Edge(intArrayOf(l), mapOf(l to Place(site, chain))); return }
        // One edge per parameter and sink API (the summary's size bound), but each label keeps its own place:
        // two parts of one object reaching the same kind of sink in different methods (a log job's settings
        // inserted in addLogjob, its locations in addLocation) are each reported where they go.
        val labels = widen(union(old.labels, intArrayOf(l)), edgeFieldLabels)
        val places = HashMap<Int, Place>(old.places.size + 1)
        for ((x, p) in old.places) if (x in labels) places[x] = p
        if (l in labels && places[l].let { it == null || chain.size < it.chain.size }) places[l] = Place(site, chain)
        // A label that widening made (p* for p.a, p.b) is reached where the nearest of them is.
        if (places.isEmpty()) places[labels[0]] = old.nearest.let { if (chain.size < it.chain.size) Place(site, chain) else it }
        sinks[key] = Edge(labels, places)
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

        fun same(o: Summary): Boolean = ret.same(o.ret) && sameEffects(o)

        /** Everything but the return value. */
        fun sameEffects(o: Summary): Boolean =
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
        // Clipboard text put back on the clipboard (a text field's cut and paste) goes nowhere new.
        if (site.kind == "CLIPBOARD" && label(label).data == "clipboard") return
        val key = "$label|${site.key}"
        val old = hits[key]
        if (old == null || chain.size < old.chain.size) hits[key] = Hit(label, site, chain)
    }

    // ---- driver ---------------------------------------------------------------------------------

    private val callers = HashMap<Int, MutableSet<Int>>()
    /** Methods that register an object whose callback this method is (they use its effects, not its return value). */
    private val callbackUsers = HashMap<Int, MutableSet<Int>>()
    private val readers = HashMap<String, MutableSet<Int>>()
    private val callees = HashMap<Int, List<Int>>()

    fun run() {
        val started = System.nanoTime()
        computeFieldTypes()
        // Call graph and field readers, for ordering and for re-analysis when something changes.
        for (mi in byId) {
            val code = mi.code() ?: continue
            val out = LinkedHashSet<Int>()
            val myRefs = refs.getOrPut(mi.owner) { HashSet() }
            // A variant (one case of a merged lambda group) runs only the code its branch reaches: only those calls and
            // field reads change its summary. With every case's callees, Tusky re-analysed each of its cases ~200 times.
            val reached = if (mi.switchAt >= 0) reachedInsns(mi, code) else null
            for ((k, i) in code.insns.withIndex()) {
                val mn = i.op.mnemonic
                val live = reached == null || reached[k]
                when {
                    i.op.index == IndexKind.METHOD && mn.startsWith("invoke") -> {
                        val ref = mi.d.methodDescriptor(i.index)
                        myRefs += ref
                        if (live) {
                            resolve(ref, mn.startsWith("invoke-virtual") || mn.startsWith("invoke-interface")).internal.forEach { out += it.id }
                            targetsAt(mi, code, cfg(mi, code), k, ref, mn).internal.forEach { out += it.id }
                        }
                    }
                    i.op.index == IndexKind.FIELD -> {
                        val f = mi.d.fieldDescriptor(i.index)
                        myRefs += f
                        if (mn.startsWith("iput") && SPILL.matches(f.substringAfter("->").substringBefore(':'))) {
                            val nf = normalizeField(f)
                            val owner = (mi.base ?: mi).id
                            spillWriters[nf] = spillWriters[nf].let { if (it == null || it == owner) owner else -1 }
                        }
                        if ((mn.startsWith("iget") || mn.startsWith("sget")) && mi.id !in variantsOf && live) readers.getOrPut(normalizeField(f)) { HashSet() } += mi.id
                    }
                }
            }
            callees[mi.id] = out.toList()
            // A method analysed through its variants is never re-analysed itself.
            if (mi.id !in variantsOf) for (c in out) callers.getOrPut(c) { HashSet() } += mi.id
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
        for (mi in byId) if (mi.id !in variantsOf) { queue += mi.id; queued.set(mi.id) }
        val deadline = started + budgetMs * 1_000_000
        val visits = IntArray(byId.size)
        visitCount = visits
        while (queue.isNotEmpty()) {
            if (System.nanoTime() > deadline) { truncated = true; break }
            val id = queue.poll()
            queued.clear(id)
            val mi = byId[id]
            if (++visits[id] > MAX_VISITS) { truncated = true; continue }
            // A sink edge keeps more field labels than a value (see [MAX_EDGE_FIELD_LABELS]), except in a method
            // re-analysed past [DEGRADE_VISITS].
            edgeFieldLabels = if (visits[id] > DEGRADE_VISITS) MAX_FIELD_LABELS else MAX_EDGE_FIELD_LABELS
            changedFields.clear()
            val fresh = analyze(mi) ?: continue
            analyses++
            val old = summaries[id]
            // Summaries only grow: joined with the previous one, so the iteration terminates even
            // where a rule is not monotone (object identity known on one pass, not on the next).
            val s = if (old == null) fresh else old.joinedWith(fresh)
            if (fresh.bounded || s.bounded) boundedMethods.set(id)
            if (WATCH != null && WATCH in mi.ref && (visits[id] < 8 || visits[id] % 50 == 0)) System.err.println("watch ${mi.ref}${mi.cases?.let { " case $it" } ?: if (mi.base != null) " default" else ""} #${visits[id]}: ${describe(s)}")
            if (old == null || !s.same(old)) {
                summaries[id] = s
                if (old != null || !s.isTrivial()) callers[id]?.forEach { c -> if (!queued.get(c)) { queued.set(c); queue += c } }
                // Registrations use a callback's effects and, where the call returns it, its result.
                callbackUsers[id]?.forEach { c -> if (!queued.get(c)) { queued.set(c); queue += c } }
            } else {
                summaries[id] = s
            }
            for (f in changedFields) readers[f]?.forEach { r -> if (!queued.get(r)) { queued.set(r); queue += r } }
            if (DEBUG && analyses % 2000 == 0) System.err.println("privacy: $analyses analyses, ${(System.nanoTime() - started) / 1_000_000} ms, queue ${queue.size}, labels ${labels.size}, globals ${global.size}")
        }
        if (DEBUG) {
            System.err.println("privacy: done $analyses analyses of ${byId.size} methods in ${(System.nanoTime() - started) / 1_000_000} ms; labels ${labels.size}; globals ${global.size}; degraded to flattening ${visits.count { it > DEGRADE_VISITS }}")
            visits.withIndex().sortedByDescending { it.value }.take(15).forEach { (i, v) -> System.err.println("  $v x ${byId[i].ref}${byId[i].cases?.let { " case $it" } ?: if (byId[i].base != null) " default" else ""} (${byId[i].code()?.insns?.size} insns)") }
            val big = summaries.entries.sortedByDescending { (_, x) -> x.sinks.size + x.ret.whole.size * 10 }.take(10)
            for ((i, x) in big) System.err.println("  summary ${byId[i].ref}: ret ${x.ret.whole.size} labels, fields ${x.ret.fields?.size}, sinks ${x.sinks.size}, paramOut ${x.paramOut.size}, fieldWrites ${x.fieldWrites.size}")
            val boundedKinds = summaries.filterKeys { boundedMethods.get(it) }.values.flatMap { x -> x.sinks.values.map { it.site.kind + " " + it.site.detail.take(30) } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)
            System.err.println("  sinks in bounded summaries: $boundedKinds; field writes in bounded: ${summaries.filterKeys { boundedMethods.get(it) }.values.sumOf { it.fieldWrites.size }}")
            System.err.println("  callbacks instantiated: ${callbackCounts.values.sum()} at ${callbackCounts.size} (callback, type) pairs; top: " + callbackCounts.entries.sortedByDescending { it.value }.take(12).joinToString("; ") { "${it.value} ${it.key}" })
            System.err.println("  global field types: " + global.keys.groupingBy { it.substringAfterLast(':') }.eachCount().entries.sortedByDescending { it.value }.take(15))
            global.keys.take(30).forEach { System.err.println("    $it") }
            System.err.println("  sink edges total ${summaries.values.sumOf { it.sinks.size }}, ret labels total ${summaries.values.sumOf { it.ret.whole.size }}, param-field labels ${labels.count { it.kind == PARAM_FIELD }}")
        }
    }
    private val DEBUG = System.getenv("APKPROJECT_PRIVACY_DEBUG") != null
    /** Kotlin's spilled locals (`L$0`, `I$1`) per field: the one method that writes them, or -1 when several do. */
    private val spillWriters = HashMap<String, Int>()
    private fun privateSpill(ctx: Ctx, f: String) = spillWriters.isNotEmpty() && !ctx.conservative && spillWriters[f] == (ctx.mi.base ?: ctx.mi).id
    private var visitCount = IntArray(0)
    private var edgeFieldLabels = MAX_EDGE_FIELD_LABELS
    private val spillCache = HashMap<Int, Map<String, RV>>()
    private val callbackCounts = HashMap<String, Int>()
    private val WATCH = System.getenv("APKPROJECT_PRIVACY_WATCH")
    private val TRACE = System.getenv("APKPROJECT_PRIVACY_TRACE")

    private fun lname(l: Int): String = when {
        isParam(l) -> "P$l"
        isParamAll(l) -> "P${l - MAXP}*"
        else -> label(l).let { x -> when (x.kind) { PARAM_FIELD -> "P${x.param}.${shortPath(x.field!!)}"; REF -> "&P${x.param}${x.field?.let { "." + shortPath(it) } ?: ""}"; SOURCE -> "S(${x.data}${x.field?.let { "@" + it.substringAfter("->").substringBefore(':') } ?: ""} ${x.method?.substringBefore('(')})"; else -> "M(${x.data})" } }
    }
    private fun shortPath(path: String) = path.split(NEST).joinToString(".") { it.substringAfter("->").substringBefore(':') }
    private fun describe(v: RV) = "{" + v.whole.joinToString(",") { lname(it) } + (v.fields?.entries?.joinToString(",", " f[", "]") { (k, x) -> shortPath(k) + "=" + x.joinToString("|") { lname(it) } } ?: "") + " a=${v.alias}}"
    private fun describe(s: Summary) = "ret=${describe(s.ret)} out=${s.paramOut.entries.joinToString { "${it.key}:${describe(it.value)}" }} fw=${s.fieldWrites.entries.joinToString(",", "[", "]") { (f, x) -> (if ("->" in f) f.substringBefore(';').substringAfterLast('/') + "." + f.substringAfter("->").substringBefore(':') else f) + "=" + x.joinToString("|") { lname(it) } }} sinks=${s.sinks.size}${if (WATCH != null) s.sinks.values.joinToString(",", "[", "]") { e -> e.site.kind + ":" + e.site.detail.take(20) + "<-" + e.labels.joinToString("|") { lname(it) + "@" + e.placeOf(it).site.method.substringAfter("->").substringBefore('(') } } else ""}"

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
        // A list already at its bound keeps its entries: new ones are dropped instead of displacing
        // kept ones, so the summary stops changing (each change re-analyses every caller).
        val writesFull = fieldWrites.size >= MAX_FIELD_WRITES
        for (k in fieldWrites.keys + o.fieldWrites.keys) if (!writesFull || k in fieldWrites) out.fieldWrites[k] = widen(union(fieldWrites[k], o.fieldWrites[k]))
        for (e in sinks.values) for (l in e.labels) e.placeOf(l).let { addEdge(out.sinks, l, it.site, it.chain) }
        val sinksFull = sinks.size >= MAX_EDGES
        for (e in o.sinks.values) for (l in e.labels) if (!sinksFull || "${paramOf(l)}|${e.site.apiKey}" in out.sinks) e.placeOf(l).let { addEdge(out.sinks, l, it.site, it.chain) }
        if (writesFull && o.fieldWrites.keys.any { it !in fieldWrites } || sinksFull && o.sinks.size > out.sinks.size) out.bounded = true
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

    private class Cfg(val starts: IntArray, val ends: IntArray, val succ: Array<IntArray>) {
        val preds: Array<IntArray> by lazy {
            val lists = Array(starts.size) { ArrayList<Int>() }
            for ((b, out) in succ.withIndex()) for (s in out) lists[s] += b
            Array(starts.size) { lists[it].toIntArray() }
        }
        fun blockOf(k: Int): Int = java.util.Arrays.binarySearch(starts, k).let { if (it >= 0) it else -it - 2 }
    }
    private val cfgs = HashMap<Int, Cfg>()

    /** The instructions [mi] can run: the blocks its control-flow graph reaches from the entry (a variant's own branch). */
    private fun reachedInsns(mi: MethodInfo, code: DecodedCode): BooleanArray {
        val g = cfg(mi, code)
        val seen = BooleanArray(g.starts.size)
        val stack = ArrayDeque<Int>()
        if (g.starts.isNotEmpty()) { seen[0] = true; stack += 0 }
        while (stack.isNotEmpty()) for (s in g.succ[stack.removeLast()]) if (!seen[s]) { seen[s] = true; stack += s }
        val out = BooleanArray(code.insns.size)
        for (b in g.starts.indices) if (seen[b]) for (k in g.starts[b] until g.ends[b]) out[k] = true
        return out
    }

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
        // A variant of a dispatching method: past the switch on the dispatch id, only its own branch runs.
        if (mi.switchAt >= 0) succ[blockOf[mi.switchAt]] = intArrayOf(blockOf[mi.branch])
        Cfg(starts, ends, succ)
    }

    private inner class Ctx(val mi: MethodInfo, val d: DexFile, val code: DecodedCode, val summary: Summary, val cfg: Cfg) {
        val regs = code.code.registersSize
        /** Dispatch ids of the objects created here (by `new-instance` index); null: not known on every path. */
        val allocCases = HashMap<Int, Set<Int>?>()
        /** A coroutine's spilled locals (`L$0` ...) this method alone writes: what it stored, read back on resumption. */
        val spills = HashMap<String, RV>()
        /** Blocks that read each spilled local: analysed again when a store changes it. */
        val spillReaders = HashMap<String, MutableSet<Int>>()
        var block = -1
        var spillsChanged = false
        var requeue: (Int) -> Unit = {}
        /** Re-analysed past [DEGRADE_VISITS]: objects are flattened into fields and spilled locals read as fields again, as before. */
        val conservative = visitCount[mi.id] > DEGRADE_VISITS
        fun noteAlloc(k: Int, cases: Set<Int>?) {
            allocCases[k] = if (!allocCases.containsKey(k)) cases else allocCases[k]?.let { old -> cases?.let { old + it } }
        }
    }

    private fun analyze(mi: MethodInfo): Summary? {
        val code = mi.code() ?: return null
        val g = cfg(mi, code)
        // Start from what the last analysis stored (inputs only grow, so it is part of what this one stores).
        var spills: Map<String, RV>? = spillCache[mi.id]
        for (round in 0 until MAX_SPILL_ROUNDS) {
            val ctx = Ctx(mi, mi.d, code, Summary(), g)
            spills?.let { ctx.spills.putAll(it) }
            analyzeOnce(ctx, mi, g)
            // A resumption's branch read a spilled local before the store that fills it was analysed: again, from the
            // start, with what was stored (a value read early and joined later would lose which object it is).
            if (ctx.spills.isNotEmpty()) spillCache[mi.id] = HashMap(ctx.spills)
            if (!ctx.spillsChanged || round == MAX_SPILL_ROUNDS - 1) return ctx.summary.bounded()
            spills = HashMap(ctx.spills)
        }
        return null
    }

    private fun analyzeOnce(ctx: Ctx, mi: MethodInfo, g: Cfg) {
        val n = g.starts.size
        val inState = arrayOfNulls<Array<RV>>(n)
        inState[0] = entry(mi, ctx.regs)
        val work = ArrayDeque<Int>().apply { add(0) }
        val inWork = BooleanArray(n).apply { this[0] = true }
        // A resumption's branch reads a spilled local before the branch that stores it is analysed: read again then.
        ctx.requeue = { rb -> if (!inWork[rb] && inState[rb] != null) { inWork[rb] = true; work.addLast(rb) } }
        var steps = 0
        while (work.isNotEmpty()) {
            if (++steps > n * 40 + 1000) break
            val b = work.removeFirst()
            inWork[b] = false
            val state = inState[b]!!.copyOf()
            ctx.block = b
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
            mn.startsWith("iget") && privateSpill(ctx, normalizeField(ctx.d.fieldDescriptor(i.index))) -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                ctx.spillReaders.getOrPut(f) { HashSet() } += ctx.block
                set(i.regs[0], ctx.spills[f] ?: EMPTY_RV)
            }
            mn.startsWith("iput") && privateSpill(ctx, normalizeField(ctx.d.fieldDescriptor(i.index))) -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                val v = state[i.regs[0]]
                if (!(v.isEmpty() && v.alias == -1 && v.holder == -1)) {
                    val old = ctx.spills[f]
                    val now = if (old == null) v else join(old, v)
                    if (old == null || !now.same(old)) { ctx.spills[f] = now; if (ctx.spillReaders[f] != null) ctx.spillsChanged = true }
                }
            }
            mn.startsWith("iget") -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                set(i.regs[0], readField(state[i.regs[1]], f, globalOf(f), channel = f.substringAfterLast(':') in CHANNEL_TYPES))
            }
            mn.startsWith("iput") -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                val v = state[i.regs[0]]
                val value = flatten(v)
                if (value.isNotEmpty()) {
                    if (shared(f, static = false)) writeGlobal(ctx, f, value)
                    storeField(ctx, state, i.regs[1], f, v)
                }
            }
            mn.startsWith("sget") -> {
                val f = ctx.d.fieldDescriptor(i.index)
                val channel = f.substringAfterLast(':') in CHANNEL_TYPES
                set(i.regs[0], rv(union(globalOf(normalizeField(f)), staticSource(ctx, f)), holder = if (channel) STATIC_HOLDER else -1, holderField = if (channel) normalizeField(f) else null))
            }
            mn.startsWith("sput") -> {
                val f = normalizeField(ctx.d.fieldDescriptor(i.index))
                val value = flatten(state[i.regs[0]])
                if (value.isNotEmpty() && shared(f, static = true)) writeGlobal(ctx, f, value)
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

    /**
     * Field [f] of [base] read (`base.f`): what is known in that field ([global]: what it holds across instances) and
     * the object's own value, with what is known of the read object's own fields (`f.g`) as its fields. A field of a
     * parameter's object is its own symbolic label (`p.f`), and so is one of an object read from a parameter's field
     * (`p.a.f`): the caller knows those labels. The result remembers where it was read from (a known object, a field
     * of one, or a [channel] field), so that adding to it adds to that field.
     */
    private fun readField(base: RV, f: String, global: IntArray = EMPTY, channel: Boolean = false): RV {
        val fieldPart = union(deref(base.fields?.get(f)), global)
        // Two levels deep: the object read keeps what is known about its own fields (`base.f.g`).
        val inner = base.fields?.let { fs -> fs.entries.filter { it.key.startsWith(f + NEST) }.associate { it.key.substring(f.length + 1) to it.value } }
        val outer = base.holder in 0 until MAXP && base.holderField != null && NEST !in base.holderField
        val w = when {
            base.alias in 0 until MAXP -> union(union(fieldPart, base.whole.filter { it != base.alias }.toIntArray()), intArrayOf(paramField(base.alias, f)))
            outer -> {
                val a = paramField(base.holder, base.holderField!!)
                if (a in base.whole) union(union(fieldPart, base.whole.filter { it != a }.toIntArray()), intArrayOf(paramField(base.holder, base.holderField + NEST + f))) else union(fieldPart, base.whole)
            }
            else -> union(fieldPart, base.whole)
        }
        val (holder, holderField) = when {
            base.alias != -1 -> base.alias to f
            base.holder != -1 && base.holder != STATIC_HOLDER && base.holder != UNKNOWN_HOLDER && base.holderField != null ->
                base.holder to if (NEST in base.holderField) base.holderField else base.holderField + NEST + f
            channel -> UNKNOWN_HOLDER to f
            else -> -1 to null
        }
        return rv(w, fields = inner?.takeIf { it.isNotEmpty() }, holder = holder, holderField = holderField)
    }

    /**
     * [v] stored into field [f] of the object in register [r]. A parameter's object (a lambda capturing `this` or
     * `message`), or one read from a parameter's field (a lambda's `create` copying `this.$message`), is stored as a
     * reference to it, with what this method put into its fields one level down, not flattened: a read through the
     * field is that object's own field. Any other object is flattened into the field, unless [keepFields] (an element
     * added to a collection), where its own fields stay apart one level down (`f.g`).
     */
    private fun storeField(ctx: Ctx, state: Array<RV>, r: Int, f: String, v: RV, keepFields: Boolean = false) {
        val ref = refOf(ctx, v)
        if (ref == null && !keepFields) { mutate(ctx, state, r, null, f to flatten(v)); return }
        // Without a reference, a parameter's object is that parameter with everything in it, as flattening gives it.
        val own = if (ref != null) union(v.whole.filter { it != selfLabel(v) }.toIntArray(), intArrayOf(ref))
            else if (v.alias in 0 until MAXP) union(v.whole, intArrayOf(MAXP + v.alias)) else v.whole
        mutate(ctx, state, r, null, f to own)
        v.fields?.forEach { (g, l) -> if (l.isNotEmpty()) mutate(ctx, state, r, null, f + NEST + g.substringBefore(NEST) to l) }
    }

    /**
     * The contents of collection [from] added to the collection in register [r]: its own value (keys, what it took in as
     * a whole) and its elements, as elements. A parameter's collection (its class not known here), or an object with
     * fields other than elements, adds everything in it to the value, as any argument would.
     */
    private fun copyElements(ctx: Ctx, state: Array<RV>, r: Int, from: RV) {
        if (from.alias in 0 until MAXP || from.fields.orEmpty().keys.any { it != ELEMENT && !it.startsWith(ELEMENT + NEST) }) {
            mutate(ctx, state, r, flatten(from), null)
            return
        }
        mutate(ctx, state, r, from.whole, null)
        from.fields?.forEach { (g, l) -> if (l.isNotEmpty()) mutate(ctx, state, r, null, g to l) }
    }

    private fun globalOf(f: String): IntArray = global[f] ?: EMPTY

    private fun writeGlobal(ctx: Ctx, f: String, labels: IntArray) {
        // Data read back from a shared field is not stored into another one (see viaField).
        val concrete = labels.filter { isSource(it) && label(it).field == null && label(it).data !in HEAP_LOCAL }.map { viaField(it, f) }.sorted().toIntArray()
        val symbolic = labels.filter { isSymbolic(it) }.toIntArray()
        if (concrete.isNotEmpty()) {
            val old = global[f] ?: EMPTY
            val merged = union(old, concrete)
            if (merged.size != old.size) {
                global[f] = merged
                changedFields += f
                if (TRACE != null && TRACE in f) System.err.println("trace: $f += ${concrete.filter { it !in old }.joinToString { lname(it) }} in ${ctx.mi.ref}")
            }
        }
        if (symbolic.isNotEmpty()) ctx.summary.fieldWrites[f] = widen(union(ctx.summary.fieldWrites[f], symbolic))
    }

    /**
     * Adds labels to the object in register [r] (to its value, or to one of its fields), and to
     * every register holding the same object; a parameter's object is reported in the summary.
     */
    private fun mutate(ctx: Ctx, state: Array<RV>, r: Int, whole: IntArray?, field: Pair<String, IntArray>?, intoHeld: Boolean = false) {
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
        // A channel (LiveData, StateFlow, a queue) read from a field: what is posted reaches whoever reads that field.
        if (holderField != null && NEST !in holderField && holderField.substringAfterLast(':') in CHANNEL_TYPES && shared(holderField, target.holder == STATIC_HOLDER)) {
            writeGlobal(ctx, holderField, union(whole, deref(field?.second)))
        }
        // An element read from a collection (see [ELEMENT]) is not written back into it: what is added to the element
        // afterwards is not followed (on Tusky, ConstraintLayout's dependency graph, whose nodes keep lists of each other,
        // re-analysed its update methods past their safeguard).
        if (target.holder != -1 && target.holder != STATIC_HOLDER && target.holder != UNKNOWN_HOLDER && holderField != null && !holderField.endsWith(ELEMENT)) {
            // Added to the held object as a whole (`this.sb.append(x)`): the holder's field. Written into one
            // of its fields (`this.model.loc = x`): that path of the holder, two levels at most (deeper
            // writes count for the second level as a whole).
            val added = HashMap<String, IntArray>()
            if (whole != null && whole.isNotEmpty()) added[holderField] = whole
            if (field != null && field.second.isNotEmpty()) {
                val path = if (NEST in holderField) holderField else holderField + NEST + field.first.substringBefore(NEST)
                added[path] = union(added[path], field.second)
            }
            for (x in 0 until ctx.regs) if (state[x].alias == target.holder) {
                val v = state[x]
                state[x] = rv(v.whole, v.alias, HashMap(v.fields.orEmpty()).also { m -> added.forEach { (key, l) -> m[key] = union(m[key], l) } }, v.holder, v.holderField)
            }
            if (target.holder in 0 until MAXP) {
                val old = ctx.summary.paramOut[target.holder] ?: EMPTY_RV
                ctx.summary.paramOut[target.holder] = join(old, rv(EMPTY, -1, added))
            }
        }
        checkMarks(ctx, state[r])
        // Data a callee added to a field that holds a stream (a thread's `this.out.write(buf)`, seen at the thread's
        // start): written to that stream. Not when what is added is itself a stream (the callee assigned the field).
        if (intoHeld && field != null && NEST !in field.first && field.second.none(::isStreamMark)) state[r].fields?.get(field.first)?.let { held ->
            if (held.any(::isStreamMark)) checkMarks(ctx, RV(union(held.filter(::isStreamMark).toIntArray(), deref(field.second)!!.filter { isSource(it) || isSymbolic(it) }.toIntArray()), null, -1))
        }
    }

    /**
     * A stream to the network or a file that now carries data: written out. Both are on the stream's
     * own value (a wrapping stream takes in the one it wraps, `write` takes in the data); an object
     * that merely holds a stream in a field (a map view with its tile archive) is not one.
     */
    private fun checkMarks(ctx: Ctx, v: RV) {
        val all = v.whole
        val marks = all.filter(::isStreamMark)
        if (marks.isEmpty()) return
        val data = all.filter { isSource(it) || isSymbolic(it) }.toIntArray()
        if (data.isEmpty()) return
        for (m in marks) {
            val l = label(m)
            val site = when (l.data) {
                "net" -> SinkSite("NETWORK", l.api!!, "HTTP request body or socket", ctx.mi.ref, null)
                "pipe" -> SinkSite("IPC", l.api!!, "pipe to another app or process", ctx.mi.ref, null)
                else -> SinkSite("STORAGE", l.api!!, "file", ctx.mi.ref, null)
            }
            record(ctx, data, site)
            // Written to a file of a known name: read back wherever a file of that name is opened.
            if (l.data!!.startsWith("file:")) writeGlobal(ctx, l.data, data)
        }
    }

    /** A stream to the network, a pipe or a file (`file:<name>` when its name is known). */
    private fun isStreamMark(l: Int) = isMark(l) && label(l).data!!.let { it == "net" || it == "pipe" || it == "file" || it.startsWith("file:") }

    /** The name a `File` was created with (`path:<name>`). */
    private fun isPath(l: Int) = isMark(l) && label(l).data!!.startsWith("path:")

    private fun paths(v: RV): List<String> = v.whole.filter(::isPath).map { label(it).data!!.removePrefix("path:") }

    /** A file's name, for matching where it is written and read: the last part of its path. */
    private fun baseName(s: String): String? = s.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() && it.length <= 120 }

    /**
     * Names of the file a stream constructor opens, from its first argument (the path; a later string is a
     * mode or a charset, `RandomAccessFile(file, "rw")`): a constant path's last part, or the name a `File`
     * was created with.
     */
    private fun fileNames(ctx: Ctx, k: Int, types: List<String>, argRegs: IntArray, state: Array<RV>): List<String> {
        val r = argRegs.getOrElse(1) { -1 }
        if (r !in 0 until ctx.regs) return emptyList()
        return when (types.getOrNull(1)) {
            STRING -> constString(ctx, k, r)?.let(::baseName)?.let(::listOf) ?: paths(state[r])
            FILE -> paths(state[r])
            else -> emptyList()
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
            for (p in firstArg until types.size) record(ctx, passed(types[p], arg(p)), site)
        }
        for (spec in sinkSpecs(owner, name, static)) {
            // A PrintStream over a file or a socket stores or sends (its stream's mark says so); only the console is a log.
            if (spec.kind == "LOG" && owner == "Ljava/io/PrintStream;" && flatten(arg(0)).any(::isStreamMark)) continue
            // An inherited platform method is named by its platform class, not the app class it was called on.
            val api = if (spec.owner == owner) display(ref) else display("${spec.owner}->$name")
            // System.out and System.err are the log; any other PrintStream writes to the stream it was given (a GPX export's file).
            val toFile = spec.kind == "LOG" && owner == "Ljava/io/PrintStream;" && !console(ctx, k, argRegs.getOrElse(0) { -1 })
            val site = if (toFile) SinkSite("STORAGE", api, "file", ctx.mi.ref, null) else SinkSite(spec.kind, api, spec.detail, ctx.mi.ref, spec.attention)
            val params = spec.params ?: (firstArg until types.size).toList()
            for (p in params) if (p < types.size) record(ctx, passed(types[p], arg(p)), site)
        }

        // An instance of a merged lambda group created with a known id runs that case only.
        if (!static && name == "<init>") dispatch[owner]?.let { disp ->
            val created = arg(0).alias
            if (created <= -2) ctx.noteAlloc(-2 - created, disp.fixed[ref]?.let { setOf(it) } ?: disp.constructors[ref]?.let { p -> dispatchValue(ctx, k, argRegs.getOrElse(p + 1) { -1 }) })
        }
        // Internal code: instantiate the summaries of every target.
        val resolved = targetsAt(ctx.mi, ctx.code, ctx.cfg, k, ref, mn)
        val targets = if (static || resolved.internal.none { it.base != null }) resolved.internal else forCases(ctx, arg(0), resolved.internal)
        var result: RV = EMPTY_RV
        // Platform and library methods are modelled by the class that defines them, not the app subclass named in the call.
        val model = if (resolved.internal.isEmpty() && !resolved.generic) platformOwner(owner) else owner
        // A platform or library method (`OutputStream.write`) on an object whose class is not known
        // may run the platform's own implementation as well as the app's overrides.
        val platformToo = resolved.internal.isNotEmpty() && !resolved.exact && !static && !mn.startsWith("invoke-direct") && !mn.startsWith("invoke-super") &&
            (owner !in classFlags || modelled(owner))
        if (resolved.internal.isNotEmpty()) {
            // The targets are alternatives (one of them runs): each starts from the state before the
            // call, and what they leave is joined, so one target's effects never feed another's.
            val pre = if (targets.size + (if (platformToo) 1 else 0) > 1) state.copyOf() else null
            var post: Array<RV>? = null
            var first = true
            for (t in targets) {
                val s = summaries[t.id] ?: continue
                val st = pre?.copyOf() ?: state
                val r = instantiate(ctx, s, argRegs, st, k)
                result = if (first) r else join(result, r)
                first = false
                if (pre != null) post = post?.let { acc -> Array(acc.size) { x -> join(acc[x], st[x]) } } ?: st
            }
            if (platformToo) {
                val st = pre?.copyOf() ?: state
                val r = external(ctx, ref, model, name, returnType, static, types, argRegs, st)
                result = if (first) r else join(result, r)
                if (pre != null) post = post?.let { acc -> Array(acc.size) { x -> join(acc[x], st[x]) } } ?: st
            }
            post?.copyInto(state)
        } else {
            // App objects handed to platform or library code (listeners, Runnables, lambdas, observers) run later.
            // What they return is the call's result where the call returns one (`withContext(io) { load() }`,
            // `executor.submit(callable)`, `stream.map(f)`). Where that is all of it ([returnsCallbackResult]), the
            // object itself, with what it captured, is not: a builder's block emits through a parameter
            // (`flow { emit(x) }`, `Observable.create { it.onNext(x) }`), so there the object stays in.
            val wantsResult = returnType.startsWith("L") || returnType.startsWith("[")
            // Only for an object created here (`new Copier(in, out).start()`), and [callbacks] narrows it further.
            val onReceiver = "$model->$name" in RECEIVER_CALLBACKS && !static && arg(0).alias <= -2
            val objects = if (!resolved.generic && (model !in classFlags || modelled(model))) callbackObjects(ctx, k, static, types, argRegs, state, onReceiver) else null
            val skip = if (wantsResult && returnsCallbackResult(model, name)) objects?.map { it.first }?.toSet().orEmpty() else emptySet()
            result = external(ctx, ref, model, name, returnType, static, types, argRegs, state, skip)
            if (objects != null) {
                val returned = callbacks(ctx, k, static, types, argRegs, state, wantsResult, "$model->$name" in RESPONSE_CALLBACKS, onReceiver, objects)
                if (returned !== EMPTY_RV && wantsResult) result = rv(union(result.whole, flatten(returned)), result.alias, result.fields)
            }
        }
        // A server's response is the server's data, not the request's: a connection's input stream, an OkHttp
        // call's response, the body a Retrofit method returns. A call or response object keeps its request (an OkHttp
        // response's `toString()` prints the URL), so the request is a field of it: the body read from it carries none.
        if (endpoints[ref] != null || "$model->$name" in RESPONSE_OBJECTS) {
            // An OkHttp call built here knows its URL apart; any other keeps the whole request.
            val request = (if (static) null else arg(0).fields?.get(REQUEST_URL)?.filterNot(::isMark)?.toIntArray()) ?: union(flatten(result), if (static) EMPTY else passed(types[0], arg(0)))
            result = if (request.isEmpty() || returnType !in HOLDS_REQUEST) EMPTY_RV else rv(EMPTY, fields = mapOf(HELD_REQUEST to request))
        } else if ("$model->$name" in RESPONSES) result = EMPTY_RV
        // An object decoded from JSON (kotlinx.serialization, Gson, Moshi, Jackson) holds the data in fields, but
        // which ones is not known: the object as a whole (encoded again, passed to a library, printed) carries it,
        // a field the app reads by name (`message.topic`, set by the app and not in the JSON) does not.
        if ("$model->$name" in DECODERS) {
            val data = flatten(result)
            result = if (data.isEmpty()) EMPTY_RV else rv(EMPTY, fields = mapOf(CONTENT to data))
        }
        // Reading into a buffer (`in.read(buf)`): from a file opened by name, the buffer takes what the file holds;
        // from a stream an object holds in a field (a copying thread's `this.in`), what the caller put there. Not
        // from other streams: a library decoding persisted bytes through a helper (Paho restoring its messages)
        // spreads them through every object of its client, which the object model cannot tell apart.
        if (!static && (name == "read" || name == "readFully") && model.startsWith("Ljava/io/")) {
            val stream = arg(0).whole
            val named = stream.any(::isPath)
            val held = stream.any { it >= MAXP * 2 && label(it).kind == PARAM_FIELD }
            val buffer = (1 until types.size).firstOrNull { types[it].startsWith("[") }
            if (buffer != null && (named || held)) stream.filter { !isMark(it) }.toIntArray().takeIf { it.isNotEmpty() }?.let { mutate(ctx, state, argRegs[buffer], it, null) }
        }
        // A fix's time, provider and accuracy describe the measurement, not where the device is; an app
        // subclass overriding them (`CorrectingLocation.getTime()`) keeps that meaning.
        if (name in LOCATION_METADATA && !static && LOCATION in ancestors(owner)) result = EMPTY_RV
        // Stored and read back: SharedPreferences keys and SQLite tables.
        storage(ctx, k, model, name, types, argRegs, state)?.let { read -> if (read.isNotEmpty()) result = rv(union(result.whole, read), result.alias, result.fields) }
        // A whole number read from a table (from the cursor's own labels, the table's, or a cursor a caller passes) is
        // an id, a time, a count or a flag, not where the device is: coordinates are stored as real numbers or text
        // (scaled integers, E6, are missed). Other personal data read as a number stays.
        if (model in CURSORS && name in INTEGER_READS && result.whole.any { isSymbolic(it) || isSource(it) && label(it).data == "location" })
            result = rv(result.whole.filter { isSource(it) && label(it).data != "location" }.toIntArray(), result.alias, result.fields)

        // Sources replace or add to the result.
        sourceOf(ctx, k, ref, model, name, static, argRegs, state)?.let { (data, api) ->
            val l = source(data, api, ctx.mi.ref)
            sourceUses.getOrPut("$data|$api") { LinkedHashSet() } += ctx.mi.ref
            val buffer = ARG_SOURCES["$model->$name"]
            if (buffer != null) mutate(ctx, state, argRegs.getOrElse(buffer) { -1 }, intArrayOf(l), null)
            else result = rv(union(result.whole, intArrayOf(l)), result.alias, result.fields)
        }
        // Streams to the network or a file are marked, to recognise data written to them.
        STREAM_MARKS["$model->$name"]?.let { kind -> result = rv(union(result.whole, intArrayOf(mark(kind, display(ref)))), result.alias, result.fields) }
        if (name == "<init>" && !static) STREAM_MARKS["$owner-><init>"]?.let { kind ->
            // A file stream opened by name carries the name, so that what is written is found where the file is read.
            val named = if (kind == "file") fileNames(ctx, k, types, argRegs, state) else emptyList()
            val marks = if (named.isEmpty()) intArrayOf(mark(kind, display(ref))) else named.map { mark("file:$it", display(ref)) }.sorted().toIntArray()
            mutate(ctx, state, argRegs[0], marks, null)
        }
        // A wrapping stream over an object the method knows (`ObjectOutputStream(bytes)`) writes into it: what is
        // written to the wrapper lands in the wrapped stream's content (`bytes.toByteArray()`).
        if (name == "<init>" && !static && owner in WRAPPING_STREAMS && types.size >= 2) {
            val inner = arg(1).alias
            val self = arg(0).alias
            if (inner != -1 && self != -1 && inner != self) for (x in 0 until regs) if (state[x].alias == self) state[x].let { v -> state[x] = RV(v.whole, v.fields, v.alias, inner, CONTENT) }
        }
        // A file created under a constant name (`File(dir, "track.txt")`) is marked with it.
        if (name == "<init>" && !static && owner == FILE && types.size >= 2) {
            val r = argRegs[types.lastIndex]
            val names = if (types.last() == STRING && r in 0 until regs) constString(ctx, k, r)?.let(::baseName)?.let(::listOf) ?: paths(state[r]) else emptyList()
            if (names.isNotEmpty()) mutate(ctx, state, argRegs[0], names.map { mark("path:$it", "java.io.File") }.sorted().toIntArray(), null)
        }
        if (owner == "Landroid/net/Uri;" && name == "parse") constString(ctx, k, argRegs.getOrElse(0) { -1 })?.let { s ->
            uriData(s)?.let { result = rv(union(result.whole, intArrayOf(mark("uri:$it", "Uri.parse(\"${s.take(60)}\")"))), result.alias, result.fields) }
        }
        if (name == "openFileOutput" && isContext(owner)) {
            val file = constString(ctx, k, argRegs.getOrElse(1) { -1 })?.let(::baseName)
            result = rv(union(result.whole, intArrayOf(mark(if (file != null) "file:$file" else "file", "Context.openFileOutput"))), result.alias, result.fields)
        }
        state[regs] = result
    }

    /**
     * A callee's summary at this call: its parameter labels replaced by what the arguments carry.
     * [values], when given, are the argument values (a callback's parameters have no register).
     */
    private fun instantiate(ctx: Ctx, s: Summary, argRegs: IntArray, state: Array<RV>, k: Int, values: Array<RV>? = null): RV {
        // For a callback, symbolic results about this method's own `this` are not kept (see [carries]).
        fun keep(l: Int) = values == null || !isSymbolic(l) || carries(ctx, intArrayOf(l), self = values.firstOrNull()?.alias == 0)
        val regs = ctx.regs
        fun arg(p: Int): RV = values?.getOrNull(p) ?: argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        // With [inner] (a returned value), what is known inside a parameter's field `p.f` (its `f.g`) is
        // collected there as the result's own fields instead of being merged into its value.
        // The object of this method's parameter r as it is here (what this method put into its fields so far).
        fun stateOf(r: Int): RV? = state.firstOrNull { it.alias == r } ?: ctx.summary.paramOut[r]
        // A reference the argument holds in a field (this method's terms), read through: as a whole ([rest] null),
        // everything in that object; its field [rest], that field only.
        fun throughRef(x: Int, rest: String?): IntArray {
            val xl = label(x)
            if (rest == null) return if (xl.field == null) union(intArrayOf(MAXP + xl.param), stateOf(xl.param)?.let { flatten(it) }) else intArrayOf(paramField(xl.param, xl.field))
            return when {
                xl.field == null -> {
                    var o = intArrayOf(paramField(xl.param, rest))
                    stateOf(xl.param)?.let { st ->
                        o = union(o, st.whole.filter { it != xl.param }.toIntArray())
                        st.fields?.let { sf -> o = union(o, deref(sf[rest])); for ((key, v) in sf) if (key.startsWith(rest + NEST)) o = union(o, deref(v)) }
                    }
                    o
                }
                NEST !in xl.field -> intArrayOf(paramField(xl.param, xl.field + NEST + rest))
                else -> intArrayOf(paramField(xl.param, xl.field))
            }
        }
        fun subst(labels: IntArray, inner: HashMap<String, IntArray>? = null): IntArray {
            if (labels.isEmpty()) return EMPTY
            var out = EMPTY
            val concrete = ArrayList<Int>()
            for (l in labels) {
                when {
                    isParam(l) -> out = union(out, arg(l).whole)
                    isParamAll(l) -> out = union(out, flatten(arg(l - MAXP)))
                    isRef(l) -> {
                        // The callee stored its parameter's object (or the one at its field path) in a field: the same
                        // object here, as a reference when this method can name it, else everything in it.
                        val lb = label(l)
                        val a = arg(lb.param)
                        val r = refOf(ctx, a)
                        if (r == null) out = union(out, if (lb.field == null) flatten(a) else subst(intArrayOf(paramField(lb.param, lb.field))))
                        else {
                            val self = selfLabel(a)
                            val rl = label(r)
                            val target = when {
                                lb.field == null -> r
                                rl.field == null -> ref(rl.param, lb.field)
                                NEST !in rl.field && NEST !in lb.field -> ref(rl.param, rl.field + NEST + lb.field)
                                else -> paramField(rl.param, (rl.field + NEST + lb.field).split(NEST).take(2).joinToString(NEST))
                            }
                            out = union(union(out, a.whole.filter { it != self }.toIntArray()), intArrayOf(target))
                        }
                    }
                    l >= MAXP * 2 && label(l).kind == PARAM_FIELD -> {
                        // `p.f`: the argument, its field f and everything known inside f; `p.f.g`: the argument,
                        // its field f as a whole and f.g. A reference in f is read through: `p.f.g` is that object's g.
                        val lb = label(l)
                        val path = lb.field!!
                        val top = path.substringBefore(NEST)
                        val a = arg(lb.param)
                        out = union(out, a.whole)
                        a.fields?.let { fs ->
                            fs[top]?.let { held ->
                                if (held.none(::isRef)) out = union(out, held)
                                else {
                                    out = union(out, held.filterNot(::isRef).toIntArray())
                                    for (x in held) if (isRef(x)) out = union(out, throughRef(x, if (NEST in path && !ctx.conservative) path.substringAfter(NEST) else null))
                                }
                            }
                            if (NEST in path) out = union(out, deref(fs[path]))
                            else for ((key, v) in fs) if (key.length > top.length && key[top.length] == NEST[0] && key.startsWith(top)) {
                                if (inner == null) out = union(out, deref(v)) else key.substring(top.length + 1).let { g -> inner[g] = union(inner[g], v) }
                            }
                        }
                        if (a.alias in 0 until MAXP) out = union(out.filter { it != a.alias }.toIntArray(), intArrayOf(paramField(a.alias, path)))
                        else if (a.holder in 0 until MAXP && a.holderField != null && NEST !in a.holderField) {
                            // The argument is the caller's `q.h`: the callee's `p.f` is the caller's `q.h.f`.
                            val h = paramField(a.holder, a.holderField)
                            if (h in out) out = union(out.filter { it != h }.toIntArray(), intArrayOf(paramField(a.holder, a.holderField + NEST + top)))
                        }
                    }
                    else -> concrete += l
                }
            }
            return if (concrete.isEmpty()) out else union(out, concrete.sorted().toIntArray())
        }
        // Return value: the same object as an argument (a builder returning itself), plus what the summary adds.
        val ret = s.ret
        var result = if (ret.alias in 0 until MAXP) arg(ret.alias) else EMPTY_RV
        val inner = HashMap<String, IntArray>()
        // A reference the callee stored in field f of an object: what this method knows of the referenced object's
        // fields goes one level down (f.g), as a store here would put it.
        fun nested(v: IntArray, each: (String, IntArray) -> Unit) {
            for (l in v) if (isRef(l) && label(l).field == null) {
                val a = arg(label(l).param)
                if (refOf(ctx, a) != null) a.fields?.forEach { (g, x) -> if (x.isNotEmpty()) each(g.substringBefore(NEST), x) }
            }
        }
        ret.fields?.forEach { (f, v) ->
            inner[f] = union(inner[f], subst(v))
            if (NEST !in f) nested(v) { g, x -> inner[f + NEST + g] = union(inner[f + NEST + g], x) }
        }
        val retWhole = subst(ret.whole, inner)
        result = rv(union(result.whole, retWhole), result.alias, if (inner.isEmpty()) result.fields else HashMap(result.fields.orEmpty()).also { m -> inner.forEach { (f, v) -> m[f] = union(m[f], v) } })
        // Objects the callee changed.
        for ((p, delta) in s.paramOut) {
            val r = argRegs.getOrNull(p) ?: continue
            if (r !in 0 until regs) continue
            val w = subst(delta.whole)
            if (w.isNotEmpty()) mutate(ctx, state, r, w, null)
            delta.fields?.forEach { (f, v) ->
                subst(v).takeIf { it.isNotEmpty() }?.let { mutate(ctx, state, r, null, f to it, intoHeld = true) }
                if (NEST !in f) nested(v) { g, x -> mutate(ctx, state, r, null, f + NEST + g to x) }
            }
        }
        // Fields it writes.
        for ((f, v) in s.fieldWrites) subst(v).filter(::keep).toIntArray().takeIf { it.isNotEmpty() }?.let { writeGlobal(ctx, f, it) }
        // Parameters that reach a sink inside it.
        for (e in s.sinks.values) for (x in e.labels) {
            // Each label at its own place (see [addEdge]).
            val place = e.placeOf(x)
            val chain = if (place.chain.size >= MAX_CHAIN) place.chain else listOf(ctx.mi.ref) + place.chain
            for (l in subst(intArrayOf(x))) when {
                isSource(l) -> recordFlow(l, place.site, chain)
                isSymbolic(l) && keep(l) -> addEdge(ctx.summary.sinks, l, place.site, chain)
            }
        }
        return result
    }

    /**
     * Library and platform code: data flows from the receiver and arguments to the result; containers
     * and builders take in what is added to them. A Kotlin `Continuation` argument is control flow
     * (the caller's state machine), not data.
     */
    private fun external(ctx: Ctx, ref: String, owner: String, name: String, returnType: String, static: Boolean, types: List<String>, argRegs: IntArray, state: Array<RV>, skip: Set<Int> = emptySet()): RV {
        val regs = ctx.regs
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        val first = if (static) 0 else 1
        // A collection keeps an object added to it as its element ([ELEMENT]): the argument of the element's (erased) type,
        // the last one (`add(i, e)`, `put(k, v)`); keys and indices go into the collection's own value.
        val collection = !static && owner in COLLECTIONS
        val element = if (collection && name in ELEMENT_STORES) types.indexOfLast { it == OBJECT }.takeIf { it >= 1 } ?: -1 else -1
        // Another collection's contents (`addAll(c)`, `putAll(m)`, `new ArrayList(c)`): its elements stay elements.
        val copied = if (collection && (name == "addAll" || name == "putAll" || name == "<init>")) types.indexOfLast { it in COLLECTIONS }.takeIf { it >= 1 } ?: -1 else -1
        var args = EMPTY
        for (p in first until argRegs.size) if (p != element && p != copied && p !in skip && types.getOrNull(p) != CONTINUATION) args = union(args, passed(types.getOrElse(p) { "" }, arg(p)))
        // A file made from a directory and a name is named by the name (see [fileNames]), not by its directory's.
        if (owner == FILE && name == "<init>") args = args.filter { !isPath(it) }.toIntArray()
        // A platform system service (`LocationManager.getProviders()`) answers with platform state: the app cannot
        // put its own data into one, so what the receiver's labels say (a field path collapsed into its holder) is not in it.
        val receiver = if (static || contextType(types[0]) || owner in SYSTEM_SERVICES) EMPTY_RV else arg(0)
        if (!static && args.isNotEmpty() && absorbs(owner, name)) mutate(ctx, state, argRegs[0], args, null)
        if (element != -1) storeField(ctx, state, argRegs[0], ELEMENT, arg(element), keepFields = true)
        if (copied != -1) copyElements(ctx, state, argRegs[0], arg(copied))
        // `Collections.addAll(c, a, b)`: the collection takes in the elements.
        if (static && owner == "Ljava/util/Collections;" && name == "addAll" && argRegs.size == 2) mutate(ctx, state, argRegs[0], passed(types[1], arg(1)), null)
        // A map entry's new value is the map's element (the entry read from it remembers the map).
        if (collection && owner == MAP_ENTRY && name == "setValue") mutate(ctx, state, argRegs[0], args, null)
        // OkHttp keeps a request's URL apart from its body: a logged response prints the URL only (see [REQUEST_URL]).
        // A marker says the URL is known even when it carries nothing (a constant).
        if (!static && owner == OKHTTP_REQUEST_BUILDER && name == "url") mutate(ctx, state, argRegs[0], null, REQUEST_URL to union(args, intArrayOf(mark("url", "okhttp3.Request.Builder.url"))))
        if (!static && (owner == OKHTTP_REQUEST_BUILDER && name == "build" || owner == "Lokhttp3/OkHttpClient;" && name == "newCall")) {
            val url = (if (name == "build") receiver else arg(1)).fields?.get(REQUEST_URL)
            return rv(union(receiver.whole, args), fields = url?.let { mapOf(REQUEST_URL to it) })
        }
        if (returnType == "V" || returnType == "Z" || name in NON_DATA) return EMPTY_RV
        // toString describes the object's fields.
        if (name == "toString" && !static) return rv(union(flatten(receiver), args))
        val after = if (static) EMPTY_RV else state.getOrElse(argRegs[0]) { EMPTY_RV }
        if (collection) {
            // A map's keys are in its own value, its values are its element.
            if (name in KEY_VIEWS) return rv(after.whole)
            // An iterator or a view (`iterator()`, `entrySet()`, `subList`), and an entry's key or value: the collection itself.
            if (name in ELEMENT_VIEWS || owner == MAP_ENTRY && (name == "getKey" || name == "getValue")) return after
            // Read back (`get`, `next`, `poll`; `put` and `set` return the element they replace): the element, as a field read gives it.
            // With a default (`getOrDefault(k, d)`, a SparseArray's `get(key, d)`), that too.
            val default = name == "getOrDefault" || name == "get" && types.size == 3 && types[2] == OBJECT
            if (name in ELEMENT_READS || element != -1) return readField(after, ELEMENT).let { e -> if (default) join(e, rv(args)) else e }
            // Anything else (`toArray()`, `stream()`): everything in it, elements included.
            return rv(union(flatten(after), args))
        }
        // A builder returns itself: the same object, with what it took in.
        if (!static && returnType == owner && absorbs(owner, name)) return after
        // A library reading the object (a JSON tree's `get`, `toByteArray()`) reaches its content (see [CONTENT]).
        return rv(union(union(receiver.whole, deref(receiver.fields?.get(CONTENT))), args))
    }

    /**
     * App objects passed to platform or library code: a listener, a Runnable, a Kotlin lambda, an
     * Observer, a FlowCollector. The code that receives them calls their callback methods later; that
     * call is taken here, at the registration, with the object as `this` (so data it captured in its
     * fields goes along) and the registering receiver's data as the callback's arguments
     * (`task.addOnSuccessListener(l)` gives `l.onSuccess` the task's result,
     * `liveData.observe(owner, o)` gives `o.onChanged` what was posted).
     */
    /**
     * Library calls whose result is what the callback passed to them returns, nothing else: Kotlin coroutine builders
     * (classes merged by R8 keep the method names), an executor's `submit`, a future's `supplyAsync`, `lazy`, Room's
     * transactions.
     */
    private fun returnsCallbackResult(owner: String, name: String): Boolean = when {
        owner.startsWith("Lkotlinx/coroutines/") -> name in setOf("withContext", "runBlocking", "async", "withTimeout", "withTimeoutOrNull", "coroutineScope", "supervisorScope", "invoke")
        owner.startsWith("Ljava/util/concurrent/") -> name in setOf("submit", "invokeAny", "supplyAsync", "thenApply", "thenApplyAsync", "thenCompose", "thenComposeAsync")
        owner.startsWith("Lkotlin/") -> name == "lazy"
        owner.startsWith("Landroidx/room/") -> name == "runInTransaction" || name == "withTransaction"
        else -> false
    }

    /** The arguments that are app callback objects (see [callbacks]), with their callback methods; null: none. */
    private fun callbackObjects(ctx: Ctx, k: Int, static: Boolean, types: List<String>, argRegs: IntArray, state: Array<RV>, receiver: Boolean): List<Pair<Int, List<MethodInfo>>>? {
        val regs = ctx.regs
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        // `thread.start()`, `task.execute(args)`: the receiver's own callback runs, with the arguments.
        val first = if (static || receiver) 0 else 1
        var found: MutableList<Pair<Int, List<MethodInfo>>>? = null
        for (p in first until types.size) {
            val t = types[p]
            if (t.length == 1 || t.startsWith("[") || t == CONTINUATION || t == "Ljava/lang/String;") continue
            val callbacks = forCases(ctx, arg(p), dynamicTypes(ctx, arg(p), t, k, argRegs.getOrElse(p) { -1 }).flatMap { callbackMethods(it, t) }.distinct())
            if (callbacks.isNotEmpty()) (found ?: ArrayList<Pair<Int, List<MethodInfo>>>().also { found = it }) += p to callbacks
        }
        return found
    }

    private fun callbacks(ctx: Ctx, k: Int, static: Boolean, types: List<String>, argRegs: IntArray, state: Array<RV>, wantsResult: Boolean, response: Boolean, receiver: Boolean, objects: List<Pair<Int, List<MethodInfo>>>): RV {
        val regs = ctx.regs
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        val first = if (static || receiver) 0 else 1
        var returned = EMPTY_RV
        // A request's callback receives the server's response, not the request (`call.enqueue(callback)`).
        var delivered = if (static || response || receiver) EMPTY else passed(types[0], arg(0))
        if (static || receiver) for (p in first until types.size) if (objects.none { it.first == p } && types[p] != CONTINUATION) delivered = union(delivered, passed(types[p], arg(p)))
        // Different callbacks of an object can all run; the variants of one (a merged lambda whose
        // case is not known) are alternatives, each applied to the state before the registration.
        for ((p, callbacks) in objects) for (group in callbacks.groupBy { it.base ?: it }.values) {
            val pre = if (group.size > 1) state.copyOf() else null
            var post: Array<RV>? = null
            for (cb in group) {
                val st = pre?.copyOf() ?: state
                val obj = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { st[it] } ?: EMPTY_RV
                // Nothing to carry in: the callback's own analysis already records its flows. Data counts when
                // it is a source, or comes from a parameter other than `this` (`post(loc)` capturing `loc`);
                // an activity registering itself carries only its own `this`, which no caller taints.
                // Where the call returns the callback's result (`withContext(io) { queue.load() }`), that result
                // counts even when nothing goes in.
                if (!wantsResult && !carries(ctx, delivered) && !carries(ctx, flatten(obj), self = obj.alias == 0)) continue
                // A thread started here counts when it holds a stream to write to (a copier, `new Copier(in, pipeOut)`)
                // or captured personal data read here: generic worker and pool threads (ExoPlayer's decoder, executor
                // threads wrapping a Runnable) re-analysed Tusky's callers past their safeguard for nothing.
                if (receiver && p == 0 && flatten(obj).none(::isSource) && obj.fields?.values?.any { f -> f.any(::isStreamMark) } != true) continue
                // The registering method depends on the callback's effects and result: re-analysed when they change.
                callbackUsers.getOrPut(cb.id) { HashSet() } += ctx.mi.id
                val s = summaries[cb.id] ?: continue
                // A callback without effects carries nothing onward, unless what it returns is the call's result.
                if (s.sinks.isEmpty() && s.fieldWrites.isEmpty() && s.paramOut.isEmpty() && (!wantsResult || s.ret.isEmpty())) continue
                if (DEBUG) callbackCounts.merge(cb.ref + " <- " + types[p], 1, Int::plus)
                val n = cb.paramRegs.size
                val values = Array(n) { j -> if (j == 0) obj else rv(delivered) }
                returned = join(returned, instantiate(ctx, s, IntArray(n) { j -> if (j == 0) argRegs[p] else -1 }, st, k, values))
                if (pre != null) post = post?.let { acc -> Array(acc.size) { x -> join(acc[x], st[x]) } } ?: st
            }
            post?.copyInto(state)
        }
        return returned
    }

    /**
     * Whether [labels] bring data into a callback. A symbolic label counts when it is about a parameter
     * other than `this`, or about a field of `this` captured by an object created here (a lambda
     * capturing `this.message`). `this` as a whole does not: an activity registering itself, or an
     * anonymous class holding its outer activity, carries only that `this`, which no caller taints.
     */
    private fun carries(ctx: Ctx, labels: IntArray, self: Boolean = true): Boolean = labels.any { l ->
        if (isSymbolic(l)) ctx.mi.m.isStatic || paramOf(l) >= 1 || !self && l >= MAXP * 2 else isSource(l) || isMark(l)
    }

    /** Classes the object can be: where it was created in this method, `this`, or the few app classes implementing [declared]. */
    private fun dynamicTypes(ctx: Ctx, v: RV, declared: String, k: Int = -1, r: Int = -1): List<String> {
        val a = v.alias
        if (a <= -2) {
            val insn = ctx.code.insns.getOrNull(-2 - a) ?: return emptyList()
            return if (insn.op.mnemonic == "new-instance") listOf(ctx.d.type(insn.index)) else emptyList()
        }
        if (a == 0 && !ctx.mi.m.isStatic) return listOf(ctx.mi.owner)
        // The register's static type (a field's, a cast's, a call's return or a parameter's type) narrows the
        // candidates: the object is of that type or below it.
        val st = if (k >= 0 && r >= 0) staticType(ctx, k, r) else null
        if (st != null && st != declared && st in classFlags) {
            // Only narrows: the parameter type's few implementations that are the static type or below it.
            if (st in byOwner || st in subtypes) return implementations(declared).filter { st in ancestors(it) }
        }
        return implementations(declared)
    }

    /** The declared type of what register [r] holds before [k]: a field's type (or its one known stored class), a cast, a call's return type, a parameter's type. */
    private fun staticType(ctx: Ctx, k: Int, r0: Int): String? {
        var r = r0
        var at = definition(ctx.code, ctx.cfg, k, r) ?: return null
        repeat(4) {
            if (at < 0) return@repeat
            val x = ctx.code.insns[at]
            when {
                x.op.mnemonic.startsWith("move-object") -> { r = x.regs[1]; at = definition(ctx.code, ctx.cfg, at, r) ?: return null }
                else -> return@repeat
            }
        }
        if (at == -1) {
            val p = ctx.mi.paramRegs.indexOf(r).takeIf { it >= 0 } ?: return null
            if (p == 0 && !ctx.mi.m.isStatic) return ctx.mi.owner
            val types = (if (ctx.mi.m.isStatic) emptyList() else listOf(ctx.mi.owner)) + ctx.mi.m.proto.parameters.map { ctx.d.type(it) }
            return types.getOrNull(p)
        }
        val x = ctx.code.insns[at]
        val mn = x.op.mnemonic
        return when {
            mn == "iget-object" || mn == "sget-object" -> {
                val f = normalizeField(ctx.d.fieldDescriptor(x.index))
                val t = f.substringAfterLast(':')
                // Refinable fields with known stores: the one class stored, when there is exactly one.
                if (refinable(f)) fieldTypes[f]?.singleOrNull() ?: t else t
            }
            mn == "check-cast" -> ctx.d.type(x.index)
            mn.startsWith("move-result") && at > 0 -> {
                val call = ctx.code.insns[at - 1]
                if (call.op.index == IndexKind.METHOD && call.op.mnemonic.startsWith("invoke")) ctx.d.type(ctx.d.protos[ctx.d.methodIds[call.index].protoIdx].returnType) else null
            }
            else -> null
        }
    }

    /** App classes implementing or extending [t], when there are at most three (more: a generic type that says nothing). */
    private fun implementations(t: String): List<String> = implementationCache.getOrPut(t) {
        val out = ArrayList<String>()
        val queue = ArrayDeque(listOf(t))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (c in byOwner && classFlags[c]?.let { it and (AccessFlags.INTERFACE or AccessFlags.ABSTRACT) == 0 } == true) {
                out += c
                if (out.size > 3) return@getOrPut emptyList()
            }
            queue += subtypes[c].orEmpty()
        }
        out
    }
    private val implementationCache = HashMap<String, List<String>>()

    /** The methods of [type] (or its app superclasses) that implement the callback interface [iface]. */
    private fun callbackMethods(type: String, iface: String): List<MethodInfo> = callbackCache.getOrPut("$type|$iface") {
        val matchers = interfaceSigs(iface) ?: return@getOrPut emptyList()
        val out = LinkedHashMap<String, MethodInfo>()
        var c: String? = type
        val seen = HashSet<String>()
        while (c != null && seen.add(c) && c in byOwner) {
            for (mi in byOwner[c].orEmpty()) {
                val sig = mi.ref.substringAfter("->")
                if (mi.m.isStatic || sig.startsWith("<") || sig in out) continue
                if (matchers.any { m -> if (m.endsWith("(")) sig.startsWith(m) else sig == m }) out[sig] = mi
            }
            c = superOf[c]
        }
        out.values.take(MAX_CALLBACKS).flatMap { variants(it, null) }
    }
    private val callbackCache = HashMap<String, List<MethodInfo>>()

    /**
     * The callback methods of an interface: the abstract methods it and its super-interfaces declare
     * in the DEX (androidx `Observer`, Kotlin `Function2`, a library's listener), or, for platform
     * types, the names in [FRAMEWORK_CALLBACKS]. Null: not a known callback type.
     */
    private fun interfaceSigs(t: String): List<String>? = interfaceCache.getOrPut(t) {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(t))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            if (!seen.add(x) || x == "Ljava/lang/Object;") continue
            val flags = classFlags[x]
            // Interfaces of named libraries only: a renamed interface is the app's own (or cannot be told from it).
            if (flags != null && flags and AccessFlags.INTERFACE != 0 && modelled(x)) out += declaredSigs[x].orEmpty().filter { !it.startsWith("<") && it !in OBJECT_SIGS }
            FRAMEWORK_CALLBACKS[x]?.let { names -> out += names.map { "$it(" } }
            queue += interfacesOf[x].orEmpty()
            superOf[x]?.let { queue += it }
        }
        out.toList().takeIf { it.isNotEmpty() }
    }
    private val interfaceCache = HashMap<String, List<String>?>()

    /**
     * Data stored and read back in another method: SharedPreferences keys and SQLite tables carry it
     * like a shared field (one hop). Returns what a read gives, or null when the call is not one.
     */
    private fun storage(ctx: Ctx, k: Int, owner: String, name: String, types: List<String>, argRegs: IntArray, state: Array<RV>): IntArray? {
        val regs = ctx.regs
        fun arg(p: Int): RV = argRegs.getOrNull(p)?.takeIf { it in 0 until regs }?.let { state[it] } ?: EMPTY_RV
        fun rest(from: Int): IntArray {
            var w = EMPTY
            for (p in from until types.size) w = union(w, flatten(arg(p)))
            return w
        }
        when {
            owner == "Landroid/content/SharedPreferences\$Editor;" && name.startsWith("put") && types.size >= 3 -> {
                val key = constString(ctx, k, argRegs[1]) ?: return null
                rest(2).takeIf { it.isNotEmpty() }?.let { writeGlobal(ctx, "pref:$key", it) }
            }
            owner == "Landroid/content/SharedPreferences;" && name.startsWith("get") && types.size >= 2 -> {
                val key = constString(ctx, k, argRegs[1]) ?: return null
                return read(ctx, "pref:$key")
            }
            owner == SQLITE_DB && name in TABLE_WRITES -> {
                val table = constString(ctx, k, argRegs.getOrElse(1) { -1 })?.let(::tableName) ?: return null
                // The ContentValues written; the WHERE clause and its arguments only select rows.
                val values = argRegs.getOrElse(storedValues(name)) { -1 }.takeIf { it in 0 until ctx.regs }?.let { flatten(state[it]) } ?: EMPTY
                values.takeIf { it.isNotEmpty() }?.let { writeGlobal(ctx, "db:$table", it) }
            }
            owner == SQLITE_DB && name == "execSQL" -> {
                val sql = constString(ctx, k, argRegs.getOrElse(1) { -1 }) ?: return null
                val data = rest(2)
                if (data.isNotEmpty()) for (t in tables(WRITE_SQL, sql)) writeGlobal(ctx, "db:$t", data)
            }
            owner == SQLITE_DB && (name == "query" || name == "queryWithFactory") -> {
                val table = (1 until types.size).firstNotNullOfOrNull { p -> if (types[p] == "Ljava/lang/String;") constString(ctx, k, argRegs[p])?.let(::tableName) else null } ?: return null
                return read(ctx, "db:$table")
            }
            owner == SQLITE_DB && name == "rawQuery" -> {
                val sql = constString(ctx, k, argRegs.getOrElse(1) { -1 }) ?: return null
                var w = EMPTY
                for (t in tables(READ_SQL, sql)) w = union(w, read(ctx, "db:$t"))
                return w
            }
            owner in STATEMENTS && name.startsWith("bind") && types.size >= 3 -> {
                val data = flatten(arg(2))
                if (data.isNotEmpty()) for (t in sqlTables(ctx, WRITE_SQL)) writeGlobal(ctx, "db:$t", data)
            }
            owner in CURSORS && name.startsWith("get") && name !in NON_DATA -> {
                var w = EMPTY
                for (t in sqlTables(ctx, READ_SQL)) w = union(w, read(ctx, "db:$t"))
                return w
            }
            // Extras of an intent or a bundle (another screen's arguments, saved state, a worker's input) by constant key.
            owner in EXTRA_HOLDERS && name.startsWith("put") && "Boolean" !in name && types.size >= 3 && types[1] == STRING -> {
                val key = constString(ctx, k, argRegs[1])?.takeIf(::appKey) ?: return null
                rest(2).takeIf { it.isNotEmpty() }?.let { writeGlobal(ctx, "extra:$key", it) }
            }
            owner in EXTRA_HOLDERS && name.startsWith("get") && "Boolean" !in name && types.size >= 2 && types[1] == STRING -> {
                val key = constString(ctx, k, argRegs[1])?.takeIf(::appKey) ?: return null
                return read(ctx, "extra:$key")
            }
            // Files read back by name (written through a stream marked `file:<name>`, see [checkMarks]).
            isContext(owner) && name == "openFileInput" -> {
                val file = constString(ctx, k, argRegs.getOrElse(1) { -1 })?.let(::baseName) ?: return null
                return union(read(ctx, "file:$file"), intArrayOf(mark("path:$file", "Context.openFileInput")))
            }
            owner in FILE_READERS && name == "<init>" -> {
                var w = EMPTY
                for (f in fileNames(ctx, k, types, argRegs, state)) w = union(w, read(ctx, "file:$f"))
                if (w.isNotEmpty()) mutate(ctx, state, argRegs[0], w, null)
            }
            owner in KOTLIN_FILES && name.removeSuffix("\$default") in KOTLIN_FILE_READS -> {
                var w = EMPTY
                for (f in paths(arg(0))) w = union(w, read(ctx, "file:$f"))
                return w
            }
            owner in KOTLIN_FILES && name.removeSuffix("\$default") in KOTLIN_FILE_WRITES -> {
                val data = rest(1).filter { isSource(it) || isSymbolic(it) }.toIntArray()
                if (data.isNotEmpty()) for (f in paths(arg(0))) writeGlobal(ctx, "file:$f", data)
            }
        }
        return null
    }

    /** Extra keys an app defines for its own components; `android.intent.extra.*` and the platform's and libraries' state keys address other apps or their own code. */
    private fun appKey(key: String) = key.isNotBlank() && !key.startsWith("android.") && !key.startsWith("android:") && !key.startsWith("androidx.")

    private fun read(ctx: Ctx, key: String): IntArray {
        readers.getOrPut(key) { HashSet() } += ctx.mi.id
        return globalOf(key)
    }

    private fun tableName(s: String): String? = s.trim('`', '"', '[', ']', ' ').takeIf { TABLE_NAME.matches(it) }?.lowercase()

    /** Tables a SQL statement names (only for text shaped like SQL, so "Received from server" names none). */
    private fun tables(pattern: Regex, sql: String): List<String> =
        if (ScreenMap.sqlOp(sql) == null) emptyList() else pattern.findAll(sql).mapNotNull { tableName(it.groupValues[1]) }.distinct().toList()

    /**
     * Tables a bind or a cursor read in this method uses: those named by SQL constants in the method,
     * else in its class or outer class (Room puts an insert's SQL in `createQuery()` and its binds in
     * `bind()`), unless that class is an activity or service. More than two candidates: ambiguous, none.
     */
    private fun sqlTables(ctx: Ctx, pattern: Regex): List<String> {
        val own = ctx.code.insns.filter { it.op.index == IndexKind.STRING }.flatMap { tables(pattern, ctx.d.string(it.index)) }.distinct()
        if (own.isNotEmpty()) return own.takeIf { it.size <= 2 }.orEmpty()
        // The class-wide fallback is for generated database classes; an activity or service reading a
        // cursor (a content provider's) has nothing to do with the SQL its other methods run.
        if (isContext(ctx.mi.owner)) return emptyList()
        val outer = ctx.mi.owner.substringBefore('$').let { if (it.endsWith(";")) it else "$it;" }
        val cls = (classSql(ctx.mi.owner, pattern) + classSql(outer, pattern)).distinct()
        return cls.takeIf { it.size <= 2 }.orEmpty()
    }

    private fun classSql(owner: String, pattern: Regex): List<String> = classSqlCache.getOrPut("$owner|${pattern.pattern.length}") {
        byOwner[owner].orEmpty().flatMap { m ->
            val code = m.code() ?: return@flatMap emptyList()
            code.insns.filter { it.op.index == IndexKind.STRING }.flatMap { tables(pattern, m.d.string(it.index)) }
        }.distinct()
    }
    private val classSqlCache = HashMap<String, List<String>>()

    private fun absorbs(owner: String, name: String): Boolean = when {
        name == "<init>" -> true
        owner == "Ljava/lang/StringBuilder;" || owner == "Ljava/lang/StringBuffer;" -> name in setOf("append", "insert", "replace", "setCharAt")
        owner.endsWith("\$Builder;") || owner.endsWith("Builder;") -> !name.startsWith("build")
        owner.startsWith("Ljava/util/") || owner.startsWith("Lkotlin/collections/") -> name in MUTATORS
        owner in COLLECTIONS -> name in MUTATORS || name == "append" || name == "setValueAt"
        owner.startsWith("Ljava/io/") -> name.startsWith("write") || name.startsWith("print") || name == "append" || name == "format"
        owner == "Landroid/content/ContentValues;" || owner == "Landroid/os/Bundle;" || owner == "Landroid/os/BaseBundle;" || owner == "Landroid/os/PersistableBundle;" -> name.startsWith("put")
        owner == "Landroid/content/Intent;" -> name.startsWith("put") || name.startsWith("set") || name == "fillIn" || name == "replaceExtras"
        owner.startsWith("Lorg/json/") -> name == "put" || name.startsWith("put") || name == "accumulate" || name == "append"
        owner.startsWith("Lcom/google/gson/Json") -> name == "add" || name == "addProperty" || name == "set"
        owner == "Landroid/os/Parcel;" -> name.startsWith("write")
        owner == "Landroid/os/Message;" -> name == "setData"
        owner.startsWith("Landroidx/lifecycle/") -> name == "setValue" || name == "postValue"
        owner.startsWith("Lkotlinx/coroutines/flow/") -> name in setOf("setValue", "emit", "tryEmit", "compareAndSet")
        owner.startsWith("Lkotlinx/coroutines/channels/") -> name in setOf("send", "trySend", "offer", "trySendBlocking")
        owner.startsWith("Lio/reactivex/") && "/subjects/" in owner -> name == "onNext"
        else -> false
    }

    private fun isContext(owner: String) = ancestors(owner).any { it in CONTEXT_TYPES }

    /**
     * What platform or library code can read from an argument of declared [type]: everything reachable
     * from it, except from a Context (an activity, a service, the application). Platform code uses a
     * Context's own platform state (resources, theme, system services), never the fields an app declares
     * in its subclass, so nothing the app keeps there goes along: `getTheme()`, `obtainStyledAttributes`
     * or `new Intent(context, Cls::class.java)` carry no app data.
     */
    private fun passed(type: String, v: RV): IntArray = if (contextType(type) || type in SYSTEM_SERVICES) EMPTY else flatten(v)
    private fun contextType(type: String) = type.startsWith("L") && isContext(type)

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
        return modelSource(ref, owner)
    }

    // ---- the privacy API model ------------------------------------------------------------------

    /** Source APIs the model recognised (their display name) -> its confidence. */
    private val modelApis = HashMap<String, Double>()
    private val modelCache = HashMap<String, PrivacyApiModel.Prediction?>()
    private fun modelPrediction(ref: String) = modelCache.getOrPut(ref) { PrivacyApiModel.predict(ref) }

    /** A platform method no table names that the model takes, confidently, for a personal-data source. */
    private fun modelSource(ref: String, owner: String): Pair<String, String>? {
        // A status (boolean) or an action (void) is not data, whatever the model says.
        if (!useModel || owner in classFlags || MODEL_SOURCE_OWNERS.none { owner.startsWith(it) } || ref.endsWith(")V") || ref.endsWith(")Z")) return null
        val p = modelPrediction(ref) ?: return null
        val data = p.label.removePrefix("source:").takeIf { p.label.startsWith("source:") && it in MODEL_SOURCE_KINDS } ?: return null
        if (p.probability < MODEL_THRESHOLD) return null
        // A fix's time, provider and accuracy are not where the device is, whatever the model says.
        if (owner == LOCATION && ref.substringAfter("->").substringBefore('(') in LOCATION_METADATA) return null
        val api = display(ref)
        modelApis[api] = p.probability
        return data to api
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
    /** Of the variants among [targets], those that run for the object [v] when its dispatch id is known. */
    private fun forCases(ctx: Ctx, v: RV, targets: List<MethodInfo>): List<MethodInfo> {
        val (cls, cases) = when {
            v.alias == 0 && !ctx.mi.m.isStatic && ctx.mi.cases != null -> ctx.mi.owner to ctx.mi.cases
            v.alias <= -2 -> ctx.allocCases[-2 - v.alias]?.let { c -> ctx.code.insns.getOrNull(-2 - v.alias)?.takeIf { it.op.mnemonic == "new-instance" }?.let { ctx.d.type(it.index) to c } }
            else -> null
        } ?: return targets
        return targets.filter { t -> t.base == null || t.owner != cls || t in variants(t.base, cases) }
    }

    /**
     * The dispatch id in register [r] before instruction [k], from the same straight-line code: a
     * constant, or (in a variant) the method's own id read back from `this` (a coroutine's `create`).
     */
    private fun dispatchValue(ctx: Ctx, k: Int, r: Int): Set<Int>? {
        if (r < 0) return null
        for (j in k - 1 downTo maxOf(0, k - 16)) {
            val x = ctx.code.insns[j]
            if (x.op.flow != FlowKind.NONE) return null
            if (r in dev.apkproject.lift.DataflowShape.writes(x, ctx.regs)) {
                val mn = x.op.mnemonic
                return when {
                    mn == "const" || mn.startsWith("const/") -> x.literal?.toInt()?.let { setOf(it) }
                    mn == "iget" && ctx.mi.cases != null && dispatch[ctx.mi.owner]?.field == ctx.d.fieldDescriptor(x.index) -> ctx.mi.cases
                    else -> null
                }
            }
            // The register may have been set on another path into this block.
            if (j > 0 && java.util.Arrays.binarySearch(ctx.cfg.starts, j) >= 0) return null
        }
        return null
    }

    /** Whether the PrintStream in [r] before [k] is `System.out` or `System.err`. */
    private fun console(ctx: Ctx, k: Int, r: Int): Boolean {
        var reg = r
        var at = definition(ctx.code, ctx.cfg, k, reg)
        repeat(4) {
            val x = at?.takeIf { it >= 0 }?.let { ctx.code.insns[it] } ?: return false
            if (x.op.mnemonic.startsWith("move-object")) { reg = x.regs[1]; at = definition(ctx.code, ctx.cfg, at!!, reg) }
            else return x.op.mnemonic == "sget-object" && ctx.d.fieldDescriptor(x.index).let { it == "Ljava/lang/System;->out:Ljava/io/PrintStream;" || it == "Ljava/lang/System;->err:Ljava/io/PrintStream;" }
        }
        return false
    }

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

    fun flows(permissions: Set<String>?, app: SourceRuntime.App? = null): List<PrivacyFlow> {
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
            val runtime = app?.let { a -> SourceRuntime.verdict(src.api, data, a)?.let { it.describe(a) to it.possible(a) } }
            val returns = runtime?.second
            out += PrivacyFlow(
                data, PrivacyMap.TITLES[data] ?: data, src.api, display(src.method!!), h.site.kind, h.site.api, h.site.detail, display(h.site.method),
                chain.map(::display), if (src.field != null) emptyList() else sourcePath(meet, src.method).map(::display),
                if (declared == false || returns == false) "LOW" else attention(data, h.site), codeOf(h.site.method), src.field?.let(::fieldName), declared,
                model = modelApis[src.api], runtime = runtime?.first, returnsData = returns,
            )
        }
        // A model-based flow adds something only where no table-named source of the same data reaches the
        // same sink call (a Location getter the model knows, on a location the tables already follow).
        val byTables = out.filter { it.model == null }.map { "${it.data}|${it.sinkApi}|${it.sinkDetail}|${it.sinkIn}" }.toSet()
        out.removeAll { it.model != null && "${it.data}|${it.sinkApi}|${it.sinkDetail}|${it.sinkIn}" in byTables }
        return out.sortedWith(compareBy({ ATTENTION.indexOf(it.attention) }, { it.data }, { it.sink }, { it.sinkDetail }, { it.sourceIn }))
    }

    fun sources(flows: List<PrivacyFlow>, permissions: Set<String>?, app: SourceRuntime.App? = null): List<PrivacySource> = sourceUses.entries.map { (key, users) ->
        val data = key.substringBefore('|')
        val api = key.substringAfter('|')
        val runtime = app?.let { a -> SourceRuntime.verdict(api, data, a)?.let { it.describe(a) to it.possible(a) } }
        PrivacySource(
            data, PrivacyMap.TITLES[data] ?: data, api, users.map(::display).sorted().take(20), flows.any { it.data == data && it.sourceApi == api },
            permissionsFor(api, data), declared(api, data, permissions), modelApis[api], runtime?.first, runtime?.second,
        )
    }.sortedWith(compareBy({ it.data }, { it.api }))

    /**
     * Runtime permissions the source API needs (any one of them); empty when it needs none: these rules,
     * which cover older Android levels too (`READ_PHONE_STATE` for the IMEI before Android 10), and what
     * the SDK's own annotations say the API requires on every level ([PlatformPermissions]).
     */
    private fun permissionsFor(api: String, data: String): List<String> {
        val known = knownPermissions(api, data) ?: return emptyList()
        return (known + PlatformPermissions.permissions(api)).distinct()
    }

    /** Permissions by rule; null for APIs that need none whatever the annotations list (privileged-only alternatives). */
    private fun knownPermissions(api: String, data: String): List<String>? = when {
        api.startsWith("Settings.Secure") || api.endsWith("WifiInfo.getMacAddress") || api.endsWith("NetworkInterface.getHardwareAddress") || api == "android.os.Build.SERIAL" -> null
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

    /** A shared field, a SharedPreferences key, a database table, a file or an extra, as the report names it. */
    private fun fieldName(f: String): String = when {
        f.startsWith("pref:") -> "SharedPreferences key '${f.removePrefix("pref:")}'"
        f.startsWith("db:") -> "database table '${f.removePrefix("db:")}'"
        f.startsWith("file:") -> "file '${f.removePrefix("file:")}'"
        f.startsWith("extra:") -> "intent or bundle extra '${f.removePrefix("extra:")}'"
        else -> display(f.substringBefore(':'))
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
        private const val REF = 5
        /** Virtual calls with more implementations are generic (compareTo, invoke, run): modelled, not followed. */
        private const val MAX_TARGETS = 8
        /** Field labels of one parameter before they are widened to the whole parameter. */
        private const val MAX_FIELD_LABELS = 4
        /**
         * The same for the labels of one sink edge, which gather from every sink a parameter reaches through its callees
         * (see [addEdge]): widened at four, a coroutine lambda's `this` became everything it captured, its location too,
         * wherever one captured field (a waypoint, a repository) was logged.
         */
        private const val MAX_EDGE_FIELD_LABELS = 8
        /** Fields tracked per object, and sink edges and field writes per summary. */
        private const val MAX_FIELDS = 16
        private const val MAX_EDGES = 48
        private const val MAX_FIELD_WRITES = 128
        private const val MAX_CHAIN = 14
        /** The holder of a value read from a static field. */
        private const val STATIC_HOLDER = Int.MIN_VALUE
        /** The holder of a channel read from a field of an unknown object (`this.model.location`). */
        private const val UNKNOWN_HOLDER = Int.MIN_VALUE + 1
        private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
        private const val LOCATION = "Landroid/location/Location;"
        /** The privacy API model's confidence needed to take a method as a source or sink. */
        const val MODEL_THRESHOLD = 0.95
        // Kinds the model tells apart reliably enough; Wi-Fi (SuSi's broad "network information") is left to the tables.
        private val MODEL_SOURCE_KINDS = setOf("location", "device-id", "phone-number", "accounts", "contacts", "calendar", "sms")
        // Sources only: the model's sink classes are SuSi's broad "changes state" notion (a selection
        // update counts), and the sink tables already cover the channels that carry data out.
        private val MODEL_SOURCE_OWNERS = listOf("Landroid/", "Ljava/net/")
        /** Separates the two fields of a two-level path (`a|b` is `x.a.b`); never part of a field descriptor. */
        private const val NEST = "|"
        /** Callback methods taken per registered object. */
        private const val MAX_CALLBACKS = 6
        /**
         * Every method of common platform interfaces an app implements, to tell a method the platform
         * can call (an interface method) from one only the app calls. Other platform supertypes count
         * as able to call anything.
         */
        private val PLATFORM_INTERFACE_METHODS: Map<String, Set<String>> = mapOf(
            "Ljava/lang/Runnable;" to setOf("run"), "Ljava/util/concurrent/Callable;" to setOf("call"),
            "Landroid/location/LocationListener;" to setOf("onLocationChanged", "onProviderEnabled", "onProviderDisabled", "onStatusChanged", "onFlushComplete"),
            "Landroid/view/View\$OnClickListener;" to setOf("onClick"), "Landroid/view/View\$OnLongClickListener;" to setOf("onLongClick"),
            "Landroid/content/DialogInterface\$OnClickListener;" to setOf("onClick"), "Landroid/widget/AdapterView\$OnItemClickListener;" to setOf("onItemClick"),
            "Landroid/text/TextWatcher;" to setOf("beforeTextChanged", "onTextChanged", "afterTextChanged"),
            "Landroid/hardware/SensorEventListener;" to setOf("onSensorChanged", "onAccuracyChanged"),
            "Landroid/os/Handler\$Callback;" to setOf("handleMessage"),
            "Landroid/content/ServiceConnection;" to setOf("onServiceConnected", "onServiceDisconnected", "onBindingDied", "onNullBinding"),
            "Ljava/io/Serializable;" to emptySet(), "Landroid/os/Parcelable;" to setOf("describeContents", "writeToParcel"),
        )

        /** Platform methods that return a stream the platform creates (a connection's, a socket's, a process's). */
        private val PLATFORM_STREAMS = setOf("getOutputStream", "getInputStream", "getErrorStream", "openStream")

        private val OBJECT_SIGS = setOf("toString()Ljava/lang/String;", "equals(Ljava/lang/Object;)Z", "hashCode()I", "finalize()V", "clone()Ljava/lang/Object;")

        /**
         * Field types that carry data from whoever posts to whoever observes: LiveData, flows,
         * channels and blocking queues. In the app's own classes they are shared like value fields.
         */
        private val CHANNEL_TYPES = setOf(
            "Landroidx/lifecycle/LiveData;", "Landroidx/lifecycle/MutableLiveData;", "Landroidx/lifecycle/MediatorLiveData;",
            "Lkotlinx/coroutines/flow/StateFlow;", "Lkotlinx/coroutines/flow/MutableStateFlow;", "Lkotlinx/coroutines/flow/SharedFlow;",
            "Lkotlinx/coroutines/flow/MutableSharedFlow;", "Lkotlinx/coroutines/flow/Flow;", "Lkotlinx/coroutines/channels/Channel;",
            "Lkotlinx/coroutines/channels/SendChannel;", "Lkotlinx/coroutines/channels/ReceiveChannel;",
            "Ljava/util/concurrent/BlockingQueue;", "Ljava/util/concurrent/LinkedBlockingQueue;", "Ljava/util/concurrent/ArrayBlockingQueue;",
            "Ljava/util/concurrent/BlockingDeque;", "Ljava/util/concurrent/LinkedBlockingDeque;", "Ljava/util/concurrent/ConcurrentLinkedQueue;",
            "Ljava/util/concurrent/ConcurrentLinkedDeque;", "Ljava/util/concurrent/PriorityBlockingQueue;",
            "Lio/reactivex/subjects/PublishSubject;", "Lio/reactivex/subjects/BehaviorSubject;", "Lio/reactivex/rxjava3/subjects/PublishSubject;",
            "Lio/reactivex/rxjava3/subjects/BehaviorSubject;",
        )

        /** Platform callback types (not in the DEX) and the methods the platform calls on them. */
        private val FRAMEWORK_CALLBACKS: Map<String, List<String>> = mapOf(
            "Ljava/lang/Runnable;" to listOf("run"), "Ljava/util/concurrent/Callable;" to listOf("call"), "Ljava/util/TimerTask;" to listOf("run"),
            "Ljava/lang/Thread;" to listOf("run"), "Ljava/util/Comparator;" to listOf("compare"),
            "Ljava/util/function/Consumer;" to listOf("accept"), "Ljava/util/function/BiConsumer;" to listOf("accept"),
            "Ljava/util/function/Function;" to listOf("apply"), "Ljava/util/function/BiFunction;" to listOf("apply"),
            "Ljava/util/function/Supplier;" to listOf("get"), "Ljava/util/function/Predicate;" to listOf("test"),
            "Landroid/view/View\$OnClickListener;" to listOf("onClick"), "Landroid/view/View\$OnLongClickListener;" to listOf("onLongClick"),
            "Landroid/view/View\$OnTouchListener;" to listOf("onTouch"), "Landroid/content/DialogInterface\$OnClickListener;" to listOf("onClick"),
            "Landroid/widget/AdapterView\$OnItemClickListener;" to listOf("onItemClick"), "Landroid/text/TextWatcher;" to listOf("afterTextChanged", "onTextChanged"),
            "Landroid/location/LocationListener;" to listOf("onLocationChanged", "onProviderEnabled", "onProviderDisabled", "onStatusChanged"),
            "Landroid/hardware/SensorEventListener;" to listOf("onSensorChanged"), "Landroid/os/Handler\$Callback;" to listOf("handleMessage"),
            "Landroid/content/BroadcastReceiver;" to listOf("onReceive"), "Landroid/content/ServiceConnection;" to listOf("onServiceConnected"),
            "Landroid/os/AsyncTask;" to listOf("doInBackground", "onPostExecute", "onProgressUpdate"),
            "Landroid/net/ConnectivityManager\$NetworkCallback;" to listOf("onAvailable", "onCapabilitiesChanged", "onLost"),
            "Landroid/telephony/PhoneStateListener;" to listOf("onCallStateChanged", "onCellLocationChanged", "onServiceStateChanged"),
            "Lcom/google/android/gms/tasks/OnSuccessListener;" to listOf("onSuccess"), "Lcom/google/android/gms/tasks/OnCompleteListener;" to listOf("onComplete"),
            "Lcom/google/android/gms/location/LocationCallback;" to listOf("onLocationResult"),
            "Landroidx/lifecycle/Observer;" to listOf("onChanged"), "Lkotlinx/coroutines/flow/FlowCollector;" to listOf("emit"),
            "Lkotlin/jvm/functions/Function0;" to listOf("invoke"), "Lkotlin/jvm/functions/Function1;" to listOf("invoke"), "Lkotlin/jvm/functions/Function2;" to listOf("invoke"),
            "Lio/reactivex/functions/Consumer;" to listOf("accept"), "Lio/reactivex/rxjava3/functions/Consumer;" to listOf("accept"),
        )

        private const val FILE = "Ljava/io/File;"
        private const val STRING = "Ljava/lang/String;"

        /** Holders of keyed extras: intents, bundles, WorkManager's input data. */
        private val EXTRA_HOLDERS = setOf(
            "Landroid/content/Intent;", "Landroid/os/Bundle;", "Landroid/os/BaseBundle;", "Landroid/os/PersistableBundle;",
            "Landroidx/work/Data;", "Landroidx/work/Data\$Builder;",
        )

        /** Streams and readers opening a file to read it. */
        private val FILE_READERS = setOf("Ljava/io/FileInputStream;", "Ljava/io/FileReader;", "Ljava/util/Scanner;")
        private val KOTLIN_FILES = setOf("Lkotlin/io/FilesKt;", "Lkotlin/io/FilesKt__FileReadWriteKt;")
        private val KOTLIN_FILE_READS = setOf("readText", "readBytes", "readLines", "bufferedReader", "reader", "inputStream")
        private val KOTLIN_FILE_WRITES = setOf("writeText", "appendText", "writeBytes", "appendBytes")

        /** Calls whose result is a server's response: the server's data, not what the request carried. */
        private val RESPONSES = buildSet {
            for (c in listOf("Ljava/net/URLConnection;", "Ljava/net/HttpURLConnection;", "Ljavax/net/ssl/HttpsURLConnection;")) {
                for (m in listOf("getInputStream", "getErrorStream", "getResponseMessage", "getHeaderField", "getHeaderFields", "getContent")) add("$c->$m")
            }
            add("Ljava/net/Socket;->getInputStream"); add("Ljava/net/URL;->openStream"); add("Ljava/net/URL;->getContent")
        }
        /** Calls returning a response object that holds its request (see [HELD_REQUEST]). */
        private val RESPONSE_OBJECTS = setOf("Lokhttp3/Call;->execute", "Lretrofit2/Call;->execute")
        private const val HELD_REQUEST = "Lokhttp3/Response;->request:Lokhttp3/Request;"
        private val HOLDS_REQUEST = setOf("Lokhttp3/Call;", "Lokhttp3/Response;", "Lretrofit2/Call;", "Lretrofit2/Response;")
        private const val OKHTTP_REQUEST_BUILDER = "Lokhttp3/Request\$Builder;"
        /**
         * The labels of an OkHttp request's URL, kept beside the whole request on its builder, the request and the
         * call: what a response holds of its request (its `toString()` prints protocol, code, message and URL, not
         * the body; GPSLogger's Dawarich worker logs a response to a POST whose body carries the location).
         */
        private const val REQUEST_URL = "?->url:Ljava/lang/String;"

        /** JSON decoding into objects: the result holds the input's data in fields, which ones is not known ([CONTENT]). */
        private val DECODERS = buildSet {
            for (c in listOf("Lkotlinx/serialization/json/Json;", "Lkotlinx/serialization/json/Json\$Default;", "Lkotlinx/serialization/StringFormat;")) {
                add("$c->decodeFromString"); add("$c->decodeFromJsonElement")
            }
            add("Lkotlinx/serialization/BinaryFormat;->decodeFromByteArray"); add("Lkotlinx/serialization/json/JvmStreamsKt;->decodeFromStream")
            add("Lcom/google/gson/Gson;->fromJson"); add("Lcom/google/gson/TypeAdapter;->fromJson"); add("Lcom/google/gson/TypeAdapter;->fromJsonTree")
            add("Lcom/squareup/moshi/JsonAdapter;->fromJson"); add("Lcom/squareup/moshi/JsonAdapter;->fromJsonValue")
            for (m in listOf("readValue", "treeToValue", "convertValue")) add("Lcom/fasterxml/jackson/databind/ObjectMapper;->$m")
            add("Lcom/fasterxml/jackson/databind/ObjectReader;->readValue")
        }
        /**
         * The pseudo-field holding what an object contains without the app's own fields saying where:
         * a decoded object's data, the bytes written into an in-memory stream through a wrapping one.
         * Library calls on the object reach it ([external]); a field the app reads by name does not.
         */
        private const val CONTENT = "?->content:Ljava/lang/Object;"

        /**
         * What a collection holds ([COLLECTIONS]): an object added to it (`list.add(loc)`, `map.put(k, v)`) is stored
         * like a field of the collection, its own fields one level down (`element.lat`), and what is read back
         * (`get`, `next`, `poll`) is read like that field, so a row's id taken from a list of rows is the id only.
         * Keys and indices stay in the collection's value; passed on or printed, the collection is everything in it.
         */
        private const val ELEMENT = "?->element:Ljava/lang/Object;"
        private const val OBJECT = "Ljava/lang/Object;"
        private const val MAP_ENTRY = "Ljava/util/Map\$Entry;"

        /** Collections, their iterators and entries (see [ELEMENT]). */
        private val COLLECTIONS = setOf(
            "Ljava/lang/Iterable;", "Ljava/util/Collection;", "Ljava/util/AbstractCollection;", "Ljava/util/List;", "Ljava/util/AbstractList;",
            "Ljava/util/AbstractSequentialList;", "Ljava/util/ArrayList;", "Ljava/util/LinkedList;", "Ljava/util/Vector;", "Ljava/util/Stack;",
            "Ljava/util/Set;", "Ljava/util/AbstractSet;", "Ljava/util/HashSet;", "Ljava/util/LinkedHashSet;", "Ljava/util/SortedSet;",
            "Ljava/util/NavigableSet;", "Ljava/util/TreeSet;", "Ljava/util/Map;", "Ljava/util/AbstractMap;", "Ljava/util/HashMap;",
            "Ljava/util/LinkedHashMap;", "Ljava/util/SortedMap;", "Ljava/util/NavigableMap;", "Ljava/util/TreeMap;", "Ljava/util/Hashtable;",
            "Ljava/util/WeakHashMap;", "Ljava/util/IdentityHashMap;", "Ljava/util/EnumMap;", MAP_ENTRY, "Ljava/util/AbstractMap\$SimpleEntry;",
            "Ljava/util/AbstractMap\$SimpleImmutableEntry;", "Ljava/util/Queue;", "Ljava/util/AbstractQueue;", "Ljava/util/Deque;",
            "Ljava/util/ArrayDeque;", "Ljava/util/PriorityQueue;", "Ljava/util/Iterator;", "Ljava/util/ListIterator;", "Ljava/util/Enumeration;",
            "Ljava/util/concurrent/ConcurrentMap;", "Ljava/util/concurrent/ConcurrentHashMap;", "Ljava/util/concurrent/ConcurrentSkipListMap;",
            "Ljava/util/concurrent/ConcurrentSkipListSet;", "Ljava/util/concurrent/CopyOnWriteArrayList;", "Ljava/util/concurrent/CopyOnWriteArraySet;",
            "Ljava/util/concurrent/BlockingQueue;", "Ljava/util/concurrent/LinkedBlockingQueue;", "Ljava/util/concurrent/ArrayBlockingQueue;",
            "Ljava/util/concurrent/PriorityBlockingQueue;", "Ljava/util/concurrent/BlockingDeque;", "Ljava/util/concurrent/LinkedBlockingDeque;",
            "Ljava/util/concurrent/ConcurrentLinkedQueue;", "Ljava/util/concurrent/ConcurrentLinkedDeque;", "Lkotlin/collections/ArrayDeque;",
            "Landroid/util/SparseArray;", "Landroid/util/LongSparseArray;", "Landroid/util/ArrayMap;", "Landroid/util/ArraySet;", "Landroid/util/LruCache;",
            "Landroidx/collection/ArrayMap;", "Landroidx/collection/SimpleArrayMap;", "Landroidx/collection/ArraySet;",
            "Landroidx/collection/SparseArrayCompat;", "Landroidx/collection/LongSparseArray;", "Landroidx/collection/LruCache;",
        )
        private val ELEMENT_STORES = setOf(
            "add", "addFirst", "addLast", "offer", "offerFirst", "offerLast", "push", "put", "putIfAbsent", "set", "replace",
            "addElement", "insertElementAt", "setElementAt", "append", "setValueAt",
        )
        private val ELEMENT_READS = setOf(
            "get", "getOrDefault", "next", "previous", "nextElement", "poll", "pollFirst", "pollLast", "peek", "peekFirst", "peekLast", "pop",
            "element", "remove", "removeFirst", "removeLast", "take", "first", "last", "getFirst", "getLast", "firstElement", "lastElement",
            "elementAt", "floor", "ceiling", "higher", "lower", "firstEntry", "lastEntry", "floorEntry", "ceilingEntry", "higherEntry",
            "lowerEntry", "pollFirstEntry", "pollLastEntry", "removeFirstOrNull", "removeLastOrNull", "firstOrNull", "lastOrNull", "valueAt",
        )
        private val ELEMENT_VIEWS = setOf(
            "iterator", "listIterator", "descendingIterator", "values", "entrySet", "descendingMap", "descendingSet", "subList", "headSet",
            "tailSet", "subSet", "headMap", "tailMap", "subMap", "elements",
        )
        private val KEY_VIEWS = setOf("keySet", "navigableKeySet", "descendingKeySet", "keys")
        private val RESPONSE_CALLBACKS = setOf("Lokhttp3/Call;->enqueue", "Lretrofit2/Call;->enqueue")
        /** Calls that run a callback of their receiver: a thread subclass's `run`, an AsyncTask's `doInBackground`. */
        private val RECEIVER_CALLBACKS = setOf("Ljava/lang/Thread;->start", "Landroid/os/AsyncTask;->execute", "Landroid/os/AsyncTask;->executeOnExecutor")

        /** Streams that wrap the one given to their constructor: what is written to them is written to it. */
        private val WRAPPING_STREAMS = setOf(
            "Ljava/io/ObjectOutputStream;", "Ljava/io/DataOutputStream;", "Ljava/io/BufferedOutputStream;", "Ljava/io/PrintStream;",
            "Ljava/io/OutputStreamWriter;", "Ljava/io/BufferedWriter;", "Ljava/io/PrintWriter;", "Ljava/io/FilterOutputStream;",
            "Ljava/util/zip/GZIPOutputStream;", "Ljava/util/zip/DeflaterOutputStream;", "Ljava/util/zip/ZipOutputStream;",
        )

        private const val SQLITE_DB = "Landroid/database/sqlite/SQLiteDatabase;"
        private val INTEGER_READS = setOf("getInt", "getLong", "getShort")

        /**
         * Platform system services (what `getSystemService` returns) that hold no data the app puts in: calls on them
         * answer with platform state. Not AccountManager (it keeps an app's user data) nor ClipboardManager (what was copied).
         */
        private val SYSTEM_SERVICES = setOf(
            "Landroid/location/LocationManager;", "Landroid/telephony/TelephonyManager;", "Landroid/telephony/SubscriptionManager;",
            "Landroid/net/ConnectivityManager;", "Landroid/net/wifi/WifiManager;", "Landroid/app/AlarmManager;", "Landroid/os/PowerManager;",
            "Landroid/hardware/SensorManager;", "Landroid/media/AudioManager;", "Landroid/view/inputmethod/InputMethodManager;",
            "Landroid/app/KeyguardManager;", "Landroid/view/WindowManager;", "Landroid/app/ActivityManager;", "Landroid/app/NotificationManager;",
            "Landroid/bluetooth/BluetoothManager;", "Landroid/bluetooth/BluetoothAdapter;", "Landroid/os/UserManager;",
            "Landroid/hardware/camera2/CameraManager;", "Landroid/os/Vibrator;", "Landroid/os/storage/StorageManager;",
            "Landroid/app/usage/UsageStatsManager;", "Landroid/app/job/JobScheduler;", "Landroid/content/pm/PackageManager;",
        )
        private val TABLE_WRITES = setOf("insert", "insertOrThrow", "insertWithOnConflict", "replace", "replaceOrThrow", "update", "updateWithOnConflict")
        /** The argument of a [TABLE_WRITES] call that holds the ContentValues written. */
        private fun storedValues(name: String) = if (name.startsWith("update")) 2 else 3
        private val STATEMENTS = setOf(
            "Landroid/database/sqlite/SQLiteStatement;", "Landroid/database/sqlite/SQLiteProgram;", "Landroidx/sqlite/db/SupportSQLiteStatement;",
            "Landroidx/sqlite/db/SupportSQLiteProgram;", "Landroidx/sqlite/SQLiteStatement;",
        )
        private val CURSORS = setOf(
            "Landroid/database/Cursor;", "Landroid/database/sqlite/SQLiteCursor;", "Landroid/database/CursorWrapper;", "Landroid/database/AbstractCursor;",
            "Landroid/database/AbstractWindowedCursor;", "Landroidx/sqlite/SQLiteStatement;",
        )
        private val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val WRITE_SQL = Regex("""(?i)(?:\bINSERT\s+(?:OR\s+\w+\s+)?INTO|\bREPLACE\s+INTO|\bUPDATE(?:\s+OR\s+\w+)?)\s+[`"\[]?([A-Za-z_][A-Za-z0-9_]*)""")
        private val READ_SQL = Regex("""(?i)\b(?:FROM|JOIN)\s+[`"\[]?([A-Za-z_][A-Za-z0-9_]*)""")
        /** Analyses of one method before it is left as it is (a safeguard; the lattice is finite). */
        private const val MAX_VISITS = 200
        /** Kotlin's names for a coroutine's spilled locals. */
        private val SPILL = Regex("[LIJFDZBCS]\\$[0-9]+")
        /** Analyses of one method after which it is analysed the conservative way (flattened objects, spilled locals as fields). */
        private const val DEGRADE_VISITS = 64
        private const val MAX_SPILL_ROUNDS = 3
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
            "Lcom/google/android/gms/tasks/",
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

        /** Location getters that describe the fix, not the place (PhoneTrack stores a fix's time beside its jobs). */
        private val LOCATION_METADATA = setOf(
            "getTime", "getElapsedRealtimeNanos", "getElapsedRealtimeMillis", "getElapsedRealtimeUncertaintyNanos", "getProvider",
            "getAccuracy", "getVerticalAccuracyMeters", "getBearingAccuracyDegrees", "getSpeedAccuracyMetersPerSecond", "getExtras",
            "isFromMockProvider", "isMock", "hasAccuracy", "hasAltitude", "hasBearing", "hasSpeed",
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
            // Guarded by READ_PHONE_STATE / READ_PRIVILEGED_PHONE_STATE / location in the SDK's annotations (platform-permissions.tsv).
            add("phone-number", "Landroid/telephony/TelephonyManager;", "getVoiceMailNumber")
            add("phone-number", "Landroid/telecom/TelecomManager;", "getLine1Number", "getVoiceMailNumber")
            add("device-id", "Landroid/telephony/TelephonyManager;", "getNai")
            add("wifi", "Landroid/net/wifi/WifiManager;", "getConfiguredNetworks")
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
            // The write end of a pipe whose read end a binder call hands to another process (Nextcloud single sign-on).
            "Landroid/os/ParcelFileDescriptor\$AutoCloseOutputStream;-><init>" to "pipe",
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
            // MQTT clients hand messages to a sender thread through an in-memory queue: the publish call is the send.
            for (t in listOf("MqttAsyncClient", "MqttClient", "IMqttAsyncClient", "IMqttClient")) net("Lorg/eclipse/paho/client/mqttv3/$t;", setOf("publish"), "MQTT publish")
            net("Lorg/eclipse/paho/android/service/MqttAndroidClient;", setOf("publish"), "MQTT publish")
            net("Linfo/mqtt/android/service/MqttAndroidClient;", setOf("publish"), "MQTT publish")
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
            // What is stored: the ContentValues (not the WHERE clause and its arguments, which select rows), or the SQL and its bound values.
            add(SinkSpec("STORAGE", SQLITE_DB, setOf("insert", "insertOrThrow", "insertWithOnConflict", "replace", "replaceOrThrow"), listOf(3), "SQLite database"))
            add(SinkSpec("STORAGE", SQLITE_DB, setOf("update", "updateWithOnConflict"), listOf(2), "SQLite database"))
            add(SinkSpec("STORAGE", SQLITE_DB, setOf("execSQL"), null, "SQLite database"))
            for (t in listOf("Landroid/database/sqlite/SQLiteStatement;", "Landroid/database/sqlite/SQLiteProgram;", "Landroidx/sqlite/db/SupportSQLiteStatement;", "Landroidx/sqlite/db/SupportSQLiteProgram;", "Landroidx/sqlite/SQLiteStatement;")) {
                add(SinkSpec("STORAGE", t, setOf("bindString", "bindLong", "bindDouble", "bindBlob", "bindText"), null, "SQLite database"))
            }
            for (t in KOTLIN_FILES) add(SinkSpec("STORAGE", t, KOTLIN_FILE_WRITES + KOTLIN_FILE_WRITES.map { "$it\$default" }, null, "file"))
            add(SinkSpec("STORAGE", "Ljava/nio/file/Files;", setOf("write", "writeString"), null, "file"))
            add(SinkSpec("CLIPBOARD", "Landroid/content/ClipboardManager;", setOf("setPrimaryClip", "setText"), null, "clipboard", "LOW"))
            // A binder call hands its parcel to another process: an AIDL proxy's `mRemote.transact(code, data, reply, flags)`,
            // a Messenger's message (to another app, a system service, or a service of the app running in its own process).
            add(SinkSpec("IPC", "Landroid/os/IBinder;", setOf("transact"), listOf(2), "binder call to another app or service"))
            add(SinkSpec("IPC", "Landroid/os/Messenger;", setOf("send"), listOf(1), "message to another app or service"))
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
    /** " (model 0.97)" for an API the privacy API model recognised; empty for a table entry. */
    fun modelNote(model: Double?): String = model?.let { " (model %.2f)".format(java.util.Locale.ROOT, it) } ?: ""

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
                val attention = f.attention + when {
                    f.permissionDeclared == false -> " (cannot run: permission not requested)"
                    f.returnsData == false -> " (no data at run time)"
                    else -> ""
                }
                appendLine("| $attention | ${f.dataTitle} | ${cell(f.sourceApi)}${modelNote(f.model)} in ${cell(f.sourceIn)} | ${f.sink}: ${cell(f.sinkDetail)} (${cell(f.sinkApi)}) | ${f.code} | ${cell(path)} |")
            }
            if (r.flows.size > 200) appendLine("\n${r.flows.size - 200} more in privacy.json.")
            appendLine()
        }
        if (r.sources.isNotEmpty()) {
            appendLine("## Personal data the code reads")
            appendLine()
            appendLine("| Data | API | Permission | At run time | Reaches a sink | Called in |")
            appendLine("|---|---|---|---|---|---|")
            for (s in r.sources) {
                val permission = when (s.permissionDeclared) { true -> "requested"; false -> "not requested (cannot run)"; null -> if (s.permissions.isEmpty()) "none needed" else "-" }
                appendLine("| ${s.title} | ${cell(s.api)}${modelNote(s.model)} | $permission | ${cell(s.runtime ?: "-")} | ${if (s.reachesSink) "yes" else "no"} | ${cell(s.usedIn.take(5).joinToString())} |")
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
