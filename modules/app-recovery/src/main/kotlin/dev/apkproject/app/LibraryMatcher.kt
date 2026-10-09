package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder
import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * Finds library classes that R8 renamed, by what renaming does not change. Framework types,
 * methods and fields (java.*, android.*, ...) keep their names; string constants stay; and a class
 * keeps the shape of its fields and methods (R8 only removes members). Classes of the APK are
 * compared with the classes of candidate library artifacts (dexed with D8 so both sides are read by
 * the same code), and a match is accepted only when it clearly beats every alternative. Matched
 * classes then become named types on both sides, which sharpens every shape that mentions them,
 * and matching repeats until nothing new is found.
 */
object LibraryMatcher {
    @Serializable
    data class ClassMatch(val obfuscated: String, val original: String, val score: Double, val margin: Double, val by: String = "features")

    @Serializable
    data class Result(val classes: List<ClassMatch>, val methods: Map<String, Map<String, String>>, val fields: Map<String, Map<String, String>>, val rounds: Int)

    private val FRAMEWORK = listOf("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lorg/json/", "Lorg/xml/", "Lorg/w3c/", "Lsun/", "Lcom/android/internal/")

    fun isFramework(type: String): Boolean {
        val t = type.trimStart('[')
        return t.length == 1 || FRAMEWORK.any { t.startsWith(it) }
    }

    /** Rename-invariant description of one class. */
    class Profile(
        val name: String,
        val kind: Int,
        val superType: String,
        val interfaces: List<String>,
        val fields: List<Pair<Boolean, String>>,
        val fieldNames: List<Triple<Boolean, String, String>>, // static, name, type
        val methods: List<Triple<Boolean, String, String>>, // static, name, proto
        val strings: Set<String>,
        val refs: Set<String>, // descriptors of methods and fields used by the code
    )

    fun profiles(dex: List<DexFile>): Map<String, Profile> {
        val out = HashMap<String, Profile>()
        for (d in dex) for (c in d.classDefs) {
            val name = d.type(c.classIdx)
            val data = c.classData
            val fieldIds = data?.let { (it.staticFields.map { f -> true to f } + it.instanceFields.map { f -> false to f }) }.orEmpty()
            val fields = fieldIds.map { (st, f) -> st to d.type(d.fieldIds[f.fieldIdx].typeIdx) }
            val fieldNames = fieldIds.map { (st, f) -> Triple(st, d.string(d.fieldIds[f.fieldIdx].nameIdx), d.type(d.fieldIds[f.fieldIdx].typeIdx)) }
            val strings = HashSet<String>()
            val refs = HashSet<String>()
            val methods = ArrayList<Triple<Boolean, String, String>>()
            for (m in d.methods().filter { it.classDef === c }) {
                methods += Triple(m.isStatic, m.name, m.protoDescriptor)
                val code = m.code ?: continue
                val insns = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value?.insns ?: continue
                for (i in insns) when (i.op.index) {
                    IndexKind.STRING -> strings += d.string(i.index)
                    IndexKind.METHOD -> refs += d.methodDescriptor(i.index)
                    IndexKind.FIELD -> refs += d.fieldDescriptor(i.index)
                    IndexKind.TYPE -> refs += "type:" + d.type(i.index)
                    else -> Unit
                }
            }
            val kind = c.accessFlags and (AccessFlags.INTERFACE or AccessFlags.ABSTRACT or AccessFlags.ENUM or AccessFlags.ANNOTATION)
            out[name] = Profile(name, kind, c.superclassIdx.takeIf { it >= 0 && it < d.typeIds.size }?.let { d.type(it) } ?: "", c.interfaces.map { d.type(it) }, fields, fieldNames, methods, strings, refs)
        }
        return out
    }

