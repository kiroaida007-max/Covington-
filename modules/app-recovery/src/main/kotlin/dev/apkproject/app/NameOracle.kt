package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DecodedCode
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.lift.DataflowShape
import kotlinx.serialization.Serializable

/**
 * Original names that compilers and code generators write into the bytecode as data. R8 renames
 * classes, fields and methods, but it cannot change a string the program prints or looks up
 * without changing what the program does, and those strings name the declarations:
 *
 * - Kotlin data classes: `toString()` prints `User(id=…, name=…)`, the class's simple name and
 *   each property, in the order the fields are read.
 * - Moshi codegen: an adapter's `toString()` is `GeneratedJsonAdapter(User)`; Moshi names the
 *   adapter `UserJsonAdapter`, and it casts to and instantiates the model.
 * - ViewBinding: a binding is generated from one layout (`activity_main` → `ActivityMainBinding`),
 *   and each field from a view id (`send_button` → `sendButton`); `bind()` reports missing views.
 * - Room: an insert adapter's SQL names the entity's table and columns, and `bind()` reads the
 *   entity's fields in column order (names unless `tableName`/`@ColumnInfo` override them).
 * - Enums: each constant is constructed with its own name, `valueOf` depends on it.
 *
 * Each rule reads the bytecode only, records its evidence, and abstains on anything ambiguous
 * (two rules disagreeing, a merged class printing two names). Renaming changes no code: the
 * round trip maps every name back before comparing.
 */
object NameOracle {
    /** A recovered name for one declaration, with the rule and the evidence it came from. */
    @Serializable
    data class Name(val declaration: String, val name: String, val rule: String, val evidence: String)

    class Result(
        /** Class descriptor -> recovered simple name. */
        val classes: Map<String, Name>,
        /** Class descriptor -> (field name -> recovered name). */
        val fields: Map<String, Map<String, Name>>,
        /** Class descriptor -> (method name + descriptor -> recovered name). */
        val methods: Map<String, Map<String, Name>>,
        /** Declarations two rules named differently; none of their names is used. */
        val conflicts: List<String>,
    ) {
        val all: List<Name> get() = classes.values + fields.values.flatMap { it.values } + methods.values.flatMap { it.values }

        companion object {
            val EMPTY = Result(emptyMap(), emptyMap(), emptyMap(), emptyList())
        }
    }

    const val DATA_CLASS = "kotlin-data-class-toString"
    const val PRINTED = "toString-property-labels"
    const val MOSHI = "moshi-generated-adapter"
    const val VIEW_BINDING = "viewbinding-layout"
    const val ROOM = "room-insert-adapter"
    const val ENUM = "enum-constant-name"
    const val ACCESSOR = "accessor-of-named-field"

    private val IDENTIFIER = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
    private val DATA_FIRST = Regex("""^([A-Z][A-Za-z0-9_]*)\(([A-Za-z_][A-Za-z0-9_]*)=$""")
    private val DATA_NEXT = Regex("""^, ([A-Za-z_][A-Za-z0-9_]*)=$""")
    private val MOSHI_TO_STRING = Regex("""^GeneratedJsonAdapter\(([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\)$""")
    private val ROOM_INSERT = Regex("""^INSERT (?:OR [A-Z]+ )?INTO `([A-Za-z_][A-Za-z0-9_]*)` \(([^)]*)\) VALUES""")
    private const val MISSING_VIEW = "Missing required view with ID: "

