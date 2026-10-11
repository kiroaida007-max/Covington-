package dev.apkproject.app

import com.sun.net.httpserver.HttpServer
import dev.apkproject.contracts.Outcome
import dev.apkproject.dexasm.Assembler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SBOM components from what an APK carries, the fix for the installed version's line, CVSS scores,
 * OSV.dev (by version and by commit) and NVD (native libraries by CPE) lookups against a local
 * server with canned records, and CycloneDX 1.5 output checked
 * against the official schema.
 */
class SupplyChainTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var server: HttpServer
    private val vulnRequests = AtomicInteger()
    private var failVulns = false
    private var commitVulns = false
    private val nvdRequests = AtomicInteger()
    private val nvdKeys = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** The NVD's first answer is its rate limit (403): the client waits and retries. */
    private var nvdRateLimitOnce = false

    private fun nvdCve(id: String, configurations: String, metrics: String = "", status: String = "Analyzed") = """
        {"cve":{"id":"$id","vulnStatus":"$status","published":"2022-03-25T09:15:08.187","lastModified":"2024-01-01T00:00:00.000",
          "descriptions":[{"lang":"en","value":"Test description of $id."}],
          "metrics":{$metrics},
          "weaknesses":[{"source":"nvd@nist.gov","type":"Primary","description":[{"lang":"en","value":"CWE-787"}]}],
          "configurations":[$configurations]}}
    """.trimIndent()
    private fun node(vararg matches: String) = """{"operator":"OR","negate":false,"cpeMatch":[${matches.joinToString(",")}]}"""
    private fun v31(vector: String, severity: String) = """"cvssMetricV31":[{"source":"nvd@nist.gov","type":"Primary","cvssData":{"version":"3.1","vectorString":"$vector","baseSeverity":"$severity"}}]"""

    /** zlib's NVD record, in the API's shape: two pages of three. */
    private val nvdZlib = listOf(
        // Fixed in 1.2.12: affects 1.2.11.
        nvdCve("CVE-2018-25032", """{"nodes":[${node("""{"vulnerable":true,"criteria":"cpe:2.3:a:zlib:zlib:*:*:*:*:*:*:*:*","versionEndExcluding":"1.2.12"}""")}]}""",
            v31("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H", "HIGH")),
        // Through 1.2.12: affects 1.2.11, no fixed version recorded.
        nvdCve("CVE-2022-37434", """{"nodes":[${node("""{"vulnerable":true,"criteria":"cpe:2.3:a:zlib:zlib:*:*:*:*:*:*:*:*","versionEndIncluding":"1.2.12"}""")}]}""",
            v31("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", "CRITICAL")),
        // Through 1.1.3: not 1.2.11.
        nvdCve("CVE-2002-0059", """{"nodes":[${node("""{"vulnerable":true,"criteria":"cpe:2.3:a:zlib:zlib:*:*:*:*:*:*:*:*","versionEndIncluding":"1.1.3"}""")}]}"""),
        // Exactly 1.2.11, rated with CVSS v2 only.
        nvdCve("CVE-2099-0001", """{"nodes":[${node("""{"vulnerable":true,"criteria":"cpe:2.3:a:zlib:zlib:1.2.11:*:*:*:*:*:*:*"}""")}]}""",
            """"cvssMetricV2":[{"source":"nvd@nist.gov","type":"Primary","baseSeverity":"MEDIUM","cvssData":{"version":"2.0","vectorString":"AV:N/AC:L/Au:N/C:N/I:N/A:P"}}]"""),
        // zlib only as the platform another product runs on (not vulnerable itself).
        nvdCve("CVE-2099-0002", """{"operator":"AND","nodes":[${node("""{"vulnerable":true,"criteria":"cpe:2.3:a:other:tool:*:*:*:*:*:*:*:*"}""")},${node("""{"vulnerable":false,"criteria":"cpe:2.3:a:zlib:zlib:*:*:*:*:*:*:*:*","versionEndExcluding":"9.9"}""")}]}"""),
        // Rejected records do not count.
        nvdCve("CVE-2099-0003", """{"nodes":[${node("""{"vulnerable":true,"criteria":"cpe:2.3:a:zlib:zlib:*:*:*:*:*:*:*:*","versionEndExcluding":"9.9"}""")}]}""", status = "Rejected"),
    )

    /** Canned OSV records: Gson's writeReplace DoS and a Jackson gadget fixed on four lines. */
    private val records = mapOf(
        "GHSA-4jrv-ppp4-jm57" to """
            {"id":"GHSA-4jrv-ppp4-jm57","summary":"Deserialization of Untrusted Data in Gson","aliases":["CVE-2022-25647"],
             "modified":"2024-01-01T00:00:00Z","published":"2022-05-03T00:00:44Z",
             "database_specific":{"cwe_ids":["CWE-502"],"severity":"HIGH"},
             "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}],
             "affected":[{"package":{"name":"com.google.code.gson:gson","ecosystem":"Maven"},
               "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.8.9"}]}]}]}
        """.trimIndent(),
        "GHSA-jjjh-jjxp-wpff" to """
            {"id":"GHSA-jjjh-jjxp-wpff","summary":"Uncontrolled Resource Consumption in Jackson-databind","aliases":["CVE-2022-42003"],
             "modified":"2024-01-01T00:00:00Z","published":"2022-10-02T00:00:00Z",
             "database_specific":{"cwe_ids":["CWE-400","CWE-502"],"severity":"HIGH"},
             "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}],
             "affected":[
               {"package":{"name":"com.fasterxml.jackson.core:jackson-databind","ecosystem":"Maven"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.4.0-rc1"},{"fixed":"2.12.7.1"}]}]},
               {"package":{"name":"com.fasterxml.jackson.core:jackson-databind","ecosystem":"Maven"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.13.0"},{"fixed":"2.13.4.2"}]}]}]}
        """.trimIndent(),
        "OSV-2099-1" to """
            {"id":"OSV-2099-1","summary":"Heap-buffer-overflow in BoringSSL","modified":"2024-01-01T00:00:00Z",
             "affected":[{"package":{"name":"boringssl","ecosystem":"OSS-Fuzz"},
               "ranges":[{"type":"GIT","repo":"https://boringssl.googlesource.com/boringssl","events":[{"introduced":"0"},{"fixed":"0123456789abcdef0123456789abcdef01234567"}]}]}]}
        """.trimIndent(),
        "GHSA-27xj-rqx5-2255" to """
            {"id":"GHSA-27xj-rqx5-2255","summary":"jackson-databind mishandles the interaction between serialization gadgets and typing","aliases":["CVE-2020-10673"],
             "modified":"2024-01-01T00:00:00Z",
             "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H"}],
             "affected":[
               {"package":{"name":"com.fasterxml.jackson.core:jackson-databind","ecosystem":"Maven"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.6.7.4"}]}]},
               {"package":{"name":"com.fasterxml.jackson.core:jackson-databind","ecosystem":"Maven"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.7.0"},{"fixed":"2.7.9.7"}]}]},
               {"package":{"name":"com.fasterxml.jackson.core:jackson-databind","ecosystem":"Maven"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.8.0"},{"fixed":"2.8.11.6"}]}]},
               {"package":{"name":"com.fasterxml.jackson.core:jackson-databind","ecosystem":"Maven"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.9.0"},{"fixed":"2.9.10.4"}]}]}]}
        """.trimIndent(),
    )
    private val BORINGSSL = "49f0329110a1d93a5febc2bceceedc655d995420"
    private val byPackage = mapOf(
        "com.google.code.gson:gson" to listOf("GHSA-4jrv-ppp4-jm57"),
        "com.fasterxml.jackson.core:jackson-databind" to listOf("GHSA-jjjh-jjxp-wpff", "GHSA-27xj-rqx5-2255"),
    )

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val (status, body) = when {
                path == "/v1/querybatch" -> {
                    val q = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject["queries"]!!.jsonArray
                    val results = q.joinToString(",") { e ->
                        val commit = e.jsonObject["commit"]?.jsonPrimitive?.content
                        val ids = if (commit != null) (if (commitVulns && commit == BORINGSSL) listOf("OSV-2099-1") else emptyList())
                        else byPackage[e.jsonObject["package"]!!.jsonObject["name"]!!.jsonPrimitive.content].orEmpty()
                        if (ids.isEmpty()) "{}" else """{"vulns":[${ids.joinToString(",") { """{"id":"$it","modified":"2024-01-01T00:00:00Z"}""" }}]}"""
                    }
                    200 to """{"results":[$results]}"""
                }
                path.startsWith("/v1/vulns/") -> {
                    vulnRequests.incrementAndGet()
                    if (failVulns) 503 to "unavailable" else records[path.removePrefix("/v1/vulns/")]?.let { 200 to it } ?: (404 to "{}")
                }
                path == "/nvd" -> {
                    nvdRequests.incrementAndGet()
                    nvdKeys += ex.requestHeaders.getFirst("apiKey") ?: ""
                    val query = ex.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
                    when {
                        nvdRateLimitOnce -> { nvdRateLimitOnce = false; 403 to "rate limited" }
                        query["virtualMatchString"] != "cpe:2.3:a:zlib:zlib" || "noRejected" !in query -> 200 to """{"resultsPerPage":0,"startIndex":0,"totalResults":0,"vulnerabilities":[]}"""
                        else -> {
                            val start = query["startIndex"]?.toInt() ?: 0
                            val page = nvdZlib.drop(start).take(3)
                            200 to """{"resultsPerPage":${page.size},"startIndex":$start,"totalResults":${nvdZlib.size},"vulnerabilities":[${page.joinToString(",")}]}"""
                        }
                    }
                }
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}"

    /** An APK whose libraries are named only the ways real ones are: version files and strings in code. */
    private fun apk(): Path {
        val dex = Assembler.assemble(
            """
            .class public Lokhttp3/internal/Version;
            .super Ljava/lang/Object;
            .method public static userAgent()Ljava/lang/String;
                .registers 1
                const-string v0, "okhttp/3.12.0"
                return-object v0
            .end method

            .class public final La/b;
            .super Ljava/lang/Object;
            .method public static version()Ljava/lang/Object;
                .registers 4
                const-string v0, "2.9.8"
                const-string v1, "com.fasterxml.jackson.core"
                const-string v2, "jackson-databind"
                invoke-static {v0, v1, v2}, La/c;->parse(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;
                move-result-object v3
                return-object v3
            .end method
            """.trimIndent(),
        )
        val zlib = "\u007fELF....deflate 1.2.11 Copyright 1995-2017 Jean-loup Gailly and Mark Adler\u0000".toByteArray(Charsets.ISO_8859_1)
        val bytes = TestApks.zip(
            linkedMapOf(
                "classes.dex" to dex,
                "META-INF/maven/com.google.code.gson/gson/pom.properties" to "groupId=com.google.code.gson\nartifactId=gson\nversion=2.8.5\n".toByteArray(),
                "META-INF/androidx.core_core.version" to "1.12.0\n".toByteArray(),
                "firebase-messaging.properties" to "version=23.4.0\nclient=firebase-messaging\nfirebase-messaging_client=23.4.0\n".toByteArray(),
                "org/conscrypt/conscrypt.properties" to "org.conscrypt.boringssl.version=49f0329110a1d93a5febc2bceceedc655d995420\norg.conscrypt.version.major=2\norg.conscrypt.version.minor=5\norg.conscrypt.version.patch=2\n".toByteArray(),
                "lib/arm64-v8a/libconscrypt_jni.so" to byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
                "lib/arm64-v8a/libz-bundled.so" to zlib,
            ),
        )
        return TestApks.write(dir, "app.apk", bytes)
    }

    private fun open(p: Path) = (AppInput.open(p) as Outcome.Ok).value

    @Test
    fun `components come from version files, version strings and native files`() {
        val input = open(apk())
        val c = SupplyChain.components(input, input.dexFiles()).associateBy { if (it.type == "file") it.path!! else it.coordinate }
        assertEquals("2.8.5", c.getValue("com.google.code.gson:gson").version)
        assertEquals("manifest-analysis", c.getValue("com.google.code.gson:gson").technique)
        assertEquals("1.12.0", c.getValue("androidx.core:core").version)
        assertEquals("23.4.0", c.getValue("com.google.firebase:firebase-messaging").version)
        // OkHttp 3 keeps its version only in its User-Agent string; renamed classes do not matter.
        val okhttp = c.getValue("com.squareup.okhttp3:okhttp")
        assertEquals("3.12.0", okhttp.version)
        assertEquals("binary-analysis", okhttp.technique)
        assertEquals("pkg:maven/com.squareup.okhttp3/okhttp@3.12.0", okhttp.purl)
        // Jackson's PackageVersion constants, in a class R8 renamed to a.b.
        assertEquals("2.9.8", c.getValue("com.fasterxml.jackson.core:jackson-databind").version)
        assertTrue("a.b.version" in c.getValue("com.fasterxml.jackson.core:jackson-databind").evidence)
        // Conscrypt with its native library is the Android artifact; BoringSSL is named by commit.
        assertEquals("2.5.2", c.getValue("org.conscrypt:conscrypt-android").version)
        assertEquals("49f0329110a1d93a5febc2bceceedc655d995420", c.getValue("boringssl").version)
        assertNull(c.getValue("boringssl").ecosystem, "a commit is not looked up as a Maven version")
        // Native: every file with its hash, and zlib's version from its copyright banner.
        val zlib = c.getValue("zlib")
        assertEquals("1.2.11", zlib.version)
        assertEquals("cpe:2.3:a:zlib:zlib:1.2.11:*:*:*:*:*:*:*", zlib.cpe)
        assertEquals(64, c.getValue("lib/arm64-v8a/libz-bundled.so").sha256!!.length)
        assertEquals(2, c.values.count { it.type == "file" })
    }

    @Test
    fun `maven versions order numerically with qualifiers below the release`() {
        val ordered = listOf("1.0-alpha1", "1.0-beta", "1.0-rc1", "1.0", "1.0.1", "1.9", "1.10", "2.9.8", "2.9.10", "2.9.10.4", "2.12.7.1", "2.13.0")
        for (i in ordered.indices) for (j in ordered.indices) {
            val c = MavenVersion.compare(ordered[i], ordered[j]).coerceIn(-1, 1)
            assertEquals(i.compareTo(j).coerceIn(-1, 1), c, "${ordered[i]} vs ${ordered[j]}")
        }
        assertEquals(0, MavenVersion.compare("1.0", "1.0.0"))
        assertEquals(0, MavenVersion.compare("1.0", "1.0-final"))
    }

    @Test
    fun `CVSS v3 base scores match the FIRST calculator`() {
        assertEquals(9.8, Cvss3.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
        assertEquals(8.1, Cvss3.score("CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H"))
        assertEquals(7.5, Cvss3.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"))
        assertEquals(6.1, Cvss3.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"))
        assertEquals(10.0, Cvss3.score("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
        assertEquals(0.0, Cvss3.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
        assertNull(Cvss3.score("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
    }

    @Test
    fun `fixed-in lists only the fix of the installed version's line`() {
        fun range(vararg events: Pair<String, String>) = Json.parseToJsonElement(
            """{"ranges":[{"type":"ECOSYSTEM","events":[${events.joinToString(",") { """{"${it.first}":"${it.second}"}""" }}]}]}""",
        ).jsonObject
        val lines = listOf(
            range("introduced" to "0", "fixed" to "2.6.7.4"), range("introduced" to "2.7.0", "fixed" to "2.7.9.7"),
            range("introduced" to "2.8.0", "fixed" to "2.8.11.6"), range("introduced" to "2.9.0", "fixed" to "2.9.10.4"),
        )
        assertEquals(listOf("2.9.10.4"), OsvClient.fixedIn("2.9.8", lines))
        assertEquals(listOf("2.7.9.7"), OsvClient.fixedIn("2.7.3", lines))
        // One range with several lines: the first fix above the installed version.
        val one = range("introduced" to "0", "fixed" to "2.6.7.4", "introduced" to "2.7.0", "fixed" to "2.7.9.7", "introduced" to "2.9.0", "fixed" to "2.9.10.4")
        assertEquals(listOf("2.9.10.4"), OsvClient.fixedIn("2.9.8", listOf(one)))
        // No fix published for the line.
        assertEquals(emptyList(), OsvClient.fixedIn("1.4", listOf(range("introduced" to "1.0", "last_affected" to "1.9"))))
        assertTrue(OsvClient.contains(listOf("introduced" to "1.0", "last_affected" to "1.9"), "1.9"))
        assertFalse(OsvClient.contains(listOf("introduced" to "1.0", "last_affected" to "1.9"), "1.9.1"))
        assertFalse(OsvClient.contains(listOf("introduced" to "2.4.0-rc1", "fixed" to "2.12.7.1"), "2.3.9"))
    }

    @Test
    fun `OSV advisories, cache, CycloneDX output and a failed lookup`() {
        val input = open(apk())
        val cache = dir.resolve("cache")
        val r = SupplyChain.analyze(input, input.dexFiles(), null, OsvClient(url, cache))
        assertTrue(r.lookupComplete, r.lookup)
        assertEquals(setOf("GHSA-4jrv-ppp4-jm57", "GHSA-jjjh-jjxp-wpff", "GHSA-27xj-rqx5-2255"), r.advisories.map { it.id }.toSet())
        val gson = r.advisories.single { it.id == "GHSA-4jrv-ppp4-jm57" }
        assertEquals(listOf("2.8.9"), gson.fixedIn)
        assertEquals(7.5, gson.score)
        assertEquals("HIGH", gson.severity)
        assertEquals(listOf("CWE-502"), gson.cwes)
        // Jackson: 2.9.8 is fixed by 2.9.10.4 for the gadget and 2.12.7.1 for the DoS, not by 2.6.7.4 or 2.13.4.2.
        assertEquals(listOf("2.9.10.4"), r.advisories.single { it.id == "GHSA-27xj-rqx5-2255" }.fixedIn)
        assertEquals(listOf("2.12.7.1"), r.advisories.single { it.id == "GHSA-jjjh-jjxp-wpff" }.fixedIn)
        val jackson = r.risks.single { it.coordinate == "com.fasterxml.jackson.core:jackson-databind" }
        assertEquals("2.12.7.1", jackson.upgradeTo, "the smallest upgrade that fixes both")
        assertEquals(2, jackson.advisories)
        assertEquals(3, vulnRequests.get())

        // A second run reads the records from the cache.
        val again = SupplyChain.analyze(input, input.dexFiles(), null, OsvClient(url, cache))
        assertEquals(3, vulnRequests.get())
        assertEquals(r.advisories, again.advisories)
        assertTrue("3 from cache" in again.lookup, again.lookup)

        val bom = SupplyChain.cycloneDx(r, timestamp = Instant.parse("2026-10-11T00:00:00Z"))
        val json = Json.parseToJsonElement(bom).jsonObject
        assertEquals("1.5", json["specVersion"]!!.jsonPrimitive.content)
        val vulns = json["vulnerabilities"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, vulns.size)
        assertEquals("pkg:maven/com.google.code.gson/gson@2.8.5", vulns.single { it["id"]!!.jsonPrimitive.content == "GHSA-4jrv-ppp4-jm57" }["affects"]!!.jsonArray[0].jsonObject["ref"]!!.jsonPrimitive.content)
        validate(bom)
        val md = SupplyChainMarkdown.render(r)
        assertTrue("| com.fasterxml.jackson.core:jackson-databind | 2.9.8 | 2 | HIGH | 2.12.7.1 |" in md, md)

        // A lookup that fails is reported, never a clean result.
        failVulns = true
        val failed = SupplyChain.analyze(input, input.dexFiles(), null, OsvClient(url, dir.resolve("empty-cache"), retryDelayMs = 1))
        assertFalse(failed.lookupComplete)
        assertTrue("FAILED" in failed.lookup)
        assertTrue("did not complete" in SupplyChainMarkdown.render(failed))

        // Offline: components only.
        val offline = SupplyChain.analyze(input, input.dexFiles(), null, null)
        assertTrue(offline.advisories.isEmpty() && !offline.lookupComplete)
        validate(SupplyChain.cycloneDx(offline))
    }

    @Test
    fun `native versions order as C libraries number them`() {
        val ascending = listOf("1.1.1", "1.1.1a", "1.1.1k", "1.1.1l", "1.1.1w", "1.1.1za", "1.1.2", "3.0.0-alpha1", "3.0.0-beta2", "3.0.0-rc1", "3.0.0", "3.0.1")
        for ((a, b) in ascending.zipWithNext()) assertTrue(NativeVersion.compare(a, b) < 0, "$a < $b")
        assertTrue(NativeVersion.compare("1.2.9", "1.2.10") < 0)
        assertTrue(NativeVersion.compare("1.2.11", "1.2.11.1") < 0 && NativeVersion.compare("1.2.11.1", "1.2.12") < 0)
        assertEquals(0, NativeVersion.compare("n4.4", "4.4"))
        assertEquals(0, NativeVersion.compare("1.2.11", "1.2.11.0"))
    }

    @Test
    fun `native libraries are matched against the NVD by CPE, commits against OSV`() {
        val input = open(apk())
        commitVulns = true
        nvdRateLimitOnce = true
        val cache = dir.resolve("nvd-cache")
        val nvd = NvdClient("$url/nvd", apiKey = "test-key", cache = cache, spacingMs = 0, retryDelayMs = 1)
        val r = SupplyChain.analyze(input, input.dexFiles(), null, OsvClient(url, dir.resolve("osv-cache")), nvd)
        assertTrue(r.lookupComplete, r.lookup)
        assertTrue("NVD, 1 native library matched by CPE, 6 CVEs read" in r.lookup, r.lookup)
        val zlib = r.advisories.filter { it.coordinate == "zlib" }.associateBy { it.id }
        // Ranges, an exact version, a platform-only mention and a rejected record.
        assertEquals(setOf("CVE-2018-25032", "CVE-2022-37434", "CVE-2099-0001"), zlib.keys)
        assertEquals(listOf("1.2.12"), zlib.getValue("CVE-2018-25032").fixedIn)
        assertEquals(emptyList(), zlib.getValue("CVE-2022-37434").fixedIn, "versionEndIncluding names the last affected version, not a fix")
        assertEquals("CRITICAL", zlib.getValue("CVE-2022-37434").severity)
        assertEquals(9.8, zlib.getValue("CVE-2022-37434").score)
        assertEquals("MEDIUM", zlib.getValue("CVE-2099-0001").severity)
        assertNull(zlib.getValue("CVE-2099-0001").score, "CVSS v2 gives the label only")
        assertTrue(zlib.values.all { it.source == "NVD" && it.installed == "1.2.11" && it.url.startsWith("https://nvd.nist.gov/vuln/detail/") })
        assertEquals(listOf("CWE-787"), zlib.getValue("CVE-2018-25032").cwes)
        val risk = r.risks.single { it.coordinate == "zlib" }
        assertEquals("1.2.12", risk.upgradeTo)
        assertEquals(2, risk.unfixed)
        // Two pages after one rate-limited answer; the API key goes in its header.
        assertEquals(3, nvdRequests.get())
        assertTrue(nvdKeys.all { it == "test-key" }, nvdKeys.toString())
        // Conscrypt's BoringSSL, built from a git commit, is looked up in OSV by that commit.
        val boring = r.advisories.single { it.id == "OSV-2099-1" }
        assertEquals("boringssl", boring.coordinate)
        assertEquals("OSV", boring.source)
        assertTrue(boring.fixedIn.isEmpty(), "a fix commit is not a version to upgrade to")

        // The NVD answer is cached per product.
        val again = SupplyChain.analyze(input, input.dexFiles(), null, OsvClient(url, dir.resolve("osv-cache")), NvdClient("$url/nvd", apiKey = null, cache = cache, spacingMs = 0))
        assertEquals(3, nvdRequests.get())
        assertEquals(zlib.keys, again.advisories.filter { it.coordinate == "zlib" }.map { it.id }.toSet())

        val bom = SupplyChain.cycloneDx(r, timestamp = Instant.parse("2026-10-11T00:00:00Z"))
        val v = Json.parseToJsonElement(bom).jsonObject["vulnerabilities"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "CVE-2018-25032" }
        assertEquals("NVD", v["source"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("pkg:generic/zlib@1.2.11", v["affects"]!!.jsonArray[0].jsonObject["ref"]!!.jsonPrimitive.content)
        validate(bom)
        val md = SupplyChainMarkdown.render(r)
        assertTrue("| zlib | 1.2.11 | 3 | CRITICAL | 1.2.12 (2 without a fix) |" in md, md)
        assertTrue("| [CVE-2018-25032](https://nvd.nist.gov/vuln/detail/CVE-2018-25032) | CVE-2018-25032 | NVD | HIGH | 7.5 | zlib | 1.2.11 | 1.2.12 |" in md, md)

        // An NVD that cannot be read leaves the lookup incomplete, never clean.
        val down = SupplyChain.analyze(input, input.dexFiles(), null, OsvClient(url, dir.resolve("osv-cache")), NvdClient("$url/missing", cache = dir.resolve("none"), spacingMs = 0, retryDelayMs = 1))
        assertFalse(down.lookupComplete)
        assertTrue("FAILED for zlib" in down.lookup, down.lookup)
    }

    @Test
    fun `native projects are named by the version banners they carry`() {
        fun so(text: String) = ("\u007fELF\u0000" + text + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val bytes = TestApks.zip(
            linkedMapOf(
                "classes.dex" to Assembler.assemble(".class public La/A;\n.super Ljava/lang/Object;"),
                "lib/arm64-v8a/libexpat.so" to so("expat_2.4.1"),
                "lib/arm64-v8a/libvpx.so" to so("WebM Project VP9 Decoder v1.12.0"),
                "lib/arm64-v8a/libopus.so" to so("libopus 1.3.1"),
                "lib/arm64-v8a/libssh2.so" to so("SSH-2.0-libssh2_1.10.0"),
                "lib/arm64-v8a/libarchive.so" to so("libarchive 3.6.2"),
                "lib/arm64-v8a/libopencv_java4.so" to so("General configuration for OpenCV 4.5.5 ====================================="),
                "lib/arm64-v8a/libsqlcipher.so" to so("4.5.4 community"),
                // The same banner text in an unrelated library does not make it SQLCipher.
                "lib/arm64-v8a/libother.so" to so("1.0.0 community"),
            ),
        )
        val input = open(TestApks.write(dir, "natives.apk", bytes))
        val c = SupplyChain.natives(input).filter { it.type == "library" }.associate { it.name to (it.version to it.cpe) }
        assertEquals(
            mapOf(
                "expat" to ("2.4.1" to "cpe:2.3:a:libexpat_project:libexpat:2.4.1:*:*:*:*:*:*:*"),
                "libvpx" to ("1.12.0" to "cpe:2.3:a:webmproject:libvpx:1.12.0:*:*:*:*:*:*:*"),
                "opus" to ("1.3.1" to "cpe:2.3:a:opus-codec:opus:1.3.1:*:*:*:*:*:*:*"),
                "libssh2" to ("1.10.0" to "cpe:2.3:a:libssh2:libssh2:1.10.0:*:*:*:*:*:*:*"),
                "libarchive" to ("3.6.2" to "cpe:2.3:a:libarchive:libarchive:3.6.2:*:*:*:*:*:*:*"),
                "opencv" to ("4.5.5" to "cpe:2.3:a:opencv:opencv:4.5.5:*:*:*:*:*:*:*"),
                "sqlcipher" to ("4.5.4" to "cpe:2.3:a:zetetic:sqlcipher:4.5.4:*:*:*:*:*:*:*"),
            ),
            c,
        )
    }

    /** Validates against the official CycloneDX 1.5 schema when Python's jsonschema is installed. */
    private fun validate(bom: String) {
        val schemas = Paths.get(javaClass.getResource("/cyclonedx-1.5/bom-1.5.schema.json")!!.toURI()).parent
        val file = Files.writeString(Files.createTempFile(dir, "bom", ".json"), bom)
        val p = runCatching {
            ProcessBuilder("python3", "-I", schemas.resolve("validate.py").toString(), schemas.toString(), file.toString()).redirectErrorStream(true).start()
        }.getOrNull()
        assumeTrue(p != null, "python3 not installed")
        p!!.waitFor(120, TimeUnit.SECONDS)
        val output = p.inputStream.readBytes().decodeToString()
        assumeTrue(p.exitValue() != 3, "jsonschema not installed")
        assertEquals(0, p.exitValue(), output)
    }
}
