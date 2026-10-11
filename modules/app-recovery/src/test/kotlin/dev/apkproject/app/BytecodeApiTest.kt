package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * An app as R8 leaves it: Retrofit's annotations renamed (`ol.f` for @GET, `ol.s` for @Path, ...),
 * Kotlin's Continuation and the app's result wrapper renamed, kotlin.Unit renamed, and a Moshi
 * codegen adapter. The extractor must recover verbs, parameters, body types and models anyway.
 */
class BytecodeApiTest {
    @TempDir
    lateinit var dir: Path

    private fun ann(pkg: String, name: String, extra: String = "") =
        "$pkg/$name.java" to "package $pkg;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface $name { String value() default \"\"; $extra }\n"

    private val sources = mapOf(
        ann("ol", "b"), ann("ol", "f"), ann("ol", "g"), ann("ol", "n"), ann("ol", "o"), // DELETE GET HEAD PATCH POST
        ann("ol", "y"), ann("ol", "s"), ann("ol", "t"), ann("ol", "v"), ann("ol", "u"), // Url Path Query QueryName QueryMap
        "ol/a.java" to "package ol;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface a { }\n", // Body
        // Retrofit's RequestFactory, renamed: the order of instanceof tests is Retrofit's.
        "ol/P.java" to """
            package ol;
            import java.lang.annotation.Annotation;
            public final class P {
                static void h(String verb, String path) { }
                public static void method(Annotation a) {
                    if (a instanceof b) h("DELETE", ((b) a).value());
                    else if (a instanceof f) h("GET", ((f) a).value());
                    else if (a instanceof g) h("HEAD", ((g) a).value());
                    else if (a instanceof n) h("PATCH", ((n) a).value());
                    else if (a instanceof o) h("POST", ((o) a).value());
                }
                public static Object parameter(Annotation a, boolean gotUrl) {
                    if (a instanceof y) { if (gotUrl) throw new IllegalArgumentException("Multiple @Url method annotations found."); return a; }
                    else if (a instanceof s) { if (gotUrl) throw new IllegalArgumentException("@Path can only be used with relative url on @%s"); return a; }
                    else if (a instanceof t) { return ((t) a).value(); }
                    else if (a instanceof v) { return ((v) a).value(); }
                    else if (a instanceof u) { throw new IllegalArgumentException("@QueryMap parameter type must be Map."); }
                    else if (a instanceof ol.a) { throw new IllegalArgumentException("Multiple @Body method annotations found."); }
                    return null;
                }
            }
        """.trimIndent(),
        "vi/c.java" to "package vi;\npublic interface c<T> { }\n", // kotlin.coroutines.Continuation
        "t9/c.java" to "package t9;\npublic final class c<T> { }\n", // the app's NetworkResult<T>
        "qi/m.java" to "package qi;\npublic final class m { public static final m a = new m(); public String toString() { return \"kotlin.Unit\"; } }\n",
        "com/example/api/User.java" to "package com.example.api;\npublic final class User { public User(String id, int followers) { } }\n",
        "com/example/api/UserJsonAdapter.java" to """
            package com.example.api;
            public final class UserJsonAdapter {
                private final Object options;
                public UserJsonAdapter() { this.options = java.util.Arrays.asList(new String[] {"id", "followers_count"}); }
            }
        """.trimIndent(),
        "hg/c.java" to """
            package hg;
            import ol.*;
            public interface c {
                @f("api/v1/users/{id}") Object a(@s("id") String id, @t("expand") Boolean expand, vi.c<? super t9.c<com.example.api.User>> k);
                @o("api/v1/users/{id}/follow") Object b(@s("id") String id, @ol.a com.example.api.User body, vi.c<? super t9.c<qi.m>> k);
                @b("api/v1/users/{id}") Object c(@s("id") String id, vi.c<? super java.util.List<com.example.api.User>> k);
            }
        """.trimIndent(),
    )

    @Test
    fun `endpoints and models survive obfuscation`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        val build = ForwardCompiler(profile).compile(sources, dir, 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)

        val roles = BytecodeApi.roles(dex)
        assertEquals(mapOf("Lol/b;" to "DELETE", "Lol/f;" to "GET", "Lol/g;" to "HEAD", "Lol/n;" to "PATCH", "Lol/o;" to "POST"), roles.verbs)
        // Query and QueryName have no anchor string of their own; their position between Path and QueryMap names them.
        assertEquals(mapOf("Lol/y;" to "Url", "Lol/s;" to "Path", "Lol/t;" to "Query", "Lol/v;" to "QueryName", "Lol/u;" to "QueryMap", "Lol/a;" to "Body"), roles.params)

        val endpoints = BytecodeApi.endpoints(dex, roles).associateBy { "${it.method} ${it.path}" }
        val get = endpoints.getValue("GET api/v1/users/{id}")
        assertEquals(listOf(EndpointParam("id", "path", "String"), EndpointParam("expand", "query", "Boolean")), get.params)
        assertEquals("com.example.api.User", get.responseType, "Continuation and the result wrapper are unwrapped")
        val follow = endpoints.getValue("POST api/v1/users/{id}/follow")
        assertEquals("com.example.api.User", follow.bodyType)
        assertEquals("Unit", follow.responseType, "kotlin.Unit is recognised under its R8 name")
        assertEquals("List<com.example.api.User>", endpoints.getValue("DELETE api/v1/users/{id}").responseType)
        assertEquals("getUsersById", ContractRenderer.operationId(get))

        val model = BytecodeApi.models(dex).single { it.className == "com.example.api.User" }
        assertEquals(listOf(ModelField("id", "id", "String"), ModelField("followers_count", "followers_count", "int")), model.fields)
    }

    /** Signature and wrapper-unwrapping helpers produce the expected Java-style type strings. */
    @Test
    fun `generic signatures become readable types`() {
        assertEquals("retrofit2.Call<List<com.x.User>>", Signatures.javaType("Lretrofit2/Call<Ljava/util/List<Lcom/x/User;>;>;"))
        assertEquals("Map<String, Integer>", Signatures.javaType("Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;"))
        assertEquals("com.x.Outer.Inner", Signatures.javaType("-Lcom/x/Outer\$Inner;"), "JSON has no variance: wildcards are dropped")
        assertEquals("int[]", Signatures.javaType("[I"))
        assertEquals(listOf("Ljava/lang/String;", "I", "Lkotlin/coroutines/Continuation<-Lcom/x/User;>;"), Signatures.parameters("(Ljava/lang/String;ILkotlin/coroutines/Continuation<-Lcom/x/User;>;)Ljava/lang/Object;"))
        assertEquals("com.x.User", BytecodeApi.unwrap("retrofit2.Response<com.x.User>"))
        assertEquals("List<com.x.User>", BytecodeApi.unwrap("t9.c<List<com.x.User>>"))
        assertEquals("com.example.Page<com.x.User>", BytecodeApi.unwrap("com.example.Page<com.x.User>"), "a named app type is not a call wrapper")
    }
}
