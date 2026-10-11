package dev.apkproject.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DesktopModelTest {
    /** Checks that each recognised engine log line maps to the expected [Stage], and unknown lines map to none. */
    @Test
    fun `engine log lines map to the steps the user sees`() {
        assertEquals(Stage.READ, Stage.of("input APK: 12 MB, 3 dex files"))
        assertEquals(Stage.NAMES, Stage.of("library naming skipped: no catalog"))
        assertEquals(Stage.NAMES, Stage.of("original names from compiler evidence: 120 classes"))
        assertEquals(Stage.DECOMPILE, Stage.of("decompiling with JADX 1.5.6..."))
        assertEquals(Stage.PROVE, Stage.of("cross-check: 27 of 29 proved"))
        assertEquals(Stage.COMPILE, Stage.of("compiling recovered Java with ECJ against android.jar + verified libraries..."))
        assertEquals(Stage.VERIFY, Stage.of("javac accepted 10/12 files"))
        assertEquals(Stage.VERIFY, Stage.of("round trip: 19189/35212 methods equivalent"))
        assertEquals(Stage.REPAIR, Stage.of("verified repair: 3 methods fixed"))
        assertEquals(Stage.BACKEND, Stage.of("backend contract: 112 endpoints"))
        assertEquals(Stage.AUDIT, Stage.of("privacy map: 2 kinds of personal data read, 0 flows reach a sink (0 high)"))
        assertEquals(Stage.AUDIT, Stage.of("supply chain: looking up known vulnerabilities on OSV.dev..."))
        assertNull(Stage.of("work directory kept: /tmp/x"))
    }

    /** Checks that [Markdown.toHtml] renders common Markdown constructs and escapes embedded HTML. */
    @Test
    fun `markdown renders headings, tables and code without passing html through`() {
        val html = Markdown.toHtml("# Report\n\nSome **bold** and `a<b>`.\n\n| Layer | Evidence |\n|---|---|\n| UI | DECODED |\n\n```\nint x = 1 < 2;\n```")
        assertTrue("<h1>Report</h1>" in html, html)
        assertTrue("<b>bold</b>" in html, html)
        assertTrue("a&lt;b&gt;" in html, html)
        assertTrue("<td>DECODED</td>" in html, html)
        assertTrue("<pre>int x = 1 &lt; 2;</pre>" in html, html)
    }

    /** Checks that a folder with a recovery report is recognised as a [Project] with verdicts derived from round-trip results. */
    @Test
    fun `a project folder is recognised and file verdicts come from the round trip`(@TempDir dir: Path) {
        assertFalse(Project.isProject(dir))
        val recovery = Files.createDirectories(dir.resolve("recovery"))
        Files.writeString(recovery.resolve("report.json"), "{}")
        Files.writeString(
            recovery.resolve("round-trip.json"),
            """[{"method":"a.A.f()V","file":"a/A.java","result":"EQUIVALENT_DATAFLOW"},{"method":"a.A.g()V","file":"a/A.java","result":"DIFFERENT"},{"method":"b.B.h()V","file":"b/B.java","result":"EQUIVALENT_MODULO_LAYOUT"}]""",
        )
        Files.writeString(recovery.resolve("compile-diagnostics.json"), """[{"path":"c/C.java","errors":2,"firstErrors":["x cannot be resolved"]}]""")
        assertTrue(Project.isProject(dir))
        val project = Project(dir)
        assertEquals(dir.fileName.toString(), project.title)
        assertEquals(FileVerdict.Kind.PARTIAL, project.verdicts.getValue("a/A.java").kind)
        assertEquals(FileVerdict.Kind.VERIFIED, project.verdicts.getValue("b/B.java").kind)
        assertEquals(FileVerdict.Kind.NOT_COMPILING, project.verdicts.getValue("c/C.java").kind)
        assertTrue(project.names.isEmpty())
    }
}
