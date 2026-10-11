package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.Insn
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.lift.DataflowShape
import kotlinx.serialization.Serializable

/** One thing a screen's code reaches: an endpoint, a server, a SQL statement or a stored setting. */
@Serializable
data class DataReach(
    /** ENDPOINT, HOST, NETWORK_CALL, SQL, CONTENT_PROVIDER, PREFERENCE. */
    val kind: String,
    /** What is reached: `GET api/v1/accounts/{id}`, `api.example.com`, `SELECT timeline_status`, `theme (read)`. */
    val target: String,
    val detail: String,
    /** The shortest call chain found, from the screen's own code to the method that does it. */
    val path: List<String>,
    /** How many screens and components reach the same target (1 = only this one). */
    val sharedBy: Int = 1,
)

/** What one screen (or service, receiver, worker) reaches through its code. */
@Serializable
data class ScreenFlow(
    val component: String,
    /** ACTIVITY, SERVICE, RECEIVER, PROVIDER, WORKER. */
    val kind: String,
    val exported: Boolean,
    val launcher: Boolean,
    /** Activities it opens (by class constant, as `Intent(this, X::class.java)` compiles). */
    val opens: List<String>,
    /** Services, receivers and workers it starts; what they reach counts for this screen too. */
    val starts: List<String>,
    val network: List<DataReach>,
    val database: List<DataReach>,
    val preferences: List<DataReach>,
    val methodsReached: Int,
    /** The walk stopped at its budget; more may be reachable. */
    val truncated: Boolean,
)

@Serializable
data class EndpointUse(val endpoint: String, val screens: List<String>)

@Serializable
data class TableUse(val table: String, val operations: List<String>, val screens: List<String>)

@Serializable
data class ScreenMapReport(
    val screens: List<ScreenFlow>,
    /** Every endpoint the app declares, with the screens that reach it (empty: not reached from any). */
    val endpoints: List<EndpointUse>,
    /** Every table the app's SQL names, with the operations seen and the screens that reach them. */
    val tables: List<TableUse>,
    val preferenceFiles: List<String>,
    val notes: List<String>,
    /**
     * Targets reached by at least [commonThreshold] components (base classes, app start-up, shared
     * libraries), listed once here with one example path instead of under every screen.
     */
    val common: List<DataReach> = emptyList(),
    val commonThreshold: Int = 0,
)

/**
 * A static call graph of every method with code: direct and static calls, virtual and interface
 * calls resolved to their implementations (class hierarchy analysis), and the classes a method
 * creates (a lambda or coroutine created is code that will run: Kotlin reaches the network from
 * inside them). Strings are recorded with the call that first uses them, so a SQL statement, a
 * URL or a preference key is attributed to the code that loads it.
 */
class AppGraph(dex: List<Pair<String, DexFile>>) {
    enum class Kind { STATIC, DIRECT, SUPER, VIRTUAL, INTERFACE }

    /**
     * A call; [via] is the dispatch class and case of the receiver when it was read from a field
     * that only ever holds that instance (a DI provider field: `accountManagerProvider.get()`).
     */
    class Call(val ref: String, val kind: Kind, val via: Pair<String, Int>? = null)

    /** A string constant and the call that first uses it, with the argument slot it is passed in (0 is the receiver of an instance call). */
    class StringUse(val value: String, val usedBy: String?, val slot: Int = -1)

    /** A class created: [case] is the constant dispatch id passed to it, when it is a [Dispatch] class. */
    class New(val type: String, val case: Int?)

    class Facts(val ref: String, val owner: String, val calls: List<Call>, val news: List<New>, val classConsts: Set<String>, val strings: List<StringUse>)

    /**
     * A class that runs one of several bodies chosen by an int field set in its constructor: R8's
     * merged lambda groups, and Hilt/Dagger `SwitchingProvider`s (one `get()` switching over every
     * binding of the app). Followed whole, either one makes every screen reach everything; the
     * constant passed where it is created selects the one body that runs.
     */
    class Dispatch(
        val field: String,
        /** Constructors that take the id as a parameter: constructor -> parameter index. */
        val constructors: Map<String, Int>,
        /** Constructors that set the id themselves (R8 gives each merged lambda its own): constructor -> id. */
        val fixed: Map<String, Int> = emptyMap(),
    )

    /** Method descriptor -> facts, for every method with code. */
    val methods = HashMap<String, Facts>()
    /** Static methods with code. R8 also parks unrelated static helpers in merged classes. */
    val statics = HashSet<String>()
    val defined = HashSet<String>()
    val superOf = HashMap<String, String>()
    val dispatch = HashMap<String, Dispatch>()
    /** Method of a [Dispatch] class -> (case -> facts of the code that case runs); null key is the default branch. */
    private val cases = HashMap<String, Map<Int?, Facts>>()
    private val subtypes = HashMap<String, MutableList<String>>()
    private val interfacesOf = HashMap<String, List<String>>()
    /** Every method signature (`name(proto)ret`) a class declares, abstract ones included. */
    private val declared = HashMap<String, MutableSet<String>>()
    private val byOwner = HashMap<String, MutableList<String>>()
    private val resolved = HashMap<String, Targets>()
    private val overriding = HashMap<String, Boolean>()
    private val branchTargets = java.util.IdentityHashMap<DecodedCode, Set<Int>>()
    /** Every dispatch id seen for a class: fixed by a constructor or passed where it is created. */
    private val ids = HashMap<String, MutableSet<Int>>()
    /** Fields that only ever hold one dispatch instance with a known case: field -> (class, case). */
    private val fieldCases = HashMap<String, Pair<String, Int>>()

