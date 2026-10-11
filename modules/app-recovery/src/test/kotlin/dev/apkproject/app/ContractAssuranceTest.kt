package dev.apkproject.app

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.*

class ContractAssuranceTest {
    @TempDir lateinit var dir: Path
    private val ref = SourceRef("dex-annotation", "classes.dex:Lexample/Api;->get")
    private fun endpoint(path: String, type: String?, method: String = "GET", params: List<EndpointParam> = emptyList()) =
        Endpoint(method, path, "get", "example.Api", params + Regex("""\{([^{}]+)\}""").findAll(path).map { it.groupValues[1] }
            .filter { name -> params.none { it.location == "path" && it.name == name } }.map { EndpointParam(it, "path", "String") }.toList(), null, type, listOf(ref))
    private fun contract(endpoints: List<Endpoint>, models: List<ModelSchema> = emptyList()) =
        ApiContract(emptyList(), endpoints, emptyList(), emptyList(), emptyList(), models, emptyList(), emptyList())
    private fun model(name: String, vararg fields: ModelField) = ModelSchema(name, fields.toList(), ref)

    @Test fun `independent validator rejects seeded wrong examples and preserves valid wire shapes`() {
        val m = model("example.Result", ModelField("id", "id", "java.lang.Integer", nullable = false),
            ModelField("visibility", "visibility", "example.Visibility", listOf("public", "private"), false),
            ModelField("optional", "optional", "String", nullable = true))
        val good = """{"id":1,"visibility":"public","optional":null}"""
        assertEquals(emptyList(), ContractExampleValidator.validate("example.Result", good, listOf(m)))
        for (bad in listOf(good.replace("\"id\":1", "\"id\":true"), good.replace("\"id\":1", "\"id\":null"),
            good.replace("public", "UNKNOWN"), good.replace("\"id\":1,", ""), good.replace("1,", "2147483648,"),
            good.replace("}", ",\"invented\":1}"), "not json")) {
            assertTrue(ContractExampleValidator.validate("example.Result", bad, listOf(m)).isNotEmpty(), bad)
        }
        assertTrue(ContractExampleValidator.validate("Map<String, Integer>", """{"x":"wrong"}""", emptyList()).isNotEmpty())
        assertTrue(ContractExampleValidator.validate("List<Boolean>", "[1]", emptyList()).isNotEmpty())
        assertEquals(emptyList(), ContractExampleValidator.validate("Map<String, List<Integer>>", """{"x":[1,2]}""", emptyList()))
    }

    @Test fun `audit withholds conflicts missing shapes and invalid recursion with evidence`() {
        val node = model("example.Node", ModelField("child", "child", "example.Node", nullable = false))
        val c = contract(listOf(endpoint("conflict", "String"), endpoint("conflict", "Integer"), endpoint("unknown", null),
            endpoint("cycle", "example.Node"), endpoint("ok", "java.lang.Integer"),
            endpoint("", "String", params = listOf(EndpointParam("url", "url", "String")))), listOf(node))
        val audit = ContractAuditor.audit(c)
        assertEquals(1, audit.checkedExamples)
        assertEquals(3, audit.withheldExamples)
        assertEquals(setOf("AMBIGUOUS_RESPONSE", "UNKNOWN_RESPONSE", "INVALID_EXAMPLE", "CHECKED_EXAMPLE"), audit.routes.map { it.status }.toSet())
        assertTrue(audit.issues.any { it.code == "UNSUPPORTED_ROUTE" })
        assertTrue(audit.routes.all { it.evidence == listOf(ref) })
        assertEquals(audit.routes.map { it.id }.toSet(), ContractAuditor.audit(c.copy(endpoints = c.endpoints.reversed())).routes.map { it.id }.toSet())
    }

