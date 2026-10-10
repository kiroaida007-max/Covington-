package dev.apkproject.source

import dev.apkproject.contracts.ByteRange

/** Java binary operators with their precedence (higher binds tighter). */
enum class JBinOp(val symbol: String, val precedence: Int, val relational: Boolean = false) {
    MUL("*", 12), DIV("/", 12), REM("%", 12),
    ADD("+", 11), SUB("-", 11),
    SHL("<<", 10), SHR(">>", 10), USHR(">>>", 10),
    LT("<", 9, true), GT(">", 9, true), LE("<=", 9, true), GE(">=", 9, true),
    EQ("==", 8, true), NE("!=", 8, true),
    AND("&", 7), XOR("^", 6), OR("|", 5),
}

enum class JUnOp(val symbol: String) { NEG("-"), BITNOT("~") }

/** Expressions carry the DEX byte ranges of every IR operation they render. */
sealed class JExpr {
    abstract val origin: List<ByteRange>

    data class IntLit(val value: Int, val hex: Boolean = false, override val origin: List<ByteRange> = emptyList()) : JExpr()
    data class Name(val id: String, override val origin: List<ByteRange> = emptyList()) : JExpr()
    data class Binary(val op: JBinOp, val left: JExpr, val right: JExpr, override val origin: List<ByteRange> = emptyList()) : JExpr()
    data class Unary(val op: JUnOp, val operand: JExpr, override val origin: List<ByteRange> = emptyList()) : JExpr()
    data class Cast(val type: String, val operand: JExpr, override val origin: List<ByteRange> = emptyList()) : JExpr()
    data class Ternary(val cond: JExpr, val ifTrue: JExpr, val ifFalse: JExpr, override val origin: List<ByteRange> = emptyList()) : JExpr()

    /** All byte ranges of this expression and its children. */
    fun allOrigins(): List<ByteRange> = when (this) {
        is IntLit, is Name -> origin
        is Binary -> origin + left.allOrigins() + right.allOrigins()
        is Unary -> origin + operand.allOrigins()
        is Cast -> origin + operand.allOrigins()
        is Ternary -> origin + cond.allOrigins() + ifTrue.allOrigins() + ifFalse.allOrigins()
    }
}

sealed class JStmt {
    abstract val origin: List<ByteRange>
    abstract val rules: List<String>

    data class LocalDecl(val type: String, val name: String, val init: JExpr?, override val origin: List<ByteRange> = emptyList(), override val rules: List<String> = emptyList()) : JStmt()
    data class Assign(val name: String, val value: JExpr, override val origin: List<ByteRange> = emptyList(), override val rules: List<String> = emptyList()) : JStmt()
    data class Return(val value: JExpr, override val origin: List<ByteRange> = emptyList(), override val rules: List<String> = emptyList()) : JStmt()
    data class If(val cond: JExpr, val then: List<JStmt>, val otherwise: List<JStmt>, override val origin: List<ByteRange> = emptyList(), override val rules: List<String> = emptyList()) : JStmt()
}

data class JParam(val type: String, val name: String)

data class JMethod(
    val modifiers: List<String>,
    val returnType: String,
    val name: String,
    val params: List<JParam>,
    val body: List<JStmt>,
    /** Short provenance note printed above the method. */
    val comment: String? = null,
    /** Key used to map printed lines back to the recovered method. */
    val methodId: String = name,
)

data class JClass(
    val packageName: String?,
    val simpleName: String,
    val modifiers: List<String>,
    val methods: List<JMethod>,
    val header: List<String> = emptyList(),
) {
    val qualifiedName: String get() = if (packageName.isNullOrEmpty()) simpleName else "$packageName.$simpleName"
    val relativePath: String get() = (packageName?.replace('.', '/')?.plus("/") ?: "") + "$simpleName.java"
}

object JavaNames {
    private val KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_",
    )

    fun isLegalIdentifier(s: String): Boolean =
        s.isNotEmpty() && s !in KEYWORDS && Character.isJavaIdentifierStart(s[0]) && s.all { Character.isJavaIdentifierPart(it) }
}