    /**
     * Matches [apk] classes (obfuscated) to [library] classes. [known] holds matches already
     * established (for example classes whose names R8 kept), obfuscated -> original.
     */
    fun match(apk: Map<String, Profile>, library: Map<String, Profile>, known: Map<String, String> = emptyMap(), maxRounds: Int = 12): Result {
        val matched = HashMap(known)
        val evidence = HashMap<String, ClassMatch>()
        // Classes of the app itself (an outer class that kept a name the libraries do not have) and
        // classes D8/R8 synthesize are never library classes.
        val candidates = apk.keys.filter { obf ->
            val outer = obf.substringBefore('$').let { if (it.endsWith(";")) it else "$it;" }
            !obf.contains("\$\$") && !obf.contains("-\$\$") && (outer == obf || outer !in apk || outer in known || outer.substringAfterLast('/').length <= 3)
        }.toHashSet()
        var rounds = 0
        while (rounds < maxRounds) {
            rounds++
            val ranking = rank(apk.filterKeys { it in candidates }, library, matched)
            val taken = matched.values.toHashSet()
            val proposals = ranking.mapNotNull { (obf, scored) ->
                val best = scored.firstOrNull() ?: return@mapNotNull null
                val second = scored.getOrNull(1)?.second ?: 0.0
                if (best.second >= 0.75 && best.second - second >= 0.15 && best.first !in taken) ClassMatch(obf, best.first, best.second, best.second - second) else null
            }
            // One library class per APK class: keep the strongest claim, and drop contested ones.
            val accepted = proposals.groupBy { it.original }.values.mapNotNull { claims ->
                val sorted = claims.sortedByDescending { it.score }
                if (sorted.size > 1 && sorted[0].score - sorted[1].score < 0.1) null else sorted[0]
            }.filter { coherent(it.obfuscated, it.original, matched) }
            accepted.forEach { matched[it.obfuscated] = it.original; evidence[it.obfuscated] = it }
            val propagated = propagate(apk, library, matched, candidates).filter { (o, n) -> coherent(o, n.first, matched) }
            propagated.forEach { (o, n) -> matched[o] = n.first; evidence[o] = ClassMatch(o, n.first, n.second.toDouble(), 0.0, "structure") }
            if (accepted.isEmpty() && propagated.isEmpty()) break
        }
        val classes = matched.filterKeys { it !in known }.map { (o, n) -> evidence[o] ?: ClassMatch(o, n, 1.0, 0.0) }.sortedBy { it.original }
        val (methods, fields) = members(apk, library, matched)
        return Result(classes, methods, fields, rounds)
    }

    /**
     * Type correspondences implied by classes already matched: the superclass, interfaces, field
     * types and method signatures of a matched pair line up position by position, so an unmatched
     * APK type at a position where the library has type T votes for T. A correspondence is kept
     * when it has at least two votes and twice as many as any rival.
     */
    private fun propagate(apk: Map<String, Profile>, library: Map<String, Profile>, matched: Map<String, String>, candidates: Set<String>): Map<String, Pair<String, Int>> {
        val taken = matched.values.toHashSet()
        val votes = HashMap<String, HashMap<String, Int>>()
        fun vote(a: String, l: String, w: Int) {
            val ab = a.trimStart('['); val lb = l.trimStart('[')
            if (a.length - ab.length != l.length - lb.length) return
            if (ab !in apk || ab in matched || ab !in candidates || lb !in library || lb in taken) return
            votes.getOrPut(ab) { HashMap() }.merge(lb, w, Int::plus)
        }
        fun shapeApk(t: String) = shape(t, matched, apk.keys)
        fun shapeLib(t: String) = shape(t, emptyMap(), library.keys, taken)
        fun types(desc: String) = (Signatures.parameters(desc) ?: emptyList()) + desc.substringAfter(')')
        fun compatible(a: List<String>, l: List<String>) = a.size == l.size && a.indices.all { k ->
            val sa = shapeApk(a[k]); val sl = shapeLib(l[k])
            sa == sl || (sa.trimStart('[') == "*" && sa.length == sl.length - sl.trimStart('[').length + 1 && sl.trimStart('[').startsWith("L") && !isFramework(sl))
        }
        for ((o, n) in matched) {
            val a = apk[o] ?: continue
            val l = library[n] ?: continue
            vote(a.superType, l.superType, 3)
            val ai = a.interfaces.filter { it !in matched && !isFramework(it) }
            val li = l.interfaces.filter { it !in taken && !isFramework(it) }
            if (ai.size == li.size) ai.indices.forEach { vote(ai[it], li[it], if (ai.size == 1) 3 else 1) }
            for ((st, nm, pr) in a.methods) {
                val at = types(pr)
                val options = l.methods.filter { (lst, lnm, lpr) -> lst == st && (nm.length <= 2 || nm == lnm) && compatible(at, types(lpr)) }
                if (options.size != 1) continue
                val lt = types(options.single().third)
                at.indices.forEach { vote(at[it], lt[it], 1) }
            }
            for ((st, t) in a.fields) {
                if (shapeApk(t).trimStart('[') != "*") continue
                val options = l.fields.filter { (lst, lt) -> lst == st && shapeLib(lt).trimStart('[').let { it == "*" || (it.startsWith("L") && !isFramework(it)) } && lt.length - lt.trimStart('[').length == t.length - t.trimStart('[').length }
                if (options.size == 1) vote(t, options.single().second, 1)
            }
        }
        val out = HashMap<String, Pair<String, Int>>()
        val claimed = HashMap<String, MutableList<Pair<String, Int>>>()
        for ((a, vs) in votes) {
            val sorted = vs.entries.sortedByDescending { it.value }
            val best = sorted.first()
            val second = sorted.getOrNull(1)?.value ?: 0
            // Two votes, or a single one with no rival at all (an unambiguous signature alignment).
            if ((best.value >= 2 && best.value >= 2 * second) || (best.value >= 1 && second == 0)) claimed.getOrPut(best.key) { ArrayList() } += a to best.value
        }
        for ((l, claims) in claimed) {
            val sorted = claims.sortedByDescending { it.second }
            val a = sorted[0].first
            if ((sorted.size == 1 || (sorted[0].second >= 2 && sorted[0].second >= 2 * sorted[1].second)) && consistent(apk.getValue(a), library.getValue(l), ::shapeApk, ::shapeLib)) out[a] = l to sorted[0].second
        }
        return out
    }