    fun recover(dex: List<DexFile>, resources: ResourceTable = ResourceTable.EMPTY): Result {
        val collector = Collector()
        val defined = dex.flatMap { d -> d.classDefs.map { d.type(it.classIdx) } }.toHashSet()
        val methodsByOwner = dex.flatMap { it.methods() }.groupBy { it.owner }
        val superOf = dex.flatMap { d -> d.classDefs.map { c -> d.type(c.classIdx) to (c.superclassIdx.takeIf { it in d.typeIds.indices }?.let { d.type(it) } ?: "") } }.toMap()
        // Class -> (superclass, number of interfaces, number of instance fields).
        val shapes = dex.flatMap { d -> d.classDefs.map { c -> d.type(c.classIdx) to ((superOf[d.type(c.classIdx)] ?: "") to c.interfaces.size) } }.toMap()
        val decoded = HashMap<DexMethod, DecodedCode?>()
        val dataClasses = HashSet<String>()
        fun decode(m: DexMethod): DecodedCode? = decoded.getOrPut(m) { m.code?.let { (InstructionDecoder.decode(m.dex, it) as? Outcome.Ok)?.value } }

        for ((owner, methods) in methodsByOwner) {
            val toString = methods.firstOrNull { !it.isStatic && it.name == "toString" && it.protoDescriptor == "()Ljava/lang/String;" }
            if (toString != null) {
                val code = decode(toString)
                if (code != null) {
                    printedProperties(toString, code)?.let { printed ->
                        val fields = printed.properties.mapNotNull { it.second }
                        val text = "\"${printed.name}(${printed.properties.joinToString(", ") { it.first + "=" }})\""
                        // The class name only for a generated data class; the labels name the fields printed after them either way.
                        val allFields = fields.size == printed.properties.size
                        val generated = allFields && generatedDataClass(methods, fields, ::decode) == true
                        if (generated) {
                            collector.cls(owner, printed.name, DATA_CLASS, "toString prints $text; hashCode/equals cover the same fields")
                            dataClasses += owner
                        }
                        for ((property, field) in printed.properties) {
                            if (field != null) collector.field(owner, field, property, if (generated) DATA_CLASS else PRINTED, "toString prints \"$property=\" and then field $field")
                        }
                    }
                    moshi(toString, code, methods, defined, ::decode)?.let { (model, qualified) ->
                        val adapter = qualified.replace('.', '_') + "JsonAdapter"
                        collector.cls(owner, adapter, MOSHI, "toString returns \"GeneratedJsonAdapter($qualified)\"")
                        collector.cls(model, qualified.substringAfterLast('.'), MOSHI, "adapter ${adapter} reads and writes $model")
                    }
                }
            }
            if (superOf[owner] == "Ljava/lang/Enum;") methods.firstOrNull { it.name == "<clinit>" }?.let { clinit ->
                decode(clinit)?.let { enumConstants(clinit, it).forEach { (field, name) -> collector.field(owner, field, name, ENUM, "constructed as $name in <clinit>") } }
            }
            room(methods, defined, ::decode, collector)
        }
        if (resources.size > 0) {
            val sites = HashMap<String, MutableList<BindingSite>>()
            for ((_, methods) in methodsByOwner) for (m in methods) {
                val code = decode(m) ?: continue
                if (code.insns.none { it.op.mnemonic.startsWith("const-string") && m.dex.string(it.index) == MISSING_VIEW }) continue
                bindingSites(m, code, resources, defined).forEach { sites.getOrPut(it.binding) { ArrayList() } += it }
            }
            viewBinding(sites, methodsByOwner, shapes, ::decode, collector)
        }
        accessors(methodsByOwner.filterKeys { it in dataClasses }, ::decode, collector)
        return collector.result()
    }

    /** What a `Name(a=…, b=…)` toString prints: the class name, and each property's field (null when not a field). */
    internal class Printed(val name: String, val properties: List<Pair<String, String?>>)

