package dev.apkproject.lift

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import dev.apkproject.dexasm.Assembler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataflowShapeTest {
    private fun shape(body: String, proto: String = "(II)I", registers: Int = 6): DataflowShape {
        val text = ".class public LT;\n.super Ljava/lang/Object;\n.method public static f$proto\n.registers $registers\n$body\n.end method\n"
        val dex = (DexFile.parse(Assembler.assemble(text)) as Outcome.Ok).value
        val code = dex.methods().single().code!!
        val decoded = (InstructionDecoder.decode(dex, code) as Outcome.Ok).value
        return assertNotNull(DataflowShape.of(dex, decoded))
    }

    @Test
    fun `register allocation and moves do not matter`() {
        // (a - b) * 3 computed with different registers and a redundant copy.
        val a = shape("sub-int v0, v4, v5\nmul-int/lit8 v0, v0, #3\nreturn v0")
        val b = shape("move v3, v5\nsub-int v1, v4, v3\nmul-int/lit8 v2, v1, #3\nreturn v2")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(b).match)
    }

    @Test
    fun `two-address and literal encodings are the same operation`() {
        val a = shape("sub-int/2addr v4, v5\nconst/4 v0, #3\nmul-int/2addr v4, v0\nreturn v4")
        val b = shape("sub-int v0, v4, v5\nconst/16 v1, #3\nmul-int v0, v0, v1\nreturn v0")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(b).match)
    }

    @Test
    fun `swapped operands keep the operations but change the dataflow`() {
        val a = shape("sub-int v0, v4, v5\nreturn v0")
        val b = shape("sub-int v0, v5, v4\nreturn v0")
        assertEquals(RoundTripMatch.SIMILAR_OPERATIONS, a.compare(b).match)
    }

    @Test
    fun `a different constant is a different method`() {
        val a = shape("add-int/lit8 v0, v4, #1\nreturn v0")
        val b = shape("add-int/lit8 v0, v4, #2\nreturn v0")
        val c = a.compare(b)
        assertEquals(RoundTripMatch.DIFFERENT, c.match)
        assertTrue(c.similarity < 1.0)
    }

    @Test
    fun `shared and duplicated return tails are the same method modulo layout`() {
        // max(a, b): one return reached by both paths, or one return per path.
        val a = shape("if-ge v4, v5, :a\nmove v4, v5\n:a\nreturn v4")
        val b = shape("if-ge v4, v5, :x\nmove v0, v5\nreturn v0\n:x\nmove v0, v4\nreturn v0")
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, a.compare(b).match)
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(shape("if-ge v4, v5, :a\nmove v4, v5\n:a\nreturn v4")).match)
        // min(a, b) is not max(a, b), whatever the layout.
        val min = shape("if-le v4, v5, :x\nmove v0, v5\nreturn v0\n:x\nmove v0, v4\nreturn v0")
        assertEquals(RoundTripMatch.SIMILAR_OPERATIONS, a.compare(min).match)
    }

    @Test
    fun `branch polarity, operand order and block order are layout`() {
        // a < b ? 1 : 0 in three spellings.
        val lt = shape("if-lt v4, v5, :t\nconst/4 v0, #0\nreturn v0\n:t\nconst/4 v0, #1\nreturn v0")
        val ge = shape("if-ge v4, v5, :f\nconst/4 v0, #1\nreturn v0\n:f\nconst/4 v0, #0\nreturn v0")
        val gt = shape("if-gt v5, v4, :t\nconst/4 v1, #0\ngoto :r\n:t\nconst/4 v1, #1\n:r\nreturn v1")
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, lt.compare(ge).match, "${lt.canonical}\n${ge.canonical}")
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, lt.compare(gt).match, "${lt.canonical}\n${gt.canonical}")
        // a <= b is a different condition.
        val le = shape("if-le v4, v5, :t\nconst/4 v0, #0\nreturn v0\n:t\nconst/4 v0, #1\nreturn v0")
        assertEquals(RoundTripMatch.SIMILAR_OPERATIONS, lt.compare(le).match)
        // a < b is b > a, and !(b <= a).
        val notBLeA = shape("if-le v5, v4, :f\nconst/4 v0, #1\nreturn v0\n:f\nconst/4 v0, #0\nreturn v0")
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, lt.compare(notBLeA).match, "${lt.canonical}\n${notBLeA.canonical}")
    }

    @Test
    fun `a dropped widening cast is a different method`() {
        // float ratio = (float) w / h   vs   float ratio = w / h  (a decompiler bug seen in the wild)
        val original = shape("int-to-float v0, v4\nint-to-float v1, v5\ndiv-float v0, v0, v1\nreturn v0", proto = "(II)F")
        val decompiled = shape("div-int v0, v4, v5\nint-to-float v0, v0\nreturn v0", proto = "(II)F")
        assertEquals(RoundTripMatch.DIFFERENT, original.compare(decompiled).match)
    }

    @Test
    fun `reordered side effects keep the operations but not the layout class`() {
        val a = shape("iput v4, v3, LT;->a:I\niput v5, v3, LT;->b:I\nreturn-void", proto = "(II)V", registers = 6)
        val b = shape("iput v5, v3, LT;->b:I\niput v4, v3, LT;->a:I\nreturn-void", proto = "(II)V", registers = 6)
        assertEquals(RoundTripMatch.SIMILAR_OPERATIONS, a.compare(b).match)
    }

    @Test
    fun `chains of merges through moves stay linear`() {
        // 80 diamonds that each copy one of two registers: naive origin tracing doubles per diamond.
        val body = (0 until 80).joinToString("\n") { k ->
            "if-eqz v4, :a$k\nmove v0, v1\ngoto :m$k\n:a$k\nmove v0, v2\n:m$k\nmove v1, v0\nmove v2, v0"
        } + "\nreturn v0"
        val started = System.nanoTime()
        val a = shape("const/4 v1, #1\nconst/4 v2, #2\n$body", proto = "(I)I", registers = 5)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "took too long")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(a).match)
    }

    private fun method(source: String, name: String): DataflowShape {
        val dex = (DexFile.parse(Assembler.assemble(source)) as Outcome.Ok).value
        val m = dex.methods().single { it.name == name }
        return assertNotNull(DataflowShape.of(dex, (InstructionDecoder.decode(dex, m.code!!) as Outcome.Ok).value))
    }

    @Test
    fun `calls through synthetic accessors compare as the access they perform`() {
        fun outer(accessor: String, field: String = "x") = """
            .class public LO;
            .super Ljava/lang/Object;
            .field private x:I
            .field private y:I
            .method static synthetic $accessor(LO;)I
            .registers 2
            iget v0, v1, LO;->$field:I
            return v0
            .end method
            .method public f()I
            .registers 2
            invoke-static {v1}, LO;->$accessor(LO;)I
            move-result v0
            return v0
            .end method
        """.trimIndent()
        val d8 = method(outer("-\$\$Nest\$fgetx"), "f")
        val javac = method(outer("access\$000"), "f")
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, d8.compare(javac).match)
        // An accessor of another field is a different access.
        assertEquals(RoundTripMatch.DIFFERENT, d8.compare(method(outer("access\$000", field = "y"), "f")).match)
    }

    @Test
    fun `renaming maps recompiled class names back before comparing`() {
        val a = shape("new-instance v0, LO\$1;\nreturn-object v0", proto = "()Ljava/lang/Object;")
        val b = shape("new-instance v0, LO\$2;\nreturn-object v0", proto = "()Ljava/lang/Object;")
        assertEquals(RoundTripMatch.DIFFERENT, a.compare(b).match)
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(b.renamed(mapOf("LO\$2;" to "LO\$1;", "LO\$1;" to "LO\$2;"))).match)
    }

    @Test
    fun `array literals compare the same however the compiler builds them`() {
        val proto = "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;"
        val aputs = shape(
            "const/4 v0, #2\nnew-array v0, v0, [Ljava/lang/String;\nconst/4 v1, #0\naput-object v4, v0, v1\nconst/4 v1, #1\naput-object v5, v0, v1\nreturn-object v0",
            proto = proto,
        )
        val filled = shape("filled-new-array {v4, v5}, [Ljava/lang/String;\nmove-result-object v0\nreturn-object v0", proto = proto)
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, aputs.compare(filled).match)
        val swapped = shape("filled-new-array {v5, v4}, [Ljava/lang/String;\nmove-result-object v0\nreturn-object v0", proto = proto)
        assertTrue(aputs.compare(swapped).match >= RoundTripMatch.SIMILAR_OPERATIONS, "element order matters")
    }

    @Test
    fun `a cast of null is not an operation, other casts are`() {
        val cast = shape("const/4 v0, #0\ncheck-cast v0, Ljava/lang/String;\nreturn-object v0", proto = "()Ljava/lang/Object;", registers = 1)
        val plain = shape("const/4 v0, #0\nreturn-object v0", proto = "()Ljava/lang/Object;", registers = 1)
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, cast.compare(plain).match)
        val realCast = shape("check-cast v0, Ljava/lang/String;\nreturn-object v0", proto = "(Ljava/lang/Object;)Ljava/lang/Object;", registers = 1)
        val noCast = shape("return-object v0", proto = "(Ljava/lang/Object;)Ljava/lang/Object;", registers = 1)
        assertEquals(RoundTripMatch.DIFFERENT, realCast.compare(noCast).match)
    }

    @Test
    fun `object methods dispatch the same through any static type`() {
        val proto = "(Ljava/lang/CharSequence;)Ljava/lang/String;"
        val viaObject = shape("invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;\nmove-result-object v0\nreturn-object v0", proto = proto, registers = 1)
        val viaInterface = shape("invoke-interface {v0}, Ljava/lang/CharSequence;->toString()Ljava/lang/String;\nmove-result-object v0\nreturn-object v0", proto = proto, registers = 1)
        assertEquals(RoundTripMatch.EQUIVALENT_MODULO_LAYOUT, viaObject.compare(viaInterface).match)
        val length = shape("invoke-interface {v0}, Ljava/lang/CharSequence;->length()I\nmove-result v0\nreturn v0", proto = "(Ljava/lang/CharSequence;)I", registers = 1)
        assertEquals(RoundTripMatch.DIFFERENT, viaObject.compare(length).match)
    }

    @Test
    fun `invoke targets and arity are part of the shape`() {
        val a = shape("invoke-static {v4, v5}, Ljava/lang/Math;->max(II)I\nmove-result v0\nreturn v0")
        val b = shape("invoke-static {v4, v5}, Ljava/lang/Math;->min(II)I\nmove-result v0\nreturn v0")
        val c = shape("invoke-static/range {v4 .. v5}, Ljava/lang/Math;->max(II)I\nmove-result v1\nreturn v1")
        assertEquals(RoundTripMatch.DIFFERENT, a.compare(b).match)
        assertEquals(RoundTripMatch.EQUIVALENT_DATAFLOW, a.compare(c).match)
    }
}
