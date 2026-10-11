package dev.apkproject.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Version order of C libraries as their projects and NVD write them: numeric parts compare as
 * numbers (`1.2.13` > `1.2.9`), a letter after a number is a later release (`1.1.1k` > `1.1.1` and
 * `1.1.1l` > `1.1.1k`, OpenSSL's scheme), and pre-release words come before the release
 * (`3.0.0-alpha1` < `3.0.0-beta2` < `3.0.0`). FFmpeg's `n` prefix is ignored. Missing parts count as 0.
 */
object NativeVersion : Comparator<String> {
    /** Pre-release words; a bare letter (`1.1.1a`) is a later release, as OpenSSL numbers them. */
    private val PRE = listOf("dev", "alpha", "beta", "pre", "rc")

    /** Each part: a number, and the letters after it (empty for none). */
    private fun parts(v: String): List<Pair<Long, String>> {
        val s = v.trim().lowercase().removePrefix("v").let { if (it.startsWith("n") && it.drop(1).firstOrNull()?.isDigit() == true) it.drop(1) else it }
        return Regex("""(\d+)?([a-z]*)""").findAll(s.replace('_', '.')).mapNotNull { m ->
            if (m.value.isEmpty()) null else (m.groupValues[1].toLongOrNull() ?: -1L) to m.groupValues[2]
        }.toList()
    }

    /** A suffix's rank against none: pre-release words below, other letters (`k` in `1.1.1k`) above. */
    private fun suffix(a: String, b: String): Int {
        if (a == b) return 0
        val pa = PRE.indexOf(a)
        val pb = PRE.indexOf(b)
        return when {
            a.isEmpty() -> if (pb >= 0) 1 else -1
            b.isEmpty() -> if (pa >= 0) -1 else 1
            pa >= 0 && pb >= 0 -> pa.compareTo(pb)
            pa >= 0 -> -1
            pb >= 0 -> 1
            else -> a.compareTo(b)
        }
    }

    /** Compares two native library version strings by [parts] and [suffix], part by part. */
    override fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val (nx, sx) = x.getOrElse(i) { 0L to "" }
            val (ny, sy) = y.getOrElse(i) { 0L to "" }
            // A part made only of a word (`-rc1` gives "rc" then 1): the word ranks as a suffix of the release.
            if (nx != ny) {
                if (nx == -1L && sx in PRE) return -1
                if (ny == -1L && sy in PRE) return 1
                return nx.compareTo(ny)
            }
            suffix(sx, sy).let { if (it != 0) return it }
        }
        return 0
    }
}

/**
 * Known vulnerabilities of native libraries from the NVD (API 2.0), by the CPE their version stamp
 * gives (`cpe:2.3:a:zlib:zlib:1.2.11`). OSV has no version-based ecosystem for C libraries, so they
 * are matched here: every CVE recorded against the product, then each CVE's configurations
 * evaluated against the installed version (an exact version, or a range bounded by
 * versionStart/End Including/Excluding). Only vulnerable match criteria count; a CVE whose
 * configuration also names a platform it runs on is reported, since the platform is not known.
 *
 * Without an API key the NVD allows 5 requests in 30 seconds, with one (`NVD_API_KEY`) 50; requests
 * are spaced accordingly and retried when the NVD answers 403, 429 or 503. Responses are cached per
 * product for [maxAge].
 */
