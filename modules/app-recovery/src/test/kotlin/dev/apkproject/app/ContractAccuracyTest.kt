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
import kotlin.test.assertTrue

/**
 * The bytecode shapes behind the errors measured on Tusky (benchmarks/backend-contract), rebuilt
 * small. Moshi is renamed as R8 renames it there: `th.z` Moshi, `th.k` JsonAdapter, `th.r` writer,
 * `th.o` reader, `th.e0` Types, `vh.f` Util, `la.a` Options, `th.h` the @Json annotation.
 */
class ContractAccuracyTest {
    @TempDir
    lateinit var dir: Path

    private val sources = mapOf(
        "retrofit2/http/POST.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME) public @interface POST { String value() default \"\"; }\n",
        "retrofit2/http/Part.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME) public @interface Part { String value() default \"\"; }\n",
        "retrofit2/http/Multipart.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME) public @interface Multipart { }\n",
        // OkHttp, renamed: MultipartBody.Part (p0.c) with createFormData (a), RequestBody (p0.b).
        "p0/b.java" to "package p0;\npublic final class b { }\n",
        "p0/c.java" to "package p0;\npublic final class c { public static c a(String name, String filename, b body) { return new c(); } }\n",
        "com/example/api/UploadApi.java" to """
            package com.example.api;
            import retrofit2.http.*;
            public interface UploadApi {
                @Multipart @POST("api/v2/media") Object upload(@Part p0.c file, @Part p0.c description, @Part("caption") p0.b caption);
            }
        """.trimIndent(),
        // The call site builds the unnamed parts; the optional one is null on the other path.
        "com/example/app/Uploader.java" to """
            package com.example.app;
            public final class Uploader {
                public static Object send(com.example.api.UploadApi api, String text, p0.b body) {
                    p0.c file = p0.c.a("file", "x.png", body);
                    p0.c description = text != null ? p0.c.a("description", null, body) : null;
                    return api.upload(file, description, body);
                }
            }
        """.trimIndent(),
        // Moshi, renamed.
        "th/h.java" to "package th;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME) public @interface h { String name(); boolean ignore() default false; }\n",
        "th/r.java" to "package th;\npublic class r { public r q(String name) { return this; } }\n",
        "th/o.java" to "package th;\npublic class o { public boolean u() { return false; } public int U(la.a options) { return -1; } }\n",
        "th/k.java" to "package th;\npublic abstract class k { public abstract Object b(o reader); public abstract void e(r writer, Object value); }\n",
        "th/z.java" to "package th;\npublic class z { public k a(java.lang.reflect.Type type, java.util.Set<?> annotations, String name) { return null; } }\n",
        "th/e0.java" to "package th;\npublic final class e0 { public static java.lang.reflect.Type g(Class<?> raw, java.lang.reflect.Type... args) { return raw; } }\n",
        "la/a.java" to "package la;\npublic final class a { public static a Z(String... keys) { return new a(); } }\n",
        "vh/f.java" to "package vh;\npublic final class f { public static RuntimeException k(String property, String json, th.o reader) { return new RuntimeException(json); } }\n",
        // R8 outlines "write a value, then the next name" into a static helper.
        "vh/g.java" to "package vh;\npublic final class g { public static void o(Object value, th.k adapter, th.r writer, String next) { adapter.e(writer, value); writer.q(next); } }\n",
        // An enum whose wire names survive on the renamed @Json.
        "dg/r.java" to "package dg;\npublic enum r { UNKNOWN, @th.h(name = \"public\") PUBLIC, @th.h(name = \"unlisted\") UNLISTED }\n",
        // R8's proto normalization reorders the constructor: (aspect, width) for keys width, aspect.
        "com/example/api/Size.java" to "package com.example.api;\npublic final class Size { public final int width; public final float aspect; public Size(float aspect, int width) { this.width = width; this.aspect = aspect; } }\n",
        "com/example/api/SizeJsonAdapter.java" to """
            package com.example.api;
            public final class SizeJsonAdapter extends th.k {
                private final la.a a = la.a.Z("width", "aspect");
                private final th.k b;
                private final th.k c;
                public SizeJsonAdapter(th.z moshi) {
                    b = moshi.a(Integer.TYPE, java.util.Collections.emptySet(), "width");
                    c = moshi.a(Float.TYPE, java.util.Collections.emptySet(), "aspect");
                }
                public Object b(th.o reader) {
                    Object width = b.b(reader);
                    if (width == null) throw vh.f.k("width", "width", reader);
                    Object aspect = c.b(reader);
                    if (aspect == null) throw vh.f.k("aspect", "aspect", reader);
                    return new Size((Float) aspect, (Integer) width);
                }
                public void e(th.r w, Object value) {
                    Size s = (Size) value;
                    w.q("width");
                    b.e(w, Integer.valueOf(s.width));
                    w.q("aspect");
                    c.e(w, Float.valueOf(s.aspect));
                }
            }
        """.trimIndent(),
        "com/example/api/Post.java" to """
            package com.example.api;
            public final class Post {
                public final String id; public final dg.r visibility; public final java.util.List<String> tags;
                public final Post reblog; public final String spoilerText; public final Size size;
                public Post(String id, dg.r visibility, java.util.List<String> tags, Post reblog, String spoilerText, Size size) {
                    this.id = id; this.visibility = visibility; this.tags = tags; this.reblog = reblog; this.spoilerText = spoilerText; this.size = size;
                }
                @th.h(name = "spoiler_text") public static void getSpoilerText${'$'}annotations() { }
            }
        """.trimIndent(),
        "com/example/api/PostJsonAdapter.java" to """
            package com.example.api;
            public final class PostJsonAdapter extends th.k {
                private final la.a a = la.a.Z("id", "visibility", "tags", "reblog", "spoiler_text", "size");
                private final th.k b, c, d, e, f;
                public PostJsonAdapter(th.z moshi) {
                    java.util.Set<?> none = java.util.Collections.emptySet();
                    b = moshi.a(String.class, none, "id");
                    c = moshi.a(dg.r.class, none, "visibility");
                    d = moshi.a(th.e0.g(java.util.List.class, new java.lang.reflect.Type[] { String.class }), none, "tags");
                    e = moshi.a(Post.class, none, "reblog");
                    f = moshi.a(Size.class, none, "size");
                }
                // Keys are loaded once, before the loop, and checked in its branches.
                public Object b(th.o reader) {
                    String kId = "id", kVisibility = "visibility", kTags = "tags", kSize = "size";
                    Object id = null, visibility = null, tags = null, size = null;
                    while (reader.u()) {
                        switch (reader.U(a)) {
                            case 0: id = b.b(reader); if (id == null) throw vh.f.k(kId, kId, reader); break;
                            case 1: visibility = c.b(reader); if (visibility == null) throw vh.f.k(kVisibility, kVisibility, reader); break;
                            case 2: tags = d.b(reader); if (tags == null) throw vh.f.k(kTags, kTags, reader); break;
                            case 3: e.b(reader); break;
                            case 4: b.b(reader); break;
                            case 5: size = f.b(reader); if (size == null) throw vh.f.k(kSize, kSize, reader); break;
                            default: break;
                        }
                    }
                    return null;
                }
                public void e(th.r w, Object value) {
                    Post p = (Post) value;
                    w.q("id");
                    th.k strings = b;
                    strings.e(w, p.id);
                    w.q("visibility");
                    c.e(w, p.visibility);
                    w.q("tags");
                    d.e(w, p.tags);
                    w.q("reblog");
                    vh.g.o(p.reblog, e, w, "spoiler_text");
                    strings.e(w, p.spoilerText);
                    w.q("size");
                    f.e(w, p.size);
                }
            }
        """.trimIndent(),
    )

