package dev.apkproject.export

import dev.apkproject.contracts.CheckRecord
import dev.apkproject.contracts.ExportManifest
import dev.apkproject.contracts.Hashing
import dev.apkproject.store.ArtifactStore
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

@Serializable
data class RegressionVector(val className: String, val method: String, val args: List<Int>, val expected: String)

@Serializable
data class BundleFile(val path: String, val sha256: String)

/** The accepted source bundle a job hands to the exporter. */
@Serializable
data class SourceBundleManifest(
    val jobId: String,
    val files: List<BundleFile>,
    val vectors: List<RegressionVector>,
    val buildCheck: CheckRecord,
    val javaRelease: Int,
    /** Access level per `Class.method`, used to decide which methods generated tests may call. */
    val publicMethods: List<String> = emptyList(),
)

class ExportException(val exitCode: Int, message: String) : Exception(message)

/**
 * Writes the recovered project (section 21). The project is assembled in a sibling temporary
 * directory and renamed into place only when complete, so a reader never sees a partial export.
 * The original DEX is never packaged as a substitute for missing source.
 */
object ProjectExporter {
    data class Inputs(
        val store: ArtifactStore,
        val bundle: SourceBundleManifest,
        val report: ExportManifest,
        /** Extra report files: relative path under recovery/ -> content. */
        val recoveryFiles: Map<String, ByteArray>,
        val allowPartial: Boolean,
    )

    /** [compileCheck] receives the exported `src/main/java` directory and returns null on success or diagnostics. */
    fun export(inputs: Inputs, outDir: Path, compileCheck: (Path) -> String?): Path {
        val report = inputs.report
        if (!report.policySatisfied && !inputs.allowPartial) {
            throw ExportException(4, "export policy ${report.policy} is not satisfied: ${report.methodsAccepted}/${report.methodsInScope} methods accepted. Use --allow-partial to export the accepted subset with its report.")
        }
        if (Files.exists(outDir) && Files.list(outDir).use { it.findAny().isPresent }) throw ExportException(2, "output directory $outDir is not empty")
        val parent = outDir.toAbsolutePath().parent
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".${outDir.fileName}.staging-")
        try {
            for (f in inputs.bundle.files) {
                val bytes = inputs.store.read(f.sha256)
                write(staging.resolve("src/main/java").resolve(f.path), bytes)
            }
            for ((path, text) in TestGenerator.generate(inputs.bundle)) write(staging.resolve("src/test/java").resolve(path), text.toByteArray())
            write(staging.resolve("settings.gradle.kts"), BuildFiles.settings().toByteArray())
            write(staging.resolve("build.gradle.kts"), BuildFiles.build(inputs.bundle.javaRelease).toByteArray())
            BuildFiles.copyWrapper(staging)
            for ((rel, bytes) in inputs.recoveryFiles) write(staging.resolve("recovery").resolve(rel), bytes)
            write(staging.resolve("README.md"), BuildFiles.readme(report).toByteArray())
            compileCheck(staging.resolve("src/main/java"))?.let { throw ExportException(5, "exported sources do not compile cleanly:\n$it") }
            val listing = Files.walk(staging).use { s -> s.filter { Files.isRegularFile(it) }.map { staging.relativize(it).toString().replace('\\', '/') }.sorted().toList() }
            val index = listing.joinToString("\n") { "${Hashing.sha256(staging.resolve(it))}  $it" } + "\n"
            write(staging.resolve("recovery/files.sha256"), index.toByteArray())
            if (Files.exists(outDir)) Files.delete(outDir)
            Files.move(staging, outDir, StandardCopyOption.ATOMIC_MOVE)
            return outDir
        } catch (e: Exception) {
            AtomicFiles.deleteRecursively(staging)
            throw e
        }
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}

internal object BuildFiles {
    fun settings(): String = """
        pluginManagement {
            repositories { gradlePluginPortal(); mavenCentral() }
        }
        dependencyResolutionManagement {
            repositories { mavenCentral() }
        }
        rootProject.name = "recovered"
    """.trimIndent() + "\n"

