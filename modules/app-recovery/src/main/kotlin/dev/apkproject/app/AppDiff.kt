package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class VersionInfo(
    val file: String,
    val sha256: String,
    val kind: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compiler: List<String>,
    val classes: Int,
    val methods: Int,
    val signing: SigningInfo?,
)

/** One difference between the builds, ranked by how much it deserves review. */
@Serializable
data class Change(
    /** SIGNING, IDENTITY, PERMISSIONS, COMPONENTS, MANIFEST, NETWORK, API, MODELS, SDKS, LIBRARIES, NATIVE, CAPABILITIES, RISK, HARDENING, ASSETS, CODE. */
    val area: String,
    /** ADDED, REMOVED, CHANGED. */
    val kind: String,
    val subject: String,
    val detail: String,
    /** HIGH, MEDIUM, LOW, INFO. */
    val attention: String,
    /** OBSERVED (read from both APKs) or INFERRED (matched or recognized by a pattern). */
    val evidence: String,
)

@Serializable
data class CodeSummary(
    val oldClasses: Int,
    val newClasses: Int,
    val matchedByName: Int,
    val matchedByIdenticalCode: Int,
    val matchedBySimilarCode: Int,
    /** Identical classes paired arbitrarily among themselves (interchangeable). */
    val matchedAmbiguously: Int,
    /** Matched classes the obfuscator gave another name in the new build. */
    val renamedByObfuscator: Int,
    /** Names both builds use for two different classes: a by-name diff compares the wrong code. */
    val reusedNames: Int,
    val unchanged: Int,
    val modified: Int,
    val added: Int,
    val removed: Int,
    val methodsUnchanged: Int,
    val methodsSameBehaviour: Int,
    val methodsModified: Int,
    val methodsAdded: Int,
    val methodsRemoved: Int,
    /** Static methods R8 moved to another class between the builds (not changes). */
    val methodsMoved: Int,
    /** Methods with the same instructions whose references to other classes differ consistently (R8 regrouping merged classes). */
    val methodsReferencesOnly: Int,
    /** Classes whose only differences are such references. */
    val referencesOnly: Int,
    /** Of [modified], classes R8 merged from several (their contents are regrouped between builds). */
    val modifiedMerged: Int,
    val staticMethodsPaired: Int,
    val matchingRounds: Int,
)

@Serializable
data class ClassChange(
    /** MODIFIED, ADDED, REMOVED. */
    val status: String,
    val oldName: String?,
    val newName: String?,
    /** A name for people: kept by the obfuscator, or recovered from compiler evidence (see [labelEvidence]). */
    val label: String,
    val labelEvidence: String?,
    val matchedBy: String?,
    val similarity: Double?,
    val methodsModified: List<String> = emptyList(),
    val methodsAdded: List<String> = emptyList(),
    val methodsRemoved: List<String> = emptyList(),
    val stringsAdded: List<String> = emptyList(),
    val stringsRemoved: List<String> = emptyList(),
    val apisAdded: List<String> = emptyList(),
    val apisRemoved: List<String> = emptyList(),
    /** R8 built this class by merging several (lambdas, small classes); its contents are regrouped between builds, so a change here is often not an edit. */
    val compilerMerged: Boolean = false,
    /** No method was edited; only static methods were added or removed, which R8 also does when it regroups static helpers. */
    val onlyStaticMethods: Boolean = false,
)

@Serializable
data class RiskChange(
    val oldScore: Int,
    val oldGrade: String,
    val newScore: Int,
    val newGrade: String,
    val findingsAdded: List<AuditFinding>,
    val findingsResolved: List<AuditFinding>,
    val oldHardening: String,
    val newHardening: String,
)

@Serializable
data class DiffReport(
    val old: VersionInfo,
    val new: VersionInfo,
    val headline: List<String>,
    val changes: List<Change>,
    val code: CodeSummary,
    val classes: List<ClassChange>,
    val risk: RiskChange?,
    val oldCapabilities: List<Capability>,
    val newCapabilities: List<Capability>,
    val oldSdks: List<SdkFinding>,
    val newSdks: List<SdkFinding>,
    val notes: List<String>,
)

/**
 * What changed between two builds of an app, for release review and supply-chain checks: who
 * signed it, what it asks for (permissions, components, exported surface), what it talks to
 * (hosts, endpoints, data models), what it can do (sensitive platform capabilities, SDKs, native
 * code) and which code changed, matched across obfuscation by [CodeMatcher].
 *
 * The review is static and describes differences so a reviewer can confirm each one is intended.
 * Bytecode changes are OBSERVED; whether a source edit caused one is INFERRED, because R8 also
 * changes bytecode when unrelated code changes (inlining, outlining, class merging).
 */
object AppDiff {
    private val ORDER = listOf("HIGH", "MEDIUM", "LOW", "INFO")
    private val NOWHERE: Path = Path.of(System.getProperty("java.io.tmpdir"), "apkproject-diff-" + java.util.UUID.randomUUID(), "none")

    /** Everything one build contributes to the comparison. */
    class Snapshot(val path: Path) {
        val input: AppInput = when (val o = AppInput.open(path)) {
            is Outcome.Ok -> o.value
            is Outcome.Err -> throw IllegalArgumentException("${path.fileName}: ${o.failure}")
        }
        val dex = input.dexFiles()
        val resources: ResourceTable = input.parts.firstNotNullOfOrNull { it.read("resources.arsc") }?.let { ResourceTable.parse(it) } ?: ResourceTable.EMPTY
        private val base: InputPart = input.parts.firstOrNull { it.name == "base.apk" } ?: input.parts.first()
        val manifestXml: String? = base.read("AndroidManifest.xml")?.let { BinaryXml.decode(it, resources) }
        val manifest: Manifest? = manifestXml?.let { Manifest.parse(it) }
        val anatomy = AnatomyDetector.detect(input, dex)
        val signing: SigningInfo? = if (input.kind == InputKind.DEX) null else base.let { p ->
            val jar = p.names.filter { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }.mapNotNull { p.read(it) }
            ApkSigning.read(p.bytes, jar)
        }
        val entries: Map<String, Long> = input.allEntries().filter { !it.second.isDirectory }.associate { (part, e) -> (if (input.parts.size > 1) "${part.name}!" else "") + e.name to e.crc32 }
        // Bytecode and manifest only: no decompiled sources or decoded resources are needed.
        val contract = ApiContractExtractor.extract(NOWHERE, null, null, dex, emptyList())
        val audit = FrontendAudit.analyze(NOWHERE, manifestXml, dex, anatomy, input.allEntries().map { it.second.name })
        val code = CodeIndex(dex, resources, manifest?.versionName)
        val names = runCatching { NameOracle.recover(dex.map { it.second }, resources) }.getOrDefault(NameOracle.Result.EMPTY)
        val capabilities = Capabilities.of(code.classes.map { it.javaName to it.externalRefs }, manifest?.permissions)
        val sdks = Capabilities.sdks(dex.flatMap { (_, d) -> d.typeIds.indices.map { d.type(it) } }.toHashSet(), contract.hosts)

