package dev.apkproject.dex

/** Dalvik instruction formats (source.android.com/docs/core/runtime/instruction-formats). */
enum class Format(val units: Int) {
    F10x(1), F12x(1), F11n(1), F11x(1), F10t(1),
    F20t(2), F22x(2), F21t(2), F21s(2), F21h(2), F21c(2), F23x(2), F22b(2), F22t(2), F22s(2), F22c(2),
    F32x(3), F30t(3), F31t(3), F31i(3), F31c(3), F35c(3), F3rc(3),
    F45cc(4), F4rcc(4),
    F51l(5),
    ;

    val id: String get() = name.substring(1)
}

enum class IndexKind { NONE, STRING, TYPE, FIELD, METHOD, PROTO, CALL_SITE, METHOD_HANDLE, METHOD_AND_PROTO }

enum class FlowKind { NONE, GOTO, IF, SWITCH, RETURN, THROW }

/**
 * One assigned opcode. [wide] lists, in operand order, which register operands name a
 * 64-bit register pair ('W') or a single register ('N'); used for register-bound checks.
 */
class OpcodeInfo(
    val opcode: Int,
    val mnemonic: String,
    val format: Format,
    val index: IndexKind = IndexKind.NONE,
    val flow: FlowKind = FlowKind.NONE,
    val minVersion: Int = 35,
    val wide: String = "",
    /** True for fill-array-data, packed-switch and sparse-switch, whose 31t target is a payload. */
    val payloadRef: Boolean = false,
) {
    val units: Int get() = format.units
    override fun toString(): String = mnemonic
}

/**
 * Exact opcode table for DEX 035-039. This table only defines encodings (format recognition);
 * semantic support is a separate manifest in :lift-dex. Unassigned values decode to null and are
 * reported, never guessed.
 */
object Opcodes {
    private val table = arrayOfNulls<OpcodeInfo>(256)
    private val byName = HashMap<String, OpcodeInfo>()

    operator fun get(opcode: Int): OpcodeInfo? = table[opcode and 0xff]

    fun byMnemonic(name: String): OpcodeInfo? = byName[name]

    val all: List<OpcodeInfo> get() = table.filterNotNull()

    private fun op(
        code: Int, name: String, format: Format, index: IndexKind = IndexKind.NONE, flow: FlowKind = FlowKind.NONE,
        minVersion: Int = 35, wide: String = "", payloadRef: Boolean = false,
    ) {
        check(table[code] == null) { "duplicate opcode $code" }
        val info = OpcodeInfo(code, name, format, index, flow, minVersion, wide, payloadRef)
        table[code] = info
        byName[name] = info
    }

