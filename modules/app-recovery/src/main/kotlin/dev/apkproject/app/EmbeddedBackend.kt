package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class EmbeddedHandler(
    val method: String, val framework: String, val verbs: List<String>, val paths: List<String>,
    val queryParameters: List<String?>, val evidence: SourceRef,
)

@Serializable
data class EmbeddedSource(val path: String, val sha256: String)

@Serializable
data class EmbeddedBackendReport(
    val schemaVersion: Int = 1, val inputSha256: String, val status: String,
    val handlers: List<EmbeddedHandler>, val sources: List<EmbeddedSource> = emptyList(),
    val methods: List<MethodRoundTrip> = emptyList(), val externalTypes: List<String> = emptyList(),
    val issues: List<String> = emptyList(), val runnableHandlers: List<String> = emptyList(),
    val portableBuild: String = "NOT_RUN",
    val scope: String = "OBSERVED embedded handler declarations; exported Java is DECOMPILED. Per-method round-trip results check bytecode structure, not original deployment behavior. The optional HTTP adapter is SYNTHESIZED transport calling recovered code.",
)

/** Embedded server code is distinct from Retrofit clients and synthesized contract examples. */
object EmbeddedBackend {
    private val verbs = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    private val namespaces = listOf("Ljavax/ws/rs/", "Ljakarta/ws/rs/")
    private const val spring = "Lorg/springframework/web/bind/annotation/"
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private fun key(m: DexMethod) = "${m.owner}->${m.name}${m.protoDescriptor}"
    private fun strings(a: DexAnnotation?, element: String = "value"): List<String> = when (val v = a?.elements?.get(element)) {
        is EncodedValue.Str -> listOf(v.value)
        is EncodedValue.Arr -> v.values.filterIsInstance<EncodedValue.Str>().take(65).map { it.value }
        else -> emptyList()
    }
    private fun paths(base: List<String>, local: List<String>): List<String> = base.ifEmpty { listOf("") }.asSequence().flatMap { b ->
        local.ifEmpty { listOf("") }.asSequence().map { p -> "/" + listOf(b.trim('/'), p.trim('/')).filter { it.isNotEmpty() }.joinToString("/") }
    }.take(65).distinct().toList()

