package dev.apkproject.app

import dev.apkproject.dex.DexFile
import kotlinx.serialization.Serializable

/** How much source a layer can yield, stated before any tool runs. */
@Serializable
enum class Recoverability {
    /** The original text ships in the APK (web assets, plain JavaScript, resources). */
    SOURCE_PRESENT,

    /** Bytecode that decompiles to readable source (DEX, plain JS bundles, .NET IL). */
    DECOMPILABLE,

    /** Machine code or engine bytecode: structure and symbols only, not original source. */
    BINARY_ONLY,
}

@Serializable
data class FrameworkFinding(
    val id: String,
    val name: String,
    val recoverability: Recoverability,
    val evidence: List<String>,
    val note: String,
)

@Serializable
data class NativeLibraryEntry(val path: String, val abi: String, val bytes: Long)

@Serializable
data class LibraryVersion(val coordinate: String, val version: String, val evidence: String)

@Serializable
data class AppAnatomy(
    val inputKind: String,
    val parts: List<String>,
    val dexFiles: List<String>,
    val classCount: Int,
    val methodCount: Int,
    val frameworks: List<FrameworkFinding>,
    val nativeLibraries: List<NativeLibraryEntry>,
    val webAssetRoots: List<String>,
    val javaScriptBundles: List<String>,
    val libraries: List<LibraryVersion>,
)

/** Detects which runtimes an APK carries, from archive paths, file magic and DEX type names. */
object AnatomyDetector {
    private val HERMES_MAGIC = byteArrayOf(0xc6.toByte(), 0x1f, 0xbc.toByte(), 0x03, 0xc1.toByte(), 0x03, 0x19, 0x1f)