    /**
     * `Name(a=` + this.f + `, b=` + this.g + `)`: each label is followed by the value appended for
     * it, which is either a field of this class (directly, or through String.valueOf/Arrays.toString)
     * or something else, a computed property, which names no field.
     */
    internal fun printedProperties(m: DexMethod, code: DecodedCode): Printed? {
        val size = code.code.registersSize
        val self = size - code.code.insSize
        val strings = HashMap<Int, String>()
        val fieldOf = HashMap<Int, String>()
        var name: String? = null
        var heads = 0
        var pending: String? = null
        var resultField: String? = null
        val properties = ArrayList<Pair<String, String?>>()
        fun clear(i: dev.apkproject.dex.Insn) { for (w in DataflowShape.writes(i, size)) { strings.remove(w); fieldOf.remove(w) } }
        for (i in code.insns) {
            val mnemonic = i.op.mnemonic
            when {
                mnemonic.startsWith("const-string") -> { clear(i); strings[i.regs[0]] = m.dex.string(i.index); resultField = null }
                mnemonic.startsWith("iget") && i.regs[1] == self -> {
                    clear(i)
                    val f = m.dex.fieldIds[i.index]
                    if (m.dex.type(f.classIdx) == m.owner) fieldOf[i.regs[0]] = m.dex.string(f.nameIdx)
                    resultField = null
                }
                mnemonic.startsWith("invoke") && i.op.index == IndexKind.METHOD -> {
                    val target = m.dex.methodIds[i.index]
                    val targetName = m.dex.string(target.nameIdx)
                    val args = i.regs.asList()
                    var label = false
                    for (a in args) {
                        val text = strings[a] ?: continue
                        val head = DATA_FIRST.matchEntire(text)
                        if (head != null) { heads++; name = head.groupValues[1]; pending = head.groupValues[2]; label = true }
                        else if (name != null) DATA_NEXT.matchEntire(text)?.let { pending = it.groupValues[1]; label = true }
                    }
                    if (!label && targetName == "append" && m.dex.type(target.classIdx) == "Ljava/lang/StringBuilder;") {
                        val value = args.getOrNull(1)
                        if (value != null && pending != null && strings[value] == null) {
                            properties += pending!! to fieldOf[value]
                            pending = null
                        }
                    }
                    // A field converted for printing is still that field: String.valueOf(f), Arrays.toString(f).
                    val conversion = mnemonic.startsWith("invoke-static") && (targetName == "valueOf" || targetName == "toString" || targetName.startsWith("toString-"))
                    resultField = if (conversion) args.mapNotNull { fieldOf[it] }.distinct().singleOrNull() else null
                }
                mnemonic.startsWith("move-result") -> { clear(i); resultField?.let { fieldOf[i.regs[0]] = it }; resultField = null }
                else -> { clear(i); resultField = null }
            }
        }
        // A merged class prints several names; one field printed for two properties is not a property.
        if (heads != 1 || name == null || properties.isEmpty()) return null
        val fields = properties.mapNotNull { it.second }
        if (fields.toSet().size != fields.size || properties.map { it.first }.toSet().size != properties.size) return null
        return Printed(name!!, properties)
    }

    /**
     * Whether the class has the methods Kotlin generates for a data class over exactly these fields:
     * `hashCode` folding each field with `* 31`, or `equals` comparing each field. A hand-written
     * toString in the same format (PaddingValuesImpl printing "PaddingValues(") has neither.
     */
    private fun generatedDataClass(methods: List<DexMethod>, fields: List<String>, decode: (DexMethod) -> DecodedCode?): Boolean? {
        fun reads(m: DexMethod?, self: Boolean): Pair<Set<String>, Int>? {
            val code = m?.let(decode) ?: return null
            val thisRegister = code.code.registersSize - code.code.insSize
            val read = code.insns.filter { it.op.mnemonic.startsWith("iget") && (!self || it.regs[1] == thisRegister) && m.dex.type(m.dex.fieldIds[it.index].classIdx) == m.owner }
                .map { m.dex.string(m.dex.fieldIds[it.index].nameIdx) }.toSet()
            val multiplies = code.insns.count { it.op.mnemonic.startsWith("mul-int") && it.literal == 31L }
            return read to multiplies
        }
        val hash = reads(methods.firstOrNull { !it.isStatic && it.name == "hashCode" && it.protoDescriptor == "()I" }, true)
        if (hash != null && hash.first == fields.toSet() && hash.second >= fields.size - 1) return true
        val equals = reads(methods.firstOrNull { !it.isStatic && it.name == "equals" && it.protoDescriptor == "(Ljava/lang/Object;)Z" }, false)
        if (equals != null && equals.first == fields.toSet()) return true
        // Null: neither method exists, so nothing contradicts a generated data class.
        return if (hash == null && equals == null) null else false
    }

