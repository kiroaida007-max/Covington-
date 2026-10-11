package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** One third-party component found in the app: a library with its version, or a native file. */
@Serializable
data class SbomComponent(
    /** CycloneDX component type: `library` (a versioned project) or `file` (a native library file). */
    val type: String,
    val group: String?,
    val name: String,
    val version: String?,
    /** Package URL; also the component's bom-ref. */
    val purl: String?,
    /** CPE 2.3 name for native projects, which scanners match against NVD. */
    val cpe: String? = null,
    /** OSV ecosystem the version can be looked up in (`Maven`); null when there is none. */
    val ecosystem: String? = null,
    /** Where the identity and version were read: a file in the APK or a string in the code. */
    val evidence: String,
    /** CycloneDX identity technique: manifest-analysis (a version file), binary-analysis (a string in code), hash-comparison. */
    val technique: String,
    val confidence: Double,
    val path: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
) {
    /** Maven-style `group:name`, the OSV package name. */
    val coordinate: String get() = if (group != null) "$group:$name" else name
    val ref: String get() = purl ?: "file:${path ?: name}"
}

/** One known vulnerability of a component, from OSV.dev (libraries, git commits) or the NVD (native libraries by CPE). */
@Serializable
data class Advisory(
    val id: String,
    /** CVE and other identifiers of the same issue. */
    val aliases: List<String>,
    val summary: String,
    /** CRITICAL, HIGH, MEDIUM, LOW or UNKNOWN. */
    val severity: String,
    /** CVSS v3 base score computed from [vector]; null when there is no v3 vector. */
    val score: Double?,
    val vector: String?,
    val cwes: List<String>,
    /** bom-ref of the affected component. */
    val component: String,
    val coordinate: String,
    val installed: String,
    /**
     * Versions that fix it for the installed version's line: the `fixed` event of the affected
     * range that contains [installed]. Fixes of older lines (2.6.7.4 for a 2.9.8) are left out.
     * Empty when no fixed version is published.
     */
    val fixedIn: List<String>,
    val published: String? = null,
    val modified: String? = null,
    val url: String,
    /** Where it was found: `OSV` or `NVD`. */
    val source: String = "OSV",
)

/** Per vulnerable component: how many advisories, the worst, and the smallest upgrade that fixes them all. */
@Serializable
data class ComponentRisk(
    val component: String,
    val coordinate: String,
    val installed: String,
    val advisories: Int,
    val worst: String,
    /** The lowest version that fixes every advisory with a published fix; null if none has one. */
    val upgradeTo: String?,
    /** Advisories with no fixed version for this line. */
    val unfixed: Int,
)

@Serializable
data class SbomApp(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val inputSha256: String,
    val signers: List<String> = emptyList(),
    val androidGradlePlugin: String? = null,
)

@Serializable
data class SupplyChainReport(
    val app: SbomApp,
    val components: List<SbomComponent>,
    val advisories: List<Advisory>,
    val risks: List<ComponentRisk>,
    /** How vulnerabilities were looked up: `OSV.dev …`, or why they were not. */
    val lookup: String,
    val lookupComplete: Boolean,
    val notes: List<String>,
)

/**
 * Software bill of materials for an APK and the known vulnerabilities of what it contains.
 *
 * Components come only from evidence in the package: library version files (`META-INF/<library>.version`,
 * Maven `pom.properties`, Firebase and Play services `.properties`), version strings compiled into
 * code (OkHttp's User-Agent, Jackson's PackageVersion, Bouncy Castle's provider name), the Kotlin
 * standard library's version constant, and native libraries with their hashes and, where a known
 * project stamps one, its version. Each component says where it was read. Libraries R8 renamed and
 * that carry no version file are not listed: their identity would be a guess.
 */
object SupplyChain {
    // ---- components ---------------------------------------------------------------------------

    /** Every third-party component found in [input]: libraries from version files, code and native files. */
    fun components(input: AppInput, dex: List<Pair<String, DexFile>>): List<SbomComponent> {
        val out = LinkedHashMap<String, SbomComponent>()
        /** Adds [c], keeping the first component seen per coordinate or file path. */
        fun add(c: SbomComponent) {
            val key = if (c.type == "file") "file:${c.path}" else c.coordinate
            out.putIfAbsent(key, c)
        }
        val entries = input.allEntries()
        val names = entries.map { it.second.name }.toSet()

        for (lib in LibraryCatalog.read(input)) {
            if (!VERSION.matches(lib.version)) continue
            val (g, a) = if (':' in lib.coordinate) lib.coordinate.substringBefore(':') to lib.coordinate.substringAfter(':') else null to lib.coordinate
            add(maven(g, a, lib.version, lib.evidence, "manifest-analysis"))
        }
        // Firebase, Play services and Google's data transport ship `<artifact>.properties` at the root.
        for ((part, e) in entries) {
            val n = e.name
            if ('/' in n || !n.endsWith(".properties") || e.uncompressedSize > 4096) continue
            val props = runCatching { java.util.Properties().apply { load(part.read(e).inputStream()) } }.getOrNull() ?: continue
            val version = props.getProperty("version")?.trim() ?: continue
            val client = props.getProperty("client")?.trim() ?: continue
            if (!VERSION.matches(version)) continue
            val group = when {
                client.startsWith("firebase-") -> "com.google.firebase"
                client.startsWith("play-services-") -> "com.google.android.gms"
                client.startsWith("transport-") -> "com.google.android.datatransport"
                client.startsWith("recaptcha") -> "com.google.android.recaptcha"
                client.startsWith("integrity") -> "com.google.android.play"
                else -> null
            }
            add(maven(group, client, version, n, "manifest-analysis"))
        }
        // Conscrypt states its version and the BoringSSL commit it was built from.
        entries.firstOrNull { it.second.name == "org/conscrypt/conscrypt.properties" }?.let { (part, e) ->
            val p = java.util.Properties().apply { load(part.read(e).inputStream()) }
            val v = listOf("major", "minor", "patch").map { p.getProperty("org.conscrypt.version.$it") }
            if (v.all { it != null }) {
                val artifact = if (names.any { it.endsWith("/libconscrypt_jni.so") }) "conscrypt-android" else "conscrypt-openjdk-uber"
                add(maven("org.conscrypt", artifact, v.joinToString("."), e.name, "manifest-analysis"))
            }
            p.getProperty("org.conscrypt.boringssl.version")?.takeIf { Regex("[0-9a-f]{40}").matches(it) }?.let { commit ->
                add(SbomComponent("library", null, "boringssl", commit, "pkg:github/google/boringssl@$commit", null, null,
                    "${e.name}: org.conscrypt.boringssl.version (statically linked into Conscrypt's native library)", "manifest-analysis", 1.0))
            }
        }
        LibraryResolver.kotlinStdlibVersion(dex)?.let { add(maven("org.jetbrains.kotlin", "kotlin-stdlib", it.version, it.evidence, "binary-analysis")) }
        for (c in fromCode(dex)) add(c)
        for (c in natives(input)) add(c)
        return out.values.sortedWith(compareBy({ it.type != "library" }, { it.coordinate.lowercase() }, { it.path }))
    }