        fun info() = VersionInfo(
            path.fileName.toString(), input.sha256, input.kind.name, manifest?.packageName, manifest?.versionName, manifest?.versionCode,
            manifest?.minSdk, manifest?.targetSdk, CompilerMarker.read(dex).map { it.toString() }, code.classes.size,
            code.classes.sumOf { it.methods.size }, signing,
        )
    }

    class Result(val report: DiffReport, val classMap: String)

    fun compare(oldPath: Path, newPath: Path, log: (String) -> Unit = {}): Result {
        log("reading ${oldPath.fileName}")
        val a = Snapshot(oldPath)
        log("reading ${newPath.fileName}")
        val b = Snapshot(newPath)
        log("matching code across renames")
        val match = CodeMatcher.match(a.code, b.code)
        log("comparing methods of ${match.pairs.size} class pairs")
        return Result(build(a, b, match), classMap(a, b, match))
    }

    private fun build(a: Snapshot, b: Snapshot, match: CodeMatcher.Result): DiffReport {
        val changes = ArrayList<Change>()
        val notes = ArrayList<String>()
        signing(a, b, changes, notes)
        identity(a, b, changes)
        if (a.manifest != null && b.manifest != null) manifest(a.manifest, b.manifest, changes)
        else notes += "A manifest was not available on ${if (a.manifest == null) "the old" else "the new"} side (bare DEX or App Bundle protobuf), so permissions and components were not compared."
        network(a, b, match, changes)
        sdks(a, b, changes)
        libraries(a, b, changes)
        nativeAndAssets(a, b, changes)
        capabilities(a, b, changes)
        val risk = risk(a, b, changes)
        val (summary, classes) = code(a, b, match, changes)

        val sorted = changes.sortedWith(compareBy({ ORDER.indexOf(it.attention) }, { it.area }, { it.subject }))
        val headline = ArrayList<String>()
        val ai = a.info(); val bi = b.info()
        fun version(v: VersionInfo) = listOfNotNull(v.versionName, v.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { v.file }
        headline += "${version(ai)} → ${version(bi)}" + (b.manifest?.packageName?.let { " of $it" } ?: "")
        headline += "Code: ${summary.oldClasses} → ${summary.newClasses} classes; ${summary.modified} modified, ${summary.added} added, ${summary.removed} removed; " +
            "${summary.renamedByObfuscator} unchanged classes only renamed by the obfuscator."
        val counts = ORDER.associateWith { level -> sorted.count { it.attention == level } }
        headline += "Review first: ${counts["HIGH"]} high, ${counts["MEDIUM"]} medium; ${counts["LOW"]} low and ${counts["INFO"]} informational changes."
        risk?.let { headline += "Static risk score ${it.oldScore} (${it.oldGrade}) → ${it.newScore} (${it.newGrade}); ${it.findingsAdded.size} new and ${it.findingsResolved.size} resolved findings." }

        notes += "Static comparison of two APKs: nothing was run. Each change is a difference to confirm as intended, not a verdict on the app."
        notes += "Classes are matched by code, not by name, so obfuscator renames are not reported as changes. A MODIFIED class has different bytecode; R8 also changes bytecode when unrelated code changes (inlining), so not every modification is a source edit."
        notes += "SAME_BEHAVIOUR methods differ only in code layout (block order, registers): the dataflow matches once both sides use common names. MOVED methods are static methods R8 placed in another class this time."
        notes += "Inlined resource ids are compared by resource name, and the app's own version name inside strings is ignored, so a new string resource or a version bump does not mark code as changed."
        notes += "The risk comparison covers the manifest and code checks of `apkproject audit`; layout (UI-quality) checks are not part of the diff."
        return DiffReport(ai, bi, headline, sorted, summary, classes, risk, a.capabilities, b.capabilities, a.sdks, b.sdks, notes)
    }

    // ---- signing and identity ---------------------------------------------------------------

    private fun signing(a: Snapshot, b: Snapshot, out: MutableList<Change>, notes: MutableList<String>) {
        val sa = a.signing?.signers.orEmpty()
        val sb = b.signing?.signers.orEmpty()
        if (sa.isEmpty() || sb.isEmpty()) {
            if (a.input.kind != InputKind.DEX && b.input.kind != InputKind.DEX) notes += "One build is unsigned, so the signers were not compared."
            return
        }
        val da = sa.map { it.sha256 }.toSet()
        val db = sb.map { it.sha256 }.toSet()
        if (da == db) return
        val rotated = b.signing?.lineage.orEmpty().any { it in da }
        out += if (rotated) Change(
            "SIGNING", "CHANGED", "Signing key rotated",
            "The new build is signed by ${sb.joinToString { it.subject }} and carries an APK Signature Scheme v3 rotation lineage that includes the old certificate, so it installs as an update.",
            "MEDIUM", "OBSERVED",
        ) else if (da.intersect(db).isEmpty()) Change(
            "SIGNING", "CHANGED", "Different signing certificate",
            "Old: ${sa.joinToString { "${it.subject} (SHA-256 ${it.sha256.take(16)}…)" }}. New: ${sb.joinToString { "${it.subject} (SHA-256 ${it.sha256.take(16)}…)" }}. " +
                "Android will not install it over the old version; if it is offered as an update, it comes from another publisher or was re-signed.",
            "HIGH", "OBSERVED",
        ) else Change("SIGNING", "CHANGED", "Signer set changed", "Signers: ${sa.size} → ${sb.size}; some certificates are shared.", "MEDIUM", "OBSERVED")
    }

    private fun identity(a: Snapshot, b: Snapshot, out: MutableList<Change>) {
        val ma = a.manifest ?: return
        val mb = b.manifest ?: return
        if (ma.packageName != mb.packageName) out += Change("IDENTITY", "CHANGED", "Package name", "${ma.packageName} → ${mb.packageName}: this is a different app to Android, not an update.", "HIGH", "OBSERVED")
        if (ma.versionCode != null && mb.versionCode != null && mb.versionCode < ma.versionCode) {
            out += Change("IDENTITY", "CHANGED", "Version code went down", "${ma.versionCode} → ${mb.versionCode}: a downgrade.", "MEDIUM", "OBSERVED")
        }
        if (ma.targetSdk != null && mb.targetSdk != null && mb.targetSdk < ma.targetSdk) {
            out += Change("IDENTITY", "CHANGED", "targetSdkVersion lowered", "${ma.targetSdk} → ${mb.targetSdk}: the app opts back into older platform behaviour.", "MEDIUM", "OBSERVED")
        } else if (ma.targetSdk != mb.targetSdk) out += Change("IDENTITY", "CHANGED", "targetSdkVersion", "${ma.targetSdk} → ${mb.targetSdk}", "INFO", "OBSERVED")
        if (ma.minSdk != mb.minSdk) out += Change("IDENTITY", "CHANGED", "minSdkVersion", "${ma.minSdk} → ${mb.minSdk}", if ((mb.minSdk ?: 0) < (ma.minSdk ?: 0)) "LOW" else "INFO", "OBSERVED")
    }

    // ---- manifest ----------------------------------------------------------------------------

    /** The parts of a decoded manifest an update review compares. */
    class Manifest(
        val packageName: String?,
        val versionCode: Long?,
        val versionName: String?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val permissions: Set<String>,
        val declaredPermissions: Map<String, String>,
        val components: Map<String, Component>,
        val application: Map<String, String>,
        val queries: Set<String>,
        val metaData: Set<String>,
    ) {
        class Component(val tag: String, val name: String, val exported: Boolean, val permission: String?, val actions: Set<String>, val links: Set<String>)

        companion object {
            fun parse(xml: String): Manifest? {
                val doc = runCatching {
                    val f = DocumentBuilderFactory.newInstance()
                    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    f.isExpandEntityReferences = false
                    f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
                }.getOrNull() ?: return null
                val root = doc.documentElement
                val pkg = root.getAttribute("package").ifEmpty { null }
                fun Element.attr(n: String) = getAttribute("android:$n").ifEmpty { null }
                fun Element.children(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
                fun qualify(n: String) = if (n.startsWith('.') && pkg != null) pkg + n else n
                val sdk = root.children("uses-sdk").firstOrNull()
                val app = root.children("application").firstOrNull()
                val components = LinkedHashMap<String, Component>()
                for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) for (e in app?.children(tag).orEmpty()) {
                    val name = qualify(e.attr("name") ?: continue)
                    val filters = e.children("intent-filter")
                    val exported = e.attr("exported")?.let { it == "true" } ?: filters.isNotEmpty()
                    val actions = filters.flatMap { it.children("action") }.mapNotNull { it.attr("name") }.toSet()
                    val links = filters.flatMap { it.children("data") }.mapNotNull { d ->
                        val scheme = d.attr("scheme") ?: return@mapNotNull null
                        scheme + (d.attr("host")?.let { "://$it" } ?: ":")
                    }.toSet()
                    components["$tag $name"] = Component(tag, name, exported, e.attr("permission") ?: e.attr("readPermission"), actions, links)
                }
                val appAttrs = listOf("debuggable", "allowBackup", "usesCleartextTraffic", "networkSecurityConfig", "testOnly", "requestLegacyExternalStorage", "largeHeap", "sharedUserId")
                    .mapNotNull { n -> (app?.attr(n) ?: if (n == "sharedUserId") root.attr(n) else null)?.let { n to it } }.toMap()
                return Manifest(
                    pkg, root.attr("versionCode")?.toLongOrNull(), root.attr("versionName"),
                    sdk?.attr("minSdkVersion")?.toIntOrNull(), sdk?.attr("targetSdkVersion")?.toIntOrNull(),
                    root.children("uses-permission").mapNotNull { it.attr("name") }.toSet() + root.children("uses-permission-sdk-23").mapNotNull { it.attr("name") },
                    root.children("permission").mapNotNull { p -> p.attr("name")?.let { it to (p.attr("protectionLevel") ?: "normal") } }.toMap(),
                    components, appAttrs,
                    root.children("queries").flatMap { q -> q.children("package").mapNotNull { it.attr("name") } + q.children("intent").flatMap { i -> i.children("action").mapNotNull { it.attr("name") } } }.toSet(),
                    app?.children("meta-data")?.mapNotNull { it.attr("name") }.orEmpty().toSet(),
                )
            }
        }
    }

    /** Permissions that reach beyond the app's own data and deserve a closer look when they appear. */
    private val SPECIAL_PERMISSIONS = setOf(
        "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE", "BIND_ACCESSIBILITY_SERVICE",
        "BIND_DEVICE_ADMIN", "PACKAGE_USAGE_STATS", "WRITE_SETTINGS", "READ_PRIVILEGED_PHONE_STATE", "BIND_NOTIFICATION_LISTENER_SERVICE",
        "ACCESS_BACKGROUND_LOCATION", "USE_FULL_SCREEN_INTENT", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    )

    private fun manifest(a: Manifest, b: Manifest, out: MutableList<Change>) {
        for (p in (b.permissions - a.permissions).sorted()) {
            val short = p.removePrefix("android.permission.")
            val level = when {
                short in SPECIAL_PERMISSIONS -> "HIGH"
                p.startsWith("android.permission.") && short in FrontendAudit.DANGEROUS_PERMISSIONS -> "HIGH"
                else -> "LOW"
            }
            out += Change("PERMISSIONS", "ADDED", p, if (level == "HIGH") "A sensitive permission is new: confirm the feature needs it and that the privacy disclosure covers it." else "New permission.", level, "OBSERVED")
        }
        for (p in (a.permissions - b.permissions).sorted()) out += Change("PERMISSIONS", "REMOVED", p, "No longer requested.", "INFO", "OBSERVED")
        for ((p, level) in b.declaredPermissions - a.declaredPermissions.keys) out += Change("PERMISSIONS", "ADDED", "declares $p", "The app defines a new permission (protectionLevel $level).", "LOW", "OBSERVED")

        for ((key, c) in b.components) {
            val old = a.components[key]
            val open = c.exported && c.permission == null && "android.intent.action.MAIN" !in c.actions
            if (old == null) {
                out += Change("COMPONENTS", "ADDED", "${c.tag} ${c.name}",
                    if (open) "New exported ${c.tag} with no permission: other apps can start it. Confirm it validates every Intent it receives." else "New ${c.tag}${if (c.exported) " (exported, protected by ${c.permission ?: "the launcher"})" else ""}.",
                    if (open) "MEDIUM" else "LOW", "OBSERVED")
                continue
            }
            if (!old.exported && c.exported) out += Change("COMPONENTS", "CHANGED", "${c.tag} ${c.name}", "Now exported${if (c.permission == null) " without a permission" else ""}: other apps can reach it.", if (c.permission == null) "MEDIUM" else "LOW", "OBSERVED")
            if (old.permission != null && c.permission == null && c.exported) out += Change("COMPONENTS", "CHANGED", "${c.tag} ${c.name}", "Its permission (${old.permission}) was removed while it stays exported.", "MEDIUM", "OBSERVED")
            for (link in (c.links - old.links)) out += Change("COMPONENTS", "ADDED", "link $link", "${c.name} now handles $link links.", "LOW", "OBSERVED")
        }
        for ((key, c) in a.components) if (key !in b.components) out += Change("COMPONENTS", "REMOVED", "${c.tag} ${c.name}", "Removed.", "INFO", "OBSERVED")

        for (flag in (a.application.keys + b.application.keys).sorted()) {
            val before = a.application[flag]
            val after = b.application[flag]
            if (before == after) continue
            val (level, why) = when {
                flag == "debuggable" && after == "true" -> "HIGH" to "The new build is debuggable: anyone can attach a debugger and run code as the app."
                flag == "usesCleartextTraffic" && after == "true" -> "MEDIUM" to "Unencrypted HTTP is now allowed."
                flag == "allowBackup" && after == "true" -> "LOW" to "App data can now be backed up off the device."
                flag == "networkSecurityConfig" -> "LOW" to "The network security policy (trusted CAs, cleartext, pinning) changed; review the new file."
                flag == "sharedUserId" -> "HIGH" to "The shared user id changed."
                else -> "INFO" to ""
            }
            out += Change("MANIFEST", "CHANGED", "android:$flag", "${before ?: "unset"} → ${after ?: "unset"}. $why".trim(), level, "OBSERVED")
        }
        for (q in (b.queries - a.queries).sorted()) out += Change("MANIFEST", "ADDED", "can see $q", "Package visibility: the app now declares it queries $q.", "LOW", "OBSERVED")
        val newMeta = (b.metaData - a.metaData).sorted()
        if (newMeta.isNotEmpty()) out += Change("MANIFEST", "ADDED", "meta-data", "New keys (SDK configuration often lives here): ${newMeta.take(15).joinToString()}${if (newMeta.size > 15) ", …" else ""}", "INFO", "OBSERVED")
    }

    // ---- network, API and models -----------------------------------------------------------

    private fun network(a: Snapshot, b: Snapshot, match: CodeMatcher.Result, out: MutableList<Change>) {
        val ha = a.contract.hosts.toSet()
        val hb = b.contract.hosts.toSet()
        for (h in (hb - ha).sorted()) {
            val urls = b.contract.urls.filter { it.host == h }.map { it.url }.take(3)
            out += Change("NETWORK", "ADDED", h, "A new server address in the app: ${urls.joinToString()}. Confirm what is sent there.", "MEDIUM", "OBSERVED")
        }
        for (h in (ha - hb).sorted()) out += Change("NETWORK", "REMOVED", h, "No longer referenced.", "INFO", "OBSERVED")

        fun key(e: Endpoint) = "${e.method} ${e.path}"
        val ea = a.contract.endpoints.groupBy(::key)
        val eb = b.contract.endpoints.groupBy(::key)
        for (k in (eb.keys - ea.keys).sorted()) out += Change("API", "ADDED", k, "New endpoint the client calls (${eb.getValue(k).first().params.joinToString { "${it.location} ${it.name}" }.ifEmpty { "no parameters" }}).", "LOW", "OBSERVED")
        for (k in (ea.keys - eb.keys).sorted()) out += Change("API", "REMOVED", k, "No longer called.", "INFO", "OBSERVED")
        for (k in (ea.keys intersect eb.keys).sorted()) {
            val pa = ea.getValue(k).flatMap { it.params }.map { "${it.location} ${it.name}" }.toSet()
            val pb = eb.getValue(k).flatMap { it.params }.map { "${it.location} ${it.name}" }.toSet()
            if (pa != pb) out += Change("API", "CHANGED", k, listOfNotNull(
                (pb - pa).takeIf { it.isNotEmpty() }?.let { "new parameters: ${it.joinToString()}" },
                (pa - pb).takeIf { it.isNotEmpty() }?.let { "removed: ${it.joinToString()}" },
            ).joinToString("; "), "LOW", "OBSERVED")
        }

        // Models are found by class; obfuscated names are mapped through the code matching.
        fun descriptor(javaName: String) = "L" + javaName.replace('.', '/') + ";"
        val modelsB = b.contract.models.associateBy { it.className }
        val seen = HashSet<String>()
        for (m in a.contract.models) {
            val counterpart = match.pairOfOld(descriptor(m.className))?.new?.javaName ?: m.className
            val n = modelsB[counterpart] ?: modelsB.values.firstOrNull { o -> o.fields.map { it.jsonName }.toSet() == m.fields.map { it.jsonName }.toSet() && o.className !in seen }
            if (n == null) { out += Change("MODELS", "REMOVED", m.className, "Data model no longer parsed.", "INFO", "INFERRED"); continue }
            seen += n.className
            val fa = m.fields.associateBy { it.jsonName }
            val fb = n.fields.associateBy { it.jsonName }
            val added = fb.keys - fa.keys
            val removed = fa.keys - fb.keys
            val retyped = (fa.keys intersect fb.keys).filter { fa.getValue(it).nullable != fb.getValue(it).nullable }
            if (added.isNotEmpty() || removed.isNotEmpty() || retyped.isNotEmpty()) out += Change("MODELS", "CHANGED", n.className, listOfNotNull(
                added.takeIf { it.isNotEmpty() }?.let { "new JSON fields: ${it.sorted().joinToString()}" },
                removed.takeIf { it.isNotEmpty() }?.let { "removed: ${it.sorted().joinToString()}" },
                retyped.takeIf { it.isNotEmpty() }?.let { "nullability changed: ${it.sorted().joinToString()}" },
            ).joinToString("; "), "INFO", "OBSERVED")
        }
        for (n in b.contract.models) if (n.className !in seen) out += Change("MODELS", "ADDED", n.className, "New data model with JSON fields ${n.fields.take(8).joinToString { it.jsonName }}${if (n.fields.size > 8) ", …" else ""}.", "INFO", "INFERRED")
    }

    private fun sdks(a: Snapshot, b: Snapshot, out: MutableList<Change>) {
        val na = a.sdks.associateBy { it.name }
        for (s in b.sdks) if (s.name !in na) out += Change(
            "SDKS", "ADDED", s.name, "New ${s.category.lowercase()} SDK (${s.signals.take(3).joinToString()}). Check what data it collects and that the privacy disclosure covers it.",
            if (s.category in setOf("Advertising", "Attribution", "Session recording", "Analytics", "Social / analytics")) "MEDIUM" else "LOW", s.evidence,
        )
        val nb = b.sdks.map { it.name }.toSet()
        for (s in a.sdks) if (s.name !in nb) out += Change("SDKS", "REMOVED", s.name, "No longer present.", "INFO", s.evidence)
    }

    private fun libraries(a: Snapshot, b: Snapshot, out: MutableList<Change>) {
        val la = a.anatomy.libraries.associate { it.coordinate to it.version }
        val lb = b.anatomy.libraries.associate { it.coordinate to it.version }
        for ((c, v) in lb) {
            val old = la[c]
            when {
                old == null -> out += Change("LIBRARIES", "ADDED", c, "Version $v.", "LOW", "OBSERVED")
                old != v -> out += Change("LIBRARIES", "CHANGED", c, "$old → $v", "INFO", "OBSERVED")
            }
        }
        for (c in la.keys - lb.keys) out += Change("LIBRARIES", "REMOVED", c, "Version ${la.getValue(c)} removed.", "INFO", "OBSERVED")
    }

    private fun nativeAndAssets(a: Snapshot, b: Snapshot, out: MutableList<Change>) {
        fun native(n: String) = Regex("""(?:.*!)?lib/[^/]+/[^/]+\.so""").matches(n)
        fun hiddenCode(n: String) = n.contains("assets/") && (n.endsWith(".dex") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".so"))
        val libsA = a.entries.filterKeys(::native)
        val libsB = b.entries.filterKeys(::native)
        fun byLib(m: Map<String, Long>) = m.keys.groupBy { it.substringAfterLast('/') }
        val ga = byLib(libsA)
        val gb = byLib(libsB)
        for ((lib, paths) in gb) {
            val before = ga[lib]
            if (before == null) out += Change("NATIVE", "ADDED", lib, "New native library (${paths.map { it.substringBeforeLast('/').substringAfterLast('/') }.sorted().joinToString()}). Native code is not decompiled; review where it comes from.", "MEDIUM", "OBSERVED")
            else if (paths.any { p -> libsA[p] != null && libsA[p] != libsB[p] }) out += Change("NATIVE", "CHANGED", lib, "Binary changed.", "LOW", "OBSERVED")
        }
        for (lib in ga.keys - gb.keys) out += Change("NATIVE", "REMOVED", lib, "Removed.", "INFO", "OBSERVED")
        for (n in b.entries.keys.filter(::hiddenCode).sorted()) if (a.entries[n] != b.entries[n]) out += Change(
            "ASSETS", if (n in a.entries) "CHANGED" else "ADDED", n, "Code shipped as an asset can be loaded at run time, outside the reviewed DEX. Confirm why it is there.", "HIGH", "OBSERVED",
        )
        val skip = setOf("classes", "lib/", "META-INF/")
        fun group(n: String) = n.substringAfter('!').let { e -> when { e.startsWith("res/") -> "res/"; e.startsWith("assets/") -> "assets/"; e.startsWith("META-INF/") -> "META-INF/"; e.startsWith("lib/") -> "lib/"; e.startsWith("classes") -> "classes"; else -> "other" } }
        val added = (b.entries.keys - a.entries.keys).filter { group(it) !in skip }
        val removed = (a.entries.keys - b.entries.keys).filter { group(it) !in skip }
        val changed = (a.entries.keys intersect b.entries.keys).filter { group(it) !in skip && a.entries[it] != b.entries[it] }
        for (g in (added + removed + changed).map(::group).distinct().sorted()) {
            val ad = added.count { group(it) == g }; val rm = removed.count { group(it) == g }; val ch = changed.count { group(it) == g }
            val sample = (added.filter { group(it) == g } + changed.filter { group(it) == g }).filter { !it.startsWith("res/") }.take(5)
            out += Change("ASSETS", "CHANGED", g, "$ad added, $rm removed, $ch changed files${if (sample.isNotEmpty()) " (e.g. ${sample.joinToString()})" else ""}.", "INFO", "OBSERVED")
        }
    }

