package dev.apkproject.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.UIManager

/** Look and feel, colors and vector icons (crisp at any Windows display scale). */
object Theme {
    /** WindowsLookAndFeel on Windows; the platform's system look elsewhere. */
    fun install() {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    private fun color(key: String, fallback: Int) = UIManager.getColor(key) ?: Color(fallback)
    val background: Color get() = color("Panel.background", 0xF0F0F0)
    val surface: Color get() = color("TextArea.background", 0xFFFFFF)
    val border: Color get() = color("Separator.foreground", 0xA0A0A0)
    val text: Color get() = color("Label.foreground", 0x000000)
    val muted: Color get() = text
    val accent: Color get() = color("TextField.selectionBackground", 0x0078D7)
    val accentSoft: Color get() = background
    val good: Color get() = Color(0x17662E)
    val goodSoft: Color get() = background
    val warn: Color get() = Color(0x865A00)
    val warnSoft: Color get() = background
    val bad: Color get() = Color(0xB00020)
    val badSoft: Color get() = background

    fun font(size: Float = UIManager.getFont("Label.font").size2D, bold: Boolean = false): Font {
        val base = UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)
        return base.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
    }

    val mono: Font get() = Font(if (dev.apkproject.contracts.isWindows()) "Consolas" else Font.MONOSPACED, Font.PLAIN, 13)

    fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    /** A document and code glyph; the same mark is used by the native launcher. */
    fun appImage(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(size / 32.0, size / 32.0)
        val page = GeneralPath().apply {
            moveTo(5f, 2f); lineTo(21f, 2f); lineTo(28f, 9f)
            lineTo(28f, 30f); lineTo(5f, 30f); closePath()
        }
        g.color = Color.WHITE
        g.fill(page)
        g.color = Color(0x606060)
        g.stroke = BasicStroke(1.5f)
        g.draw(page)
        g.drawLine(21, 2, 21, 9); g.drawLine(21, 9, 28, 9)
        g.color = Color(0x0078D7)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
        g.drawLine(12, 14, 8, 18); g.drawLine(8, 18, 12, 22)
        g.drawLine(22, 14, 26, 18); g.drawLine(26, 18, 22, 22)
        g.drawLine(19, 12, 15, 24)
        g.dispose()
        return img
    }

}

/** Small vector icons drawn with Java2D. */
class VectorIcon(private val size: Int, private val color: () -> Color, private val draw: (Graphics2D, Float) -> Unit) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.translate(x, y)
        g2.color = color()
        g2.stroke = BasicStroke(size / 12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        draw(g2, size.toFloat())
        g2.dispose()
    }
}