    /** For every unmatched APK class, library candidates by score (fraction of the class's evidence the candidate has). */
    fun rank(apk: Map<String, Profile>, library: Map<String, Profile>, matched: Map<String, String>): Map<String, List<Pair<String, Double>>> {
        val taken = matched.values.toHashSet()
        fun shapeApk(t: String) = shape(t, matched, apk.keys)
        fun shapeLib(t: String) = shape(t, emptyMap(), library.keys, taken)
        val libTokens = library.filterKeys { it !in taken }.mapValues { (_, p) -> tokens(p, ::shapeLib) }
        val df = HashMap<String, Int>()
        libTokens.values.forEach { ts -> ts.forEach { df.merge(it, 1, Int::plus) } }
        val n = libTokens.size.coerceAtLeast(1)
        fun idf(t: String) = df[t]?.let { ln(1.0 + n.toDouble() / it) } ?: 0.0
        val index = HashMap<String, MutableList<String>>()
        libTokens.forEach { (name, ts) -> ts.forEach { t -> if ((df[t] ?: 0) <= 50) index.getOrPut(t) { ArrayList() } += name } }
        val out = HashMap<String, List<Pair<String, Double>>>()
        for ((obf, p) in apk) {
            if (obf in matched) continue
            val all = tokens(p, ::shapeApk)
            val ts = all.filter { it in df }
            // Evidence the libraries have never seen still counts against a match (it came from elsewhere).
            val unseen = all.count { it !in df && (it.startsWith("s:") || it.startsWith("r:")) }
            val weight = ts.sumOf { idf(it) } + unseen * ln(1.0 + n)
            if (weight < 6.0) continue
            val candidates = ts.flatMap { index[it].orEmpty() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(60).map { it.key }
            out[obf] = candidates.filter { consistent(p, library.getValue(it), ::shapeApk, ::shapeLib) }.map { lib ->
                val lt = libTokens.getValue(lib)
                lib to ts.filter { it in lt }.sumOf { idf(it) } / weight
            }.sortedByDescending { it.second }
        }
        return out
    }

    private fun packageOf(t: String) = t.removePrefix("L").removeSuffix(";").substringBeforeLast('/', "")

    /**
     * R8 usually keeps the classes of one original package together. When the matches already made
     * in an obfuscated package agree on an original package (70% or more of at least three), a match
     * into another package is not believed. With full repackaging (everything in one package) there
     * is no such majority and nothing is filtered.
     */
    private fun coherent(obfuscated: String, original: String, matched: Map<String, String>): Boolean {
        val pkg = packageOf(obfuscated)
        val neighbours = matched.filter { (o, n) -> o != n && packageOf(o) == pkg }.values.map { packageOf(it) }
        if (neighbours.size < 3) return true
        val (top, count) = neighbours.groupingBy { it }.eachCount().maxBy { it.value }.let { it.key to it.value }
        return count * 10 < neighbours.size * 7 || top == packageOf(original)
    }

    /**
     * Hard constraints a correct match satisfies: the same kind (interface, annotation, enum), and a
     * superclass that, when already known on the APK side, is the library class's superclass.
     */
    private fun consistent(a: Profile, l: Profile, shapeApk: (String) -> String, shapeLib: (String) -> String): Boolean {
        val kinds = AccessFlags.INTERFACE or AccessFlags.ANNOTATION or AccessFlags.ENUM
        if ((a.kind and kinds) != (l.kind and kinds)) return false
        val sa = shapeApk(a.superType)
        return sa == "*" || sa == shapeLib(l.superType) || shapeLib(l.superType) == "*"
    }

    /** A type as matching sees it: framework names and primitives as they are, matched classes by their original name, others unknown. */
    private fun shape(t: String, names: Map<String, String>, own: Set<String>, known: Set<String> = emptySet()): String {
        val dims = t.takeWhile { it == '[' }
        val base = t.substring(dims.length)
        return dims + when {
            base.length == 1 || isFramework(base) -> base
            names[base] != null -> names.getValue(base)
            base in known -> base
            else -> "*"
        }
    }

    private fun proto(desc: String, shape: (String) -> String): String {
        val params = Signatures.parameters(desc + "V")?.dropLast(0) ?: return "?"
        return params.joinToString("", "(", ")") { shape(it) } + shape(desc.substringAfter(')'))
    }

    /** Features of a class: strings, framework references, and member shapes. */
    private fun tokens(p: Profile, shape: (String) -> String): Set<String> {
        val out = HashSet<String>()
        p.strings.forEach { out += "s:$it" }
        for (r in p.refs) {
            if (r.startsWith("type:")) shape(r.removePrefix("type:")).takeIf { it != "*" }?.let { out += "t:$it" }
            else {
                val owner = r.substringBefore("->")
                val so = shape(owner)
                if (so == "*") continue
                // A framework member keeps its name; a library member may have been renamed, so only its
                // owner and the shape of its signature count.
                if (isFramework(owner)) out += "r:$r"
                else {
                    out += "o:$so"
                    val member = r.substringAfter("->")
                    out += if ('(' in member) "q:$so->${proto(member.substring(member.indexOf('(')), shape)}" else "q:$so->:${shape(member.substringAfter(':'))}"
                }
            }
        }
        out += "k:${p.kind}:${shape(p.superType)}"
        p.interfaces.forEach { out += "i:${shape(it)}" }
        p.fields.groupingBy { (st, t) -> "f:$st:${shape(t)}" }.eachCount().forEach { (k, c) -> out += "$k#$c" }
        p.methods.groupingBy { (st, _, pr) -> "m:$st:${proto(pr, shape)}" }.eachCount().forEach { (k, c) -> repeat(c) { i -> out += "$k#$i" } }
        p.methods.filter { (_, nm, _) -> nm.length > 2 && nm != "<init>" && nm != "<clinit>" }.forEach { (_, nm, pr) -> out += "n:$nm${proto(pr, shape)}" }
        return out
    }

    /** Members of matched classes: same name if R8 kept it, else the unique member with the same shape. */
    private fun members(apk: Map<String, Profile>, library: Map<String, Profile>, matched: Map<String, String>): Pair<Map<String, Map<String, String>>, Map<String, Map<String, String>>> {
        val methods = HashMap<String, Map<String, String>>()
        val fields = HashMap<String, Map<String, String>>()
        val names = matched
        fun shapeApk(t: String) = shape(t, names, apk.keys)
        fun shapeLib(t: String) = shape(t, emptyMap(), library.keys, matched.values.toHashSet())
        for ((obf, orig) in matched) {
            val a = apk[obf] ?: continue
            val l = library[orig] ?: continue
            val mm = HashMap<String, String>()
            val libByShape = l.methods.groupBy { (st, _, pr) -> "$st:${proto(pr, ::shapeLib)}" }
            for ((st, nm, pr) in a.methods) {
                if (nm == "<init>" || nm == "<clinit>") continue
                if (l.methods.any { it.second == nm && proto(it.third, ::shapeLib) == proto(pr, ::shapeApk) }) continue // name kept
                val same = libByShape["$st:${proto(pr, ::shapeApk)}"]?.map { it.second }?.distinct() ?: continue
                if (same.size == 1 && same.single() != nm) mm["$nm$pr"] = same.single()
            }
            if (mm.isNotEmpty()) methods[obf] = mm
            val fm = HashMap<String, String>()
            val libFields = l.fieldNames.groupBy { (st, _, t) -> "$st:${shapeLib(t)}" }
            for ((st, nm, t) in a.fieldNames) {
                if (l.fieldNames.any { it.second == nm }) continue
                val same = libFields["$st:${shapeApk(t)}"]?.map { it.second }?.distinct() ?: continue
                if (same.size == 1) fm[nm] = same.single()
            }
            if (fm.isNotEmpty()) fields[obf] = fm
        }
        return methods to fields
    }
}
