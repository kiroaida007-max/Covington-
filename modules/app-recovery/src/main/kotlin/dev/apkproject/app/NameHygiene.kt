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

    /** `Lrecovered;->name(` -> `Lrecovered;->original(` for renamed methods. */
    fun methodsBack(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((owner, m) in methods) {
            val named = classes[owner] ?: owner
            for ((o, n) in m) out["$named->$n("] = "$named->${o.substringBefore('(')}("
        }
        return out
    }

    fun recovered(owner: String): String = classes[owner] ?: owner

    fun tiny(): String = buildString {
        append("tiny\t2\t0\tofficial\tnamed\n")
        for (owner in (classes.keys + fields.keys).toSortedSet()) {
            val o = owner.removePrefix("L").removeSuffix(";")
            val n = (classes[owner] ?: owner).removePrefix("L").removeSuffix(";")
            append("c\t").append(o).append('\t').append(n).append('\n')
        }
    }

    companion object {
        val EMPTY = NameMapping(emptyMap(), emptyMap())

        fun compute(dex: List<Pair<String, DexFile>>, library: LibraryMatcher.Result? = null): NameMapping {
            // Library matches first: those classes take their original names (and members theirs).
            val libraryClasses = library?.classes?.associate { it.obfuscated to it.original }.orEmpty()
            val allOriginal = dex.flatMap { (_, d) -> d.classDefs.map { d.type(it.classIdx) } }
            val existing = allOriginal.toHashSet()
            val renamedByLibrary = libraryClasses.filter { (o, n) -> n !in existing || n == o }
            val classNames = allOriginal.map { (renamedByLibrary[it] ?: it).removePrefix("L").removeSuffix(";") }
            val roots = classNames.filter { '/' in it }.map { it.substringBefore('/') }.toHashSet()
            val simple = classNames.flatMap { it.substringAfterLast('/').split('$') }.toHashSet()
            val taken = HashSet(roots + simple)
            val packageRenames = HashMap<String, String>()
            for (root in roots.filter { it in simple }.sorted()) {
                var n = root + "_"
                while (n in taken) n += "_"
                taken += n
                packageRenames[root] = n
            }
            val classes = HashMap<String, String>()
            for ((original, c) in allOriginal.zip(classNames)) {
                val root = c.substringBefore('/', "")
                val final = packageRenames[root]?.let { "L${it}${c.substring(root.length)};" } ?: "L$c;"
                if (final != original) classes[original] = final
            }
            // Fields named like a class of their own package, or like a root package, obscure it.
            val byPackage = classNames.groupBy({ it.substringBeforeLast('/', "") }, { it.substringAfterLast('/').substringBefore('$') }).mapValues { it.value.toHashSet() }
            val fields = HashMap<String, HashMap<String, String>>()
            for ((_, d) in dex) for (c in d.classDefs) {
                val owner = d.type(c.classIdx)
                val pkg = (renamedByLibrary[owner] ?: owner).removePrefix("L").removeSuffix(";").substringBeforeLast('/', "")
                val siblings = byPackage[pkg].orEmpty()
                val data = c.classData ?: continue
                val own = (data.staticFields + data.instanceFields).map { d.string(d.fieldIds[it.fieldIdx].nameIdx) }.toHashSet()
                val libraryFields = library?.fields?.get(owner).orEmpty()
                for (f in data.staticFields + data.instanceFields) {
                    val name = d.string(d.fieldIds[f.fieldIdx].nameIdx)
                    val libraryName = libraryFields[name]?.takeIf { it !in own }
                    if (libraryName != null) {
                        fields.getOrPut(owner) { HashMap() }[name] = libraryName
                        continue
                    }
                    if (name !in siblings && name !in roots) continue
                    var n = name + "_f"
                    while (n in own) n += "_"
                    fields.getOrPut(owner) { HashMap() }[name] = n
                }
            }
            val methods = library?.methods?.filterKeys { it in renamedByLibrary }.orEmpty()
            return NameMapping(classes, fields, methods, renamedByLibrary.count { (o, n) -> o != n })
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
