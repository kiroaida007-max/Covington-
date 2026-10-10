package dev.apkproject.app

import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class SourceRef(val kind: String, val location: String)

/**
 * One parameter of an endpoint. [nameSource] says where its wire name comes from: the Retrofit
 * annotation (`annotation`), the call sites that build an unnamed multipart part (`call-site`,
 * INFERRED), or nowhere (`unnamed`, name is a placeholder).
 */
@Serializable
data class EndpointParam(val name: String, val location: String, val javaType: String, val nameSource: String = "annotation")

@Serializable
data class Endpoint(
    val method: String,
    val path: String,
    val operation: String,
    val declaredIn: String,
    val params: List<EndpointParam>,
    val bodyType: String?,
    val responseType: String?,
    val evidence: List<SourceRef>,
)

@Serializable
data class ObservedUrl(val url: String, val host: String, val origin: String, val evidence: List<SourceRef>)

/**
 * A JSON property. [enumValues] lists the wire values when the type is an enum (or a collection of
 * one); [nullable] is known for Moshi codegen models (their reader rejects null only for non-null
 * properties) and null when the bytecode does not say.
 */
@Serializable
data class ModelField(val jsonName: String, val javaName: String, val javaType: String, val enumValues: List<String> = emptyList(), val nullable: Boolean? = null)

@Serializable
data class ModelSchema(val className: String, val fields: List<ModelField>, val evidence: SourceRef)

@Serializable
data class ConfigValue(val key: String, val value: String, val masked: Boolean, val evidence: SourceRef)

@Serializable
data class ApiContract(
    val baseUrls: List<String>,
    val endpoints: List<Endpoint>,
    val urls: List<ObservedUrl>,
    val hosts: List<String>,
    val graphqlOperations: List<SourceRef>,
    val models: List<ModelSchema>,
    val serviceConfig: List<ConfigValue>,
    val notes: List<String>,
)

/**
 * The client's view of its backend. An APK contains the code that *calls* a server, never the
 * server's own code. This extractor recovers what the client reveals: base URLs and literal URLs
 * (from the DEX string table, observed), Retrofit interface declarations, OkHttp/WebView URL calls,
 * GraphQL documents, JSON model classes and cloud service configuration (from decompiled source and
 * resources, inferred).
 */
object ApiContractExtractor {
    private val URL = Regex("""\b(?:https?|wss?)://[A-Za-z0-9.-]+(?::\d{1,5})?(?:/[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%{}-]*)?""")
    private val HTTP_ANNOTATION = Regex("""@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)""")
    private val HTTP_GENERIC = Regex("""@HTTP\(([^)]*)\)""")
    private val PARAM_ANNOTATION = Regex("""@(Path|Query|QueryMap|Header|HeaderMap|Field|FieldMap|Part|PartMap|Body|Url|Tag)(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?""")
    private val SERIALIZED = Regex("""@(?:[\w.]+\.)?(?:SerializedName|Json|SerialName|JsonProperty)\(\s*(?:name\s*=\s*|value\s*=\s*)?"([^"]+)"[^)]*\)""")
    private val GRAPHQL = Regex("""^\s*(query|mutation|subscription)\s+\w+[\s({]""")
    private val SERVICE_KEYS = listOf(
        "google_app_id", "gcm_defaultSenderId", "default_web_client_id", "firebase_database_url", "google_api_key",
        "google_crash_reporting_api_key", "google_storage_bucket", "project_id", "com.google.firebase.crashlytics.mapping_file_id",
    )
    private val SECRET_KEYS = Regex("""(?i)(api_?key|secret|token|password|client_secret)""")

