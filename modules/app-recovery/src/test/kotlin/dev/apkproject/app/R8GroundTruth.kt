package dev.apkproject.app

/** Ground truth belongs in the evaluator; production matching never reads an R8 map. */
internal class R8GroundTruth(lines: List<String>) {
    private val header = Regex("""^(\S+) -> (\S+):$""")
    private val method = Regex("""^\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$""")
    val origins: Map<String, Set<String>>
    val classes: Map<String, String>
    // Full APK descriptors distinguish overloads. Multiple local inline frames with the same
    // descriptor are deliberately unevaluable instead of guessing which source method survived.
    val methods: Map<String, Map<String, Set<String>>>

    init {
        val collected = linkedMapOf<String, MutableSet<String>>()
        val renamed = linkedMapOf<String, String>()
        for (line in lines) {
            val h = header.matchEntire(line) ?: continue
            val original = descriptor(h.groupValues[1])
            val obfuscated = descriptor(h.groupValues[2])
            renamed[original] = obfuscated
            collected.getOrPut(obfuscated) { linkedSetOf() } += original
        }
        origins = collected
        classes = collected.mapNotNull { (obf, names) -> names.singleOrNull()?.let { obf to it } }.toMap()
        val members = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        var owner = ""
        var obfuscatedOwner = ""
        fun renamedType(type: String): String {
            val desc = descriptor(type)
            val dimensions = desc.takeWhile { it == '[' }
            val base = desc.removePrefix(dimensions)
            return dimensions + (renamed[base] ?: base)
        }
        for (line in lines) {
            val h = header.matchEntire(line)
            if (h != null) {
                owner = h.groupValues[1]
                obfuscatedOwner = descriptor(h.groupValues[2])
                continue
            }
            val m = method.matchEntire(line) ?: continue
            val (returnType, qualifiedName, parameters, obfuscatedName) = m.destructured
            val methodOwner = qualifiedName.substringBeforeLast('.', owner)
            if (methodOwner != owner) continue // a foreign inline frame is not this declaration
            val name = qualifiedName.substringAfterLast('.')
            if (name.startsWith('<')) continue
            val args = if (parameters.isEmpty()) emptyList() else parameters.split(',')
            val prototype = args.joinToString("", "(", ")") { renamedType(it) } + renamedType(returnType)
            members.getOrPut(obfuscatedOwner) { linkedMapOf() }
                .getOrPut(obfuscatedName + prototype) { linkedSetOf() } += name
        }
        methods = members
    }

    private fun descriptor(type: String): String = when {
        type.endsWith("[]") -> "[" + descriptor(type.removeSuffix("[]"))
        else -> mapOf("void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C",
            "short" to "S", "int" to "I", "long" to "J", "float" to "F", "double" to "D")[type]
            ?: "L${type.replace('.', '/')};"
    }
}
