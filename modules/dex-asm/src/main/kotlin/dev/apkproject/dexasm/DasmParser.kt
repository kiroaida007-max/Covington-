package dev.apkproject.dexasm

import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.Opcodes

class AsmException(val line: Int, message: String) : Exception("line $line: $message")

data class AsmFile(val version: Int, val classes: List<AsmClass>)

data class AsmClass(
    val line: Int,
    val flags: Int,
    val name: String,
    val superclass: String?,
    val interfaces: List<String>,
    val sourceFile: String?,
    val fields: List<AsmField>,
    val methods: List<AsmMethod>,
)

data class AsmField(val line: Int, val flags: Int, val name: String, val type: String)

data class AsmMethod(
    val line: Int,
    val flags: Int,
    val name: String,
    val proto: String,
    val registers: Int?,
    val ins: Int?,
    val outs: Int?,
    /** Register number -> parameter name, from `.param`. */
    val params: Map<Int, String>,
    val items: List<AsmItem>,
    val catches: List<AsmCatch>,
)

sealed class AsmItem {
    abstract val line: Int
}

data class AsmLabel(override val line: Int, val name: String) : AsmItem()

data class AsmLine(override val line: Int, val number: Int) : AsmItem()

data class AsmInsn(override val line: Int, val mnemonic: String, val operands: List<Operand>, val declaredAddress: Int?) : AsmItem()

data class AsmPackedSwitch(override val line: Int, val firstKey: Int, val targets: List<Operand>, val declaredAddress: Int?) : AsmItem()

data class AsmSparseSwitch(override val line: Int, val cases: List<Pair<Int, Operand>>, val declaredAddress: Int?) : AsmItem()

data class AsmArrayData(override val line: Int, val width: Int, val values: List<Long>, val declaredAddress: Int?) : AsmItem()

/** `.catch` / `.catchall` (type == null). */
data class AsmCatch(val line: Int, val type: String?, val start: String, val end: String, val handler: String)

sealed class Operand {
    data class Reg(val n: Int) : Operand()
    data class RegList(val regs: List<Int>) : Operand()
    data class RegRange(val first: Int, val last: Int) : Operand()
    data class Lit(val value: Long, val hex: Boolean) : Operand()
    data class Label(val name: String) : Operand()
    data class Relative(val offset: Int) : Operand()
    data class Str(val value: String) : Operand()
    data class Ref(val text: String) : Operand()
    data class Proto(val text: String) : Operand()
    data class Indexed(val kind: String, val index: Int) : Operand()
}

/** Parses the `.dasm` grammar produced by [dev.apkproject.dex.Disassembler]. */
object DasmParser {
    fun parse(text: String): AsmFile {
        val lines = text.lines()
        var version = 35
        val classes = ArrayList<AsmClass>()
        var i = 0

        // Mutable state for the class being read.
        var cls: ClassBuilder? = null
        fun finishClass() {
            cls?.let { classes += it.build() }
            cls = null
        }

        while (i < lines.size) {
            val lineNo = i + 1
            val line = stripComment(lines[i]).trim()
            i++
            if (line.isEmpty()) continue
            val words = line.split(Regex("\\s+"))
            when (words[0]) {
                ".dex" -> version = words.getOrNull(1)?.toIntOrNull() ?: throw AsmException(lineNo, "bad .dex version")
                ".class" -> {
                    finishClass()
                    if (words.size < 2) throw AsmException(lineNo, ".class needs a name")
                    cls = ClassBuilder(lineNo, flags(lineNo, words.subList(1, words.size - 1), AccessFlags.Kind.CLASS), words.last())
                }
                ".super" -> requireClass(cls, lineNo).superclass = words.getOrNull(1)
                ".implements" -> requireClass(cls, lineNo).interfaces += words[1]
                ".source" -> requireClass(cls, lineNo).sourceFile = Strings.unquote(line.substringAfter(".source").trim(), lineNo)
                ".field" -> {
                    val c = requireClass(cls, lineNo)
                    val decl = words.last()
                    val colon = decl.indexOf(':')
                    if (colon <= 0) throw AsmException(lineNo, "field declaration must be name:Type")
                    c.fields += AsmField(lineNo, flags(lineNo, words.subList(1, words.size - 1), AccessFlags.Kind.FIELD), decl.substring(0, colon), decl.substring(colon + 1))
                }
                ".method" -> {
                    val c = requireClass(cls, lineNo)
                    val decl = words.last()
                    val paren = decl.indexOf('(')
                    if (paren <= 0) throw AsmException(lineNo, "method declaration must be name(params)Return")
                    val (method, next) = parseMethod(lines, i, lineNo, flags(lineNo, words.subList(1, words.size - 1), AccessFlags.Kind.METHOD), decl.substring(0, paren), decl.substring(paren))
                    c.methods += method
                    i = next
                }
                else -> throw AsmException(lineNo, "unexpected '${words[0]}' outside a method")
            }
        }
        finishClass()
        return AsmFile(version, classes)
    }

