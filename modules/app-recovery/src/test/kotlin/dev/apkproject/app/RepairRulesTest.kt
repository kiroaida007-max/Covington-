package dev.apkproject.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepairRulesTest {
    private val source = """
        package com.example;

        public class Cube {
            private Context mContext;

            public Cube(Context context) {
                this.mContext = context;
            }

            public void onSurfaceChanged(GL10 gl, int width, int height) {
                gl.glViewport(0, 0, width, height);
                float ratio = width / height; // ratio of the view
                String s = "a / b";
                gl.glFrustumf(-ratio, ratio, -1.0f, 1.0f, 1.0f, 10.0f);
            }

            public void other(int a, int b) {
                int q = a / b;
            }

            class Binder {
                void bind() {
                    ServiceConnection conn = Cube.this.new Connection();
                    View v = (View) find(1);
                }
            }
        }
    """.trimIndent()

    private val file = "com/example/Cube.java"

    @Test
    fun `regions cover only the named methods`() {
        val code = JavaMethodLocator.codeMask(source)
        val r = RepairRules.regions(source, code, "Lcom/example/Cube;->onSurfaceChanged(Ljavax/microedition/khronos/opengles/GL10;II)V").single()
        val body = source.substring(r.first, r.second)
        assertTrue(body.startsWith("{") && body.endsWith("}") && "float ratio" in body && "int q" !in body, body)
        val ctor = RepairRules.regions(source, code, "Lcom/example/Cube;-><init>(Landroid/content/Context;)V").single()
        assertTrue("this.mContext = context" in source.substring(ctor.first, ctor.second))
        assertEquals(emptyList(), RepairRules.regions(source, code, "Lcom/example/Cube$1;-><init>(Lcom/example/Cube;)V"))
        assertEquals(emptyList(), RepairRules.regions(source, code, "Lcom/example/Cube;->lambda\$onClick\$0(Landroid/view/View;)V"))
    }

    @Test
    fun `a dropped float widening is proposed only where the declaration needs it`() {
        val c = RepairRules.candidates(file, source, listOf("Lcom/example/Cube;->onSurfaceChanged(Ljavax/microedition/khronos/opengles/GL10;II)V"))
        val widen = c.filter { it.rule == "widen-before-divide" }
        assertEquals(listOf("float ratio = ((float) width) / height; // ratio of the view"), widen.map { it.after })
        assertEquals(12, widen.single().line)
        assertTrue(c.none { "a / b" !in it.text }, "string literals are never edited")
        assertTrue(c.none { "int q = ((" in it.text }, "other methods are not touched")
    }

    @Test
    fun `qualified inner class creation and casts are proposed in nested classes`() {
        val c = RepairRules.candidates(file, source, listOf("Lcom/example/Cube\$Binder;->bind()V"))
        assertTrue(c.any { it.rule == "implicit-outer-instance" && it.after == "ServiceConnection conn = new Connection();" }, c.map { it.after }.toString())
        assertTrue(c.any { it.rule == "drop-cast" && it.after == "View v = find(1);" }, c.map { it.after }.toString())
    }
}