    private val VERSION = Regex("""\d+(?:\.\d+)*(?:[-.+][0-9A-Za-z.\-+]+)?""")

    /** Builds a Maven-coordinate [SbomComponent] with its purl and OSV ecosystem. */
    private fun maven(group: String?, name: String, version: String, evidence: String, technique: String) = SbomComponent(
        "library", group, name, version,
        if (group != null) "pkg:maven/$group/$name@$version" else "pkg:generic/$name@$version",
        null, if (group != null) "Maven" else null, evidence, technique, if (technique == "manifest-analysis") 1.0 else 0.9,
    )

    private val OKHTTP = Regex("""^okhttp/(\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.]+)?)$""")
    private val BOUNCY_CASTLE = Regex("""^BouncyCastle Security Provider v(\d+\.\d+(?:\.\d+)?)$""")
    private val JACKSON_GROUP = Regex("""^com\.fasterxml\.jackson\.[a-z.]+$""")
    private val JACKSON_ARTIFACT = Regex("""^jackson-[a-z0-9-]+$""")

    /** Versions compiled into code as strings: they survive R8 renaming. */
    internal fun fromCode(dex: List<Pair<String, DexFile>>): List<SbomComponent> {
        val out = ArrayList<SbomComponent>()
        for ((file, d) in dex) {
            for (s in d.strings) {
                OKHTTP.find(s)?.let { m ->
                    val v = m.groupValues[1]
                    val g = if ((v.substringBefore('.').toIntOrNull() ?: 0) >= 3) "com.squareup.okhttp3" else "com.squareup.okhttp"
                    out += maven(g, "okhttp", v, "$file: string \"$s\" (OkHttp's User-Agent)", "binary-analysis")
                }
                BOUNCY_CASTLE.find(s)?.let { m ->
                    val v = m.groupValues[1]
                    val artifact = if (MavenVersion.compare(v, "1.72") >= 0) "bcprov-jdk18on" else "bcprov-jdk15on"
                    out += maven("org.bouncycastle", artifact, v, "$file: string \"$s\" (provider info)", "binary-analysis")
                }
            }
            // Jackson's PackageVersion classes build Version(…, "com.fasterxml.jackson.core", "jackson-databind") from constants in one method.
            if (d.strings.none { JACKSON_GROUP.matches(it) }) continue
            for (m in d.methods()) {
                val code = m.code ?: continue
                val decoded = (InstructionDecoder.decode(d, code) as? Outcome.Ok)?.value ?: continue
                val consts = decoded.insns.filter { it.op.index == IndexKind.STRING }.map { d.string(it.index) }
                val group = consts.firstOrNull { JACKSON_GROUP.matches(it) } ?: continue
                val artifact = consts.firstOrNull { JACKSON_ARTIFACT.matches(it) } ?: continue
                val version = consts.firstOrNull { Regex("""^\d+\.\d+\.\d+(?:[.-][0-9A-Za-z.\-]+)?$""").matches(it) } ?: continue
                out += maven(group, artifact, version, "$file: ${m.owner.removePrefix("L").removeSuffix(";").replace('/', '.')}.${m.name} (Jackson PackageVersion constants)", "binary-analysis")
            }
        }
        return out.distinctBy { it.coordinate }
    }

    /** Native projects that stamp their version into the binary: (file name filter, pattern, project, CPE vendor:product). */
    private class NativeRule(val file: Regex?, val pattern: Regex, val name: String, val cpe: String)