    init {
        for ((_, d) in dex) for (c in d.classDefs) {
            val name = d.type(c.classIdx)
            if (!defined.add(name)) continue
            c.superclassIdx.takeIf { it >= 0 && it < d.typeIds.size }?.let { s -> d.type(s).also { superOf[name] = it; subtypes.getOrPut(it) { ArrayList() } += name } }
            interfacesOf[name] = c.interfaces.map { d.type(it) }
            c.interfaces.forEach { i -> subtypes.getOrPut(d.type(i)) { ArrayList() } += name }
        }
        for ((_, d) in dex) for (m in d.methods()) declared.getOrPut(m.owner) { HashSet() } += m.name + m.protoDescriptor
        val decodedAll = ArrayList<Triple<DexFile, dev.apkproject.dex.DexMethod, DecodedCode>>()
        for ((_, d) in dex) for (m in d.methods()) {
            val code = m.code ?: continue
            val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value ?: continue
            decodedAll += Triple(d, m, decoded)
        }
        dispatch.putAll(findDispatch(decodedAll))
        for ((owner, disp) in dispatch) ids.getOrPut(owner) { HashSet() } += disp.fixed.values
        findFieldCases(decodedAll)
        for ((d, m, decoded) in decodedAll) {
            val ref = "${m.owner}->${m.name}${m.protoDescriptor}"
            if (ref in methods) continue
            methods[ref] = facts(d, decoded, ref, m.owner, decoded.insns.indices)
            if (m.isStatic) statics += ref
            byOwner.getOrPut(m.owner) { ArrayList() } += ref
            val disp = dispatch[m.owner] ?: continue
            dispatchSwitch(d, decoded, disp.field)?.let { sw -> cases[ref] = slice(d, decoded, ref, m.owner, sw) }
        }
    }

    fun methodsOf(owner: String): List<String> = byOwner[owner].orEmpty()

    /** Whether [ref] switches on its class's dispatch id (so only one case runs per instance). */
    fun dispatches(ref: String): Boolean = ref in cases

    /** The class and its superclasses. */
    fun ancestors(c: String): List<String> {
        val out = ArrayList<String>()
        var at: String? = c
        while (at != null && at !in out) { out += at; at = superOf[at] }
        return out
    }

