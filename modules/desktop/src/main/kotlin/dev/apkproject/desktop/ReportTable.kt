package dev.apkproject.desktop

import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.JComponent
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel

/** Complete cell text remains readable when a native table column is narrow. */
class ReportTable(model: TableModel) : JTable(model) {
    init { fillsViewportHeight = true; rowHeight = maxOf(rowHeight, getFontMetrics(font).height + 4); toolTipText = "" }
    override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): java.awt.Component {
        // APK-controlled table strings are literal text, never Swing HTML (which can fetch images).
        (renderer as? JComponent)?.putClientProperty("html.disable", true)
        return super.prepareRenderer(renderer, row, column)
    }
    override fun getToolTipText(event: MouseEvent): String? {
        val row = rowAtPoint(event.point)
        val column = columnAtPoint(event.point)
        if (row < 0 || column < 0) return null
        return "<html><div style='width:500px'>${Ui.escape(getValueAt(row, column)?.toString().orEmpty())}</div></html>"
    }
}