    fun detect(input: AppInput, dex: List<Pair<String, DexFile>>): AppAnatomy {
        val entries = input.allEntries()
        val names = entries.map { it.second.name }
        fun any(pred: (String) -> Boolean) = names.filter(pred)
        val findings = ArrayList<FrameworkFinding>()
        val types = dex.flatMap { (_, d) -> d.typeIds.indices.asSequence().map { d.type(it) } }.toHashSet()
        fun hasType(prefix: String) = types.any { it.startsWith(prefix) }

        val classCount = dex.sumOf { it.second.classDefs.size }
        if (classCount > 0) {
            val kotlin = any { it.startsWith("kotlin/") && it.endsWith(".kotlin_builtins") || it.startsWith("META-INF/") && it.endsWith(".kotlin_module") } +
                (if (hasType("Lkotlin/Metadata;")) listOf("DEX type kotlin.Metadata") else emptyList())
            if (kotlin.isNotEmpty()) {
                findings += FrameworkFinding("kotlin", "Kotlin", Recoverability.DECOMPILABLE, kotlin.take(5), "Kotlin compiles to the same DEX bytecode; it is recovered as Java source with Kotlin metadata names where present.")
            } else {
                findings += FrameworkFinding("java", "Java", Recoverability.DECOMPILABLE, dex.map { it.first }, "DEX bytecode decompiles to Java source.")
            }
            if (hasType("Landroidx/compose/runtime/")) {
                findings += FrameworkFinding("compose", "Jetpack Compose", Recoverability.DECOMPILABLE, listOf("DEX types androidx.compose.runtime.*"), "Compose UI is code, not XML: it is recovered as decompiled composable functions.")
            }
        }
        val flutter = any { it.matches(Regex("""lib/[^/]+/lib(flutter|app)\.so""")) || it.startsWith("assets/flutter_assets/") }
        if (flutter.isNotEmpty()) {
            findings += FrameworkFinding(
                "flutter", "Flutter", Recoverability.BINARY_ONLY, flutter.take(5),
                "Dart code is compiled ahead of time to machine code in libapp.so; Dart source cannot be recovered. Assets under flutter_assets are extracted as-is.",
            )
        }
        val rnBundles = any { it.endsWith(".android.bundle") || it == "assets/index.bundle" }
        val rnLibs = any { it.matches(Regex("""lib/[^/]+/lib(reactnativejni|hermes|jsc|reactnative)\.so""")) }
        val hermesBundles = ArrayList<String>()
        val plainBundles = ArrayList<String>()
        for ((part, e) in entries) {
            if (e.name in rnBundles) {
                val head = part.read(e).copyOf(8)
                if (head.contentEquals(HERMES_MAGIC)) hermesBundles += e.name else plainBundles += e.name
            }
        }
        if (rnBundles.isNotEmpty() || rnLibs.isNotEmpty()) {
            val hermes = hermesBundles.isNotEmpty()
            findings += FrameworkFinding(
                if (hermes) "react-native-hermes" else "react-native", if (hermes) "React Native (Hermes bytecode)" else "React Native",
                if (hermes) Recoverability.BINARY_ONLY else Recoverability.DECOMPILABLE, (rnBundles + rnLibs).take(5),
                if (hermes) "The JavaScript bundle is Hermes bytecode; this toolchain does not decompile Hermes. Native and Java layers are still recovered."
                else "The JavaScript bundle ships as text; modules are recovered with REA + Wakaru.",
            )
        }
        val webRoots = listOf("assets/www/", "assets/public/").filter { root -> names.any { it.startsWith(root) } }
        if (names.any { it.startsWith("assets/www/") && (it.endsWith("cordova.js") || it.endsWith("cordova_plugins.js")) }) {
            findings += FrameworkFinding("cordova", "Apache Cordova / Ionic", Recoverability.SOURCE_PRESENT, any { it.startsWith("assets/www/") }.take(5), "The web front end ships as HTML/CSS/JavaScript and is copied verbatim.")
        }
        if (names.any { it == "assets/capacitor.config.json" } || names.any { it.startsWith("assets/public/") && it.endsWith("index.html") }) {
            findings += FrameworkFinding("capacitor", "Capacitor / Ionic", Recoverability.SOURCE_PRESENT, any { it.startsWith("assets/public/") || it == "assets/capacitor.config.json" }.take(5), "The web front end ships as HTML/CSS/JavaScript and is copied verbatim.")
        }
        val dotnet = any { it.startsWith("assemblies/") || it.matches(Regex("""lib/[^/]+/lib(monodroid|monosgen-2\.0|xamarin-app|assemblies\..*)\.so""")) }
        if (dotnet.isNotEmpty()) {
            findings += FrameworkFinding("xamarin", "Xamarin / .NET MAUI", Recoverability.DECOMPILABLE, dotnet.take(5), ".NET assemblies are extracted; C# decompilation needs a .NET decompiler (not bundled). REA's managed-code tools can inventory them.")
        }
        val il2cpp = any { it.endsWith("/libil2cpp.so") || it.endsWith("global-metadata.dat") }
        val unityMono = any { it.startsWith("assets/bin/Data/Managed/") && it.endsWith(".dll") }
        if (il2cpp.isNotEmpty()) {
            findings += FrameworkFinding("unity-il2cpp", "Unity (IL2CPP)", Recoverability.BINARY_ONLY, il2cpp.take(5), "C# was converted to C++ and compiled to machine code; only metadata (types and method names) can be recovered.")
        } else if (unityMono.isNotEmpty()) {
            findings += FrameworkFinding("unity-mono", "Unity (Mono)", Recoverability.DECOMPILABLE, unityMono.take(5), "Managed assemblies are extracted; C# decompilation needs a .NET decompiler (not bundled).")
        }
        val natives = entries.filter { (_, e) -> e.name.matches(Regex("""(?:[^/]+/)?lib/[^/]+/[^/]+\.so""")) }.map { (_, e) ->
            NativeLibraryEntry(e.name, e.name.substringBeforeLast('/').substringAfterLast('/'), e.uncompressedSize)
        }
        if (natives.isNotEmpty() && findings.none { it.id in setOf("flutter", "unity-il2cpp") }) {
            findings += FrameworkFinding("native", "Native code (C/C++)", Recoverability.BINARY_ONLY, natives.map { it.path }.take(5), "Native libraries are mapped to their JNI entry points; pseudocode needs a native disassembler (REA with Ghidra or Hopper).")
        }
        val jsBundles = plainBundles + webRoots.flatMap { root ->
            entries.filter { (_, e) -> e.name.startsWith(root) && e.name.endsWith(".js") && e.uncompressedSize >= 20_000 && !e.name.endsWith("cordova.js") }.map { it.second.name }
        }
        return AppAnatomy(
            inputKind = input.kind.name,
            parts = input.parts.map { it.name },
            dexFiles = dex.map { it.first },
            classCount = classCount,
            methodCount = dex.sumOf { it.second.methods().size },
            frameworks = findings,
            nativeLibraries = natives,
            webAssetRoots = webRoots,
            javaScriptBundles = jsBundles.distinct(),
            libraries = LibraryCatalog.read(input),
        )
    }
}

/**
 * Library identities observed in the archive (dossier section 17, "library boundary"): AndroidX and
 * Kotlin libraries ship `META-INF/<group>_<artifact>.version`; some JARs keep Maven pom.properties.
 */
object LibraryCatalog {
    fun read(input: AppInput): List<LibraryVersion> {
        val out = LinkedHashMap<String, LibraryVersion>()
        for ((part, e) in input.allEntries()) {
            val name = e.name
            if (e.uncompressedSize > 4096) continue
            if (name.startsWith("META-INF/") && name.endsWith(".version") && name.indexOf('/', 9) < 0) {
                val base = name.removePrefix("META-INF/").removeSuffix(".version")
                val version = String(part.read(e), Charsets.UTF_8).trim()
                if (version.isEmpty() || version.length > 64) continue
                val coordinate = if (base.startsWith("kotlinx_")) {
                    "org.jetbrains.kotlinx:" + base.replace('_', '-')
                } else if ('_' in base) {
                    val group = base.substringBefore('_')
                    "$group:${base.substringAfter('_')}"
                } else {
                    base
                }
                out.putIfAbsent(coordinate, LibraryVersion(coordinate, version, name))
            } else if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                val props = java.util.Properties().apply { load(part.read(e).inputStream()) }
                val g = props.getProperty("groupId") ?: continue
                val a = props.getProperty("artifactId") ?: continue
                val v = props.getProperty("version") ?: continue
                out.putIfAbsent("$g:$a", LibraryVersion("$g:$a", v, name))
            }
        }
        return out.values.sortedBy { it.coordinate }
    }
}