    private fun capabilities(a: Snapshot, b: Snapshot, out: MutableList<Change>) {
        val ca = a.capabilities.associateBy { it.id }
        for (c in b.capabilities) {
            val before = ca[c.id]
            val where = "${c.apis.take(3).joinToString()} (in ${c.usedIn.take(3).joinToString()}${if (c.usedIn.size > 3) ", …" else ""})"
            if (before == null) {
                out += if (c.permissionDeclared == false) Change(
                    "CAPABILITIES", "ADDED", c.title,
                    "The code now references $where, but the app does not request ${c.permissions.joinToString(" or ")}, so it cannot use it (often unused library code). ${c.reference}", "LOW", "OBSERVED",
                ) else Change(
                    "CAPABILITIES", "ADDED", c.title,
                    "The code now uses $where. Confirm the feature needs it${if (c.category == "PRIVACY") " and that it is disclosed" else ""}. ${c.reference}", c.attention, "OBSERVED",
                )
            } else if (before.permissionDeclared == false && c.permissionDeclared == true) out += Change(
                "CAPABILITIES", "CHANGED", c.title,
                "Code for this was already present, but the app now requests ${c.permissions.joinToString(" or ")}, so it can run: $where. ${c.reference}", c.attention, "OBSERVED",
            )
        }
        val cb = b.capabilities.map { it.id }.toSet()
        for (c in a.capabilities) if (c.id !in cb) out += Change("CAPABILITIES", "REMOVED", c.title, "No longer used.", "INFO", "OBSERVED")
    }