    /** `GeneratedJsonAdapter(Model)`: the model is the app type the adapter casts to and instantiates. */
    private fun moshi(m: DexMethod, code: DecodedCode, methods: List<DexMethod>, defined: Set<String>, decode: (DexMethod) -> DecodedCode?): Pair<String, String>? {
        val text = code.insns.filter { it.op.mnemonic.startsWith("const-string") }.joinToString("") { m.dex.string(it.index) }
        val qualified = MOSHI_TO_STRING.matchEntire(text)?.groupValues?.get(1) ?: return null
        // The model is what fromJson constructs (or looks up a constructor of, for default values) and
        // toJson takes; a field's type is only cast to (a State enum read five times outnumbers it).
        val constructed = HashSet<String>()
        val received = HashSet<String>()
        for (other in methods) {
            Signatures.parameters(other.protoDescriptor)?.forEach { t -> if (t in defined && t != m.owner) received += t }
            val c = decode(other) ?: continue
            for (i in c.insns) {
                val t = when (i.op.mnemonic) { "check-cast", "new-instance", "const-class" -> other.dex.type(i.index); else -> continue }
                if (t !in defined || t == m.owner) continue
                if (i.op.mnemonic == "check-cast") received += t else constructed += t
            }
        }
        val model = (constructed intersect received).singleOrNull() ?: return null
        return model to qualified
    }

    /** `new E("NAME", ordinal)` stored in a static field of E: field -> NAME. */
    private fun enumConstants(m: DexMethod, code: DecodedCode): List<Pair<String, String>> {
        val strings = HashMap<Int, String>()
        val named = HashMap<Int, String>()
        val out = ArrayList<Pair<String, String>>()
        val size = code.code.registersSize
        for (i in code.insns) {
            val mnemonic = i.op.mnemonic
            when {
                mnemonic.startsWith("const-string") -> { strings[i.regs[0]] = m.dex.string(i.index); continue }
                mnemonic.startsWith("invoke-direct") && i.op.index == IndexKind.METHOD -> {
                    val target = m.dex.methodIds[i.index]
                    if (m.dex.type(target.classIdx) == m.owner && m.dex.string(target.nameIdx) == "<init>" && i.regs.size >= 2) {
                        strings[i.regs[1]]?.takeIf { IDENTIFIER.matches(it) }?.let { named[i.regs[0]] = it }
                    }
                    continue
                }
                mnemonic == "sput-object" -> {
                    val f = m.dex.fieldIds[i.index]
                    val name = named[i.regs[0]]
                    if (name != null && m.dex.type(f.classIdx) == m.owner && m.dex.type(f.typeIdx) == m.owner) {
                        out += m.dex.string(f.nameIdx) to name
                        // The constant's own field is the first store; later ones are aliases (DEFAULT = AUTO).
                        named.values.removeAll { it == name }
                    }
                    continue
                }
                mnemonic.startsWith("move-object") -> { named[i.regs[1]]?.let { named[i.regs[0]] = it }; continue }
            }
            for (r in DataflowShape.writes(i, size)) { strings.remove(r); named.remove(r) }
        }
        return out.takeIf { pairs -> pairs.map { it.second }.toSet().size == pairs.size } ?: emptyList()
    }

    /** One construction of a ViewBinding: the class, the layout inflated for it, and each constructor argument's view. */
    private class BindingSite(val binding: String, val layout: String?, val arguments: List<String?>)

    private const val ROOT = "rootView"

