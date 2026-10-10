package dev.apkproject.app

import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The app's front end, read statically from the APK, for its owner or an authorized tester:
 *
 *  - which UI toolkit and source language built it ([UiToolkit]), and how far that front end can
 *    be recovered;
 *  - its screens ([Screen]): activities, fragments, Compose entry points and layouts;
 *  - static UI-quality and app-security findings ([AuditFinding]) with a risk score ([RiskScore]).
 *
 * Every finding is OBSERVED (read directly from the manifest or resources) or INFERRED (a code or
 * layout pattern that may be guarded at runtime), and points at a fix and a reference (OWASP MASVS
 * or CWE). This is a static review of what the APK contains: it never runs the app, so on-device
 * rendering faults are out of scope; what it reports are the defects a lint or security scan finds
 * without a device. It describes risks so they can be fixed, not exploited.
 */
@Serializable
data class FrontendReport(
    val toolkit: UiToolkit,
    val screens: List<Screen>,
    val findings: List<AuditFinding>,
    val risk: RiskScore,
    val hardening: Hardening,
    val notes: List<String>,
)

@Serializable
data class UiToolkit(
    /** e.g. "Android Views (XML layouts)", "Jetpack Compose", "Flutter", "React Native". */
    val name: String,
    /** The source language that produced the UI, e.g. "Kotlin", "Java", "Dart", "JavaScript/TypeScript". */
    val language: String,
    /** How far the UI source is recoverable from this APK. */
    val recoverability: String,
    val evidence: List<String>,
    val note: String,
    /** Other UI toolkits also present (hybrid apps), by name. */
    val also: List<String> = emptyList(),
)

@Serializable
data class Screen(
    val name: String,
    /** ACTIVITY, ACTIVITY_ALIAS, FRAGMENT, COMPOSABLE, LAYOUT. */
    val kind: String,
    val layout: String? = null,
    val exported: Boolean = false,
    val launcher: Boolean = false,
    val deepLinks: List<String> = emptyList(),
    val widgets: Int = 0,
    val evidence: String,
)

@Serializable
data class AuditFinding(
    val id: String,
    /** SECURITY, PRIVACY, ACCESSIBILITY, LOCALIZATION, UI_QUALITY. */
    val category: String,
    /** CRITICAL, HIGH, MEDIUM, LOW, INFO. */
    val severity: String,
    val title: String,
    val detail: String,
    /** Where it was found: a component name, a file, or `file:line`. */
    val where: String,
    val fix: String,
    /** OWASP MASVS id, CWE id, or an Android docs anchor. */
    val reference: String,
    /** OBSERVED (read from the APK) or INFERRED (a pattern that may be guarded at runtime). */
    val evidence: String,
    /** How many places this finding was seen (accessibility gaps repeat across layouts). */
    val count: Int = 1,
)

@Serializable
data class RiskScore(
    /** 0 (no risk found) to 100 (many severe findings). Higher is worse. */
    val score: Int,
    /** A (safest) to F, from the score. */
    val grade: String,
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val info: Int,
    val summary: String,
)

/** Static front-end + security review of a decompiled project. */
object FrontendAudit {
    private val SEVERITY_WEIGHT = mapOf("CRITICAL" to 25, "HIGH" to 10, "MEDIUM" to 4, "LOW" to 1, "INFO" to 0)
    private val SEVERITY_ORDER = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")

