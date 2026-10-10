package dev.apkproject.app

import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable

/**
 * The app's hardening, read statically — the MASVS-RESILIENCE picture that matters on "solid",
 * well-protected apps:
 *
 *  - how obfuscated the code is, and so how much is recoverable;
 *  - packers and commercial protectors it ships ([Protector]);
 *  - anti-analysis and integrity defenses it runs ([Defense]): root/emulator/debugger/Frida
 *    detection, SSL pinning, tamper checks.
 *
 * This is descriptive, for an app's owner or an authorized tester: it reports which protections are
 * present and how they limit static recovery. It never bypasses, disables or defeats a protection,
 * and gives no steps to do so. A protector that loads its real code at runtime leaves only a loader
 * in the APK, so the tool still returns a useful report (toolkit, resources, protections) instead
 * of failing — that is what "works even on solid apps" means here.
 */
@Serializable
data class Hardening(
    /** NONE, BASIC or HARDENED, from how many protectors and defenses were found. */
    val level: String,
    val obfuscation: Obfuscation,
    val protectors: List<Protector>,
    val defenses: List<Defense>,
    /** How the hardening limits what this tool can recover. */
    val recoveryImpact: String,
)

@Serializable
data class Obfuscation(
    /** NONE, LIGHT, PARTIAL, HEAVY or UNKNOWN. */
    val level: String,
    /** Fraction (0..1) of the app's classes whose names are minified (1–2 characters). */
    val minifiedRatio: Double,
    val tool: String?,
    val evidence: String,
)

@Serializable
data class Protector(
    val name: String,
    /** PACKER, OBFUSCATOR or RASP. */
    val kind: String,
    val evidence: List<String>,
    val note: String,
)

@Serializable
data class Defense(
    val name: String,
    /** How confident: OBSERVED (a distinctive artifact) or INFERRED (code/string indicators). */
    val evidence: String,
    val indicators: List<String>,
    /** The MASVS control it supports. */
    val masvs: String,
)

