package dev.apkproject.app

import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renames that make obfuscated code expressible in Java. R8 reuses one short name for a package,
 * a class and a field (`k1` the package, `b0.k1` the class, `p` the field and `p` the class next
 * to it). Bytecode does not care; Java's name resolution does: a variable or type named `k1`
 * obscures the package `k1`, so `k1.s` no longer compiles (JLS 6.4.2). The fix is applied by
 * JADX through a Tiny v2 mapping and undone by the round trip, so no comparison is lost.
 */
@Serializable
data class NameMapping(
    /** Original class descriptor -> descriptor in the recovered source. */
    val classes: Map<String, String>,
    /** Original class descriptor -> (original field name -> name in the recovered source). */
    val fields: Map<String, Map<String, String>>,
    /** Original class descriptor -> (original method name + descriptor -> name in the recovered source). */
    val methods: Map<String, Map<String, String>> = emptyMap(),
    /** Classes named after a library class they were matched to (evidence level INFERRED). */
    val libraryMatches: Int = 0,
    /** Classes, fields and methods named from compiler-written evidence ([NameOracle]). */
    val evidenceClasses: Int = 0,
    val evidenceFields: Int = 0,
    val evidenceMethods: Int = 0,
    /** Library matches dropped: compiler evidence names the class differently, R8 kept its name, or the target is a synthetic lambda. */
    val libraryMatchesVetoed: Int = 0,
) {
    val isEmpty: Boolean get() = classes.isEmpty() && fields.isEmpty() && methods.isEmpty()

    /** Recovered descriptor -> original descriptor. */
    fun classesBack(): Map<String, String> = classes.entries.associate { (o, n) -> n to o }

    /** `Lrecovered;->name:` -> `Lrecovered;->original:` for renamed fields. */
    fun fieldsBack(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((owner, m) in fields) {
            val named = classes[owner] ?: owner
            for ((o, n) in m) out["$named->$n:"] = "$named->$o:"
        }
        return out
    }

    /** Full recovered method reference -> original name, still in recovered class descriptors. */
    fun methodsBack(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((owner, m) in methods) {
            val named = classes[owner] ?: owner
            for ((o, n) in m) {
                val proto = DESCRIPTOR.replace(o.substring(o.indexOf('('))) { classes[it.value] ?: it.value }
                out["$named->$n$proto"] = "$named->${o.substringBefore('(')}$proto"
            }
        }
        return out
    }

    fun recovered(owner: String): String = classes[owner] ?: owner

    fun tiny(): String = buildString {
        append("tiny\t2\t0\tofficial\tnamed\n")
        for (owner in (classes.keys + fields.keys + methods.keys).toSortedSet()) {
            val o = owner.removePrefix("L").removeSuffix(";")
            val n = (classes[owner] ?: owner).removePrefix("L").removeSuffix(";")
            append("c\t").append(o).append('\t').append(n).append('\n')
        }
    }

    companion object {
        val EMPTY = NameMapping(emptyMap(), emptyMap())
        private val DESCRIPTOR = Regex("""L[^;\s(),<>\[]+;""")

        fun compute(dex: List<Pair<String, DexFile>>, library: LibraryMatcher.Result? = null, evidence: NameOracle.Result = NameOracle.Result.EMPTY): NameMapping {
            fun simpleName(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringAfterLast('$')
            // Compiler-written evidence names a class exactly: a library match that gives it another
            // simple name is wrong, and goes (with its member names).
            val matched = library?.classes?.associate { it.obfuscated to it.original }.orEmpty()
            // Also dropped: a match that gives a class whose name R8 kept (the original name) another name,
            // and one to a D8 synthetic lambda, numbered per build ($$ExternalSyntheticLambda0 in one, 2 in
            // another). Measured on Tusky 32.2 against its R8 mapping, these were 30 of 127 wrong library names.
            val vetoed = matched.filter { (o, n) ->
                evidence.classes[o]?.let { it.name != simpleName(n) } == true ||
                    (!NameOracle.minified(simpleName(o)) && simpleName(o) != simpleName(n)) || "\$\$ExternalSynthetic" in n
            }.keys
            // Library matches first: those classes take their original names (and members theirs).
            val libraryClasses = matched - vetoed
            val allOriginal = dex.flatMap { (_, d) -> d.classDefs.map { d.type(it.classIdx) } }
            val existing = allOriginal.toHashSet()
            val renamedByLibrary = libraryClasses.filter { (o, n) -> n !in existing || n == o }
            // Then compiler evidence: the class keeps its package and takes the recovered simple name.
            // That name must be unique among every class the code refers to (JADX 1.5.6 confuses a
            // renamed same-package class with an imported one of the same name), else the package
            // is appended: Notification next to android.app.Notification becomes Notification_ha.
            val referencedSimpleNames = dex.flatMap { (_, d) -> d.typeIds.indices.map { d.type(it) } }
                .map { it.trimStart('[') }.filter { it.startsWith('L') }.map(::simpleName)
            val takenSimple = HashSet(allOriginal.map { simpleName(renamedByLibrary[it] ?: it) } + referencedSimpleNames)
            val renamedByEvidence = HashMap<String, String>()
            for (original in allOriginal) {
                if (renamedByLibrary[original]?.let { it != original } == true) continue
                val name = evidence.classes[original]?.name ?: continue
                val path = original.removePrefix("L").removeSuffix(";")
                val pkg = path.substringBeforeLast('/', "")
                var n = name
                if (n in takenSimple) n = name + "_" + pkg.replace('/', '_')
                while (n in takenSimple) n += "_"
                takenSimple += n
                val segments = path.substringAfterLast('/').split('$')
                renamedByEvidence[original] = "L" + (if (pkg.isEmpty()) "" else "$pkg/") + (segments.dropLast(1) + n).joinToString("$") + ";"
            }
            val classNames = allOriginal.map { (renamedByLibrary[it] ?: renamedByEvidence[it] ?: it).removePrefix("L").removeSuffix(";") }
            val roots = classNames.filter { '/' in it }.map { it.substringBefore('/') }.toHashSet()
            val simple = classNames.flatMap { it.substringAfterLast('/').split('$') }.toHashSet()
            val taken = HashSet(roots + simple)
            // A type named like a root package obscures the package in qualified names (JLS 6.4.2):
            // with class b0.k1 in scope, `k1.s` means a member of that class. The type is renamed,
            // and to a name no other class has (b0.k1 -> k1_b0): JADX 1.5.6 checks same-package
            // collisions against original names, so a package moved by a mapping (k1 -> k1_), or
            // two classes given one new simple name, make it import q.N over a same-package N.
            val obscuring = roots.filter { it in simple }.toHashSet()
            val typeRenames = HashMap<String, String>() // owner path + '/' + segment -> new segment
            fun withoutObscuringTypes(c: String): String {
                val pkg = c.substringBeforeLast('/', "")
                val segments = c.substringAfterLast('/').split('$')
                val name = segments.mapIndexed { i, segment ->
                    if (segment !in obscuring) return@mapIndexed segment
                    val owner = (listOf(pkg) + segments.take(i)).filter { it.isNotEmpty() }.joinToString("/")
                    typeRenames.getOrPut("$owner/$segment") {
                        var n = segment + "_" + owner.replace('/', '_')
                        while (n in taken) n += "_"
                        taken += n
                        n
                    }
                }.joinToString("$")
                return if (pkg.isEmpty()) name else "$pkg/$name"
            }
            val classes = HashMap<String, String>()
            for ((original, c) in allOriginal.zip(classNames)) {
                val final = "L${withoutObscuringTypes(c)};"
                if (final != original) classes[original] = final
            }
            // Imports can bring a type from any package into scope. Fields also hide imported
            // types (not just siblings), e.g. an integer R hides an imported resource class R.
            // Include external DEX references, such as framework types, in this collision set.
            val referencedClasses = dex.flatMap { (_, d) -> d.typeIds.indices.map { d.type(it) } }
                .map { it.trimStart('[') }.filter { it.startsWith('L') }
                .map { it.removePrefix("L").removeSuffix(";") }
            val referencedSimple = referencedClasses.flatMap { it.substringAfterLast('/').split('$') }
            val referencedRoots = referencedClasses.filter { '/' in it }.map { it.substringBefore('/') }
            val obscured = roots + typeRenames.values + simple + referencedSimple + referencedRoots
            val fields = HashMap<String, HashMap<String, String>>()
            for ((_, d) in dex) for (c in d.classDefs) {
                val owner = d.type(c.classIdx)
                val data = c.classData ?: continue
                val own = (data.staticFields + data.instanceFields).map { d.string(d.fieldIds[it.fieldIdx].nameIdx) }.toHashSet()
                val libraryFields = (if (owner in vetoed) emptyMap() else library?.fields?.get(owner).orEmpty()) +
                    evidence.fields[owner].orEmpty().mapValues { it.value.name }
                for (f in data.staticFields + data.instanceFields) {
                    val name = d.string(d.fieldIds[f.fieldIdx].nameIdx)
                    val libraryName = libraryFields[name]?.takeIf { it !in own }
                    if (libraryName != null) {
                        var n = libraryName
                        if (n in obscured) n += "_f"
                        while (n in own || n in obscured) n += "_"
                        own += n
                        fields.getOrPut(owner) { HashMap() }[name] = n
                        continue
                    }
                    if (name !in obscured) continue
                    var n = name + "_f"
                    while (n in own || n in obscured) n += "_"
                    own += n
                    fields.getOrPut(owner) { HashMap() }[name] = n
                }
            }
            // A kept class name does not imply kept member names. Preserve its mappings too, and
            // carry virtual names into unmatched app subclasses before handing them to JADX.
            val librarySeeds = library?.methods?.filterKeys { it !in vetoed && (it !in libraryClasses || it in renamedByLibrary) }.orEmpty()
            val evidenceSeeds = evidence.methods.mapValues { (_, m) -> m.mapValues { it.value.name } }
            val seeds = (librarySeeds.keys + evidenceSeeds.keys).associateWith { librarySeeds[it].orEmpty() + evidenceSeeds[it].orEmpty() }
            val methods = VirtualMethodNames.expand(dex.map { it.second }, seeds)
            val evidenceFields = evidence.fields.entries.sumOf { (owner, m) -> m.count { (f, n) -> fields[owner]?.get(f) == n.name } }
            val evidenceMethods = evidence.methods.entries.sumOf { (owner, m) -> m.count { (sig, n) -> methods[owner]?.get(sig) == n.name } }
            return NameMapping(
                classes, fields, methods, renamedByLibrary.count { (o, n) -> o != n },
                evidenceClasses = renamedByEvidence.size, evidenceFields = evidenceFields, evidenceMethods = evidenceMethods, libraryMatchesVetoed = vetoed.size,
            )
        }
    }
}

/** Writes [mapping] as Tiny v2 with field lines (JADX reads it through `--mappings-path`). */
fun NameMapping.writeTiny(file: Path, dex: List<Pair<String, DexFile>>) {
    val sb = StringBuilder("tiny\t2\t0\tofficial\tnamed\n")
    val fieldTypes = HashMap<String, Map<String, String>>()
    for ((_, d) in dex) for (c in d.classDefs) {
        val owner = d.type(c.classIdx)
        if (owner !in fields) continue
        val data = c.classData ?: continue
        fieldTypes[owner] = (data.staticFields + data.instanceFields).associate { f -> d.fieldIds[f.fieldIdx].let { d.string(it.nameIdx) to d.type(it.typeIdx) } }
    }
    for (owner in (classes.keys + fields.keys + methods.keys).toSortedSet()) {
        val o = owner.removePrefix("L").removeSuffix(";")
        val n = (classes[owner] ?: owner).removePrefix("L").removeSuffix(";")
        sb.append("c\t").append(o).append('\t').append(n).append('\n')
        for ((field, renamed) in fields[owner].orEmpty().toSortedMap()) {
            val type = fieldTypes[owner]?.get(field) ?: continue
            sb.append("\tf\t").append(type).append('\t').append(field).append('\t').append(renamed).append('\n')
        }
        for ((method, renamed) in methods[owner].orEmpty().toSortedMap()) {
            val name = method.substringBefore('(')
            sb.append("\tm\t").append(method.substring(name.length)).append('\t').append(name).append('\t').append(renamed).append('\n')
        }
    }
    Files.writeString(file, sb)
}