    private class ClassBuilder(val line: Int, val flags: Int, val name: String) {
        var superclass: String? = null
        val interfaces = ArrayList<String>()
        var sourceFile: String? = null
        val fields = ArrayList<AsmField>()
        val methods = ArrayList<AsmMethod>()
        fun build() = AsmClass(line, flags, name, superclass, interfaces, sourceFile, fields, methods)
    }

    private fun requireClass(c: ClassBuilder?, line: Int): ClassBuilder = c ?: throw AsmException(line, "directive outside a .class")

    private fun flags(line: Int, words: List<String>, kind: AccessFlags.Kind): Int = try {
        AccessFlags.parse(words, kind)
    } catch (e: IllegalArgumentException) {
        throw AsmException(line, e.message ?: "bad flags")
    }

    private fun parseMethod(lines: List<String>, start: Int, declLine: Int, flags: Int, name: String, proto: String): Pair<AsmMethod, Int> {
        var registers: Int? = null
        var ins: Int? = null
        var outs: Int? = null
        val params = LinkedHashMap<Int, String>()
        val items = ArrayList<AsmItem>()
        val catches = ArrayList<AsmCatch>()
        var i = start
        while (i < lines.size) {
            val lineNo = i + 1
            var line = stripComment(lines[i]).trim()
            i++
            if (line.isEmpty()) continue
            if (line == ".end method") {
                return AsmMethod(declLine, flags, name, proto, registers, ins, outs, params, items, catches) to i
            }
            if (line.startsWith(":")) {
                items += AsmLabel(lineNo, line.substring(1))
                continue
            }
            var declared: Int? = null
            ADDRESS.find(line)?.let { m ->
                declared = m.groupValues[1].toInt(16)
                line = line.substring(m.value.length).trim()
            }
            val words = line.split(Regex("\\s+"))
            fun intArg(): Int = words.getOrNull(1)?.toIntOrNull() ?: throw AsmException(lineNo, "${words[0]} needs a number")
            when (words[0]) {
                ".registers" -> registers = intArg()
                ".ins" -> ins = intArg()
                ".outs" -> outs = intArg()
                ".line" -> items += AsmLine(lineNo, intArg())
                ".param" -> {
                    val reg = words.getOrNull(1)?.removePrefix("v")?.toIntOrNull() ?: throw AsmException(lineNo, ".param needs a register")
                    params[reg] = Strings.unquote(line.substringAfter(words[1]).trim(), lineNo)
                }
                ".catch", ".catchall" -> catches += parseCatch(lineNo, line, words[0] == ".catchall")
                ".packed-switch" -> {
                    val first = words.getOrNull(1)?.let { parseInt(it, lineNo) } ?: throw AsmException(lineNo, ".packed-switch needs a first key")
                    val targets = ArrayList<Operand>()
                    while (true) {
                        if (i >= lines.size) throw AsmException(lineNo, "unterminated .packed-switch")
                        val t = stripComment(lines[i]).trim(); i++
                        if (t.isEmpty()) continue
                        if (t == ".end packed-switch") break
                        targets += parseTargetOperand(t, i)
                    }
                    items += AsmPackedSwitch(lineNo, first, targets, declared)
                }
                ".sparse-switch" -> {
                    val cases = ArrayList<Pair<Int, Operand>>()
                    while (true) {
                        if (i >= lines.size) throw AsmException(lineNo, "unterminated .sparse-switch")
                        val t = stripComment(lines[i]).trim(); i++
                        if (t.isEmpty()) continue
                        if (t == ".end sparse-switch") break
                        val parts = t.split("->").map { it.trim() }
                        if (parts.size != 2) throw AsmException(i, "sparse-switch case must be 'key -> target'")
                        cases += parseInt(parts[0], i) to parseTargetOperand(parts[1], i)
                    }
                    items += AsmSparseSwitch(lineNo, cases, declared)
                }
                ".array-data" -> {
                    val width = intArg()
                    if (width !in setOf(1, 2, 4, 8)) throw AsmException(lineNo, "array element width must be 1, 2, 4 or 8")
                    val values = ArrayList<Long>()
                    while (true) {
                        if (i >= lines.size) throw AsmException(lineNo, "unterminated .array-data")
                        val t = stripComment(lines[i]).trim(); i++
                        if (t.isEmpty()) continue
                        if (t == ".end array-data") break
                        t.split(Regex("\\s+")).forEach { values += parseLong(it, i) }
                    }
                    items += AsmArrayData(lineNo, width, values, declared)
                }
                else -> {
                    if (words[0].startsWith(".")) throw AsmException(lineNo, "unknown directive ${words[0]}")
                    val mnemonic = words[0]
                    if (Opcodes.byMnemonic(mnemonic) == null) throw AsmException(lineNo, "unknown instruction '$mnemonic'")
                    items += AsmInsn(lineNo, mnemonic, Operands(line.substring(mnemonic.length), lineNo).parseAll(), declared)
                }
            }
        }
        throw AsmException(declLine, "method $name is missing .end method")
    }