    fun scan(dex: List<Pair<String, DexFile>>, inputSha256: String, cancel: CancellationToken = CancellationToken()): EmbeddedBackendReport {
        val handlers = ArrayList<EmbeddedHandler>()
        val issues = ArrayList<String>()
        if (cancel.cancelled) throw CancelledException()
        val hierarchy by lazy { dex.flatMap { (_, d) -> d.classDefs.map { d.type(it.classIdx) to (d to it) } }.groupBy({ it.first }, { it.second }) }
        fun inherits(owner: String, target: String): Boolean {
            val seen = HashSet<String>(); val work = ArrayDeque(listOf(owner)); val queued = hashSetOf(owner)
            fun enqueue(type: String) { if (queued.size < 256 && queued.add(type)) work += type }
            while (work.isNotEmpty() && seen.size < 256) {
                val type = work.removeFirst()
                if (type == target) return true
                if (!seen.add(type)) continue
                val (d, c) = hierarchy[type]?.singleOrNull() ?: continue
                if (c.superclassIdx != NO_INDEX) enqueue(d.type(c.superclassIdx))
                c.interfaces.forEach { enqueue(d.type(it)) }
            }
            return false
        }
        for ((part, d) in dex) for (c in d.classDefs) {
            if (cancel.cancelled) throw CancelledException()
            if (handlers.size >= 128) return EmbeddedBackendReport(inputSha256 = inputSha256, status = "DECLARATIONS_FOUND", handlers = handlers.take(128), issues = listOf("Handler scan budget reached (128); remaining declarations were not examined."))
            if (c.accessFlags and (AccessFlags.INTERFACE or AccessFlags.ABSTRACT or AccessFlags.ANNOTATION) != 0) continue
            val a = d.annotations(c)
            val owner = d.type(c.classIdx)
            val methods = c.classData?.let { it.directMethods + it.virtualMethods }.orEmpty().map { DexMethod(d, c, it) }
            for (m in methods.filter { it.code != null }) {
                val annotations = a.methods[m.encoded.methodIdx].orEmpty()
                if (!m.isStatic && m.accessFlags and AccessFlags.PUBLIC != 0) {
                    val nano = "Lfi/iki/elonen/NanoHTTPD;"
                    if (m.name == "serve" && owner != nano && m.protoDescriptor == "(Lfi/iki/elonen/NanoHTTPD\$IHTTPSession;)Lfi/iki/elonen/NanoHTTPD\$Response;" && inherits(owner, nano))
                        handlers += EmbeddedHandler(key(m), "NanoHTTPD", emptyList(), emptyList(), listOf(null), SourceRef("dex-server-override", "$part:${key(m)}"))
                    if (m.name == "handle" && m.protoDescriptor == "(Lcom/sun/net/httpserver/HttpExchange;)V" && inherits(owner, "Lcom/sun/net/httpserver/HttpHandler;"))
                        handlers += EmbeddedHandler(key(m), "JDK HttpHandler", emptyList(), emptyList(), listOf(null), SourceRef("dex-server-override", "$part:${key(m)}"))
                }
                for (ns in namespaces) {
                    val base = a.classAnnotations.singleOrNull { it.type == ns + "Path;" }
                    val http = annotations.filter { it.type.removePrefix(ns).removeSuffix(";") in verbs && it.type.startsWith(ns) }
                    if (base != null && http.size == 1) {
                        val local = annotations.singleOrNull { it.type == ns + "Path;" }
                        val params = m.proto.parameters.indices.map { index ->
                            val p = a.parameters[m.encoded.methodIdx]?.getOrNull(index).orEmpty()
                            if (p.size == 1 && p[0].type == ns + "QueryParam;") strings(p[0]).singleOrNull() else null
                        }
                        val basePaths = strings(base)
                        val localPaths = strings(local)
                        val declaredPaths = if (basePaths.size == 1 && basePaths.single().length <= 2048 &&
                            (local == null || localPaths.size == 1) && localPaths.all { it.length <= 2048 }) paths(basePaths, localPaths) else emptyList()
                        if (declaredPaths.isEmpty()) issues += "Unsupported JAX-RS path declaration: ${key(m)}"
                        handlers += EmbeddedHandler(key(m), "JAX-RS", listOf(http.single().type.removePrefix(ns).removeSuffix(";")),
                            declaredPaths, params, SourceRef("dex-server-annotation", "$part:${key(m)}"))
                    }
                }
                val controller = a.classAnnotations.any { it.type == spring + "RestController;" || it.type == "Lorg/springframework/stereotype/Controller;" }
                if (controller) {
                    val mappings = annotations.filter { it.type.startsWith(spring) && it.type.removePrefix(spring).removeSuffix(";") in setOf("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping") }
                    if (mappings.size == 1) {
                        val mapping = mappings.single()
                        val verb = mapping.type.removePrefix(spring).removeSuffix("Mapping;").uppercase()
                        val http = if (verb in verbs) listOf(verb) else when (val v = mapping.elements["method"]) {
                            is EncodedValue.Arr -> v.values.filterIsInstance<EncodedValue.EnumRef>().map { it.descriptor.substringAfter("->").substringBefore(':') }.filter { it in verbs }
                            else -> emptyList()
                        }
                        val base = a.classAnnotations.singleOrNull { it.type == spring + "RequestMapping;" }
                        val declaredPaths = paths(strings(base) + strings(base, "path"), strings(mapping) + strings(mapping, "path"))
                        if (declaredPaths.size > 64 || declaredPaths.any { it.length > 4096 }) issues += "Spring path budget exceeded: ${key(m)}"
                        handlers += EmbeddedHandler(key(m), "Spring MVC", http, declaredPaths.filter { it.length <= 4096 }.take(64),
                            m.proto.parameters.map { null }, SourceRef("dex-server-annotation", "$part:${key(m)}"))
                    }
                }
            }
        }
        if (handlers.size > 128) issues += "Handler budget exceeded (128); remaining declarations were not exported."
        val found = handlers.take(128)
        return EmbeddedBackendReport(inputSha256 = inputSha256, status = if (found.isEmpty()) "NOT_DETECTED" else "DECLARATIONS_FOUND", handlers = found, issues = issues)
    }