    /**
     * Whether [ref] overrides or implements a method of a supertype: a callback the framework or a
     * library may call once an instance exists (`onCreate`, `invoke`, `onClick`). Supertypes outside
     * the DEX (the platform) are not visible, so there a kept (not obfuscated) name counts; except
     * java.lang.Object, whose few methods are known (every class has it, so it would make every
     * unobfuscated method a callback).
     */
    fun overrides(ref: String): Boolean = overriding.getOrPut(ref) {
        val owner = ref.substringBefore("->")
        val sig = ref.substringAfter("->")
        if (sig.startsWith("<")) return@getOrPut false
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        superOf[owner]?.let { queue += it }
        queue += interfacesOf[owner].orEmpty()
        var leavesDex = false
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (!seen.add(t)) continue
            if (t == "Ljava/lang/Object;") { if (sig in OBJECT_METHODS) return@getOrPut true; continue }
            if (t !in defined) { leavesDex = true; continue }
            if (sig in declared[t].orEmpty()) return@getOrPut true
            superOf[t]?.let { queue += it }
            queue += interfacesOf[t].orEmpty()
        }
        leavesDex && CodeMatcher.stableMember(sig.substringBefore('('))
    }

    /** Targets of a virtual or interface call: the receiver type's own method, and overrides in subtypes. */
    class Targets(val upward: String?, val downward: List<String>, val tooMany: Boolean) {
        val all: List<String> get() = listOfNotNull(upward) + downward
    }

    /** Facts of [ref], restricted to dispatch case [case] when it is a dispatching method. */
    fun facts(ref: String, case: Int?): Facts? {
        val all = methods[ref] ?: return null
        if (case == null) return all
        val byCase = cases[ref] ?: return all
        byCase[case]?.let { return it }
        // The switch lists the ids of the merged classes that implement this method; its default
        // branch is one more. An id not listed takes the default only when it is the one id of the
        // class left over; otherwise its class does not implement the method.
        val listed = byCase.keys.filterNotNull().toSet()
        val leftover = ids[ref.substringBefore("->")].orEmpty() - listed
        return if (leftover == setOf(case)) byCase[null] else null
    }

    /**
     * Fields that hold a dispatch instance created with a known case, possibly wrapped by a static
     * call (`DoubleCheck.provider(new SwitchingProvider(this, 3))`), and never anything else. Hilt
     * stores every binding's provider this way; reading the field and calling `get()` runs that case.
     */
    private fun findFieldCases(all: List<Triple<DexFile, dev.apkproject.dex.DexMethod, DecodedCode>>) {
        val other = HashSet<String>()
        val seen = HashMap<String, Pair<String, Int>>()
        for ((d, _, code) in all) {
            val starts = blockStarts(code)
            val carried = HashMap<Int, Pair<String, Int>>()
            var result: Pair<String, Int>? = null
            for ((k, i) in code.insns.withIndex()) {
                if (i.address in starts) carried.clear()
                val mn = i.op.mnemonic
                val pendingResult = result
                result = null
                when {
                    mn == "new-instance" -> {
                        val c = dispatchCase(d, code, k)
                        if (c != null) { carried[i.regs[0]] = d.type(i.index) to c; continue }
                    }
                    mn.startsWith("move-result") -> {
                        if (pendingResult != null) { carried[i.regs[0]] = pendingResult; continue }
                    }
                    // A one-argument static wrapper (DoubleCheck.provider, SingleCheck.provider) keeps it.
                    mn.startsWith("invoke-static") && i.regs.size == 1 -> result = carried[i.regs[0]]
                    mn.startsWith("iput") || mn.startsWith("sput") -> {
                        val f = d.fieldDescriptor(i.index)
                        val v = carried[i.regs[0]]
                        if (v == null || (seen[f] != null && seen[f] != v)) other += f else seen[f] = v
                    }
                }
                for (w in DataflowShape.writes(i, code.code.registersSize)) carried.remove(w)
                if (i.op.flow != dev.apkproject.dex.FlowKind.NONE) carried.clear()
            }
        }
        for ((f, v) in seen) if (f !in other) fieldCases[f] = v
    }

    /** Addresses where a straight-line block starts: branch, switch and exception handler targets. */
    private fun blockStarts(code: DecodedCode): Set<Int> = branchTargets.getOrPut(code) {
        val t = HashSet<Int>()
        for (i in code.insns) {
            if (i.target >= 0 && !i.op.payloadRef) t += i.target
            if (i.op.flow == dev.apkproject.dex.FlowKind.SWITCH) code.switchCases(i).forEach { (_, a) -> t += a }
        }
        for (tr in code.code.tries) code.code.handlerFor(tr).let { h -> h.typed.forEach { t += it.second }; if (h.catchAllAddr >= 0) t += h.catchAllAddr }
        t
    }

    /** The provider field the receiver of the call at [k] was just read from, as its (class, case). */
    private fun receiverCase(d: DexFile, code: DecodedCode, k: Int): Pair<String, Int>? {
        if (fieldCases.isEmpty()) return null
        val r = code.insns[k].regs.firstOrNull() ?: return null
        val starts = blockStarts(code)
        for (j in k - 1 downTo maxOf(0, k - 24)) {
            val x = code.insns[j]
            if (x.op.flow != dev.apkproject.dex.FlowKind.NONE) return null
            if (r in DataflowShape.writes(x, code.code.registersSize)) {
                val mn = x.op.mnemonic
                return if (mn.startsWith("iget-object") || mn.startsWith("sget-object")) fieldCases[d.fieldDescriptor(x.index)] else null
            }
            if (x.address in starts) return null
        }
        return null
    }

    /** Per case: the facts of the instructions before the switch plus those reachable from the case's target. */
    private fun slice(d: DexFile, code: DecodedCode, ref: String, owner: String, switchAt: Int): Map<Int?, Facts> {
        val position = HashMap<Int, Int>()
        code.insns.forEachIndexed { k, i -> position[i.address] = k }
        fun reachable(start: Int): Set<Int> {
            val seen = HashSet<Int>()
            val stack = ArrayDeque(listOf(start))
            while (stack.isNotEmpty()) {
                val k = stack.removeLast()
                if (k < 0 || k >= code.insns.size || !seen.add(k)) continue
                val i = code.insns[k]
                val flow = i.op.flow
                if (flow != dev.apkproject.dex.FlowKind.GOTO && flow != dev.apkproject.dex.FlowKind.RETURN && flow != dev.apkproject.dex.FlowKind.THROW) stack += k + 1
                if (i.target >= 0 && !i.op.payloadRef) position[i.target]?.let { stack += it }
                if (flow == dev.apkproject.dex.FlowKind.SWITCH) code.switchCases(i).forEach { (_, t) -> position[t]?.let { stack += it } }
            }
            return seen
        }
        val prefix = (0 until switchAt).toSet()
        val out = HashMap<Int?, Facts>()
        for ((key, target) in code.switchCases(code.insns[switchAt])) position[target]?.let { out[key] = facts(d, code, ref, owner, (prefix + reachable(it)).sorted()) }
        out[null] = facts(d, code, ref, owner, (prefix + reachable(switchAt + 1)).sorted())
        return out
    }

    private fun facts(d: DexFile, code: DecodedCode, ref: String, owner: String, indices: Iterable<Int>): Facts {
        val calls = ArrayList<Call>()
        val news = ArrayList<New>()
        val consts = LinkedHashSet<String>()
        val strings = ArrayList<StringUse>()
        for (k in indices) {
            val i = code.insns[k]
            val m = i.op.mnemonic
            when {
                i.op.index == IndexKind.METHOD && m.startsWith("invoke") -> {
                    val kind = when {
                        m.startsWith("invoke-static") -> Kind.STATIC
                        m.startsWith("invoke-direct") -> Kind.DIRECT
                        m.startsWith("invoke-super") -> Kind.SUPER
                        m.startsWith("invoke-interface") -> Kind.INTERFACE
                        else -> Kind.VIRTUAL
                    }
                    calls += Call(d.methodDescriptor(i.index), kind, if (kind == Kind.INTERFACE || kind == Kind.VIRTUAL) receiverCase(d, code, k) else null)
                }
                m == "new-instance" -> {
                    val type = d.type(i.index)
                    val case = dispatchCase(d, code, k)
                    if (case != null) ids.getOrPut(type) { HashSet() } += case
                    news += New(type, case)
                }
                m == "const-class" -> consts += d.type(i.index)
                i.op.index == IndexKind.STRING -> {
                    val use = firstUse(code, k)?.takeIf { it.op.index == IndexKind.METHOD && it.op.mnemonic.startsWith("invoke") }
                    strings += StringUse(d.string(i.index), use?.let { d.methodDescriptor(it.index) }, use?.regs?.indexOf(i.regs[0]) ?: -1)
                }
            }
        }
        return Facts(ref, owner, calls, news, consts, strings)
    }

    /** The constant passed as the dispatch id where a [Dispatch] class is created at instruction [k], if any. */
    private fun dispatchCase(d: DexFile, code: DecodedCode, k: Int): Int? {
        val disp = dispatch[d.type(code.insns[k].index)] ?: return null
        val init = firstUse(code, k) ?: return null
        if (init.op.index != IndexKind.METHOD) return null
        val ctor = d.methodDescriptor(init.index)
        disp.fixed[ctor]?.let { return it }
        val param = disp.constructors[ctor] ?: return null
        val params = d.protos[d.methodIds[init.index].protoIdx].parameters.map { d.type(it) }
        var slot = 1
        for (p in 0 until param) slot += if (params[p] == "J" || params[p] == "D") 2 else 1
        val reg = init.regs.getOrNull(slot) ?: return null
        val at = code.insns.indexOf(init)
        // Only within the same straight-line block: a constant on another path says nothing.
        val targets = blockStarts(code)
        for (j in at - 1 downTo maxOf(0, at - 40)) {
            val x = code.insns[j]
            if (x.op.flow != dev.apkproject.dex.FlowKind.NONE) return null
            if (reg in DataflowShape.writes(x, code.code.registersSize)) {
                val mn = x.op.mnemonic
                return if (mn == "const" || mn.startsWith("const/")) x.literal.toInt() else null
            }
            if (x.address in targets) return null
        }
        return null
    }


    /** The first later instruction that reads the register instruction [k] wrote, unless it is overwritten first. */
    private fun firstUse(code: DecodedCode, k: Int): Insn? {
        val r = code.insns[k].regs.firstOrNull() ?: return null
        for (j in k + 1 until minOf(code.insns.size, k + 48)) {
            val x = code.insns[j]
            if (r in DataflowShape.reads(x)) return x
            if (r in DataflowShape.writes(x, code.code.registersSize)) return null
        }
        return null
    }

    /**
     * The methods with code a call can run. Static, direct and super calls resolve upwards to the
     * declaring class; virtual and interface calls also take every override in a subtype, unless
     * there are more than [MAX_TARGETS] (a generic callback such as `Runnable.run`, which says
     * nothing about which code runs).
     */
    fun targets(call: Call): Targets = resolved.getOrPut(call.ref + call.kind) {
        val owner = call.ref.substringBefore("->")
        val sig = call.ref.substringAfter("->")
        fun upward(start: String): String? {
            var c: String? = start
            val seen = HashSet<String>()
            while (c != null && seen.add(c)) {
                if ("$c->$sig" in methods) return "$c->$sig"
                c = superOf[c]
            }
            return null
        }
        when (call.kind) {
            Kind.STATIC, Kind.DIRECT, Kind.SUPER -> Targets(upward(owner), emptyList(), false)
            else -> {
                val down = LinkedHashSet<String>()
                val queue = ArrayDeque(subtypes[owner].orEmpty())
                val seen = HashSet<String>()
                while (queue.isNotEmpty() && down.size <= MAX_TARGETS) {
                    val s = queue.removeFirst()
                    if (!seen.add(s)) continue
                    if ("$s->$sig" in methods) down += "$s->$sig"
                    queue += subtypes[s].orEmpty()
                }
                Targets(upward(owner), down.toList(), down.size > MAX_TARGETS)
            }
        }
    }

    companion object {
        /**
         * The [Dispatch] classes among [all] methods: classes whose constructors store an int id (a
         * parameter or a constant) that a method of the class then switches on.
         */
        fun findDispatch(all: List<Triple<DexFile, dev.apkproject.dex.DexMethod, DecodedCode>>): Map<String, Dispatch> {
            val dispatch = HashMap<String, Dispatch>()
            // owner -> ctor ref -> (field, parameter index or null, constant or null)
            val ctorFields = HashMap<String, MutableMap<String, Triple<String, Int?, Int?>>>()
            for ((d, m, code) in all) {
                if (m.name != "<init>") continue
                val params = m.proto.parameters.map { d.type(it) }
                val self = code.code.registersSize - code.code.insSize
                var reg = self + 1
                val regOf = params.map { t -> reg.also { reg += if (t == "J" || t == "D") 2 else 1 } }
                for ((k, i) in code.insns.withIndex()) {
                    if (i.op.mnemonic != "iput" || i.regs.getOrNull(1) != self) continue
                    val f = d.fieldDescriptor(i.index)
                    if (!f.startsWith("${m.owner}->") || !f.endsWith(":I")) continue
                    val ref = "${m.owner}->${m.name}${m.protoDescriptor}"
                    val p = regOf.indexOf(i.regs[0]).takeIf { it >= 0 && params[it] == "I" }
                    // Or a constant the constructor loads itself: `const/16 v0, #24; iput v0, p0, id`.
                    val constant = if (p != null) null else (k - 1 downTo maxOf(0, k - 4)).map { code.insns[it] }
                        .firstOrNull { i.regs[0] in DataflowShape.writes(it, code.code.registersSize) }
                        ?.takeIf { it.op.mnemonic == "const" || it.op.mnemonic.startsWith("const/") }?.literal?.toInt()
                    if (p == null && constant == null) continue
                    ctorFields.getOrPut(m.owner) { HashMap() }[ref] = Triple(f, p, constant)
                    break
                }
            }
            for ((d, m, code) in all) {
                val ctors = ctorFields[m.owner] ?: continue
                if (m.owner in dispatch) continue
                for (f in ctors.values.map { it.first }.distinct()) {
                    if (dispatchSwitch(d, code, f) != null) {
                        val mine = ctors.filterValues { it.first == f }
                        dispatch[m.owner] = Dispatch(
                            f,
                            mine.filterValues { it.second != null }.mapValues { it.value.second!! },
                            mine.filterValues { it.third != null }.mapValues { it.value.third!! },
                        )
                        break
                    }
                }
            }
            return dispatch
        }

        /**
         * The index of a switch on [field] (read from `this`) near the start of the method. R8 hoists
         * constants above the switch, so it is the first switch on the loaded register, as long as no
         * branch or write to that register comes first.
         */
        fun dispatchSwitch(d: DexFile, code: DecodedCode, field: String): Int? {
            val self = code.code.registersSize - code.code.insSize
            val selfCopies = HashSet<Int>().apply { add(self) }
            for ((k, i) in code.insns.withIndex()) {
                if (k > 12) return null
                if (i.op.mnemonic.startsWith("move-object") && i.regs.getOrNull(1) in selfCopies) selfCopies += i.regs[0]
                if (i.op.mnemonic != "iget" || i.regs.getOrNull(1) !in selfCopies || d.fieldDescriptor(i.index) != field) continue
                val r = i.regs[0]
                for (j in k + 1 until minOf(code.insns.size, k + 64)) {
                    val x = code.insns[j]
                    if (x.op.mnemonic.endsWith("-switch") && x.regs.firstOrNull() == r) return j
                    if (x.op.flow != dev.apkproject.dex.FlowKind.NONE || r in DataflowShape.writes(x, code.code.registersSize)) return null
                }
            }
            return null
        }

        /** Overrides beyond this many in subtypes: a generic callback (Runnable.run), not followed. */
        const val MAX_TARGETS = 32
        private val OBJECT_METHODS = setOf("toString()Ljava/lang/String;", "equals(Ljava/lang/Object;)Z", "hashCode()I", "finalize()V", "clone()Ljava/lang/Object;")
    }
}

