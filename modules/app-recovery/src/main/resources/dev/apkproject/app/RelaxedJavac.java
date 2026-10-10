// Part of apkproject: runs javac in process and drops only the errors of Java's checked-exception
// rules (an undeclared throw, a catch for an exception the body cannot throw, an override that
// throws more). Those rules decide whether a program is accepted, never what code is generated.
// Kotlin has no checked exceptions, so decompiled Kotlin breaks them everywhere; relaxing them lets
// the round trip compare such methods instead of skipping them. Every other error is reported as is.
//
// Build: javac --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
//              --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED -d DIR RelaxedJavac.java
// Run:   java (same --add-exports) -cp DIR RelaxedJavac <javac options> @<file listing the sources>

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;

import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RelaxedJavac {
    private static final Set<String> CHECKED_EXCEPTION_RULES = Set.of(
            "compiler.err.unreported.exception.need.to.catch.or.throw",
            "compiler.err.unreported.exception.default.constructor",
            "compiler.err.unreported.exception.implicit.close",
            "compiler.err.except.never.thrown.in.try",
            "compiler.err.except.already.caught",
            "compiler.err.override.meth.doesnt.throw");

    public static void main(String[] args) throws Exception {
        List<String> options = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("@")) sources.addAll(Files.readAllLines(Path.of(a.substring(1)), StandardCharsets.UTF_8));
            else options.add(a);
        }
        sources.removeIf(String::isBlank);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromStrings(sources);
        JavacTask task = (JavacTask) compiler.getTask(null, files, null, options, null, units);
        Log log = Log.instance(((BasicJavacTask) task).getContext());
        int[] relaxed = {0};
        new Log.DiagnosticHandler() {
            {
                install(log);
            }

            @Override
            public void report(JCDiagnostic d) {
                if (d.getKind() == Diagnostic.Kind.ERROR && CHECKED_EXCEPTION_RULES.contains(d.getCode())) {
                    relaxed[0]++;
                    JavaFileObject source = d.getSource();
                    System.out.println((source == null ? "?" : source.getName()) + ":" + d.getLineNumber() + ": relaxed: " + d.getCode());
                    return;
                }
                prev.report(d);
            }
        };
        boolean ok = task.call();
        System.out.println("relaxed " + relaxed[0] + " checked-exception errors");
        System.exit(ok ? 0 : 1);
    }
}
