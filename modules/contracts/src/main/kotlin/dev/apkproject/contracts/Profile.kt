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
)

/** A profile with placeholders resolved against a concrete environment. */
data class ResolvedProfile(
    val profile: ToolchainProfile,
    val profileHash: String,
    val jdkHome: Path,
    val d8Jar: Path,
    val d8Lib: Path,
    val z3: Path,
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
        if (!vars.containsKey("JDK17_HOME")) findJdk(profile.jdk.expectedMajor)?.let { vars["JDK17_HOME"] = it.toString() }
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
        )
    }

    /** `<repo>/.tools` when the profile lives in `<repo>/profiles`. */
    private fun defaultToolsDir(profilePath: Path): Path {
        val abs = profilePath.toAbsolutePath().normalize()
        val repo = abs.parent?.parent ?: abs.parent
        return repo.resolve(".tools")
    }

    /** Finds a JDK of the requested major version: the running JVM first, then common install roots. */
    fun findJdk(major: Int): Path? {
        val running = Paths.get(System.getProperty("java.home"))
        if (Runtime.version().feature() == major && Files.isExecutable(running.resolve("bin/javac"))) return running
        val roots = listOf("/usr/lib/jvm", "/Library/Java/JavaVirtualMachines", "C:\\Program Files\\Java")
        for (root in roots) {
            val dir = Paths.get(root)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                val match = stream.sorted().filter { p ->
                    val name = p.fileName.toString()
                    (name.contains("-$major") || name.contains("jdk$major") || name.contains("jdk-$major")) &&
                        (Files.isRegularFile(p.resolve("bin/javac")) || Files.isRegularFile(p.resolve("Contents/Home/bin/javac")))
                }.findFirst().orElse(null)
                if (match != null) {
                    return if (Files.isDirectory(match.resolve("Contents/Home"))) match.resolve("Contents/Home") else match
                }
            }
        }
        return null
    }

    private val PLACEHOLDER = Regex("""\$\{([A-Z0-9_]+)}""")
}

fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