/**
 * Screen → code → server and database: for every screen (and service, receiver, worker), the
 * endpoints, servers, SQL statements and stored settings its code can reach, each with the call
 * chain that leads there. The chain is static: code the walk reaches may still be guarded at run
 * time, so every reach is INFERRED. Targets shared by most screens (base classes, app start-up) are
 * marked by how many screens share them.
 */
object ScreenMap {
    /**
     * A SQL statement by its shape, not its first word: "Update" or "with tag" alone is text.
     * Group 1 is the operation.
     */
    private val SQL = Regex(
        """^\s*(?:(SELECT)\s+\S[\s\S]*?\bFROM\s+\S|(INSERT)\s+(?:OR\s+\w+\s+)?INTO\s+\S|(UPDATE)\s+(?:OR\s+\w+\s+)?\S+\s+SET\s|(DELETE)\s+FROM\s+\S|(REPLACE)\s+INTO\s+\S|(WITH)\s+(?:RECURSIVE\s+)?\S+(?:\s*\([^)]*\))?\s+AS\s*\()""",
        RegexOption.IGNORE_CASE,
    )
    /** XML namespace identifiers look like URLs but name no server the app contacts. */
    private val NAMESPACE_HOSTS = setOf("www.w3.org", "schemas.android.com", "ns.adobe.com", "xmlpull.org", "schemas.xmlsoap.org", "schemas.microsoft.com", "purl.org", "xml.org")
    internal fun sqlOp(q: String): String? = SQL.find(q)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.uppercase()?.let { if (it == "WITH") "SELECT" else it }
    private val SCHEMA = Regex("""^\s*(?:CREATE\s+(?:TEMP(?:ORARY)?\s+)?(?:UNIQUE\s+)?(?:VIRTUAL\s+)?(?:TABLE|INDEX|VIEW|TRIGGER)\s|DROP\s+(?:TABLE|INDEX|VIEW|TRIGGER)\s|ALTER\s+TABLE\s|PRAGMA\s+\w)""", RegexOption.IGNORE_CASE)
    private val TABLE = Regex("""\b(?:FROM|INTO|UPDATE|JOIN)\s+(?:OR\s+\w+\s+)?[`"\[]?([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""^(?:https?|wss?)://([A-Za-z0-9.-]+)(?::\d+)?(?:/\S*)?$""")
    private val NETWORK_CALLS = listOf(
        "Ljava/net/URL;->openConnection", "Ljava/net/URL;->openStream", "Ljava/net/HttpURLConnection;->connect",
        "Ljavax/net/ssl/HttpsURLConnection;->connect", "Landroid/webkit/WebView;->loadUrl", "Landroid/webkit/WebView;->postUrl",
        "Ljava/net/Socket;-><init>", "Lokhttp3/Call;->execute", "Lokhttp3/Call;->enqueue", "Lokhttp3/OkHttpClient;->newWebSocket",
        "Lretrofit2/Call;->execute", "Lretrofit2/Call;->enqueue", "Landroid/app/DownloadManager;->enqueue",
    )
    private val SQLITE_CALLS = listOf("query", "rawQuery", "insert", "insertOrThrow", "insertWithOnConflict", "replace", "update", "updateWithOnConflict", "delete", "execSQL")
    private const val PREFS_FILE = "->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;"
    private val DEFAULT_PREFS = listOf("Landroid/preference/PreferenceManager;->getDefaultSharedPreferences", "Landroidx/preference/PreferenceManager;->getDefaultSharedPreferences")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val TABLE_CALLS = mapOf(
        "query" to "SELECT", "insert" to "INSERT", "insertOrThrow" to "INSERT", "insertWithOnConflict" to "INSERT",
        "replace" to "REPLACE", "replaceOrThrow" to "REPLACE", "update" to "UPDATE", "updateWithOnConflict" to "UPDATE", "delete" to "DELETE",
    )
    private val CONTENT_CALLS = listOf("query", "insert", "update", "delete", "bulkInsert")
    private const val MAX_DEPTH = 24
    private const val MAX_METHODS = 60_000