    private val NATIVE_RULES = listOf(
        NativeRule(null, Regex("""(?:^|[^A-Za-z])OpenSSL (\d+\.\d+\.\d+[a-z]{0,2})(?:-[\w.]+)?\s+\d{1,2} \w{3} \d{4}"""), "openssl", "openssl:openssl"),
        NativeRule(null, Regex("""(?:^|[^A-Za-z])(?:deflate|inflate) (\d+\.\d+(?:\.\d+){0,2}) Copyright"""), "zlib", "zlib:zlib"),
        NativeRule(null, Regex("""(?:^|[^A-Za-z])libpng version (\d+\.\d+\.\d+)"""), "libpng", "libpng:libpng"),
        NativeRule(null, Regex("""(?:^|[^A-Za-z])libjpeg-turbo version (\d+\.\d+\.\d+)"""), "libjpeg-turbo", "libjpeg-turbo:libjpeg-turbo"),
        NativeRule(null, Regex("""libcurl/(\d+\.\d+\.\d+)"""), "curl", "haxx:libcurl"),
        NativeRule(null, Regex("""(?:^|[^A-Za-z])FFmpeg version (n?\d+\.\d+(?:\.\d+)?)"""), "ffmpeg", "ffmpeg:ffmpeg"),
        NativeRule(null, Regex("""(?:^|[^A-Za-z])[Mm]bed TLS (\d+\.\d+\.\d+)"""), "mbedtls", "arm:mbed_tls"),
        NativeRule(Regex("(?i)sqlite"), Regex("""^(3\.\d{1,2}\.\d{1,2})$"""), "sqlite", "sqlite:sqlite"),
        NativeRule(Regex("(?i)avif"), Regex("""^(\d+\.\d+\.\d+(?:-\d+-g[0-9a-f]{7,})?)$"""), "libavif", "aomedia:libavif"),
        // Expat's XML_ExpatVersion(), libvpx's codec names, Opus's opus_get_version_string(), libarchive's
        // and libssh2's version banners, OpenCV's build information, SQLCipher's version pragma.
        NativeRule(null, Regex("""^expat_(\d+\.\d+\.\d+)$"""), "expat", "libexpat_project:libexpat"),
        NativeRule(null, Regex("""WebM Project VP[89] (?:En|De)coder v(\d+\.\d+\.\d+)"""), "libvpx", "webmproject:libvpx"),
        NativeRule(null, Regex("""^libopus (\d+\.\d+(?:\.\d+)?)"""), "opus", "opus-codec:opus"),
        NativeRule(null, Regex("""^libarchive (\d+\.\d+\.\d+)"""), "libarchive", "libarchive:libarchive"),
        NativeRule(null, Regex("""SSH-2\.0-libssh2_(\d+\.\d+\.\d+)"""), "libssh2", "libssh2:libssh2"),
        NativeRule(null, Regex("""General configuration for OpenCV (\d+\.\d+\.\d+)"""), "opencv", "opencv:opencv"),
        NativeRule(Regex("(?i)sqlcipher"), Regex("""^(\d+\.\d+\.\d+) community$"""), "sqlcipher", "zetetic:sqlcipher"),
    )

    /** Every native library file with its hash, plus the native projects whose version is stamped inside. */
    internal fun natives(input: AppInput): List<SbomComponent> {
        val files = ArrayList<SbomComponent>()
        val projects = LinkedHashMap<String, SbomComponent>()
        val scanned = HashSet<String>()
        val prefix = input.parts.size > 1
        for ((part, e) in input.allEntries()) {
            if (!e.name.matches(Regex("""(?:[^/]+/)?lib/[^/]+/[^/]+\.so"""))) continue
            val bytes = runCatching { part.read(e) }.getOrNull() ?: continue
            val path = (if (prefix) "${part.name}!" else "") + e.name
            val abi = e.name.substringBeforeLast('/').substringAfterLast('/')
            files += SbomComponent(
                "file", null, e.name.substringAfterLast('/'), null, null, null, null, "$path ($abi)", "hash-comparison", 1.0,
                path, Hashing.sha256(bytes), bytes.size.toLong(),
            )
            // The same library built for another ABI carries the same banners: each name is read once.
            if (!scanned.add(e.name.substringAfterLast('/'))) continue
            val strings by lazy { printable(bytes) }
            for (rule in NATIVE_RULES) {
                if (rule.name in projects || (rule.file != null && !rule.file.containsMatchIn(e.name.substringAfterLast('/')))) continue
                val hit = strings.firstNotNullOfOrNull { s -> rule.pattern.find(s)?.let { it.groupValues[1] to s } } ?: continue
                val (v, s) = hit
                val cleanVersion = v.removePrefix("n")
                val quoted = s.substring(maxOf(0, s.indexOf(v) - 40)).trim().take(80)
                projects[rule.name] = SbomComponent(
                    "library", null, rule.name, v, "pkg:generic/${rule.name}@$v", "cpe:2.3:a:${rule.cpe}:${cleanVersion.substringBefore('-')}:*:*:*:*:*:*:*",
                    null, "$path: string \"$quoted\"", "binary-analysis", 0.8, path,
                )
            }
        }
        return projects.values.toList() + files
    }

