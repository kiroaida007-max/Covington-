package dev.apkproject.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Names JADX changed so the source is valid Java (R8 gives several members of one class the same
 * name, or names that collide with packages). JADX marks each with
 * `/* JADX INFO: renamed from: a, reason: ... */`; this map turns recompiled references back into
 * the original ones so that the round trip compares like with like.
 */
class JadxRenames private constructor(
    /** class descriptor -> (new member name -> original member name), fields and methods separately. */
    val fields: Map<String, Map<String, String>>,
    val methods: Map<String, Map<String, String>>,
) {
    val size: Int get() = fields.values.sumOf { it.size } + methods.values.sumOf { it.size }

    /** The recompiled name of an original method, when exactly one member was renamed from it. */
    fun recompiledMethodName(owner: String, original: String): String? =
        methods[owner]?.entries?.filter { it.value == original }?.singleOrNull()?.key

    /** `Lowner;->newName` -> `Lowner;->originalName`, for rewriting member references. */
    fun memberMap(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((owner, m) in fields) for ((n, o) in m) out["$owner->$n:"] = "$owner->$o:"
        for ((owner, m) in methods) for ((n, o) in m) out["$owner->$n("] = "$owner->$o("
        return out
    }

    companion object {
        private val COMMENT = Regex("""/\* JADX INFO: renamed from: ([^,*]+?)(?:, reason: [^*]*)?\s*\*/""")
        private val CLASS_DECL = Regex("""\b(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)""")

        fun parse(javaRoot: Path): JadxRenames {
            val fields = HashMap<String, HashMap<String, String>>()
            val methods = HashMap<String, HashMap<String, String>>()
            if (!Files.isDirectory(javaRoot)) return JadxRenames(fields, methods)
            val files = Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.toList() }
            for (f in files) {
                val text = Files.readString(f)
                if (!text.contains("renamed from")) continue
                val pkg = Regex("""(?m)^package\s+([\w.]+)\s*;""").find(text)?.groupValues?.get(1)?.replace('.', '/')?.let { "$it/" } ?: ""
                val classes = classRanges(text, pkg)
                for (m in COMMENT.findAll(text)) {
                    val original = m.groupValues[1].trim().substringAfterLast('.')
                    val after = m.range.last + 1
                    val declEnd = (after until text.length).firstOrNull { text[it] == ';' || text[it] == '{' || text[it] == '=' } ?: continue
                    val decl = text.substring(after, declEnd).replace(Regex("""@[\w.]+(?:\([^)]*\))?"""), " ").trim()
                    if (CLASS_DECL.containsMatchIn(decl)) continue // class renames change file names; not mapped here
                    val owner = classes.filter { (start, end, _) -> after in start until end }.maxByOrNull { it.first }?.third ?: continue
                    val paren = decl.indexOf('(')
                    if (paren >= 0) {
                        val name = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(decl.substring(0, paren))?.groupValues?.get(1) ?: continue
                        methods.getOrPut(owner) { HashMap() }[name] = original
                    } else {
                        val name = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(decl)?.groupValues?.get(1) ?: continue
                        fields.getOrPut(owner) { HashMap() }[name] = original
                    }
                }
            }
            return JadxRenames(fields, methods)
        }

        /** (body start, body end, descriptor) of every named class declared in [text], nested ones as `Outer$Inner`. */
        private fun classRanges(text: String, pkg: String): List<Triple<Int, Int, String>> {
            val code = JavaMethodLocator.codeMask(text)
            val out = ArrayList<Triple<Int, Int, String>>()
            val stack = ArrayList<Pair<Int, String>>() // (depth at which the class body opened, descriptor)
            var depth = 0
            var pending: String? = null
            var i = 0
            while (i < text.length) {
                if (!code[i]) { i++; continue }
                val c = text[i]
                if (c.isLetter() && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                    val m = CLASS_DECL.matchAt(text, i)
                    if (m != null) {
                        val outer = stack.lastOrNull()?.second
                        pending = if (outer == null) "$pkg${m.groupValues[1]}" else "${outer.removeSuffix(";")}$${m.groupValues[1]}"
                        i = m.range.last + 1
                        continue
                    }
                }
                when (c) {
                    '{' -> {
                        depth++
                        pending?.let { stack += depth to it; out += Triple(i, -1, "L$it;") }
                        pending = null
                    }
                    '}' -> {
                        if (stack.isNotEmpty() && stack.last().first == depth) {
                            val (_, name) = stack.removeAt(stack.size - 1)
                            val idx = out.indexOfLast { it.third == "L$name;" && it.second == -1 }
                            if (idx >= 0) out[idx] = out[idx].copy(second = i)
                        }
                        depth--
                    }
                    ';' -> if (pending != null && depth == stack.lastOrNull()?.first ?: 0) pending = null
                }
                i++
            }
            return out.map { if (it.second == -1) it.copy(second = text.length) else it }
        }
    }
}
