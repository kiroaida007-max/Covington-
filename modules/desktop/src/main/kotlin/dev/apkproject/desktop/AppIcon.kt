package dev.apkproject.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage

/**
 * The program icon, drawn so it is sharp at every size Windows asks for: a source page with a
 * folded corner and Android-green angle brackets. `packaging/icon.ico` is rendered from here.
 */
object AppIcon {
    private val paper = Color(0xFFFFFF)
    private val fold = Color(0xDDE3EA)
    private val edge = Color(0x5B6670)
    private val green = Color(0x2E9E5E)

    fun images(): List<BufferedImage> = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map(::image)

    fun image(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val s = size.toFloat()
        val left = s * 0.16f
        val right = s * 0.84f
        val top = s * 0.06f
        val bottom = s * 0.94f
        val corner = s * 0.24f
        val page = GeneralPath().apply {
            moveTo(left, top); lineTo(right - corner, top); lineTo(right, top + corner); lineTo(right, bottom); lineTo(left, bottom); closePath()
        }
        g.color = paper
        g.fill(page)
        val line = maxOf(1f, s / 32f)
        g.stroke = BasicStroke(line, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g.color = edge
        g.draw(page)
        val dog = GeneralPath().apply { moveTo(right - corner, top); lineTo(right - corner, top + corner); lineTo(right, top + corner); closePath() }
        g.color = fold
        g.fill(dog)
        g.color = edge
        g.draw(dog)
        // </> in the lower part of the page.
        g.color = green
        g.stroke = BasicStroke(maxOf(1.3f, s / 16f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val cy = s * 0.60f
        val h = s * 0.17f
        g.draw(GeneralPath().apply { moveTo(s * 0.40f, cy - h); lineTo(s * 0.27f, cy); lineTo(s * 0.40f, cy + h) })
        g.draw(GeneralPath().apply { moveTo(s * 0.60f, cy - h); lineTo(s * 0.73f, cy); lineTo(s * 0.60f, cy + h) })
        if (size >= 24) g.draw(GeneralPath().apply { moveTo(s * 0.545f, cy - h * 1.05f); lineTo(s * 0.455f, cy + h * 1.05f) })
        g.dispose()
        return img
    }
}