    /** Printable ASCII runs of 4+ characters, as `strings` prints them. */
    private fun printable(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 0x20..0x7e) { sb.append(c.toChar()); continue }
            if (sb.length >= 4) out += sb.toString()
            sb.setLength(0)
        }
        if (sb.length >= 4) out += sb.toString()
        return out
    }

    /** The app's identity for the SBOM: package, versions, input hash, signer certificates and AGP version. */
    fun app(input: AppInput, manifestXml: String?): SbomApp {
        val m = manifestXml?.let { AppDiff.Manifest.parse(it) }
        val base = input.parts.firstOrNull { it.name == "base.apk" } ?: input.parts.first()
        val signers = if (input.kind == InputKind.DEX) emptyList() else runCatching {
            val jar = base.names.filter { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }.mapNotNull { base.read(it) }
            ApkSigning.read(base.bytes, jar).signers.map { it.sha256 }
        }.getOrDefault(emptyList())
        val agp = input.allEntries().firstOrNull { it.second.name == "META-INF/com/android/build/gradle/app-metadata.properties" }?.let { (p, e) ->
            runCatching { java.util.Properties().apply { load(p.read(e).inputStream()) }.getProperty("androidGradlePluginVersion") }.getOrNull()
        }
        return SbomApp(m?.packageName, m?.versionName, m?.versionCode, input.sha256, signers, agp)
    }

    // ---- report -------------------------------------------------------------------------------

    /**
     * Components plus their advisories: libraries and git commits from OSV.dev, native libraries
     * from the NVD by CPE. [osv] null means no lookup (offline); then advisories are empty and
     * [SupplyChainReport.lookupComplete] is false. [nvd] null leaves native libraries unchecked.
     */
    fun analyze(input: AppInput, dex: List<Pair<String, DexFile>>, manifestXml: String?, osv: OsvClient?, nvd: NvdClient? = null, log: (String) -> Unit = {}): SupplyChainReport {
        val components = components(input, dex)
        val app = app(input, manifestXml)
        val queryable = components.filter { it.version != null && (it.ecosystem != null || OsvClient.commit(it) != null) }
        val natives = components.filter { it.cpe != null && it.version != null }
        var lookup = "not looked up (offline)"
        var complete = false
        var advisories = emptyList<Advisory>()
        if (osv != null) {
            val started = System.nanoTime()
            val r = osv.advisories(queryable, log)
            advisories = r.advisories
            complete = r.failed.isEmpty()
            lookup = "OSV.dev, ${queryable.size} packages queried, ${r.vulnerabilities} advisories read (${r.fromCache} from cache) in ${(System.nanoTime() - started) / 1_000_000} ms" +
                (if (r.failed.isEmpty()) "" else "; FAILED for ${r.failed.size}: ${r.failed.take(5).joinToString()}")
            if (nvd != null && natives.isNotEmpty()) {
                val t = System.nanoTime()
                val n = nvd.advisories(natives, log)
                advisories = (advisories + n.advisories).sortedWith(compareBy({ SEVERITY_ORDER.indexOf(it.severity) }, { -(it.score ?: 0.0) }, { it.coordinate }, { it.id }))
                complete = complete && n.failed.isEmpty()
                lookup += "; NVD, ${natives.size} native librar${if (natives.size == 1) "y" else "ies"} matched by CPE, ${n.cves} CVEs read (${n.fromCache} products from cache) in ${(System.nanoTime() - t) / 1_000_000} ms" +
                    (if (n.failed.isEmpty()) "" else "; FAILED for ${n.failed.joinToString()}")
            }
        }
        val risks = risks(advisories)
        val notes = buildList {
            add("Components come from evidence in the package only: version files, version strings compiled into code, and native file hashes. Each lists where it was read.")
            add("Libraries that R8 renamed and that ship no version file are not listed: naming them would be a guess. `apkproject decompile` matches such code against Maven artifacts (INFERRED).")
            add("A version file can outlive the code: R8 may have removed most of a library while its META-INF version file still ships. An advisory says the version is affected, not that the vulnerable code is present or reachable.")
            add("Fixed-in versions are those of the installed version's line (the affected range that contains it); fixes for older lines are left out.")
            if (nvd != null && osv != null) add("Native projects stamped with a version are matched against the NVD by CPE: every CVE recorded for the product, with its configurations evaluated against the installed version. OSV has no version-based ecosystem for C libraries. A native library without a version stamp is listed by hash only.")
            else add("Native projects are listed with a CPE for NVD-based scanners; they were not looked up in the NVD here.")
            if (components.any { OsvClient.commit(it) != null }) add("Components built from a git commit (Conscrypt's BoringSSL) are looked up in OSV.dev by that commit.")
            if (osv == null) add("Vulnerabilities were not looked up (offline). Run without --offline, or import sbom.cdx.json into a CycloneDX tool such as Dependency-Track.")
        }
        return SupplyChainReport(app, components, advisories, risks, lookup, complete, notes)
    }

    /** Per vulnerable component: advisory count, worst severity, and the smallest upgrade fixing them all. */
    internal fun risks(advisories: List<Advisory>): List<ComponentRisk> = advisories.groupBy { it.component }.map { (ref, xs) ->
        // Native libraries number their versions their own way (`1.1.1k`); Maven's order is for libraries.
        val order: Comparator<String> = if (xs.all { it.source == "NVD" }) NativeVersion else MavenVersion
        val fixes = xs.mapNotNull { a -> a.fixedIn.minWithOrNull(order) }
        ComponentRisk(
            ref, xs.first().coordinate, xs.first().installed, xs.size,
            xs.minByOrNull { SEVERITY_ORDER.indexOf(it.severity) }!!.severity,
            fixes.maxWithOrNull(order), xs.count { it.fixedIn.isEmpty() },
        )
    }.sortedWith(compareBy({ SEVERITY_ORDER.indexOf(it.worst) }, { -it.advisories }, { it.coordinate }))

    val SEVERITY_ORDER = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")

    /** "1 known vulnerability in 1 component", "61 known vulnerabilities in 3 components". */
    fun vulnerabilities(advisories: Int, components: Int) =
        "$advisories known vulnerabilit${if (advisories == 1) "y" else "ies"} in $components component${if (components == 1) "" else "s"}"

    // ---- CycloneDX ----------------------------------------------------------------------------

    /** CycloneDX 1.5 JSON (validated against the official schema in the tests). */
    fun cycloneDx(r: SupplyChainReport, toolVersion: String = "0.1.0", timestamp: Instant = Instant.now()): String {
        val appRef = "app:${r.app.packageName ?: r.app.inputSha256.take(16)}"
        val body = buildJsonObject {
            put("bomFormat", "CycloneDX")
            put("specVersion", "1.5")
            put("serialNumber", "urn:uuid:" + UUID.nameUUIDFromBytes((r.app.inputSha256 + r.components.joinToString { it.ref } + r.advisories.joinToString { it.id + it.modified }).toByteArray()))
            put("version", 1)
            putJsonObject("metadata") {
                put("timestamp", timestamp.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
                putJsonObject("tools") {
                    putJsonArray("components") { addJsonObject { put("type", "application"); put("name", "apkproject"); put("version", toolVersion) } }
                }
                putJsonObject("component") {
                    put("type", "application")
                    put("bom-ref", appRef)
                    put("name", r.app.packageName ?: "unknown")
                    r.app.versionName?.let { put("version", it) }
                    putJsonArray("hashes") { addJsonObject { put("alg", "SHA-256"); put("content", r.app.inputSha256) } }
                    putJsonArray("properties") {
                        r.app.versionCode?.let { v -> addJsonObject { put("name", "android:versionCode"); put("value", v.toString()) } }
                        r.app.signers.forEach { s -> addJsonObject { put("name", "android:signerCertificateSha256"); put("value", s) } }
                        r.app.androidGradlePlugin?.let { v -> addJsonObject { put("name", "android:androidGradlePluginVersion"); put("value", v) } }
                    }
                }
            }
            putJsonArray("components") {
                for (c in r.components) addJsonObject {
                    put("type", c.type)
                    put("bom-ref", c.ref)
                    c.group?.let { put("group", it) }
                    put("name", c.name)
                    c.version?.let { put("version", it) }
                    c.purl?.let { put("purl", it) }
                    c.cpe?.let { put("cpe", it) }
                    if (c.sha256 != null) putJsonArray("hashes") { addJsonObject { put("alg", "SHA-256"); put("content", c.sha256) } }
                    putJsonObject("evidence") {
                        putJsonObject("identity") {
                            put("field", if (c.purl != null) "purl" else "name")
                            put("confidence", c.confidence)
                            putJsonArray("methods") { addJsonObject { put("technique", c.technique); put("confidence", c.confidence); put("value", c.evidence) } }
                        }
                        c.path?.let { p -> putJsonArray("occurrences") { addJsonObject { put("location", p) } } }
                    }
                    if (c.size != null) putJsonArray("properties") { addJsonObject { put("name", "apkproject:size"); put("value", c.size.toString()) } }
                }
            }
            putJsonArray("dependencies") {
                addJsonObject {
                    put("ref", appRef)
                    putJsonArray("dependsOn") { r.components.map { it.ref }.distinct().forEach { add(it) } }
                }
            }
            if (r.advisories.isNotEmpty()) putJsonArray("vulnerabilities") {
                for ((id, xs) in r.advisories.groupBy { it.id }) addJsonObject {
                    val a = xs.first()
                    put("bom-ref", "vuln:$id")
                    put("id", id)
                    putJsonObject("source") { put("name", a.source); put("url", a.url) }
                    val refs = a.aliases.filter { (it.startsWith("CVE-") || it.startsWith("GHSA-")) && it != id }
                    if (refs.isNotEmpty()) putJsonArray("references") {
                        for (alias in refs) addJsonObject {
                            put("id", alias)
                            putJsonObject("source") {
                                if (alias.startsWith("CVE-")) { put("name", "NVD"); put("url", "https://nvd.nist.gov/vuln/detail/$alias") }
                                else { put("name", "GitHub"); put("url", "https://github.com/advisories/$alias") }
                            }
                        }
                    }
                    putJsonArray("ratings") {
                        addJsonObject {
                            putJsonObject("source") { put("name", a.source) }
                            a.score?.let { put("score", it) }
                            put("severity", if (a.severity == "UNKNOWN") "unknown" else a.severity.lowercase())
                            when {
                                a.vector?.startsWith("CVSS:3.1/") == true -> put("method", "CVSSv31")
                                a.vector?.startsWith("CVSS:3.0/") == true -> put("method", "CVSSv3")
                                a.vector?.startsWith("CVSS:4.0/") == true -> put("method", "CVSSv4")
                                a.vector != null -> put("method", "other")
                            }
                            a.vector?.let { put("vector", it) }
                        }
                    }
                    val cwes = a.cwes.mapNotNull { it.removePrefix("CWE-").toIntOrNull() }
                    if (cwes.isNotEmpty()) putJsonArray("cwes") { cwes.forEach { add(it) } }
                    put("description", a.summary)
                    val fixes = xs.flatMap { it.fixedIn }.distinct()
                    put("recommendation", if (fixes.isEmpty()) "No fixed version is published for the installed line." else "Upgrade to ${fixes.joinToString(" or ")} or later.")
                    a.published?.let { put("published", it) }
                    a.modified?.let { put("updated", it) }
                    putJsonArray("affects") {
                        for (x in xs.distinctBy { it.component }) addJsonObject {
                            put("ref", x.component)
                            putJsonArray("versions") { addJsonObject { put("version", x.installed); put("status", "affected") } }
                        }
                    }
                }
            }
        }
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), body)
    }
}