    /** Export a conservative whole-class dependency slice; never invent missing business methods. */
    fun recover(dex: List<Pair<String, DexFile>>, inputSha256: String, javaRoot: Path, output: Path,
                names: NameMapping, existingChecks: List<MethodRoundTrip>, profile: ResolvedProfile,
                work: Path, cancel: CancellationToken, log: (String) -> Unit = {}): EmbeddedBackendReport {
        val scanned = scan(dex, inputSha256, cancel)
        if (scanned.handlers.isEmpty()) return write(output, scanned)
        log("embedded backend: ${scanned.handlers.size} handler declarations; exporting code and dependencies")
        val issues = scanned.issues.toMutableSet()
        fun issue(message: String) { if (issues.size < 128) issues += message }
        val classes = dex.flatMap { (part, d) -> d.classDefs.map { d.type(it.classIdx) to Triple(part, d, it) } }.groupBy({ it.first }, { it.second })
        fun sourcePath(type: String) = (names.classes[type] ?: type).removePrefix("L").removeSuffix(";").substringBefore('$') + ".java"
        val siblings = classes.keys.groupBy(::sourcePath)
        val queue = ArrayDeque(scanned.handlers.map { it.method.substringBefore("->") }.distinct())
        val queued = queue.toMutableSet()
        val selected = LinkedHashSet<String>()
        val external = sortedSetOf<String>()
        var codeUnits = 0L
        fun add(type: String) {
            val t = type.trimStart('[')
            if (!t.startsWith("L")) return
            if (t in classes) {
                if (t !in queued) {
                    if (queued.size < 512) { queued += t; queue.add(t) }
                    else issue("Dependency class budget exceeded (512); slice is incomplete.")
                }
            } else if (external.size < 4096) external += t else issue("External type budget exceeded (4096); slice is incomplete.")
        }
        fun value(v: EncodedValue) { when (v) {
            is EncodedValue.TypeRef -> add(v.descriptor)
            is EncodedValue.EnumRef -> add(v.descriptor.substringBefore("->"))
            is EncodedValue.Arr -> v.values.forEach(::value)
            is EncodedValue.Nested -> { add(v.annotation.type); v.annotation.elements.values.forEach(::value) }
            else -> Unit
        } }
        fun annotation(a: DexAnnotation) { add(a.type); a.elements.values.forEach(::value) }
        while (queue.isNotEmpty()) {
            if (cancel.cancelled) throw CancelledException()
            val owner = queue.removeFirst()
            if (owner in selected) continue
            if (selected.size >= 512) { issues += "Dependency class budget exceeded (512); slice is incomplete."; break }
            selected += owner
            val entry = classes.getValue(owner).singleOrNull()
            if (entry == null) { issue("Ambiguous duplicate class: $owner"); continue }
            siblings[sourcePath(owner)].orEmpty().forEach(::add)
            val (_, d, c) = entry
            if (c.superclassIdx != NO_INDEX) add(d.type(c.superclassIdx))
            c.interfaces.forEach { add(d.type(it)) }
            val a = d.annotations(c)
            (a.classAnnotations + a.fields.values.flatten() + a.methods.values.flatten() + a.parameters.values.flatten().flatten()).forEach(::annotation)
            c.classData?.let { data ->
                (data.staticFields + data.instanceFields).forEach { add(d.type(d.fieldIds[it.fieldIdx].typeIdx)) }
                for (m in (data.directMethods + data.virtualMethods).map { DexMethod(d, c, it) }) {
                    m.proto.parameters.forEach { add(d.type(it)) }; add(d.type(m.proto.returnType))
                    if (m.accessFlags and AccessFlags.NATIVE != 0) issue("Native dependency requires its original runtime: ${key(m)}")
                    val code = m.code ?: continue
                    codeUnits += code.insns.size
                    if (codeUnits > 2_000_000) { issues += "Instruction budget exceeded (2,000,000); slice is incomplete."; break }
                    val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value
                    if (decoded == null) { issue("Instruction decode failed: ${key(m)}"); continue }
                    for (i in decoded.insns) when (i.op.index) {
                        IndexKind.TYPE -> add(d.type(i.index))
                        IndexKind.FIELD -> { val f = d.fieldIds[i.index]; add(d.type(f.classIdx)); add(d.type(f.typeIdx)) }
                        IndexKind.METHOD -> {
                            val id = d.methodIds[i.index]; add(d.type(id.classIdx)); val p = d.protos[id.protoIdx]
                            p.parameters.forEach { add(d.type(it)) }; add(d.type(p.returnType))
                            if (i.op.mnemonic.startsWith("invoke-interface") || i.op.mnemonic.startsWith("invoke-virtual")) issue("Virtual dispatch targets require runtime review: ${key(m)}@${i.address}")
                        }
                        IndexKind.CALL_SITE, IndexKind.METHOD_HANDLE, IndexKind.METHOD_AND_PROTO -> issue("Dynamic invocation requires runtime review: ${key(m)}@${i.address}")
                        else -> Unit
                    }
                }
            }
            if (codeUnits > 2_000_000) break
        }
        if (external.any { it.startsWith("Ljava/lang/reflect/") || it.startsWith("Ljava/lang/invoke/") }) issues += "Reflective dependency targets cannot be established statically."
        val files = ArrayList<EmbeddedSource>()
        var bytes = 0L
        val realRoot = javaRoot.toRealPath()
        for (path in selected.map(::sourcePath).distinct().sorted()) {
            if (cancel.cancelled) throw CancelledException()
            if (!safePath(path)) { issue("Unsupported source path for embedded dependency."); continue }
            val source = javaRoot.resolve(path)
            if (!Files.isRegularFile(source) || !source.toRealPath().startsWith(realRoot)) { issue("Source unavailable: $path"); continue }
            if (Files.size(source) > 2 * 1024 * 1024 || bytes + Files.size(source) > 32 * 1024 * 1024) { issues += "Source byte budget exceeded (2 MiB/file, 32 MiB total)."; break }
            val data = Files.newInputStream(source).use { it.readNBytes(2 * 1024 * 1024 + 1) }
            if (data.size > 2 * 1024 * 1024) { issue("Source changed beyond its byte budget: $path"); continue }
            bytes += data.size
            val dest = output.resolve("src").resolve(path)
            Files.createDirectories(dest.parent); Files.write(dest, data)
            files += EmbeddedSource("src/$path", Hashing.sha256(data))
        }
        val methodKeys = selected.flatMap { owner -> classes[owner].orEmpty().flatMap { (_, d, c) ->
            c.classData?.let { it.directMethods + it.virtualMethods }.orEmpty().map { DexMethod(d, c, it) }.filter { it.code != null }.map(::key)
        } }.toSet()
        val checks = existingChecks.filter { it.method in methodKeys }
        val checkedMethods = checks.filter { it.result in RoundTripVerifier.EQUIVALENT }.map { it.method }.toSet()
        if (!checkedMethods.containsAll(methodKeys)) issue("Some exported methods lack matching full-recovery round-trip checks; launchers withheld.")
        var report = scanned.copy(status = if (files.isEmpty()) "CODE_UNAVAILABLE" else "CODE_EXPORTED", sources = files, methods = checks, externalTypes = external.toList(), issues = issues.toList())
        val candidates = if (issues.isNotEmpty() || files.isEmpty()) emptyList() else scanned.handlers.filter { h ->
            val m = classes[h.method.substringBefore("->")]?.singleOrNull()?.let { (_, d, c) -> c.classData?.let { it.directMethods + it.virtualMethods }.orEmpty().map { DexMethod(d, c, it) }.singleOrNull { key(it) == h.method } }
            h.framework == "JAX-RS" && h.verbs == listOf("GET") && h.paths.size == 1 && Regex("/[A-Za-z0-9_./-]*").matches(h.paths.single()) &&
                m != null && m.isStatic && m.accessFlags and AccessFlags.PUBLIC != 0 && m.classDef.accessFlags and AccessFlags.PUBLIC != 0 && '$' !in m.owner &&
                m.proto.parameters.size <= 8 && m.proto.parameters.all { m.dex.type(it) in setOf("I", "J") } && m.dex.type(m.proto.returnType) in setOf("I", "J") &&
                h.queryParameters.size == m.proto.parameters.size && h.queryParameters.all { it != null && Regex("[A-Za-z_][A-Za-z0-9_]{0,127}").matches(it) } &&
                h.queryParameters.distinct().size == h.queryParameters.size &&
                (names.classes[m.owner] ?: m.owner).removePrefix("L").removeSuffix(";").split('/').all { identifier.matches(it) } &&
                identifier.matches(names.methods[m.owner]?.get(m.name + m.protoDescriptor) ?: m.name)
        }.filter { h -> scanned.handlers.count { it.paths.any { p -> p == h.paths.single() } } == 1 }
        if (candidates.isNotEmpty() && issues.isEmpty() && files.isNotEmpty()) {
            val adapter = adapter(candidates, classes, names)
            Files.createDirectories(output)
            Files.writeString(output.resolve("RecoveredBackend.java"), adapter)
            files += EmbeddedSource("RecoveredBackend.java", Hashing.sha256(adapter))
            // Relative, quoted javac argument-file paths work on Windows and POSIX without shell interpolation.
            Files.writeString(output.resolve("sources.args"), files.joinToString("\n") { "\"${it.path}\"" } + "\n")
            val build = Files.createDirectories(work.resolve("classes"))
            log("embedded backend: compiling portable source bundle without Android or external libraries")
            val compiled = ProcessWorker.run(listOf(profile.javac.toString(), "--release", "8", "-encoding", "UTF-8", "-proc:none", "-d", build.toString(), "@sources.args"), output, 120_000, cancel = cancel)
            if (compiled.cancelled) throw CancelledException()
            Files.writeString(output.resolve("build-check.txt"), (compiled.stdout + compiled.stderr).take(64 * 1024))
            report = report.copy(sources = files, portableBuild = if (compiled.ok) "COMPILES" else "FAILED")
            if (compiled.ok) {
                val verifier = RoundTripVerifier(profile, cancel).also { it.names = names; it.renames = JadxRenames.parse(javaRoot) }
                val (summary, portableChecks) = verifier.verify(dex, build, emptyList(), emptyMap(), files.map { it.path.removePrefix("src/") }.filter { it != "RecoveredBackend.java" }.toSet(), emptyList(), 21, work.resolve("roundtrip"), 120_000)
                val compared = portableChecks.associateBy { it.method }
                if (summary.status == "MEASURED" && methodKeys.all { compared[it]?.result in RoundTripVerifier.EQUIVALENT }) {
                    Files.writeString(output.resolve("run.sh"), "#!/bin/sh\nset -eu\ncd -- \"\$(dirname -- \"\$0\")\"\njavac --release 8 -encoding UTF-8 -proc:none -d classes @sources.args\nexec java -cp classes RecoveredBackend \"\$@\"\n")
                    output.resolve("run.sh").toFile().setExecutable(true)
                    Files.writeString(output.resolve("run.cmd"), "@echo off\r\ncd /d \"%~dp0\"\r\njavac --release 8 -encoding UTF-8 -proc:none -d classes @sources.args\r\nif errorlevel 1 exit /b %errorlevel%\r\njava -cp classes RecoveredBackend %*\r\n")
                    report = report.copy(status = "RUNNABLE_ADAPTER", methods = portableChecks, runnableHandlers = candidates.map { it.method }, portableBuild = "COMPILES_AND_ROUND_TRIP_MATCHES")
                } else report = report.copy(methods = portableChecks, issues = report.issues + "Portable build did not match every original method in the exported slice; launchers withheld.")
            } else report = report.copy(issues = report.issues + "Standalone javac build failed; see build-check.txt. Launchers withheld.")
        }
        return write(output, report)
    }

