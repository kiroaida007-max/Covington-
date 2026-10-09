package dev.apkproject.ir

/** Observable outcome of running a pure method: a returned value or a thrown exception class. */
sealed class Outcome {
    data class Returned(val value: Int) : Outcome() {
        override fun toString(): String = "returned $value"
    }

    data class Threw(val exception: String) : Outcome() {
        override fun toString(): String = "threw $exception"
    }
}

/**
 * Reference interpreter for the V0 IR. It is one of several independent semantic models:
 * the SMT encoding, the JVM executing compiled candidates and (for fixtures) the JVM executing
 * the hidden original source. A disagreement between any two is a bug to investigate.
 */
object IrEvaluator {
    class StepLimitExceeded : RuntimeException("step budget exhausted")

    fun run(m: MethodIr, args: IntArray, stepBudget: Int = 100_000): Outcome {
        require(args.size == m.signature.params.size) { "expected ${m.signature.params.size} arguments" }
        val env = HashMap<Value, Int>()
        var current = m.entry
        var previous = -1
        var steps = 0
        while (true) {
            if (++steps > stepBudget) throw StepLimitExceeded()
            val b = m.block(current)
            // Phis read their inputs simultaneously, before any is written.
            val phiValues = b.phis.map { it.def to env.getValue(it.incoming[previous] ?: error("phi ${it.def} has no input from b$previous")) }
            phiValues.forEach { (v, x) -> env[v] = x }
            for (op in b.ops) {
                env[op.def] = when (op) {
                    is Param -> args[op.index]
                    is ConstBits -> op.int
                    is Unary -> unary(op.kind, env.getValue(op.a))
                    is Binary -> {
                        val a = env.getValue(op.a)
                        val c = env.getValue(op.b)
                        if (op.kind.mayThrow && c == 0) return Outcome.Threw("java.lang.ArithmeticException")
                        binary(op.kind, a, c)
                    }
                    is Phi -> error("phi in op list")
                }
            }
            when (val t = b.terminator) {
                is Return -> return Outcome.Returned(env.getValue(t.value))
                is Goto -> {
                    previous = current; current = t.target
                }
                is If -> {
                    val a = env.getValue(t.a)
                    val c = t.b?.let { env.getValue(it) } ?: 0
                    previous = current
                    current = if (t.cond.test(a, c)) t.ifTrue else t.ifFalse
                }
            }
        }
    }

    fun binary(kind: BinKind, a: Int, b: Int): Int = when (kind) {
        BinKind.ADD -> a + b
        BinKind.SUB -> a - b
        BinKind.MUL -> a * b
        BinKind.DIV -> if (a == Int.MIN_VALUE && b == -1) Int.MIN_VALUE else a / b
        BinKind.REM -> if (b == -1) 0 else a % b
        BinKind.AND -> a and b
        BinKind.OR -> a or b
        BinKind.XOR -> a xor b
        BinKind.SHL -> a shl (b and 31)
        BinKind.SHR -> a shr (b and 31)
        BinKind.USHR -> a ushr (b and 31)
    }

    fun unary(kind: UnKind, a: Int): Int = when (kind) {
        UnKind.NEG -> -a
        UnKind.NOT -> a.inv()
        UnKind.TO_BYTE -> a.toByte().toInt()
        UnKind.TO_CHAR -> a and 0xffff
        UnKind.TO_SHORT -> a.toShort().toInt()
    }
}