/**
 * Maven version order (the main rules of Maven's ComparableVersion): numeric parts compare as
 * numbers, missing parts count as 0, and qualifiers order alpha < beta < milestone < rc < snapshot
 * < release (`ga`, `final`) < sp < anything else. So 2.9.10.4 > 2.9.8, 1.0-rc1 < 1.0, 1.0 == 1.0.0.
 */
object MavenVersion : Comparator<String> {
    private val QUALIFIERS = listOf("alpha", "beta", "milestone", "rc", "snapshot", "", "sp")
    private val ALIASES = mapOf("a" to "alpha", "b" to "beta", "m" to "milestone", "cr" to "rc", "ga" to "", "final" to "", "release" to "")

    /** Splits a version string into its numeric and qualifier items, trailing zeros/release qualifiers dropped. */
    private fun items(v: String): List<Any> {
        val out = ArrayList<Any>()
        val sb = StringBuilder()
        var digits: Boolean? = null
        /** Appends the pending digit or qualifier run in [sb] to [out]. */
        fun flush() {
            if (sb.isEmpty()) return
            val s = sb.toString()
            out += if (digits == true) BigInteger(s) else (ALIASES[s.lowercase()] ?: s.lowercase())
            sb.setLength(0)
        }
        for (ch in v.trim()) {
            if (ch == '.' || ch == '-' || ch == '_' || ch == '+') { flush(); digits = null; continue }
            val d = ch.isDigit()
            if (digits != null && d != digits) flush()
            digits = d
            sb.append(ch)
        }
        flush()
        // Trailing zeros and release qualifiers do not change a version: 1.0.0 == 1 == 1-final.
        while (out.isNotEmpty() && (out.last() == BigInteger.ZERO || out.last() == "")) out.removeAt(out.size - 1)
        return out
    }