    @Test fun `model identity never substitutes a different qualified class or chooses an ambiguous simple name`() {
        val a = model("first.User", ModelField("first", "first", "String"))
        val b = model("second.User", ModelField("second", "second", "String"))
        assertNull(ContractRenderer.Models(listOf(a)).find("missing.User"))
        assertNull(ContractRenderer.Models(listOf(a, b)).find("User"))
        assertNull(ContractRenderer.Models(listOf(a, a)).find("first.User"))
        assertTrue(ContractExampleValidator.validate("first.User", """{"second":"string"}""", listOf(a, b)).isNotEmpty())
        val audit = ContractAuditor.audit(contract(listOf(endpoint("x", "missing.User")), listOf(a)))
        assertEquals("UNKNOWN_RESPONSE", audit.routes.single().status)
    }

    @Test fun `custom string shapes remain open and response alternatives are checked separately`() {
        val notice = model("ex.Notice", ModelField("type", "type", "obfuscated.Type", nullable = false,
            wire = ScalarWireShape("string", listOf("mention"), listOf(ref))))
        assertEquals(emptyList(), ContractExampleValidator.validate("ex.Notice", "{\"type\":\"future_notification\"}", listOf(notice)))
        assertTrue(ContractExampleValidator.validate("ex.Notice", "{\"type\":12}", listOf(notice)).isNotEmpty())
        assertTrue(ContractExampleValidator.validate("ex.Notice", "{\"type\":null}", listOf(notice)).isNotEmpty())
        val c = contract(listOf(endpoint("post", "ex.Notice", "POST"), endpoint("post", "Missing", "POST")), listOf(notice))
        val route = ContractAuditor.audit(c).routes.single()
        assertEquals("AMBIGUOUS_RESPONSE", route.status)
        assertEquals(setOf("CHECKED_EXAMPLE", "UNKNOWN_RESPONSE"), route.responseVariants.map { it.status }.toSet())
        assertEquals(route.responseVariants.map { it.id }, ContractAuditor.audit(c.copy(endpoints = c.endpoints.reversed())).routes.single().responseVariants.map { it.id })
        assertTrue(ContractRenderer.openApi(c, "test", "1").contains("x-apkproject-known-values"))
    }

    @Test fun `branching recursive examples stop at the generation budget`() {
        val m = model("example.Tree", *Array(10) { ModelField("child$it", "child$it", "example.Tree", nullable = false) })
        assertNull(ContractRenderer.example("example.Tree", ContractRenderer.Models(listOf(m)).index(), 0))
        assertEquals("UNKNOWN_RESPONSE", ContractAuditor.audit(contract(listOf(endpoint("tree", "example.Tree")), listOf(m))).routes.single().status)
    }

    @Test fun `client selection requires checked alternatives and an exact nullable string partition`() {
        val body = model("example.Body", ModelField("when", "when", "String", nullable = true))
        val endpoints = listOf(endpoint("pick", "String", "POST").copy(bodyType = body.className,
            clientSelection = ClientSelection("when", listOf("null", "empty"), listOf(ref))),
            endpoint("pick", "Integer", "POST").copy(bodyType = body.className,
                clientSelection = ClientSelection("when", listOf("nonempty"), listOf(ref))))
        assertEquals("CHECKED_CLIENT_SELECTION", ContractAuditor.audit(contract(endpoints, listOf(body))).routes.single().status)
        for (bad in listOf(endpoints.map { it.copy(clientSelection = null) },
            endpoints.map { it.copy(clientSelection = it.clientSelection!!.copy(evidence = emptyList())) },
            endpoints.map { it.copy(clientSelection = it.clientSelection!!.copy(stringStates = listOf("null"))) },
            endpoints.map { it.copy(clientSelection = it.clientSelection!!.copy(jsonName = "missing")) },
            listOf(endpoints[0], endpoints[1].copy(responseType = "unknown")))) {
            val route = ContractAuditor.audit(contract(bad, listOf(body))).routes.single()
            assertEquals("AMBIGUOUS_RESPONSE", route.status)
            assertTrue(route.responseVariants.all { it.clientSelection == null })
        }
        assertEquals("AMBIGUOUS_RESPONSE", ContractAuditor.audit(contract(endpoints,
            listOf(body.copy(fields = listOf(ModelField("when", "when", "String", nullable = false)))))).routes.single().status)
    }