    private fun safePath(path: String) = path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") && Regex("[A-Za-z0-9_$.-]+").matches(it) }
    private fun adapter(handlers: List<EmbeddedHandler>, classes: Map<String, List<Triple<String, DexFile, ClassDef>>>, names: NameMapping): String {
        val calls = handlers.map { h ->
            val (_, d, c) = classes.getValue(h.method.substringBefore("->")).single()
            val m = c.classData!!.directMethods.map { DexMethod(d, c, it) }.single { key(it) == h.method }
            val owner = (names.classes[m.owner] ?: m.owner).removePrefix("L").removeSuffix(";").replace('/', '.')
            val name = names.methods[m.owner]?.get(m.name + m.protoDescriptor) ?: m.name
            require(owner.split('.').all { identifier.matches(it) } && identifier.matches(name)) { "Unsupported portable Java identifier" }
            val args = m.proto.parameters.mapIndexed { i, t -> (if (d.type(t) == "I") "intValue" else "longValue") + "(q, \"${h.queryParameters[i]}\")" }
            "if (path.equals(\"${h.paths.single()}\")) return Long.toString($owner.$name(${args.joinToString(", ")}));"
        }.joinToString("\n")
        val template = checkNotNull(EmbeddedBackend::class.java.getResource("/backend/RecoveredBackend.java.template")).readText()
        return template.replace("/* RECOVERED_CALLS */", calls)
    }