    /** The qualifier's position in [QUALIFIERS]' order; unknown qualifiers rank last. */
    private fun rank(q: String): Int = QUALIFIERS.indexOf(q).let { if (it >= 0) it else QUALIFIERS.size }

    /** Compares two Maven version strings item by item, as Maven's ComparableVersion does. */
    override fun compare(a: String, b: String): Int {
        val x = items(a)
        val y = items(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)
            val q = y.getOrNull(i)
            val c = when {
                p is BigInteger && q is BigInteger -> p.compareTo(q)
                p is BigInteger && q == null -> p.signum()
                p == null && q is BigInteger -> -q.signum()
                p is BigInteger -> 1 // a number beats a qualifier: 1.0.1 > 1.0-rc1
                q is BigInteger -> -1
                else -> {
                    val sp = (p as String?) ?: ""
                    val sq = (q as String?) ?: ""
                    val r = rank(sp).compareTo(rank(sq))
                    if (r != 0) r else if (rank(sp) == QUALIFIERS.size) sp.compareTo(sq) else 0
                }
            }
            if (c != 0) return c
        }
        return 0
    }
}

/** CVSS v3.0/v3.1 base score from a vector string (FIRST specification, section 7). */
object Cvss3 {
    /** The CVSS v3 base score computed from a `CVSS:3.x/...` vector string, or null when it cannot be parsed. */
    fun score(vector: String): Double? {
        if (!vector.startsWith("CVSS:3.")) return null
        val m = vector.split('/').drop(1).mapNotNull { p -> p.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val scope = m["S"] ?: return null
        val changed = scope == "C"
        val av = mapOf("N" to 0.85, "A" to 0.62, "L" to 0.55, "P" to 0.2)[m["AV"]] ?: return null
        val ac = mapOf("L" to 0.77, "H" to 0.44)[m["AC"]] ?: return null
        val pr = when (m["PR"]) { "N" -> 0.85; "L" -> if (changed) 0.68 else 0.62; "H" -> if (changed) 0.5 else 0.27; else -> return null }
        val ui = mapOf("N" to 0.85, "R" to 0.62)[m["UI"]] ?: return null
        val cia = mapOf("H" to 0.56, "L" to 0.22, "N" to 0.0)
        val c = cia[m["C"]] ?: return null
        val i = cia[m["I"]] ?: return null
        val a = cia[m["A"]] ?: return null
        val iss = 1 - (1 - c) * (1 - i) * (1 - a)
        val impact = if (changed) 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15.0) else 6.42 * iss
        val exploitability = 8.22 * av * ac * pr * ui
        if (impact <= 0) return 0.0
        return roundUp(minOf((if (changed) 1.08 else 1.0) * (impact + exploitability), 10.0))
    }

    /** CVSS v3.1 Roundup: the smallest number with one decimal that is >= the input, free of float error. */
    private fun roundUp(x: Double): Double {
        val i = Math.round(x * 100_000)
        return if (i % 10_000 == 0L) i / 100_000.0 else (Math.floor(i / 10_000.0) + 1) / 10.0
    }

    /** The CVSS severity label for a base score. */
    fun severity(score: Double): String = when {
        score >= 9.0 -> "CRITICAL"
        score >= 7.0 -> "HIGH"
        score >= 4.0 -> "MEDIUM"
        score > 0.0 -> "LOW"
        else -> "UNKNOWN"
    }
}

/**
 * OSV.dev client: one `querybatch` for every package, then each advisory's record (cached by its
 * `modified` time, so a second run fetches only what changed). Requests that fail are retried
 * three times; a package whose lookup still fails is reported, never silently treated as clean.
 */