    @Test fun `extraction preserves conflicting overloads instead of merging by operation name`() {
        val root = Files.createDirectories(dir.resolve("java"))
        Files.writeString(root.resolve("Api.java"), """
            import retrofit2.http.GET;
            import retrofit2.http.Query;
            interface Api {
                @GET("result") Call<String> fetch(@Query("mode") String mode);
                @GET("result") Call<Integer> fetch(@Query("mode") int mode);
            }
        """.trimIndent())
        val c = ApiContractExtractor.extract(root, null, null, emptyList(), emptyList())
        assertEquals(2, c.endpoints.size)
        assertEquals("AMBIGUOUS_RESPONSE", ContractAuditor.audit(c).routes.single().status)
        assertContains(ContractRenderer.openApi(c, "overloads", "1"), "schema: {anyOf: [{type: string}, {type: integer}]}")
    }

    @Test fun `JSON control characters and enum wire values survive rendering and audit`() {
        val m = model("example.Strange", ModelField("quote\"\\\n\t", "x", "example.Enum", listOf("value\"\n\u0001"), false))
        val c = contract(listOf(endpoint("strange", "example.Strange")), listOf(m))
        val route = ContractAuditor.audit(c).routes.single()
        assertEquals("CHECKED_EXAMPLE", route.status)
        val json = Json.parseToJsonElement(assertNotNull(route.example)).jsonObject
        assertEquals("value\"\n\u0001", json.getValue("quote\"\\\n\t").jsonPrimitive.content)
        Files.writeString(dir.resolve("openapi.yaml"), ContractRenderer.openApi(c, "title\nnext", "1"))
        assertContains(Files.readString(dir.resolve("openapi.yaml")), "\\n")
    }

    @Test fun `OpenAPI normalizes absolute and equivalent placeholder routes and omits dynamic URLs`() {
        val c = contract(listOf(endpoint("https://api.example.com/v2/users/{id}", "String", params = listOf(EndpointParam("id", "path", "String"))),
            endpoint("v2/users/{user}", "Integer", params = listOf(EndpointParam("user", "path", "String"))),
            endpoint("", "String", params = listOf(EndpointParam("url", "url", "String"))), endpoint("empty", "Void")))
        val spec = ContractRenderer.openApi(c, "test", "1")
        assertContains(spec, "'/v2/users/{id}':")
        assertFalse("'/v2/users/{user}':" in spec)
        assertFalse("'/https://" in spec)
        assertFalse("  '/':" in spec)
        assertContains(spec, "anyOf:")
        assertContains(spec, "'204':")
        assertContains(spec, "x-apkproject-example-checks:")
    }

