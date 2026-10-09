package dev.apkproject.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiContractTest {
    @TempDir
    lateinit var dir: Path

    private val service = """
        package com.example.api;

        import java.util.List;
        import retrofit2.Call;
        import retrofit2.http.Body;
        import retrofit2.http.GET;
        import retrofit2.http.HTTP;
        import retrofit2.http.Header;
        import retrofit2.http.POST;
        import retrofit2.http.Path;
        import retrofit2.http.Query;

        public interface UserService {
            @GET("users/{id}")
            Call<User> user(@Path("id") String str, @Query("expand") boolean z);

            @Headers({"Accept: application/json"})
            @POST("users")
            Call<User> create(@Body User user, @Header("Authorization") String str);

            @GET("users")
            Object list(@Query("page") int i, kotlin.coroutines.Continuation<? super List<User>> continuation);

            @HTTP(method = "DELETE", path = "users/{id}", hasBody = true)
            Call<Void> remove(@Path("id") String str);
        }
    """.trimIndent()

    private val model = """
        package com.example.api;

        public final class User {
            @SerializedName("user_id")
            private final String id;
            @com.squareup.moshi.Json(name = "display_name")
            public String name;
            @SerializedName(value = "tags", alternate = {"labels"})
            private java.util.List<String> tags;
        }
    """.trimIndent()

    @Test
    fun `retrofit declarations become endpoints with parameters and unwrapped types`() {
        val endpoints = ApiContractExtractor.retrofitEndpoints("com/example/api/UserService.java", service)
        assertEquals(listOf("GET users/{id}", "POST users", "GET users", "DELETE users/{id}"), endpoints.map { "${it.method} ${it.path}" })
        val get = endpoints[0]
        assertEquals("user", get.operation)
        assertEquals("com.example.api.UserService", get.declaredIn)
        assertEquals(listOf(EndpointParam("id", "path", "String"), EndpointParam("expand", "query", "boolean")), get.params)
        assertEquals("User", get.responseType)
        val post = endpoints[1]
        assertEquals("User", post.bodyType)
        assertEquals(listOf(EndpointParam("Authorization", "header", "String")), post.params)
        assertEquals("List<User>", endpoints[2].responseType, "suspend functions answer with the Continuation's type")
        assertEquals("com/example/api/UserService.java:14", get.evidence.single().location)
    }

    @Test
    fun `json model fields keep their wire names`() {
        val schema = ApiContractExtractor.modelSchemas("com/example/api/User.java", model).single()
        assertEquals("com.example.api.User", schema.className)
        assertEquals(
            listOf(ModelField("user_id", "id", "String"), ModelField("display_name", "name", "String"), ModelField("tags", "tags", "java.util.List<String>")),
            schema.fields,
        )
    }

    @Test
    fun `generic argument lists split only at the top level`() {
        assertEquals(listOf("Map<String, List<Integer>> a", "@Query(\"x,y\") int b"), ApiContractExtractor.splitTopLevel("Map<String, List<Integer>> a, @Query(\"x,y\") int b").let { listOf(it[0], it.drop(1).joinToString(",")) })
    }

    @Test
    fun `extraction combines code, resources and assets and masks secrets`() {
        val java = Files.createDirectories(dir.resolve("java/com/example/api"))
        Files.writeString(java.resolve("UserService.java"), service)
        Files.writeString(java.resolve("User.java"), model)
        Files.writeString(
            java.resolve("Net.java"),
            "package com.example.api;\nclass Net { Object r = new Retrofit.Builder().baseUrl(\"https://api.example.com/v2/\").build();\n String q = \"query Me { me { id } }\"; }\n",
        )
        val lib = Files.createDirectories(dir.resolve("java/okhttp3"))
        Files.writeString(lib.resolve("Lib.java"), "package okhttp3;\nclass Lib { String u = \"https://publicsuffix.org/list\"; }\n")
        val values = Files.createDirectories(dir.resolve("res/values"))
        Files.writeString(
            values.resolve("strings.xml"),
            """<resources><string name="google_app_id">1:123:android:abc</string><string name="maps_api_key">AIzaSyEXAMPLEEXAMPLE123</string><string name="cdn">https://cdn.example.com/img</string></resources>""",
        )
        val assets = Files.createDirectories(dir.resolve("assets"))
        Files.writeString(assets.resolve("config.json"), """{"socket":"wss://live.example.com/ws"}""")

        val c = ApiContractExtractor.extract(dir.resolve("java"), dir.resolve("res"), assets, emptyList(), listOf("com.example"))
        assertEquals(listOf("https://api.example.com/v2/"), c.baseUrls)
        assertEquals(4, c.endpoints.size)
        assertEquals(listOf("com.example.api.User"), c.models.map { it.className })
        assertEquals(1, c.graphqlOperations.size)
        assertEquals(listOf("api.example.com", "cdn.example.com", "live.example.com", "publicsuffix.org"), c.hosts)
        assertEquals("library-code", c.urls.single { it.host == "publicsuffix.org" }.origin)
        assertEquals("app-or-observed", c.urls.single { it.host == "live.example.com" }.origin)
        val key = c.serviceConfig.single { it.key == "maps_api_key" }
        assertTrue(key.masked)
        assertFalse(key.value.contains("EXAMPLEEXAMPLE"))
        assertEquals("1:123:android:abc", c.serviceConfig.single { it.key == "google_app_id" }.value)

        val openApi = ContractRenderer.openApi(c, "Example", "1.0")
        for (needle in listOf(
            "openapi: 3.0.3", "- url: 'https://api.example.com/v2/'", "'/users/{id}':", "operationId: 'user'", "name: 'expand'", "in: query",
            "schema: {type: array, items: {\$ref: '#/components/schemas/User'}}", "'User':", "'user_id': {type: string}", "'display_name':", "delete:",
        )) {
            assertContains(openApi, needle)
        }
        val skeleton = ContractRenderer.serverSkeleton(c)
        assertContains(skeleton, "SYNTHESIZED")
        assertContains(skeleton, "501")
        assertContains(skeleton, "users")
        // The synthesized backend must be a compilable starting point.
        val src = Files.createDirectories(dir.resolve("skeleton")).resolve("BackendSkeleton.java")
        Files.writeString(src, skeleton)
        val javac = assertNotNull(ToolProvider.getSystemJavaCompiler(), "tests run on a JDK")
        val diagnostics = ByteArrayOutputStream()
        assertEquals(0, javac.run(null, null, diagnostics, "-d", dir.resolve("skeleton-classes").toString(), src.toString()), diagnostics.toString())

        // And it serves: a recovered route answers with JSON in the recovered model's wire names.
        val port = ServerSocket(0).use { it.localPort }
        val launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val server = ProcessBuilder(launcher, "-cp", dir.resolve("skeleton-classes").toString(), "BackendSkeleton", port.toString()).redirectErrorStream(true).start()
        try {
            val client = HttpClient.newHttpClient()
            var response: HttpResponse<String>? = null
            for (attempt in 0 until 50) {
                response = runCatching {
                    client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/users/42")).GET().build(), HttpResponse.BodyHandlers.ofString())
                }.getOrNull()
                if (response != null) break
                Thread.sleep(100)
            }
            val r = assertNotNull(response, "mock server did not start")
            assertEquals(200, r.statusCode())
            assertEquals("""{"user_id":"string","display_name":"string","tags":["string"]}""", r.body())
            assertEquals("user", r.headers().firstValue("X-Apkproject-Mock").orElse(""))
        } finally {
            server.destroy()
        }
    }
}