    fun build(release: Int): String = """
        // Synthesized by apkproject: a plain Java library build for the V0 method laboratory.
        // V1 exports an Android application project instead.
        plugins {
            `java-library`
        }

        dependencies {
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set($release)
            options.compilerArgs.add("-Xlint:-options")
        }

        tasks.test {
            useJUnitPlatform()
        }
    """.trimIndent() + "\n"

    fun copyWrapper(dir: Path) {
        for (name in listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val stream = BuildFiles::class.java.getResourceAsStream("/wrapper/$name") ?: continue
            val target = dir.resolve(name)
            Files.createDirectories(target.parent)
            stream.use { Files.copy(it, target) }
            if (name == "gradlew") {
                runCatching {
                    Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(target) + setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))
                }
            }
        }
    }

    fun readme(r: ExportManifest): String = buildString {
        append("# Recovered project\n\n")
        append("Produced by apkproject from input `${r.inputs.firstOrNull()?.relativePath}` (sha256 `${r.inputs.firstOrNull()?.sha256}`)\n")
        append("with toolchain profile `${r.profileId}`.\n\n")
        append("| | |\n|---|---|\n")
        append("| Methods in the input | ${r.methodsInScope} |\n")
        append("| Recovered as source and accepted | ${r.methodsAccepted} |\n")
        append("| Not recovered (listed in `recovery/report.json`) | ${r.methodsUnresolved} |\n")
        append("| Export policy | ${r.policy} (${if (r.policySatisfied) "satisfied" else "not satisfied, partial export"}) |\n\n")
        append("## What the evidence means\n\n")
        r.notes.forEach { append("- ").append(it).append('\n') }
        append("\n## Layout\n\n")
        append("- `src/main/java`: recovered source. Names are observed from the APK where present; `pN`/`tN` names are synthesized.\n")
        append("- `src/test/java`: generated regression tests. Expected values come from the original bytecode's semantics.\n")
        append("- `recovery/report.json`: per-method status, obligations, checks and evidence levels.\n")
        append("- `recovery/source-map.json`: each source line mapped to DEX byte ranges and the rules that produced it.\n")
        append("- `recovery/disassembly/`: Dalvik assembly of the input, the observed evidence.\n\n")
        append("Build with `./gradlew build` (JDK 17).\n")
    }
}

/** Generates JUnit 5 tests that pin the validated behaviour of each recovered method. */
internal object TestGenerator {
    fun generate(bundle: SourceBundleManifest): Map<String, String> {
        val callable = bundle.publicMethods.toSet()
        return bundle.vectors.filter { "${it.className}.${it.method}" in callable }.groupBy { it.className }.mapKeys { (cls, _) ->
            val pkg = cls.substringBeforeLast('.', "")
            val simple = cls.substringAfterLast('.')
            (if (pkg.isEmpty()) "" else pkg.replace('.', '/') + "/") + "${simple}RecoveredTest.java"
        }.mapValues { (_, vectors) ->
            val cls = vectors.first().className
            val pkg = cls.substringBeforeLast('.', "")
            val simple = cls.substringAfterLast('.')
            buildString {
                if (pkg.isNotEmpty()) append("package $pkg;\n\n")
                append("import org.junit.jupiter.api.Test;\n\n")
                append("import static org.junit.jupiter.api.Assertions.assertEquals;\n")
                append("import static org.junit.jupiter.api.Assertions.assertThrows;\n\n")
                append("/** Generated by apkproject. Expected values come from the original bytecode's semantics. */\n")
                append("class ${simple}RecoveredTest {\n")
                vectors.groupBy { it.method }.forEach { (method, vs) ->
                    append("    @Test\n    void $method() {\n")
                    for (v in vs) {
                        val call = "$simple.$method(${v.args.joinToString(", ") { literal(it) }})"
                        if (v.expected.startsWith("ok ")) {
                            append("        assertEquals(${literal(v.expected.removePrefix("ok ").toInt())}, $call);\n")
                        } else {
                            append("        assertThrows(${v.expected.removePrefix("throw ")}.class, () -> $call);\n")
                        }
                    }
                    append("    }\n\n")
                }
                setLength(length - 1)
                append("}\n")
            }
        }
    }

    private fun literal(v: Int): String = if (v == Int.MIN_VALUE) "Integer.MIN_VALUE" else "$v"
}
