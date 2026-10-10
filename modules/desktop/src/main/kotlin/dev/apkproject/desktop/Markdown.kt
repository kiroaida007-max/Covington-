package dev.apkproject.desktop

/**
 * The Markdown the recovery report uses (headings, paragraphs, lists, pipe tables, code spans,
 * bold, links, fenced code), rendered as HTML for Swing's HTML view. Everything else is text.
 */
object Markdown {
    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out.append("<p>").append(inline(paragraph.joinToString(" "))).append("</p>\n")
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out.append("<pre>").append(Ui.escape(code.joinToString("\n"))).append("</pre>\n")
                }
                trimmed.startsWith("#") -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                    out.append("<h$level>").append(inline(trimmed.drop(level).trim())).append("</h$level>\n")
                }
                trimmed.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("""\|?\s*:?-{3,}.*""")) -> {
                    flush()
                    out.append("<table>\n<tr>")
                    cells(trimmed).forEach { out.append("<th>").append(inline(it)).append("</th>") }
                    out.append("</tr>\n")
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        out.append("<tr>")
                        cells(lines[i].trim()).forEach { out.append("<td>").append(inline(it)).append("</td>") }
                        out.append("</tr>\n")
                        i++
                    }
                    out.append("</table>\n")
                    continue
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flush()
                    out.append("<ul>\n")
                    while (i < lines.size && (lines[i].trim().startsWith("- ") || lines[i].trim().startsWith("* "))) {
                        out.append("<li>").append(inline(lines[i].trim().drop(2))).append("</li>\n")
                        i++
                    }
                    out.append("</ul>\n")
                    continue
                }
                trimmed.isEmpty() -> flush()
                else -> paragraph += trimmed
            }
            i++
        }
        flush()
        return out.toString()
    }

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Code spans first (their content is literal), then bold and links in the rest. */
    fun inline(text: String): String {
        val out = StringBuilder()
        val parts = text.split('`')
        for ((k, part) in parts.withIndex()) {
            if (k % 2 == 1 && k < parts.size - 1) {
                out.append("<code>").append(Ui.escape(part)).append("</code>")
            } else {
                var s = Ui.escape(if (k % 2 == 1) "`$part" else part)
                s = s.replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
                s = s.replace(Regex("""\[([^\]]+)]\(([^)\s]+)\)"""), "<a href=\"$2\">$1</a>")
                out.append(s)
            }
        }
        return out.toString()
    }

    /** A complete page in the current theme's colors. */
    fun page(markdown: String): String {
        val css = """
            body { font-family: '${Theme.font(13f).family}', sans-serif; font-size: 13pt; color: ${Theme.hex(Theme.text)}; margin: 18px 22px; }
            h1 { font-size: 20pt; margin: 6px 0 10px 0; }
            h2 { font-size: 15pt; margin: 18px 0 6px 0; }
            h3 { font-size: 13.5pt; margin: 14px 0 4px 0; }
            p { margin: 4px 0 8px 0; }
            code { font-family: monospace; color: ${Theme.hex(Theme.accent)}; }
            pre { font-family: monospace; background: ${Theme.hex(Theme.background)}; padding: 8px; }
            table { border-collapse: collapse; margin: 6px 0 12px 0; }
            th { text-align: left; background: ${Theme.hex(Theme.background)}; padding: 4px 8px; border: 1px solid ${Theme.hex(Theme.border)}; }
            td { padding: 4px 8px; border: 1px solid ${Theme.hex(Theme.border)}; vertical-align: top; }
            a { color: ${Theme.hex(Theme.accent)}; }
            li { margin-bottom: 2px; }
        """.trimIndent()
        return "<html><head><style>$css</style></head><body>${toHtml(markdown)}</body></html>"
    }
}