    class Component(val name: String, val kind: String, val exported: Boolean, val launcher: Boolean)

    /** Manifest components, plus WorkManager workers (named classes with `doWork`/`startWork`). */
    fun components(manifestXml: String?, graph: AppGraph): List<Component> {
        val out = ArrayList<Component>()
        val manifest = manifestXml?.let { AppDiff.Manifest.parse(it) }
        for (c in manifest?.components?.values.orEmpty()) {
            val kind = when (c.tag) { "activity", "activity-alias" -> "ACTIVITY"; "service" -> "SERVICE"; "receiver" -> "RECEIVER"; else -> "PROVIDER" }
            val descriptor = "L" + c.name.replace('.', '/') + ";"
            if (c.tag == "activity-alias" || descriptor !in graph.defined) continue
            out += Component(descriptor, kind, c.exported, "android.intent.action.MAIN" in c.actions)
        }
        val known = out.map { it.name }.toSet()
        for (owner in graph.defined) {
            if (owner in known || !CodeMatcher.stableClassName(owner)) continue
            if (graph.methodsOf(owner).any { it.contains("->doWork(") || it.contains("->startWork(") }) out += Component(owner, "WORKER", false, false)
        }
        return out.sortedWith(compareBy({ listOf("ACTIVITY", "SERVICE", "WORKER", "RECEIVER", "PROVIDER").indexOf(it.kind) }, { it.name }))
    }