    private fun dex(): List<Pair<String, DexFile>> {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        val build = ForwardCompiler(profile).compile(sources, dir, 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        return listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)
    }

    @Test
    fun `models are typed from the adapter, not from a reordered constructor`() {
        val models = BytecodeApi.models(dex()).associateBy { it.className }
        val size = models.getValue("com.example.api.Size").fields.associateBy { it.jsonName }
        assertEquals("int", size.getValue("width").javaType, "the constructor's first parameter is the float")
        assertEquals("float", size.getValue("aspect").javaType)

        val post = models.getValue("com.example.api.Post").fields.associateBy { it.jsonName }
        assertEquals(ModelField("id", "id", "String", nullable = false), post.getValue("id"))
        // The renamed @Json is recognised by its members; wire names in declaration order.
        assertEquals(ModelField("visibility", "visibility", "dg.r", listOf("UNKNOWN", "public", "unlisted"), nullable = false), post.getValue("visibility"))
        assertEquals("List<String>", post.getValue("tags").javaType, "newParameterizedType with R8's Class parameter")
        // The outlined helper writes reblog and then names spoiler_text.
        assertEquals(ModelField("reblog", "reblog", "com.example.api.Post", nullable = true), post.getValue("reblog"))
        assertEquals(ModelField("spoiler_text", "spoilerText", "String", nullable = true), post.getValue("spoiler_text"))
        assertEquals(ModelField("size", "size", "com.example.api.Size", nullable = false), post.getValue("size"))
    }

