package dev.apkproject.contracts

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class JdkSpec(val home: String, val expectedMajor: Int)

@Serializable
data class JavacSpec(val release: Int, val flags: List<String>)

@Serializable
data class D8Spec(
    val jar: String,
    val sha256: String,
    val version: String,
    val mode: String,
    val minApi: Int,
    val lib: String,
)

@Serializable
data class Z3Spec(val executable: String, val sha256: String, val version: String)

@Serializable
data class DexSpec(val supportedVersions: List<String>)

/** Whole-application decompiler (the engine REA wraps for APKs). Output is a generator, never acceptance. */
@Serializable
data class JadxSpec(val jar: String, val sha256: String, val version: String, val args: List<String> = emptyList(), val timeoutMs: Long = 1_800_000)

/** Eclipse compiler: per-file diagnostics in one pass, class files even when some methods fail. */
@Serializable
data class EcjSpec(val jar: String, val sha256: String, val version: String)

/** Where verified library artifacts (AAR/JAR from Google Maven and Maven Central) are cached. */
@Serializable
data class LibrarySpec(val cache: String, val repositories: List<String>)

/** Android platform library for compile checks of recovered application code. */
@Serializable
data class AndroidPlatformSpec(val androidJar: String, val sha256: String, val apiLevel: Int)

/** REA (Reverse Engineer Anything) CLI and its pinned Android engine, used for cross-checked evidence. */
@Serializable
data class ReaSpec(
    val executable: String,
    val version: String,
    val engineJar: String,
    val engineSha256: String,
    /** Wakaru, the JavaScript decompiler REA drives for `recover-javascript-sources`. */
    val wakaru: String? = null,
    val wakaruSha256: String? = null,
    val timeoutMs: Long = 300_000,
)

/** A versioned toolchain profile (sections 04-05). Paths may contain `${VAR}` placeholders. */
@Serializable
data class ToolchainProfile(
    val schemaVersion: Int,
    val id: String,
    val description: String,
    val jdk: JdkSpec,
    val javac: JavacSpec,
    val d8: D8Spec,
    val z3: Z3Spec,
    val dex: DexSpec,
    val jadx: JadxSpec? = null,
    val android: AndroidPlatformSpec? = null,
    val rea: ReaSpec? = null,
    val ecj: EcjSpec? = null,
    val libraries: LibrarySpec? = null,
)

/** A profile with placeholders resolved against a concrete environment. */
data class ResolvedProfile(
    val profile: ToolchainProfile,
    val profileHash: String,
    val jdkHome: Path,
    val d8Jar: Path,
    val d8Lib: Path,
    val z3: Path,
    val jadxJar: Path? = null,
    val androidJar: Path? = null,
    val reaExecutable: Path? = null,
    val reaEngineJar: Path? = null,
    val wakaru: Path? = null,
    val ecjJar: Path? = null,
    val libraryCache: Path? = null,
) {
    val javac: Path get() = jdkHome.resolve("bin").resolve(exe("javac"))
    val java: Path get() = jdkHome.resolve("bin").resolve(exe("java"))

    private fun exe(name: String) = if (isWindows()) "$name.exe" else name
}

class ProfileException(message: String) : Exception(message)

object Profiles {
    fun load(path: Path, env: Map<String, String> = System.getenv()): ResolvedProfile {
        if (!Files.isRegularFile(path)) throw ProfileException("profile not found: $path")
        val bytes = Files.readAllBytes(path)
        val profile = try {
            Json.decode<ToolchainProfile>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProfileException("profile $path is not a valid toolchain profile: ${e.message}")
        }
        if (profile.schemaVersion != 1) throw ProfileException("unsupported profile schema ${profile.schemaVersion}")
        val vars = HashMap(env)
        vars.putIfAbsent("APKPROJECT_TOOLS", defaultToolsDir(path).toString())
        if (!vars.containsKey("JDK17_HOME")) findJdk(profile.jdk.expectedMajor, env)?.let { vars["JDK17_HOME"] = it.toString() }
        fun resolve(value: String): Path {
            val out = PLACEHOLDER.replace(value) { m ->
                vars[m.groupValues[1]] ?: throw ProfileException(
                    "profile ${profile.id} needs \${${m.groupValues[1]}}; set the environment variable or install the tool",
                )
            }
            return Paths.get(out)
        }
        return ResolvedProfile(
            profile = profile,
            profileHash = Hashing.sha256(bytes),
            jdkHome = resolve(profile.jdk.home),
            d8Jar = resolve(profile.d8.jar),
            d8Lib = resolve(profile.d8.lib),
            z3 = resolve(profile.z3.executable),
            jadxJar = profile.jadx?.let { resolve(it.jar) },
            androidJar = profile.android?.let { resolve(it.androidJar) },
            reaExecutable = profile.rea?.let { resolve(it.executable) },
            reaEngineJar = profile.rea?.let { resolve(it.engineJar) },
            wakaru = profile.rea?.wakaru?.let { resolve(it) },
            ecjJar = profile.ecj?.let { resolve(it.jar) },
            libraryCache = profile.libraries?.let { resolve(it.cache) },
        )
    }

    /** `<repo>/.tools` when the profile lives in `<repo>/profiles`. */
    private fun defaultToolsDir(profilePath: Path): Path {
        val abs = profilePath.toAbsolutePath().normalize()
        val repo = abs.parent?.parent ?: abs.parent
        return repo.resolve(".tools")
    }

    /**
     * Finds a JDK of the requested major version: the running JVM (a desktop install bundles one),
     * then `JAVA_HOME`, then common install roots on Linux, macOS and Windows.
     */
    fun findJdk(major: Int, env: Map<String, String> = System.getenv()): Path? {
        val javac = if (isWindows()) "javac.exe" else "javac"
        fun jdk(p: Path): Path? = when {
            Files.isRegularFile(p.resolve("bin").resolve(javac)) -> p
            Files.isRegularFile(p.resolve("Contents/Home/bin").resolve(javac)) -> p.resolve("Contents/Home")
            else -> null
        }
        val running = Paths.get(System.getProperty("java.home"))
        if (Runtime.version().feature() == major) jdk(running)?.let { return it }
        env["JAVA_HOME"]?.let { home -> jdk(Paths.get(home))?.takeIf { releaseMajor(it) == major }?.let { return it } }
        val roots = listOf(
            "/usr/lib/jvm", "/Library/Java/JavaVirtualMachines",
            "C:\\Program Files\\Java", "C:\\Program Files\\Eclipse Adoptium", "C:\\Program Files\\Microsoft",
            "C:\\Program Files\\Zulu", "C:\\Program Files\\Amazon Corretto",
        )
        for (root in roots) {
            val dir = Paths.get(root)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                stream.sorted().toList().firstNotNullOfOrNull { p ->
                    val name = p.fileName.toString()
                    if (name.contains("-$major") || name.contains("jdk$major") || name.contains("jdk-$major") || name.contains("jdk_$major")) jdk(p) else null
                }?.let { return it }
            }
        }
        return null
    }

    /** JAVA_VERSION="17.0.12" in a JDK's `release` file. */
    private fun releaseMajor(jdk: Path): Int? = runCatching {
        Regex("""JAVA_VERSION="(\d+)""").find(Files.readString(jdk.resolve("release")))?.groupValues?.get(1)?.toInt()
    }.getOrNull()

    private val PLACEHOLDER = Regex("""\$\{([A-Z0-9_]+)}""")
}

fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