    /**
     * ViewBinding's bind code (generated in the binding class, but often inlined by R8 into its
     * callers, several per merged lambda): inflate a layout or take a view, find each child by id,
     * reporting "Missing required view with ID: " otherwise, and pass them to the binding's
     * constructor with the root first.
     */
    private fun bindingSites(m: DexMethod, code: DecodedCode, resources: ResourceTable, defined: Set<String>): List<BindingSite> {
        val size = code.code.registersSize
        val ids = HashMap<Int, Int>()
        val layouts = HashMap<Int, String>()
        val found = HashMap<Int, String>()
        var r = size - code.code.insSize + if (m.isStatic) 0 else 1
        for (t in Signatures.parameters(m.protoDescriptor).orEmpty()) {
            if (t == "Landroid/view/View;") found[r] = ROOT
            r += if (t == "J" || t == "D") 2 else 1
        }
        val sites = ArrayList<BindingSite>()
        var pending: String? = null
        var layout: String? = null
        fun clear(i: dev.apkproject.dex.Insn) { for (w in DataflowShape.writes(i, size)) { ids.remove(w); layouts.remove(w); found.remove(w) } }
        for (i in code.insns) {
            val mnemonic = i.op.mnemonic
            when {
                mnemonic.startsWith("const") && !mnemonic.startsWith("const-string") && !mnemonic.startsWith("const-class") && !mnemonic.startsWith("const-wide") -> {
                    clear(i)
                    val id = i.literal.toInt()
                    resources.name(id, "id")?.let { ids[i.regs[0]] = id }
                    resources.name(id, "layout")?.let { layouts[i.regs[0]] = it }
                    pending = null
                }
                mnemonic.startsWith("invoke") && i.op.index == IndexKind.METHOD -> {
                    val target = m.dex.methodIds[i.index]
                    val owner = m.dex.type(target.classIdx)
                    val args = i.regs.asList()
                    pending = null
                    if (m.dex.string(target.nameIdx) == "<init>" && owner in defined && args.size >= 2 && found[args[1]] == ROOT) {
                        sites += BindingSite(owner, layout, args.drop(1).map { found[it] })
                        continue
                    }
                    val inflated = args.mapNotNull { layouts[it] }.distinct()
                    val looked = args.mapNotNull { ids[it] }.distinct()
                    val wrapped = args.mapNotNull { found[it] }.filter { it != ROOT }.distinct()
                    pending = when {
                        inflated.size == 1 -> { layout = inflated.single(); ROOT }
                        looked.size == 1 && args.any { found[it] == ROOT } -> resources.name(looked.single(), "id")?.let(::camel)
                        // An included layout's binding is bound from the view found for its id.
                        looked.isEmpty() && wrapped.size == 1 && args.size == 1 -> wrapped.single()
                        else -> null
                    }
                }
                mnemonic == "move-result-object" -> { clear(i); pending?.let { found[i.regs[0]] = it }; pending = null }
                mnemonic == "check-cast" -> pending = null
                mnemonic.startsWith("move-object") -> { val from = found[i.regs[1]]; clear(i); from?.let { found[i.regs[0]] = it }; pending = null }
                else -> { clear(i); pending = null }
            }
        }
        return sites
    }