    private fun parseCatch(line: Int, text: String, all: Boolean): AsmCatch {
        val m = (if (all) CATCHALL else CATCH).matchEntire(text) ?: throw AsmException(line, "malformed ${if (all) ".catchall" else ".catch"}")
        return if (all) AsmCatch(line, null, m.groupValues[1], m.groupValues[2], m.groupValues[3])
        else AsmCatch(line, m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
    }

    private fun parseTargetOperand(t: String, line: Int): Operand = when {
        t.startsWith(":") -> Operand.Label(t.substring(1))
        t.startsWith("+") || t.startsWith("-") -> Operand.Relative(parseInt(t.removePrefix("+"), line))
        else -> throw AsmException(line, "expected a label or +offset, got '$t'")
    }

    fun parseLong(s: String, line: Int): Long {
        val neg = s.startsWith("-")
        val body = s.removePrefix("-")
        val v = try {
            if (body.startsWith("0x")) java.lang.Long.parseUnsignedLong(body.substring(2), 16) else java.lang.Long.parseUnsignedLong(body)
        } catch (e: NumberFormatException) {
            throw AsmException(line, "bad number '$s'")
        }
        return if (neg) -v else v
    }

    fun parseInt(s: String, line: Int): Int {
        val v = parseLong(s, line)
        if (v < Int.MIN_VALUE || v > 0xffffffffL) throw AsmException(line, "number '$s' does not fit 32 bits")
        return v.toInt()
    }

    /** Removes a `;` comment that starts the line or follows whitespace, outside string literals. */
    fun stripComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else if (c == '"') {
                inString = true
            } else if (c == ';' && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private val ADDRESS = Regex("^([0-9a-f]{4,8}):\\s")
    private val CATCH = Regex("""\.catch\s+(\S+)\s+\{:(\S+)\s+\.\.\s+:(\S+)}\s+:(\S+)""")
    private val CATCHALL = Regex("""\.catchall\s+\{:(\S+)\s+\.\.\s+:(\S+)}\s+:(\S+)""")
}

/** Tokenizer for one instruction's operand list. */
private class Operands(val s: String, val line: Int) {
    var p = 0

    fun parseAll(): List<Operand> {
        val out = ArrayList<Operand>()
        while (true) {
            skip()
            if (p >= s.length) return out
            out += one()
            skip()
            if (p < s.length && s[p] == ',') p++
        }
    }

    private fun skip() {
        while (p < s.length && s[p].isWhitespace()) p++
    }

    private fun err(msg: String): Nothing = throw AsmException(line, msg)

    private fun one(): Operand {
        val c = s[p]
        return when {
            c == 'v' && p + 1 < s.length && s[p + 1].isDigit() -> Operand.Reg(register())
            c == '{' -> regList()
            c == '#' -> {
                p++
                val tok = token()
                Operand.Lit(DasmParser.parseLong(tok, line), tok.removePrefix("-").startsWith("0x"))
            }
            c == ':' -> {
                p++; Operand.Label(token())
            }
            c == '"' -> Operand.Str(string())
            c == '(' -> Operand.Proto(token())
            c == 'L' || c == '[' -> Operand.Ref(token())
            s.startsWith("call_site@", p) || s.startsWith("method_handle@", p) -> {
                val tok = token()
                Operand.Indexed(tok.substringBefore('@'), tok.substringAfter('@').toIntOrNull() ?: err("bad index in $tok"))
            }
            else -> err("unexpected operand text '${s.substring(p)}'")
        }
    }

    private fun token(): String {
        val start = p
        while (p < s.length && !s[p].isWhitespace() && s[p] != ',' && s[p] != '}') p++
        if (start == p) err("empty operand")
        return s.substring(start, p)
    }

    private fun register(): Int {
        p++ // 'v'
        val start = p
        while (p < s.length && s[p].isDigit()) p++
        return s.substring(start, p).toIntOrNull() ?: err("bad register")
    }

    private fun regList(): Operand {
        p++ // '{'
        val regs = ArrayList<Int>()
        skip()
        if (p < s.length && s[p] == '}') {
            p++; return Operand.RegList(regs)
        }
        while (true) {
            skip()
            if (p >= s.length || s[p] != 'v') err("expected a register in list")
            val r = register()
            skip()
            if (s.startsWith("..", p)) {
                p += 2; skip()
                if (p >= s.length || s[p] != 'v') err("expected the last register of a range")
                val last = register()
                skip()
                if (p >= s.length || s[p] != '}') err("expected '}' after a register range")
                p++
                return Operand.RegRange(r, last)
            }
            regs += r
            if (p < s.length && s[p] == ',') {
                p++; continue
            }
            if (p < s.length && s[p] == '}') {
                p++; return Operand.RegList(regs)
            }
            err("expected ',' or '}' in register list")
        }
    }

    private fun string(): String {
        val start = p
        p++
        while (p < s.length && s[p] != '"') {
            if (s[p] == '\\') p++
            p++
        }
        if (p >= s.length) err("unterminated string")
        p++
        return Strings.unquote(s.substring(start, p), line)
    }
}

object Strings {
    fun unquote(q: String, line: Int): String {
        if (q.length < 2 || !q.startsWith("\"") || !q.endsWith("\"")) throw AsmException(line, "expected a quoted string")
        val sb = StringBuilder()
        var i = 1
        while (i < q.length - 1) {
            val c = q[i]
            if (c == '\\') {
                i++
                when (val e = q.getOrNull(i) ?: throw AsmException(line, "dangling escape")) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '\'' -> sb.append('\'')
                    'u' -> {
                        val hex = q.substring(i + 1, minOf(i + 5, q.length - 1))
                        if (hex.length != 4) throw AsmException(line, "bad \\u escape")
                        sb.append(hex.toInt(16).toChar()); i += 4
                    }
                    else -> throw AsmException(line, "unknown escape \\$e")
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