    private fun risk(a: Snapshot, b: Snapshot, out: MutableList<Change>): RiskChange {
        val fa = a.audit.findings.associateBy { it.id }
        val fb = b.audit.findings.associateBy { it.id }
        val added = fb.values.filter { it.id !in fa }
        val resolved = fa.values.filter { it.id !in fb }
        fun level(severity: String) = when (severity) { "CRITICAL", "HIGH" -> "HIGH"; "MEDIUM" -> "MEDIUM"; "LOW" -> "LOW"; else -> "INFO" }
        for (f in added) out += Change("RISK", "ADDED", f.title, "${f.detail} Fix: ${f.fix} (${f.reference})", level(f.severity), f.evidence)
        for (f in resolved) out += Change("RISK", "REMOVED", f.title, "Resolved in the new build.", "INFO", f.evidence)
        val ha = a.audit.hardening
        val hb = b.audit.hardening
        val newProtectors = hb.protectors.map { it.name } - ha.protectors.map { it.name }.toSet()
        if (newProtectors.isNotEmpty()) out += Change("HARDENING", "ADDED", newProtectors.joinToString(), "The code is now protected (packed or encrypted), so this and later updates cannot be fully reviewed statically.", "MEDIUM", "OBSERVED")
        if (ha.level != hb.level || ha.obfuscation.level != hb.obfuscation.level) out += Change(
            "HARDENING", "CHANGED", "Resilience", "Protection ${ha.level} → ${hb.level}; obfuscation ${ha.obfuscation.level} → ${hb.obfuscation.level}.", "INFO", "OBSERVED",
        )
        return RiskChange(a.audit.risk.score, a.audit.risk.grade, b.audit.risk.score, b.audit.risk.grade, added, resolved, ha.level, hb.level)
    }