    /** Names a binding class from the layouts it is inflated from, and its fields from the views it receives. */
    private fun viewBinding(sites: Map<String, List<BindingSite>>, methodsByOwner: Map<String, List<DexMethod>>, shapes: Map<String, Pair<String, Int>>, decode: (DexMethod) -> DecodedCode?, collector: Collector) {
        for ((owner, uses) in sites) {
            // A binding implements ViewBinding.getRoot(); a ViewHolder built from the inflated view does not,
            // and a class R8 merged a binding into has other supertypes too.
            if (methodsByOwner[owner].orEmpty().none { !it.isStatic && it.name == "getRoot" && it.protoDescriptor.startsWith("()L") }) continue
            if (shapes[owner]?.let { it.first != "Ljava/lang/Object;" || it.second != 1 } != false) continue
            uses.mapNotNull { it.layout }.toSet().singleOrNull()?.let { layout ->
                collector.cls(owner, pascal(layout) + "Binding", VIEW_BINDING, "constructed with the views of layout/$layout")
            }
            val methods = methodsByOwner[owner].orEmpty()
            for (m in methods.filter { it.isStatic }) {
                val name = when (m.protoDescriptor) {
                    "(Landroid/view/View;)$owner" -> "bind"
                    "(Landroid/view/LayoutInflater;)$owner", "(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Z)$owner" -> "inflate"
                    else -> null
                } ?: continue
                collector.method(owner, m.name + m.protoDescriptor, name, VIEW_BINDING, "ViewBinding's $name signature")
            }
            // Every construction must agree on what each argument is.
            val arguments = uses.map { it.arguments }.distinct().singleOrNull() ?: continue
            val init = methods.firstOrNull { it.name == "<init>" && Signatures.parameters(it.protoDescriptor)?.size == arguments.size } ?: continue
            val initCode = decode(init) ?: continue
            val self = initCode.code.registersSize - initCode.code.insSize
            val registers = run {
                var r = self + 1
                Signatures.parameters(init.protoDescriptor).orEmpty().map { t -> r.also { r += if (t == "J" || t == "D") 2 else 1 } }
            }
            val named = HashSet<String>()
            for (i in initCode.insns) {
                if (!i.op.mnemonic.startsWith("iput") || i.regs[1] != self) continue
                val name = arguments.getOrNull(registers.indexOf(i.regs[0])) ?: continue
                val f = init.dex.fieldIds[i.index]
                if (init.dex.type(f.classIdx) != owner || !named.add(name)) continue
                collector.field(owner, init.dex.string(f.nameIdx), name, VIEW_BINDING, if (name == ROOT) "the bound root view" else "view found by id/${snake(name)}")
            }
        }
    }

    /** Room's insert adapter: `INSERT … INTO `Table` (`a`,`b`)` and bind(statement, entity) reads field k for column k. */
    private fun room(methods: List<DexMethod>, defined: Set<String>, decode: (DexMethod) -> DecodedCode?, collector: Collector) {
        val query = methods.firstNotNullOfOrNull { m ->
            if (m.protoDescriptor != "()Ljava/lang/String;") null
            else decode(m)?.insns?.firstNotNullOfOrNull { i -> if (i.op.mnemonic.startsWith("const-string")) ROOM_INSERT.find(m.dex.string(i.index)) else null }
        } ?: return
        val table = query.groupValues[1]
        val columns = query.groupValues[2].split(',').map { it.trim().trim('`') }
        if (columns.any { !IDENTIFIER.matches(it) }) return
        for (m in methods) {
            if (m.isStatic) continue
            val params = Signatures.parameters(m.protoDescriptor) ?: continue
            if (params.size != 2) continue
            val code = decode(m) ?: continue
            val size = code.code.registersSize
            // The entity parameter and its copies; R8 often merges the typed bind into the bridge, which
            // casts the Object to the entity.
            val entityRegisters = HashSet<Int>().apply { add(size - code.code.insSize + 2) }
            val entity = params[1].takeIf { it in defined }
                ?: code.insns.firstOrNull { it.op.mnemonic == "check-cast" && (it.regs[0] == size - code.code.insSize + 2 || code.insns.any { mv -> mv.op.mnemonic.startsWith("move-object") && mv.regs[0] == it.regs[0] && mv.regs[1] == size - code.code.insSize + 2 }) }
                    ?.let { m.dex.type(it.index) }?.takeIf { it in defined }
                ?: continue
            val literals = HashMap<Int, Int>()
            val fields = HashMap<Int, String>()
            var pending: String? = null
            val byColumn = HashMap<Int, String>()
            for (i in code.insns) {
                val mnemonic = i.op.mnemonic
                when {
                    mnemonic.startsWith("const") && !mnemonic.startsWith("const-string") && !mnemonic.startsWith("const-class") -> {
                        for (r in DataflowShape.writes(i, size)) { literals.remove(r); fields.remove(r) }
                        literals[i.regs[0]] = i.literal.toInt()
                        pending = null
                        continue
                    }
                    mnemonic.startsWith("move-object") && i.regs[1] in entityRegisters -> {
                        for (r in DataflowShape.writes(i, size)) { literals.remove(r); fields.remove(r) }
                        entityRegisters += i.regs[0]
                        pending = null
                        continue
                    }
                    mnemonic == "check-cast" -> { pending = null; continue }
                    mnemonic.startsWith("iget") && i.regs[1] in entityRegisters -> {
                        val f = m.dex.fieldIds[i.index]
                        for (r in DataflowShape.writes(i, size)) { literals.remove(r); fields.remove(r) }
                        if (m.dex.type(f.classIdx) == entity) fields[i.regs[0]] = m.dex.string(f.nameIdx)
                        pending = null
                        continue
                    }
                    mnemonic.startsWith("invoke") && i.op.index == IndexKind.METHOD -> {
                        val column = i.regs.getOrNull(1)?.let { literals[it] }
                        val field = i.regs.getOrNull(2)?.let { fields[it] }
                        if (i.regs.size >= 3 && column != null && field != null && column in 1..columns.size) byColumn.putIfAbsent(column, field)
                        // A converter's result still holds the field it converted (Converters.toJson(entity.tags)).
                        pending = i.regs.asList().mapNotNull { fields[it] }.distinct().singleOrNull()
                        continue
                    }
                    mnemonic.startsWith("move-result") -> {
                        for (r in DataflowShape.writes(i, size)) { literals.remove(r); fields.remove(r) }
                        pending?.let { fields[i.regs[0]] = it }
                        pending = null
                        continue
                    }
                }
                pending = null
                for (r in DataflowShape.writes(i, size)) { literals.remove(r); fields.remove(r); entityRegisters.remove(r) }
            }
            if (byColumn.isEmpty() || byColumn.values.toSet().size != byColumn.size) continue
            collector.cls(entity, table, ROOM, "insert adapter writes table `$table`")
            for ((column, field) in byColumn) collector.field(entity, field, columns[column - 1], ROOM, "bound as column `${columns[column - 1]}` of `$table`")
            return
        }
    }

