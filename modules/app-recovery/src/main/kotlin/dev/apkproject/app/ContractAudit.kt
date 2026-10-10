package dev.apkproject.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Serializable
data class ContractIssue(val code: String, val location: String, val detail: String, val evidence: List<SourceRef> = emptyList())

@Serializable
data class AuditedResponseVariant(
    val id: String,
    val responseType: String?,
    val operations: List<String>,
    val evidence: List<SourceRef>,
    val status: String,
    val example: String?,
    val issues: List<ContractIssue>,
    val exampleSha256: String? = example?.let { dev.apkproject.contracts.Hashing.sha256(it) },
    val clientSelection: ClientSelection? = null,
)

@Serializable
data class AuditedRoute(
    val id: String,
    val method: String,
    val path: String,
    val fixedQuery: Map<String, List<String>>,
    val operations: List<String>,
    val evidence: List<SourceRef>,
    val status: String,
    val example: String?,
    val issues: List<ContractIssue>,
    val exampleSha256: String? = example?.let { dev.apkproject.contracts.Hashing.sha256(it) },
    val responseVariants: List<AuditedResponseVariant> = emptyList(),
)

/** Structural assurance of synthesized examples, never a claim about the original server. */
@Serializable
data class ContractAudit(
    val schemaVersion: Int = 1,
    val contractSha256: String,
    val scope: String = "Client contract and synthesized examples; not original server behavior or contract completeness",
    val declarations: Int,
    val routes: List<AuditedRoute>,
    val issues: List<ContractIssue>,
    val checkedExamples: Int,
    val withheldExamples: Int,
    val checkedResponseVariants: Int = routes.sumOf { r -> r.responseVariants.count { it.status == "CHECKED_EXAMPLE" } },
    val checkedClientSelections: Int = routes.count { it.status == "CHECKED_CLIENT_SELECTION" },
)

/** One shared interpretation of Retrofit's literal route; @Url and unknown verbs stay explicit. */
internal data class ContractPath(val path: String, val query: Map<String, List<String>>) {
    companion object {
        val verbs = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")
        private val parameter = Regex("""\{[^{}]+\}""")

        fun parse(e: Endpoint): ContractPath? {
            if (e.method.uppercase() !in verbs || e.params.any { it.location == "url" }) return null
            val raw = e.path
            val pathAndQuery = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                // Braces in Retrofit placeholders are not legal URI characters; parse the authority separately.
                val rest = raw.substringAfter("://")
                val start = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) rest.length else it }
                val authority = rest.substring(0, start)
                val uri = runCatching { java.net.URI(raw.substringBefore("://") + "://" + authority) }.getOrNull()
                if (uri?.host == null) return null
                rest.substring(start).let { if (it.startsWith('/')) it else "/$it" }
            } else raw
            if (pathAndQuery.contains('#') || pathAndQuery.contains('\n') || pathAndQuery.contains('\r')) return null
            val path = "/" + pathAndQuery.substringBefore('?').removePrefix("/")
            if (parameter.replace(path, "").any { it == '{' || it == '}' }) return null
            val query = linkedMapOf<String, MutableList<String>>()
            if ('?' in pathAndQuery) {
                for (part in pathAndQuery.substringAfter('?').split('&').filter { it.isNotEmpty() }) {
                    val name = decode(part.substringBefore('=')) ?: return null
                    val value = decode(part.substringAfter('=', "")) ?: return null
                    if ('{' in name || '{' in value) return null
                    query.getOrPut(name) { mutableListOf() }.add(value)
                }
            }
            return ContractPath(path, query.toSortedMap().mapValues { it.value.sorted() })
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8) }.getOrNull()
    }
}

