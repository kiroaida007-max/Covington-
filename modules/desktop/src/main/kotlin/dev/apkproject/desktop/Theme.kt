package dev.apkproject.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.GradientPaint
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
    var dark = false
        private set

    fun install(dark: Boolean) {
        this.dark = dark
        FlatLaf.setGlobalExtraDefaults(
            mapOf(
                "@accentColor" to "#2563EB",
                "Component.arc" to "8",
                "Button.arc" to "8",
                "TextComponent.arc" to "8",
                "ScrollBar.width" to "12",
                "ScrollBar.thumbArc" to "999",
                "ScrollBar.thumbInsets" to "2,2,2,2",
                "TabbedPane.showTabSeparators" to "false",
                "Table.showHorizontalLines" to "true",
                "Table.intercellSpacing" to "0,1",
                "Tree.rowHeight" to "24",
                "Table.rowHeight" to "26",
            ),
        )
        if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
        UIManager.put("defaultFont", UIManager.getFont("defaultFont")?.deriveFont(13.5f))
        FlatLaf.updateUI()
    }

    // Semantic colors, chosen per theme.
    val background: Color get() = if (dark) Color(0x1E1F22) else Color(0xF6F7F9)
    val surface: Color get() = if (dark) Color(0x2B2D30) else Color.WHITE
    val sidebar: Color get() = if (dark) Color(0x232427) else Color(0xEEF0F4)
    val border: Color get() = if (dark) Color(0x3C3F44) else Color(0xDDE1E7)
    val text: Color get() = if (dark) Color(0xE6E7EA) else Color(0x1B1F24)
    val muted: Color get() = if (dark) Color(0x9AA0A8) else Color(0x5F6874)
    val accent: Color get() = Color(0x2563EB)
    val accentSoft: Color get() = if (dark) Color(0x1D2B4A) else Color(0xE8EFFD)
    val good: Color get() = if (dark) Color(0x4ADE80) else Color(0x15803D)
    val goodSoft: Color get() = if (dark) Color(0x163322) else Color(0xE6F4EA)
    val warn: Color get() = if (dark) Color(0xFBBF24) else Color(0xB45309)
    val warnSoft: Color get() = if (dark) Color(0x3A2E12) else Color(0xFDF3E1)
    val bad: Color get() = if (dark) Color(0xF87171) else Color(0xB91C1C)
    val badSoft: Color get() = if (dark) Color(0x3B1D1D) else Color(0xFDECEC)

    fun font(size: Float, bold: Boolean = false): Font {
        val base = UIManager.getFont("defaultFont") ?: Font("Dialog", Font.PLAIN, 13)
        return base.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
    }

    val mono: Font get() = Font(if (dev.apkproject.contracts.isWindows()) "Consolas" else Font.MONOSPACED, Font.PLAIN, 13)

    fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    /** The app's mark: an opened package with code brackets, on a blue-to-violet tile. */
    fun appImage(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val s = size.toFloat()
        g.paint = GradientPaint(0f, 0f, Color(0x2563EB), s, s, Color(0x7C3AED))
        g.fill(RoundRectangle2D.Float(0f, 0f, s, s, s * 0.44f, s * 0.44f))
        g.color = Color.WHITE
        g.stroke = BasicStroke(maxOf(1.2f, s * 0.075f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val lt = GeneralPath()
        lt.moveTo(s * 0.40, s * 0.30); lt.lineTo(s * 0.22, s * 0.50); lt.lineTo(s * 0.40, s * 0.70)
        g.draw(lt)
        val gt = GeneralPath()
        gt.moveTo(s * 0.60, s * 0.30); gt.lineTo(s * 0.78, s * 0.50); gt.lineTo(s * 0.60, s * 0.70)
        g.draw(gt)
        val slash = GeneralPath()
        slash.moveTo(s * 0.55, s * 0.26); slash.lineTo(s * 0.45, s * 0.74)
        g.draw(slash)
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
