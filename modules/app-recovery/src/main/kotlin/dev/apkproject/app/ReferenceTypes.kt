package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI

/** Source attribution is additional input, never a name recovered from the APK. */
@Serializable
data class ReferenceTypeIdentity(
    val name: String,
    val repository: String,
    val revision: String,
    val sourcePath: String,
    val sourceSha256: String,
    val apkSha256: String,
    val sourceProperty: String,
    val scope: String = "REFERENCE_SOURCE: declaration in supplied source; not bytecode identity verification",
)

@Serializable
data class ReferenceTypeBinding(
    val model: String,
    val property: String,
    val bytecodeType: String,
    val referenceType: String,
    val sourcePath: String,
    val sourceSha256: String,
)

@Serializable
data class ReferenceTypeManifest(
    val schemaVersion: Int = 1,
    val apkSha256: String,
    val repository: String,
    val revision: String,
    val bindings: List<ReferenceTypeBinding>,
)

/**
 * Opt-in source names for otherwise unnamed model field types. The original javaType stays
 * intact. APK/source hashes bind this attribution to exact inputs, not to an asserted build
 * equivalence. A small Kotlin declaration reader checks the property and nested type; unsupported
 * syntax, competing identities, missing bytecode evidence and escaped paths fail explicitly.
 */