    fun extract(javaRoot: Path, resRoot: Path?, assetsRoot: Path?, dex: List<Pair<String, DexFile>>, appPackages: List<String>): ApiContract {
        val urls = LinkedHashMap<String, MutableList<SourceRef>>()
        fun addUrl(raw: String, ref: SourceRef) {
            val u = raw.trimEnd('.', ',', ')', ';', '\'')
            if (u.length < 10 || u.contains("schemas.android.com") || u.contains("www.w3.org") || u.contains("xmlpull.org")) return
            urls.getOrPut(u) { ArrayList() } += ref
        }
        // 1. Observed: literal strings in the DEX string tables (independent of any decompiler).
        for ((name, d) in dex) for (s in d.strings) URL.findAll(s).forEach { addUrl(it.value, SourceRef("dex-string", name)) }

        // 2. Decompiled source: Retrofit interfaces, URL calls, GraphQL documents, JSON models.
        val endpoints = ArrayList<Endpoint>()
        val sourceImports = HashMap<String, Map<String, String>>()
        val baseUrls = LinkedHashSet<String>()
        val graphql = ArrayList<SourceRef>()
        val models = ArrayList<ModelSchema>()
        val files = if (Files.isDirectory(javaRoot)) Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.sorted().toList() } else emptyList()
        for (f in files) {
            val rel = javaRoot.relativize(f).toString().replace('\\', '/')
            val text = Files.readString(f)
            val lines = text.lines()
            lines.forEachIndexed { i, line ->
                URL.findAll(line).forEach { addUrl(it.value, SourceRef("decompiled", "$rel:${i + 1}")) }
                Regex("""\.baseUrl\(\s*"([^"]+)"\s*\)""").findAll(line).forEach { baseUrls += it.groupValues[1] }
                if (GRAPHQL.containsMatchIn(line.substringAfter('"', ""))) graphql += SourceRef("decompiled", "$rel:${i + 1}")
            }
            if (text.contains("retrofit2.http.")) {
                val extracted = retrofitEndpoints(rel, text)
                val imports = Regex("""(?m)^\s*import\s+([\w.$]+);""").findAll(text).map { it.groupValues[1] }.associateBy { it.substringAfterLast('.') }
                extracted.forEach { sourceImports[it.declaredIn] = imports }
                endpoints += extracted
            }
            if (SERIALIZED.containsMatchIn(text)) models += modelSchemas(rel, text)
        }

