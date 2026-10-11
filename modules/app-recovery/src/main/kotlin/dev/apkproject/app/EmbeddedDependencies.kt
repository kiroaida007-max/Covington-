package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.*
import java.nio.file.Files
import java.nio.file.Path

internal typealias EmbeddedClass = Triple<String, DexFile, ClassDef>

internal data class EmbeddedSlice(
    val classes: Map<String, List<EmbeddedClass>>, val files: List<EmbeddedSource>,
    val methodKeys: Map<String, Set<String>>, val methods: List<MethodRoundTrip>,
    val handlers: List<EmbeddedHandlerCheck>, val externalTypes: List<String>, val issues: List<String>,
)

/** Analyze each class once, then propagate its review requirements only to dependent handlers. */
internal object EmbeddedDependencies {
    private fun key(m: DexMethod) = "${m.owner}->${m.name}${m.protoDescriptor}"
    private fun safePath(path: String) = path.split('/').all {
        it.isNotEmpty() && it !in setOf(".", "..") && Regex("[A-Za-z0-9_$.-]+").matches(it)
    }

    fun export(dex: List<Pair<String, DexFile>>, scanned: EmbeddedBackendReport, javaRoot: Path,
               output: Path, names: NameMapping, checks: List<MethodRoundTrip>, cancel: CancellationToken): EmbeddedSlice {
        val localScanIssues = scanned.handlers.flatMap { it.issues }.toSet()
        val globalIssues = scanned.issues.filterNot { it in localScanIssues }.toMutableSet()
        val classIssues = HashMap<String, MutableSet<String>>()
        val blocked = HashSet<String>()
        var diagnosticCount = 0
        fun issue(owner: String, message: String) {
            blocked += owner
            if (diagnosticCount < 128 && classIssues.getOrPut(owner) { linkedSetOf() }.add(message)) diagnosticCount++
        }
        val classes = dex.flatMap { (part, d) -> d.classDefs.map { d.type(it.classIdx) to Triple(part, d, it) } }
            .groupBy({ it.first }, { it.second })
        fun sourcePath(type: String) = (names.classes[type] ?: type).removePrefix("L").removeSuffix(";").substringBefore('$') + ".java"
        val siblings = classes.keys.groupBy(::sourcePath)
        val queue = ArrayDeque(scanned.handlers.map { it.method.substringBefore("->") }.distinct())
        val queued = queue.toMutableSet()
        val selected = LinkedHashSet<String>()
        val edges = HashMap<String, MutableSet<String>>()
        val external = sortedSetOf<String>()
        var codeUnits = 0L
        fun add(owner: String, type: String) {
            val t = type.trimStart('[')
            if (!t.startsWith("L")) return
            if (t in classes) {
                if (t !in queued) {
                    if (queued.size >= 512) {
                        globalIssues += "Dependency class budget exceeded (512); analysis is incomplete."
                        return
                    }
                    queued += t; queue += t
                }
                edges.getOrPut(owner) { linkedSetOf() } += t
            } else {
                if (external.size < 4096 || t in external) external += t
                else globalIssues += "External type budget exceeded (4096); analysis is incomplete."
                if (!t.startsWith("Ljava/")) issue(owner, "External dependency requires its original runtime: $t")
            }
            if (t.startsWith("Ljava/lang/reflect/") || t.startsWith("Ljava/lang/invoke/"))
                issue(owner, "Reflective dependency targets cannot be established statically: $t")
        }
        fun value(owner: String, v: EncodedValue) { when (v) {
            is EncodedValue.TypeRef -> add(owner, v.descriptor)
            is EncodedValue.EnumRef -> add(owner, v.descriptor.substringBefore("->"))
            is EncodedValue.Arr -> v.values.forEach { value(owner, it) }
            is EncodedValue.Nested -> { add(owner, v.annotation.type); v.annotation.elements.values.forEach { value(owner, it) } }
            else -> Unit
        } }
        fun annotation(owner: String, a: DexAnnotation) { add(owner, a.type); a.elements.values.forEach { value(owner, it) } }
        while (queue.isNotEmpty()) {
            if (cancel.cancelled) throw CancelledException()
            val owner = queue.removeFirst()
            if (!selected.add(owner)) continue
            val entry = classes.getValue(owner).singleOrNull()
            if (entry == null) { issue(owner, "Ambiguous duplicate class: $owner"); continue }
            siblings[sourcePath(owner)].orEmpty().forEach { add(owner, it) }
            val (_, d, c) = entry
            if (c.superclassIdx != NO_INDEX) add(owner, d.type(c.superclassIdx))
            c.interfaces.forEach { add(owner, d.type(it)) }
            val a = d.annotations(c)
            (a.classAnnotations + a.fields.values.flatten() + a.methods.values.flatten() + a.parameters.values.flatten().flatten())
                .forEach { annotation(owner, it) }
            c.classData?.let { data ->
                (data.staticFields + data.instanceFields).forEach { add(owner, d.type(d.fieldIds[it.fieldIdx].typeIdx)) }
                for (m in (data.directMethods + data.virtualMethods).map { DexMethod(d, c, it) }) {
                    m.proto.parameters.forEach { add(owner, d.type(it)) }; add(owner, d.type(m.proto.returnType))
                    if (m.accessFlags and AccessFlags.NATIVE != 0) issue(owner, "Native dependency requires its original runtime: ${key(m)}")
                    val code = m.code ?: continue
                    codeUnits += code.insns.size
                    if (codeUnits > 2_000_000) {
                        globalIssues += "Instruction budget exceeded (2,000,000); analysis is incomplete."
                        break
                    }
                    val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value
                    if (decoded == null) { issue(owner, "Instruction decode failed: ${key(m)}"); continue }
                    for (i in decoded.insns) when (i.op.index) {
                        IndexKind.TYPE -> add(owner, d.type(i.index))
                        IndexKind.FIELD -> { val f = d.fieldIds[i.index]; add(owner, d.type(f.classIdx)); add(owner, d.type(f.typeIdx)) }
                        IndexKind.METHOD -> {
                            val id = d.methodIds[i.index]; add(owner, d.type(id.classIdx)); val p = d.protos[id.protoIdx]
                            p.parameters.forEach { add(owner, d.type(it)) }; add(owner, d.type(p.returnType))
                            if (i.op.mnemonic.startsWith("invoke-interface") || i.op.mnemonic.startsWith("invoke-virtual"))
                                issue(owner, "Virtual dispatch targets require runtime review: ${key(m)}@${i.address}")
                        }
                        IndexKind.CALL_SITE, IndexKind.METHOD_HANDLE, IndexKind.METHOD_AND_PROTO ->
                            issue(owner, "Dynamic invocation requires runtime review: ${key(m)}@${i.address}")
                        else -> Unit
                    }
                }
            }
            if (codeUnits > 2_000_000) break
        }
        val files = ArrayList<EmbeddedSource>()
        var bytes = 0L
        val realRoot = javaRoot.toRealPath()
        val ownersByPath = selected.groupBy(::sourcePath)
        for ((path, owners) in ownersByPath.toSortedMap()) {
            if (cancel.cancelled) throw CancelledException()
            fun sourceIssue(message: String) { owners.forEach { issue(it, message) } }
            if (!safePath(path)) { sourceIssue("Unsupported source path for embedded dependency."); continue }
            val source = javaRoot.resolve(path)
            if (!Files.isRegularFile(source) || !source.toRealPath().startsWith(realRoot)) {
                sourceIssue("Source unavailable: $path"); continue
            }
            if (Files.size(source) > 2 * 1024 * 1024) { sourceIssue("Source byte budget exceeded (2 MiB/file): $path"); continue }
            val data = Files.newInputStream(source).use { it.readNBytes(2 * 1024 * 1024 + 1) }
            if (data.size > 2 * 1024 * 1024) { sourceIssue("Source changed beyond its byte budget: $path"); continue }
            if (bytes + data.size > 32 * 1024 * 1024) {
                globalIssues += "Total source byte budget exceeded (32 MiB); export is incomplete."
                break
            }
            bytes += data.size
            val dest = output.resolve("src").resolve(path)
            Files.createDirectories(dest.parent); Files.write(dest, data)
            files += EmbeddedSource("src/$path", Hashing.sha256(data))
        }
        val methodKeys = selected.associateWith { owner -> classes[owner].orEmpty().flatMap { (_, d, c) ->
            c.classData?.let { it.directMethods + it.virtualMethods }.orEmpty().map { DexMethod(d, c, it) }
                .filter { it.code != null }.map(::key)
        }.toSet() }
        val successful = checks.filter { it.result in RoundTripVerifier.EQUIVALENT }.map { it.method }.toSet()
        methodKeys.forEach { (owner, methods) ->
            if (!successful.containsAll(methods)) issue(owner, "Some exported methods lack matching full-recovery round-trip checks: $owner")
        }
        val closures = HashMap<String, List<String>>()
        fun closure(root: String) = closures.getOrPut(root) {
            val found = linkedSetOf(root); val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                if (cancel.cancelled) throw CancelledException()
                edges[pending.removeFirst()].orEmpty().forEach { if (found.add(it)) pending += it }
            }
            found.sorted()
        }
        val handlers = scanned.handlers.map { h ->
            val dependencies = closure(h.method.substringBefore("->"))
            val notes = (globalIssues + h.issues + dependencies.flatMap { owner ->
                classIssues[owner].orEmpty().ifEmpty {
                    if (owner in blocked) setOf("Dependency requires review (diagnostic budget reached): $owner") else emptySet()
                }
            }).distinct().take(128)
            EmbeddedHandlerCheck(h.method, if (notes.isEmpty()) "CHECKED_DEPENDENCIES" else "REQUIRES_REVIEW", dependencies,
                dependencies.map { "src/${sourcePath(it)}" }.distinct().sorted(), notes)
        }
        val allMethodKeys = methodKeys.values.flatten().toSet()
        return EmbeddedSlice(classes, files, methodKeys, checks.filter { it.method in allMethodKeys }, handlers,
            external.toList(), (globalIssues + handlers.flatMap { it.issues }).distinct().take(128))
    }
}