object Icons {
    private fun path(vararg points: Float): GeneralPath = GeneralPath().apply {
        moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) { lineTo(points[i], points[i + 1]); i += 2 }
    }

    fun check(size: Int, color: () -> Color = { Theme.good }) = VectorIcon(size, color) { g, s ->
        g.fill(Ellipse2D.Float(0f, 0f, s, s))
        g.color = Color.WHITE
        g.stroke = BasicStroke(s / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path(s * .28f, s * .52f, s * .44f, s * .68f, s * .73f, s * .36f))
    }

    fun cross(size: Int, color: () -> Color = { Theme.bad }) = VectorIcon(size, color) { g, s ->
        g.fill(Ellipse2D.Float(0f, 0f, s, s))
        g.color = Color.WHITE
        g.stroke = BasicStroke(s / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path(s * .33f, s * .33f, s * .67f, s * .67f)); g.draw(path(s * .67f, s * .33f, s * .33f, s * .67f))
    }

    fun warning(size: Int, color: () -> Color = { Theme.warn }) = VectorIcon(size, color) { g, s ->
        g.fill(Ellipse2D.Float(0f, 0f, s, s))
        g.color = Color.WHITE
        g.stroke = BasicStroke(s / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path(s * .5f, s * .26f, s * .5f, s * .56f))
        g.fill(Ellipse2D.Float(s * .43f, s * .68f, s * .14f, s * .14f))
    }

    fun pending(size: Int) = VectorIcon(size, { Theme.border }) { g, s ->
        g.stroke = BasicStroke(s / 9f)
        g.draw(Ellipse2D.Float(s * .08f, s * .08f, s * .84f, s * .84f))
    }

    fun skipped(size: Int) = VectorIcon(size, { Theme.muted }) { g, s ->
        g.stroke = BasicStroke(s / 9f)
        g.draw(Ellipse2D.Float(s * .08f, s * .08f, s * .84f, s * .84f))
        g.draw(path(s * .32f, s * .5f, s * .68f, s * .5f))
    }

    /** A spinner whose arc angle is read from [phase] at paint time. */
    fun spinner(size: Int, phase: () -> Int) = VectorIcon(size, { Theme.accent }) { g, s ->
        g.stroke = BasicStroke(s / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Theme.accentSoft
        g.draw(Ellipse2D.Float(s * .1f, s * .1f, s * .8f, s * .8f))
        g.color = Theme.accent
        g.draw(Arc2D.Float(s * .1f, s * .1f, s * .8f, s * .8f, -phase().toFloat(), 100f, Arc2D.OPEN))
    }

    fun dot(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s -> g.fill(Ellipse2D.Float(s * .3f, s * .3f, s * .4f, s * .4f)) }

    fun upload(size: Int, color: () -> Color = { Theme.accent }) = VectorIcon(size, color) { g, s ->
        g.stroke = BasicStroke(s / 14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(RoundRectangle2D.Float(s * .16f, s * .30f, s * .68f, s * .56f, s * .12f, s * .12f))
        g.draw(path(s * .5f, s * .66f, s * .5f, s * .10f))
        g.draw(path(s * .34f, s * .26f, s * .5f, s * .10f, s * .66f, s * .26f))
    }

    fun recover(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s ->
        g.draw(path(s * .36f, s * .22f, s * .12f, s * .5f, s * .36f, s * .78f))
        g.draw(path(s * .64f, s * .22f, s * .88f, s * .5f, s * .64f, s * .78f))
    }

    fun results(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s ->
        g.draw(RoundRectangle2D.Float(s * .14f, s * .12f, s * .72f, s * .76f, s * .14f, s * .14f))
        g.draw(path(s * .30f, s * .36f, s * .70f, s * .36f)); g.draw(path(s * .30f, s * .52f, s * .70f, s * .52f)); g.draw(path(s * .30f, s * .68f, s * .54f, s * .68f))
    }

    fun tools(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s ->
        g.draw(Ellipse2D.Float(s * .30f, s * .30f, s * .40f, s * .40f))
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            g.draw(path((s / 2 + Math.cos(a) * s * .26).toFloat(), (s / 2 + Math.sin(a) * s * .26).toFloat(), (s / 2 + Math.cos(a) * s * .40).toFloat(), (s / 2 + Math.sin(a) * s * .40).toFloat()))
        }
    }

    fun info(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s ->
        g.draw(Ellipse2D.Float(s * .1f, s * .1f, s * .8f, s * .8f))
        g.draw(path(s * .5f, s * .46f, s * .5f, s * .72f))
        g.fill(Ellipse2D.Float(s * .44f, s * .26f, s * .12f, s * .12f))
    }

    fun folder(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s ->
        g.draw(path(s * .1f, s * .28f, s * .38f, s * .28f, s * .46f, s * .38f, s * .9f, s * .38f, s * .9f, s * .8f, s * .1f, s * .8f, s * .1f, s * .28f))
    }

    fun file(size: Int, color: () -> Color) = VectorIcon(size, color) { g, s ->
        g.draw(path(s * .24f, s * .1f, s * .6f, s * .1f, s * .78f, s * .28f, s * .78f, s * .9f, s * .24f, s * .9f, s * .24f, s * .1f))
    }
}