object ReferenceTypes {
    private val hash = Regex("[a-f0-9]{64}")
    private val qualified = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun apply(contract: ApiContract, apkSha256: String, manifestPath: Path, sourceRoot: Path): ApiContract {
        val manifest = Json.decode<ReferenceTypeManifest>(read(manifestPath, 64 * 1024))
        require(manifest.schemaVersion == 1) { "Unsupported reference-type manifest version" }
        require(hash.matches(apkSha256) && manifest.apkSha256 == apkSha256) { "Reference types do not match this input's SHA-256" }
        val uri = runCatching { URI(manifest.repository) }.getOrNull()
        require(manifest.repository.length <= 2048 && uri?.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Reference repository must be an HTTPS URL without credentials, query or fragment" }
        require(Regex("[a-f0-9]{40}|[a-f0-9]{64}").matches(manifest.revision)) { "Reference revision must be an exact commit hash" }
        require(manifest.bindings.size in 1..128) { "Reference manifest must contain 1 to 128 bindings" }
        require(manifest.bindings.map { it.model to it.property }.distinct().size == manifest.bindings.size) { "Duplicate reference property binding" }
        require(manifest.bindings.groupBy { it.bytecodeType }.values.all { bs -> bs.map { it.referenceType }.distinct().size == 1 }) { "Competing source identities for a bytecode type" }
        require(manifest.bindings.groupBy { it.referenceType }.values.all { bs -> bs.map { it.bytecodeType }.distinct().size == 1 }) { "Multiple bytecode types claim one source identity" }
        val root = sourceRoot.toRealPath()
        val source = HashMap<String, Pair<String, KotlinDeclarations>>()
        var total = 0L
        val attributed = HashMap<Pair<String, String>, ReferenceTypeIdentity>()
        for (b in manifest.bindings) {
            require(listOf(b.model, b.bytecodeType, b.referenceType).all { it.length <= 1024 && qualified.matches(it) } && b.property.length <= 128 && identifier.matches(b.property)) { "Unsupported reference type or property spelling" }
            require(hash.matches(b.sourceSha256)) { "Invalid reference source SHA-256" }
            require(b.sourcePath.endsWith(".kt") && b.sourcePath.split('/').all { Regex("[A-Za-z0-9_.-]+").matches(it) && it != "." && it != ".." }) { "Reference source path must be a relative Kotlin path" }
            val checked = source.getOrPut(b.sourcePath) {
                val path = root.resolve(b.sourcePath).toRealPath()
                require(path.startsWith(root)) { "Reference source path escapes its root" }
                val bytes = readBytes(path, 2 * 1024 * 1024)
                total += bytes.size
                require(total <= 8 * 1024 * 1024) { "Reference source exceeds the total byte budget" }
                Hashing.sha256(bytes) to KotlinDeclarations(decode(bytes))
            }
            require(checked.first == b.sourceSha256) { "Reference source hash differs: ${b.sourcePath}" }
            require(checked.second.propertyType(b.model, b.property) == b.referenceType && checked.second.defines(b.referenceType)) { "Reference source does not declare ${b.model}.${b.property} as ${b.referenceType}" }
            val model = contract.models.singleOrNull { it.className == b.model }
                ?: throw IllegalArgumentException("Reference model is missing or ambiguous: ${b.model}")
            val field = model.fields.singleOrNull { it.javaName == b.property }
                ?: throw IllegalArgumentException("Reference property is missing or ambiguous: ${b.model}.${b.property}")
            require(field.javaType == b.bytecodeType) { "Reference bytecode type differs for ${b.model}.${b.property}" }
            require(model.evidence.kind == "dex-moshi-adapter" && field.evidence.any { it.kind == "dex-model-field" }) { "Reference property has no direct bytecode model evidence" }
            val identity = ReferenceTypeIdentity(b.referenceType, manifest.repository, manifest.revision, b.sourcePath,
                checked.first, apkSha256, "${b.model}.${b.property}")
            require(field.referenceType == null || field.referenceType == identity) { "Reference property already has a different attribution" }
            attributed[b.model to b.property] = identity
        }
        return contract.copy(models = contract.models.map { m -> m.copy(fields = m.fields.map { f ->
            attributed[m.className to f.javaName]?.let { f.copy(referenceType = it) } ?: f
        }) }, notes = (contract.notes + "${attributed.size} type names attributed to supplied reference source. APK types and extraction scores remain separate; source hashes do not prove original build or server identity.").distinct())
    }

    private fun readBytes(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path)) { "Reference input is not a regular file" }
        return Files.newInputStream(path).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) { "Reference input exceeds its byte budget" } }
    }
    private fun read(path: Path, limit: Int) = decode(readBytes(path, limit))
    private fun decode(bytes: ByteArray) = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Reference input is not UTF-8") }

    /** Limited to explicit Kotlin primary-constructor properties and same-file named types. */
    private class KotlinDeclarations(raw: String) {
        private val text = mask(raw)
        private val close = delimiters(text)
        private val pkg = Regex("(?m)^\\s*package\\s+([\\w.]+)\\s*$").find(text)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Reference source has no explicit package")
        private val imports = Regex("(?m)^\\s*import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?\\s*$").findAll(text)
            .map { it.groupValues[2].ifEmpty { it.groupValues[1].substringAfterLast('.') } to it.groupValues[1] }.toList()
        private data class Decl(val name: String, val start: Int, val params: Int?, val body: Int?, val end: Int)
        private val declarations = Regex("\\b(?:class|interface|object)\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(text).map { m ->
            require(m.groupValues[1].length <= 128 && pkg.length <= 1024) { "Reference declaration name budget exceeded" }
            var at = skip(m.range.last + 1)
            val params = at.takeIf { text.getOrNull(it) == '(' }
            if (params != null) at = skip(close.getValue(params) + 1)
            // Unsupported inheritance/header syntax cannot invent a primary property.
            val body = at.takeIf { text.getOrNull(it) == '{' }
            Decl(m.groupValues[1], m.range.first, params, body, body?.let { close.getValue(it) } ?: (params?.let { close.getValue(it) } ?: m.range.last))
        }.toList().also { require(it.size <= 512) { "Too many reference declarations" } }
        private val parents by lazy { declarations.associateWith { d ->
            declarations.filter { it !== d && it.body != null && d.start > it.body && d.end < it.end }.minByOrNull { it.end - it.start }
        } }
        private val names = HashMap<Decl, String>()
        private val byName by lazy { declarations.groupBy { name(it) } }
        private fun skip(start: Int): Int { var i = start; while (i < text.length && text[i].isWhitespace()) i++; return i }
        private fun owner(d: Decl): Decl? = parents[d]
        private fun name(d: Decl): String = names.getOrPut(d) { owner(d)?.let { name(it) + "." + d.name } ?: "$pkg.${d.name}" }
        fun defines(type: String) = byName[type]?.size == 1
        fun propertyType(model: String, property: String): String? {
            val d = byName[model]?.singleOrNull() ?: return null
            val start = d.params ?: return null
            val header = text.substring(start + 1, close.getValue(start))
            val found = Regex("\\b(?:val|var)\\s+${Regex.escape(property)}\\s*:\\s*([A-Za-z_][A-Za-z0-9_.]*)(\\??)(?=\\s*(?:[,=)]|$))").findAll(header)
                .filter { m -> close.none { (open, end) -> open > start && open < start + 1 + m.range.first && end >= start + 1 + m.range.first } }
                .toList().singleOrNull() ?: return null
            val type = found.groupValues[1]
            if ('.' in type) return type.takeIf { defines(it) } ?: "$pkg.$type".takeIf { defines(it) }
            val imported = imports.filter { it.first == type }.map { it.second }.distinct()
            if (imported.size > 1) return null
            var scope: Decl? = d
            while (scope != null) {
                val candidate = name(scope) + "." + type
                if (defines(candidate)) return candidate.takeIf { imported.isEmpty() || imported.single() == candidate }
                scope = owner(scope)
            }
            if (imported.size == 1) return imported.single()
            return "$pkg.$type".takeIf { defines(it) }
        }

        companion object {
            private fun mask(raw: String): String {
                val out = raw.toCharArray(); var i = 0
                fun blank(a: Int, b: Int) { for (k in a until b) if (out[k] != '\n' && out[k] != '\r') out[k] = ' ' }
                while (i < raw.length) {
                    val begin = i
                    when {
                        raw.startsWith("//", i) -> { i = raw.indexOf('\n', i).takeIf { it >= 0 } ?: raw.length; blank(begin, i) }
                        raw.startsWith("/*", i) -> {
                            i += 2; var depth = 1
                            while (i < raw.length && depth > 0) when {
                                raw.startsWith("/*", i) -> { depth++; i += 2 }
                                raw.startsWith("*/", i) -> { depth--; i += 2 }
                                else -> i++
                            }
                            require(depth == 0) { "Unterminated reference comment" }; blank(begin, i)
                        }
                        raw.startsWith("\"\"\"", i) -> { val end = raw.indexOf("\"\"\"", i + 3); require(end >= 0) { "Unterminated reference string" }; require('$' !in raw.substring(i + 3, end)) { "Interpolated reference literals are unsupported" }; i = end + 3; blank(begin, i) }
                        raw[i] == '"' || raw[i] == '\'' -> {
                            val quote = raw[i++]; var ended = false
                            while (i < raw.length) { val c = raw[i++]; if (c == '\\') i++ else if (c == quote) { ended = true; break } }
                            require(ended && i <= raw.length) { "Unterminated reference literal" }; blank(begin, i)
                            require('$' !in raw.substring(begin + 1, i - 1)) { "Interpolated reference literals are unsupported" }
                        }
                        else -> i++
                    }
                }
                require('`' !in out) { "Backtick identifiers are unsupported in reference types" }
                return String(out)
            }
            private fun delimiters(text: String): Map<Int, Int> {
                val stack = ArrayDeque<Pair<Char, Int>>(); val result = HashMap<Int, Int>()
                for ((i, c) in text.withIndex()) when (c) {
                    '(', '{', '[' -> { require(stack.size < 256) { "Reference nesting budget exceeded" }; stack.addLast(c to i) }
                    ')', '}', ']' -> { val top = stack.removeLastOrNull(); require(top != null && "({[".indexOf(top.first) == ")}]".indexOf(c)) { "Unbalanced reference source" }; require(result.size < 100_000) { "Reference delimiter budget exceeded" }; result[top.second] = i }
                }
                require(stack.isEmpty()) { "Unbalanced reference source" }
                return result
            }
        }
    }
}