class OsvClient(
    private val baseUrl: String = "https://api.osv.dev",
    private val cache: Path? = null,
    private val threads: Int = 8,
    /** First retry delay; it doubles on each of the three retries. */
    private val retryDelayMs: Long = 500,
) {
    class Result(val advisories: List<Advisory>, val vulnerabilities: Int, val fromCache: Int, val failed: List<String>)

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Looks up advisories for [components] via OSV's querybatch, then fetches and caches each record. */
    fun advisories(components: List<SbomComponent>, log: (String) -> Unit = {}): Result {
        if (components.isEmpty()) return Result(emptyList(), 0, 0, emptyList())
        val failed = ArrayList<String>()
        // Per component: the advisory ids and their modified times.
        val hits = ArrayList<Pair<SbomComponent, List<Pair<String, String?>>>>()
        for (chunk in components.chunked(500)) {
            val body = buildJsonObject {
                putJsonArray("queries") {
                    for (c in chunk) addJsonObject {
                        val commit = commit(c)
                        if (c.ecosystem == null && commit != null) put("commit", commit)
                        else {
                            putJsonObject("package") { put("name", c.coordinate); put("ecosystem", c.ecosystem!!) }
                            put("version", c.version!!)
                        }
                    }
                }
            }
            val response = post("/v1/querybatch", body.toString())
            if (response == null) { failed += chunk.map { it.coordinate }; continue }
            val results = runCatching { json.parseToJsonElement(response).jsonObject["results"]!!.jsonArray }.getOrNull()
            if (results == null || results.size != chunk.size) { failed += chunk.map { it.coordinate }; continue }
            for ((c, r) in chunk.zip(results)) {
                val vulns = (r as? JsonObject)?.get("vulns") as? JsonArray ?: JsonArray(emptyList())
                hits += c to vulns.map { v -> v.jsonObject["id"]!!.jsonPrimitive.content to v.jsonObject["modified"]?.jsonPrimitive?.contentOrNull }
            }
        }
        val ids = hits.flatMap { it.second }.distinctBy { it.first }
        log("OSV: ${ids.size} advisories for ${hits.count { it.second.isNotEmpty() }} of ${components.size} packages")
        var cached = 0
        val records = HashMap<String, JsonObject>()
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val futures = ids.map { (id, modified) ->
                id to pool.submit(Callable {
                    readCache(id, modified)?.let { return@Callable it to true }
                    val text = get("/v1/vulns/$id") ?: return@Callable null
                    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@Callable null
                    writeCache(id, text)
                    obj to false
                })
            }
            for ((id, f) in futures) {
                val r = f.get()
                if (r == null) { failed += id; continue }
                records[id] = r.first
                if (r.second) cached++
            }
        } finally {
            pool.shutdown()
        }
        val out = ArrayList<Advisory>()
        for ((c, vulns) in hits) for ((id, _) in vulns) {
            val v = records[id] ?: continue
            if (v["withdrawn"] != null) continue
            out += advisory(c, v)
        }
        val sorted = out.sortedWith(compareBy({ SupplyChain.SEVERITY_ORDER.indexOf(it.severity) }, { -(it.score ?: 0.0) }, { it.coordinate }, { it.id }))
        return Result(sorted, ids.size, cached, failed)
    }

    /** The cached record for advisory [id], when its cached `modified` time matches [modified]. */
    private fun readCache(id: String, modified: String?): JsonObject? {
        val f = cache?.resolve("$id.json") ?: return null
        if (!Files.isRegularFile(f)) return null
        val obj = runCatching { json.parseToJsonElement(Files.readString(f)).jsonObject }.getOrNull() ?: return null
        val have = obj["modified"]?.jsonPrimitive?.contentOrNull
        // The batch reports the record's modified time: a cached copy is current when they agree to the second.
        return obj.takeIf { modified != null && have != null && have.take(19) == modified.take(19) }
    }

    /** Writes advisory [id]'s raw JSON [text] to the cache, atomically. */
    private fun writeCache(id: String, text: String) {
        val dir = cache ?: return
        runCatching {
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, id, ".part")
            Files.writeString(tmp, text)
            Files.move(tmp, dir.resolve("$id.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** POSTs JSON [body] to [path] under [baseUrl]. */
    private fun post(path: String, body: String): String? = send(
        HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
    )

    /** GETs [path] under [baseUrl]. */
    private fun get(path: String): String? = send(HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(60)).GET().build())

    /** Sends [request], retrying on failures and 429s up to three times; null when it never succeeds. */
    private fun send(request: HttpRequest): String? {
        for (attempt in 0 until 4) {
            val r = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            if (r != null && r.statusCode() == 200) return r.body()
            if (r != null && r.statusCode() in 400..499 && r.statusCode() != 429) return null
            if (attempt < 3) Thread.sleep(retryDelayMs shl attempt)
        }
        return null
    }

    companion object {
        /** The git commit a component was built from (`pkg:github/google/boringssl@<sha>`), which OSV matches against its git ranges. */
        fun commit(c: SbomComponent): String? =
            c.version?.takeIf { c.purl?.startsWith("pkg:github/") == true && Regex("[0-9a-f]{40}").matches(it) }

        /**
         * Where advisories are cached: `APKPROJECT_CACHE`, else the platform's cache folder
         * (`%LOCALAPPDATA%\apkproject\cache`, `~/Library/Caches/apkproject`, `$XDG_CACHE_HOME/apkproject`).
         */
        fun defaultCache(): Path {
            System.getenv("APKPROJECT_CACHE")?.let { return Path.of(it, "osv") }
            val home = Path.of(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            val base = when {
                os.contains("win") -> Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString(), "apkproject", "cache")
                os.contains("mac") -> home.resolve("Library/Caches/apkproject")
                else -> Path.of(System.getenv("XDG_CACHE_HOME") ?: home.resolve(".cache").toString(), "apkproject")
            }
            return base.resolve("osv")
        }

        /** An [Advisory] for [c] from an OSV record: severity, CVSS score, and the fix for the installed line. */
        fun advisory(c: SbomComponent, v: JsonObject): Advisory {
            val id = v["id"]!!.jsonPrimitive.content
            val installed = c.version!!
            val aliases = (v["aliases"] as? JsonArray).strings()
            val db = v["database_specific"] as? JsonObject
            val vector = (v["severity"] as? JsonArray)?.map { it.jsonObject }?.let { list ->
                (list.firstOrNull { it.str("type") == "CVSS_V3" } ?: list.firstOrNull { it.str("type") == "CVSS_V4" })?.str("score")
            }
            val score = vector?.let { Cvss3.score(it) }
            val label = when (db?.str("severity")?.uppercase()) {
                "CRITICAL" -> "CRITICAL"; "HIGH" -> "HIGH"; "MODERATE", "MEDIUM" -> "MEDIUM"; "LOW" -> "LOW"
                else -> score?.let { Cvss3.severity(it) } ?: "UNKNOWN"
            }
            val affected = (v["affected"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            val mine = affected.filter { a -> (a["package"] as? JsonObject)?.let { it.str("name") == c.coordinate && it.str("ecosystem") == c.ecosystem } == true }
            return Advisory(
                id, aliases, v.str("summary") ?: v.str("details")?.lineSequence()?.firstOrNull()?.take(200) ?: id,
                label, score, vector, (db?.get("cwe_ids") as? JsonArray).strings(), c.ref, c.coordinate, installed,
                fixedIn(installed, mine.ifEmpty { affected }), v.str("published"), v.str("modified"), "https://osv.dev/vulnerability/$id",
            )
        }

        /**
         * Fixed versions for [installed]: the first `fixed` event above it in each ECOSYSTEM range
         * that contains it. When no range contains it (OSV matched it by its explicit version list),
         * the smallest fixed version above it.
         */
        fun fixedIn(installed: String, affected: List<JsonObject>): List<String> {
            val ranges = affected.flatMap { a -> (a["ranges"] as? JsonArray)?.map { it.jsonObject }.orEmpty() }
                .filter { it.str("type") == "ECOSYSTEM" || it.str("type") == "SEMVER" }
                .map { r -> (r["events"] as? JsonArray)?.map { e -> e.jsonObject.entries.first().let { it.key to it.value.jsonPrimitive.content } }.orEmpty() }
            val containing = ranges.filter { contains(it, installed) }
            /** The smallest `fixed` version in [events] above [installed], if any. */
            fun firstFixAbove(events: List<Pair<String, String>>) = events.filter { it.first == "fixed" && MavenVersion.compare(it.second, installed) > 0 }.map { it.second }.minWithOrNull(MavenVersion)
            val fixes = if (containing.isNotEmpty()) containing.mapNotNull(::firstFixAbove) else listOfNotNull(ranges.mapNotNull(::firstFixAbove).minWithOrNull(MavenVersion))
            return fixes.distinct().sortedWith(MavenVersion)
        }

        /** OSV range evaluation: events in version order; introduced opens, fixed (at) and last_affected (after) close. */
        internal fun contains(events: List<Pair<String, String>>, v: String): Boolean {
            val order = Comparator<Pair<String, String>> { a, b ->
                val za = a.first == "introduced" && a.second == "0"
                val zb = b.first == "introduced" && b.second == "0"
                when { za && zb -> 0; za -> -1; zb -> 1; else -> MavenVersion.compare(a.second, b.second) }
            }
            var affected = false
            for (e in events.sortedWith(order)) {
                when (e.first) {
                    "introduced" -> if (e.second == "0" || MavenVersion.compare(v, e.second) >= 0) affected = true
                    "fixed", "limit" -> if (MavenVersion.compare(v, e.second) >= 0) affected = false
                    "last_affected" -> if (MavenVersion.compare(v, e.second) > 0) affected = false
                }
            }
            return affected
        }

        /** The string value of key [k], or null when absent or not a primitive. */
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        /** The string values of a JSON array's primitive elements; empty when null. */
        private fun JsonArray?.strings(): List<String> = this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }
}

/** Human-readable SBOM and vulnerability report. */
object SupplyChainMarkdown {
    /** Renders a [SupplyChainReport] as a Markdown document. */
    fun render(r: SupplyChainReport): String = buildString {
        val libs = r.components.filter { it.type == "library" }
        val files = r.components.filter { it.type == "file" }
        appendLine("# Software bill of materials")
        appendLine()
        appendLine("App: `${r.app.packageName ?: "?"}` ${r.app.versionName ?: ""}${r.app.versionCode?.let { " ($it)" } ?: ""}, input SHA-256 `${r.app.inputSha256}`")
        appendLine()
        appendLine("${libs.size} libraries with a version and ${files.size} native files. Vulnerabilities: ${r.lookup}.")
        if (!r.lookupComplete && r.lookup.startsWith("OSV")) appendLine("\n**The lookup did not complete: packages listed as failed may have advisories.**")
        appendLine()
        if (r.risks.isNotEmpty()) {
            appendLine("## Vulnerable components")
            appendLine()
            appendLine("| Component | Installed | Advisories | Worst | Upgrade to |")
            appendLine("|---|---|---:|---|---|")
            for (k in r.risks) appendLine("| ${k.coordinate} | ${k.installed} | ${k.advisories} | ${k.worst} | ${k.upgradeTo ?: "no fix published"}${if (k.unfixed > 0 && k.upgradeTo != null) " (${k.unfixed} without a fix)" else ""} |")
            appendLine()
            appendLine("## Advisories")
            appendLine()
            appendLine("| ID | CVE | Source | Severity | CVSS | Component | Installed | Fixed in | Summary |")
            appendLine("|---|---|---|---|---:|---|---|---|---|")
            for (a in r.advisories) appendLine(
                "| [${a.id}](${a.url}) | ${cves(a).joinToString().ifEmpty { "-" }} | ${a.source} | ${a.severity} | ${a.score ?: "-"} | ${a.coordinate} | ${a.installed} | ${a.fixedIn.joinToString().ifEmpty { "-" }} | ${cell(a.summary)} |",
            )
            appendLine()
        } else if (r.lookupComplete) {
            appendLine("No known vulnerabilities in these versions.")
            appendLine()
        }
        appendLine("## Libraries")
        appendLine()
        appendLine("| Component | Version | Evidence |")
        appendLine("|---|---|---|")
        for (c in libs) appendLine("| ${c.coordinate} | ${c.version ?: "?"} | ${cell(c.evidence)} |")
        appendLine()
        if (files.isNotEmpty()) {
            appendLine("## Native files")
            appendLine()
            appendLine("| File | Size | SHA-256 |")
            appendLine("|---|---:|---|")
            for (f in files) appendLine("| ${f.path} | ${f.size} | `${f.sha256}` |")
            appendLine()
        }
        appendLine("## Notes")
        appendLine()
        r.notes.forEach { appendLine("- $it") }
    }

    /** Escapes [s] for a Markdown table cell and truncates it when very long. */
    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").let { if (it.length > 200) it.take(197) + "…" else it }

    /** The CVE ids of an advisory: its aliases, or the id itself for an NVD record. */
    fun cves(a: Advisory): List<String> = (listOf(a.id) + a.aliases).filter { it.startsWith("CVE-") }.distinct()
}
