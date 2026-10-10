package dev.apkproject.app

import kotlinx.serialization.json.*

/** Compilable JDK-only server. Only independently checked examples can reach a successful response. */
internal object ContractServer {
    fun render(c: ApiContract): String {
        val audit = ContractAuditor.audit(c)
        val routes = audit.routes.joinToString("\n") { r ->
            val pattern = "^" + r.path.split(Regex("""\{[^{}]+\}""")).joinToString("[^/]+") { Regex.escape(it) } + "$"
            val queries = r.fixedQuery.flatMap { (k, vs) -> vs.map { k to it } }
            val original = c.endpoints.first { it.method.uppercase() == r.method && ContractPath.parse(it) == ContractPath(r.path, r.fixedQuery) }
            val error = buildJsonObject { put("status", "not implemented"); put("reason", r.status); put("routeId", r.id) }.toString()
            "        ROUTES.add(new Route(${javaString(r.method)}, ${javaString(r.path)}, ${javaString(pattern)}, " +
                "new String[]{${queries.joinToString { javaString(it.first) }}}, new String[]{${queries.joinToString { javaString(it.second) }}}, " +
                "${javaString(ContractRenderer.operationId(original))}, ${javaString(r.id)}, ${r.example?.let { javaString(it) } ?: "null"}, ${javaString(error)}));"
        }
        val template = checkNotNull(ContractServer::class.java.getResource("/backend/BackendSkeleton.java.template")) { "missing server template" }.readText()
        return template.replace("/* GENERATED_ROUTES */", routes).replace("GENERATED_CONTRACT_SHA256", audit.contractSha256)
    }

    /** Java literals also escape controls; octal avoids Java's pre-lexical Unicode newline processing. */
    private fun javaString(s: String): String = s.map { ch ->
        when (ch) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> when {
                ch.code < 32 || ch.code == 127 -> "\\" + ch.code.toString(8).padStart(3, '0')
                ch.code > 127 -> "\\u" + ch.code.toString(16).padStart(4, '0')
                else -> ch.toString()
            }
        }
    }.joinToString("", "\"", "\"")
}