    fun analyze(dex: List<Pair<String, DexFile>>, manifestXml: String?, names: Map<String, String> = emptyMap()): ScreenMapReport {
        val graph = AppGraph(dex)
        val endpointMethods = runCatching { BytecodeApi.endpointMethods(dex) }.getOrDefault(emptyMap())
        val components = components(manifestXml, graph)
        val byName = components.associateBy { it.name }
        val appPackage = manifestXml?.let { AppDiff.Manifest.parse(it)?.packageName }
        fun display(descriptor: String): String {
            val owner = descriptor.substringBefore("->")
            val cls = (names[owner] ?: owner.removePrefix("L").removeSuffix(";").replace('/', '.'))
            if ("->" !in descriptor) return cls
            return cls + "." + descriptor.substringAfter("->").substringBefore('(')
        }

        class Found(val reach: DataReach, val component: String)
        val flows = ArrayList<ScreenFlow>()
        val found = ArrayList<Found>()
        for (component in components) {
            // The component's own code and the app's base classes it inherits (BaseActivity), not
            // the framework or library classes above them.
            val seeds = ArrayList<String>()
            var cls: String? = component.name
            val seen = HashSet<String>()
            while (cls != null && cls in graph.defined && seen.add(cls)) {
                if (cls != component.name && !(appPackage != null && cls.removePrefix("L").replace('/', '.').startsWith("$appPackage.") && CodeMatcher.stableClassName(cls))) break
                seeds += graph.methodsOf(cls)
                cls = graph.superOf[cls]
            }
            // A node is a method, or "method#case" for one body of a dispatching class.
            val parent = HashMap<String, String?>()
            val depth = HashMap<String, Int>()
            val queue = ArrayDeque<String>()
            for (s in seeds) if (s !in depth) { parent[s] = null; depth[s] = 0; queue += s }
            val opens = LinkedHashSet<String>()
            val starts = LinkedHashSet<String>()
            val reaches = LinkedHashMap<String, DataReach>()
            fun path(to: String): List<String> {
                val out = ArrayList<String>()
                var at: String? = to
                while (at != null && out.size < 40) { out += display(at.substringBefore('#')); at = parent[at] }
                return out.reversed().fold(ArrayList<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
            }
            fun record(kind: String, target: String, detail: String, at: String) {
                val key = "$kind|$target"
                if (key !in reaches) reaches[key] = DataReach(kind, target, detail, path(at))
            }
            fun enqueue(from: String, to: String) {
                if (to in depth) return
                depth[to] = depth.getValue(from) + 1
                parent[to] = from
                queue += to
            }
            // Rapid type analysis: an override in a subtype is followed only once the walk has created
            // an instance of that class (calls waiting for one are kept until then).
            val instantiated = HashSet<String>()
            val pending = HashMap<String, MutableList<Pair<String, String>>>()
            fun instantiate(c: String) {
                for (a in graph.ancestors(c)) if (instantiated.add(a)) pending.remove(a)?.forEach { (from, to) -> enqueue(from, to) }
            }
            instantiate(component.name)
            var truncated = false
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                if (depth.size > MAX_METHODS) { truncated = true; break }
                val atCase = at.substringAfter('#', "").toIntOrNull()
                val f = graph.facts(at.substringBefore('#'), atCase) ?: continue
                val d = depth.getValue(at)
                for (call in f.calls) {
                    endpointMethods[call.ref]?.let { record("ENDPOINT", it, "via ${display(call.ref)}", at) }
                    val ref = call.ref
                    if (NETWORK_CALLS.any { ref.startsWith(it) }) record("NETWORK_CALL", display(ref), "framework network call", at)
                    if (ref.startsWith("Landroid/database/sqlite/SQLiteDatabase;->") && ref.substringAfter("->").substringBefore('(') in SQLITE_CALLS) {
                        val name = ref.substringAfter("->").substringBefore('(')
                        val used = f.strings.filter { it.usedBy == ref && it.slot == 1 }.map { it.value }
                        when {
                            // A constant statement is recorded below, with its tables.
                            used.any { sqlOp(it) != null || SCHEMA.containsMatchIn(it) } -> Unit
                            // Built from a constant (`"UPDATE notes SET ... id = " + id`): that text is recorded below.
                            name == "execSQL" || name == "rawQuery" -> if (f.strings.none { sqlOp(it.value) != null || SCHEMA.containsMatchIn(it.value) }) record("SQL", "$name (statement built at run time)", "SQLiteDatabase.$name", at)
                            else -> record("SQL", "${TABLE_CALLS[name] ?: name.uppercase()} ${used.firstOrNull()?.takeIf { IDENTIFIER.matches(it) } ?: "(table chosen at run time)"}", "SQLiteDatabase.$name", at)
                        }
                    }
                    if (DEFAULT_PREFS.any { ref.startsWith(it) }) record("PREFERENCE", "file (default)", "PreferenceManager.getDefaultSharedPreferences", at)
                    if (ref.startsWith("Landroid/content/ContentResolver;->") && ref.substringAfter("->").substringBefore('(') in CONTENT_CALLS) {
                        record("CONTENT_PROVIDER", "ContentResolver.${ref.substringAfter("->").substringBefore('(')}", "reads or writes another app's data through a content provider", at)
                    }
                    if (d < MAX_DEPTH) {
                        // A provider read from its field runs the one binding it was created for.
                        call.via?.let { (type, case) ->
                            val t = "$type->${call.ref.substringAfter("->")}"
                            if (graph.dispatches(t)) enqueue(at, "$t#$case")
                        }
                        val targets = graph.targets(call)
                        // A dispatching class is entered where it is created (with its case), not through
                        // its interfaces, which would run every body it holds; a call on `this` inside one
                        // case stays in that case.
                        fun node(t: String): String? = when {
                            !graph.dispatches(t) -> t
                            atCase != null && t.substringBefore("->") == f.owner -> "$t#$atCase"
                            else -> null
                        }
                        when {
                            targets.tooMany -> Unit
                            // One implementation in the whole app (a Room DAO, a repository interface): no doubt which code runs.
                            targets.all.size == 1 -> node(targets.all.single())?.let { enqueue(at, it) }
                            else -> {
                                targets.upward?.let(::node)?.let { enqueue(at, it) }
                                for (t in targets.downward) {
                                    val n = node(t) ?: continue
                                    val owner = t.substringBefore("->")
                                    if (owner in instantiated) enqueue(at, n) else pending.getOrPut(owner) { ArrayList() } += at to n
                                }
                            }
                        }
                    }
                }
                for (s in f.strings) {
                    val v = s.value
                    when {
                        sqlOp(v) != null -> {
                            val op = sqlOp(v)!!
                            val tables = TABLE.findAll(v).map { it.groupValues[1] }.distinct().toList()
                            record("SQL", "$op ${tables.joinToString(", ").ifEmpty { "?" }}", v.trim().replace(Regex("\\s+"), " ").take(240), at)
                        }
                        URL.matches(v) && URL.find(v)!!.groupValues[1].lowercase() !in NAMESPACE_HOSTS -> record("HOST", URL.find(v)!!.groupValues[1].lowercase(), v.take(200), at)
                        // The key is the first argument; a default value (`getString("theme", "light")`) is not a key.
                        s.usedBy == null || s.slot != 1 -> Unit
                        s.usedBy.startsWith("Landroid/content/SharedPreferences;->get") -> record("PREFERENCE", "$v (read)", "SharedPreferences.${s.usedBy.substringAfter("->").substringBefore('(')}", at)
                        s.usedBy.startsWith("Landroid/content/SharedPreferences\$Editor;->") -> record("PREFERENCE", "$v (write)", "SharedPreferences.Editor.${s.usedBy.substringAfter("->").substringBefore('(')}", at)
                        // Called on any Context subclass (`this.getSharedPreferences` in an Activity names the Activity).
                        s.usedBy.endsWith(PREFS_FILE) -> record("PREFERENCE", "file $v", "Context.getSharedPreferences", at)
                    }
                }
                if (d >= MAX_DEPTH) continue
                // Created classes: lambdas, coroutines, listeners and helpers whose code will run.
                for (n in f.news) {
                    if (n.type !in graph.defined || byName[n.type]?.kind == "ACTIVITY") continue
                    instantiate(n.type)
                    // A merged lambda copying itself (a coroutine's create()) keeps its own case.
                    val case = n.case ?: atCase?.takeIf { n.type == f.owner }
                    if (case == null && n.type in graph.dispatch) {
                        // Created with an id the walk cannot see: its bodies are not followed (each would be
                        // credited to this screen), only its constructors.
                        graph.methodsOf(n.type).filter { "-><init>(" in it }.forEach { enqueue(at, it) }
                        continue
                    }
                    // An instance runs its own methods; static methods stored in the same class are not
                    // run by creating it. Of a merged class, only its constructors and its switching
                    // methods (on this case) are its own; other methods belong to other merged classes
                    // and are reached only when this case's code calls them.
                    val own = graph.methodsOf(n.type).filter { it !in graph.statics }
                    // A dependency-injection provider (`get()` switching over bindings: Hilt's
                    // SwitchingProvider) is created for every binding up front; creating one does not
                    // run it. A screen reaches its own objects through the classes it asks for and calls.
                    if (case != null && n.type in graph.dispatch) own.filter { "-><init>(" in it || (graph.dispatches(it) && !it.endsWith("->get()Ljava/lang/Object;")) }
                        .forEach { enqueue(at, if (graph.dispatches(it)) "$it#$case" else it) }
                    // Any other object runs its constructors, and its callbacks (methods overriding a
                    // supertype's: a lambda's invoke, a listener's onClick, a fragment's onCreateView)
                    // once handed to the framework. Its other methods run only when called.
                    else own.filter { "-><init>(" in it || graph.overrides(it) }.forEach { enqueue(at, it) }
                }
                // Class constants: another screen opened, a service or worker started, or a class
                // created by a factory (ViewModels): its constructors run.
                for (c in f.classConsts) {
                    val target = byName[c]
                    when {
                        c == component.name -> Unit
                        target?.kind == "ACTIVITY" -> opens += display(c)
                        target != null -> { starts += display(c); instantiate(c); graph.methodsOf(c).forEach { enqueue(at, it) } }
                        // Created by a factory from its class (a ViewModel): constructors and callbacks run.
                        c in graph.defined -> { instantiate(c); graph.methodsOf(c).filter { it !in graph.statics && ("-><init>(" in it || graph.overrides(it)) || "-><clinit>(" in it }.forEach { enqueue(at, it) } }
                    }
                }
            }
            val list = reaches.values.toList()
            list.forEach { found += Found(it, component.name) }
            flows += ScreenFlow(
                display(component.name), component.kind, component.exported, component.launcher, opens.toList(), starts.toList(),
                list.filter { it.kind in setOf("ENDPOINT", "HOST", "NETWORK_CALL") }, list.filter { it.kind in setOf("SQL", "CONTENT_PROVIDER") },
                list.filter { it.kind == "PREFERENCE" }, depth.size, truncated,
            )
        }
        // How many components share each target; screen-specific ones first.
        val shared = found.groupBy { "${it.reach.kind}|${it.reach.target}" }.mapValues { (_, v) -> v.map { it.component }.distinct().size }
        fun ranked(xs: List<DataReach>) = xs.map { it.copy(sharedBy = shared["${it.kind}|${it.target}"] ?: 1) }.sortedWith(compareBy({ it.sharedBy }, { it.kind }, { it.target }))
        val screens = flows.map { it.copy(network = ranked(it.network), database = ranked(it.database), preferences = ranked(it.preferences)) }
        val threshold = if (components.size >= 4) maxOf(3, (components.size * 2 + 2) / 3) else Int.MAX_VALUE
        val common = screens.flatMap { it.network + it.database + it.preferences }.filter { it.sharedBy >= threshold }
            .distinctBy { "${it.kind}|${it.target}" }.sortedWith(compareBy({ -it.sharedBy }, { it.kind }, { it.target }))

        val endpoints = endpointMethods.values.distinct().sorted().map { e ->
            EndpointUse(e, screens.filter { s -> s.network.any { it.kind == "ENDPOINT" && it.target == e } }.map { it.component })
        }
        // Every table the app's SQL names, whether or not a screen reaches it.
        val allSql = graph.methods.values.flatMap { f -> f.strings.map { it.value } }.filter { sqlOp(it) != null || SCHEMA.containsMatchIn(it) }
        // SQLite table names ignore case: one entry per table, spelled as the schema (or first use) spells it.
        val tableOps = LinkedHashMap<String, MutableSet<String>>()
        val spelling = HashMap<String, String>()
        fun table(name: String, preferred: Boolean = false): MutableSet<String> {
            if (preferred || name.lowercase() !in spelling) spelling[name.lowercase()] = name
            return tableOps.getOrPut(name.lowercase()) { LinkedHashSet() }
        }
        for (q in allSql.filter { SCHEMA.containsMatchIn(it) }) {
            Regex("""CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"\[]?([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE).find(q)?.let { table(it.groupValues[1], preferred = true) }
        }
        for (q in allSql.filter { sqlOp(it) != null }) {
            val op = sqlOp(q)!!
            TABLE.findAll(q).map { it.groupValues[1] }.forEach { table(it) += op }
        }
        // SQLiteDatabase's own calls name the table as their first argument: insert("notes", ...).
        for (f in graph.methods.values) for (u in f.strings) {
            val by = u.usedBy ?: continue
            if (u.slot != 1 || !by.startsWith("Landroid/database/sqlite/SQLiteDatabase;->") || !IDENTIFIER.matches(u.value)) continue
            TABLE_CALLS[by.substringAfter("->").substringBefore('(')]?.let { table(u.value) += it }
        }
        // Room's `_new_X` tables are temporary copies made while a migration rebuilds X.
        val names = tableOps.keys.filter { k -> !k.startsWith("sqlite_") && k !in setOf("select", "where") && !(k.startsWith("_new_") && k.removePrefix("_new_") in tableOps) }
        val tables = names.map { k -> spelling.getValue(k) }.sortedBy { it.lowercase() }.map { t ->
            val users = screens.filter { s -> s.database.any { r -> r.kind == "SQL" && r.target.substringAfter(' ').split(", ").any { it.equals(t, ignoreCase = true) } } }.map { it.component }
            TableUse(t, tableOps.getValue(t.lowercase()).sorted(), users)
        }
        val prefFiles = graph.methods.values.flatMap { f -> f.strings.filter { it.slot == 1 && it.usedBy?.endsWith(PREFS_FILE) == true }.map { it.value } }.distinct().sorted()
        val notes = listOf(
            "Static reachability: a screen reaches what its code, the code it calls, and the lambdas, coroutines and helpers it creates can run. Code can still be guarded at run time, so every reach is INFERRED.",
            "Virtual calls with more than ${AppGraph.MAX_TARGETS} possible implementations (generic callbacks) are not followed, and other activities are not entered: opening a screen is listed under 'opens'.",
            "Services, receivers and workers a screen starts are followed: what they reach counts for the screen.",
            "'Shared by' counts the screens and components that reach the same target; base classes and app start-up make some targets common to almost all.",
        ) + (if (endpointMethods.isEmpty()) listOf("No Retrofit endpoints were found; network reaches are servers and framework network calls only.") else emptyList())
        return ScreenMapReport(screens, endpoints, tables, prefFiles, notes, common, if (threshold == Int.MAX_VALUE) 0 else threshold)
    }
}

/** Human-readable rendering of a [ScreenMapReport]. */
object ScreenMapMarkdown {
    fun render(r: ScreenMapReport): String = buildString {
        appendLine("# Screen → code → server and database")
        appendLine()
        appendLine("${r.screens.count { it.kind == "ACTIVITY" }} screens and ${r.screens.count { it.kind != "ACTIVITY" }} other components; " +
            "${r.endpoints.count { it.screens.isNotEmpty() }} of ${r.endpoints.size} endpoints and ${r.tables.count { it.screens.isNotEmpty() }} of ${r.tables.size} database tables are reached from at least one.")
        appendLine()
        appendLine("| Screen / component | Kind | Endpoints | Servers | SQL | Settings | Opens |")
        appendLine("|---|---|---:|---:|---:|---:|---|")
        for (s in r.screens) appendLine("| ${s.component} | ${s.kind} | ${s.network.count { it.kind == "ENDPOINT" }} | ${s.network.count { it.kind == "HOST" }} | ${s.database.count { it.kind == "SQL" }} | ${s.preferences.size} | ${cell(s.opens.take(4).joinToString())} |")
        appendLine()
        if (r.common.isNotEmpty()) {
            appendLine("## Common to most components")
            appendLine()
            appendLine("Reached by ${r.commonThreshold} or more of the ${r.screens.size} components (base classes, app start-up, shared libraries); listed once here, not under each screen.")
            appendLine()
            appendLine("| Kind | Target | Shared by | Example path |")
            appendLine("|---|---|---:|---|")
            for (x in r.common) appendLine("| ${x.kind} | ${cell(x.target)} | ${x.sharedBy} | ${cell(x.path.joinToString(" → "))} |")
            appendLine()
        }
        fun own(xs: List<DataReach>) = if (r.commonThreshold > 0) xs.filter { it.sharedBy < r.commonThreshold } else xs
        for (s in r.screens) {
            if (own(s.network).isEmpty() && own(s.database).isEmpty() && own(s.preferences).isEmpty()) continue
            appendLine("## ${s.component} (${s.kind.lowercase()}${if (s.exported) ", exported" else ""}${if (s.launcher) ", launcher" else ""})")
            appendLine()
            if (s.starts.isNotEmpty()) appendLine("Starts: ${s.starts.joinToString()}\n")
            for ((title, xs) in listOf("Network" to own(s.network), "Database" to own(s.database), "Stored settings" to own(s.preferences))) {
                if (xs.isEmpty()) continue
                appendLine("**$title**")
                appendLine()
                appendLine("| Target | Shared by | Path |")
                appendLine("|---|---:|---|")
                for (x in xs.take(60)) appendLine("| ${cell(x.target)} | ${x.sharedBy} | ${cell(x.path.joinToString(" → "))} |")
                if (xs.size > 60) appendLine("\n${xs.size - 60} more in screen-map.json.")
                appendLine()
            }
        }
        appendLine("## Endpoints by screen")
        appendLine()
        appendLine("| Endpoint | Reached from |")
        appendLine("|---|---|")
        for (e in r.endpoints) appendLine("| ${cell(e.endpoint)} | ${cell(e.screens.joinToString().ifEmpty { "(no screen: not reached statically)" })} |")
        appendLine()
        if (r.tables.isNotEmpty()) {
            appendLine("## Database tables")
            appendLine()
            appendLine("| Table | Operations in the code | Reached from |")
            appendLine("|---|---|---|")
            for (t in r.tables) appendLine("| ${t.table} | ${t.operations.joinToString().ifEmpty { "schema only" }} | ${cell(t.screens.joinToString().ifEmpty { "-" })} |")
            appendLine()
        }
        if (r.preferenceFiles.isNotEmpty()) appendLine("Preference files: ${r.preferenceFiles.joinToString()}\n")
        appendLine("## Notes")
        appendLine()
        r.notes.forEach { appendLine("- $it") }
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").let { if (it.length > 300) it.take(297) + "…" else it }
}