/** Independent of YAML rendering: checks actual JSON values against the recovered model facts. */
object ContractExampleValidator {
    fun validate(type: String, example: String, models: List<ModelSchema>): List<String> {
        val value = runCatching { Json.parseToJsonElement(example) }.getOrElse { return listOf("$: invalid JSON: ${it.message}") }
        val index = ContractRenderer.Models(models)
        val errors = arrayListOf<String>()
        var visits = 0
        fun check(v: JsonElement, raw: String, path: String, nullable: Boolean?, enums: List<String>, depth: Int) {
            if (++visits > 10_000 || depth > 64) {
                if (errors.none { "validation budget" in it }) errors += "$path: validation budget exceeded"
                return
            }
            val t = normalizedJsonType(raw)
            if (v == JsonNull) {
                if (nullable != true && t !in setOf("void", "Void", "Unit", "kotlin.Unit")) errors += "$path: null is not known to be allowed"
                return
            }
            val list = collectionElement(t)
            if (list != null) {
                if (v !is JsonArray) errors += "$path: expected an array"
                else v.forEachIndexed { i, item -> check(item, list, "$path[$i]", false, enums, depth + 1) }
                return
            }
            val map = mapValue(t)
            if (map != null || t in setOf("Map", "HashMap", "LinkedHashMap", "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap")) {
                if (v !is JsonObject) errors += "$path: expected an object"
                else if (map != null) v.forEach { (k, item) -> check(item, map, "$path.$k", false, emptyList(), depth + 1) }
                return
            }
            val p = v as? JsonPrimitive
            when {
                enums.isNotEmpty() -> if (p?.isString != true || p.content !in enums) errors += "$path: expected one of the declared enum wire values"
                t in setOf("String", "CharSequence", "Date", "java.util.Date", "Instant", "java.time.Instant") -> {
                    if (p?.isString != true) errors += "$path: expected a string"
                    else if (t in setOf("Date", "java.util.Date", "Instant", "java.time.Instant") && runCatching { java.time.Instant.parse(p.content) }.isFailure) errors += "$path: expected a date-time"
                }
                t in setOf("boolean", "Boolean") -> if (p?.isString != false || p.booleanOrNull == null) errors += "$path: expected a boolean"
                t in setOf("int", "Integer", "long", "Long", "short", "Short", "byte", "Byte") -> {
                    val n = p?.takeIf { !it.isString }?.longOrNull
                    val range = when (t) {
                        "byte", "Byte" -> -128L..127L
                        "short", "Short" -> -32768L..32767L
                        "int", "Integer" -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                        else -> Long.MIN_VALUE..Long.MAX_VALUE
                    }
                    if (n == null || n !in range) errors += "$path: expected an in-range $t"
                }
                t in setOf("double", "Double", "float", "Float", "Number") -> if (p?.isString != false || p.doubleOrNull?.isFinite() != true) errors += "$path: expected a finite number"
                t in setOf("void", "Void", "Unit", "kotlin.Unit") -> errors += "$path: expected no response value"
                else -> {
                    val m = index.find(t)
                    if (m == null) errors += "$path: unknown or ambiguous model $t"
                    else if (v !is JsonObject) errors += "$path: expected model ${m.className}"
                    else {
                        if (m.fields.map { it.jsonName }.distinct().size != m.fields.size) errors += "$path: duplicate model wire names"
                        for (f in m.fields) {
                            val item = v[f.jsonName]
                            if (item == null) errors += "$path.${f.jsonName}: synthesized example omitted a recovered property"
                            else check(item, f.wire?.let { if (it.type == "string") "String" else "?" } ?: f.javaType, "$path.${f.jsonName}", f.nullable, if (f.wire != null) emptyList() else f.enumValues, depth + 1)
                        }
                        for (k in v.keys - m.fields.map { it.jsonName }.toSet()) errors += "$path.$k: synthesized property has no recovered evidence"
                    }
                }
            }
        }
        check(value, type, "$", false, emptyList(), 0)
        return errors.take(100)
    }
}

internal fun normalizedJsonType(type: String): String = type.trim().removePrefix("final ")
    .replace("? extends ", "").replace("? super ", "").removePrefix("java.lang.")

internal fun collectionElement(type: String): String? = type.takeIf { it.endsWith("[]") }?.removeSuffix("[]")
    ?: Regex("""^(?:java\.util\.)?(?:List|ArrayList|Set|HashSet|LinkedHashSet|Collection|Iterable)<(.+)>$""").find(type)?.groupValues?.get(1)

internal fun mapValue(type: String): String? = Regex("""^(?:java\.util\.)?(?:Map|HashMap|LinkedHashMap)<\s*[^,<>]+\s*,\s*(.+)>$""").find(type)?.groupValues?.get(1)

