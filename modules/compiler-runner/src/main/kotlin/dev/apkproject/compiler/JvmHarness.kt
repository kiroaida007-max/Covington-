package dev.apkproject.compiler

import dev.apkproject.contracts.ResolvedProfile
import java.nio.file.Files
import java.nio.file.Path

/** One static int method invocation request. */
data class Invocation(val className: String, val method: String, val args: IntArray) {
    override fun equals(other: Any?) = other is Invocation && className == other.className && method == other.method && args.contentEquals(other.args)
    override fun hashCode() = (className.hashCode() * 31 + method.hashCode()) * 31 + args.contentHashCode()
}

/** Observed outcome as reported by the child JVM: `ok <int>` or `throw <class>` or `error <reason>`. */
data class Observed(val kind: String, val value: String) {
    override fun toString(): String = "$kind $value"
}

/**
 * Runs compiled classes in a separate JVM from the profile's JDK. The harness is compiled from
 * source with the profile's javac; application code is never loaded into the engine's own JVM
 * (section 20).
 */
class JvmHarness(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null) {
    fun run(classpath: List<Path>, calls: List<Invocation>, workDir: Path, timeoutMs: Long): List<Observed> {
        Files.createDirectories(workDir)
        val harnessDir = workDir.resolve("harness")
        val harnessClass = harnessDir.resolve("ApkprojectHarness.class")
        if (!Files.exists(harnessClass)) {
            Files.createDirectories(harnessDir)
            val src = harnessDir.resolve("ApkprojectHarness.java")
            Files.writeString(src, SOURCE)
            val r = ProcessWorker.run(listOf(profile.javac.toString(), "--release", "8", "-Xlint:-options", "-d", harnessDir.toString(), src.toString()), harnessDir, timeoutMs, cancel = cancel)
            check(r.ok) { "harness compilation failed: ${r.stderr}" }
        }
        val input = workDir.resolve("calls.txt")
        Files.write(input, calls.map { c -> (listOf(c.className, c.method, c.args.size.toString()) + c.args.map { it.toString() }).joinToString(" ") })
        val output = workDir.resolve("observed.txt")
        val cp = classpath.joinToString(java.io.File.pathSeparator) { it.toString() }
        val argv = listOf(profile.java.toString(), "-Xss4m", "-cp", harnessDir.toString(), "ApkprojectHarness", cp, input.toString(), output.toString())
        val r = ProcessWorker.run(argv, workDir, timeoutMs, cancel = cancel)
        if (r.cancelled) throw CancelledException()
        check(r.ok) { "harness run failed (exit ${r.exitCode}, timedOut=${r.timedOut}): ${r.stderr.take(2000)}" }
        val lines = Files.readAllLines(output)
        check(lines.size == calls.size) { "harness returned ${lines.size} results for ${calls.size} calls" }
        return lines.map { Observed(it.substringBefore(' '), it.substringAfter(' ')) }
    }

    companion object {
        private val SOURCE = """
            import java.io.*;
            import java.lang.reflect.*;
            import java.net.*;
            import java.util.*;

            public final class ApkprojectHarness {
                public static void main(String[] a) throws Exception {
                    String[] parts = a[0].split(File.pathSeparator);
                    URL[] urls = new URL[parts.length];
                    for (int i = 0; i < parts.length; i++) urls[i] = new File(parts[i]).toURI().toURL();
                    ClassLoader loader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());
                    Map<String, Method> cache = new HashMap<>();
                    try (BufferedReader in = new BufferedReader(new FileReader(a[1]));
                         PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(a[2])))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            String[] t = line.split(" ");
                            int n = Integer.parseInt(t[2]);
                            String key = t[0] + "#" + t[1] + "#" + n;
                            try {
                                Method m = cache.get(key);
                                if (m == null) {
                                    Class<?>[] types = new Class<?>[n];
                                    Arrays.fill(types, int.class);
                                    m = Class.forName(t[0], true, loader).getDeclaredMethod(t[1], types);
                                    m.setAccessible(true);
                                    cache.put(key, m);
                                }
                                Object[] args = new Object[n];
                                for (int i = 0; i < n; i++) args[i] = Integer.valueOf(t[3 + i]);
                                out.println("ok " + m.invoke(null, args));
                            } catch (InvocationTargetException e) {
                                out.println("throw " + e.getCause().getClass().getName());
                            } catch (Throwable e) {
                                out.println("error " + e.getClass().getName());
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