        // 3. Resources and assets: service configuration and URLs.
        val config = ArrayList<ConfigValue>()
        if (resRoot != null && Files.isDirectory(resRoot)) {
            Files.walk(resRoot).use { s -> s.filter { it.isRegularFile() && it.extension == "xml" && it.parent.fileName.toString().startsWith("values") }.toList() }.forEach { f ->
                val rel = "res/" + resRoot.relativize(f).toString().replace('\\', '/')
                val text = Files.readString(f)
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(text).forEach { m ->
                    val key = m.groupValues[1]
                    val value = m.groupValues[2].trim()
                    URL.findAll(value).forEach { addUrl(it.value, SourceRef("resource", rel)) }
                    if (key in SERVICE_KEYS || (SECRET_KEYS.containsMatchIn(key) && value.length >= 8)) {
                        val secret = SECRET_KEYS.containsMatchIn(key)
                        config += ConfigValue(key, if (secret) mask(value) else value, secret, SourceRef("resource", rel))
                    }
                }
            }
        }
        if (assetsRoot != null && Files.isDirectory(assetsRoot)) {
            Files.walk(assetsRoot).use { s -> s.filter { it.isRegularFile() && it.extension in setOf("json", "js", "html", "xml", "properties", "txt") && Files.size(it) < 16L * 1024 * 1024 }.toList() }.forEach { f ->
                val rel = "assets/" + assetsRoot.relativize(f).toString().replace('\\', '/')
                URL.findAll(Files.readString(f, Charsets.ISO_8859_1)).forEach { addUrl(it.value, SourceRef("asset", rel)) }
            }
        }
        val observed = urls.map { (u, refs) ->
            val host = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            val app = refs.any { r -> r.kind != "decompiled" || QualityScanner.origin(r.location, appPackages) == "app" }
            ObservedUrl(u, host, if (app) "app-or-observed" else "library-code", refs.distinctBy { it.kind + it.location }.take(5))
        }.sortedWith(compareBy({ it.origin }, { it.host }, { it.url }))
        val hosts = observed.map { it.host }.filter { it.contains('.') }.distinct().sorted()
        // Bytecode-level endpoints and models (OBSERVED; independent of the decompiler and of
        // obfuscation) are merged with what the decompiled source shows.
        val extractionFailures = arrayListOf<String>()
        val dexEndpoints = runCatching { BytecodeApi.endpoints(dex) }.getOrElse {
            extractionFailures += "Extraction failure: DEX endpoints (${it.javaClass.simpleName}: ${it.message})"
            emptyList()
        }
        val mergedEndpoints = LinkedHashMap<String, Endpoint>()
        for (e in dexEndpoints + endpoints) {
            // Keep overloads and conflicting declarations. A method name alone is not a signature.
            fun signatureType(raw: String?): String? = raw?.let { type ->
                Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").replace(type) { token ->
                    val t = token.value
                    val common = setOf("String", "Object", "Void", "Boolean", "Integer", "Long", "Short", "Byte", "Float", "Double", "Number", "CharSequence",
                        "List", "ArrayList", "Set", "HashSet", "LinkedHashSet", "Collection", "Iterable", "Map", "HashMap", "LinkedHashMap")
                    when {
                        t.removePrefix("java.lang.").removePrefix("java.util.") in common -> t.substringAfterLast('.')
                        '.' in t || t.first().isLowerCase() -> t
                        sourceImports[e.declaredIn]?.containsKey(t) == true -> sourceImports.getValue(e.declaredIn).getValue(t)
                        else -> e.declaredIn.substringBeforeLast('.', "") + "." + t
                    }
                }.replace("? extends ", "").replace("? super ", "").replace(" ", "")
            }
            val signature = e.params.map { listOf(it.location, it.name, signatureType(it.javaType)) } + listOf(listOf(signatureType(e.bodyType), signatureType(e.responseType)))
            val key = "${e.method} ${e.path} ${e.declaredIn}.${e.operation} " + dev.apkproject.contracts.Json.encodeLine(signature)
            val prior = mergedEndpoints[key]
            mergedEndpoints[key] = prior?.copy(evidence = (prior.evidence + e.evidence).distinct()) ?: e
        }
        val dexModels = runCatching { BytecodeApi.models(dex) }.getOrElse {
            extractionFailures += "Extraction failure: DEX models (${it.javaClass.simpleName}: ${it.message})"
            emptyList()
        }
        val mergedModels = (dexModels + models).groupBy { it.className }.map { (_, ms) -> ms.first() }
        return ApiContract(
            baseUrls = baseUrls.toList(),
            endpoints = mergedEndpoints.values.sortedWith(compareBy({ it.path }, { it.method })),
            urls = observed,
            hosts = hosts,
            graphqlOperations = graphql,
            models = mergedModels.sortedBy { it.className },
            serviceConfig = config,
            notes = listOf(
                "This is the client's view of the backend: requests the app can make and data shapes it expects.",
                "Server-side source code is not contained in an APK and cannot be recovered from one.",
                "Values whose keys look like secrets are masked; the APK ships them to every user, so treat them as already public and rotate if needed.",
            ) + extractionFailures,
        )
    }

    private fun mask(v: String): String = if (v.length <= 8) "****" else v.take(4) + "...(" + v.length + " chars)"

    /** Retrofit interface methods: `@GET("users/{id}") Call<User> get(@Path("id") String id);` */
    fun retrofitEndpoints(rel: String, text: String): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        val lines = text.lines()
        var i = 0
        val className = rel.removeSuffix(".java").replace('/', '.')
        while (i < lines.size) {
            val ann = HTTP_ANNOTATION.find(lines[i])
            val generic = if (ann == null) HTTP_GENERIC.find(lines[i]) else null
            if (ann == null && generic == null) {
                i++; continue
            }
            val method = ann?.groupValues?.get(1) ?: Regex("""method\s*=\s*"(\w+)"""").find(generic!!.groupValues[1])?.groupValues?.get(1) ?: "GET"
            val path = ann?.groupValues?.get(2) ?: Regex("""path\s*=\s*"([^"]*)"""").find(generic!!.groupValues[1])?.groupValues?.get(1) ?: ""
            // The declaration may follow further annotations and can span several lines.
            val decl = StringBuilder()
            var j = i
            val firstLineRest = lines[i].substring((ann ?: generic)!!.range.last + 1)
            decl.append(firstLineRest)
            while (!decl.contains(';') && j + 1 < lines.size && j - i < 20) {
                j++; decl.append(' ').append(lines[j].trim())
            }
            val sig = decl.toString().substringBefore(';').replace(Regex("""@(?:Headers|FormUrlEncoded|Multipart|Streaming)(?:\([^)]*\))?"""), "").trim()
            val nameMatch = Regex("""(\S.*?)\s+(\w+)\s*\((.*)\)\s*(?:throws.*)?$""").find(sig)
            if (nameMatch != null) {
                val returnType = nameMatch.groupValues[1].replace(Regex("""^(?:public|abstract|default|\s)+"""), "").trim()
                val params = splitTopLevel(nameMatch.groupValues[3]).mapNotNull { p ->
                    val a = PARAM_ANNOTATION.find(p) ?: return@mapNotNull null
                    val rest = p.substring(a.range.last + 1).trim().split(Regex("""\s+"""))
                    val type = rest.dropLast(1).joinToString(" ").ifEmpty { rest.firstOrNull() ?: "?" }
                    val loc = a.groupValues[1].lowercase()
                    EndpointParam(a.groupValues[2].ifEmpty { rest.lastOrNull() ?: "body" }, loc, type)
                }
                val continuation = continuationType(nameMatch.groupValues[3])
                out += Endpoint(
                    method = method.uppercase(),
                    path = path,
                    operation = nameMatch.groupValues[2],
                    declaredIn = className,
                    params = params.filter { it.location != "body" && it.location != "tag" },
                    bodyType = params.firstOrNull { it.location == "body" }?.javaType,
                    responseType = continuation ?: unwrap(returnType),
                    evidence = listOf(SourceRef("decompiled", "$rel:${i + 1}")),
                )
            }
            i = j + 1
        }
        return out
    }

    /** `Continuation<? super List<User>>` (a Kotlin suspend function) -> `List<User>`, with nested generics balanced. */
    private fun continuationType(params: String): String? {
        val m = Regex("""Continuation<\?\s*super\s+""").find(params) ?: return null
        var depth = 1
        for (k in m.range.last + 1 until params.length) {
            when (params[k]) {
                '<' -> depth++
                '>' -> if (--depth == 0) return params.substring(m.range.last + 1, k).trim()
            }
        }
        return null
    }

    private fun unwrap(t: String): String {
        val inner = Regex("""^(?:retrofit2\.)?(?:Call|Single|Observable|Flowable|Maybe|Response|Deferred|LiveData|Flow)<(.+)>$""").find(t)?.groupValues?.get(1)
        return inner?.let(::unwrap) ?: t
    }

    fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in s.withIndex()) {
            when (c) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ',' -> if (depth == 0) {
                    out += s.substring(start, i); start = i + 1
                }
            }
        }
        if (s.substring(start).isNotBlank()) out += s.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Fields annotated with Gson/Moshi/kotlinx/Jackson names, per class in the file. */
    fun modelSchemas(rel: String, text: String): List<ModelSchema> {
        val lines = text.lines()
        val fields = ArrayList<ModelField>()
        var firstLine = 0
        for ((i, line) in lines.withIndex()) {
            val m = SERIALIZED.find(line) ?: continue
            val decl = (line.substring(m.range.last + 1) + " " + lines.getOrElse(i + 1) { "" }).trim()
            val f = Regex("""(?:(?:public|private|protected|final|transient|volatile|static)\s+)*([\w.$<>\[\], ?]+?)\s+(\w+)\s*[;=]""").find(decl) ?: continue
            if (fields.isEmpty()) firstLine = i + 1
            fields += ModelField(m.groupValues[1], f.groupValues[2], f.groupValues[1].trim())
        }
        if (fields.isEmpty()) return emptyList()
        return listOf(ModelSchema(rel.removeSuffix(".java").replace('/', '.'), fields.distinctBy { it.jsonName }, SourceRef("decompiled", "$rel:$firstLine")))
    }
}