    /** Getters and setters of named fields: `return this.f` is getF(), `this.f = p` is setF(p). */
    private fun accessors(methodsByOwner: Map<String, List<DexMethod>>, decode: (DexMethod) -> DecodedCode?, collector: Collector) {
        for ((owner, names) in collector.fieldNames()) {
            if (owner !in methodsByOwner) continue
            for (m in methodsByOwner[owner].orEmpty()) {
                if (m.isStatic || m.name.startsWith('<') || m.accessFlags and AccessFlags.SYNTHETIC != 0) continue
                val code = decode(m) ?: continue
                val insns = code.insns.filter { it.op.mnemonic != "nop" }
                if (insns.size != 2) continue
                val self = code.code.registersSize - code.code.insSize
                val (a, b) = insns
                val params = Signatures.parameters(m.protoDescriptor) ?: continue
                val field = when {
                    params.isEmpty() && a.op.mnemonic.startsWith("iget") && a.regs[1] == self && b.op.mnemonic.startsWith("return") && b.regs.firstOrNull() == a.regs[0] -> a
                    params.size == 1 && a.op.mnemonic.startsWith("iput") && a.regs[1] == self && a.regs[0] == self + 1 && b.op.mnemonic == "return-void" -> a
                    else -> null
                } ?: continue
                val f = m.dex.fieldIds[field.index]
                if (m.dex.type(f.classIdx) != owner) continue
                val property = names[m.dex.string(f.nameIdx)] ?: continue
                val getter = params.isEmpty()
                val boolean = m.dex.type(f.typeIdx) == "Z"
                val name = when {
                    getter && boolean && Regex("^is[A-Z]").containsMatchIn(property) -> property
                    getter -> "get" + property.replaceFirstChar { it.uppercase() }
                    boolean && Regex("^is[A-Z]").containsMatchIn(property) -> "set" + property.removePrefix("is")
                    else -> "set" + property.replaceFirstChar { it.uppercase() }
                }
                collector.method(owner, m.name + m.protoDescriptor, name, ACCESSOR, (if (getter) "returns" else "assigns") + " the field named $property")
            }
        }
    }

