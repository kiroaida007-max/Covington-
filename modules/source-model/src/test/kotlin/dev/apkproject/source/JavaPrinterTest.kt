package dev.apkproject.source

import dev.apkproject.source.JExpr.Binary
import dev.apkproject.source.JExpr.IntLit
import dev.apkproject.source.JExpr.Name
import dev.apkproject.source.JExpr.Ternary
import dev.apkproject.source.JExpr.Unary
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaPrinterTest {
    private val x = Name("x")
    private val y = Name("y")

    @Test
    fun `parenthesises by precedence and associativity`() {
        assertEquals("x + y * 2", JavaPrinter.expr(Binary(JBinOp.ADD, x, Binary(JBinOp.MUL, y, IntLit(2)))))
        assertEquals("(x + y) * 2", JavaPrinter.expr(Binary(JBinOp.MUL, Binary(JBinOp.ADD, x, y), IntLit(2))))
        assertEquals("x - (y - 1)", JavaPrinter.expr(Binary(JBinOp.SUB, x, Binary(JBinOp.SUB, y, IntLit(1)))))
        assertEquals("x - y - 1", JavaPrinter.expr(Binary(JBinOp.SUB, Binary(JBinOp.SUB, x, y), IntLit(1))))
        assertEquals("(x & 255) == 0", JavaPrinter.expr(Binary(JBinOp.EQ, Binary(JBinOp.AND, x, IntLit(255)), IntLit(0))))
        assertEquals("x << (y + 1)", JavaPrinter.expr(Binary(JBinOp.SHL, x, Binary(JBinOp.ADD, y, IntLit(1)))))
    }

    @Test
    fun `negative literals and negation never print as decrement`() {
        assertEquals("x - -5", JavaPrinter.expr(Binary(JBinOp.SUB, x, IntLit(-5))))
        assertEquals("-(-5)", JavaPrinter.expr(Unary(JUnOp.NEG, IntLit(-5))))
        assertEquals("-(-x)", JavaPrinter.expr(Unary(JUnOp.NEG, Unary(JUnOp.NEG, x))))
        assertEquals("-2147483648", JavaPrinter.expr(IntLit(Int.MIN_VALUE)))
    }

    @Test
    fun `ternaries nest to the right`() {
        val t = Ternary(Binary(JBinOp.LT, x, IntLit(0)), Unary(JUnOp.NEG, x), x)
        assertEquals("x < 0 ? -x : x", JavaPrinter.expr(t))
        assertEquals("(x < 0 ? -x : x) + 1", JavaPrinter.expr(Binary(JBinOp.ADD, t, IntLit(1))))
    }

    @Test
    fun `prints a class with line origins`() {
        val range = dev.apkproject.contracts.ByteRange("a".repeat(64), 100, 4)
        val m = JMethod(listOf("public", "static"), "int", "twice", listOf(JParam("int", "x")), listOf(JStmt.Return(Binary(JBinOp.ADD, x, x, listOf(range)), rules = listOf("r1"))))
        val unit = JavaPrinter.print(JClass("com.example", "Fixtures", listOf("public"), listOf(m)))
        assertTrue(unit.text.contains("package com.example;"))
        assertTrue(unit.text.contains("    public static int twice(int x) {\n        return x + x;\n    }"))
        val origin = unit.lines.single()
        assertEquals("        return x + x;", unit.text.lines()[origin.line - 1])
        assertEquals(listOf(range), origin.ranges)
    }

    @Test
    fun `identifier legality`() {
        assertTrue(JavaNames.isLegalIdentifier("abc\$1"))
        assertFalse(JavaNames.isLegalIdentifier("int"))
        assertFalse(JavaNames.isLegalIdentifier("1a"))
        assertFalse(JavaNames.isLegalIdentifier("a-b"))
    }
}