/** The contract with Java type names translated (obfuscated names to the names given in the recovered source). */
fun ApiContract.renamedTypes(classes: Map<String, String>): ApiContract {
    if (classes.isEmpty()) return this
    // Nested classes may be written with '$' (binary name) or '.' (source name): both spellings are mapped.
    val dotted = HashMap<String, String>()
    for ((o, n) in classes) {
        val binary = o.removePrefix("L").removeSuffix(";").replace('/', '.') to n.removePrefix("L").removeSuffix(";").replace('/', '.')
        dotted[binary.first] = binary.second
        dotted[binary.first.replace('$', '.')] = binary.second.replace('$', '.')
    }
    val name = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""")
    fun t(s: String?) = s?.let { name.replace(it) { m -> dotted[m.value] ?: m.value } }
    return copy(
        endpoints = endpoints.map { e -> e.copy(bodyType = t(e.bodyType), responseType = t(e.responseType), declaredIn = t(e.declaredIn)!!, params = e.params.map { it.copy(javaType = t(it.javaType)!!) }) },
        models = models.map { m -> m.copy(className = t(m.className)!!, fields = m.fields.map { it.copy(javaType = t(it.javaType)!!) }) },
    )
}

/** Renders the contract as an OpenAPI 3 draft and a runnable, clearly synthesized server skeleton. */
object ContractRenderer {
    private fun yamlString(s: String): String = if (s.any { it.code < 32 || it.code == 127 }) JsonPrimitive(s).toString()
        else "'" + s.replace("'", "''") + "'"

    /** OpenAPI cannot express two paths differing only in placeholder names. Keep their declarations. */
    private fun openApiEndpoints(c: ApiContract): List<Endpoint> {
        val placeholders = Regex("""\{([^{}]+)\}""")
        val canonical = linkedMapOf<String, String>()
        return c.endpoints.mapNotNull { e ->
            val parsed = ContractPath.parse(e) ?: return@mapNotNull null
            val key = placeholders.replace(parsed.path, "{}")
            val path = canonical.getOrPut(key) { parsed.path }
            val names = placeholders.findAll(parsed.path).map { it.groupValues[1] }.zip(placeholders.findAll(path).map { it.groupValues[1] }).toMap()
            val params = e.params.map { if (it.location == "path") it.copy(name = names[it.name] ?: it.name) else it }
            val fixed = parsed.query.keys.filter { name -> params.none { it.location == "query" && it.name == name } }
                .map { EndpointParam(it, "query", "String", "literal-query") }
            e.copy(path = path, params = params + fixed)
        }
    }

    fun openApi(c: ApiContract, title: String, version: String): String = buildString {
        fun q(s: String) = yamlString(s)
        append("# Inferred by apkproject from the Android client. This is the client's view of the server,\n")
        append("# not the server's source code. Each operation lists the evidence it was inferred from.\n")
        append("openapi: 3.0.3\n")
        append("info:\n  title: ${q("$title API (inferred from client)")}\n  version: ${q(version)}\n")
        // Servers: explicit base URLs, else observed URLs that look like API roots. A client that is
        // configured at runtime (e.g. a federated app) has none, and none are invented.
        val servers = c.baseUrls.ifEmpty {
            c.urls.filter { it.origin == "app-or-observed" && (it.host.startsWith("api.") || it.url.contains("/api/")) && it.host !in LIBRARY_HOSTS }
                .map { it.url.substringBefore("?").let { u -> u.substring(0, u.indexOf('/', u.indexOf("://") + 3).let { i -> if (i < 0) u.length else i }) } }.distinct().take(5)
        }
        if (servers.isNotEmpty()) {
            append("servers:\n")
            servers.forEach { append("  - url: ${q(it)}\n") }
        }
        append("paths:")
        val exported = openApiEndpoints(c)
        if (exported.isEmpty()) append(" {}\n") else append("\n")
        val models = Models(c.models)
        val audit = ContractAuditor.audit(c)
        val usedIds = HashSet<String>()
        for ((path, ops) in exported.groupBy { it.path }) {
            append("  ${q(path)}:\n")
            // Several declarations may share a method and path (with and without an auth header, or
            // answering different models): one operation, the union of their parameters, alternative responses.
            for ((method, variants) in ops.groupBy { it.method.uppercase() }) {
                val e = variants.first()
                append("    ${method.lowercase()}:\n")
                append("      operationId: ${q(uniqueId(operationId(e), e, usedIds))}\n")
                append("      x-apkproject-evidence: [${variants.flatMap { it.evidence }.distinct().joinToString { q("${it.kind}:${it.location}") }}]\n")
                append("      x-apkproject-declared-in: ${q(e.declaredIn)}\n")
                val shape = Regex("""\{[^{}]+\}""")
                val audited = audit.routes.filter { it.method == method && shape.replace(it.path, "{}") == shape.replace(path, "{}") }
                append("      x-apkproject-example-checks: ${dev.apkproject.contracts.Json.encodeLine(audited.map { mapOf("routeId" to it.id, "status" to it.status) })}\n")
                append("      x-apkproject-fixed-query-variants: ${dev.apkproject.contracts.Json.encodeLine(audited.map { it.fixedQuery }.distinct())}\n")
                // The fullest variant's parameters (unnamed parts stay separate), then any the others add.
                val fullest = variants.maxBy { it.params.size }.params
                val all = fullest + variants.flatMap { it.params }.filter { p -> fullest.none { it.location == p.location && it.name == p.name } }.distinctBy { it.location + ":" + it.name }
                val params = all.filter { it.location in setOf("path", "query", "header") }.distinctBy {
                    it.location + ":" + if (it.location == "header") it.name.lowercase() else it.name
                }
                val queryMaps = all.filter { it.location == "querymap" }
                if (params.isNotEmpty() || queryMaps.isNotEmpty()) {
                    append("      parameters:\n")
                    for (p in params) {
                        val types = variants.flatMap { it.params }.filter {
                            it.location == p.location && (it.name == p.name || p.location == "header" && it.name.equals(p.name, ignoreCase = true))
                        }.map { schema(it.javaType, models) }.distinct()
                        // A header every variant sends is still optional: the app decides per call.
                        append("        - name: ${q(p.name)}\n          in: ${p.location}\n          required: ${p.location == "path"}\n          schema: ${alternatives(types)}\n")
                    }
                    for ((k, p) in queryMaps.withIndex()) {
                        append("        - name: ${q(if (k == 0) "query" else "query${k + 1}")}\n          in: query\n          required: false\n          style: form\n          explode: true\n")
                        append("          schema: {type: object, additionalProperties: true}\n          x-apkproject-query-map: ${q(p.javaType)}\n")
                    }
                }
                val form = all.filter { it.location == "field" }
                val parts = all.filter { it.location == "part" }
                val formMap = all.any { it.location == "fieldmap" }
                val partMap = all.any { it.location == "partmap" }
                val bodies = variants.mapNotNull { it.bodyType }.distinct()
                if (bodies.isNotEmpty() || form.isNotEmpty() || parts.isNotEmpty() || formMap || partMap) {
                    append("      requestBody:\n        content:\n")
                    if (bodies.isNotEmpty()) append("          application/json:\n            schema: ${alternatives(bodies.map { schema(it, models) })}\n")
                    if (form.isNotEmpty() || formMap) {
                        append("          application/x-www-form-urlencoded:\n            schema:\n              type: object\n")
                        if (formMap) append("              additionalProperties: true\n")
                        if (form.isNotEmpty()) {
                            append("              properties:\n")
                            form.forEach { append("                ${q(it.name)}: ${schema(it.javaType, models)}\n") }
                        }
                    }
                    if (parts.isNotEmpty() || partMap) {
                        append("          multipart/form-data:\n            schema:\n              type: object\n")
                        if (partMap) append("              additionalProperties: true\n")
                        if (parts.isNotEmpty()) {
                            append("              properties:\n")
                            for ((k, part) in parts.withIndex()) {
                                // A @Part without a name carries its name inside the MultipartBody.Part built at the call site.
                                val unnamed = part.nameSource == "unnamed"
                                val name = if (unnamed) "part${k + 1}" else part.name
                                // OkHttp's Part and RequestBody (renamed or not) are opaque bytes; strings, numbers and models are not.
                                val simple = part.javaType.substringAfterLast('.')
                                val binary = simple !in setOf("String", "int", "Integer", "long", "Long", "boolean", "Boolean", "double", "Double", "float", "Float") && models.find(part.javaType) == null
                                append("                ${q(name)}: ${if (binary) "{type: string, format: binary}" else schema(part.javaType, models)}")
                                if (unnamed) append(" # unnamed @Part: its name is set where the request is built")
                                else if (part.nameSource == "call-site") append(" # name INFERRED from the call sites that build this part")
                                append("\n")
                            }
                        }
                    }
                }
                val noBody = setOf("Void", "Unit", "void", "java.lang.Void", "kotlin.Unit")
                val responses = variants.mapNotNull { it.responseType }.distinct().filter { it !in noBody }
                append("      responses:\n")
                if (responses.isNotEmpty() || variants.any { it.responseType == null }) {
                    append("        '200':\n          description: ${q("Synthesized example status; shape inferred from ${variants.map { it.responseType ?: "unknown" }.distinct().joinToString(" or ")}")}\n")
                    if (responses.isNotEmpty()) append("          content:\n            application/json:\n              schema: ${alternatives(responses.map { schema(it, models) })}\n")
                }
                if (variants.any { it.responseType in noBody }) append("        '204':\n          description: 'Synthesized empty response for a void return type'\n")
                append("        '501':\n          description: 'Synthesized backend withheld an unknown, ambiguous or invalid example'\n")
            }
        }
        if (c.models.isNotEmpty()) {
            append("components:\n  schemas:\n")
            for (m in c.models.distinctBy { it.className }) {
                append("    ${q(models.component(m))}:\n      type: object\n      x-apkproject-evidence: ${q("${m.evidence.kind}:${m.evidence.location}")}\n")
                if (m.className.substringAfterLast('.') != models.component(m)) append("      x-java-type: ${q(m.className)}\n")
                append("      properties:")
                if (m.fields.isEmpty()) append(" {}")
                append("\n")
                for (f in m.fields.distinctBy { it.jsonName }) append("        ${q(f.jsonName)}: ${schema(f.javaType, models, f.enumValues).let { if (f.nullable == true) nullable(it) else it }}\n")
            }
        }
    }

    /** OpenAPI 3.0 cannot put `nullable` beside a `$ref`, so a nullable reference is wrapped in allOf. */
    private fun nullable(schema: String) =
        if (schema.startsWith("{\$ref")) "{nullable: true, allOf: [$schema]}" else schema.replaceFirst("{", "{nullable: true, ")

    private fun alternatives(schemas: List<String>) = schemas.distinct().let { if (it.size == 1) it[0] else "{anyOf: [${it.joinToString(", ")}]}" }

    /** operationIds must be unique: a clash (e.g. /api/v1/instance and /api/v2/instance) takes the path's version, then a number. */
    private fun uniqueId(base: String, e: Endpoint, used: MutableSet<String>): String {
        if (used.add(base)) return base
        val version = Regex("""(?:^|/)(v\d+)(?:/|$)""").find(e.path)?.groupValues?.get(1)?.uppercase()
        if (version != null && used.add(base + version)) return base + version
        var k = 2
        while (!used.add("$base$k")) k++
        return "$base$k"
    }

    /**
     * Models by name: full class names always; simple names only where they are unambiguous
     * (`entity.Translation` and `Instance.Configuration.Translation` both exist in Tusky).
     * Component names are the nested class name, or the full name when two classes share it.
     */
    class Models(val all: List<ModelSchema>) {
        private val byFull = all.groupBy { it.className }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        private val bySimple = all.groupBy { it.className.substringAfterLast('.') }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        private val nested = all.associateWith { m -> m.className.split('.').dropWhile { it.firstOrNull()?.isUpperCase() != true }.joinToString(".").ifEmpty { m.className } }
        private val clash = nested.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        fun find(javaType: String): ModelSchema? = byFull[javaType] ?: byFull[javaType.replace('$', '.')] ?: javaType.takeIf { '.' !in it && '$' !in it }?.let { bySimple[it] }

        fun component(m: ModelSchema): String = nested.getValue(m).let { if (it in clash) m.className else it }.let { raw ->
            if (Regex("""^[A-Za-z0-9._-]+$""").matches(raw)) raw
            else raw.replace(Regex("""[^A-Za-z0-9._-]"""), "_") + "_" + dev.apkproject.contracts.Hashing.sha256(m.className).take(12)
        }

        /** For [ContractRenderer.example]: full names, plus simple names where unambiguous. */
        fun index(): Map<String, ModelSchema> = bySimple + byFull + byFull.mapKeys { it.key.replace('$', '.') }
    }

    private val LIBRARY_HOSTS = setOf("www.w3.org", "schemas.android.com", "apache.org", "www.apache.org", "github.com", "developer.android.com", "goo.gl", "google.com", "www.google.com")

    /** The declared method name, or (when R8 shortened it) one built from the verb and the path. */
    fun operationId(e: Endpoint): String {
        if (e.operation.length > 3) return e.operation
        val parts = e.path.removePrefix("/").split('/').filter { it.isNotEmpty() && !Regex("""^v\d+$""").matches(it) && it != "api" }
        val words = parts.map { p ->
            if (p.startsWith("{")) "By" + p.trim('{', '}').replaceFirstChar { it.uppercase() }
            else p.split('_', '-', '.').joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        return e.method.lowercase() + words.joinToString("").ifEmpty { "Root" }
    }

    fun schema(javaType: String, models: Models, enumValues: List<String> = emptyList()): String {
        val t = normalizedJsonType(javaType)
        val list = Regex("""^(?:java\.util\.)?(?:List|ArrayList|Set|HashSet|LinkedHashSet|Collection|Iterable)<(.+)>$""").find(t)?.groupValues?.get(1)
        val map = Regex("""^(?:java\.util\.)?(?:Map|HashMap|LinkedHashMap)<\s*[^,<>]+\s*,\s*(.+)>$""").find(t)?.groupValues?.get(1)
        return when {
            t.endsWith("[]") -> "{type: array, items: ${schema(t.removeSuffix("[]"), models, enumValues)}}"
            list != null -> "{type: array, items: ${schema(list, models, enumValues)}}"
            map != null -> "{type: object, additionalProperties: ${schema(map, models)}}"
            // Enum values read from the bytecode (@Json names, else constant names).
            enumValues.isNotEmpty() -> "{type: string, enum: [${enumValues.joinToString(", ") { yamlString(it) }}]}"
            Regex("""^(?:java\.util\.)?(?:Map|HashMap|LinkedHashMap)(?:<.*>)?$""").matches(t) -> "{type: object}"
            t in setOf("int", "Integer", "long", "Long", "short", "Short", "byte", "Byte") -> "{type: integer}"
            t in setOf("double", "Double", "float", "Float", "Number") -> "{type: number}"
            t in setOf("boolean", "Boolean") -> "{type: boolean}"
            t in setOf("String", "java.lang.String", "CharSequence") -> "{type: string}"
            t in setOf("Date", "java.util.Date", "Instant", "java.time.Instant") -> "{type: string, format: date-time}"
            models.find(t) != null -> "{\$ref: '#/components/schemas/${models.component(models.find(t)!!)}'}"
            else -> "{type: object, x-java-type: ${yamlString(t)}}"
        }
    }

    /** Checked examples only; uncertainty remains in contract-audit.json and HTTP 501 responses. */
    fun serverSkeleton(c: ApiContract): String = ContractServer.render(c)

    /** Example JSON for a Java type, using recovered models; null when the shape is unknown. */
    fun example(javaType: String, known: Map<String, ModelSchema>, depth: Int): String? {
        return try { exampleValue(javaType, known, depth, ExampleBudget()) } catch (_: ExampleLimit) { null }
    }

    private class ExampleLimit : RuntimeException()
    private class ExampleBudget {
        var nodes = 0
        fun visit() { if (++nodes > 10_000) throw ExampleLimit() }
        fun size(chars: Int) { if (chars > 256 * 1024) throw ExampleLimit() }
    }

    private fun exampleValue(javaType: String, known: Map<String, ModelSchema>, depth: Int, budget: ExampleBudget): String? {
        budget.visit()
        val t = normalizedJsonType(javaType)
        val list = collectionElement(t)
        // An element of unknown shape (or past the depth limit) leaves the list empty rather than [null];
        // deep lists stay empty, which every list type accepts.
        if (list != null) return "[" + (if (depth > 2) "" else exampleValue(list, known, depth + 1, budget)?.takeIf { it != "null" } ?: "") + "]"
        if (Regex("""^(?:java\.util\.)?(?:Map|HashMap|LinkedHashMap)<.+>$""").matches(t)) return "{}"
        val model = known[t] ?: known[t.replace('$', '.')] ?: t.takeIf { '.' !in it && '$' !in it }?.let { known[it] }
        return when (t.substringAfterLast('.')) {
            "Unit", "Void", "void" -> "null"
            "String", "CharSequence" -> "\"string\""
            "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte" -> "0"
            "double", "Double", "float", "Float", "Number" -> "0.0"
            "boolean", "Boolean" -> "false"
            "Date", "Instant" -> "\"1970-01-01T00:00:00Z\""
            // Models nest (a status holds its reblog, a status). Where nullability is known, nested
            // nullable models and lists are null and non-null ones are always filled (the app's
            // reader rejects null there); otherwise models beyond three levels are null.
            else -> model?.let { m ->
                if (depth > MAX_DEPTH || (depth > 3 && m.fields.none { it.nullable != null })) "null"
                else buildString {
                    append('{')
                    for ((i, f) in m.fields.withIndex()) {
                        if (i > 0) append(',')
                        append(JsonPrimitive(f.jsonName).toString()).append(':').append(fieldExample(f, known, depth, budget))
                        budget.size(length)
                    }
                    append('}')
                }
            }
        }
    }

    private const val MAX_DEPTH = 10

    private fun fieldExample(f: ModelField, known: Map<String, ModelSchema>, depth: Int, budget: ExampleBudget): String {
        enumExample(f)?.let { return it }
        val simple = setOf("String", "CharSequence", "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte", "double", "Double", "float", "Float", "Number", "boolean", "Boolean", "Date", "Instant")
        if (f.nullable == true && depth >= 1 && f.javaType.substringAfterLast('.') !in simple) return "null"
        return exampleValue(f.javaType, known, depth + 1, budget) ?: "null"
    }

    /** An enum field's first wire value (in a one-element list for a collection of enums). */
    private fun enumExample(f: ModelField): String? {
        // A fallback constant without a wire name (UNKNOWN) is spelled in capitals among lowercase wire names.
        val v = f.enumValues.firstOrNull { it != it.uppercase() } ?: f.enumValues.firstOrNull() ?: return null
        val quoted = JsonPrimitive(v).toString()
        return if (collectionElement(normalizedJsonType(f.javaType)) != null) "[$quoted]" else quoted
    }

    fun readme(c: ApiContract): String = buildString {
        append("# Backend: the client's view\n\n")
        append("An APK contains the code that **calls** a server, never the server's own code, so the backend\n")
        append("cannot be decompiled from the app. This folder holds what the app reveals about it.\n\n")
        append("| File | What it is | Evidence level |\n|---|---|---|\n")
        append("| `api-contract.json` | Every URL, host, endpoint, model and service setting found, with locations | OBSERVED (DEX strings, resources) and INFERRED (decompiled code) |\n")
        append("| `openapi.yaml` | OpenAPI 3 draft of the endpoints the app calls | INFERRED |\n| `contract-audit.json` | Per-route evidence, structural example checks and unresolved conflicts; not a proof of server behavior | OBSERVED + INFERRED; examples SYNTHESIZED |\n")
        append("| `server-skeleton/BackendSkeleton.java` | Runnable JDK mock server: one route per inferred operation, answering independently checked example JSON (501 for unknown, conflicting or invalid examples) | SYNTHESIZED (not recovered) |\n\n")
        append("Found: ${c.endpoints.size} declared endpoints, ${c.baseUrls.size} base URLs, ${c.hosts.size} hosts, ")
        append("${c.models.size} JSON model classes, ${c.graphqlOperations.size} GraphQL documents, ${c.serviceConfig.size} service settings.\n\n")
        if (c.hosts.isNotEmpty()) {
            append("## Hosts\n\n")
            c.hosts.take(50).forEach { append("- `$it`\n") }
            if (c.hosts.size > 50) append("- … ${c.hosts.size - 50} more in api-contract.json\n")
            append('\n')
        }
        if (c.endpoints.isNotEmpty()) {
            append("## Declared endpoints\n\n| Method | Path | Operation | Response | Source |\n|---|---|---|---|---|\n")
            c.endpoints.take(200).forEach { append("| ${it.method} | `${it.path}` | `${it.declaredIn.substringAfterLast('.')}.${it.operation}` | `${it.responseType ?: "?"}` | ${it.evidence.firstOrNull()?.location ?: "no source reference"} |\n") }
            append('\n')
        }
        append("Secret-looking values are masked. The APK ships them to every user, so treat them as public.\n")
    }
}
