package dev.apkproject.desktop

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in reproducible workload; reports measurements without fragile timing assertions. */
@EnabledIfEnvironmentVariable(named = "APKPROJECT_PERFORMANCE", matches = "1")
class DesktopPerformanceTest {
    @Test
    fun `large project load and first view`(@TempDir dir: Path) {
        val recovery = Files.createDirectories(dir.resolve("recovery"))
        Files.writeString(recovery.resolve("report.json"), "{}")
        val java = Files.createDirectories(dir.resolve("app/src/main/java"))
        repeat(2000) { i ->
            val folder = Files.createDirectories(java.resolve("p$i"))
            repeat(5) { Files.writeString(folder.resolve("C$it.java"), "class C$it {}") }
        }
        Files.newBufferedWriter(recovery.resolve("round-trip.json")).use { out ->
            out.append('[')
            repeat(200000) { i ->
                if (i > 0) out.append(',')
                out.append("""{"method":"p${i / 100}.C${i % 5}.m$i()V","file":"p${i / 100}/C${i % 5}.java","result":"EQUIVALENT_DATAFLOW"}""")
            }
            out.append(']')
        }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val allocated = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        val project = Project(dir)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val bytes = bean.getThreadAllocatedBytes(thread) - allocated
        assertEquals(10000, project.verdicts.size)
        var viewMs = 0L
        SwingUtilities.invokeAndWait {
            val begin = System.nanoTime()
            val view = ProjectView(project, Environment(dir.resolve("data"), Settings(null)), Settings(null), StatusBar())
            viewMs = (System.nanoTime() - begin) / 1_000_000
            view.dispose()
        }
        println("DESKTOP_PERF methods=200000 files=10000 folders=2000 loadMs=$elapsed loadAllocatedBytes=$bytes firstViewEdtMs=$viewMs")
    }
}