    @Test fun `compiled server chooses specific routes preserves query conditions and exposes uncertainty over real HTTP`() {
        val strange = model("example.Strange", ModelField("quote\"\n猫🧪", "x", "String", nullable = false))
        val cycle = model("example.Cycle", ModelField("next", "next", "example.Cycle", nullable = false))
        val nullableNode = model("example.NullableNode", ModelField("name", "name", "String", nullable = false),
            ModelField("child", "child", "example.NullableNode", nullable = true),
            ModelField("visibility", "visibility", "example.Visibility", listOf("public", "private"), nullable = true))
        val selectionBody = model("example.SelectionBody", ModelField("when", "when", "String", nullable = true))
        fun conditional(type: String, states: List<String>) = endpoint("selected", type, "POST").copy(bodyType = selectionBody.className,
            clientSelection = ClientSelection("when", states, listOf(ref)))
        val c = contract(listOf(
            endpoint("users/{id}", "Integer"), endpoint("users/search", "String"),
            endpoint("search?kind=short", "Integer"), endpoint("search?kind=full", "String"),
            endpoint("repeat?tag=a&tag=a", "String"), endpoint("conflict", "String"), endpoint("conflict", "Integer"),
            endpoint("cycle", "example.Cycle"), endpoint("unknown", "Missing"), endpoint("unknown", "String"), endpoint("empty", "Void"),
            endpoint("head", "String", "HEAD"), endpoint("strange", "example.Strange"),
            endpoint("overlap/{id}/x", "Integer"), endpoint("overlap/fixed/{name}", "String"),
            endpoint("https://api.example.com/absolute/{id}", "Integer"),
            endpoint("nullable", "example.NullableNode"),
            conditional("String", listOf("null", "empty")), conditional("Integer", listOf("nonempty")),
        ), listOf(strange, cycle, nullableNode, selectionBody))
        val src = dir.resolve("BackendSkeleton.java")
        Files.writeString(src, ContractRenderer.serverSkeleton(c))
        assertTrue(Files.readString(src).all { it.code < 128 }, "generated Java must preserve Unicode without a platform encoding dependency")
        System.getProperty("apkproject.contract.fixtureDir")?.let { outputDir ->
            val fixture = Files.createDirectories(Path.of(outputDir))
            Files.writeString(fixture.resolve("openapi.yaml"), ContractRenderer.openApi(c, "HTTP regression fixture", "1"))
            Files.writeString(fixture.resolve("contract-audit.json"), dev.apkproject.contracts.Json.encode(ContractAuditor.audit(c)))
            Files.copy(src, Files.createDirectories(fixture.resolve("server-skeleton")).resolve(src.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val output = ByteArrayOutputStream()
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, output, "-encoding", "windows-1252", "-d", dir.toString(), src.toString()), output.toString())
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val server = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", exe).toString(), "-cp", dir.toString(), "BackendSkeleton", "0")
            .redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val line = reader.submit<String> {
                val lines = server.inputStream.bufferedReader()
                generateSequence { lines.readLine() }.first { it.startsWith("Skeleton for ") }
            }.get(30, TimeUnit.SECONDS)
            val base = "http://127.0.0.1:" + line.substringAfterLast(':')
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            fun request(path: String, method: String = "GET", variant: String? = null, body: String? = null,
                        contentType: String? = "application/json"): HttpResponse<String> = client.send(
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).apply {
                    if (variant != null) header("X-Apkproject-Variant", variant)
                    if (body != null && contentType != null) header("Content-Type", contentType)
                }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals("\"string\"", request("/users/search").body(), "literal route must win even when wildcard was declared first")
            assertEquals("0", request("/users/42").body())
            assertEquals("0", request("/users/a%2Fb").body(), "an encoded slash remains within the placeholder")
            assertEquals("0", request("/search?kind=short").body())
            assertEquals("\"string\"", request("/search?kind=full").body())
            assertEquals(404, request("/search?kind=other").statusCode())
            assertEquals(404, request("/repeat?tag=a").statusCode())
            assertEquals(200, request("/repeat?tag=a&tag=a").statusCode())
            assertEquals(501, request("/conflict").statusCode())
            assertEquals("AMBIGUOUS_RESPONSE", Json.parseToJsonElement(request("/conflict").body()).jsonObject.getValue("reason").jsonPrimitive.content)
            val alternatives = ContractAuditor.audit(c).routes.single { it.path == "/conflict" }.responseVariants
            assertEquals(2, alternatives.size)
            alternatives.forEach { v ->
                val selected = request("/conflict", variant = v.id)
                assertEquals(200, selected.statusCode())
                assertEquals(v.example, selected.body())
                assertEquals(v.id, selected.headers().firstValue("X-Apkproject-Variant").orElse(""))
            }
            assertEquals(400, request("/conflict", variant = "unknown").statusCode())
            assertEquals(400, request("/users/search", variant = alternatives.first().id).statusCode())
            val uncertain = ContractAuditor.audit(c).routes.single { it.path == "/unknown" }.responseVariants
            assertEquals(501, request("/unknown", variant = uncertain.single { it.status != "CHECKED_EXAMPLE" }.id).statusCode())
            assertEquals(200, request("/unknown", variant = uncertain.single { it.status == "CHECKED_EXAMPLE" }.id).statusCode())
            val duplicateSelector = client.send(HttpRequest.newBuilder(URI.create(base + "/conflict"))
                .header("X-Apkproject-Variant", alternatives[0].id).header("X-Apkproject-Variant", alternatives[1].id).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, duplicateSelector.statusCode())
            for (body in listOf("{}", "{\"when\":null}", "{\"when\":\"\"}", "{\"nested\":{\"when\":\"later\"}}")) {
                val response = request("/selected", "POST", body = body)
                assertEquals(200, response.statusCode(), body)
                assertEquals("\"string\"", response.body())
                assertEquals("OBSERVED_CLIENT_RULE", response.headers().firstValue("X-Apkproject-Selection").orElse(""))
            }
            for (body in listOf("{\"when\":\"later\"}", "{\"wh\\u0065n\":\"later\"}", "{\"when\":\" \"}"))
                assertEquals("0", request("/selected", "POST", body = body).body(), body)
            for (body in listOf("{\"when\":3}", "{\"when\":true}", "{\"when\":[]}", "{\"when\":{}}", "null", "[]", "{} junk",
                "{\"when\":\"\",\"when\":\"later\"}", "{\"when\":\"\",\"wh\\u0065n\":\"later\"}", "{\"x\":01}", "{\"x\":1.}",
                "{\"x\":1e}", "{\"x\":tru}", "{\"x\":\"\\q\"}", "{\"x\":\"\u0001\"}", "{\"x\":[1,]}",
                "[".repeat(66) + "0" + "]".repeat(66)))
                assertEquals(400, request("/selected", "POST", body = body).statusCode(), body)
            assertEquals(415, request("/selected", "POST", body = "{}", contentType = "text/plain").statusCode())
            assertEquals(415, request("/selected", "POST", body = "{}", contentType = null).statusCode())
            assertEquals(413, request("/selected", "POST", body = "{\"x\":\"" + "x".repeat(65536) + "\"}").statusCode())
            val selectedVariants = ContractAuditor.audit(c).routes.single { it.path == "/selected" }.responseVariants
            val explicit = request("/selected", "POST", selectedVariants.single { it.responseType == "Integer" }.id, "{\"when\":null}")
            assertEquals("0", explicit.body())
            assertFalse(explicit.headers().firstValue("X-Apkproject-Selection").isPresent)
            val invalidUtf8 = client.send(HttpRequest.newBuilder(URI.create(base + "/selected")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, invalidUtf8.statusCode())
            for (path in listOf("/cycle", "/unknown", "/overlap/fixed/x")) assertEquals(501, request(path).statusCode(), path)
            assertEquals(204, request("/empty").statusCode())
            assertEquals("", request("/head", "HEAD").body())
            val wrongMethod = request("/users/search", "POST")
            assertEquals(405, wrongMethod.statusCode())
            assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElse(""))
            assertEquals(404, request("/missing").statusCode())
            assertEquals("0", request("/absolute/7").body())
            val weird = request("/strange")
            assertEquals("SYNTHESIZED", weird.headers().firstValue("X-Apkproject-Evidence").orElse(""))
            assertEquals(ContractAuditor.audit(c).contractSha256, weird.headers().firstValue("X-Apkproject-Contract").orElse(""))
            assertTrue(weird.headers().firstValue("X-Apkproject-Route").isPresent)
            assertEquals("string", Json.parseToJsonElement(weird.body()).jsonObject.getValue("quote\"\n猫🧪").jsonPrimitive.content)
            val futures = (1..20).map { client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/users/42")).GET().build(), HttpResponse.BodyHandlers.ofString()) }
            assertTrue(futures.all { it.get(10, TimeUnit.SECONDS).body() == "0" })
        } finally {
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS) }
            reader.shutdownNow()
        }
    }
}