    /**
     * @param main the decompiled `app/src/main` directory (decoded AndroidManifest.xml and res/).
     * @param manifestXml the decoded manifest text, or null.
     * @param dex the app's DEX files, for WebView and Compose code checks.
     * @param anatomy the detected runtimes.
     */
    fun analyze(
        main: Path,
        manifestXml: String?,
        dex: List<Pair<String, DexFile>>,
        anatomy: AppAnatomy,
        entries: List<String> = emptyList(),
    ): FrontendReport {
        val notes = ArrayList<String>()
        val findings = ArrayList<AuditFinding>()
        val res = main.resolve("res")

        val targetSdk = manifestXml?.let { Regex("""android:targetSdkVersion="(\d+)"""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val minSdk = manifestXml?.let { Regex("""android:minSdkVersion="(\d+)"""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: CompilerMarker.read(dex).mapNotNull { it.minApi }.minOrNull()

        val toolkit = toolkit(anatomy, res)
        val screens = screens(manifestXml, res, anatomy, dex)

        manifestXml?.let { findings += manifestFindings(it, targetSdk, minSdk) }
        findings += webViewFindings(dex, targetSdk)
        findings += secretFindings(resourceStrings(res, manifestXml))
        if (res.isDirectory()) findings += layoutFindings(res)

        if (manifestXml == null) notes += "No decoded AndroidManifest.xml: the manifest security checks were skipped."
        if (!res.isDirectory()) notes += "No decoded res/ directory: the UI-quality checks were skipped."
        if (targetSdk == null) notes += "targetSdkVersion was not found; checks that depend on it were skipped."

        val hardening = HardeningAudit.analyze(dex, anatomy, entries, manifestXml)
        // Resilience: no anti-reversing protections and no obfuscation makes the app trivial to
        // reverse. Informational (it matters most for apps handling sensitive data).
        if (hardening.level == "NONE" && hardening.obfuscation.level in setOf("NONE", "LIGHT", "UNKNOWN") && dex.isNotEmpty()) findings += AuditFinding(
            "no-anti-reversing", "SECURITY", "INFO", "No anti-reversing protections",
            "The code is not obfuscated and ships no packer, root/debugger detection or integrity check, so it is straightforward to decompile and modify.",
            "whole app", "For an app handling sensitive data, add code obfuscation and anti-tamper checks (defence in depth, not a substitute for server-side checks).", "MASVS-RESILIENCE", "INFERRED",
        )

        val merged = merge(findings).sortedWith(compareBy({ SEVERITY_ORDER.indexOf(it.severity) }, { it.category }, { it.id }))
        return FrontendReport(toolkit, screens, merged, score(merged), hardening, notes)
    }

    // ---- UI toolkit and language ----------------------------------------------------------

    private fun toolkit(anatomy: AppAnatomy, res: Path): UiToolkit {
        val by = anatomy.frameworks.associateBy { it.id }
        val language = when {
            "kotlin" in by -> "Kotlin"
            "java" in by -> "Java"
            else -> "unknown"
        }
        val hasLayouts = res.resolve("layout").isDirectory()
        // Cross-platform UI runtimes own the whole front end when present.
        by["flutter"]?.let {
            return UiToolkit("Flutter", "Dart", "Binary only: Dart is AOT-compiled into libapp.so; the UI source cannot be recovered. flutter_assets are extracted as-is.", it.evidence, it.note, otherUi(by, "flutter"))
        }
        by["react-native"]?.let {
            return UiToolkit("React Native", "JavaScript/TypeScript", "The JS bundle is recovered (REA + Wakaru); TypeScript types and original formatting are lost.", it.evidence, it.note, otherUi(by, "react-native"))
        }
        by["unity"]?.let {
            return UiToolkit("Unity", "C#", "Binary only: the engine and IL2CPP/Mono code are native; UI is defined inside the Unity scenes, not as Android views.", it.evidence, it.note, otherUi(by, "unity"))
        }
        by["xamarin"]?.let {
            return UiToolkit("Xamarin / .NET MAUI", "C#", "The .NET assemblies are present; C# can be recovered with a .NET decompiler, which this tool does not run.", it.evidence, it.note, otherUi(by, "xamarin"))
        }
        if (anatomy.webAssetRoots.isNotEmpty() || by.keys.any { it in setOf("cordova", "ionic", "capacitor") }) {
            val web = by.values.firstOrNull { it.id in setOf("cordova", "ionic", "capacitor") }
            return UiToolkit("Hybrid WebView (${web?.name ?: "Cordova/Capacitor"})", "HTML/CSS/JavaScript", "The UI is a web app under assets/www; it is recovered as-is. Minified JS is beautified (REA + Wakaru).", web?.evidence ?: anatomy.webAssetRoots, web?.note ?: "The screens are web pages shown in a WebView.", otherUi(by, web?.id ?: ""))
        }
        by["compose"]?.let {
            return UiToolkit("Jetpack Compose", language, "Compose UI is Kotlin code (not XML): recovered as decompiled @Composable functions. Preview rendering needs the project built.", it.evidence, it.note, otherUi(by, "compose"))
        }
        return UiToolkit(
            if (hasLayouts) "Android Views (XML layouts)" else "Android Views",
            language,
            "Native Android UI: XML layouts are decoded to readable XML, and the code is recovered as $language (as Java).",
            listOfNotNull(if (hasLayouts) "res/layout/ present" else null, "DEX UI classes"),
            "Classic Android view hierarchy.",
            otherUi(by, "java", "kotlin", "compose"),
        )
    }

    private fun otherUi(by: Map<String, FrameworkFinding>, vararg exclude: String): List<String> =
        by.values.filter { it.id in setOf("compose", "cordova", "ionic", "capacitor", "flutter", "react-native", "unity", "xamarin") && it.id !in exclude }.map { it.name }

    // ---- Screens --------------------------------------------------------------------------

    private fun screens(manifestXml: String?, res: Path, anatomy: AppAnatomy, dex: List<Pair<String, DexFile>>): List<Screen> {
        val out = ArrayList<Screen>()
        if (manifestXml != null) {
            val layoutWidgets = layoutWidgetCounts(res)
            for (m in Regex("""<(activity(?:-alias)?)\b((?:(?!</\1>|/>).)*?)(?:/>|>((?:(?!</\1>).)*)</\1>)""", RegexOption.DOT_MATCHES_ALL).findAll(manifestXml)) {
                val head = m.groupValues[2]
                val body = m.groupValues[3]
                val name = Regex("""android:name="([^"]+)"""").find(head)?.groupValues?.get(1) ?: continue
                val exported = isExported(head, body)
                val launcher = body.contains("android.intent.category.LAUNCHER")
                val deepLinks = Regex("""android:scheme="([^"]+)"""").findAll(body).map { it.groupValues[1] }.distinct().toList()
                val kind = if (m.groupValues[1] == "activity-alias") "ACTIVITY_ALIAS" else "ACTIVITY"
                out += Screen(simpleName(name), kind, exported = exported, launcher = launcher, deepLinks = deepLinks, evidence = "manifest <${m.groupValues[1]}>")
            }
            // Fragments by convention: classes whose name ends in Fragment (OBSERVED name, not every one is a screen).
            if (anatomy.frameworks.none { it.id in setOf("flutter", "react-native", "unity") }) {
                val fragments = dex.flatMap { (_, d) -> d.typeIds.indices.asSequence().map { d.type(it) } }
                    .filter { it.endsWith("Fragment;") && !it.startsWith("Landroidx/") && !it.startsWith("Lcom/google/android/") && !it.startsWith("Lkotlin") }
                    .map { simpleName(it.removePrefix("L").removeSuffix(";").replace('/', '.')) }
                    .distinct().sorted()
                for (f in fragments.take(400)) out += Screen(f, "FRAGMENT", evidence = "DEX type name")
            }
            // Record the layouts themselves, with their widget counts.
            for ((layout, count) in layoutWidgets.toSortedMap()) out += Screen(layout, "LAYOUT", layout = layout, widgets = count, evidence = "res/layout/$layout.xml")
        }
        return out
    }

    // ---- Manifest security ----------------------------------------------------------------

    private fun manifestFindings(xml: String, targetSdk: Int?, minSdk: Int?): List<AuditFinding> {
        val out = ArrayList<AuditFinding>()
        fun appFlag(name: String) = Regex("""<application\b[^>]*\b$name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

        if (appFlag("android:debuggable") == "true") out += AuditFinding(
            "debuggable", "SECURITY", "CRITICAL", "The app ships debuggable",
            "android:debuggable=\"true\" lets anyone attach a debugger, read memory and run code as the app on any device.",
            "application", "Remove android:debuggable, or set it only in debug builds.", "CWE-489; MASVS-RESILIENCE", "OBSERVED",
        )
        val backup = appFlag("android:allowBackup")
        if (backup == "true" || (backup == null && (targetSdk == null || targetSdk < 31))) out += AuditFinding(
            "allow-backup", "PRIVACY", "MEDIUM", "Backups are allowed",
            "android:allowBackup is ${backup ?: "unset (defaults to true below API 31)"}, so app data can be copied off the device over adb or cloud backup.",
            "application", "Set android:allowBackup=\"false\", or restrict it with android:fullBackupContent / dataExtractionRules.", "MASVS-STORAGE-2", "OBSERVED",
        )
        val cleartext = appFlag("android:usesCleartextTraffic")
        if (cleartext == "true") out += AuditFinding(
            "cleartext", "SECURITY", "HIGH", "Cleartext (HTTP) traffic is permitted",
            "android:usesCleartextTraffic=\"true\" allows unencrypted HTTP, which can be read or modified on the network.",
            "application", "Set it to false and use HTTPS, with a network security config that forbids cleartext.", "CWE-319; MASVS-NETWORK-1", "OBSERVED",
        ) else if (cleartext == null && targetSdk != null && targetSdk < 28 && !xml.contains("networkSecurityConfig")) out += AuditFinding(
            "cleartext-default", "SECURITY", "MEDIUM", "Cleartext traffic is allowed by default",
            "targetSdkVersion $targetSdk is below 28, where cleartext HTTP is permitted unless a network security config forbids it.",
            "application", "Add a network security config with cleartextTrafficPermitted=\"false\", or raise targetSdkVersion.", "CWE-319; MASVS-NETWORK-1", "OBSERVED",
        )
        if (appFlag("android:testOnly") == "true") out += AuditFinding(
            "test-only", "SECURITY", "LOW", "The app is marked testOnly",
            "android:testOnly=\"true\" is a development flag that should not reach production.",
            "application", "Remove android:testOnly from release builds.", "MASVS-RESILIENCE", "OBSERVED",
        )
        // Exported components without a permission guard.
        for (m in Regex("""<(activity(?:-alias)?|service|receiver|provider)\b((?:(?!</\1>|/>).)*?)(?:/>|>((?:(?!</\1>).)*)</\1>)""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val tag = m.groupValues[1]
            val head = m.groupValues[2]
            val body = m.groupValues[3]
            val name = Regex("""android:name="([^"]+)"""").find(head)?.groupValues?.get(1) ?: continue
            if (body.contains("android.intent.category.LAUNCHER")) continue // the launcher is meant to be exported
            if (!isExported(head, body)) continue
            val permissioned = head.contains("android:permission") || head.contains("android:readPermission") || head.contains("android:writePermission")
            val provider = tag == "provider"
            val grants = head.contains("android:grantUriPermissions=\"true\"")
            when {
                provider && !permissioned -> out += AuditFinding(
                    "exported-provider:${simpleName(name)}", "SECURITY", if (grants) "HIGH" else "MEDIUM", "Exported content provider without a permission",
                    "The content provider ${simpleName(name)} is exported${if (grants) " and grants URI permissions" else ""} with no android:permission, so other apps can reach its data.",
                    "provider ${simpleName(name)}", "Set android:exported=\"false\" if it is internal, or guard it with a signature-level permission.", "CWE-926; MASVS-PLATFORM-1", "OBSERVED",
                )
                !permissioned -> out += AuditFinding(
                    "exported-${tag}", "SECURITY", "MEDIUM", "Exported ${tag} without a permission",
                    "${simpleName(name)} is exported with an intent filter and no android:permission, so any app can invoke it. Validate every incoming Intent, or restrict it.",
                    "$tag ${simpleName(name)}", "Set android:exported=\"false\" if it is internal, otherwise require a permission and validate the Intent extras.", "CWE-926; MASVS-PLATFORM-1", "OBSERVED",
                )
            }
            if (!provider) {
                val autoVerify = head.contains("android:autoVerify=\"true\"")
                val httpLink = body.contains("android:scheme=\"https\"") || body.contains("android:scheme=\"http\"")
                if (httpLink && !autoVerify) out += AuditFinding(
                    "deep-link-unverified", "SECURITY", "LOW", "Web deep link without App Links verification",
                    "${simpleName(name)} handles an http(s) deep link without android:autoVerify, so another app can claim the same link.",
                    "$tag ${simpleName(name)}", "Add android:autoVerify=\"true\" and host a Digital Asset Links file, and validate the link's data.", "MASVS-PLATFORM-3", "OBSERVED",
                )
            }
        }
        if (minSdk != null && minSdk < 23) out += AuditFinding(
            "low-min-sdk", "SECURITY", "MEDIUM", "Very low minSdkVersion",
            "minSdkVersion $minSdk lets the app run on Android versions that no longer receive security patches and lack runtime permissions and modern TLS.",
            "uses-sdk", "Raise minSdkVersion to at least 23 (24+ recommended).", "MASVS-PLATFORM", "OBSERVED",
        )
        // Dangerous permissions, listed for privacy review (informational).
        val dangerous = Regex("""android:name="android\.permission\.([A-Z_]+)"""").findAll(xml).map { it.groupValues[1] }
            .filter { it in DANGEROUS_PERMISSIONS }.distinct().toList()
        if (dangerous.isNotEmpty()) out += AuditFinding(
            "dangerous-permissions", "PRIVACY", "INFO", "Sensitive permissions requested",
            "The app requests ${dangerous.size} sensitive permission(s): ${dangerous.joinToString(", ")}. Confirm each is needed and disclosed in the privacy policy.",
            "uses-permission", "Remove any permission the app does not use; request the rest at runtime with a clear rationale.", "MASVS-PRIVACY-1", "OBSERVED", dangerous.size,
        )
        return out
    }

    private fun isExported(head: String, body: String): Boolean {
        Regex("""android:exported="(true|false)"""").find(head)?.let { return it.groupValues[1] == "true" }
        // No explicit flag: a component with an intent filter is exported (and must be explicit on API 31+).
        return body.contains("<intent-filter")
    }

    // ---- WebView security (code) ----------------------------------------------------------

    private fun webViewFindings(dex: List<Pair<String, DexFile>>, targetSdk: Int?): List<AuditFinding> {
        if (dex.isEmpty()) return emptyList()
        val calls = HashSet<String>()
        for ((_, d) in dex) for (m in d.methodIds) calls += d.string(m.nameIdx)
        if ("loadUrl" !in calls && "loadData" !in calls && "loadDataWithBaseURL" !in calls && "addJavascriptInterface" !in calls) return emptyList()
        val out = ArrayList<AuditFinding>()
        fun seen(vararg names: String) = names.any { it in calls }

        if (seen("addJavascriptInterface")) {
            val critical = targetSdk != null && targetSdk < 17
            out += AuditFinding(
                "webview-js-interface", "SECURITY", if (critical) "CRITICAL" else "MEDIUM", "WebView exposes a JavaScript interface",
                "addJavascriptInterface bridges web content to app code." + if (critical) " With targetSdkVersion $targetSdk (<17), any page can call arbitrary public methods via reflection (CVE-2012-6636)." else " Any page loaded in the WebView can call the @JavascriptInterface methods, so only load trusted content.",
                "WebView", "Only expose @JavascriptInterface methods to content you control, and load only trusted, HTTPS URLs.", "CWE-749; MASVS-PLATFORM-2", "INFERRED",
            )
        }
        if (seen("setAllowUniversalAccessFromFileURLs", "setAllowFileAccessFromFileURLs")) out += AuditFinding(
            "webview-file-access", "SECURITY", "HIGH", "WebView allows file:// cross-origin access",
            "setAllowUniversalAccessFromFileURLs / setAllowFileAccessFromFileURLs let a file:// page read other local files and remote origins, enabling local file theft.",
            "WebView settings", "Leave these false, and prefer WebViewAssetLoader over file:// URLs.", "CWE-200; MASVS-PLATFORM-2", "INFERRED",
        )
        if (seen("setWebContentsDebuggingEnabled")) out += AuditFinding(
            "webview-debugging", "SECURITY", "MEDIUM", "WebView contents debugging is enabled somewhere",
            "setWebContentsDebuggingEnabled(true) exposes the WebView to chrome://inspect. It should be off in release builds.",
            "WebView", "Gate it on BuildConfig.DEBUG so it is never true in release.", "MASVS-RESILIENCE", "INFERRED",
        )
        if (seen("onReceivedSslError")) out += AuditFinding(
            "webview-ssl-error", "SECURITY", "HIGH", "WebView overrides SSL error handling",
            "A WebViewClient.onReceivedSslError override is present. If it calls handler.proceed(), it ignores TLS certificate errors and exposes traffic to interception. Verify it never proceeds on error.",
            "WebViewClient.onReceivedSslError", "Call handler.cancel() on any certificate error; never proceed().", "CWE-295; MASVS-NETWORK-3", "INFERRED",
        )
        return out
    }

    // ---- Secrets in resources -------------------------------------------------------------

    private val SECRET_PATTERNS = listOf(
        Triple("google-api-key", Regex("""\bAIza[0-9A-Za-z\-_]{35}\b"""), "A Google API key is stored in resources."),
        Triple("aws-access-key", Regex("""\bAKIA[0-9A-Z]{16}\b"""), "An AWS access key id is stored in resources."),
        Triple("slack-token", Regex("""\bxox[baprs]-[0-9A-Za-z\-]{10,}\b"""), "A Slack token is stored in resources."),
        Triple("private-key", Regex("""-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"""), "A private key is embedded in resources."),
        Triple("jwt", Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"""), "A JSON Web Token is stored in resources."),
        Triple("bearer-secret", Regex("""(?i)\b(?:secret|api[_-]?key|token|password|passwd)\b\s*[:=]\s*['"][^'"\s]{12,}['"]"""), "A value named like a secret is stored in resources."),
    )

    /** String values exposed in the APK: resources under res/values and manifest meta-data. */
    private fun resourceStrings(res: Path, manifestXml: String?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (dir in res.listDirectoryFilesOrEmpty().filter { it.isDirectory() && it.name.startsWith("values") }) {
            for (file in dir.listDirectoryFilesOrEmpty().filter { it.name.endsWith(".xml") }) {
                val xml = runCatching { Files.readString(file) }.getOrNull() ?: continue
                for (m in Regex("""<(?:string|item)\b[^>]*android:name="([^"]*)"[^>]*>(.*?)</(?:string|item)>|<(?:string|item)\b[^>]*name="([^"]*)"[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                    val name = m.groupValues[1].ifBlank { m.groupValues[3] }
                    val value = m.groupValues[2].ifBlank { m.groupValues[4] }.trim()
                    if (value.isNotEmpty()) out += "${dir.name}/${file.name}:$name" to value
                }
            }
        }
        manifestXml?.let { xml ->
            for (m in Regex("""<meta-data\b[^>]*android:name="([^"]*)"[^>]*android:value="([^"]*)"""").findAll(xml)) {
                out += "manifest meta-data ${m.groupValues[1]}" to m.groupValues[2]
            }
        }
        return out
    }

    private fun secretFindings(strings: List<Pair<String, String>>): List<AuditFinding> {
        val out = ArrayList<AuditFinding>()
        val hits = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for ((name, value) in strings) {
            if (value.length < 12 || value.length > 4096) continue
            for ((id, regex, detail) in SECRET_PATTERNS) {
                if (regex.containsMatchIn(value)) {
                    hits.getOrPut(id) { detail to ArrayList() }.second += name.ifBlank { "resources.arsc" }
                }
            }
        }
        for ((id, pair) in hits) {
            val (detail, where) = pair
            val severity = if (id == "private-key" || id == "aws-access-key") "CRITICAL" else "HIGH"
            out += AuditFinding(
                "secret:$id", "SECURITY", severity, "Hard-coded secret in resources",
                "$detail Anyone can unzip the APK and read it; it is not protected by being inside the app.",
                where.distinct().take(5).joinToString(", "), "Move the secret to a server, or fetch it at runtime; rotate the exposed value.", "CWE-312; CWE-798; MASVS-STORAGE-1", "OBSERVED", where.size,
            )
        }
        return out
    }

    // ---- Layout UI quality ----------------------------------------------------------------

    private fun layoutFindings(res: Path): List<AuditFinding> {
        val out = ArrayList<AuditFinding>()
        val noDesc = ArrayList<String>()
        val hardText = ArrayList<String>()
        val hardSize = ArrayList<String>()
        val noInputType = ArrayList<String>()
        val smallTarget = ArrayList<String>()
        for (dir in res.listDirectoryFilesOrEmpty().filter { it.isDirectory() && it.name.startsWith("layout") }) {
            for (file in dir.listDirectoryFilesOrEmpty().filter { it.name.endsWith(".xml") }) {
                val xml = runCatching { Files.readString(file) }.getOrNull() ?: continue
                val label = "res/${dir.name}/${file.name}"
                for (tag in Regex("""<([\w.]+)\b((?:[^>"]|"[^"]*")*?)/?>""").findAll(xml)) {
                    val element = tag.groupValues[1]
                    val attrs = tag.groupValues[2]
                    if (element in setOf("ImageView", "ImageButton") && !attrs.contains("android:contentDescription") && !attrs.contains("importantForAccessibility=\"no\"")) noDesc += label
                    if (attrs.contains(Regex("""android:text="(?!@)[^"]+"""")) && !attrs.contains("tools:text")) hardText += label
                    Regex("""android:(?:layout_width|layout_height|textSize|padding\w*|margin\w*)="(-?\d+(?:\.\d+)?)px"""").find(attrs)?.let { hardSize += label }
                    if (element == "EditText" && !attrs.contains("android:inputType")) noInputType += label
                    // A fixed, small clickable target (both dimensions under 48dp).
                    val w = Regex("""android:layout_width="(\d+)dp"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    val h = Regex("""android:layout_height="(\d+)dp"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    if (attrs.contains("android:onClick") || element in setOf("ImageButton", "Button")) {
                        if (w != null && w < 48 && h != null && h < 48) smallTarget += label
                    }
                }
            }
        }
        fun add(ids: List<String>, finding: (Int, List<String>) -> AuditFinding) { if (ids.isNotEmpty()) out += finding(ids.size, ids.distinct()) }
        add(noDesc) { n, w -> AuditFinding("a11y-content-description", "ACCESSIBILITY", "MEDIUM", "Images without a content description",
            "$n ImageView/ImageButton(s) have no android:contentDescription, so screen readers announce nothing. Decorative images should set it to \"@null\" or importantForAccessibility=\"no\".",
            w.take(5).joinToString(", "), "Add a meaningful android:contentDescription, or mark decorative images importantForAccessibility=\"no\".", "WCAG 1.1.1; Android accessibility", "OBSERVED", n) }
        add(hardText) { n, w -> AuditFinding("i18n-hardcoded-text", "LOCALIZATION", "LOW", "Hard-coded UI text",
            "$n view(s) set android:text to a literal string instead of a @string resource, so the text cannot be translated.",
            w.take(5).joinToString(", "), "Move the text to res/values/strings.xml and reference it with @string/…", "Android localization", "OBSERVED", n) }
        add(hardSize) { n, w -> AuditFinding("ui-px-dimension", "UI_QUALITY", "LOW", "Dimensions in px instead of dp/sp",
            "$n dimension(s) are in px, which do not scale with screen density and will look wrong on many devices.",
            w.take(5).joinToString(", "), "Use dp for sizes and sp for text.", "Android support different densities", "OBSERVED", n) }
        add(noInputType) { n, w -> AuditFinding("ui-no-input-type", "UI_QUALITY", "LOW", "EditText without an input type",
            "$n EditText(s) declare no android:inputType, so the keyboard, autofill and validation default poorly.",
            w.take(5).joinToString(", "), "Set an appropriate android:inputType (and android:autofillHints).", "Android text fields", "OBSERVED", n) }
        add(smallTarget) { n, w -> AuditFinding("a11y-touch-target", "ACCESSIBILITY", "LOW", "Touch target smaller than 48dp",
            "$n clickable view(s) are smaller than the 48dp minimum recommended touch target.",
            w.take(5).joinToString(", "), "Make the touch target at least 48dp, with padding if the visual is smaller.", "WCAG 2.5.5; Android touch targets", "OBSERVED", n) }
        return out
    }

    // ---- Scoring --------------------------------------------------------------------------

    private fun merge(findings: List<AuditFinding>): List<AuditFinding> =
        findings.groupBy { it.id }.map { (_, group) ->
            if (group.size == 1) group[0] else group[0].copy(count = group.sumOf { it.count }, where = group.flatMap { it.where.split(", ") }.distinct().take(6).joinToString(", "))
        }

    private fun score(findings: List<AuditFinding>): RiskScore {
        val counts = SEVERITY_ORDER.associateWith { sev -> findings.count { it.severity == sev } }
        val raw = findings.sumOf { (SEVERITY_WEIGHT[it.severity] ?: 0) }
        val score = raw.coerceAtMost(100)
        val grade = when {
            counts["CRITICAL"]!! > 0 || score >= 70 -> "F"
            score >= 45 -> "D"
            score >= 25 -> "C"
            score >= 10 -> "B"
            else -> "A"
        }
        val summary = when (grade) {
            "A" -> "No significant front-end or security issues found by the static checks."
            "B" -> "A few low-to-medium issues; worth fixing but nothing severe."
            "C" -> "Several issues, including medium-severity ones, that should be addressed."
            "D" -> "Many or serious issues; the app needs security and quality fixes before release."
            else -> "Critical issues found. Address them before shipping."
        }
        return RiskScore(score, grade, counts["CRITICAL"]!!, counts["HIGH"]!!, counts["MEDIUM"]!!, counts["LOW"]!!, counts["INFO"]!!, summary)
    }

    // ---- Helpers --------------------------------------------------------------------------

    private fun simpleName(fqn: String) = fqn.trimStart('.').substringAfterLast('.')

    private fun layoutWidgetCounts(res: Path): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (dir in res.listDirectoryFilesOrEmpty().filter { it.isDirectory() && it.name == "layout" }) {
            for (file in dir.listDirectoryFilesOrEmpty().filter { it.name.endsWith(".xml") }) {
                val xml = runCatching { Files.readString(file) }.getOrNull() ?: continue
                out[file.name.removeSuffix(".xml")] = Regex("""<[A-Za-z]""").findAll(xml).count()
            }
        }
        return out
    }

    private fun Path.listDirectoryFilesOrEmpty(): List<Path> =
        if (isDirectory()) Files.list(this).use { it.sorted().toList() } else emptyList()

    internal val DANGEROUS_PERMISSIONS = setOf(
        "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION", "RECORD_AUDIO", "CAMERA", "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
        "CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "SEND_SMS", "RECEIVE_SMS", "READ_SMS",
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_CALENDAR", "WRITE_CALENDAR",
        "BODY_SENSORS", "ACTIVITY_RECOGNITION", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
        "POST_NOTIFICATIONS", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "QUERY_ALL_PACKAGES",
    )
}

/** The front-end audit as a readable report. */
object FrontendMarkdown {
    fun render(r: FrontendReport): String = buildString {
        append("# Front end: UI and security review\n\n")
        append("A static review of the app's front end, read from the APK. It never runs the app, so it\n")
        append("reports the UI-quality and security issues a lint or security scan finds without a device,\n")
        append("each with its evidence level and a reference. Use it to fix issues, on an app you own or are\n")
        append("authorized to test.\n\n")

        append("## Risk score: ${r.risk.score}/100 (grade ${r.risk.grade})\n\n")
        append("${r.risk.summary}\n\n")
        append("| Severity | Count |\n|---|---|\n")
        append("| Critical | ${r.risk.critical} |\n| High | ${r.risk.high} |\n| Medium | ${r.risk.medium} |\n| Low | ${r.risk.low} |\n| Info | ${r.risk.info} |\n\n")

        append("## UI toolkit\n\n")
        append("- **Built with:** ${r.toolkit.name}\n")
        append("- **Language:** ${r.toolkit.language}\n")
        append("- **Front-end recoverability:** ${r.toolkit.recoverability}\n")
        if (r.toolkit.also.isNotEmpty()) append("- **Also present:** ${r.toolkit.also.joinToString(", ")}\n")
        append("- **Evidence:** ${r.toolkit.evidence.joinToString(", ")} (OBSERVED)\n\n")

        val h = r.hardening
        append("## Resilience / hardening\n\n")
        append("Overall: **${h.level}**. ${h.recoveryImpact}\n\n")
        append("- **Obfuscation:** ${h.obfuscation.level} \u2014 ${h.obfuscation.evidence}\n")
        if (h.protectors.isNotEmpty()) {
            append("- **Protectors / packers:**\n")
            for (p in h.protectors) append("  - ${p.name} (${p.kind}): ${p.note} [${p.evidence.joinToString(", ")}]\n")
        } else append("- **Protectors / packers:** none detected\n")
        if (h.defenses.isNotEmpty()) {
            append("- **Anti-analysis / integrity defenses:**\n")
            for (d in h.defenses) append("  - ${d.name} (${d.evidence}, ${d.masvs}): ${d.indicators.joinToString(", ")}\n")
        } else append("- **Anti-analysis / integrity defenses:** none detected\n")
        append("\n")

        val byKind = r.screens.groupBy { it.kind }
        append("## Screens\n\n")
        append("${byKind["ACTIVITY"]?.size ?: 0} activities, ${byKind["ACTIVITY_ALIAS"]?.size ?: 0} aliases, ")
        append("${byKind["FRAGMENT"]?.size ?: 0} fragments, ${byKind["LAYOUT"]?.size ?: 0} layouts. Exported and deep-linked screens are the app's attack surface.\n\n")
        val activities = r.screens.filter { it.kind == "ACTIVITY" || it.kind == "ACTIVITY_ALIAS" }
        if (activities.isNotEmpty()) {
            append("| Screen | Exported | Launcher | Deep links |\n|---|---|---|---|\n")
            for (s in activities.take(60)) append("| `${s.name}` | ${if (s.exported) "yes" else "no"} | ${if (s.launcher) "yes" else ""} | ${s.deepLinks.joinToString(", ")} |\n")
            if (activities.size > 60) append("| … ${activities.size - 60} more | | | |\n")
            append("\n")
        }

        append("## Findings\n\n")
        if (r.findings.isEmpty()) append("No issues found by the static checks.\n\n") else {
            append("| Severity | Category | Finding | Where | Evidence | Reference |\n|---|---|---|---|---|---|\n")
            for (f in r.findings) {
                val count = if (f.count > 1) " (×${f.count})" else ""
                append("| ${f.severity} | ${f.category} | ${f.title}$count | ${f.where.take(80)} | ${f.evidence} | ${f.reference} |\n")
            }
            append("\n### Detail\n\n")
            for (f in r.findings) {
                append("**[${f.severity}] ${f.title}** — ${f.category}, ${f.evidence}, ${f.reference}\n\n")
                append("${f.detail}\n\n")
                append("- Where: ${f.where}\n- Fix: ${f.fix}\n\n")
            }
        }
        if (r.notes.isNotEmpty()) {
            append("## Notes\n\n")
            r.notes.forEach { append("- $it\n") }
            append("\n")
        }
        append("---\n\nEvidence: OBSERVED = read directly from the manifest or resources; INFERRED = a code or layout\n")
        append("pattern that may be guarded at runtime (verify before acting). References: OWASP MASVS, CWE,\n")
        append("Android developer docs. The risk score weights findings (critical 25, high 10, medium 4,\n")
        append("low 1) capped at 100.\n")
    }
}