    @Test
    fun `unnamed multipart parts are named at their call site`() {
        val upload = BytecodeApi.endpoints(dex()).single()
        assertEquals(
            listOf(EndpointParam("file", "part", "p0.c", "call-site"), EndpointParam("description", "part", "p0.c", "call-site"), EndpointParam("caption", "part", "p0.b")),
            upload.params,
        )
    }

    @Test
    fun `the rendered contract is valid OpenAPI and the mock fills non-null fields`() {
        val d = dex()
        val c = ApiContract(emptyList(), BytecodeApi.endpoints(d), emptyList(), emptyList(), emptyList(), BytecodeApi.models(d), emptyList(), emptyList())
        val yaml = ContractRenderer.openApi(c, "Example", "1.0")
        assertTrue("multipart/form-data:" in yaml && "'file': {type: string, format: binary}" in yaml, yaml)
        assertTrue("'visibility': {type: string, enum: ['UNKNOWN', 'public', 'unlisted']}" in yaml, yaml)
        assertTrue("'reblog': {anyOf: [{type: object, nullable: true, enum: [null]}, {\$ref: '#/components/schemas/Post'}]}" in yaml, yaml)
        // A non-null nested model is always filled; a nullable one is shown once, then null, which ends the recursion.
        val example = ContractRenderer.example("com.example.api.Post", ContractRenderer.Models(c.models).index(), 0)!!
        val inner = """"id":"string","visibility":"public","tags":["string"],"reblog":null,"spoiler_text":"string","size":{"width":0,"aspect":0.0}"""
        assertEquals("""{"id":"string","visibility":"public","tags":["string"],"reblog":{$inner},"spoiler_text":"string","size":{"width":0,"aspect":0.0}}""", example)
    }

    @Test
    fun `same-named models, shared routes and clashing operation ids render as valid OpenAPI`() {
        val ref = SourceRef("test", "x")
        fun model(name: String) = ModelSchema(name, listOf(ModelField("text", "text", "String")), ref)
        fun get(path: String, response: String) = Endpoint("GET", path, "r", "a.b", emptyList(), null, response, listOf(ref))
        val models = listOf(model("com.x.entity.Translation"), model("com.x.entity.Instance.Configuration.Translation"), model("com.x.entity.Status"), model("com.x.entity.Scheduled"))
        val endpoints = listOf(
            get("api/v1/instance", "com.x.entity.Instance.Configuration.Translation"),
            get("api/v2/instance", "com.x.entity.Translation"),
            Endpoint("POST", "api/v1/statuses", "a", "a.b", emptyList(), null, "com.x.entity.Status", listOf(ref)),
            Endpoint("POST", "api/v1/statuses", "b", "a.b", emptyList(), null, "com.x.entity.Scheduled", listOf(ref)),
        )
        val yaml = ContractRenderer.openApi(ApiContract(emptyList(), endpoints, emptyList(), emptyList(), emptyList(), models, emptyList(), emptyList()), "x", "1")
        // Both Translations get their own component, and each reference finds the right one.
        assertTrue("    'Translation':" in yaml && "    'Instance.Configuration.Translation':" in yaml, yaml)
        assertTrue("schema: {\$ref: '#/components/schemas/Instance.Configuration.Translation'}" in yaml, yaml)
        // getInstance for v1, getInstanceV2 for v2: operation ids are unique.
        assertTrue("operationId: 'getInstance'" in yaml && "operationId: 'getInstanceV2'" in yaml, yaml)
        // Two declarations of POST /api/v1/statuses: one operation answering either model.
        assertEquals(1, Regex("""\n    post:""").findAll(yaml).count())
        assertTrue("{anyOf: [{\$ref: '#/components/schemas/Status'}, {\$ref: '#/components/schemas/Scheduled'}]}" in yaml, yaml)
    }
}