    fun write(output: Path, report: EmbeddedBackendReport): EmbeddedBackendReport {
        Files.createDirectories(output)
        if (report.runnableHandlers.isEmpty()) {
            Files.deleteIfExists(output.resolve("run.cmd")); Files.deleteIfExists(output.resolve("run.sh"))
        }
        Files.writeString(output.resolve("report.json"), Json.encode(report))
        Files.writeString(output.resolve("README.md"), buildString {
            append("# Embedded backend logic\n\nInput SHA-256: `${report.inputSha256}`. Status: ${report.status}.\n\n${report.scope}\n\n")
            append("${report.handlers.size} retained handler declarations; ${report.sources.size} exported files. Source hashes and per-method checks are in `report.json`.\n\n")
            report.handlers.forEach { append("- `${it.method}` (${it.framework}): ${it.verbs.joinToString()} ${it.paths.joinToString()}\n") }
            if (report.runnableHandlers.isNotEmpty()) append("\nRun with a Java 17 JDK: `sh run.sh 8080` (POSIX) or `run.cmd 8080` (Windows). The listener binds to 127.0.0.1. Integer/long query parameters call the recovered static handlers; responses are decimal text. The transport, missing/invalid-parameter 400s and exception 500s are synthesized, not original framework behavior. Recovery does not start the embedded server.\n")
            if (report.status == "NOT_DETECTED") append("\nNo supported concrete handler declarations were found. This is not proof that embedded logic is absent. Remote server code cannot be recovered unless it is supplied.\n")
            append("\nDetection covers retained javax/jakarta JAX-RS class `Path` + method HTTP annotations, Spring MVC controllers/mappings, NanoHTTPD session `serve` overrides and JDK `HttpHandler.handle` implementations with concrete bodies. NanoHTTPD/JDK paths are left unknown because routing occurs at runtime. Obfuscated/stripped markers, other custom routers, native/JS servers and framework initialization need separate recovery. Android/framework-dependent code remains in the recovered app project.\n")
            append("\nRunnable export is limited to unique literal GET routes with public static int/long handlers and distinct int/long QueryParam inputs. It requires a standalone strict javac build and matching round trips for every original code method in the whole-class dependency slice. Dynamic dispatch, reflection, native methods or incomplete dependency analysis withhold launchers. Matching fingerprints is not a proof of original service, storage, authentication or deployment behavior. Editing exported files invalidates the recorded hashes/checks.\n")
            if (report.issues.isNotEmpty()) { append("\n## Dependencies requiring review\n\n"); report.issues.forEach { append("- ${it.replace('`', '\'').replace('\n', ' ')}\n") } }
        })
        return report
    }
}