object HardeningAudit {
    fun analyze(dex: List<Pair<String, DexFile>>, anatomy: AppAnatomy, entries: List<String>, manifestXml: String?): Hardening {
        val types = HashSet<String>()
        val methodNames = HashSet<String>()
        val strings = HashSet<String>()
        for ((_, d) in dex) {
            for (t in d.typeIds.indices) types += d.type(t)
            for (m in d.methodIds) methodNames += d.string(m.nameIdx)
            strings += d.strings
        }
        val nativeLibs = anatomy.nativeLibraries.map { it.path.substringAfterLast('/') }.toSet()
        val entryNames = entries.map { it.substringAfterLast('!').lowercase() }
        val loaderClass = manifestXml?.let { Regex("""<application\b[^>]*android:name="([^"]+)"""").find(it)?.groupValues?.get(1) }

        val protectors = detectProtectors(types, nativeLibs, entryNames, loaderClass)
        val defenses = detectDefenses(types, methodNames, strings, manifestXml)
        val obfuscation = obfuscation(types, dex)

        val level = when {
            protectors.any { it.kind == "PACKER" } || defenses.size >= 3 -> "HARDENED"
            protectors.isNotEmpty() || defenses.isNotEmpty() || obfuscation.level == "HEAVY" -> "BASIC"
            else -> "NONE"
        }
        val impact = buildString {
            val packer = protectors.firstOrNull { it.kind == "PACKER" }
            if (packer != null) append("Packed with ${packer.name}: the real DEX is decrypted and loaded at runtime, so it is not present statically. This report covers the loader stub and the resources; run the tool on an unpacked dump for the full code. ")
            when (obfuscation.level) {
                "HEAVY" -> append("Code is heavily obfuscated (${(obfuscation.minifiedRatio * 100).toInt()}% of app classes renamed): original identifiers are mostly unrecoverable; recovered names come from compiler evidence only.")
                "PARTIAL" -> append("Code is partly obfuscated: some names are recoverable, many are not.")
                "NONE", "LIGHT" -> if (packer == null) append("Code is not obfuscated: identifiers are intact and recover well.")
                else -> {}
            }
            if (isEmpty()) append("No hardening detected that limits recovery.")
        }
        return Hardening(level, obfuscation, protectors, defenses, impact)
    }

    // ---- Packers, protectors, obfuscators -------------------------------------------------

    /** (name, kind, native-lib basenames, asset/entry substrings, loader/class descriptor substrings, note). */
    private data class Sig(val name: String, val kind: String, val libs: Set<String>, val assets: Set<String>, val classes: Set<String>, val note: String)

    private val PROTECTORS = listOf(
        Sig("Bangcle / SecShell", "PACKER", setOf("libsecexe.so", "libsecmain.so", "libSecShell.so", "libDexHelper.so"), setOf("bangcle_classes.jar", "secdata0.jar"), setOf("Lcom/secneo/apkwrapper/", "Lcom/secshell/"), "Bangcle/SecNeo app shell."),
        Sig("Qihoo 360 Jiagu", "PACKER", setOf("libjiagu.so", "libjiagu_art.so", "libjiagu_x86.so", "libprotectClass.so"), setOf("libjiagu"), setOf("Lcom/stub/StubApp", "Lcom/qihoo/util/"), "Qihoo 360 Jiagu packer."),
        Sig("Tencent Legu", "PACKER", setOf("libshella.so", "libshellx.so", "libtup.so", "libexec.so", "libBugly.so"), setOf("0oo00l111l1l", "tosversion"), setOf("Lcom/tencent/StubShell/", "Lcom/tencent/bugly/"), "Tencent Legu packer."),
        Sig("Ijiami", "PACKER", setOf("libexec.so", "libexecmain.so", "libijm.so"), setOf("ijiami.dat", "ijiami.ajm"), setOf("Lcom/shell/", "Lcom/ijm/"), "Ijiami packer."),
        Sig("Baidu Protect", "PACKER", setOf("libbaiduprotect.so"), setOf("baiduprotect"), setOf("Lcom/baidu/protect/"), "Baidu app protection."),
        Sig("Alibaba / Mobile Security", "PACKER", setOf("libmobisec.so", "libsgmain.so", "libsgsecuritybody.so"), setOf("libmobisec"), setOf("Lcom/ali/mobisecenhance/", "Lcom/alibaba/wireless/security/"), "Alibaba/Aliyun protection."),
        Sig("NQ Shield", "PACKER", setOf("libnqshield.so"), setOf("nqshield"), setOf("Lcom/nqshield/"), "NQ (NetQin) Shield packer."),
        Sig("DexProtector", "PACKER", setOf("libdexprotector.so"), setOf("dp.arm", "dp.x86", "dexprotector"), setOf("Lcom/licel/dexprotector/"), "Licel DexProtector."),
        Sig("APKProtect", "PACKER", setOf("libAPKProtect.so", "libapkprotect.so"), setOf("apkprotect"), emptySet(), "APKProtect packer."),
        Sig("Promon SHIELD", "RASP", setOf("libshield.so"), emptySet(), setOf("Lno/promon/"), "Promon SHIELD in-app protection (RASP)."),
        Sig("Appdome", "RASP", emptySet(), setOf("appdome"), setOf("Lcom/appdome/"), "Appdome no-code protection (RASP)."),
        Sig("Arxan / Digital.ai", "RASP", setOf("libarxan.so"), setOf("arxan"), setOf("Lcom/arxan/"), "Arxan / Digital.ai App Protection (RASP)."),
        Sig("Guardsquare DexGuard", "OBFUSCATOR", emptySet(), setOf("dexguard"), setOf("Lcom/guardsquare/dexguard/", "Ldexguard/"), "DexGuard: string encryption, reflection and control-flow obfuscation."),
        Sig("Virbox / SenseShield", "PACKER", setOf("libv++_64.so", "libv++.so", "libsandhook.so"), setOf("virbox"), emptySet(), "Virbox Protector."),
    )

    private fun detectProtectors(types: Set<String>, nativeLibs: Set<String>, entries: List<String>, loaderClass: String?): List<Protector> {
        val out = ArrayList<Protector>()
        for (sig in PROTECTORS) {
            val evidence = ArrayList<String>()
            sig.libs.filter { it in nativeLibs }.forEach { evidence += "native library $it" }
            sig.assets.filter { a -> entries.any { it.contains(a) } }.forEach { evidence += "file *$it*" }
            sig.classes.filter { c -> types.any { it.startsWith(c) } || loaderClass?.let { "L${it.replace('.', '/')};".startsWith(c) } == true }.forEach { evidence += "class $it*" }
            if (evidence.isNotEmpty()) out += Protector(sig.name, sig.kind, evidence.distinct().take(4), sig.note)
        }
        return out
    }

    // ---- Anti-analysis and integrity defenses ---------------------------------------------

    private fun detectDefenses(types: Set<String>, methods: Set<String>, strings: Set<String>, manifestXml: String?): List<Defense> {
        // Lower-cased string pool, used only for whole-token matches (not substrings): tokens here
        // are distinctive enough that a substring would overmatch (e.g. "xposed" inside
        // "ExposedDropdownMenu", "frida" inside "blackfriday"), so a match requires the token to be
        // a path/package element, a filename, or a standalone word.
        val lower = strings.asSequence().map { it.lowercase() }.filter { it.length in 3..400 }.toHashSet()
        fun hasToken(token: String) = lower.any { s ->
            var i = s.indexOf(token)
            while (i >= 0) {
                val before = if (i == 0) ' ' else s[i - 1]
                val after = if (i + token.length >= s.length) ' ' else s[i + token.length]
                // A token boundary is a non-identifier character ('.', '/', '-', '_' count as separators).
                fun boundary(c: Char) = !(c.isLetterOrDigit())
                if (boundary(before) && boundary(after)) return true
                i = s.indexOf(token, i + 1)
            }
            false
        }
        fun anyToken(vararg tokens: String) = tokens.filter { hasToken(it) }
        fun anyType(vararg needles: String) = needles.filter { n -> types.any { it.contains(n) } }
        val out = ArrayList<Defense>()

        (anyToken("magisk", "supersu", "superuser.apk", "/system/bin/su", "/system/xbin/su", "/sbin/su", "com.topjohnwu.magisk", "eu.chainfire.supersu", "busybox") + anyType("Lcom/scottyab/rootbeer/")).distinct().let {
            if (it.isNotEmpty()) out += Defense("Root detection", "INFERRED", it.take(4), "MASVS-RESILIENCE-1")
        }
        anyToken("ro.kernel.qemu", "goldfish", "ranchu", "generic_x86", "genymotion", "vbox86", "/dev/socket/qemud", "bluestacks", "nox-android").let {
            if (it.isNotEmpty()) out += Defense("Emulator detection", "INFERRED", it.take(4), "MASVS-RESILIENCE-1")
        }
        (anyToken("tracerpid", "/proc/self/status") + methods.filter { it == "isDebuggerConnected" || it == "waitForDebugger" }.map { "$it()" }).distinct().let {
            // isDebuggerConnected alone is ambiguous (logging); report only with a second indicator.
            if (it.size >= 2 || it.any { s -> s.startsWith("tracerpid") || s.startsWith("/proc") }) out += Defense("Anti-debugging", "INFERRED", it.take(4), "MASVS-RESILIENCE-4")
        }
        anyToken("re.frida.server", "frida-server", "gum-js-loop", "libfrida", "frida-gadget", "de.robv.android.xposed", "xposedbridge", "io.github.lsposed", "lsposed", "com.saurik.substrate", "magiskhide").let {
            if (it.isNotEmpty()) out += Defense("Hooking framework detection (Frida/Xposed)", "INFERRED", it.take(4), "MASVS-RESILIENCE-4")
        }
        // TLS pinning: OkHttp CertificatePinner, TrustKit, or a literal pin (sha256/<base64>).
        val pinEvidence = ArrayList<String>()
        if (types.any { it.contains("okhttp3/CertificatePinner") }) pinEvidence += "okhttp3.CertificatePinner"
        if (types.any { it.contains("com/datatheorem/android/trustkit") || it.contains("appmattus/certificatetransparency") }) pinEvidence += "pinning library"
        val pinRegex = Regex("""^sha256/[A-Za-z0-9+/]{43}=$""")
        if (strings.any { pinRegex.matches(it) }) pinEvidence += "pinned public-key hash (sha256/…)"
        if (pinEvidence.isNotEmpty()) out += Defense("TLS certificate pinning", if (pinEvidence.any { it.startsWith("pinned") }) "OBSERVED" else "INFERRED", pinEvidence.take(4), "MASVS-NETWORK-4")
        // Integrity / tamper check: a distinctive signal, not a generic crypto error ("invalid
        // signature") or reading package info for the version.
        val tamper = anyToken("re-signed", "apk tampered", "signaturecheck") +
            methods.filter { it == "checkSignatures" || it == "getSigningCertificateHistory" }.map { "$it()" } +
            anyType("Lcom/android/vending/licensing/")
        if (tamper.isNotEmpty()) out += Defense("Signature / tamper / licensing check", "INFERRED", tamper.take(4), "MASVS-RESILIENCE-3")
        // Device attestation.
        val attest = anyType("Lcom/google/android/play/core/integrity/", "Lcom/google/android/gms/safetynet/") + anyToken("play-integrity", "safetynetapi")
        if (attest.isNotEmpty()) out += Defense("Play Integrity / SafetyNet attestation", "INFERRED", attest.take(4), "MASVS-RESILIENCE-3")
        return out
    }

    // ---- Obfuscation level ----------------------------------------------------------------

    private val LIBRARY_PREFIXES = listOf(
        "Landroid/", "Landroidx/", "Lkotlin/", "Lkotlinx/", "Lcom/google/", "Lokhttp3/", "Lokio/", "Lretrofit2/", "Lio/reactivex/",
        "Lcom/squareup/", "Ldagger/", "Ljavax/", "Lorg/jetbrains/", "Lorg/intellij/", "Lcom/facebook/", "Lio/grpc/", "Lorg/apache/",
        "Lcom/bumptech/", "Lio/ktor/", "Lorg/json/", "Lcom/fasterxml/", "Lorg/slf4j/", "Lio/sentry/", "Lcom/android/", "Ljava/", "Lj$/",
    )

    private fun obfuscation(types: Set<String>, dex: List<Pair<String, DexFile>>): Obfuscation {
        val app = types.filter { it.startsWith("L") && LIBRARY_PREFIXES.none { p -> it.startsWith(p) } }
        val considered = app.filter { !it.contains("$") } // skip nested/synthetic classes
        if (considered.size < 20) return Obfuscation("UNKNOWN", 0.0, null, "Too few app classes (${considered.size}) to judge obfuscation.")
        val minified = considered.count { t ->
            val simple = t.removeSuffix(";").substringAfterLast('/')
            simple.length <= 2 && simple.all { it.isLetterOrDigit() }
        }
        val ratio = minified.toDouble() / considered.size
        val tool = CompilerMarker.read(dex).firstOrNull { it.r8Mode != null || it.tool.contains("R8", true) || it.tool.contains("D8", true) }?.let {
            if (it.r8Mode != null) "R8 (${it.r8Mode})" else it.tool
        }
        val level = when {
            ratio >= 0.6 -> "HEAVY"
            ratio >= 0.25 -> "PARTIAL"
            ratio >= 0.05 -> "LIGHT"
            else -> "NONE"
        }
        val evidence = "${(ratio * 100).toInt()}% of ${considered.size} app classes have 1–2 character names" + (tool?.let { "; built with $it" } ?: "")
        return Obfuscation(level, ratio, tool, evidence)
    }
}