    /** R8's names: at most three letters or digits (a, k1, aB0), or its `$r8$` prefix. */
    internal fun minified(name: String): Boolean = name.startsWith("\$r8\$") || (name.length <= 3 && name.all { it.isLetterOrDigit() })

    internal fun pascal(snake: String): String = snake.split('_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

    /** ViewBinding's field naming: `send_button` -> `sendButton`. */
    internal fun camel(id: String): String = pascal(id).replaceFirstChar { it.lowercase() }.takeIf { IDENTIFIER.matches(it) } ?: id

    private fun snake(camel: String) = camel.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }

    /** Proposals from all rules; a declaration named differently by two rules keeps no name. */
    private class Collector {
        private val classes = LinkedHashMap<String, MutableList<Name>>()
        private val fields = LinkedHashMap<String, LinkedHashMap<String, MutableList<Name>>>()
        private val methods = LinkedHashMap<String, LinkedHashMap<String, MutableList<Name>>>()

        // A name R8 kept is the original: only minified names are replaced.
        fun cls(owner: String, name: String, rule: String, evidence: String) {
            val current = owner.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringAfterLast('$')
            if (IDENTIFIER.matches(name) && minified(current) && name != current) classes.getOrPut(owner) { ArrayList() } += Name(owner, name, rule, evidence)
        }

        fun field(owner: String, field: String, name: String, rule: String, evidence: String) {
            if (IDENTIFIER.matches(name) && minified(field) && name != field) fields.getOrPut(owner) { LinkedHashMap() }.getOrPut(field) { ArrayList() } += Name("$owner->$field", name, rule, evidence)
        }

        fun method(owner: String, signature: String, name: String, rule: String, evidence: String) {
            if (IDENTIFIER.matches(name) && minified(signature.substringBefore('(')) && name != signature.substringBefore('(')) methods.getOrPut(owner) { LinkedHashMap() }.getOrPut(signature) { ArrayList() } += Name("$owner->$signature", name, rule, evidence)
        }

        fun fieldNames(): Map<String, Map<String, String>> = fields.mapValues { (_, m) -> m.mapNotNull { (f, ns) -> agreed(ns)?.let { f to it.name } }.toMap() }

        private val conflicts = ArrayList<String>()

        private fun agreed(names: List<Name>): Name? = names.map { it.name }.distinct().singleOrNull()?.let { names.first() }

        private fun <K> resolve(entries: Map<K, MutableList<Name>>): Map<K, Name> = entries.mapNotNull { (k, ns) ->
            val one = agreed(ns)
            if (one == null) conflicts += ns.first().declaration + ": " + ns.joinToString(" vs ") { "${it.name} (${it.rule})" }
            one?.let { k to (if (ns.size > 1) it.copy(rule = ns.map { n -> n.rule }.distinct().joinToString("+")) else it) }
        }.toMap()

        fun result(): Result {
            val c = resolve(classes)
            // Within a class, two members may not take one name.
            fun unique(m: Map<String, Name>): Map<String, Name> {
                val counts = m.values.groupingBy { it.name }.eachCount()
                return m.filterValues { counts.getValue(it.name) == 1 }
            }
            val f = fields.mapValues { (_, m) -> unique(resolve(m)) }.filterValues { it.isNotEmpty() }
            // Methods may share a name only with different parameters (a getter and a componentN
            // with one body would both become getX: neither is kept).
            val mm = methods.mapValues { (_, m) ->
                val named = resolve(m)
                val counts = named.entries.groupingBy { (sig, n) -> n.name + sig.substring(sig.indexOf('('), sig.indexOf(')') + 1) }.eachCount()
                named.filter { (sig, n) -> counts.getValue(n.name + sig.substring(sig.indexOf('('), sig.indexOf(')') + 1)) == 1 }
            }.filterValues { it.isNotEmpty() }
            return Result(c, f, mm, conflicts.toList())
        }
    }
}