object ContractAuditor {
    fun audit(c: ApiContract): ContractAudit {
        val issues = arrayListOf<ContractIssue>()
        val parsed = c.endpoints.mapNotNull { e ->
            val p = ContractPath.parse(e)
            if (p == null) {
                issues += ContractIssue("UNSUPPORTED_ROUTE", "${e.method} ${e.path}", "Dynamic URL, unsupported verb, or malformed literal route; no route invented", e.evidence)
                null
            } else p to e
        }
        for ((name, ms) in c.models.groupBy { it.className }.filterValues { it.size > 1 }) {
            issues += ContractIssue("DUPLICATE_MODEL", name, "Multiple recovered definitions; examples depending on this model are withheld", ms.map { it.evidence })
        }
        val index = ContractRenderer.Models(c.models).index()
        var routes = parsed.groupBy { (p, e) -> Triple(e.method.uppercase(), p.path, p.query) }.map { (key, items) ->
            val variants = items.map { it.second }
            val evidence = variants.flatMap { it.evidence }.distinct()
            val types = variants.map { it.responseType }.distinct()
            val location = "${key.first} ${key.second}"
            val local = arrayListOf<ContractIssue>()
            var status: String
            var example: String? = null
            if (types.size != 1) {
                status = "AMBIGUOUS_RESPONSE"
                local += ContractIssue(status, location, "Declared alternatives: ${types.joinToString { it ?: "unknown" }}; select a checked mock variant explicitly; original dispatch rule is not recovered", evidence)
            } else if (types.single() == null) {
                status = "UNKNOWN_RESPONSE"
                local += ContractIssue(status, location, "No recovered response type", evidence)
            } else {
                val type = types.single()!!
                val candidate = ContractRenderer.example(type, index, 0)
                val failures = candidate?.let { ContractExampleValidator.validate(type, it, c.models) }
                if (candidate == null || failures?.isNotEmpty() == true) {
                    status = if (candidate == null) "UNKNOWN_RESPONSE" else "INVALID_EXAMPLE"
                    local += ContractIssue(status, location, failures?.joinToString("; ") ?: "Unknown or ambiguous response model $type", evidence)
                } else {
                    status = "CHECKED_EXAMPLE"
                    example = candidate
                }
            }
            val id = dev.apkproject.contracts.Hashing.combine(key.first, key.second, dev.apkproject.contracts.Json.encodeLine(key.third)).take(16)
            val responses = if (types.size < 2) emptyList() else variants.groupBy { it.responseType }.map { (type, declarations) ->
                val refs = declarations.flatMap { it.evidence }.distinct()
                val candidate = type?.let { ContractRenderer.example(it, index, 0) }
                val errors = candidate?.let { ContractExampleValidator.validate(type!!, it, c.models) }
                val check = when { candidate == null -> "UNKNOWN_RESPONSE"; errors!!.isNotEmpty() -> "INVALID_EXAMPLE"; else -> "CHECKED_EXAMPLE" }
                AuditedResponseVariant(dev.apkproject.contracts.Hashing.combine(id, type ?: "unknown").take(16), type,
                    declarations.map { "${it.declaredIn}.${it.operation}" }.distinct().sorted(), refs, check,
                    candidate.takeIf { check == "CHECKED_EXAMPLE" }, if (check == "CHECKED_EXAMPLE") emptyList() else
                        listOf(ContractIssue(check, location, errors?.joinToString("; ") ?: "Unknown response shape", refs)),
                    clientSelection = declarations.map { it.clientSelection }.distinct().singleOrNull())
            }.sortedBy { it.id }
            val selections = responses.mapNotNull { it.clientSelection }
            val states = selections.flatMap { it.stringStates }
            val selectionField = selections.map { it.jsonName }.distinct().singleOrNull()
            val body = variants.map { it.bodyType }.distinct().singleOrNull()
            val field = c.models.singleOrNull { it.className == body }?.fields?.singleOrNull { it.jsonName == selectionField }
            val partition = responses.size > 1 && selections.size == responses.size && responses.all { it.status == "CHECKED_EXAMPLE" } &&
                selections.all { it.evidence.isNotEmpty() && it.stringStates.isNotEmpty() } && selectionField != null &&
                states.sorted() == listOf("empty", "nonempty", "null") && field?.javaType in setOf("String", "java.lang.String") && field?.nullable == true
            if (partition) {
                status = "CHECKED_CLIENT_SELECTION"
                local.removeAll { it.code == "AMBIGUOUS_RESPONSE" }
            }
            AuditedRoute(id, key.first, key.second, key.third, variants.map { "${it.declaredIn}.${it.operation}" }.distinct(), evidence, status, example, local,
                responseVariants = if (partition) responses else responses.map { it.copy(clientSelection = null) })
        }.sortedWith(compareBy({ it.path }, { it.method }, { it.fixedQuery.toString() }))
        val placeholder = Regex("""\{[^{}]+\}""")
        for ((_, sameShape) in routes.groupBy { it.method + " " + placeholder.replace(it.path, "{}") + " " + it.fixedQuery }.filterValues { it.size > 1 }) {
            val issue = ContractIssue("OVERLAPPING_ROUTES", sameShape.joinToString { "${it.method} ${it.path}" },
                "Equivalent route templates with different placeholder names; matching requests are withheld rather than choosing a declaration",
                sameShape.flatMap { it.evidence }.distinct())
            val affected = sameShape.map { it.id }.toSet()
            routes = routes.map { if (it.id in affected) it.copy(status = "AMBIGUOUS_ROUTE", example = null, exampleSha256 = null, responseVariants = emptyList(), issues = it.issues + issue) else it }
        }
        c.notes.filter { it.startsWith("Extraction failure:") }.forEach { issues += ContractIssue("EXTRACTION_FAILURE", "contract", it) }
        return ContractAudit(contractSha256 = dev.apkproject.contracts.Hashing.sha256(dev.apkproject.contracts.Json.encodeLine(c)),
            declarations = c.endpoints.size, routes = routes, issues = issues + routes.flatMap { it.issues },
            checkedExamples = routes.count { it.status == "CHECKED_EXAMPLE" },
            withheldExamples = routes.count { it.status !in setOf("CHECKED_EXAMPLE", "CHECKED_CLIENT_SELECTION") })
    }
}