    // ---- code --------------------------------------------------------------------------------

    private fun code(a: Snapshot, b: Snapshot, match: CodeMatcher.Result, out: MutableList<Change>): Pair<CodeSummary, List<ClassChange>> {
        val layout = CodeMatcher.LayoutCheck(match)
        var mu = 0; var ms = 0; var mm = 0; var ma = 0; var mr = 0; var mv = 0; var mo = 0
        val classes = ArrayList<ClassChange>()
        var unchanged = 0
        fun label(c: CodeIndex.Class, s: Snapshot): Pair<String, String?> =
            s.names.classes[c.name]?.let { it.name to "recovered from ${it.rule}" } ?: (c.javaName to null)
        fun m(x: CodeIndex.Method) = x.name + x.proto
        for (p in match.pairs) {
            val methods = if (p.how == CodeMatcher.How.IDENTICAL_CODE || p.how == CodeMatcher.How.IDENTICAL_CODE_AMBIGUOUS) {
                p.old.methods.zip(p.new.methods).map { CodeMatcher.MethodPair(it.first, it.second, CodeMatcher.MethodStatus.UNCHANGED, 1.0) }
            } else CodeMatcher.methods(match, p, layout)
            mu += methods.count { it.status == CodeMatcher.MethodStatus.UNCHANGED }
            ms += methods.count { it.status == CodeMatcher.MethodStatus.SAME_BEHAVIOUR }
            mm += methods.count { it.status == CodeMatcher.MethodStatus.MODIFIED }
            ma += methods.count { it.status == CodeMatcher.MethodStatus.ADDED }
            mr += methods.count { it.status == CodeMatcher.MethodStatus.REMOVED }
            mv += methods.count { it.status == CodeMatcher.MethodStatus.MOVED && it.new != null }
            mo += methods.count { it.status == CodeMatcher.MethodStatus.REFERENCES_ONLY }
            val fieldsChanged = p.old.fields.size != p.new.fields.size
            val changed = methods.any { it.status in setOf(CodeMatcher.MethodStatus.MODIFIED, CodeMatcher.MethodStatus.ADDED, CodeMatcher.MethodStatus.REMOVED) } || fieldsChanged
            val referencesOnly = !changed && methods.any { it.status == CodeMatcher.MethodStatus.REFERENCES_ONLY }
            if (!changed && !referencesOnly) { unchanged++; continue }
            val (name, evidence) = label(p.new, b).takeIf { it.second != null } ?: label(p.old, a)
            val merged = p.old.name in a.code.merged || p.new.name in b.code.merged
            // What the edited methods bring that the class did not have (methods R8 only moved in are not edits).
            val editedNew = methods.filter { it.new != null && it.status in setOf(CodeMatcher.MethodStatus.MODIFIED, CodeMatcher.MethodStatus.ADDED, CodeMatcher.MethodStatus.REFERENCES_ONLY) }.map { it.new!! }
            val editedOld = methods.filter { it.old != null && it.status in setOf(CodeMatcher.MethodStatus.MODIFIED, CodeMatcher.MethodStatus.REMOVED, CodeMatcher.MethodStatus.REFERENCES_ONLY) }.map { it.old!! }
            val stringsAdded = editedNew.flatMap { it.strings }.distinct() - p.old.strings
            val stringsRemoved = editedOld.flatMap { it.strings }.distinct() - p.new.strings
            val apisAdded = editedNew.flatMap { it.externalRefs }.distinct() - p.old.externalRefs
            val apisRemoved = editedOld.flatMap { it.externalRefs }.distinct() - p.new.externalRefs
            classes += ClassChange(
                if (referencesOnly) "REFERENCES_ONLY" else "MODIFIED", p.old.javaName, p.new.javaName, name, evidence, p.how.name, p.similarity,
                methods.filter { it.status == CodeMatcher.MethodStatus.MODIFIED || (referencesOnly && it.status == CodeMatcher.MethodStatus.REFERENCES_ONLY) }.map { m(it.new!!) },
                methods.filter { it.status == CodeMatcher.MethodStatus.ADDED }.map { m(it.new!!) },
                methods.filter { it.status == CodeMatcher.MethodStatus.REMOVED }.map { m(it.old!!) },
                stringsAdded.take(20), stringsRemoved.take(20),
                apisAdded.map(::member).distinct().take(20), apisRemoved.map(::member).distinct().take(20),
                merged,
                !fieldsChanged && methods.none { it.status == CodeMatcher.MethodStatus.MODIFIED } &&
                    methods.filter { it.status == CodeMatcher.MethodStatus.ADDED || it.status == CodeMatcher.MethodStatus.REMOVED }.all { (it.new ?: it.old)!!.isStatic },
            )
        }
        for (c in match.added) {
            val (name, evidence) = label(c, b)
            classes += ClassChange("ADDED", null, c.javaName, name, evidence, null, null, methodsAdded = c.methods.map(::m), stringsAdded = c.strings.take(20), apisAdded = c.externalRefs.map(::member).distinct().take(20))
        }
        for (c in match.removed) {
            val (name, evidence) = label(c, a)
            classes += ClassChange("REMOVED", c.javaName, null, name, evidence, null, null, methodsRemoved = c.methods.map(::m))
        }
        val oldNames = a.code.classes.map { it.name }.toSet()
        val newNames = b.code.classes.mapTo(HashSet()) { it.name }
        val reused = match.pairs.count { it.old.name != it.new.name && it.old.name in newNames } + match.removed.count { it.name in newNames }
        val summary = CodeSummary(
            a.code.classes.size, b.code.classes.size,
            match.pairs.count { it.how == CodeMatcher.How.NAME }, match.pairs.count { it.how == CodeMatcher.How.IDENTICAL_CODE },
            match.pairs.count { it.how == CodeMatcher.How.SIMILAR_CODE }, match.pairs.count { it.how == CodeMatcher.How.IDENTICAL_CODE_AMBIGUOUS },
            match.pairs.count { it.old.name != it.new.name }, reused,
            unchanged, classes.count { it.status == "MODIFIED" }, match.added.size, match.removed.size,
            mu, ms, mm, ma, mr, mv, mo, classes.count { it.status == "REFERENCES_ONLY" }, classes.count { it.status == "MODIFIED" && it.compilerMerged }, match.staticPairs.size, match.rounds,
        )
        if (oldNames.isNotEmpty()) out += Change(
            "CODE", "CHANGED", "Classes",
            "${summary.modified} modified (${summary.modifiedMerged} of them classes R8 merged from several), ${summary.added} added, ${summary.removed} removed of ${summary.newClasses}; methods: $mm modified, $ma added, $mr removed. " +
                "Not counted as changes: $ms methods that differ only in layout, $mv static methods R8 moved, and ${summary.referencesOnly} classes whose code is the same but refers to classes R8 grouped differently.",
            "INFO", "INFERRED",
        )
        val rank = listOf("MODIFIED", "ADDED", "REMOVED", "REFERENCES_ONLY")
        val ranked = classes.sortedWith(compareBy<ClassChange>({ rank.indexOf(it.status) }, { it.compilerMerged || it.onlyStaticMethods }, { -it.apisAdded.size - it.stringsAdded.size }, { -it.methodsModified.size }, { it.label }))
        return summary to ranked.take(3000)
    }