    init {
        op(0x00, "nop", Format.F10x)
        op(0x01, "move", Format.F12x)
        op(0x02, "move/from16", Format.F22x)
        op(0x03, "move/16", Format.F32x)
        op(0x04, "move-wide", Format.F12x, wide = "WW")
        op(0x05, "move-wide/from16", Format.F22x, wide = "WW")
        op(0x06, "move-wide/16", Format.F32x, wide = "WW")
        op(0x07, "move-object", Format.F12x)
        op(0x08, "move-object/from16", Format.F22x)
        op(0x09, "move-object/16", Format.F32x)
        op(0x0a, "move-result", Format.F11x)
        op(0x0b, "move-result-wide", Format.F11x, wide = "W")
        op(0x0c, "move-result-object", Format.F11x)
        op(0x0d, "move-exception", Format.F11x)
        op(0x0e, "return-void", Format.F10x, flow = FlowKind.RETURN)
        op(0x0f, "return", Format.F11x, flow = FlowKind.RETURN)
        op(0x10, "return-wide", Format.F11x, flow = FlowKind.RETURN, wide = "W")
        op(0x11, "return-object", Format.F11x, flow = FlowKind.RETURN)
        op(0x12, "const/4", Format.F11n)
        op(0x13, "const/16", Format.F21s)
        op(0x14, "const", Format.F31i)
        op(0x15, "const/high16", Format.F21h)
        op(0x16, "const-wide/16", Format.F21s, wide = "W")
        op(0x17, "const-wide/32", Format.F31i, wide = "W")
        op(0x18, "const-wide", Format.F51l, wide = "W")
        op(0x19, "const-wide/high16", Format.F21h, wide = "W")
        op(0x1a, "const-string", Format.F21c, IndexKind.STRING)
        op(0x1b, "const-string/jumbo", Format.F31c, IndexKind.STRING)
        op(0x1c, "const-class", Format.F21c, IndexKind.TYPE)
        op(0x1d, "monitor-enter", Format.F11x)
        op(0x1e, "monitor-exit", Format.F11x)
        op(0x1f, "check-cast", Format.F21c, IndexKind.TYPE)
        op(0x20, "instance-of", Format.F22c, IndexKind.TYPE)
        op(0x21, "array-length", Format.F12x)
        op(0x22, "new-instance", Format.F21c, IndexKind.TYPE)
        op(0x23, "new-array", Format.F22c, IndexKind.TYPE)
        op(0x24, "filled-new-array", Format.F35c, IndexKind.TYPE)
        op(0x25, "filled-new-array/range", Format.F3rc, IndexKind.TYPE)
        op(0x26, "fill-array-data", Format.F31t, payloadRef = true)
        op(0x27, "throw", Format.F11x, flow = FlowKind.THROW)
        op(0x28, "goto", Format.F10t, flow = FlowKind.GOTO)
        op(0x29, "goto/16", Format.F20t, flow = FlowKind.GOTO)
        op(0x2a, "goto/32", Format.F30t, flow = FlowKind.GOTO)
        op(0x2b, "packed-switch", Format.F31t, flow = FlowKind.SWITCH, payloadRef = true)
        op(0x2c, "sparse-switch", Format.F31t, flow = FlowKind.SWITCH, payloadRef = true)
        op(0x2d, "cmpl-float", Format.F23x)
        op(0x2e, "cmpg-float", Format.F23x)
        op(0x2f, "cmpl-double", Format.F23x, wide = "NWW")
        op(0x30, "cmpg-double", Format.F23x, wide = "NWW")
        op(0x31, "cmp-long", Format.F23x, wide = "NWW")
        listOf("if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le").forEachIndexed { i, n -> op(0x32 + i, n, Format.F22t, flow = FlowKind.IF) }
        listOf("if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez").forEachIndexed { i, n -> op(0x38 + i, n, Format.F21t, flow = FlowKind.IF) }
        val arraySuffixes = listOf("", "-wide", "-object", "-boolean", "-byte", "-char", "-short")
        arraySuffixes.forEachIndexed { i, s -> op(0x44 + i, "aget$s", Format.F23x, wide = if (s == "-wide") "WNN" else "") }
        arraySuffixes.forEachIndexed { i, s -> op(0x4b + i, "aput$s", Format.F23x, wide = if (s == "-wide") "WNN" else "") }
        arraySuffixes.forEachIndexed { i, s -> op(0x52 + i, "iget$s", Format.F22c, IndexKind.FIELD, wide = if (s == "-wide") "WN" else "") }
        arraySuffixes.forEachIndexed { i, s -> op(0x59 + i, "iput$s", Format.F22c, IndexKind.FIELD, wide = if (s == "-wide") "WN" else "") }
        arraySuffixes.forEachIndexed { i, s -> op(0x60 + i, "sget$s", Format.F21c, IndexKind.FIELD, wide = if (s == "-wide") "W" else "") }
        arraySuffixes.forEachIndexed { i, s -> op(0x67 + i, "sput$s", Format.F21c, IndexKind.FIELD, wide = if (s == "-wide") "W" else "") }
        val invokes = listOf("virtual", "super", "direct", "static", "interface")
        invokes.forEachIndexed { i, k -> op(0x6e + i, "invoke-$k", Format.F35c, IndexKind.METHOD) }
        invokes.forEachIndexed { i, k -> op(0x74 + i, "invoke-$k/range", Format.F3rc, IndexKind.METHOD) }
        // Unary operations and conversions: 7b..8f, operand widths [dest, source].
        listOf(
            "neg-int" to "NN", "not-int" to "NN", "neg-long" to "WW", "not-long" to "WW", "neg-float" to "NN",
            "neg-double" to "WW", "int-to-long" to "WN", "int-to-float" to "NN", "int-to-double" to "WN",
            "long-to-int" to "NW", "long-to-float" to "NW", "long-to-double" to "WW", "float-to-int" to "NN",
            "float-to-long" to "WN", "float-to-double" to "WN", "double-to-int" to "NW", "double-to-long" to "WW",
            "double-to-float" to "NW", "int-to-byte" to "NN", "int-to-char" to "NN", "int-to-short" to "NN",
        ).forEachIndexed { i, (n, w) -> op(0x7b + i, n, Format.F12x, wide = w) }
        val intOps = listOf("add", "sub", "mul", "div", "rem", "and", "or", "xor", "shl", "shr", "ushr")
        val floatOps = listOf("add", "sub", "mul", "div", "rem")
        fun binops(base: Int, suffix: String, format: Format) {
            var code = base
            for (o in intOps) op(code++, "$o-int$suffix", format)
            for (o in intOps) {
                val shift = o.startsWith("sh") || o == "ushr"
                val w = if (format == Format.F23x) (if (shift) "WWN" else "WWW") else (if (shift) "WN" else "WW")
                op(code++, "$o-long$suffix", format, wide = w)
            }
            for (o in floatOps) op(code++, "$o-float$suffix", format)
            for (o in floatOps) op(code++, "$o-double$suffix", format, wide = if (format == Format.F23x) "WWW" else "WW")
        }
        binops(0x90, "", Format.F23x)
        binops(0xb0, "/2addr", Format.F12x)
        listOf("add-int/lit16", "rsub-int", "mul-int/lit16", "div-int/lit16", "rem-int/lit16", "and-int/lit16", "or-int/lit16", "xor-int/lit16")
            .forEachIndexed { i, n -> op(0xd0 + i, n, Format.F22s) }
        listOf(
            "add-int/lit8", "rsub-int/lit8", "mul-int/lit8", "div-int/lit8", "rem-int/lit8", "and-int/lit8",
            "or-int/lit8", "xor-int/lit8", "shl-int/lit8", "shr-int/lit8", "ushr-int/lit8",
        ).forEachIndexed { i, n -> op(0xd8 + i, n, Format.F22b) }
        op(0xfa, "invoke-polymorphic", Format.F45cc, IndexKind.METHOD_AND_PROTO, minVersion = 38)
        op(0xfb, "invoke-polymorphic/range", Format.F4rcc, IndexKind.METHOD_AND_PROTO, minVersion = 38)
        op(0xfc, "invoke-custom", Format.F35c, IndexKind.CALL_SITE, minVersion = 38)
        op(0xfd, "invoke-custom/range", Format.F3rc, IndexKind.CALL_SITE, minVersion = 38)
        op(0xfe, "const-method-handle", Format.F21c, IndexKind.METHOD_HANDLE, minVersion = 39)
        op(0xff, "const-method-type", Format.F21c, IndexKind.PROTO, minVersion = 39)
    }

    /** Pseudo-instruction identifiers stored in the `nop` opcode position. */
    const val PACKED_SWITCH_PAYLOAD = 0x0100
    const val SPARSE_SWITCH_PAYLOAD = 0x0200
    const val FILL_ARRAY_DATA_PAYLOAD = 0x0300
}