class NvdClient(
    private val baseUrl: String = "https://services.nvd.nist.gov/rest/json/cves/2.0",
    private val apiKey: String? = System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() },
    private val cache: Path? = null,
    private val maxAge: Duration = Duration.ofHours(24),
    /** Pause between requests; the default follows the NVD's published rate limits. */
    private val spacingMs: Long = if (apiKey != null) 700 else 6_500,
    private val retryDelayMs: Long = 6_000,
) {
    class Result(val advisories: List<Advisory>, val cves: Int, val fromCache: Int, val failed: List<String>)

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var lastRequest = 0L

    /** Looks up NVD advisories for each component's CPE and version, logging a one-line summary. */
    fun advisories(components: List<SbomComponent>, log: (String) -> Unit = {}): Result {
        val out = ArrayList<Advisory>()
        val failed = ArrayList<String>()
        var cves = 0
        var cached = 0
        for (c in components) {
            val version = c.version ?: continue
            val (vendor, product) = vendorProduct(c.cpe ?: continue) ?: continue
            val (records, fromCache) = records(vendor, product) ?: run { failed += c.name; null } ?: continue
            if (fromCache) cached++
            cves += records.size
            for (cve in records) advisory(c, vendor, product, version, cve)?.let { out += it }
        }
        log("NVD: ${out.size} CVEs affect ${out.map { it.component }.distinct().size} of ${components.size} native libraries ($cves CVEs read)")
        val sorted = out.sortedWith(compareBy({ SupplyChain.SEVERITY_ORDER.indexOf(it.severity) }, { -(it.score ?: 0.0) }, { it.coordinate }, { it.id }))
        return Result(sorted, cves, cached, failed)
    }

    /** Every CVE recorded against vendor:product (all pages), from the cache when it is recent; null when the NVD could not be read. */
    private fun records(vendor: String, product: String): Pair<List<JsonObject>, Boolean>? {
        val key = "$vendor:$product"
        val file = cache?.resolve("nvd-${key.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
        if (file != null && Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() > System.currentTimeMillis() - maxAge.toMillis()) {
            runCatching { json.parseToJsonElement(Files.readString(file)) as JsonArray }.getOrNull()?.let { return it.map { v -> v.jsonObject } to true }
        }
        val all = ArrayList<JsonObject>()
        var start = 0
        while (true) {
            val match = URLEncoder.encode("cpe:2.3:a:$vendor:$product", Charsets.UTF_8)
            val text = get("$baseUrl?virtualMatchString=$match&noRejected&resultsPerPage=2000&startIndex=$start") ?: return null
            val page = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val vulns = (page["vulnerabilities"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("cve") as? JsonObject } ?: return null
            all += vulns
            val total = page["totalResults"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: all.size
            start += vulns.size
            if (vulns.isEmpty() || start >= total) break
        }
        if (file != null) runCatching {
            Files.createDirectories(file.parent)
            val tmp = Files.createTempFile(file.parent, "nvd", ".part")
            Files.writeString(tmp, JsonArray(all).toString())
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return all to false
    }

    /** GETs [url], spacing requests to the rate limit and retrying on 403/429/503; null on failure. */
    private fun get(url: String): String? {
        for (attempt in 0 until 4) {
            val wait = lastRequest + spacingMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastRequest = System.currentTimeMillis()
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET()
                .apply { if (apiKey != null) header("apiKey", apiKey) }.build()
            val r = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            if (r != null && r.statusCode() == 200) return r.body()
            // 403 and 429 are the NVD's rate limit; 5xx a busy server. Other 4xx will not change on a retry.
            if (r != null && r.statusCode() in 400..499 && r.statusCode() != 403 && r.statusCode() != 429) return null
            if (attempt < 3) Thread.sleep(retryDelayMs shl attempt)
        }
        return null
    }

    companion object {
        /** `cpe:2.3:a:zlib:zlib:1.2.11:…` -> (zlib, zlib). */
        fun vendorProduct(cpe: String): Pair<String, String>? {
            val p = cpe.split(':')
            return if (p.size >= 5 && p[0] == "cpe" && p[1] == "2.3") p[3] to p[4] else null
        }

        /**
         * An [Advisory] when [cve] affects [version] of vendor:product: some vulnerable match
         * criterion of one of its configurations names that product and contains the version.
         * Fixed in: the versionEndExcluding of each containing range (a versionEndIncluding range
         * names the last affected version, not a fix).
         */
        fun advisory(c: SbomComponent, vendor: String, product: String, version: String, cve: JsonObject): Advisory? {
            if (cve.str("vulnStatus") == "Rejected") return null
            val matches = ArrayList<JsonObject>()
            for (conf in (cve["configurations"] as? JsonArray).objects()) for (node in (conf["nodes"] as? JsonArray).objects()) {
                if ((node["negate"] as? JsonPrimitive)?.contentOrNull == "true") continue
                for (m in (node["cpeMatch"] as? JsonArray).objects()) {
                    if ((m["vulnerable"] as? JsonPrimitive)?.contentOrNull != "true") continue
                    val crit = m.str("criteria")?.split(':') ?: continue
                    if (crit.size < 6 || crit[3] != vendor || crit[4] != product) continue
                    if (matches(crit[5], crit.getOrNull(6), m, version)) matches += m
                }
            }
            if (matches.isEmpty()) return null
            val id = cve.str("id") ?: return null
            val metrics = cve["metrics"] as? JsonObject
            // The NVD's own (Primary) CVSS v3.1 rating, else v3.0, else any v3, then v4.0 and v2 for the label only.
            fun metric(key: String) = (metrics?.get(key) as? JsonArray).objects().let { list -> list.firstOrNull { it.str("type") == "Primary" } ?: list.firstOrNull() }
            val v3 = metric("cvssMetricV31") ?: metric("cvssMetricV30")
            val v4 = metric("cvssMetricV40")
            val v2 = metric("cvssMetricV2")
            val data3 = v3?.get("cvssData") as? JsonObject
            val vector = data3?.str("vectorString") ?: (v4?.get("cvssData") as? JsonObject)?.str("vectorString")
            val score = vector?.let { Cvss3.score(it) } ?: (v4?.get("cvssData") as? JsonObject)?.str("baseScore")?.toDoubleOrNull()
            val label = data3?.str("baseSeverity") ?: (v4?.get("cvssData") as? JsonObject)?.str("baseSeverity") ?: v2?.str("baseSeverity")
            val severity = when (label?.uppercase()) {
                "CRITICAL" -> "CRITICAL"; "HIGH" -> "HIGH"; "MEDIUM" -> "MEDIUM"; "LOW" -> "LOW"
                else -> score?.let { Cvss3.severity(it) } ?: "UNKNOWN"
            }
            val description = (cve["descriptions"] as? JsonArray).objects().firstOrNull { it.str("lang") == "en" }?.str("value") ?: id
            val cwes = (cve["weaknesses"] as? JsonArray).objects().flatMap { w -> (w["description"] as? JsonArray).objects().mapNotNull { it.str("value") } }
                .filter { it.startsWith("CWE-") }.distinct()
            val fixed = matches.mapNotNull { it.str("versionEndExcluding") }.filter { NativeVersion.compare(it, version) > 0 }.distinct().sortedWith(NativeVersion)
            return Advisory(
                id, emptyList(), description.lineSequence().first().let { if (it.length > 300) it.take(297) + "…" else it },
                severity, score, vector, cwes, c.ref, c.coordinate, version, fixed,
                cve.str("published"), cve.str("lastModified"), "https://nvd.nist.gov/vuln/detail/$id", source = "NVD",
            )
        }

        /** Whether a CPE match criterion (its version and update fields, and range bounds) contains [version]. */
        internal fun matches(critVersion: String, critUpdate: String?, m: JsonObject, version: String): Boolean {
            val startIncl = m.str("versionStartIncluding")
            val startExcl = m.str("versionStartExcluding")
            val endIncl = m.str("versionEndIncluding")
            val endExcl = m.str("versionEndExcluding")
            if (critVersion != "*" && critVersion != "-") {
                // An exact version; its update field (`1.0.2` update `beta1`) is part of the version.
                val exact = if (critUpdate != null && critUpdate != "*" && critUpdate != "-") "$critVersion-$critUpdate" else critVersion
                return NativeVersion.compare(exact, version) == 0
            }
            if (startIncl == null && startExcl == null && endIncl == null && endExcl == null) return critVersion == "*"
            if (startIncl != null && NativeVersion.compare(version, startIncl) < 0) return false
            if (startExcl != null && NativeVersion.compare(version, startExcl) <= 0) return false
            if (endIncl != null && NativeVersion.compare(version, endIncl) > 0) return false
            if (endExcl != null && NativeVersion.compare(version, endExcl) >= 0) return false
            return true
        }

        /** The string value of key [k], or null when absent or not a primitive. */
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        /** The elements of a JSON array that are objects; empty when null or none are. */
        private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
    }
}