    private fun member(ref: String) = ref.substringBefore('(').substringBefore(':').removePrefix("L").replace(";->", ".").replace('/', '.')

    private fun classMap(a: Snapshot, b: Snapshot, match: CodeMatcher.Result): String = buildString {
        append("# old\tnew\tmatched_by\tsimilarity\n")
        for (p in match.pairs) append(p.old.name).append('\t').append(p.new.name).append('\t').append(p.how.name).append('\t').append("%.3f".format(p.similarity)).append('\n')
        for (c in match.removed) append(c.name).append("\t-\tREMOVED\t0\n")
        for (c in match.added) append("-\t").append(c.name).append("\tADDED\t0\n")
    }
}

/** Human-readable rendering of a [DiffReport]. */
object DiffMarkdown {
    fun render(r: DiffReport): String = buildString {
        appendLine("# Update review: ${r.old.file} → ${r.new.file}")
        appendLine()
        r.headline.forEach { appendLine("- $it") }
        appendLine()
        appendLine("| | Old | New |")
        appendLine("|---|---|---|")
        appendLine("| Package | ${r.old.packageName ?: "-"} | ${r.new.packageName ?: "-"} |")
        appendLine("| Version | ${r.old.versionName ?: "-"} (${r.old.versionCode ?: "-"}) | ${r.new.versionName ?: "-"} (${r.new.versionCode ?: "-"}) |")
        appendLine("| min / target SDK | ${r.old.minSdk ?: "-"} / ${r.old.targetSdk ?: "-"} | ${r.new.minSdk ?: "-"} / ${r.new.targetSdk ?: "-"} |")
        appendLine("| Signer | ${signer(r.old)} | ${signer(r.new)} |")
        appendLine("| Classes / methods | ${r.old.classes} / ${r.old.methods} | ${r.new.classes} / ${r.new.methods} |")
        appendLine("| Compiler | ${r.old.compiler.joinToString().ifEmpty { "-" }} | ${r.new.compiler.joinToString().ifEmpty { "-" }} |")
        r.risk?.let { appendLine("| Risk score | ${it.oldScore} (${it.oldGrade}) | ${it.newScore} (${it.newGrade}) |") }
        appendLine()
        for (level in listOf("HIGH", "MEDIUM", "LOW", "INFO")) {
            val items = r.changes.filter { it.attention == level }
            if (items.isEmpty()) continue
            appendLine("## ${when (level) { "HIGH" -> "Review first"; "MEDIUM" -> "Review"; "LOW" -> "Worth a look"; else -> "For information" }} ($level, ${items.size})")
            appendLine()
            appendLine("| Area | Change | Subject | Detail | Evidence |")
            appendLine("|---|---|---|---|---|")
            for (c in items) appendLine("| ${c.area} | ${c.kind} | ${cell(c.subject)} | ${cell(c.detail)} | ${c.evidence} |")
            appendLine()
        }
        val s = r.code
        appendLine("## Code, matched across obfuscation")
        appendLine()
        appendLine("Classes are paired by what renaming cannot change (operations, constants, strings, framework calls, the shape of references), so the obfuscator's renames are not changes.")
        appendLine()
        appendLine("| Measure | Count |")
        appendLine("|---|---:|")
        appendLine("| Classes old → new | ${s.oldClasses} → ${s.newClasses} |")
        appendLine("| Paired by kept name | ${s.matchedByName} |")
        appendLine("| Paired by identical code | ${s.matchedByIdenticalCode} |")
        appendLine("| Paired by similar code | ${s.matchedBySimilarCode} |")
        appendLine("| Identical, interchangeable copies | ${s.matchedAmbiguously} |")
        appendLine("| Renamed by the obfuscator (same class, new name) | ${s.renamedByObfuscator} |")
        appendLine("| Names reused for a different class | ${s.reusedNames} |")
        appendLine("| Unchanged / modified / added / removed classes | ${s.unchanged} / ${s.modified} / ${s.added} / ${s.removed} |")
        appendLine("| Modified classes that R8 merged from several (often regrouping, not edits) | ${s.modifiedMerged} |")
        appendLine("| Methods modified / added / removed | ${s.methodsModified} / ${s.methodsAdded} / ${s.methodsRemoved} |")
        appendLine("| Methods that differ only in layout | ${s.methodsSameBehaviour} |")
        appendLine("| Static methods paired across classes / moved by R8 | ${s.staticMethodsPaired} / ${s.methodsMoved} |")
        appendLine("| Classes (methods) differing only in references R8 regrouped | ${s.referencesOnly} (${s.methodsReferencesOnly}) |")
        appendLine()
        if (r.classes.isNotEmpty()) {
            appendLine("### Changed classes")
            appendLine()
            appendLine("Old and new names as they appear in each APK. A label in *italics* was recovered from compiler evidence (INFERRED). REFERENCES_ONLY classes run the same instructions but refer to classes R8 grouped differently; they are listed last and not counted as modified. (R8-merged) marks a class R8 built from several, whose contents it regroups between builds; (static methods only) a class where no method was edited and only static methods came or went, which R8 also does when it regroups helpers. Both are listed after the other modified classes.")
            appendLine()
            appendLine("| Status | Class | Old → new name | Methods (mod/add/rem) | New strings | New framework calls |")
            appendLine("|---|---|---|---|---|---|")
            for (c in r.classes.take(300)) {
                val label = (if (c.labelEvidence != null) "*${c.label}*" else c.label) + (if (c.compilerMerged) " (R8-merged)" else "") + (if (c.onlyStaticMethods && c.status == "MODIFIED") " (static methods only)" else "")
                appendLine("| ${c.status} | ${cell(label)} | ${cell("${c.oldName ?: "-"} → ${c.newName ?: "-"}")} | ${c.methodsModified.size}/${c.methodsAdded.size}/${c.methodsRemoved.size} | ${cell(c.stringsAdded.take(3).joinToString { "\"$it\"" })} | ${cell(c.apisAdded.take(3).joinToString())} |")
            }
            if (r.classes.size > 300) appendLine("\n${r.classes.size - 300} more in diff.json.")
            appendLine()
        }
        if (r.newCapabilities.isNotEmpty()) {
            appendLine("## Capabilities in the new build")
            appendLine()
            appendLine("| Capability | Category | Since | Permission | Used in |")
            appendLine("|---|---|---|---|---|")
            val old = r.oldCapabilities.map { it.id }.toSet()
            for (c in r.newCapabilities) {
                val permission = when (c.permissionDeclared) {
                    true -> "requested"
                    false -> "not requested (cannot run)"
                    null -> if (c.permissions.isEmpty()) "none needed" else "unknown"
                }
                appendLine("| ${c.title} | ${c.category} | ${if (c.id in old) "both builds" else "**new**"} | $permission | ${cell(c.usedIn.take(3).joinToString())} |")
            }
            appendLine()
        }
        appendLine("## Notes")
        appendLine()
        r.notes.forEach { appendLine("- $it") }
    }

    private fun signer(v: VersionInfo) = v.signing?.signers?.firstOrNull()?.let { "${it.subject.substringBefore(',')} (${it.sha256.take(12)}…, ${it.schemes.joinToString("+")})" } ?: "-"

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").let { if (it.length > 220) it.take(217) + "…" else it }
}
