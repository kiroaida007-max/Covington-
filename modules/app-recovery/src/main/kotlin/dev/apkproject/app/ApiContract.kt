package dev.apkproject.app

import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Serializable
data class SourceRef(val kind: String, val location: String)

@Serializable
data class EndpointParam(val name: String, val location: String, val javaType: String)

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

@Serializable
data class ModelField(val jsonName: String, val javaName: String, val javaType: String)

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
            if (text.contains("retrofit2.http.")) endpoints += retrofitEndpoints(rel, text)
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
        val dexEndpoints = runCatching { BytecodeApi.endpoints(dex) }.getOrDefault(emptyList())
        val mergedEndpoints = LinkedHashMap<String, Endpoint>()
        for (e in dexEndpoints + endpoints) {
            val key = "${e.method} ${e.path} ${e.declaredIn}.${e.operation}"
            val prior = mergedEndpoints[key]
            mergedEndpoints[key] = prior?.copy(evidence = (prior.evidence + e.evidence).distinct()) ?: e
        }
        val dexModels = runCatching { BytecodeApi.models(dex) }.getOrDefault(emptyList())
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
            ),
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

/** Renders the contract as an OpenAPI 3 draft and a runnable, clearly synthesized server skeleton. */
object ContractRenderer {
    fun openApi(c: ApiContract, title: String, version: String): String = buildString {
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
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
        if (c.endpoints.isEmpty()) append(" {}\n") else append("\n")
        val known = c.models.associateBy { it.className.substringAfterLast('.') }
        for ((path, ops) in c.endpoints.groupBy { "/" + it.path.removePrefix("/").substringBefore('?') }) {
            append("  ${q(path)}:\n")
            for (e in ops.distinctBy { it.method }) {
                append("    ${e.method.lowercase()}:\n")
                append("      operationId: ${q(operationId(e))}\n")
                append("      x-apkproject-evidence: [${e.evidence.joinToString { q("${it.kind}:${it.location}") }}]\n")
                append("      x-apkproject-declared-in: ${q(e.declaredIn)}\n")
                val params = e.params.filter { it.location in setOf("path", "query", "header") }
                if (params.isNotEmpty()) {
                    append("      parameters:\n")
                    for (p in params) {
                        append("        - name: ${q(p.name)}\n          in: ${p.location}\n          required: ${p.location == "path"}\n          schema: ${schema(p.javaType, known)}\n")
                    }
                }
                val form = e.params.filter { it.location in setOf("field", "part") }
                if (e.bodyType != null || form.isNotEmpty()) {
                    append("      requestBody:\n        content:\n")
                    if (e.bodyType != null) append("          application/json:\n            schema: ${schema(e.bodyType, known)}\n")
                    if (form.isNotEmpty()) {
                        append("          application/x-www-form-urlencoded:\n            schema:\n              type: object\n              properties:\n")
                        form.forEach { append("                ${q(it.name)}: ${schema(it.javaType, known)}\n") }
                    }
                }
                append("      responses:\n        '200':\n          description: ${q("Inferred from return type ${e.responseType ?: "unknown"}")}\n")
                if (e.responseType != null && e.responseType != "Void" && e.responseType != "Unit") {
                    append("          content:\n            application/json:\n              schema: ${schema(e.responseType, known)}\n")
                }
            }
        }
        if (c.models.isNotEmpty()) {
            append("components:\n  schemas:\n")
            for (m in c.models) {
                append("    ${q(m.className.substringAfterLast('.'))}:\n      type: object\n      x-apkproject-evidence: ${q("${m.evidence.kind}:${m.evidence.location}")}\n      properties:\n")
                for (f in m.fields) append("        ${q(f.jsonName)}: ${schema(f.javaType, known)}\n")
            }
        }
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

    fun schema(javaType: String, known: Map<String, ModelSchema>): String {
        val t = javaType.trim().removePrefix("final ").replace("? extends ", "").replace("? super ", "")
        return when {
            t.endsWith("[]") -> "{type: array, items: ${schema(t.removeSuffix("[]"), known)}}"
            Regex("""^(?:java\.util\.)?(?:List|ArrayList|Set|Collection|Iterable)<(.+)>$""").matches(t) ->
                "{type: array, items: ${schema(Regex("""<(.+)>$""").find(t)!!.groupValues[1], known)}}"
            Regex("""^(?:java\.util\.)?(?:Map|HashMap)<.+>$""").matches(t) -> "{type: object}"
            t in setOf("int", "Integer", "long", "Long", "short", "Short", "byte", "Byte") -> "{type: integer}"
            t in setOf("double", "Double", "float", "Float", "Number") -> "{type: number}"
            t in setOf("boolean", "Boolean") -> "{type: boolean}"
            t in setOf("String", "java.lang.String", "CharSequence") -> "{type: string}"
            known.containsKey(t.substringAfterLast('.')) -> "{\$ref: '#/components/schemas/${t.substringAfterLast('.')}'}"
            else -> "{type: object, x-java-type: '${t.replace("'", "''")}'}"
        }
    }

    /** A dependency-free JDK HTTP server with one handler per inferred operation; every handler answers 501. */
    fun serverSkeleton(c: ApiContract): String = buildString {
        append("// SYNTHESIZED by apkproject. This is NOT the app's original server code, which is not in the APK.\n")
        append("// It is a runnable starting point shaped by the client's inferred API contract (../openapi.yaml).\n")
        append("// Every route answers 501 until you implement it. Run: java BackendSkeleton.java [port]\n\n")
        append("import com.sun.net.httpserver.HttpExchange;\nimport com.sun.net.httpserver.HttpServer;\n")
        append("import java.io.IOException;\nimport java.io.OutputStream;\nimport java.net.InetSocketAddress;\nimport java.nio.charset.StandardCharsets;\n")
        append("import java.util.ArrayList;\nimport java.util.List;\nimport java.util.regex.Pattern;\n\n")
        append("public final class BackendSkeleton {\n")
        append("    private static final class Route {\n        final String method; final Pattern path; final String operation; final String response;\n")
        append("        Route(String method, String path, String operation, String response) {\n            this.method = method; this.path = Pattern.compile(path); this.operation = operation; this.response = response;\n        }\n    }\n\n")
        append("    private static final List<Route> ROUTES = new ArrayList<>();\n\n    static {\n")
        for (e in c.endpoints.distinctBy { it.method + it.path }) {
            // Literal segments are quoted; {placeholders} match one path segment.
            val literal = e.path.removePrefix("/").substringBefore('?').split(Regex("""\{[^}]*\}"""))
            val regex = "^/" + literal.joinToString("[^/]+") { if (it.isEmpty()) "" else Regex.escape(it) } + "$"
            append("        ROUTES.add(new Route(${j(e.method)}, ${j(regex)}, ${j(e.operation)}, ${j(e.responseType ?: "unknown")}));\n")
        }
        append("    }\n\n")
        append("    public static void main(String[] args) throws IOException {\n")
        append("        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;\n")
        append("        HttpServer server = HttpServer.create(new InetSocketAddress(\"127.0.0.1\", port), 0);\n")
        append("        server.createContext(\"/\", BackendSkeleton::handle);\n        server.start();\n")
        append("        System.out.println(\"Skeleton for \" + ROUTES.size() + \" inferred operations on http://127.0.0.1:\" + port);\n    }\n\n")
        append("    private static void handle(HttpExchange exchange) throws IOException {\n")
        append("        String path = exchange.getRequestURI().getPath();\n")
        append("        for (Route r : ROUTES) {\n            if (r.method.equals(exchange.getRequestMethod()) && r.path.matcher(path).matches()) {\n")
        append("                send(exchange, 501, \"{\\\"operation\\\":\\\"\" + r.operation + \"\\\",\\\"expectedResponse\\\":\\\"\" + r.response + \"\\\",\\\"status\\\":\\\"not implemented\\\"}\");\n                return;\n            }\n        }\n")
        append("        send(exchange, 404, \"{\\\"status\\\":\\\"no inferred operation for this path\\\"}\");\n    }\n\n")
        append("    private static void send(HttpExchange exchange, int status, String json) throws IOException {\n")
        append("        byte[] body = json.getBytes(StandardCharsets.UTF_8);\n        exchange.getResponseHeaders().set(\"Content-Type\", \"application/json\");\n")
        append("        exchange.sendResponseHeaders(status, body.length);\n        try (OutputStream out = exchange.getResponseBody()) {\n            out.write(body);\n        }\n    }\n}\n")
    }

    private fun j(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun readme(c: ApiContract): String = buildString {
        append("# Backend: the client's view\n\n")
        append("An APK contains the code that **calls** a server, never the server's own code, so the backend\n")
        append("cannot be decompiled from the app. This folder holds what the app reveals about it.\n\n")
        append("| File | What it is | Evidence level |\n|---|---|---|\n")
        append("| `api-contract.json` | Every URL, host, endpoint, model and service setting found, with locations | OBSERVED (DEX strings, resources) and INFERRED (decompiled code) |\n")
        append("| `openapi.yaml` | OpenAPI 3 draft of the endpoints the app calls | INFERRED |\n")
        append("| `server-skeleton/BackendSkeleton.java` | Runnable JDK server with one 501 route per inferred operation | SYNTHESIZED (not recovered) |\n\n")
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
            c.endpoints.take(200).forEach { append("| ${it.method} | `${it.path}` | `${it.declaredIn.substringAfterLast('.')}.${it.operation}` | `${it.responseType ?: "?"}` | ${it.evidence.first().location} |\n") }
            append('\n')
        }
        append("Secret-looking values are masked. The APK ships them to every user, so treat them as public.\n")
    }
}
